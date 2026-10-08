package com.ai.assistance.operit.features.reading

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

data class ReadingRefreshResult(
    val state: ReadingState,
    val indexedChapters: Int,
    val remainingCompletedChapters: Int,
    val summarizedChapters: Int,
    val remainingKnowledgeChapters: Int,
    val currentChapterIndexedUntil: Int?,
)

private data class FreshFileSummary(
    val chapter: ReaderChapter,
    val summary: String,
    val path: String,
    val contentHash: String,
    val contentHashKind: String,
)

private data class SummaryBatchCandidateScan(
    val totalInRange: Int,
    val existingInRange: Int,
    val candidates: List<ReaderChapter>,
    val selectionFailures: List<JSONObject>,
    val stopped: Boolean,
)

class ReadingCompanionService private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val provider: ReaderProvider = LegadoReaderProvider(appContext)
    private val store = ReadingCompanionStore(appContext)
    private val modelGateway = ReadingCompanionModelGateway(appContext)
    private val manualSummaryMutex = Mutex()
    @Volatile
    private var manualSummaryBatchActive = false
    @Volatile
    private var manualSummaryStopRequested = false
    @Volatile
    private var activeManualSummaryBatchId: String? = null

    suspend fun listBooks(): JSONObject {
        val selectedId = store.getSelectedBookId()
        val books = provider.getBooks().sortedByDescending(ReaderBook::lastReadAt)
        return JSONObject().apply {
            put("selectionMode", if (selectedId == null) "automatic_recent" else "manual")
            put("selectedBookId", selectedId)
            put(
                "books",
                JSONArray().apply {
                    books.forEach { book ->
                        put(
                            JSONObject()
                                .put("bookId", book.id)
                                .put("name", book.name)
                                .put("author", book.author)
                                .put("totalChapterCount", book.totalChapterCount)
                                .put("lastReadAt", book.lastReadAt)
                                .put("selected", book.id == selectedId)
                        )
                    }
                },
            )
        }
    }

    suspend fun selectBook(identifier: String?, automatic: Boolean): ReadingState {
        if (automatic) {
            store.setSelectedBookId(null)
            val state = provider.getReadingState()
            store.setLastResolvedBookId(state.book.id)
            return state
        }
        val query = identifier.orEmpty().trim()
        require(query.isNotBlank()) { "请选择书籍名称或 book_id" }
        val books = provider.getBooks()
        val exactMatches = books.filter {
            it.id == query || it.name.equals(query, ignoreCase = true)
        }
        val matches = if (exactMatches.isNotEmpty()) exactMatches else books.filter {
            it.name.contains(query, ignoreCase = true)
        }
        require(matches.isNotEmpty()) { "书架中没有找到“$query”" }
        require(matches.size == 1) {
            "匹配到多本书，请改用完整书名或 book_id：${matches.take(5).joinToString { it.name }}"
        }
        val selected = matches.single()
        val state = provider.getReadingState(selected.id)
        store.setSelectedBookId(selected.id)
        store.setLastResolvedBookId(selected.id)
        synchronizeBoundary(state)
        return state
    }

    suspend fun currentBook(bookId: String? = null): ReadingState =
        selectedReadingState(bookId?.trim()?.takeIf(String::isNotBlank))

    suspend fun persistedSummaryFiles(): JSONObject {
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        // The summaries page is a local browse surface and must never block on a live Legado
        // connection. Read everything from the persisted catalogs first; when Legado is
        // reachable, refresh the catalog so chapter inserts/moves are reflected next time.
        // An explicit selection wins; otherwise the last successfully resolved book id keeps
        // working offline.  Never fall back to a fabricated placeholder id.
        val localBookId = resolveLocalBookId()
        val local = if (localBookId != null) {
            fileStore.listSummaryFiles(localBookId)
        } else {
            JSONObject()
                .put("summaries", JSONArray())
                .put("currentChapterNumber", -1)
                .put("staleCatalog", true)
                .put("noBook", true)
        }
        try {
            val refreshed = withTimeoutOrNull(SUMMARY_LIST_CATALOG_REFRESH_BUDGET_MS) {
                val state = selectedReadingState()
                val chapters = provider.getChapters(state.book.id)
                synchronizeCatalog(fileStore, state, chapters)
                store.setLastResolvedBookId(state.book.id)
                fileStore.listSummaryFiles(state.book.id)
            }
            if (refreshed != null) {
                return refreshed.put("staleCatalog", false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Fall through to the local listing below.
        }
        return local.put("staleCatalog", true)
    }

    /**
     * Resolves the book workspace to address for local-only surfaces.  An explicit manual
     * selection wins; otherwise the last successfully resolved book (manual or automatic recent)
     * is reused so offline browsing keeps working.  Returns null only when no book has ever been
     * resolved, in which case callers return an empty result instead of creating placeholder data.
     */
    private fun resolveLocalBookId(): String? =
        store.getSelectedBookId() ?: store.getLastResolvedBookId()

    /**
     * Paginated read-only browser for the current book's durable files.
     */
    suspend fun listPersistedFiles(
        offset: Int = 0,
        limit: Int = PERSISTED_FILES_DEFAULT_LIMIT,
        callerRoleCardId: String? = null,
    ): JSONObject {
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        // Keep the browser's stable book-level documents visible even on a freshly selected book.
        // This mirrors get_local_files and does not expose any writable content surface.
        val bookId = resolveLocalBookId()
        if (bookId != null) {
            fileStore.ensureCharactersDocument(bookId)
            callerRoleCardId
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { fileStore.ensureCompanionMemory(bookId, it) }
        }
        try {
            val refreshed = withTimeoutOrNull(LIST_FILES_CATALOG_REFRESH_BUDGET_MS) {
                val state = selectedReadingState()
                val chapters = provider.getChapters(state.book.id)
                synchronizeCatalog(fileStore, state, chapters)
                store.setLastResolvedBookId(state.book.id)
                fileStore.listPersistedFiles(
                    book = state.book,
                    chapters = chapters,
                    offset = offset,
                    limit = limit,
                )
            }
            if (refreshed != null) {
                return refreshed.put("staleCatalog", false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Fall through to the cached listing below.
        }
        if (bookId != null) {
            return fileStore.listPersistedFilesFromCatalogs(
                bookId = bookId,
                offset = offset,
                limit = limit,
            ).put("staleCatalog", true)
        }
        return JSONObject()
            .put("bookId", JSONObject.NULL)
            .put("book", "")
            .put("rootPath", "")
            .put("offset", offset.coerceAtLeast(0))
            .put("limit", limit)
            .put("total", 0)
            .put("entries", JSONArray())
            .put("nextOffset", JSONObject.NULL)
            .put("staleCatalog", true)
            .put("noBook", true)
    }

    /**
     * Read one file from the resolved book root.  ReadingCompanionFileStore performs canonical
     * path and filename checks; this service deliberately does not expose arbitrary filesystem
     * access to the ToolPkg.  A bounded best-effort catalog refresh keeps stale directories out,
     * but when Legado is unreachable the on-disk catalog membership check still protects reads.
     */
    suspend fun readPersistedFile(
        path: String,
        offset: Int = 0,
        maxCharacters: Int? = null,
    ): JSONObject {
        val bookId = resolveLocalBookId()
            ?: throw IllegalArgumentException("当前没有可用的书籍，请先在 Legado 打开一本书或在阅读伴侣中选择书籍")
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        try {
            val refreshedBookId = withTimeoutOrNull(READ_FILE_CATALOG_REFRESH_BUDGET_MS) {
                val state = selectedReadingState()
                val chapters = provider.getChapters(state.book.id)
                synchronizeCatalog(fileStore, state, chapters)
                store.setLastResolvedBookId(state.book.id)
                state.book.id
            }
            if (refreshedBookId != null) {
                return fileStore.readPersistedFile(
                    bookId = refreshedBookId,
                    path = path,
                    offset = offset,
                    maxCharacters = maxCharacters,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Fall through to the local read below.
        }
        return fileStore.readPersistedFile(
            bookId = bookId,
            path = path,
            offset = offset,
            maxCharacters = maxCharacters,
        )
    }

    suspend fun localBookFiles(callerRoleCardId: String?): JSONObject {
        val state = selectedReadingState()
        val chapters = provider.getChapters(state.book.id)
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        synchronizeCatalog(fileStore, state, chapters)
        val roleCardId = callerRoleCardId?.trim()?.takeIf(String::isNotBlank)
        return JSONObject()
            .put("book", state.book.name)
            .put("author", state.book.author)
            .put("currentChapterNumber", state.chapterIndex + 1)
            .put("cacheCoverage", fileStore.chapterCacheCoverage(state.book.id, state.chapterIndex))
            .put("bookRootPath", fileStore.bookRootPath(state.book.id))
            .put("bookMetadataPath", fileStore.bookMetadataPath(state.book.id))
            .put("chaptersRootPath", fileStore.chaptersRootPath(state.book.id))
            .put("charactersPath", fileStore.ensureCharactersDocument(state.book.id).absolutePath)
            .put("aiMemoryNotice", AI_MEMORY_NOTICE)
            .put(
                "companionMemoryPath",
                roleCardId
                    ?.let { fileStore.ensureCompanionMemory(state.book.id, it).absolutePath }
                    ?: JSONObject.NULL,
            )
            .put("catalogPaths", fileStore.catalogPaths(state.book.id))
            .put(
                "safeSearchPaths",
                fileStore.safeChapterSearchPaths(
                    bookId = state.book.id,
                    chapters = chapters,
                    beforeChapterIndex = state.chapterIndex,
                ),
            )
            .put(
                "allCurrentSearchPaths",
                fileStore.allCurrentChapterSearchPaths(
                    bookId = state.book.id,
                    chapters = chapters,
                ),
            )
            .put(
                "notice",
                "For ordinary recall, grep only safeSearchPaths. chaptersRootPath may include " +
                    "inactive chapter directories and must not be searched directly. When the user " +
                    "explicitly asks to inspect future content, grep allCurrentSearchPaths. " +
                    "content.md is the last successfully fetched snapshot; Legado content or " +
                    "cleanup rules may change later, and the snapshot refreshes only when the " +
                    "plugin actually processes that chapter again",
            )
    }

    suspend fun currentContext(
        maxCharacters: Int = DEFAULT_CONTEXT_CHARACTERS,
        callerRoleCardId: String? = null,
    ): JSONObject {
        val state = selectedReadingState()
        ReadingCompanionTasks.getInstance(appContext).cacheForAdvancedProgress(state)
        synchronizeBoundary(state)
        val content = provider.getReadableChapterContent(state.book.id, state.chapterIndex)
        val safeEnd = content.readableUntil.coerceIn(0, content.content.length)
        val requestedCharacters = maxCharacters.coerceIn(
            MIN_CONTEXT_CHARACTERS,
            MAX_CONTEXT_CHARACTERS,
        )
        // Reserve a bounded part of the requested budget for compact summaries, reader memories,
        // and the companion's own comments. The remaining budget is used for novel prose.
        val supplementalBudget = minOf(
            MAX_SUPPLEMENTAL_CONTEXT_CHARACTERS,
            requestedCharacters / 4,
        )
        val envelopeBudget = minOf(
            MAX_CONTEXT_ENVELOPE_CHARACTERS,
            requestedCharacters / 8,
        )
        val novelTextBudget =
            (requestedCharacters - supplementalBudget - envelopeBudget).coerceAtLeast(0)
        val currentTextBudget = minOf(CURRENT_CHAPTER_MAX_CHARACTERS, novelTextBudget)
        val start = (safeEnd - currentTextBudget).coerceAtLeast(0)
        val currentText = content.content.substring(start, safeEnd)
        val previousTextBudget = (novelTextBudget - currentText.length).coerceAtLeast(0)
        val previousChapters = loadPreviousContext(
            state = state,
            maximumCharacters = previousTextBudget,
        )

        // Re-read the boundary after provider calls. If the reader moved backwards or switched
        // books during assembly, fail closed instead of returning a mixed or unsafe context.
        val latestState = selectedReadingState(state.book.id)
        failIfBoundaryChanged(state, latestState)
        synchronizeBoundary(latestState)
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        val chapters = provider.getChapters(state.book.id)
        val chapterByIndex = chapters.associateBy(ReaderChapter::index)
        require(chapterByIndex[content.chapterIndex]?.sourceId == content.sourceId) {
            "上下文组装期间当前章节目录发生变化"
        }
        previousChapters.forEach { previous ->
            require(chapterByIndex[previous.chapterIndex]?.sourceId == previous.sourceId) {
                "上下文组装期间前文章节目录发生变化"
            }
        }
        synchronizeCatalog(fileStore, state, chapters)
        // The context call itself is a valid first processing pass (for example, before the
        // incremental worker has run). Persist only the already-safe current prefix after the
        // sourceId/boundary checks above; previous chapters remain read-only provider context.
        fileStore.writeChapterContent(
            book = state.book,
            chapter = chapterByIndex.getValue(content.chapterIndex),
            sourceContent = content.content,
            contentHashKind = ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE,
        )

        val completeTextChapterIndices = buildSet {
            if (start == 0) add(content.chapterIndex)
            previousChapters
                .filterNot(AutoCommentContextChapter::excerptFromEnd)
                .forEach { add(it.chapterIndex) }
        }
        val fileSummaries = collectRecentFreshFileSummaries(
            state = latestState,
            limit = MAX_CONTEXT_CHAPTERS,
            includeCurrentReadableSummary = true,
            chaptersOverride = chapters,
            fileStoreOverride = fileStore,
        )
        val contextSummaries = mutableListOf<JSONObject>()
        fileSummaries.forEach { fileSummary ->
            val chapter = fileSummary.chapter
            val stored = store.getChapterKnowledge(state.book.id, chapter.index)
            val structuredVerified =
                stored != null &&
                    fileSummary.contentHashKind ==
                        ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE &&
                    fileSummary.contentHash == stored.contentHash &&
                    SpoilerGuard.isPositionAllowed(
                        stored.chapterIndex,
                        0,
                        stored.sourceEndPosition,
                        latestState,
                    )
            contextSummaries += JSONObject().apply {
                put("chapterIndex", chapter.index)
                put("chapterNumber", chapter.index + 1)
                put("chapterTitle", chapter.title)
                put("summaryPath", fileSummary.path)
                stored?.takeIf { structuredVerified }?.let {
                    put("sourceEndPos", it.sourceEndPosition)
                    put("completeChapter", it.isComplete)
                }
                // A full prose excerpt already carries the chapter text. Keep verified
                // structured facts while omitting duplicate prose for that chapter.
                if (chapter.index !in completeTextChapterIndices) {
                    put("summary", fileSummary.summary)
                }
                put(
                    "summaryOmittedBecauseTextIncluded",
                    chapter.index in completeTextChapterIndices,
                )
                if (structuredVerified) {
                    put("knowledge", compactKnowledgeJson(stored!!.structuredJson))
                }
            }
        }
        val distinctContextSummaries = contextSummaries.distinctBy { summary ->
            "${summary.optInt("chapterIndex")}:${summary.optString("summary")}:" +
                summary.optJSONObject("knowledge")?.toString().orEmpty()
        }

        val readerMemories = store.getRecentMemories(
            bookId = state.book.id,
            throughChapterIndex = latestState.chapterIndex,
            limit = MAX_CONTEXT_READER_MEMORIES,
        ).filter { memory ->
            memory.chapterIndex <= latestState.chapterIndex
        }
        val unlockedParagraphIndex =
            AutoCommentSupport.unlockedParagraphIndex(content.content, content.isComplete)
        val roleCardId = callerRoleCardId?.trim()?.takeIf(String::isNotBlank)
        val companionMemoryPath =
            roleCardId?.let { fileStore.ensureCompanionMemory(state.book.id, it).absolutePath }
        val charactersPath = fileStore.ensureCharactersDocument(state.book.id).absolutePath
        val companionComments =
            if (roleCardId == null) {
                emptyList()
            } else {
                readFreshCompanionComments(
                    state = latestState,
                    currentUnlockedParagraph = unlockedParagraphIndex,
                    roleCardId = roleCardId,
                    limit = MAX_CONTEXT_COMPANION_COMMENTS,
                )
            }

        val boundedSummaries = boundContextJson(
            entries = distinctContextSummaries,
            maximumCharacters = supplementalBudget * SUMMARY_BUDGET_FRACTION / 100,
        ) { entry, remaining ->
            entry.put(
                "summary",
                entry.optString("summary").take(remaining.coerceAtLeast(0)),
            )
        }
        val boundedMemories = boundContextMemories(
            memories = readerMemories,
            maximumCharacters = supplementalBudget * MEMORY_BUDGET_FRACTION / 100,
        )
        val boundedComments = boundContextComments(
            comments = companionComments,
            maximumCharacters = supplementalBudget * COMMENT_BUDGET_FRACTION / 100,
        )
        val metadataCharacters =
            boundedSummaries.sumOf { it.toString().length } +
                boundedMemories.sumOf { it.content.length } +
                boundedComments.sumOf { it.text.length }
        val novelCharacters =
            currentText.length + previousChapters.sumOf { it.content.length }
        val totalCharacters = novelCharacters + metadataCharacters
        val returnState = selectedReadingState(state.book.id)
        failIfBoundaryChanged(latestState, returnState)
        synchronizeBoundary(returnState)
        validateContextInputsStillCurrent(returnState, content, previousChapters)

        val result = JSONObject().apply {
            put("book", returnState.book.name)
            put("author", returnState.book.author)
            put("chapterIndex", content.chapterIndex)
            put("chapterNumber", content.chapterIndex + 1)
            put("chapterTitle", content.chapterTitle)
            put("startPos", start)
            put("endPos", safeEnd)
            put("visibleStartPos", content.visibleStart ?: JSONObject.NULL)
            put("visibleEndPos", content.visibleEnd ?: JSONObject.NULL)
            put("positionSource", if (content.visibleStart != null) "viewport" else "saved_reading_prefix")
            put("positionNotice", if (content.visibleStart != null)
                "The visible range is the displayed part of this chapter, not an eye-tracked reading cursor. Earlier text is context."
                else "Only a saved reading prefix is available. The current visible page is unknown; do not claim this is the current scene.")
            put("text", currentText)
            put(
                "previousChapters",
                JSONArray().apply {
                    previousChapters.forEach { chapter ->
                        put(
                            JSONObject()
                                .put("chapterIndex", chapter.chapterIndex)
                                .put("chapterNumber", chapter.chapterIndex + 1)
                                .put("chapterTitle", chapter.chapterTitle)
                                .put("text", chapter.content)
                                .put("excerptFromEnd", chapter.excerptFromEnd),
                        )
                    }
                },
            )
            put(
                "summaries",
                JSONArray().apply { boundedSummaries.forEach(::put) },
            )
            put(
                "readerMemories",
                JSONArray().apply {
                    boundedMemories.forEach { memory ->
                        put(
                            JSONObject()
                                .put("id", memory.id)
                                .put("chapterIndex", memory.chapterIndex)
                                .put("chapterNumber", memory.chapterIndex + 1)
                                .put("type", memory.type)
                                .put("content", memory.content)
                                .put("createdAt", memory.createdAt)
                                .put("source", "reader_memory")
                                .put("isNovelFact", false),
                        )
                    }
                },
            )
            put(
                "companionComments",
                JSONArray().apply {
                    boundedComments.forEach { comment ->
                        put(
                            comment.toCompanionCommentJson().apply {
                                put("source", "companion_comment")
                                put("isAiMemory", true)
                            },
                        )
                    }
                },
            )
            put(
                "memoryNotice",
                "readerMemories are reader-authored notes, reactions, questions, or predictions; " +
                    "they are not confirmed novel facts",
            )
            put(
                "companionMemoryNotice",
                "companionComments are this role's own unlocked AI commentary, not novel facts",
            )
            put("requestedCharacterBudget", requestedCharacters)
            put("contextCharacterCount", totalCharacters)
            put("novelCharacterCount", novelCharacters)
            put("metadataCharacterCount", metadataCharacters)
            put("previousChapterCount", previousChapters.size)
            put("summaryCount", boundedSummaries.size)
            put("readerMemoryCount", boundedMemories.size)
            put("companionCommentCount", boundedComments.size)
            put("capturedAt", content.capturedAt)
            put("boundary", if (content.visibleStart != null) "visible_page_end" else "read_prefix_only")
            put("readingCompanionFilesRoot", fileStore.rootPath())
            put("companionMemoryPath", companionMemoryPath ?: JSONObject.NULL)
            put("aiMemoryNotice", AI_MEMORY_NOTICE)
            put("charactersPath", charactersPath)
        }
        return enforceSerializedContextBudget(result, requestedCharacters)
    }

    private suspend fun loadPreviousContext(
        state: ReadingState,
        maximumCharacters: Int,
    ): List<AutoCommentContextChapter> {
        if (maximumCharacters <= 0 || state.chapterIndex <= 0) return emptyList()
        val chaptersNearestFirst = buildList {
            val firstChapter = maxOf(0, state.chapterIndex - MAX_CONTEXT_CHAPTERS)
            for (chapterIndex in (state.chapterIndex - 1) downTo firstChapter) {
                val chapter = try {
                    provider.getReadableChapterContent(state.book.id, chapterIndex)
                } catch (_: ReaderProviderException) {
                    continue
                }
                // The provider is expected to enforce this identity and boundary itself. Keep a
                // second local check here so a malformed provider response cannot enter context.
                if (
                    chapter.bookId != state.book.id ||
                    chapter.chapterIndex != chapterIndex ||
                    !chapter.isComplete
                ) {
                    continue
                }
                val safeEnd = chapter.readableUntil.coerceIn(0, chapter.content.length)
                val safeText = chapter.content.substring(0, safeEnd)
                if (safeText.isBlank()) continue
                add(
                    AnnotationChapterContent(
                        bookId = chapter.bookId,
                        sourceId = chapter.sourceId,
                        chapterIndex = chapter.chapterIndex,
                        chapterTitle = chapter.chapterTitle,
                        content = safeText,
                        contractHash = "",
                        capturedAt = chapter.capturedAt,
                    ),
                )
            }
        }
        return AutoCommentSupport.selectPreviousContext(
            chaptersNearestFirst = chaptersNearestFirst,
            maximumCharacters = maximumCharacters,
        )
    }

    private fun compactKnowledgeJson(rawJson: String): JSONObject {
        val knowledge = runCatching { JSONObject(rawJson) }.getOrElse { JSONObject() }
        // The summary is carried in its own field so the structured object does not repeat prose.
        knowledge.remove("summary")
        return knowledge
    }

    private fun boundContextJson(
        entries: List<JSONObject>,
        maximumCharacters: Int,
        trim: (JSONObject, Int) -> Unit,
    ): List<JSONObject> {
        var remaining = maximumCharacters.coerceAtLeast(0)
        if (remaining == 0) return emptyList()
        val bounded = mutableListOf<JSONObject>()
        entries.forEach { original ->
            if (remaining <= 0) return@forEach
            val entry = JSONObject(original.toString())
            val serializedLength = entry.toString().length
            if (serializedLength <= remaining) {
                bounded += entry
                remaining -= serializedLength
                return@forEach
            }
            trim(entry, remaining)
            val trimmedLength = entry.toString().length
            if (trimmedLength <= remaining && trimmedLength > 0) {
                bounded += entry
                remaining -= trimmedLength
            }
        }
        return bounded
    }

    private fun boundContextMemories(
        memories: List<ReaderMemory>,
        maximumCharacters: Int,
    ): List<ReaderMemory> {
        var remaining = maximumCharacters.coerceAtLeast(0)
        if (remaining == 0) return emptyList()
        val bounded = mutableListOf<ReaderMemory>()
        memories.forEach { memory ->
            if (remaining <= 0) return@forEach
            val content = memory.content.take(remaining)
            if (content.isBlank()) return@forEach
            bounded += memory.copy(content = content)
            remaining -= content.length
        }
        return bounded
    }

    private fun boundContextComments(
        comments: List<AutoCommentRecord>,
        maximumCharacters: Int,
    ): List<AutoCommentRecord> {
        var remaining = maximumCharacters.coerceAtLeast(0)
        if (remaining == 0) return emptyList()
        val bounded = mutableListOf<AutoCommentRecord>()
        comments.forEach { comment ->
            if (remaining <= 0) return@forEach
            val text = comment.text.take(remaining)
            if (text.isBlank()) return@forEach
            bounded += comment.copy(text = text)
            remaining -= text.length
        }
        return bounded
    }

    private fun enforceSerializedContextBudget(
        payload: JSONObject,
        maximumCharacters: Int,
    ): JSONObject {
        fun refreshCounts() {
            val previous = payload.optJSONArray("previousChapters") ?: JSONArray()
            val summaries = payload.optJSONArray("summaries") ?: JSONArray()
            val memories = payload.optJSONArray("readerMemories") ?: JSONArray()
            val comments = payload.optJSONArray("companionComments") ?: JSONArray()
            val novelCharacters =
                payload.optString("text").length +
                    (0 until previous.length()).sumOf { index ->
                        previous.optJSONObject(index)?.optString("text").orEmpty().length
                    }
            val metadataCharacters =
                summaries.toString().length +
                    memories.toString().length +
                    comments.toString().length
            payload.put("novelCharacterCount", novelCharacters)
            payload.put("metadataCharacterCount", metadataCharacters)
            payload.put("previousChapterCount", previous.length())
            payload.put("summaryCount", summaries.length())
            payload.put("readerMemoryCount", memories.length())
            payload.put("companionCommentCount", comments.length())
            payload.put("contextCharacterCount", 0)
            repeat(3) {
                payload.put("contextCharacterCount", payload.toString().length)
            }
        }

        fun currentLength(): Int {
            refreshCounts()
            return payload.toString().length
        }

        var serializedLength = currentLength()
        val optionalArrays = listOf("summaries", "readerMemories", "companionComments")
        optionalArrays.forEach { key ->
            val array = payload.optJSONArray(key) ?: return@forEach
            while (serializedLength > maximumCharacters && array.length() > 0) {
                array.remove(array.length() - 1)
                serializedLength = currentLength()
            }
        }

        val previous = payload.optJSONArray("previousChapters") ?: JSONArray()
        while (serializedLength > maximumCharacters && previous.length() > 0) {
            val oldest = previous.optJSONObject(0)
            val text = oldest?.optString("text").orEmpty()
            val overage = (serializedLength - maximumCharacters).coerceAtLeast(1)
            if (oldest != null && text.length > 256) {
                val removeCount = minOf(text.length - 128, overage + 64)
                oldest.put("text", text.drop(removeCount))
                oldest.put("excerptFromEnd", true)
            } else {
                previous.remove(0)
            }
            serializedLength = currentLength()
        }

        while (serializedLength > maximumCharacters) {
            val text = payload.optString("text")
            if (text.length <= 128) break
            val overage = (serializedLength - maximumCharacters).coerceAtLeast(1)
            val removeCount = minOf(text.length - 128, overage + 64)
            payload.put("text", text.drop(removeCount))
            payload.put("startPos", payload.optInt("startPos") + removeCount)
            serializedLength = currentLength()
        }
        if (serializedLength > maximumCharacters) {
            payload.remove("memoryNotice")
            payload.remove("companionMemoryNotice")
            currentLength()
        }
        return payload
    }

    suspend fun recentCompanionComments(
        limit: Int,
        callerRoleCardId: String?,
    ): JSONObject {
        val state = selectedReadingState()
        val content = provider.getReadableChapterContent(state.book.id, state.chapterIndex)
        val unlockedParagraphIndex =
            AutoCommentSupport.unlockedParagraphIndex(content.content, content.isComplete)
        val roleCardId = callerRoleCardId?.trim()?.takeIf(String::isNotBlank)
        val comments =
            roleCardId?.let {
                readFreshCompanionComments(
                    state = state,
                    currentUnlockedParagraph = unlockedParagraphIndex,
                    roleCardId = it,
                    limit = limit,
                )
            }.orEmpty()
        validateContextInputsStillCurrent(state, content, emptyList())
        return JSONObject().apply {
            put("book", state.book.name)
            put("roleCardId", roleCardId)
            put("boundary", "unlocked_comments_only")
            put(
                "comments",
                JSONArray().apply {
                    comments.forEach { comment -> put(comment.toCompanionCommentJson()) }
                },
            )
        }
    }

    /** Copies only complete, already-downloaded old chapters. Never invokes a model or download endpoint. */
    suspend fun cacheDownloadedChapters(bookId: String, onProgress: (JSONObject) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        val state = selectedReadingState(bookId)
        val chapters = provider.getChapters(bookId)
        val files = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        synchronizeCatalog(files, state, chapters)
        synchronizeBoundary(state)
        val old = chapters.filter { it.index < state.chapterIndex }.sortedBy { it.index }
        cacheDownloadedChapterSnapshots(old,
            hasCompleteSnapshot = { chapter -> store.isCompleteChapterIndexed(bookId, chapter.index) &&
                files.chapterFilePaths(bookId, chapter.sourceId)?.optString("contentPath")?.let { java.io.File(it).isFile } == true },
            readLocal = { provider.getCachedReadableChapterContent(bookId, it.index) },
            save = { chapter, content ->
                validateContentBeforeStore(state, content, chapter.sourceId)
                files.writeChapterContent(state.book, chapter, content.content,
                    ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE)
                store.replaceChapter(content)
            }, onProgress = onProgress,
        ).put("throughChapterIndex", state.chapterIndex - 1)
    }

    suspend fun refreshAndIndex(
        maxCompletedChapters: Int,
        @Suppress("UNUSED_PARAMETER") maxKnowledgeChapters: Int,
        scheduleMore: Boolean,
        runtime: ToolExecutionManager.ToolRuntimeContext? = null,
        bookId: String? = null,
    ): ReadingRefreshResult = withContext(Dispatchers.IO) {
        val state = selectedReadingState(bookId)
        val chapters = provider.getChapters(state.book.id)
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        synchronizeCatalog(fileStore, state, chapters)
        synchronizeBoundary(state)

        var indexed = 0
        var currentIndexedUntil: Int? = null
        if (state.bodyPosition != null) {
            runCatching {
                provider.getReadableChapterContent(state.book.id, state.chapterIndex)
            }.getOrNull()?.let { current ->
                validateContentBeforeStore(
                    state,
                    current,
                    chapters.firstOrNull { it.index == state.chapterIndex }?.sourceId,
                )
                val currentChapter =
                    chapters.firstOrNull {
                        it.index == current.chapterIndex && it.sourceId == current.sourceId
                    } ?: error("当前章节已从目录中移除")
                fileStore.writeChapterContent(
                    book = state.book,
                    chapter = currentChapter,
                    sourceContent = current.content,
                    contentHashKind = ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE,
                )
                store.replaceChapter(current)
                currentIndexedUntil = current.readableUntil
            }
        }

        val missingCompleted = chapters
            .asSequence()
            .filter { it.index < state.chapterIndex }
            .filterNot { store.isCompleteChapterIndexed(state.book.id, it.index) }
            .sortedByDescending(ReaderChapter::index)
            .toList()
        for (chapter in missingCompleted) {
            if (indexed >= maxCompletedChapters.coerceAtLeast(0)) break
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val content = provider.getCachedReadableChapterContent(state.book.id, chapter.index) ?: continue
            validateContentBeforeStore(state, content, chapter.sourceId)
            fileStore.writeChapterContent(
                book = state.book,
                chapter = chapter,
                sourceContent = content.content,
                contentHashKind = ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE,
            )
            store.replaceChapter(content)
            indexed += 1
        }
        val summaries = fileStore.listSummaryFiles(state.book.id).getJSONArray("summaries")
        repeat(summaries.length()) { index ->
            val item = summaries.getJSONObject(index)
            val chapterIndex = item.getInt("chapterNumber") - 1
            val chapter = chapters.firstOrNull { it.index == chapterIndex && !it.isVolume }
            if (chapterIndex <= state.chapterIndex && chapter != null &&
                item.optString("chapterRef") == ReadingCompanionFileStore.chapterRef(state.book.id, chapter.sourceId)) {
                val text = item.getString("summary")
                store.indexPublishedSummary(state.book.id, chapterIndex, chapter.sourceId, PublishedSummary(
                    text, item.optString("contentHash"), item.optString("contentHashKind"),
                    ReadingCompanionFileStore.contentHash(text), "file", false,
                ))
            }
        }
        val remainingText = (missingCompleted.size - indexed).coerceAtLeast(0)

        // Background refresh is intentionally indexing-only. Chapter summaries are a
        // user-requested operation and are generated by the dedicated commentary subagent path;
        // this method must never spend model calls while ordinary context/search is running.
        val summarized = 0
        val remainingKnowledge = store.countMissingKnowledge(
            state.book.id,
            state.chapterIndex,
        )
        val finalState = selectedReadingState(state.book.id)
        failIfBoundaryRegressed(state, finalState)
        synchronizeBoundary(finalState)
        // Knowledge/summary rows are intentionally never a background scheduling reason.  They
        // are generated only by an explicit manual summary batch;正文增量索引仍可继续后台运行。
        if (scheduleMore && remainingText > 0 && indexed > 0) {
            scheduleBackgroundIndex()
        }
        ReadingRefreshResult(
            state = state,
            indexedChapters = indexed,
            remainingCompletedChapters = remainingText,
            summarizedChapters = summarized,
            remainingKnowledgeChapters = remainingKnowledge,
            currentChapterIndexedUntil = currentIndexedUntil,
        )
    }

    suspend fun chapterSummary(
        chapterIndex: Int?,
        @Suppress("UNUSED_PARAMETER") generateIfMissing: Boolean = false,
        @Suppress("UNUSED_PARAMETER") runtime: ToolExecutionManager.ToolRuntimeContext? = null,
    ): JSONObject {
        val state = selectedReadingState()
        val targetIndex = chapterIndex ?: state.chapterIndex
        require(targetIndex in 0..state.chapterIndex) { "只能总结当前或已经读过的章节" }
        synchronizeBoundary(state)
        val chapters = provider.getChapters(state.book.id)
        val targetChapter = chapters.firstOrNull { it.index == targetIndex }
            ?: error("目标章节已不在目录中")
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        synchronizeCatalog(fileStore, state, chapters)
        readFreshFileSummary(
            state = state,
            chapter = targetChapter,
            includeCurrentReadableSummary = true,
            fileStore = fileStore,
        )?.let { fileSummary ->
            val stored = store.getChapterKnowledge(state.book.id, targetIndex)
            val structuredVerified =
                stored != null &&
                    fileSummary.contentHashKind ==
                        ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE &&
                    fileSummary.contentHash == stored.contentHash &&
                    SpoilerGuard.isPositionAllowed(
                        stored.chapterIndex,
                        0,
                        stored.sourceEndPosition,
                        state,
                    )
            return JSONObject()
                .put("book", state.book.name)
                .put("chapterIndex", targetIndex)
                .put("chapterNumber", targetIndex + 1)
                .put("chapterTitle", targetChapter.title)
                .put("summary", fileSummary.summary)
                .put("summaryPath", fileSummary.path)
                .put("generatedNow", false)
                .apply {
                    if (structuredVerified) {
                        put("sourceEndPos", stored!!.sourceEndPosition)
                        put("completeChapter", stored.isComplete)
                        put("knowledge", compactKnowledgeJson(stored.structuredJson))
                    }
                }
            }
        // Ordinary chapter-summary reads are deliberately side-effect free. Even when a legacy
        // caller passes generateIfMissing=true, generation must be initiated through an explicit
        // user manual action that runs the summary-only commentary subagent.
        return JSONObject()
            .put("book", state.book.name)
            .put("chapterIndex", targetIndex)
            .put("chapterNumber", targetIndex + 1)
            .put("status", "not_generated_or_stale")
            .put("manualGenerationRequired", true)
    }

    suspend fun recentSummaries(
        count: Int,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
    ): JSONObject {
        val state = selectedReadingState()
        synchronizeBoundary(state)
        val safeCount = count.coerceIn(1, 10)
        var summaries = collectRecentFreshFileSummaries(state, safeCount)
        if (summaries.size < safeCount) {
            refreshAndIndex(
                maxCompletedChapters = safeCount,
                maxKnowledgeChapters = minOf(2, safeCount - summaries.size),
                scheduleMore = true,
                runtime = runtime,
            )
            val latest = selectedReadingState(state.book.id)
            summaries = collectRecentFreshFileSummaries(latest, safeCount)
        }
        return JSONObject().apply {
            put("book", state.book.name)
            put(
                "summaries",
                JSONArray().apply {
                    summaries.forEach { fileSummary ->
                        put(
                            JSONObject()
                                .put("chapterIndex", fileSummary.chapter.index)
                                .put("chapterNumber", fileSummary.chapter.index + 1)
                                .put("chapterTitle", fileSummary.chapter.title)
                                .put("summary", fileSummary.summary)
                                .put("summaryPath", fileSummary.path)
                        )
                    }
                },
            )
        }
    }

    /**
     * Explicit user-triggered batch summary generation.
     *
     * Selection is made from complete chapters at or before the current reading boundary.  When
     * no range is supplied the newest chapters lacking a fresh persisted summary are selected.
     * A supplied range narrows the same candidate set; valid summaries are still skipped because
     * ordinary batch fill must not overwrite them.  Each selected chapter gets exactly one
     * summary-only subagent task.
     */
    suspend fun manualBatchSummaries(
        batchId: String,
        count: Int,
        startChapterIndex: Int?,
        endChapterIndex: Int?,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
        bookId: String? = null,
        onProgress: ((JSONObject) -> Unit)? = null,
    ): JSONObject {
        check(manualSummaryMutex.tryLock()) { "已有手动摘要批次正在生成，请等待完成后再试" }
        manualSummaryStopRequested = false
        activeManualSummaryBatchId = batchId
        manualSummaryBatchActive = true
        return try {
            withContext(Dispatchers.IO) {
                require(batchId.isNotBlank()) { "摘要批次标识不能为空" }
                require(count in 1..MAX_MANUAL_BATCH_BUDGET) {
                    "摘要批量数量必须为 1～$MAX_MANUAL_BATCH_BUDGET"
                }
                require(startChapterIndex == null || startChapterIndex >= 0) {
                    "摘要批量起始章节必须为非负索引"
                }
                require(endChapterIndex == null || endChapterIndex >= 0) {
                    "摘要批量结束章节必须为非负索引"
                }
                if (startChapterIndex != null && endChapterIndex != null) {
                    require(endChapterIndex >= startChapterIndex) {
                        "摘要批量结束章节不能早于起始章节"
                    }
                }

        val requestedBookId = bookId?.trim()?.takeIf(String::isNotBlank)
        val state = selectedReadingState(requestedBookId)
        if (requestedBookId != null && state.book.id != requestedBookId) {
            throw IllegalArgumentException("指定书籍当前不可用：$requestedBookId")
        }
        val chapters = provider.getChapters(state.book.id)
            .sortedByDescending(ReaderChapter::index)
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        synchronizeCatalog(fileStore, state, chapters)

        val rangeStart = startChapterIndex ?: 0
        val rangeEnd = endChapterIndex ?: state.chapterIndex
        val scan = scanSummaryBatchCandidates(
            state = state,
            chapters = chapters,
            fileStore = fileStore,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            limit = count,
        )
        val chaptersAfterScan = provider.getChapters(state.book.id)
        require(ReaderChapterCatalogSupport.hasSameIdentity(chapters, chaptersAfterScan)) {
            "Legado 目录在摘要候选扫描期间发生变化，请重试"
        }
        val candidates = scan.candidates
        val selectionFailures = scan.selectionFailures

        val processedTargets = mutableListOf<Int>()
        val results = JSONArray()
        val unavailable = JSONArray().apply {
            selectionFailures.forEach(::put)
        }
        val failures = JSONArray()
        var completedCount = 0
        var processedCount = 0
        fun emitProgress(chapterIndex: Int, status: String) {
            try {
                onProgress?.invoke(
                    JSONObject()
                        .put("bookId", state.book.id)
                        .put("batchId", batchId)
                        .put("chapterIndex", chapterIndex)
                        .put("status", status)
                        .put("processedCount", processedCount)
                        .put("completedCount", completedCount)
                        .put("failedCount", failures.length())
                        .put("unavailableCount", unavailable.length())
                        .put(
                            "targetChapterIndices",
                            JSONArray().apply { processedTargets.forEach(::put) },
                        ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Progress reporting is observational and must not invalidate a generated result.
            }
        }
        val coordinator = ReadingCompanionSubagentCoordinator.getInstance(appContext)
        for (chapter in candidates) {
            if (manualSummaryStopRequested) break
            val content =
                try {
                    provider.getReadableChapterContent(state.book.id, chapter.index)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    unavailable.put(unavailableBatchResult(chapter, error))
                    processedCount += 1
                    emitProgress(chapter.index, "unavailable")
                    continue
                }
            if (
                content.bookId != state.book.id ||
                content.chapterIndex != chapter.index ||
                content.sourceId != chapter.sourceId ||
                !content.isComplete ||
                content.readableUntil != content.content.length
            ) {
                unavailable.put(
                    unavailableBatchResult(
                        chapter,
                        IllegalStateException("章节正文已变化或暂不完整"),
                    ),
                )
                processedCount += 1
                emitProgress(chapter.index, "unavailable")
                continue
            }
            processedTargets += chapter.index
            val result =
                try {
                    generateSummaryForChapter(
                        state = state,
                        chapter = chapter,
                        content = content,
                        chapters = chapters,
                        fileStore = fileStore,
                        coordinator = coordinator,
                        runtime = runtime,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val failed = failedBatchResult(chapter, error)
                    failures.put(failed)
                    // Also surfaces the failure inside the per-run results list, matching the
                    // historical batch result shape.
                    failed
                }
            results.put(result)
            if (result.optString("status") == "generated") completedCount += 1
            processedCount += 1
            emitProgress(chapter.index, result.optString("status"))
        }

            val remainingMissing =
                scan.totalInRange - scan.existingInRange - completedCount
            val stopped = scan.stopped || manualSummaryStopRequested
            JSONObject()
                .put("book", state.book.name)
                .put("requestedCount", count)
                .put("totalInRange", scan.totalInRange)
                .put("existingInRange", scan.existingInRange)
                .put("scanComplete", !scan.stopped)
                .put("remainingMissing", remainingMissing)
                .put("remainingReadableMissing", remainingMissing)
                .put(
                    "targetChapterIndices",
                    JSONArray().apply { processedTargets.forEach(::put) },
                )
                .put("completedCount", completedCount)
                .put("processedCount", processedCount)
                .put("failedCount", failures.length())
                .put("failures", failures)
                .put("unavailableCount", unavailable.length())
                .put("unavailable", unavailable)
                .put("results", results)
                // One summary-only subagent task is created per selected chapter. Each task
                // may naturally require multiple provider turns while it reads and submits.
                .put("modelTaskCount", processedTargets.size)
                .put(
                    "status",
                    when {
                        stopped -> "stopped"
                        failures.length() > 0 -> "completed_with_failures"
                        else -> "completed"
                    },
                )
            }
        } finally {
            manualSummaryBatchActive = false
            manualSummaryStopRequested = false
            activeManualSummaryBatchId = null
            manualSummaryMutex.unlock()
        }
    }

    /** Requests that the active summary batch stop before starting its next chapter. */
    fun requestManualSummaryBatchStop(batchId: String): Boolean {
        if (
            batchId.isBlank() ||
            !manualSummaryBatchActive ||
            activeManualSummaryBatchId != batchId
        ) {
            return false
        }
        manualSummaryStopRequested = true
        return true
    }

    /** Saved per-book summary-batch preferences for the current book, or null when never set. */
    suspend fun summaryBatchPrefs(bookId: String? = null): SummaryBatchPrefs? =
        store.getSummaryBatchPrefs(selectedReadingState(bookId).book.id)

    /** Persists the per-book summary-batch preferences for the current book. */
    suspend fun saveSummaryBatchPrefs(
        startChapter: Int?,
        endChapter: Int?,
        budget: Int,
        bookId: String? = null,
    ): SummaryBatchPrefs {
        val selectedBookId = selectedReadingState(bookId).book.id
        val prefs = SummaryBatchPrefs(
            startChapter = startChapter,
            endChapter = endChapter,
            budget = budget,
        )
        store.setSummaryBatchPrefs(selectedBookId, prefs)
        return prefs
    }

    /**
     * Shared scan for the manual summary-batch surfaces.  Counts every *complete* chapter inside
     * [rangeStart]..[rangeEnd] that is at or before the current reading boundary, separates the
     * chapters that already carry a fresh summary of either content kind, and collects up to
     * [limit] missing candidates newest-first. Chapters whose readable content cannot be fetched
     * are reported as unavailable and never counted as complete candidates.
     */
    private suspend fun scanSummaryBatchCandidates(
        state: ReadingState,
        chapters: List<ReaderChapter>,
        fileStore: ReadingCompanionFileStore,
        rangeStart: Int,
        rangeEnd: Int,
        limit: Int,
    ): SummaryBatchCandidateScan {
        var totalInRange = 0
        var existingInRange = 0
        val candidates = mutableListOf<ReaderChapter>()
        val selectionFailures = mutableListOf<JSONObject>()
        var stopped = false
        for (chapter in chapters) {
            if (manualSummaryStopRequested) {
                stopped = true
                break
            }
            if (
                chapter.index < rangeStart ||
                chapter.index > rangeEnd ||
                chapter.index > state.chapterIndex
            ) {
                continue
            }
            val content = try {
                provider.getReadableChapterContentForCatalogSnapshot(
                    bookId = state.book.id,
                    chapterIndex = chapter.index,
                    expectedSourceId = chapter.sourceId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                selectionFailures +=
                    JSONObject()
                        .put("chapterIndex", chapter.index)
                        .put("chapterNumber", chapter.index + 1)
                        .put("status", "unavailable")
                        .put("error", safeReadingCompanionError(error))
                continue
            }
            if (
                content.bookId != state.book.id ||
                content.chapterIndex != chapter.index ||
                content.sourceId != chapter.sourceId ||
                !content.isComplete ||
                content.readableUntil != content.content.length
            ) {
                continue
            }
            totalInRange += 1
            val currentHash = ReadingCompanionFileStore.contentHash(content.content)
            // A summary may have been produced by the commentary path and therefore be tied to
            // the annotation body instead of the readable prefix.  Verify whichever content kind
            // the persisted metadata declares; an existing fresh summary of either kind is not a
            // missing target for ordinary batch fill.
            val existingSummary =
                fileStore.summaryContentHashKind(state.book.id, chapter.sourceId)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { contentHashKind ->
                        val persistedHash =
                            if (contentHashKind ==
                                ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE
                            ) {
                                currentHash
                            } else {
                                try {
                                    currentSummaryContentHash(
                                        state.book.id,
                                        chapter,
                                        contentHashKind,
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        persistedHash?.let {
                            fileStore.readSummary(
                                bookId = state.book.id,
                                sourceId = chapter.sourceId,
                                expectedContentHash = it,
                            )
                        }
                    }
            if (existingSummary != null) {
                existingInRange += 1
            } else if (candidates.size < limit) {
                // Do not retain chapter bodies for the whole batch. The selected chapter is
                // fetched and revalidated immediately before its subagent task starts.
                candidates += chapter
            }
        }
        return SummaryBatchCandidateScan(
            totalInRange = totalInRange,
            existingInRange = existingInRange,
            candidates = candidates,
            selectionFailures = selectionFailures,
            stopped = stopped,
        )
    }

    private fun unavailableBatchResult(chapter: ReaderChapter, error: Throwable): JSONObject =
        JSONObject()
            .put("chapterIndex", chapter.index)
            .put("chapterNumber", chapter.index + 1)
            .put("status", "unavailable")
            .put("error", safeReadingCompanionError(error))

    private fun failedBatchResult(chapter: ReaderChapter, error: Throwable): JSONObject =
        JSONObject()
            .put("chapterIndex", chapter.index)
            .put("chapterNumber", chapter.index + 1)
            .put("status", "failed")
            .put("error", safeReadingCompanionError(error))

    private suspend fun generateSummaryForChapter(
        state: ReadingState,
        chapter: ReaderChapter,
        content: ReadableChapterContent,
        chapters: List<ReaderChapter>,
        fileStore: ReadingCompanionFileStore,
        coordinator: ReadingCompanionSubagentCoordinator,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
    ): JSONObject {
        val runId =
            store.startAutoCommentRun(
                trigger = TRIGGER_MANUAL_SUMMARY,
                executionMode = AUTO_COMMENT_RUN_EXECUTION_MODE_SUBAGENT,
            )
        store.updateAutoCommentRunTarget(
            runId = runId,
            bookId = state.book.id,
            chapterIndex = chapter.index,
            chapterTitle = content.chapterTitle,
        )
        return try {
            val previousContext = loadSummaryPreviousContext(
                bookId = state.book.id,
                targetChapterIndex = chapter.index,
                chapters = chapters,
            )
            val target =
                AnnotationChapterContent(
                    bookId = content.bookId,
                    sourceId = content.sourceId,
                    chapterIndex = content.chapterIndex,
                    chapterTitle = content.chapterTitle,
                    content = content.content,
                    contractHash = ReadingCompanionFileStore.contentHash(content.content),
                    capturedAt = content.capturedAt,
                )
            val persona =
                AutoCommentPersona(
                    bookId = state.book.id,
                    roleCardId = SUMMARY_ONLY_ROLE_CARD_ID,
                    roleCardName = SUMMARY_ONLY_ROLE_CARD_NAME,
                    updatedAt = System.currentTimeMillis(),
                )
            val outcome = coordinator.runGeneration(
                runId = runId,
                trigger = TRIGGER_MANUAL_SUMMARY,
                runtime = runtime,
                bookId = state.book.id,
                bookName = state.book.name,
                chapterIndex = chapter.index,
                chapterTitle = content.chapterTitle,
                contentHash = target.contractHash,
                persona = persona,
                rolePrompt = "",
                targetContent = target.content,
                chapters = chapters,
                previousContext = previousContext,
                summaryOnly = true,
            )
            store.updateAutoCommentRunExecution(runId, outcome.execution)
            store.updateAutoCommentRunPromptMetrics(
                runId = runId,
                targetCharacterCount = content.content.count { !it.isWhitespace() },
                metrics = AutoCommentPromptMetrics(
                    previousContextChapterCount = previousContext.size,
                    previousContextCharacterCount =
                        previousContext.sumOf { it.content.trim().length },
                    contextWindowTokens = 0,
                    estimatedInputTokens =
                        (content.content.length + previousContext.sumOf { it.content.length }) / 2,
                ),
            )
            validateContentBeforeStore(state, content, chapter.sourceId)
            val published = fileStore.writeSummary(
                book = state.book,
                chapter = chapter,
                sourceContent = content.content,
                summary = outcome.summary,
            )
            // Keep the summary discoverable by the existing FTS-backed search/knowledge APIs
            // without invoking the legacy direct model gateway or fabricating structured facts.
            store.replaceChapter(content)
            store.indexPublishedSummary(state.book.id, chapter.index, chapter.sourceId, published)
            store.finishAutoCommentRun(
                runId = runId,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_GENERATED,
                commentCount = 0,
            )
            JSONObject()
                .put("chapterIndex", chapter.index)
                .put("chapterNumber", chapter.index + 1)
                .put("chapterTitle", chapter.title)
                .put("status", "generated")
                .put("summary", published.text)
                .put("summaryRevision", published.revision)
                .put("preservedManualSummary", !published.wasReplaced)
                .put("runId", runId)
        } catch (cancelled: CancellationException) {
            store.markRunInterrupted(runId, errorMessage = "cancelled")
            throw cancelled
        } catch (error: Throwable) {
            store.finishAutoCommentRun(
                runId = runId,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_FAILED,
                errorMessage = safeReadingCompanionError(error),
            )
            throw error
        } finally {
            // Summary-only runs use the same hidden audit-chat retention as commentary runs.
            // Drain pruned/orphaned child cleanup here as well so a summary-only user does not
            // accumulate hidden chats indefinitely.
            runCatching { store.flushPrunedRunChatCleanup() }
            runCatching { store.runOrphanChatCleanup() }
        }
    }

    private suspend fun loadSummaryPreviousContext(
        bookId: String,
        targetChapterIndex: Int,
        chapters: List<ReaderChapter>,
    ): List<AutoCommentContextChapter> {
        val chapterByIndex = chapters.associateBy(ReaderChapter::index)
        val nearest = mutableListOf<AutoCommentContextChapter>()
        for (index in (targetChapterIndex - REQUIRED_SUMMARY_PREVIOUS_CHAPTERS) until targetChapterIndex) {
            val expected = chapterByIndex[index] ?: continue
            val content =
                try {
                    provider.getReadableChapterContent(bookId, index)
                } catch (_: ReaderProviderException) {
                    continue
                }
            if (
                content.bookId != bookId ||
                content.sourceId != expected.sourceId ||
                !content.isComplete ||
                content.readableUntil != content.content.length
            ) {
                continue
            }
            nearest +=
                AutoCommentContextChapter(
                    sourceId = content.sourceId,
                    chapterIndex = index,
                    chapterTitle = content.chapterTitle,
                    content = content.content,
                    excerptFromEnd = false,
                )
        }
        return nearest
    }

    suspend fun character(
        name: String,
        @Suppress("UNUSED_PARAMETER") runtime: ToolExecutionManager.ToolRuntimeContext? = null,
        maxCharacters: Int = DEFAULT_CHARACTER_EVIDENCE_CHARACTERS,
    ): JSONObject {
        val query = name.trim()
        require(query.isNotBlank()) { "人物名称不能为空" }
        val state = selectedReadingState()
        synchronizeBoundary(state)
        val chapters = provider.getChapters(state.book.id)
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        // Catalog sync is deliberately the only write here. It records the currently active
        // source identities so file evidence cannot walk stale source directories.
        synchronizeCatalog(fileStore, state, chapters)
        val evidence = fileStore.findCharacterEvidence(
            bookId = state.book.id,
            chapters = chapters,
            query = query,
            throughChapterIndex = state.chapterIndex,
            currentBodyPosition = state.bodyPosition,
            maxCharacters = maxCharacters.coerceIn(
                MIN_CHARACTER_EVIDENCE_CHARACTERS,
                MAX_CHARACTER_EVIDENCE_CHARACTERS,
            ),
        )
        val hits = evidence.optJSONArray("evidence") ?: JSONArray()
        return JSONObject(evidence.toString()).apply {
            put("book", state.book.name)
            put("bookId", state.book.id)
            put("queryName", query)
            put("status", if (hits.length() == 0) "not_found_in_files" else "found")
            put(
                "notice",
                if (hits.length() == 0) {
                    "当前 characters.md 及有效已读章节 summary/content 中没有匹配人物“$query”"
                } else {
                    "证据仅来自当前 characters.md 与阅读边界内已生效的章节文件"
                },
            )
        }
    }

    suspend fun addMemory(
        type: String,
        content: String,
        chapterIndex: Int?,
    ): JSONObject {
        val state = selectedReadingState()
        val targetChapter = chapterIndex ?: state.chapterIndex
        require(targetChapter in 0..state.chapterIndex) { "记忆不能绑定到尚未阅读的章节" }
        val normalizedType = type.trim().ifBlank { "note" }.take(40)
        val normalizedContent = content.trim()
        require(normalizedContent.isNotBlank()) { "记忆内容不能为空" }
        require(normalizedContent.length <= 4000) { "单条记忆不能超过 4000 字" }
        synchronizeBoundary(state)
        val memory = store.addMemory(
            bookId = state.book.id,
            chapterIndex = targetChapter,
            type = normalizedType,
            content = normalizedContent,
        )
        return JSONObject()
            .put("id", memory.id)
            .put("book", state.book.name)
            .put("chapterIndex", memory.chapterIndex)
            .put("chapterNumber", memory.chapterIndex + 1)
            .put("type", memory.type)
            .put("content", memory.content)
            .put("createdAt", memory.createdAt)
            .put("source", "reader_memory")
    }

    suspend fun search(
        query: String,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
        bookId: String? = null,
    ): JSONObject {
        require(query.isNotBlank()) { "搜索问题不能为空" }
        var refresh = refreshAndIndex(
            maxCompletedChapters = 3,
            maxKnowledgeChapters = 0,
            scheduleMore = true,
            runtime = runtime,
            bookId = bookId,
        )
        val plan = runOptionalSearchModelStep {
            modelGateway.analyzeQuery(query, runtime)
        } ?: ReadingQueryPlan(
            intent = "综合",
            keywords = emptyList(),
            entities = emptyList(),
            timeHint = null,
        )
        val heuristicTerms = ReadingTextIndexSupport.extractQueryTerms(query)
        val terms = (plan.keywords + plan.entities + heuristicTerms)
            .flatMap(ReadingTextIndexSupport::extractQueryTerms)
            .distinct()
            .take(16)
        var searchState = selectedReadingState(refresh.state.book.id)
        synchronizeBoundary(searchState)
        var candidates = searchEvidence(searchState, terms)
        if (candidates.isEmpty()) {
            refresh = refreshAndIndex(
                maxCompletedChapters = 5,
                maxKnowledgeChapters = 0,
                scheduleMore = true,
                runtime = runtime,
                bookId = searchState.book.id,
            )
            searchState = selectedReadingState(searchState.book.id)
            synchronizeBoundary(searchState)
            candidates = searchEvidence(searchState, terms)
        }
        val memories = store.searchMemories(
            bookId = searchState.book.id,
            terms = terms,
            throughChapterIndex = searchState.chapterIndex,
            limit = 8,
        )

        val rerankState = selectedReadingState(searchState.book.id)
        failIfBoundaryRegressed(searchState, rerankState)
        synchronizeBoundary(rerankState)
        val guardedCandidates = candidates.filter { hit ->
            SpoilerGuard.isPositionAllowed(
                hit.chapterIndex,
                hit.startPosition,
                hit.endPosition,
                rerankState,
            )
        }.take(24)
        val rerankedIds = runOptionalSearchModelStep {
            modelGateway.rerank(query, guardedCandidates, runtime)
        }.orEmpty()
        val returnState = selectedReadingState(rerankState.book.id)
        failIfBoundaryRegressed(rerankState, returnState)
        synchronizeBoundary(returnState)
        val candidatesById = guardedCandidates.associateBy(ReadingSearchHit::id)
        val ordered = if (rerankedIds.isNotEmpty()) {
            rerankedIds.mapNotNull(candidatesById::get)
        } else {
            guardedCandidates.sortedByDescending(ReadingSearchHit::score)
        }
        val selected =
            verifyDatabaseHits(
                returnState,
                ordered.filter { hit ->
                    SpoilerGuard.isPositionAllowed(
                        hit.chapterIndex,
                        hit.startPosition,
                        hit.endPosition,
                        returnState,
                    )
                }.take(8),
            )

        return JSONObject().apply {
            put("book", returnState.book.name)
            put("query", query)
            put("intent", plan.intent)
            put("timeHint", plan.timeHint)
            put("keywords", JSONArray(terms))
            put("indexingRemainingChapters", refresh.remainingCompletedChapters)
            put("knowledgeRemainingChapters", refresh.remainingKnowledgeChapters)
            put(
                "results",
                JSONArray().apply { selected.forEach { put(hitJson(it)) } },
            )
            put(
                "readerMemories",
                JSONArray().apply {
                    memories.forEach { memory ->
                        put(
                            JSONObject()
                                .put("id", memory.id)
                                .put("chapterIndex", memory.chapterIndex)
                                .put("chapterNumber", memory.chapterIndex + 1)
                                .put("type", memory.type)
                                .put("content", memory.content)
                                .put("createdAt", memory.createdAt)
                                .put("source", "reader_memory")
                        )
                    }
                },
            )
            put(
                "memoryNotice",
                "readerMemories are the reader's own notes or predictions, not confirmed novel facts",
            )
        }
    }

    private suspend fun searchEvidence(
        state: ReadingState,
        terms: List<String>,
    ): List<ReadingSearchHit> {
        val structured = store.searchKnowledge(
            state.book.id,
            terms,
            summaryOnly = false,
            limit = 30,
        )
        val summaries = store.searchKnowledge(
            state.book.id,
            terms,
            summaryOnly = true,
            limit = 20,
        )
        val fullText = store.searchText(state.book.id, terms, limit = 50)
        val candidates = (structured + summaries + fullText)
            .filter {
                SpoilerGuard.isPositionAllowed(
                    it.chapterIndex,
                    it.startPosition,
                    it.endPosition,
                    state,
                )
            }
            .distinctBy(ReadingSearchHit::id)
            .sortedWith(
                compareByDescending<ReadingSearchHit> { sourcePriority(it.source) }
                    .thenByDescending { it.score }
            )
            .take(30)
        return verifyDatabaseHits(state, candidates)
    }

    private fun sourcePriority(source: String): Int = when {
        source.startsWith("structured_") -> 3
        source == "chapter_summary" -> 2
        else -> 1
    }

    private suspend fun <T> runOptionalSearchModelStep(block: suspend () -> T): T? {
        return try {
            withTimeout(SEARCH_MODEL_STEP_TIMEOUT_MS) { block() }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun selectedReadingState(explicitBookId: String? = null): ReadingState {
        val selected = explicitBookId ?: store.getSelectedBookId()
        return provider.getReadingState(selected)
    }

    internal suspend fun readFreshPersistedSummary(
        bookId: String,
        sourceId: String,
        chapterIndex: Int,
    ): String? {
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        val kind = fileStore.summaryContentHashKind(bookId, sourceId) ?: return null
        val chapter = ReaderChapter(bookId, sourceId, chapterIndex, "")
        val currentHash = try {
            withTimeoutOrNull(SUMMARY_TOOL_FRESHNESS_TIMEOUT_MS) {
                currentSummaryContentHash(bookId, chapter, kind)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        return fileStore.readSummary(bookId, sourceId, currentHash)
    }

    private suspend fun collectRecentFreshFileSummaries(
        state: ReadingState,
        limit: Int,
        includeCurrentReadableSummary: Boolean = false,
        chaptersOverride: List<ReaderChapter>? = null,
        fileStoreOverride: ReadingCompanionFileStore? = null,
    ): List<FreshFileSummary> {
        val chapters = chaptersOverride ?: provider.getChapters(state.book.id)
        val fileStore = fileStoreOverride ?: ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        synchronizeCatalog(fileStore, state, chapters)
        val candidates = chapters
            .asSequence()
            .filter { chapter ->
                chapter.index < state.chapterIndex ||
                    (includeCurrentReadableSummary && chapter.index == state.chapterIndex)
            }
            .filter { chapter -> fileStore.hasSummary(state.book.id, chapter.sourceId) }
            .sortedByDescending(ReaderChapter::index)
            .take((limit * 3).coerceAtMost(30))
            .toList()
        val result = mutableListOf<FreshFileSummary>()
        val sourceByIndex = mutableMapOf<Int, String>()
        withTimeoutOrNull(SUMMARY_CONTEXT_FRESHNESS_BUDGET_MS) {
            for (chapter in candidates) {
                if (result.size >= limit) break
                val summary = readFreshFileSummary(
                    state = state,
                    chapter = chapter,
                    includeCurrentReadableSummary = includeCurrentReadableSummary,
                    fileStore = fileStore,
                ) ?: continue
                sourceByIndex[chapter.index] = chapter.sourceId
                result += summary
            }
        }
        val currentChapterByIndex =
            provider.getChapters(state.book.id).associateBy(ReaderChapter::index)
        val finalResult = mutableListOf<FreshFileSummary>()
        withTimeoutOrNull(SUMMARY_CONTEXT_FRESHNESS_BUDGET_MS) {
            result.forEach { summary ->
                val currentChapter =
                    currentChapterByIndex[summary.chapter.index] ?: return@forEach
                if (currentChapter.sourceId != sourceByIndex[summary.chapter.index]) return@forEach
                readFreshFileSummary(
                    state = state,
                    chapter = currentChapter,
                    includeCurrentReadableSummary = includeCurrentReadableSummary,
                    fileStore = fileStore,
                )?.let(finalResult::add)
            }
        }
        return finalResult
    }

    private suspend fun readFreshFileSummary(
        state: ReadingState,
        chapter: ReaderChapter,
        includeCurrentReadableSummary: Boolean,
        fileStore: ReadingCompanionFileStore,
    ): FreshFileSummary? {
        if (chapter.index > state.chapterIndex) return null
        val contentHashKind =
            fileStore.summaryContentHashKind(state.book.id, chapter.sourceId) ?: return null
        if (
            chapter.index == state.chapterIndex &&
            (
                !includeCurrentReadableSummary ||
                    contentHashKind != ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE
            )
        ) {
            return null
        }
        val contentHash =
            fileStore.summaryContentHash(state.book.id, chapter.sourceId) ?: return null
        val summary =
            readFreshPersistedSummary(state.book.id, chapter.sourceId, chapter.index) ?: return null
        val path =
            fileStore.chapterFilePaths(state.book.id, chapter.sourceId)
                ?.optString("summaryPath")
                ?.takeIf(String::isNotBlank)
                ?: return null
        return FreshFileSummary(
            chapter = chapter,
            summary = summary,
            path = path,
            contentHash = contentHash,
            contentHashKind = contentHashKind,
        )
    }

    private suspend fun verifyDatabaseHits(
        state: ReadingState,
        hits: List<ReadingSearchHit>,
    ): List<ReadingSearchHit> {
        if (hits.isEmpty()) return emptyList()
        val chapters = provider.getChapters(state.book.id)
        val chapterByIndex = chapters.associateBy(ReaderChapter::index)
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        synchronizeCatalog(fileStore, state, chapters)
        val verifiedTextIndices = mutableSetOf<Int>()
        val verifiedKnowledgeIndices = mutableSetOf<Int>()
        withTimeoutOrNull(SUMMARY_CONTEXT_FRESHNESS_BUDGET_MS) {
            for (chapterIndex in hits.asSequence().map(ReadingSearchHit::chapterIndex).distinct().take(16)) {
                val chapter = chapterByIndex[chapterIndex] ?: continue
                val indexedHash = store.getIndexedChapter(state.book.id, chapterIndex)?.contentHash
                    ?: continue
                val currentHash = try {
                    withTimeoutOrNull(SUMMARY_TOOL_FRESHNESS_TIMEOUT_MS) {
                        currentSummaryContentHash(
                            state.book.id,
                            chapter,
                            ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (currentHash == indexedHash) {
                    verifiedTextIndices += chapterIndex
                    val knowledgeHash =
                        store.getChapterKnowledge(state.book.id, chapterIndex)?.contentHash
                    if (knowledgeHash == indexedHash) {
                        verifiedKnowledgeIndices += chapterIndex
                    }
                }
            }
        }
        return hits.filter {
            if (it.source == "full_text") {
                it.chapterIndex in verifiedTextIndices
            } else {
                it.chapterIndex in verifiedKnowledgeIndices
            }
        }
    }

    private suspend fun currentSummaryContentHash(
        bookId: String,
        chapter: ReaderChapter,
        kind: String,
    ): String? =
        when (kind) {
            ReadingCompanionFileStore.CONTENT_HASH_KIND_ANNOTATION -> {
                val current = provider.getAnnotationChapterContent(bookId, chapter.index)
                current
                    .takeIf { it.sourceId == chapter.sourceId }
                    ?.content
                    ?.let(ReadingCompanionFileStore::contentHash)
            }
            ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE -> {
                val current = provider.getReadableChapterContent(bookId, chapter.index)
                current
                    .takeIf { it.sourceId == chapter.sourceId }
                    ?.content
                    ?.let(ReadingCompanionFileStore::contentHash)
            }
            else -> null
        }

    private suspend fun readFreshCompanionComments(
        state: ReadingState,
        currentUnlockedParagraph: Int,
        roleCardId: String,
        limit: Int,
    ): List<AutoCommentRecord> {
        val safeLimit = limit.coerceIn(0, MAX_CONTEXT_COMPANION_COMMENTS)
        if (safeLimit == 0) return emptyList()
        val chapters = provider.getChapters(state.book.id)
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        synchronizeCatalog(fileStore, state, chapters)
        val sourceByIndex = mutableMapOf<Int, String>()
        val contractByIndex = mutableMapOf<Int, String>()
        val result = mutableListOf<AutoCommentRecord>()
        withTimeoutOrNull(SUMMARY_CONTEXT_FRESHNESS_BUDGET_MS) {
            for (chapter in chapters.asReversed()) {
                if (chapter.index > state.chapterIndex || result.size >= safeLimit) continue
                val storedContract =
                    fileStore.publishedContractHash(state.book.id, chapter.sourceId, roleCardId)
                        ?: continue
                val current = try {
                    withTimeoutOrNull(SUMMARY_TOOL_FRESHNESS_TIMEOUT_MS) {
                        provider.getAnnotationChapterContent(state.book.id, chapter.index)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: continue
                if (current.sourceId != chapter.sourceId || current.contractHash != storedContract) {
                    continue
                }
                val published =
                    fileStore.readPublishedComments(
                        state.book.id,
                        chapter.index,
                        current.contractHash,
                    )?.takeIf { it.optBoolean("ready") } ?: continue
                sourceByIndex[chapter.index] = chapter.sourceId
                contractByIndex[chapter.index] = current.contractHash
                val comments = published.optJSONArray("comments") ?: JSONArray()
                for (position in 0 until comments.length()) {
                    val comment = comments.optJSONObject(position) ?: continue
                    val paragraphIndex = comment.optInt("paragraphIndex", -1)
                    if (
                        paragraphIndex < 0 ||
                        (
                            chapter.index == state.chapterIndex &&
                                paragraphIndex > currentUnlockedParagraph
                            )
                    ) {
                        continue
                    }
                    result += AutoCommentRecord(
                        bookId = state.book.id,
                        chapterIndex = chapter.index,
                        paragraphIndex = paragraphIndex,
                        text = comment.optString("text"),
                        kind = comment.optString("kind"),
                        roleCardId = roleCardId,
                        roleCardName = published.optString("roleCardName"),
                        evidenceJson =
                            (comment.optJSONObject("evidence") ?: JSONObject()).toString(),
                        createdAt = comment.optLong("createdAt"),
                    )
                }
            }
        }
        val currentChapterByIndex =
            provider.getChapters(state.book.id).associateBy(ReaderChapter::index)
        val finalValidIndices = mutableSetOf<Int>()
        withTimeoutOrNull(SUMMARY_CONTEXT_FRESHNESS_BUDGET_MS) {
            sourceByIndex.forEach { (chapterIndex, sourceId) ->
                val chapter = currentChapterByIndex[chapterIndex] ?: return@forEach
                if (chapter.sourceId != sourceId) return@forEach
                val current = try {
                    withTimeoutOrNull(SUMMARY_TOOL_FRESHNESS_TIMEOUT_MS) {
                        provider.getAnnotationChapterContent(state.book.id, chapterIndex)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: return@forEach
                if (
                    current.sourceId == sourceId &&
                    current.contractHash == contractByIndex[chapterIndex]
                ) {
                    finalValidIndices += chapterIndex
                }
            }
        }
        return result
            .filter { it.chapterIndex in finalValidIndices }
            .sortedWith(
                compareByDescending<AutoCommentRecord> { it.chapterIndex }
                    .thenByDescending(AutoCommentRecord::paragraphIndex)
            )
            .take(safeLimit)
    }

    private suspend fun validateContextInputsStillCurrent(
        state: ReadingState,
        current: ReadableChapterContent,
        previous: List<AutoCommentContextChapter>,
    ) {
        val chapters = provider.getChapters(state.book.id).associateBy(ReaderChapter::index)
        require(chapters[current.chapterIndex]?.sourceId == current.sourceId) {
            "上下文返回前当前章节目录发生变化"
        }
        val latestCurrent = provider.getReadableChapterContent(state.book.id, current.chapterIndex)
        validateContentBeforeStore(state, latestCurrent, current.sourceId)
        require(
            latestCurrent.readableUntil == current.readableUntil &&
                ReadingCompanionFileStore.contentHash(latestCurrent.content) ==
                    ReadingCompanionFileStore.contentHash(current.content)
        ) { "上下文返回前当前章节正文发生变化" }
        previous.forEach { prior ->
            require(chapters[prior.chapterIndex]?.sourceId == prior.sourceId) {
                "上下文返回前前文章节目录发生变化"
            }
            val latestPrior =
                provider.getReadableChapterContent(state.book.id, prior.chapterIndex)
            require(latestPrior.sourceId == prior.sourceId) {
                "上下文返回前前文章节来源发生变化"
            }
            require(
                AutoCommentSupport.previousContextStillMatches(
                    latestContent = latestPrior.content,
                    captured = prior,
                ),
            ) { "上下文返回前前文章节正文发生变化" }
        }
    }

    private suspend fun validateGenerationInputStillCurrent(
        initialState: ReadingState,
        generatedFrom: ReadableChapterContent,
        fileStore: ReadingCompanionFileStore,
    ): Pair<ReadingState, ReaderChapter> {
        val latest = selectedReadingState(initialState.book.id)
        failIfBoundaryRegressed(initialState, latest)
        require(
            SpoilerGuard.isPositionAllowed(
                generatedFrom.chapterIndex,
                0,
                generatedFrom.readableUntil,
                latest,
            )
        ) { "章节摘要生成期间阅读边界发生变化" }
        val currentChapters = provider.getChapters(initialState.book.id)
        synchronizeCatalog(fileStore, initialState, currentChapters)
        val currentChapter =
            currentChapters.firstOrNull { it.index == generatedFrom.chapterIndex }
                ?: error("摘要生成期间目标章节已从目录中移除")
        require(currentChapter.sourceId == generatedFrom.sourceId) {
            "摘要生成期间章节目录发生变化"
        }
        val currentContent =
            provider.getReadableChapterContent(initialState.book.id, generatedFrom.chapterIndex)
        validateContentBeforeStore(latest, currentContent, currentChapter.sourceId)
        require(
            currentContent.sourceId == generatedFrom.sourceId &&
                currentContent.readableUntil == generatedFrom.readableUntil &&
                ReadingCompanionFileStore.contentHash(currentContent.content) ==
                    ReadingCompanionFileStore.contentHash(generatedFrom.content)
        ) { "摘要生成期间章节正文发生变化" }
        return latest to currentChapter
    }

    private suspend fun validateContentBeforeStore(
        previousState: ReadingState,
        content: ReadableChapterContent,
        expectedSourceId: String?,
    ) {
        require(!expectedSourceId.isNullOrBlank() && content.sourceId == expectedSourceId) {
            "Legado 目录在读取章节时发生变化，请稍后重试"
        }
        val latest = selectedReadingState(previousState.book.id)
        failIfBoundaryRegressed(previousState, latest)
        synchronizeBoundary(latest)
        require(
            SpoilerGuard.isPositionAllowed(
                content.chapterIndex,
                0,
                content.readableUntil,
                latest,
            )
        ) { "正文写入前阅读边界发生变化" }
    }

    private fun synchronizeBoundary(state: ReadingState) {
        store.updateBook(state)
        store.prepareForState(state)
    }

    private fun synchronizeCatalog(
        fileStore: ReadingCompanionFileStore,
        state: ReadingState,
        chapters: List<ReaderChapter>,
    ) {
        fileStore.syncBookCatalog(state.book, chapters)
        store.updateBook(state)
        store.synchronizeCatalogSources(state.book.id, chapters)
    }

    private fun failIfBoundaryRegressed(previous: ReadingState, latest: ReadingState) {
        if (!ReadingBoundaryGuard.hasRegressed(previous, latest)) return
        synchronizeBoundary(latest)
        throw ReaderProviderException(
            ReaderProviderException.Reason.UNSAFE_POSITION,
            "阅读进度在检索期间发生回退，已清除越界索引，请重试",
        )
    }

    private fun failIfBoundaryChanged(previous: ReadingState, latest: ReadingState) {
        val unchanged =
            previous.book.id == latest.book.id &&
                previous.chapterIndex == latest.chapterIndex &&
                previous.layoutPosition == latest.layoutPosition &&
                previous.bodyPosition == latest.bodyPosition
        if (unchanged) return
        synchronizeBoundary(latest)
        throw ReaderProviderException(
            ReaderProviderException.Reason.UNSAFE_POSITION,
            "阅读进度在上下文组装期间发生变化，请重试",
        )
    }

    fun scheduleBackgroundIndex() {
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            INDEX_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            createIndexRequest(),
        )
    }

    internal fun scheduleBackgroundIndexContinuation() {
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            INDEX_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            createIndexRequest(),
        )
    }

    private fun createIndexRequest() =
        OneTimeWorkRequestBuilder<ReadingCompanionIndexWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .build()

    private fun knowledgeJson(
        state: ReadingState,
        stored: StoredChapterKnowledge,
        generatedNow: Boolean,
    ): JSONObject = JSONObject().apply {
        put("book", state.book.name)
        put("chapterIndex", stored.chapterIndex)
        put("chapterNumber", stored.chapterIndex + 1)
        put("chapterTitle", stored.chapterTitle)
        put("sourceEndPos", stored.sourceEndPosition)
        put("completeChapter", stored.isComplete)
        put("generatedNow", generatedNow)
        put("summary", stored.summary)
        put("knowledge", JSONObject(stored.structuredJson))
        put("updatedAt", stored.updatedAt)
    }

    private fun hitJson(hit: ReadingSearchHit): JSONObject = JSONObject().apply {
        put("id", hit.id)
        put("source", hit.source)
        put("entityName", hit.entityName)
        put("chapterIndex", hit.chapterIndex)
        put("chapterNumber", hit.chapterIndex + 1)
        put("chapterTitle", hit.chapterTitle)
        put("startPos", hit.startPosition)
        put("endPos", hit.endPosition)
        put("text", hit.text)
    }

    private fun AutoCommentRecord.toCompanionCommentJson(): JSONObject = JSONObject().apply {
        put("authorRoleCardId", roleCardId)
        put("authorName", roleCardName)
        put("chapterIndex", chapterIndex)
        put("chapterNumber", chapterIndex + 1)
        put("paragraphIndex", paragraphIndex)
        put("text", text)
        put("kind", kind)
        put("createdAt", createdAt)
    }

    companion object {
        private const val AI_MEMORY_NOTICE =
            "companionMemoryPath is this AI role's own first-person companion memory, not the user's memory. " +
                "Read it before replying and proactively edit it to maintain worthwhile views, reactions, " +
                "predictions and shared discussion, without waiting for a user request or per-update approval. " +
                "Revise outdated views and merge duplicates. Keep predictions unconfirmed and never include unread plot. " +
                "readerMemories are separate reader notes."
        const val TOOLPKG_ID = "com.operit.reading_companion"
        const val SUBPACKAGE_NAME = "reading_companion"
        const val AUTO_COMMENTARY_SUBPACKAGE_NAME = "reading_companion_auto_commentary"
        private const val DEFAULT_CONTEXT_CHARACTERS = 16_000
        private const val MIN_CONTEXT_CHARACTERS = 8_000
        private const val MAX_CONTEXT_CHARACTERS = 96_000
        private const val CURRENT_CHAPTER_MAX_CHARACTERS = 6_000
        private const val MAX_SUPPLEMENTAL_CONTEXT_CHARACTERS = 8_000
        private const val MAX_CONTEXT_ENVELOPE_CHARACTERS = 4_000
        private const val MAX_CONTEXT_CHAPTERS = 8
        private const val MAX_CONTEXT_READER_MEMORIES = 24
        private const val MAX_CONTEXT_COMPANION_COMMENTS = 24
        private const val MIN_CHARACTER_EVIDENCE_CHARACTERS = 1_000
        private const val MAX_CHARACTER_EVIDENCE_CHARACTERS = 24_000
        private const val DEFAULT_CHARACTER_EVIDENCE_CHARACTERS =
            ReadingCompanionFileStore.DEFAULT_CHARACTER_EVIDENCE_CHARACTERS
        private const val SUMMARY_BUDGET_FRACTION = 50
        private const val MEMORY_BUDGET_FRACTION = 30
        private const val COMMENT_BUDGET_FRACTION = 20
        private const val INDEX_WORK_NAME = "reading_companion_incremental_index"
        private const val SEARCH_MODEL_STEP_TIMEOUT_MS = 8_000L
        private const val SUMMARY_LIST_CATALOG_REFRESH_BUDGET_MS = 6_000L
        private const val LIST_FILES_CATALOG_REFRESH_BUDGET_MS = 6_000L
        private const val READ_FILE_CATALOG_REFRESH_BUDGET_MS = 6_000L
        private const val SUMMARY_CONTEXT_FRESHNESS_BUDGET_MS = 8_000L
        private const val SUMMARY_TOOL_FRESHNESS_TIMEOUT_MS = 1_500L
        const val PERSISTED_FILES_DEFAULT_LIMIT = 50
        const val MAX_MANUAL_BATCH_BUDGET = 999
        private const val REQUIRED_SUMMARY_PREVIOUS_CHAPTERS = 4
        private const val TRIGGER_MANUAL_SUMMARY = "manual_summary"
        private const val SUMMARY_ONLY_ROLE_CARD_ID = "__reading_summary_only__"
        private const val SUMMARY_ONLY_ROLE_CARD_NAME = "章节摘要"

        @Volatile
        private var instance: ReadingCompanionService? = null

        fun getInstance(context: Context): ReadingCompanionService =
            instance ?: synchronized(this) {
                instance ?: ReadingCompanionService(context).also { instance = it }
            }
    }
}

class ReadingCompanionIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val packageManager = PackageManager.getInstance(
                applicationContext,
                AIToolHandler.getInstance(applicationContext),
            )
            if (
                !packageManager.isPackageEnabled(ReadingCompanionService.TOOLPKG_ID) ||
                !packageManager.isPackageEnabled(ReadingCompanionService.SUBPACKAGE_NAME)
            ) {
                return Result.success()
            }
            val service = ReadingCompanionService.getInstance(applicationContext)
            val refresh = service.refreshAndIndex(
                maxCompletedChapters = 8,
                maxKnowledgeChapters = 2,
                scheduleMore = false,
            )
            if (refresh.remainingCompletedChapters > 0 && refresh.indexedChapters > 0) {
                service.scheduleBackgroundIndexContinuation()
            }
            Result.success()
        } catch (_: ReaderProviderException) {
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        } catch (_: Throwable) {
            Result.failure()
        }
    }
}
