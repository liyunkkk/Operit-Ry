package com.ai.assistance.operit.features.reading

import android.content.Context
import com.ai.assistance.operit.util.OperitPaths
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

data class PreparedReadingPublication(
    val bookId: String, val sourceId: String, val chapterRef: String,
    val revision: String, val contractHash: String, val roleCardId: String,
)

data class PublishedSummary(
    val text: String,
    val sourceHash: String,
    val sourceHashKind: String,
    val revision: String,
    val editor: String,
    val wasReplaced: Boolean,
)

internal data class LegacyMigrationRequirements(
    val summary: Boolean,
    val comments: Boolean,
    val content: Boolean,
)

private data class FileTextRange(
    val content: String,
    val truncated: Boolean,
)

private data class FileQueryMatch(
    val text: String,
    val truncated: Boolean,
)

/**
 * Human- and agent-readable book files. SQLite remains the runtime coordinator for claims and
 * Legado queries. Summaries are editable; generated comments use immutable revisions. content.md is the last
 * successfully fetched read-only snapshot. Legado content or cleanup rules may change afterward,
 * and the snapshot refreshes only when the plugin actually processes that chapter again.
 */
class ReadingCompanionFileStore(
    context: Context,
    /**
     * Test-only root override. Production callers should leave this null so all books remain
     * under the installation-specific Operit external-storage directory.
     */
    internal val storageRootOverride: File? = null,
    private val publicationSnapshot: Map<String, String> = emptyMap(),
) {
    private val root: File by lazy(LazyThreadSafetyMode.PUBLICATION) {
        storageRootOverride ?: synchronized(PROCESS_LOCK) {
            migrateInstallationRoot(
                File(OperitPaths.operitRootPathSdcard(), "reading_companion/books"),
                context.packageName,
            )
        }
    }

    fun syncBookCatalog(book: ReaderBook, chapters: List<ReaderChapter>) = withFileStoreLock {
        val bookDir = bookDir(book.id)
        bookDir.mkdirs()
        val bookMetadataFile = File(bookDir, "book.md")
        val stableBookMetadata = buildString {
            appendLine("# ${book.name}")
            appendLine()
            appendLine("- author: ${book.author}")
            appendLine("- bookIdHash: ${sha256(book.id)}")
            appendLine("- chapterCount: ${chapters.size}")
            appendLine()
            appendLine("章节目录按每 100 章拆分在 `chapters/*/catalog.json`。")
        }
        atomicWriteIfChanged(bookMetadataFile, stableBookMetadata)
        val chapterRoot = File(bookDir, "chapters")
        val existingChapterDirectoriesByName =
            chapterRoot
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter(File::isDirectory)
                .flatMap { groupDirectory ->
                    groupDirectory
                        .listFiles()
                        .orEmpty()
                        .asSequence()
                        .filter(File::isDirectory)
                }
                .groupBy(File::getName)
        val activeGroupNames = linkedSetOf<String>()
        chapters
            .sortedBy(ReaderChapter::index)
            .groupBy { chapter -> chapter.index / CHAPTERS_PER_GROUP }
            .toSortedMap()
            .values
            .forEach { group ->
                if (group.isEmpty()) return@forEach
                val first = (group.first().index / CHAPTERS_PER_GROUP) * CHAPTERS_PER_GROUP + 1
                val last = first + CHAPTERS_PER_GROUP - 1
                val activeGroupName = groupName(first, last)
                activeGroupNames += activeGroupName
                val groupDir = File(chapterRoot, activeGroupName).apply { mkdirs() }
                group.forEach { chapter ->
                    val directoryName = chapterDirectoryName(book.id, chapter.sourceId)
                    val targetDir = File(groupDir, directoryName)
                    val existingDir =
                        existingChapterDirectoriesByName[directoryName]
                            .orEmpty()
                            .firstOrNull { directory -> directory == targetDir }
                            ?: existingChapterDirectoriesByName[directoryName].orEmpty().firstOrNull()
                    if (existingDir != null && existingDir != targetDir && !targetDir.exists()) {
                        moveDirectory(existingDir, targetDir)
                    }
                }
                atomicWriteIfChanged(
                    File(groupDir, "catalog.json"),
                    JSONObject()
                        .put("schemaVersion", SCHEMA_VERSION)
                        .put("bookIdHash", sha256(book.id))
                        .put("firstOrdinal", first)
                        .put("lastOrdinal", last)
                        .put(
                            "chapters",
                            JSONArray().apply {
                                group.forEach { chapter ->
                                    val relativePath =
                                        "$activeGroupName/" +
                                            chapterDirectoryName(book.id, chapter.sourceId)
                                    put(
                                        JSONObject()
                                            .put("chapterRef", chapterRef(book.id, chapter.sourceId))
                                            .put("ordinal", chapter.index + 1)
                                            .put("title", chapter.title)
                                            .put(
                                                "relativePath",
                                                relativePath,
                                            ),
                                    )
                                }
                            },
                        )
                        .toString(2),
                )
            }
        chapterRoot.listFiles().orEmpty()
            .filter(File::isDirectory)
            .filter { it.name !in activeGroupNames }
            .forEach { staleGroup ->
                File(staleGroup, "catalog.json").delete()
                if (staleGroup.listFiles().isNullOrEmpty()) staleGroup.delete()
            }
        val companionDir = File(bookDir, "companions")
        companionDir.mkdirs()
    }

    fun writeGeneratedChapter(
        book: ReaderBook,
        chapter: ReaderChapter,
        sourceContent: String,
        contentHash: String,
        contractHash: String,
        roleCardId: String,
        roleCardName: String,
        summary: String,
        comments: List<AutoCommentRecord>,
        publishSummary: Boolean = true,
        prepareOnly: Boolean = false,
    ) = withFileStoreLock {
        if (publishSummary) {
            require(summary.isNotBlank()) { "章节摘要不能为空" }
        }
        val chapterDir = chapterDir(book.id, chapter)
        chapterDir.mkdirs()
        recoverSummaryPublication(chapterDir)
        writeChapterContentLocked(
            book = book,
            chapter = chapter,
            sourceContent = sourceContent,
            contentHashKind = CONTENT_HASH_KIND_ANNOTATION,
        )
        val revision = java.util.UUID.randomUUID().toString()
        val outputDirectory = if (prepareOnly) File(chapterDir, "revisions/$revision").apply { mkdirs() } else chapterDir
        val metaFile = File(chapterDir, "meta.json")
        val summaryFile = File(chapterDir, "summary.md")
        val previousMeta = metaFile
            .takeIf(File::isFile)
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?: JSONObject()
        val existingSummary = summaryFile
            .takeIf(File::isFile)
            ?.readText()
            ?.trim()
            .orEmpty()
        val generatedSummaryHash = previousMeta?.optString("summaryHash").orEmpty()
        val preserveHumanSummary =
                existingSummary.isNotBlank() &&
                (
                    !previousMeta.has("summaryHash") ||
                        previousMeta.optString("editor") == "main_agent" ||
                        (
                            generatedSummaryHash.isNotBlank() &&
                                sha256Full(existingSummary) != generatedSummaryHash
                        )
                )
        val shouldPublishSummary = publishSummary && summary.isNotBlank()
        val publishedSummary =
            if (!shouldPublishSummary) {
                existingSummary
            } else if (preserveHumanSummary) {
                existingSummary
            } else {
                summary.trim()
            }
        val paragraphFingerprints =
            paragraphFingerprintMetadata(sourceContent, contractHash).orEmpty()
        atomicWrite(
            File(outputDirectory, "comments.json"),
            JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("revision", revision)
                .put(
                    "generationPolicyVersion",
                    AutoCommentSupport.GENERATION_POLICY_VERSION,
                )
                .put("status", "ready")
                .put("roleCardId", roleCardId)
                .put("roleCardName", roleCardName)
                .put(
                    "comments",
                    JSONArray().apply {
                        comments.forEach { comment ->
                            val fingerprint = paragraphFingerprints[comment.paragraphIndex]
                            put(
                                JSONObject()
                                    .put("paragraphIndex", comment.paragraphIndex)
                                    .put(
                                        "anchorId",
                                        AutoCommentSupport.paragraphId(comment.paragraphIndex),
                                    )
                                    .put("text", comment.text)
                                    .put("kind", comment.kind)
                                    .put("evidence", JSONObject(comment.evidenceJson))
                                    .put("createdAt", comment.createdAt)
                                    .apply {
                                        if (fingerprint != null) {
                                            put(
                                                PARAGRAPH_FINGERPRINT_FIELD,
                                                fingerprint.value,
                                            )
                                            put(
                                                PARAGRAPH_FINGERPRINT_UNIQUE_FIELD,
                                                fingerprint.isUnique,
                                            )
                                            put(
                                                PARAGRAPH_MAPPING_VERSION_FIELD,
                                                PARAGRAPH_MAPPING_VERSION,
                                            )
                                        }
                                    },
                            )
                        }
                    },
                )
                .toString(2),
        )
        // meta.json is the commit marker. Readers reject comments whose revision does not match it.
        // Preserve contentFileHash/contentFileHashKind written above (and any future metadata)
        // while updating only the generated-artifact fields owned by this operation.
        val publishedMeta = JSONObject(previousMeta.toString())
            .put("schemaVersion", SCHEMA_VERSION)
            .put("chapterRef", chapterRef(book.id, chapter.sourceId))
            .put("ordinal", chapter.index + 1)
            .put("title", chapter.title)
            .put(
                "contentHash",
                if (!shouldPublishSummary || preserveHumanSummary) {
                    previousMeta.optString("contentHash")
                } else {
                    contentHash
                },
            )
            .put(
                "contentHashKind",
                if (!shouldPublishSummary || preserveHumanSummary) {
                    previousMeta.optString("contentHashKind")
                } else {
                    CONTENT_HASH_KIND_ANNOTATION
                },
            )
            .put("contractHash", contractHash)
            .put("revision", revision)
            .put(
                "generationPolicyVersion",
                AutoCommentSupport.GENERATION_POLICY_VERSION,
            )
            .put(
                "summaryHash",
                if (shouldPublishSummary && publishedSummary.isNotBlank()) {
                    sha256Full(publishedSummary)
                } else {
                    previousMeta.optString("summaryHash")
                },
            )
            .put(
                "editor",
                if (!shouldPublishSummary) {
                    previousMeta.optString("editor").ifBlank { "comments_subagent" }
                } else if (preserveHumanSummary) {
                    "main_agent"
                } else {
                    "auto_commentary"
                },
            )
            .put("updatedAt", System.currentTimeMillis())
        if (prepareOnly) {
            atomicWrite(File(outputDirectory, "meta.json"), publishedMeta.toString(2))
            // Summary is independently editable. Never move the visible commentary commit marker here.
            if (shouldPublishSummary) {
                val summaryMeta = JSONObject(previousMeta.toString())
                listOf("contentHash", "contentHashKind", "summaryHash", "editor", "updatedAt").forEach {
                    summaryMeta.put(it, publishedMeta.get(it))
                }
                publishSummaryAndMetadata(chapterDir, publishedSummary, summaryMeta)
            }
        } else if (shouldPublishSummary) {
            publishSummaryAndMetadata(chapterDir, publishedSummary, publishedMeta)
        } else {
            atomicWrite(metaFile, publishedMeta.toString(2))
        }
        PreparedReadingPublication(book.id, chapter.sourceId, chapterRef(book.id, chapter.sourceId),
            revision, contractHash, roleCardId)

    }

    fun writeSummary(
        book: ReaderBook,
        chapter: ReaderChapter,
        sourceContent: String,
        summary: String,
    ) = withFileStoreLock {
        require(summary.isNotBlank()) { "章节摘要不能为空" }
        val chapterDir = chapterDir(book.id, chapter).apply { mkdirs() }
        recoverSummaryPublication(chapterDir)
        writeChapterContentLocked(
            book = book,
            chapter = chapter,
            sourceContent = sourceContent,
            contentHashKind = CONTENT_HASH_KIND_READABLE,
        )
        val metaFile = File(chapterDir, "meta.json")
        val summaryFile = File(chapterDir, "summary.md")
        val previousMeta = metaFile
            .takeIf(File::isFile)
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?: JSONObject()
        val sourceHash = sha256Full(sourceContent)
        val existingSummary = summaryFile
            .takeIf(File::isFile)
            ?.readText()
            ?.trim()
            .orEmpty()
        val previousSummaryHash = previousMeta.optString("summaryHash")
        val preserveHumanSummary =
            existingSummary.isNotBlank() &&
                (
                    !previousMeta.has("summaryHash") ||
                        previousMeta.optString("editor") == "main_agent" ||
                        (
                            previousSummaryHash.isNotBlank() &&
                                sha256Full(existingSummary) != previousSummaryHash
                        )
                )
        val publishedSummary = if (preserveHumanSummary) existingSummary else summary.trim()
        previousMeta
            .put("schemaVersion", SCHEMA_VERSION)
            .put("chapterRef", chapterRef(book.id, chapter.sourceId))
            .put("ordinal", chapter.index + 1)
            .put("title", chapter.title)
            .put(
                "contentHash",
                if (preserveHumanSummary) previousMeta.optString("contentHash") else sourceHash,
            )
            .put(
                "contentHashKind",
                if (preserveHumanSummary) {
                    previousMeta.optString("contentHashKind")
                } else {
                    CONTENT_HASH_KIND_READABLE
                },
            )
            .put("summaryHash", sha256Full(publishedSummary))
            .put("editor", if (preserveHumanSummary) "main_agent" else "summary_model")
            .put("updatedAt", System.currentTimeMillis())
        publishSummaryAndMetadata(chapterDir, publishedSummary, previousMeta)
        PublishedSummary(
            publishedSummary, previousMeta.optString("contentHash"),
            previousMeta.optString("contentHashKind"), sha256Full(publishedSummary),
            previousMeta.getString("editor"), !preserveHumanSummary,
        )
    }

    /**
     * Persist the exact Legado body fetched for a chapter.
     *
     * The content-file hash is intentionally separate from [meta.json]'s summary/annotation
     * freshness hash. A readable-body refresh must not make an old summary or comment appear
     * fresh (or overwrite a human-edited summary); the existing contentHash/contentHashKind
     * fields remain owned by the generated artifact that populated them.
     */
    fun writeChapterContent(
        book: ReaderBook,
        chapter: ReaderChapter,
        sourceContent: String,
        contentHashKind: String = CONTENT_HASH_KIND_READABLE,
    ) = withFileStoreLock {
        writeChapterContentLocked(book, chapter, sourceContent, contentHashKind)
    }

    private fun writeChapterContentLocked(
        book: ReaderBook,
        chapter: ReaderChapter,
        sourceContent: String,
        contentHashKind: String,
    ) {
        require(contentHashKind.isNotBlank()) { "正文文件哈希类型不能为空" }
        val chapterDir = chapterDir(book.id, chapter).apply { mkdirs() }
        recoverSummaryPublication(chapterDir)
        val contentFile = File(chapterDir, CONTENT_FILE_NAME)
        val contentFileHash = contentHash(sourceContent)
        val previousMeta = File(chapterDir, META_FILE_NAME)
            .takeIf(File::isFile)
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?: JSONObject()
        val existingContent = contentFile
            .takeIf(File::isFile)
            ?.let { runCatching { it.readText(StandardCharsets.UTF_8) }.getOrNull() }
        if (existingContent != null && existingContent != sourceContent) {
            backfillParagraphFingerprintsLocked(
                chapterDir = chapterDir,
                sourceContent = existingContent,
                expectedContractHash = previousMeta.optString("contractHash"),
            )
        }
        if (
            existingContent != sourceContent ||
            previousMeta.optString(CONTENT_FILE_HASH_FIELD) != contentFileHash ||
            previousMeta.optString(CONTENT_FILE_HASH_KIND_FIELD) != contentHashKind
        ) {
            // content.md is a read-only last-fetched Legado snapshot for agents. Replace it
            // atomically whenever a newly fetched body/hash changes instead of mutating in place.
            atomicWrite(contentFile, sourceContent)
        }
        previousMeta
            .put("schemaVersion", SCHEMA_VERSION)
            .put("chapterRef", chapterRef(book.id, chapter.sourceId))
            .put("ordinal", chapter.index + 1)
            .put("title", chapter.title)
            .put(CONTENT_FILE_HASH_FIELD, contentFileHash)
            .put(CONTENT_FILE_HASH_KIND_FIELD, contentHashKind)
            .put("updatedAt", System.currentTimeMillis())
        atomicWrite(File(chapterDir, META_FILE_NAME), previousMeta.toString(2))
    }

    fun migrateLegacyChapter(
        book: ReaderBook,
        chapter: ReaderChapter,
        knowledge: StoredChapterKnowledge?,
        autoChapter: AutoCommentChapter?,
        comments: List<AutoCommentRecord>,
        sourceContent: String? = null,
        contentHashKind: String? = null,
    ) = withFileStoreLock {
        if (knowledge == null && autoChapter == null && comments.isEmpty()) return
        val chapterDir = chapterDir(book.id, chapter).apply { mkdirs() }
        val metaFile = File(chapterDir, "meta.json")
        val summaryFile = File(chapterDir, "summary.md")
        val commentsFile = File(chapterDir, "comments.json")
        val initialMeta =
            metaFile.takeIf(File::isFile)
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                ?: JSONObject()
        val legacySummary = knowledge?.summary?.trim().orEmpty()
        val shouldImportSummary = !summaryFile.exists() && legacySummary.isNotBlank()
        val shouldImportComments =
            !commentsFile.exists() && (autoChapter != null || comments.isNotEmpty())
        val shouldImportContent =
            sourceContent != null &&
                (
                    !File(chapterDir, CONTENT_FILE_NAME).isFile ||
                        initialMeta.optString(CONTENT_FILE_HASH_FIELD) != contentHash(sourceContent)
                )
        if (!shouldImportSummary && !shouldImportComments && !shouldImportContent) {
            return@withFileStoreLock
        }
        if (shouldImportContent) {
            writeChapterContentLocked(
                book = book,
                chapter = chapter,
                sourceContent = sourceContent!!,
                contentHashKind =
                    contentHashKind?.takeIf(String::isNotBlank)
                        ?: CONTENT_HASH_KIND_READABLE,
            )
        }
        val previousMeta =
            metaFile.takeIf(File::isFile)
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                ?: JSONObject()
        previousMeta
            .put("schemaVersion", SCHEMA_VERSION)
            .put("chapterRef", chapterRef(book.id, chapter.sourceId))
            .put("ordinal", chapter.index + 1)
            .put("title", chapter.title)
        if (shouldImportSummary) {
            previousMeta
                .put("contentHash", knowledge?.contentHash.orEmpty())
                .put("contentHashKind", CONTENT_HASH_KIND_READABLE)
                .put("summaryHash", sha256Full(legacySummary))
            atomicWrite(summaryFile, "$legacySummary\n")
        }
        if (shouldImportComments) {
            val legacyRevision = "legacy-${autoChapter?.updatedAt ?: 0L}"
            previousMeta
                .put("contractHash", autoChapter?.contentHash.orEmpty())
                .put("revision", legacyRevision)
                .put("generationPolicyVersion", autoChapter?.generationPolicyVersion ?: 0)
            atomicWrite(
                commentsFile,
                JSONObject()
                    .put("schemaVersion", SCHEMA_VERSION)
                    .put("revision", legacyRevision)
                    .put(
                        "generationPolicyVersion",
                        autoChapter?.generationPolicyVersion ?: 0,
                    )
                    .put("status", autoChapter?.status ?: "legacy")
                    .put("roleCardId", autoChapter?.roleCardId.orEmpty())
                    .put("roleCardName", autoChapter?.roleCardName.orEmpty())
                    .put(
                        "comments",
                        JSONArray().apply {
                            comments.forEach { comment ->
                                put(
                                    JSONObject()
                                        .put("paragraphIndex", comment.paragraphIndex)
                                        .put(
                                            "anchorId",
                                            AutoCommentSupport.paragraphId(comment.paragraphIndex),
                                        )
                                        .put("text", comment.text)
                                        .put("kind", comment.kind)
                                        .put("evidence", JSONObject(comment.evidenceJson))
                                        .put("createdAt", comment.createdAt),
                                )
                            }
                        },
                    )
                    .toString(2),
            )
        }
        if (shouldImportSummary) {
            previousMeta.put("editor", "legacy_migration")
        }
        if (shouldImportSummary || shouldImportComments) {
            previousMeta
                .put(
                    "updatedAt",
                    maxOf(
                        previousMeta.optLong("updatedAt", 0L),
                        knowledge?.updatedAt ?: 0L,
                        autoChapter?.updatedAt ?: 0L,
                    ),
                )
        }
        // meta.json is the commit marker and is written last, after any imported artifacts.
        atomicWrite(metaFile, previousMeta.toString(2))
    }

    fun quarantineLegacyChapter(
        book: ReaderBook,
        chapterIndex: Int,
        knowledge: StoredChapterKnowledge?,
        autoChapter: AutoCommentChapter?,
        comments: List<AutoCommentRecord>,
        reason: String = "旧数据库只保存章节序号，无法安全绑定到当前 source URL",
    ) = withFileStoreLock {
        if (knowledge == null && autoChapter == null && comments.isEmpty()) return@withFileStoreLock
        val legacyDir = File(
            File(bookDir(book.id), "legacy-unverified"),
            "%04d".format(chapterIndex + 1),
        ).apply { mkdirs() }
        val updatedAt = maxOf(
            knowledge?.updatedAt ?: 0L,
            autoChapter?.updatedAt ?: 0L,
        )
        knowledge?.summary?.trim()?.takeIf(String::isNotBlank)?.let { summary ->
            atomicWrite(File(legacyDir, "summary.md"), "$summary\n")
        }
        atomicWrite(
            File(legacyDir, "meta.json"),
            JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("legacyChapterIndex", chapterIndex)
                .put("legacyChapterNumber", chapterIndex + 1)
                .put("legacyChapterTitle", knowledge?.chapterTitle ?: autoChapter?.chapterTitle)
                .put("identityVerified", false)
                .put("reason", reason)
                .put("contentHash", autoChapter?.contentHash.orEmpty())
                .put("updatedAt", updatedAt)
                .toString(2),
        )
        if (autoChapter != null || comments.isNotEmpty()) {
            atomicWrite(
                File(legacyDir, "comments.json"),
                JSONObject()
                    .put("schemaVersion", SCHEMA_VERSION)
                    .put("status", "quarantined")
                    .put("roleCardId", autoChapter?.roleCardId.orEmpty())
                    .put("roleCardName", autoChapter?.roleCardName.orEmpty())
                    .put(
                        "generationPolicyVersion",
                        autoChapter?.generationPolicyVersion ?: 0,
                    )
                    .put(
                        "comments",
                        JSONArray().apply {
                            comments.forEach { comment ->
                                put(
                                    JSONObject()
                                        .put("paragraphIndex", comment.paragraphIndex)
                                        .put("text", comment.text)
                                        .put("kind", comment.kind)
                                        .put("evidence", JSONObject(comment.evidenceJson))
                                        .put("createdAt", comment.createdAt),
                                )
                            }
                        },
                    )
                    .toString(2),
            )
        }
    }

    fun readSummary(
        bookId: String,
        sourceId: String,
        expectedContentHash: String? = null,
    ): String? = withFileStoreLock {
        val dir = findChapterDir(bookId, sourceId) ?: return null
        recoverSummaryPublication(dir)
        if (!expectedContentHash.isNullOrBlank()) {
            val meta = File(dir, "meta.json")
                .takeIf(File::isFile)
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                ?: return null
            if (meta.optString("contentHash") != expectedContentHash) return null
        }
        val summary = File(dir, "summary.md")
        return summary.takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotBlank)
    }

    fun readPublishedComments(
        bookId: String,
        chapterIndex: Int,
        expectedContractHash: String?,
        allowStaleFingerprintMapping: Boolean = false,
    ): JSONObject? = withFileStoreLock {
        readPublishedCommentsLocked(
            bookId = bookId,
            chapterIndex = chapterIndex,
            expectedContractHash = expectedContractHash,
            allowStaleFingerprintMapping = allowStaleFingerprintMapping,
        )
    }

    private fun readPublishedCommentsLocked(
        bookId: String,
        chapterIndex: Int,
        expectedContractHash: String?,
        allowStaleFingerprintMapping: Boolean,
    ): JSONObject? {
        if (chapterIndex < 0) return null
        val ordinal = chapterIndex + 1
        val first = (chapterIndex / CHAPTERS_PER_GROUP) * CHAPTERS_PER_GROUP + 1
        val last = first + CHAPTERS_PER_GROUP - 1
        val chapterRoot = File(bookDir(bookId), "chapters")
        val catalogFile = File(File(chapterRoot, groupName(first, last)), "catalog.json")
        if (!catalogFile.isFile) return null
        val catalog = runCatching { JSONObject(catalogFile.readText()) }.getOrNull() ?: return null
        val chapters = catalog.optJSONArray("chapters") ?: return null
        val entry = (0 until chapters.length())
            .asSequence()
            .mapNotNull { chapters.optJSONObject(it) }
            .firstOrNull { it.optInt("ordinal", -1) == ordinal }
            ?: return null
        val relativePath = entry.optString("relativePath").takeIf(String::isNotBlank)
            ?: return null
        val chapterDir = recoverCatalogChapter(chapterRoot, relativePath)
        val commentsDirectory = publishedCommentsDirectory(chapterDir)
        val meta = File(commentsDirectory, "meta.json")
            .takeIf(File::isFile)
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?: return null
        val storedContractHash = meta.optString("contractHash")
        val commentsFile = File(commentsDirectory, "comments.json")
        var comments = commentsFile
            .takeIf(File::isFile)
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?: return null
        comments = if (commentsDirectory != chapterDir) comments else
            backfillParagraphFingerprintsLocked(
                chapterDir = chapterDir,
                sourceContent =
                    File(chapterDir, CONTENT_FILE_NAME)
                        .takeIf(File::isFile)
                        ?.let {
                            runCatching {
                                it.readText(StandardCharsets.UTF_8)
                            }.getOrNull()
                        },
                expectedContractHash = storedContractHash,
                comments = comments,
            )
        val isReady =
            comments.optString("status") == "ready" &&
                comments.optString("revision") == meta.optString("revision") &&
                comments.optInt("generationPolicyVersion") ==
                    AutoCommentSupport.GENERATION_POLICY_VERSION &&
                meta.optInt("generationPolicyVersion") ==
                    AutoCommentSupport.GENERATION_POLICY_VERSION
        val remapRequired =
            !expectedContractHash.isNullOrBlank() &&
                storedContractHash != expectedContractHash
        if (remapRequired) {
            val hasSafeFingerprint =
                comments.optJSONArray("comments")?.let { values ->
                    (0 until values.length()).any { position ->
                        values.optJSONObject(position)?.let { comment ->
                            comment.optString(PARAGRAPH_FINGERPRINT_FIELD).isNotBlank() &&
                                comment.optBoolean(PARAGRAPH_FINGERPRINT_UNIQUE_FIELD) &&
                                comment.optString(PARAGRAPH_MAPPING_VERSION_FIELD) ==
                                PARAGRAPH_MAPPING_VERSION
                        } == true
                    }
                } == true
            if (!allowStaleFingerprintMapping || !isReady || !hasSafeFingerprint) {
                return JSONObject()
                    .put("ready", false)
                    .put("stale", true)
                    .put("contractHash", storedContractHash)
            }
        }
        return JSONObject()
            .put("ready", isReady)
            .put("stale", remapRequired)
            .put("remapRequired", remapRequired)
            .put("contractHash", storedContractHash)
            .put("roleCardName", comments.optString("roleCardName"))
            .put("comments", comments.optJSONArray("comments") ?: JSONArray())
    }

    private fun backfillParagraphFingerprintsLocked(
        chapterDir: File,
        sourceContent: String?,
        expectedContractHash: String,
        comments: JSONObject? = null,
    ): JSONObject {
        val commentsFile = File(chapterDir, "comments.json")
        val root =
            comments
                ?: commentsFile
                    .takeIf(File::isFile)
                    ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                ?: return JSONObject()
        if (sourceContent == null || expectedContractHash.isBlank()) return root
        val fingerprints =
            paragraphFingerprintMetadata(sourceContent, expectedContractHash) ?: return root
        val values = root.optJSONArray("comments") ?: return root
        var changed = false
        repeat(values.length()) { position ->
            val comment = values.optJSONObject(position) ?: return@repeat
            val fingerprint = fingerprints[comment.optInt("paragraphIndex")] ?: return@repeat
            if (
                comment.optString(PARAGRAPH_FINGERPRINT_FIELD) == fingerprint.value &&
                comment.optBoolean(PARAGRAPH_FINGERPRINT_UNIQUE_FIELD) ==
                fingerprint.isUnique &&
                comment.optString(PARAGRAPH_MAPPING_VERSION_FIELD) ==
                PARAGRAPH_MAPPING_VERSION
            ) {
                return@repeat
            }
            comment
                .put(PARAGRAPH_FINGERPRINT_FIELD, fingerprint.value)
                .put(PARAGRAPH_FINGERPRINT_UNIQUE_FIELD, fingerprint.isUnique)
                .put(PARAGRAPH_MAPPING_VERSION_FIELD, PARAGRAPH_MAPPING_VERSION)
            changed = true
        }
        if (changed) atomicWrite(commentsFile, root.toString(2))
        return root
    }

    fun publishedContractHash(
        bookId: String,
        sourceId: String,
        roleCardId: String?,
    ): String? = withFileStoreLock {
        publishedContractHashLocked(bookId, sourceId, roleCardId)
    }

    private fun publishedContractHashLocked(
        bookId: String,
        sourceId: String,
        roleCardId: String?,
    ): String? {
        val chapterDir = publishedCommentsDirectory(findChapterDir(bookId, sourceId) ?: return null)
        val meta = File(chapterDir, "meta.json")
            .takeIf(File::isFile)
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?: return null
        val comments = File(chapterDir, "comments.json")
            .takeIf(File::isFile)
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?: return null
        if (comments.optString("status") != "ready") return null
        if (comments.optString("revision") != meta.optString("revision")) return null
        if (
            comments.optInt("generationPolicyVersion") !=
                AutoCommentSupport.GENERATION_POLICY_VERSION ||
            meta.optInt("generationPolicyVersion") !=
                AutoCommentSupport.GENERATION_POLICY_VERSION
        ) {
            return null
        }
        if (roleCardId != null && comments.optString("roleCardId") != roleCardId) return null
        return meta.optString("contractHash").takeIf(String::isNotBlank)
    }

    /**
     * Lists persisted summaries purely from the local book catalogs; no Legado connection is
     * required. The catalog JSON written by [syncBookCatalog] keeps chapterRef/ordinal/title per
     * chapter, so this listing stays correct even while Legado is disconnected.
     */
    fun listSummaryFiles(bookId: String): JSONObject = withFileStoreLock {
        val rootDir = bookDir(bookId).canonicalFile
        val canonicalRootPath = rootDir.path
        val chapterRoot = File(rootDir, "chapters")
        val summaries = JSONArray()
        chapterRoot
            .listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .sortedBy(File::getName)
            .forEach { group ->
                val catalog =
                    File(group, "catalog.json")
                        .takeIf(File::isFile)
                        ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                        ?: return@forEach
                val entries = catalog.optJSONArray("chapters") ?: return@forEach
                repeat(entries.length()) { index ->
                    val item = entries.optJSONObject(index) ?: return@repeat
                    val relative = item.optString("relativePath").trim()
                    if (relative.isBlank()) return@repeat
                    val chapterDirectory = File(chapterRoot, relative)
                    val canonicalChapter =
                        runCatching { chapterDirectory.canonicalFile }.getOrNull() ?: return@repeat
                    if (
                        !canonicalChapter.isDirectory ||
                        canonicalChapter.path == canonicalRootPath ||
                        !canonicalChapter.path.startsWith(canonicalRootPath + File.separator)
                    ) return@repeat
                    recoverSummaryPublication(canonicalChapter)
                    val summaryFile = File(canonicalChapter, "summary.md")
                    if (!summaryFile.isFile) return@repeat
                    val meta =
                        File(summaryFile.parentFile, META_FILE_NAME)
                            .takeIf(File::isFile)
                            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                    summaries.put(
                        JSONObject()
                            .put("chapterRef", item.optString("chapterRef"))
                            .put("chapterNumber", item.optInt("ordinal", -1))
                            .put("chapterTitle", item.optString("title"))
                            .put("summary", summaryFile.readText().trim())
                            .put("path", summaryFile.absolutePath)
                            .put("contentHash", meta?.optString("contentHash").orEmpty())
                            .put(
                                "contentHashKind",
                                meta?.optString("contentHashKind").orEmpty(),
                            )
                            .put(
                                "updatedAt",
                                meta?.optLong("updatedAt") ?: summaryFile.lastModified(),
                            ),
                    )
                }
            }
        JSONObject()
            .put("summaries", summaries)
            .put("currentChapterNumber", -1)
            .put("staleCatalog", false)
    }

    /**
     * Cached fallback of [listPersistedFiles]: reads the same durable documents while only
     * consulting the locally persisted catalog JSON instead of the live Legado chapter list.
     */
    fun listPersistedFilesFromCatalogs(
        bookId: String,
        offset: Int = 0,
        limit: Int = 50,
        throughChapterIndex: Int? = null,
    ): JSONObject = withFileStoreLock {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 100)
        val rootDir = bookDir(bookId)
        val canonicalRootPath = rootDir.canonicalFile.path
        val chapterRoot = File(rootDir, "chapters")
        val entries = mutableListOf<JSONObject>()

        fun addFile(
            file: File,
            kind: String,
            chapterNumber: Int? = null,
            chapterTitle: String? = null,
        ) {
            // Untimed book/character/companion documents can contain later plot knowledge.
            if (throughChapterIndex != null &&
                (kind != "chapter" || chapterNumber == null || chapterNumber > throughChapterIndex + 1 ||
                    file.name !in setOf(CONTENT_FILE_NAME,"summary.md"))) return
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return
            if (
                !canonical.isFile ||
                canonical.name !in PERSISTED_FILE_ALLOWLIST ||
                !(
                    canonical.path == canonicalRootPath ||
                        canonical.path.startsWith(canonicalRootPath + File.separator)
                    )
            ) return
            val relative = runCatching {
                rootDir.canonicalFile.toPath().relativize(canonical.toPath()).toString()
            }.getOrNull()?.replace(File.separatorChar, '/') ?: return
            entries += JSONObject()
                .put("path", canonical.absolutePath)
                .put("relativePath", relative)
                .put("name", canonical.name)
                .put("kind", kind)
                .put(
                    "readOnly",
                    canonical.name == CONTENT_FILE_NAME ||
                        canonical.name == "catalog.json",
                )
                .apply {
                    chapterNumber?.let { put("chapterNumber", it) }
                    chapterTitle?.let { put("chapterTitle", it) }
                }
        }

        listOf("book.md", "characters.md", "ai-memory.md")
            .map { File(rootDir, it) }
            .forEach { addFile(it, "book") }
        File(rootDir, "companions")
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .sortedBy(File::getName)
            .map { File(it, "ai-memory.md") }
            .forEach { addFile(it, "companion") }

        val catalogChapterMetadata = mutableMapOf<String, Pair<Int, String>>()
        chapterRoot
            .listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .sortedBy(File::getName)
            .forEach { group ->
                val catalogFile = File(group, "catalog.json")
                addFile(catalogFile, "catalog")
                val parsed = runCatching { JSONObject(catalogFile.readText()) }.getOrNull()
                val catalogEntries = parsed?.optJSONArray("chapters") ?: return@forEach
                repeat(catalogEntries.length()) { index ->
                    val item = catalogEntries.optJSONObject(index) ?: return@repeat
                    val relative = item.optString("relativePath").trim()
                    if (relative.isBlank()) return@repeat
                    catalogChapterMetadata[relative] =
                        item.optInt("ordinal", -1) to item.optString("title")
                }
            }

        catalogChapterMetadata
            .toSortedMap()
            .forEach { (relative, metadata) ->
                val chapterDir = recoverCatalogChapter(chapterRoot, relative)
                val ordinal = metadata.first.takeIf { it > 0 }
                listOf(CONTENT_FILE_NAME, "summary.md", "comments.json", META_FILE_NAME)
                    .map { if (it == "comments.json") File(publishedCommentsDirectory(chapterDir), it) else File(chapterDir, it) }
                    .forEach { addFile(it, "chapter", ordinal, metadata.second) }
            }

        val ordered = entries
            .distinctBy { it.optString("path") }
            .sortedBy { it.optString("relativePath") }
        val page = ordered.drop(safeOffset).take(safeLimit)
        val bookName =
            File(rootDir, "book.md")
                .takeIf(File::isFile)
                ?.readText()
                ?.lineSequence()
                ?.firstOrNull()
                ?.removePrefix("#")
                ?.trim()
                .orEmpty()
        JSONObject()
            .put("bookId", bookId)
            .put("book", bookName)
            .put("rootPath", rootDir.canonicalPath)
            .put("offset", safeOffset)
            .put("limit", safeLimit)
            .put("total", ordered.size)
            .put("entries", JSONArray().apply { page.forEach(::put) })
            .put(
                "nextOffset",
                if (safeOffset + page.size < ordered.size) safeOffset + page.size
                else JSONObject.NULL,
            )
    }

    /**
     * Lists only the durable documents exposed by the persistent browser.
     *
     * The browser deliberately walks the active catalog rather than the whole directory tree:
     * stale source directories are not current chapter files and must
     * not accidentally become part of the normal browsing surface.
     */
    fun listPersistedFiles(
        book: ReaderBook,
        chapters: List<ReaderChapter>,
        offset: Int = 0,
        limit: Int = 50,
    ): JSONObject = withFileStoreLock {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 100)
        val rootDir = bookDir(book.id)
        val canonicalRootPath = rootDir.canonicalFile.path
        val chapterRoot = File(rootDir, "chapters")
        val entries = mutableListOf<JSONObject>()

        fun addFile(
            file: File,
            kind: String,
            chapterNumber: Int? = null,
            chapterTitle: String? = null,
        ) {
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return
            if (
                !canonical.isFile ||
                canonical.name !in PERSISTED_FILE_ALLOWLIST ||
                !(
                    canonical.path == canonicalRootPath ||
                        canonical.path.startsWith(canonicalRootPath + File.separator)
                    )
            ) return
            val relative = runCatching {
                rootDir.canonicalFile.toPath().relativize(canonical.toPath()).toString()
            }.getOrNull()?.replace(File.separatorChar, '/') ?: return
            entries += JSONObject()
                .put("path", canonical.absolutePath)
                .put("relativePath", relative)
                .put("name", canonical.name)
                .put("kind", kind)
                .put(
                    "readOnly",
                    canonical.name == CONTENT_FILE_NAME ||
                        canonical.name == "catalog.json",
                )
                .apply {
                    chapterNumber?.let { put("chapterNumber", it) }
                    chapterTitle?.let { put("chapterTitle", it) }
                }
        }

        // Root documents and per-role companion memories are book-level files.
        listOf("book.md", "characters.md", "ai-memory.md")
            .map { File(rootDir, it) }
            .forEach { addFile(it, "book") }
        File(rootDir, "companions")
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .sortedBy(File::getName)
            .map { File(it, "ai-memory.md") }
            .forEach { addFile(it, "companion") }

        // Catalog entries are the source of truth for which chapter directories are active.
        val catalogChapterMetadata = mutableMapOf<String, Pair<Int, String>>()
        chapters
            .sortedBy(ReaderChapter::index)
            .groupBy { it.index / CHAPTERS_PER_GROUP }
            .toSortedMap()
            .values
            .forEach { group ->
                if (group.isEmpty()) return@forEach
                val first = (group.first().index / CHAPTERS_PER_GROUP) * CHAPTERS_PER_GROUP + 1
                val catalog = File(
                    File(chapterRoot, groupName(first, first + CHAPTERS_PER_GROUP - 1)),
                    "catalog.json",
                )
                addFile(catalog, "catalog")
                val parsed = runCatching { JSONObject(catalog.readText()) }.getOrNull()
                val catalogEntries = parsed?.optJSONArray("chapters") ?: return@forEach
                repeat(catalogEntries.length()) { index ->
                    val item = catalogEntries.optJSONObject(index) ?: return@repeat
                    val relative = item.optString("relativePath").trim()
                    if (relative.isBlank()) return@repeat
                    catalogChapterMetadata[relative] =
                        item.optInt("ordinal", -1) to item.optString("title")
                }
            }

        catalogChapterMetadata
            .toSortedMap()
            .forEach { (relative, metadata) ->
                val chapterDir = recoverCatalogChapter(chapterRoot, relative)
                val ordinal = metadata.first.takeIf { it > 0 }
                listOf(CONTENT_FILE_NAME, "summary.md", "comments.json", META_FILE_NAME)
                    .map { if (it == "comments.json") File(publishedCommentsDirectory(chapterDir), it) else File(chapterDir, it) }
                    .forEach { addFile(it, "chapter", ordinal, metadata.second) }
            }

        val ordered = entries
            .distinctBy { it.optString("path") }
            .sortedBy { it.optString("relativePath") }
        val page = ordered.drop(safeOffset).take(safeLimit)
        JSONObject()
            .put("book", book.name)
            .put("bookId", book.id)
            .put("rootPath", rootDir.canonicalPath)
            .put("offset", safeOffset)
            .put("limit", safeLimit)
            .put("total", ordered.size)
            .put("entries", JSONArray().apply { page.forEach(::put) })
            .put(
                "nextOffset",
                if (safeOffset + page.size < ordered.size) safeOffset + page.size
                else JSONObject.NULL,
            )
    }

    /**
     * Reads one persisted browser entry after canonical-root and filename allowlist checks.
     * Every returned document is explicitly read-only; in particular content.md is never a
     * writable editing surface.  The on-disk catalogs are also consulted so a caller cannot
     * bypass pagination and read a stale source directory that no longer belongs to the current
     * book catalog.  When [maxCharacters] is supplied, the body is streamed from [offset] and
     * only that bounded range is materialized; the default null keeps the historical full-file
     * response used by the file browser.
     */
    fun chapterCacheCoverage(bookId: String, beforeChapterIndex: Int): JSONObject = withFileStoreLock {
        var total = 0
        var available = 0
        val chapterRoot = File(bookDir(bookId), "chapters")
        chapterRoot.listFiles().orEmpty().filter(File::isDirectory).forEach { group ->
            val catalog = File(group, "catalog.json").takeIf(File::isFile)?.let { JSONObject(it.readText()) }
                ?.optJSONArray("chapters") ?: return@forEach
            repeat(catalog.length()) { index ->
                val entry = catalog.getJSONObject(index)
                val ordinal = entry.optInt("ordinal", -1)
                if (ordinal > 0 && ordinal <= beforeChapterIndex) {
                    total++
                    val directory = recoverCatalogChapter(chapterRoot, entry.getString("relativePath"))
                    if (File(directory, CONTENT_FILE_NAME).isFile) available++
                }
            }
        }
        JSONObject().put("pastChapterCount", total).put("localSnapshotCount", available)
            .put("missingSnapshotCount", total - available)
            .put("note", "Search covers local snapshots only; snapshots may be partial or stale. No match does not prove absence in the book.")
    }

    /** Literal lookup over the same book-bound active files exposed by the reader. */
    fun grepPersistedFiles(bookId: String, query: String, offset: Int = 0, limit: Int = 30,
        throughChapterIndex: Int? = null): JSONObject = withFileStoreLock {
        require(query.isNotBlank()) { "query must not be blank" }
        require(offset >= 0 && limit in 1..100) { "Invalid grep page" }
        val matches = JSONArray()
        var seen = 0
        var fileOffset = 0
        var more = false
        var resultCharacters = 0
        search@ while (true) {
            val page = listPersistedFilesFromCatalogs(bookId, fileOffset, 100, throughChapterIndex)
            val entries = page.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val file = File(entry.getString("path"))
                var line = 1
                var characterOffset = 0
                file.bufferedReader().use { reader ->
                    while (true) {
                        val buffer = StringBuilder()
                        var consumed = 0
                        while (true) {
                            val code = reader.read()
                            if (code < 0) break
                            consumed++
                            if (code == 10) break
                            if (code == 13) {
                                reader.mark(1)
                                if (reader.read() == 10) consumed++ else reader.reset()
                                break
                            }
                            buffer.append(code.toChar())
                        }
                        if (consumed == 0) break
                        val text = buffer.toString()
                        val matchIndex = text.indexOf(query, ignoreCase = true)
                        if (matchIndex >= 0) {
                            if (seen++ >= offset) {
                                if (matches.length() == limit) { more = true; break }
                                val hit = JSONObject().put("path", file.absolutePath)
                                    .put("kind", entry.optString("kind"))
                                    .put("lineNumber", line).put("offset", characterOffset + matchIndex)
                                    .put("text", text.substring(maxOf(0, matchIndex - 200), minOf(text.length, matchIndex + 800)))
                                val size = hit.toString().sumOf { char -> when (char) {
                                    '"' -> 6; '&' -> 5; '<','>' -> 4; '\'' -> 6; else -> 1
                                } }
                                if (resultCharacters + size > 24_000 && matches.length() > 0) {
                                    more = true
                                    break
                                }
                                matches.put(hit)
                                resultCharacters += size
                            }
                        }
                        characterOffset += consumed
                        line++
                    }
                }
                if (more) break@search
            }
            if (page.isNull("nextOffset")) break
            fileOffset = page.getInt("nextOffset")
        }
        JSONObject().put("results", matches).put("offset", offset)
            .put("nextOffset", if (more) offset + matches.length() else JSONObject.NULL)
    }

    fun readPersistedFile(
        bookId: String,
        path: String,
        offset: Int = 0,
        maxCharacters: Int? = null,
        throughChapterIndex: Int? = null,
    ): JSONObject = withFileStoreLock {
        val requested = path.trim()
        require(requested.isNotBlank()) { "文件路径不能为空" }
        require(offset >= 0) { "文件读取偏移不能为负数" }
        require(
            maxCharacters == null ||
                maxCharacters in 1..MAX_PERSISTED_FILE_READ_CHARACTERS,
        ) {
            "文件读取范围必须为 1～$MAX_PERSISTED_FILE_READ_CHARACTERS 字符"
        }
        val safeOffset = offset
        val safeMaxCharacters = maxCharacters
        val rootDir = bookDir(bookId).canonicalFile
        val target = File(requested).canonicalFile
        val rootPath = rootDir.path
        val targetPath = target.path
        require(
            targetPath != rootPath &&
                targetPath.startsWith(rootPath + File.separator),
        ) { "文件路径必须位于当前书籍目录内" }
        require(target.name in PERSISTED_FILE_ALLOWLIST) { "不允许读取该文件类型" }
        val relative = rootDir.toPath().relativize(target.toPath()).toString()
            .replace(File.separatorChar, '/')
        require(!relative.split('/').contains("legacy-unverified")) {
            "不允许读取旧数据库隔离文件"
        }
        require(isActivePersistedPathLocked(bookId, target)) {
            "文件不属于当前书籍目录或当前章节 catalog"
        }
        if (throughChapterIndex != null) {
            var pageOffset = 0
            var allowed = false
            do {
                val page = listPersistedFilesFromCatalogs(bookId,pageOffset,100,throughChapterIndex)
                val entries = page.getJSONArray("entries")
                allowed = (0 until entries.length()).any {
                    entries.getJSONObject(it).getString("path") == target.path
                }
                val next = if (page.isNull("nextOffset")) null else page.getInt("nextOffset")
                if (allowed || next == null) break
                pageOffset = next
            } while (true)
            require(allowed) { "This task can read only chapter text and summaries through its target chapter; future and untimed memory files are excluded." }
        }
        if (target.name in setOf(CONTENT_FILE_NAME, "summary.md", "comments.json", META_FILE_NAME)) {
            recoverSummaryPublication(requireNotNull(target.parentFile))
        }
        require(target.isFile) { "文件不存在" }
        val range = readTextRange(
            file = target,
            offset = safeOffset,
            maxCharacters = safeMaxCharacters,
        )
        JSONObject()
            .put("path", target.absolutePath)
            .put("relativePath", relative)
            .put("name", target.name)
            .put("content", range.content)
            .put("readOnly", true)
            .put("offset", safeOffset)
            .put(
                "maxCharacters",
                safeMaxCharacters ?: JSONObject.NULL,
            )
            .put("returnedCharacters", range.content.length)
            .put(
                "nextOffset",
                if (range.truncated) {
                    safeOffset.toLong() + range.content.length
                } else {
                    JSONObject.NULL
                },
            )
            .put("truncated", range.truncated)
    }

    /**
     * Searches only the current book catalog for character evidence.
     *
     * The characters document is considered the durable profile source. Chapter evidence is
     * limited to [throughChapterIndex] and the active catalog entries represented by [chapters];
     * future entries and directories left behind by a source replacement are never inspected.
     * For the current chapter, [currentBodyPosition] bounds content.md to the reader's visible
     * prefix. The returned snippets share one [maxCharacters] budget.
     */
    fun findCharacterEvidence(
        bookId: String,
        chapters: List<ReaderChapter>,
        query: String,
        throughChapterIndex: Int,
        currentBodyPosition: Int? = null,
        maxCharacters: Int = DEFAULT_CHARACTER_EVIDENCE_CHARACTERS,
    ): JSONObject = withFileStoreLock {
        val normalizedQuery = query.trim()
        val safeBudget = maxCharacters.coerceAtLeast(0)
        val evidence = JSONArray()
        var remaining = safeBudget
        var truncated = false

        fun addEvidence(
            source: String,
            path: File,
            chapter: ReaderChapter?,
            match: FileQueryMatch,
        ) {
            if (match.text.isBlank()) return
            if (remaining <= 0) {
                truncated = true
                return
            }
            val bounded = match.text.take(remaining)
            if (bounded.length < match.text.length || match.truncated) {
                truncated = true
            }
            val item = JSONObject()
                .put("source", source)
                .put("path", path.absolutePath)
                .put("text", bounded)
                .put("truncated", bounded.length < match.text.length || match.truncated)
            chapter?.let {
                item
                    .put("chapterIndex", it.index)
                    .put("chapterNumber", it.index + 1)
                    .put("chapterTitle", it.title)
                    .put("sourceId", it.sourceId)
            } ?: run {
                item
                    .put("chapterIndex", JSONObject.NULL)
                    .put("chapterNumber", JSONObject.NULL)
                    .put("chapterTitle", JSONObject.NULL)
                    .put("sourceId", JSONObject.NULL)
            }
            evidence.put(item)
            remaining -= bounded.length
        }

        if (normalizedQuery.isNotBlank() && safeBudget > 0) {
            val rootDir = bookDir(bookId).canonicalFile
            val charactersFile = File(rootDir, "characters.md")
                .takeIf(File::isFile)
            charactersFile?.let { file ->
                findMatchingFileText(
                    file = file,
                    query = normalizedQuery,
                    maxCharacters = min(CHARACTER_PROFILE_MAX_CHARACTERS, remaining),
                    maxInputCharacters = CHARACTER_DOCUMENT_SCAN_MAX_CHARACTERS,
                )?.let { match ->
                    addEvidence(
                        source = "characters",
                        path = file,
                        chapter = null,
                        match = match,
                    )
                }
            }

            if (remaining > 0) {
                val activeDirectories = activeChapterDirectoriesLocked(bookId, chapters)
                chapters
                    .asSequence()
                    .filter { it.index in 0..throughChapterIndex }
                    .sortedByDescending(ReaderChapter::index)
                    .forEach { chapter ->
                        if (remaining <= 0) {
                            truncated = true
                            return@forEach
                        }
                        val chapterDirectory =
                            activeDirectories[chapterRef(bookId, chapter.sourceId)]
                                ?: return@forEach
                        // A summary is an entire-chapter artifact. It is safe only for chapters
                        // strictly before the current reading chapter; current partial progress
                        // is represented by the bounded content snapshot below.
                        if (chapter.index < throughChapterIndex) {
                            recoverSummaryPublication(chapterDirectory)
                            val summaryFile = File(chapterDirectory, "summary.md")
                                .takeIf(File::isFile)
                            summaryFile?.let { file ->
                                findMatchingFileText(
                                    file = file,
                                    query = normalizedQuery,
                                    maxCharacters = min(CHAPTER_EVIDENCE_MAX_CHARACTERS, remaining),
                                )?.let { match ->
                                    addEvidence(
                                        source = "summary",
                                        path = file,
                                        chapter = chapter,
                                        match = match,
                                    )
                                }
                            }
                        }
                        val contentFile = File(chapterDirectory, CONTENT_FILE_NAME)
                            .takeIf(File::isFile)
                        val visibleCharacters =
                            if (chapter.index == throughChapterIndex) {
                                currentBodyPosition?.coerceAtLeast(0)
                            } else {
                                null
                            }
                        if (chapter.index < throughChapterIndex || visibleCharacters != null) {
                            contentFile?.let { file ->
                                findMatchingFileText(
                                    file = file,
                                    query = normalizedQuery,
                                    maxCharacters = min(CHAPTER_EVIDENCE_MAX_CHARACTERS, remaining),
                                    maxInputCharacters = visibleCharacters,
                                )?.let { match ->
                                    addEvidence(
                                        source = "content",
                                        path = file,
                                        chapter = chapter,
                                        match = match,
                                    )
                                }
                            }
                        }
                    }
            }
        }

        JSONObject()
            .put("queryName", normalizedQuery)
            .put(
                "charactersPath",
                File(bookDir(bookId), "characters.md")
                    .takeIf(File::isFile)
                    ?.canonicalPath
                    ?: JSONObject.NULL,
            )
            .put("evidence", evidence)
            .put("matchCount", evidence.length())
            .put("truncated", truncated)
    }

    private fun readTextRange(
        file: File,
        offset: Int,
        maxCharacters: Int?,
    ): FileTextRange {
        FileInputStream(file).use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).buffered().use { reader ->
                skipCharacters(reader, offset.toLong())
                if (maxCharacters == null) {
                    val output = StringBuilder()
                    val buffer = CharArray(FILE_READ_BUFFER_CHARACTERS)
                    while (true) {
                        val count = reader.read(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count == 0) continue
                        output.append(buffer, 0, count)
                    }
                    return FileTextRange(content = output.toString(), truncated = false)
                }

                val output = StringBuilder(min(maxCharacters, FILE_READ_BUFFER_CHARACTERS))
                val buffer = CharArray(min(maxCharacters, FILE_READ_BUFFER_CHARACTERS))
                while (output.length < maxCharacters) {
                    val count = reader.read(
                        buffer,
                        0,
                        min(buffer.size, maxCharacters - output.length),
                    )
                    if (count < 0) break
                    if (count == 0) continue
                    output.append(buffer, 0, count)
                }
                val truncated = output.length == maxCharacters && reader.read() >= 0
                return FileTextRange(content = output.toString(), truncated = truncated)
            }
        }
    }

    private fun skipCharacters(reader: BufferedReader, requested: Long) {
        var remaining = requested.coerceAtLeast(0L)
        while (remaining > 0) {
            val skipped = reader.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (reader.read() < 0) break
            remaining -= 1
        }
    }

    /**
     * Reads one matching line/paragraph without materializing the rest of a potentially large
     * content.md.  [maxInputCharacters] is used for the current chapter so an unread suffix cannot
     * become evidence.
     */
    private fun findMatchingFileText(
        file: File,
        query: String,
        maxCharacters: Int,
        maxInputCharacters: Int? = null,
    ): FileQueryMatch? {
        if (!file.isFile || maxCharacters <= 0) return null
        val normalizedQuery = query.lowercase(Locale.ROOT)
        if (normalizedQuery.isBlank()) return null
        var remainingInput = maxInputCharacters?.coerceAtLeast(0)?.toLong() ?: Long.MAX_VALUE
        file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            val buffer = CharArray(FILE_READ_BUFFER_CHARACTERS)
            var tail = ""
            while (remainingInput > 0) {
                val count = reader.read(buffer, 0, minOf(buffer.size.toLong(), remainingInput).toInt())
                if (count < 0) break
                if (count == 0) continue
                remainingInput -= count
                val window = tail + String(buffer, 0, count)
                val index = window.indexOf(query, ignoreCase = true)
                if (index >= 0) {
                    val context = (maxCharacters - query.length).coerceAtLeast(0) / 2
                    val start = (index - context).coerceAtLeast(0)
                    val end = minOf(window.length, start + maxCharacters)
                    return FileQueryMatch(
                        text = window.substring(start, end),
                        truncated = start > 0 || end < window.length,
                    )
                }
                // Keep enough overlap to find names split across read buffers.
                tail = window.takeLast(maxOf(query.length - 1, maxCharacters))
            }
        }
        return null
    }

    /**
     * Resolves only catalog entries belonging to [chapters].  The directory tree may retain old
     * source snapshots, so walking every chapter directory and trusting its name would be unsafe.
     */
    private fun activeChapterDirectoriesLocked(
        bookId: String,
        chapters: List<ReaderChapter>,
    ): Map<String, File> {
        if (chapters.isEmpty()) return emptyMap()
        val byReference = chapters.associateBy { chapterRef(bookId, it.sourceId) }
        val chapterRoot = File(bookDir(bookId), "chapters").canonicalFile
        val chapterRootPath = chapterRoot.path
        val resolved = mutableMapOf<String, File>()
        chapters
            .groupBy { it.index / CHAPTERS_PER_GROUP }
            .toSortedMap()
            .values
            .forEach { group ->
                if (group.isEmpty()) return@forEach
                val first = (group.first().index / CHAPTERS_PER_GROUP) * CHAPTERS_PER_GROUP + 1
                val catalog =
                    File(
                        File(chapterRoot, groupName(first, first + CHAPTERS_PER_GROUP - 1)),
                        "catalog.json",
                    )
                if (!catalog.isFile) return@forEach
                val parsed = runCatching { JSONObject(catalog.readText()) }.getOrNull()
                    ?: return@forEach
                if (parsed.optString("bookIdHash") != sha256(bookId)) return@forEach
                val entries = parsed.optJSONArray("chapters") ?: return@forEach
                repeat(entries.length()) { index ->
                    val item = entries.optJSONObject(index) ?: return@repeat
                    val reference = item.optString("chapterRef").trim()
                    val chapter = byReference[reference] ?: return@repeat
                    if (item.optInt("ordinal", -1) != chapter.index + 1) return@repeat
                    val relative = item.optString("relativePath").trim()
                    if (relative.isBlank()) return@repeat
                    val directory =
                        runCatching { File(chapterRoot, relative).canonicalFile }.getOrNull()
                            ?: return@repeat
                    if (
                        directory.path == chapterRootPath ||
                        !directory.path.startsWith(chapterRootPath + File.separator) ||
                        !directory.isDirectory
                    ) {
                        return@repeat
                    }
                    resolved[reference] = directory
                }
            }
        return resolved
    }

    /**
     * Checks membership in the current durable browser surface using the catalogs already synced
     * for this book.  Root documents and companion memories are book-level entries; chapter files
     * are allowed only when their directory is referenced by an active catalog.json.
     */
    private fun isActivePersistedPathLocked(bookId: String, target: File): Boolean {
        val rootDir = bookDir(bookId).canonicalFile
        val targetPath = target.canonicalPath
        fun sameFile(file: File): Boolean =
            runCatching { file.canonicalFile }.getOrNull()?.let { canonical ->
                canonical.isFile && canonical.canonicalPath == targetPath
            } == true

        listOf("book.md", "characters.md", "ai-memory.md")
            .map { File(rootDir, it) }
            .forEach { if (sameFile(it)) return true }
        File(rootDir, "companions")
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .map { File(it, "ai-memory.md") }
            .forEach { if (sameFile(it)) return true }

        val chapterRoot = File(rootDir, "chapters")
        chapterRoot
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .forEach { groupDirectory ->
                val catalog = File(groupDirectory, "catalog.json")
                if (sameFile(catalog)) return true
                val parsed = runCatching { JSONObject(catalog.readText()) }.getOrNull()
                    ?: return@forEach
                val catalogEntries = parsed.optJSONArray("chapters") ?: return@forEach
                repeat(catalogEntries.length()) { index ->
                    val item = catalogEntries.optJSONObject(index) ?: return@repeat
                    val relative = item.optString("relativePath").trim()
                    if (relative.isBlank()) return@repeat
                    val chapterDirectory = File(chapterRoot, relative)
                    listOf(CONTENT_FILE_NAME, "summary.md", "comments.json", META_FILE_NAME)
                        .map { if (it == "comments.json") File(publishedCommentsDirectory(chapterDirectory), it) else File(chapterDirectory, it) }
                        .forEach { if (it.canonicalPath == targetPath) return true }
                }
            }
        return false
    }

    fun ensureCompanionMemory(bookId: String, roleCardId: String): File {
        val memory = File(
            File(File(bookDir(bookId), "companions"), sha256(roleCardId)),
            "ai-memory.md",
        )
        if (!memory.exists()) {
            memory.parentFile?.mkdirs()
            atomicWrite(
                memory,
                """
                # 我的伴读记忆

                这是我作为 AI 角色的第一人称伴读记忆，由我主动读取和维护。
                我会记录值得延续的看法、感受、猜测和共同话题，修正过时认识、合并重复内容；
                无需等待用户要求或逐次确认。猜测保持未证实标记，不记录用户尚未读到的剧情。

                ## 当前看法

                ## 未证实猜测

                ## 我们共同的话题

                ## 互动偏好
                """.trimIndent() + "\n",
            )
        }
        return memory
    }

    fun ensureCharactersDocument(bookId: String): File = withFileStoreLock {
        val document = File(bookDir(bookId), "characters.md")
        if (!document.exists()) {
            document.parentFile?.mkdirs()
            atomicWrite(
                document,
                """
                # 主要人物

                > 只记录读者当前进度内已经公开的人物信息；不要写入后台提前读取到的未来剧情。

                ## 人物条目

                <!--
                建议格式：
                ### 人物名
                - 身份与别名：
                - 与其他人物的关系：
                - 当前状态与变化：
                - 我的看法：
                -->
                """.trimIndent() + "\n",
            )
        }
        document
    }

    fun rootPath(): String = root.absolutePath

    fun bookRootPath(bookId: String): String = bookDir(bookId).absolutePath

    fun bookMetadataPath(bookId: String): String = File(bookDir(bookId), "book.md").absolutePath

    fun chaptersRootPath(bookId: String): String =
        File(bookDir(bookId), "chapters").absolutePath

    fun chapterFilePaths(bookId: String, sourceId: String): JSONObject? = withFileStoreLock {
        val dir = findChapterDir(bookId, sourceId) ?: return@withFileStoreLock null
        val content = File(dir, CONTENT_FILE_NAME).takeIf(File::isFile)
        val summary = File(dir, "summary.md").takeIf(File::isFile)
        val comments = File(publishedCommentsDirectory(dir), "comments.json").takeIf(File::isFile)
        val meta = File(dir, "meta.json").takeIf(File::isFile)
        if (content == null && summary == null && comments == null && meta == null) {
            return@withFileStoreLock null
        }
        JSONObject()
            .put("chapterDirectory", dir.absolutePath)
            .put("contentPath", content?.absolutePath ?: JSONObject.NULL)
            .put("summaryPath", summary?.absolutePath ?: JSONObject.NULL)
            .put("commentsPath", comments?.absolutePath ?: JSONObject.NULL)
            .put("metaPath", meta?.absolutePath ?: JSONObject.NULL)
    }

    fun catalogPaths(bookId: String): JSONArray = withFileStoreLock {
        JSONArray().apply {
            File(bookDir(bookId), "chapters")
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter(File::isDirectory)
                .sortedBy(File::getName)
                .map { File(it, "catalog.json") }
                .filter(File::isFile)
                .forEach { put(it.absolutePath) }
        }
    }

    /**
     * Exact grep files for chapters strictly before [beforeChapterIndex].
     * Never return recursive directory roots: they also contain old or uncommitted revisions.
     * Only the publication snapshot's current commentary file is included.
     */
    fun safeChapterSearchPaths(
        bookId: String,
        chapters: List<ReaderChapter>,
        beforeChapterIndex: Int,
    ): JSONArray =
        boundedChapterSearchPaths(bookId, chapters) { chapter ->
            chapter.index < beforeChapterIndex
        }

    fun allCurrentChapterSearchPaths(
        bookId: String,
        chapters: List<ReaderChapter>,
    ): JSONArray = boundedChapterSearchPaths(bookId, chapters) { true }

    fun summaryContentHashKind(bookId: String, sourceId: String): String? =
        withFileStoreLock {
            val dir = findChapterDir(bookId, sourceId) ?: return@withFileStoreLock null
            File(dir, "meta.json")
                .takeIf(File::isFile)
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                ?.optString("contentHashKind")
                ?.takeIf(String::isNotBlank)
        }

    fun summaryContentHash(bookId: String, sourceId: String): String? =
        withFileStoreLock {
            val dir = findChapterDir(bookId, sourceId) ?: return@withFileStoreLock null
            File(dir, "meta.json")
                .takeIf(File::isFile)
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                ?.optString("contentHash")
                ?.takeIf(String::isNotBlank)
        }

    fun contentFileHash(bookId: String, sourceId: String): String? =
        withFileStoreLock {
            val dir = findChapterDir(bookId, sourceId) ?: return@withFileStoreLock null
            File(dir, META_FILE_NAME)
                .takeIf(File::isFile)
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                ?.optString(CONTENT_FILE_HASH_FIELD)
                ?.takeIf(String::isNotBlank)
        }

    fun contentFileHashKind(bookId: String, sourceId: String): String? =
        withFileStoreLock {
            val dir = findChapterDir(bookId, sourceId) ?: return@withFileStoreLock null
            File(dir, META_FILE_NAME)
                .takeIf(File::isFile)
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                ?.optString(CONTENT_FILE_HASH_KIND_FIELD)
                ?.takeIf(String::isNotBlank)
        }

    fun hasSummary(bookId: String, sourceId: String): Boolean =
        withFileStoreLock {
            findChapterDir(bookId, sourceId)
                ?.let { File(it, "summary.md") }
                ?.isFile == true
        }

    fun hasCatalog(bookId: String): Boolean =
        File(bookDir(bookId), "chapters")
            .listFiles()
            .orEmpty()
            .any { group -> File(group, "catalog.json").isFile }

    fun isLegacyMigrationComplete(bookId: String): Boolean =
        File(bookDir(bookId), LEGACY_MIGRATION_MARKER).isFile

    internal fun legacyMigrationRequirements(
        bookId: String,
        sourceId: String,
        chapterIndex: Int,
        hasLegacySummary: Boolean,
        hasLegacyComments: Boolean,
        hasLegacyContentSource: Boolean,
    ): LegacyMigrationRequirements = withFileStoreLock {
        val chapterDir = findChapterDir(bookId, sourceId)
        val quarantineDir =
            File(
                File(bookDir(bookId), "legacy-unverified"),
                "%04d".format(chapterIndex + 1),
            )
        LegacyMigrationRequirements(
            summary =
                hasLegacySummary &&
                    chapterDir?.let { File(it, "summary.md").isFile } != true &&
                    !File(quarantineDir, "summary.md").isFile,
            comments =
                hasLegacyComments &&
                    chapterDir?.let { File(it, "comments.json").isFile } != true &&
                    !File(quarantineDir, "comments.json").isFile,
            content =
                hasLegacyContentSource &&
                    chapterDir != null &&
                    !File(chapterDir, CONTENT_FILE_NAME).isFile,
        )
    }

    fun countQuarantinedLegacyChapters(bookId: String): Int =
        File(bookDir(bookId), "legacy-unverified")
            .listFiles()
            .orEmpty()
            .count(File::isDirectory)

    fun markLegacyMigrationComplete(bookId: String) {
        atomicWrite(
            File(bookDir(bookId), LEGACY_MIGRATION_MARKER),
            "schemaVersion=$SCHEMA_VERSION\ncompletedAt=${System.currentTimeMillis()}\n",
        )
    }

    private fun bookDir(bookId: String): File = File(root, sha256(bookId))

    private fun chapterDir(bookId: String, chapter: ReaderChapter): File {
        findChapterDir(bookId, chapter.sourceId)?.let { return it }
        val first = (chapter.index / CHAPTERS_PER_GROUP) * CHAPTERS_PER_GROUP + 1
        val last = first + CHAPTERS_PER_GROUP - 1
        return File(
            File(File(bookDir(bookId), "chapters"), groupName(first, last)),
            chapterDirectoryName(bookId, chapter.sourceId),
        )
    }

    private fun findChapterDir(bookId: String, sourceId: String): File? {
        val chapterRoot = File(bookDir(bookId), "chapters")
        if (!chapterRoot.isDirectory) return null
        val name = chapterDirectoryName(bookId, sourceId)
        return chapterRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.map { File(it, name) }
            ?.firstOrNull(File::isDirectory)
            ?.also(::recoverSummaryPublication)
    }

    private fun chapterDirectoryName(bookId: String, sourceId: String): String =
        chapterRef(bookId, sourceId).removePrefix("ch_")

    private fun groupName(first: Int, last: Int): String =
        "%04d-%04d".format(first, last)

    private fun publishedCommentsDirectory(chapterDirectory: File): File {
        val revision = publicationSnapshot["ch_${chapterDirectory.name}"] ?: return chapterDirectory
        require(revision.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid publication revision" }
        return File(chapterDirectory, "revisions/$revision")
    }

    private fun recoverCatalogChapter(chapterRoot: File, relativePath: String): File {
        val directory = File(chapterRoot, relativePath).canonicalFile
        require(directory.path.startsWith(chapterRoot.canonicalPath + File.separator)) {
            "章节目录必须位于当前书籍目录内"
        }
        recoverSummaryPublication(directory)
        return directory
    }

    // A single pending record makes a summary/meta publication recoverable after process death.
    private fun publishSummaryAndMetadata(dir: File, summary: String, metadata: JSONObject) {
        val current = File(dir, "summary.md").takeIf(File::isFile)?.readText()?.trim().orEmpty()
        val pending = JSONObject()
            .put("previousSummaryHash", sha256Full(current))
            .put("summary", summary.trim())
            .put("metadata", metadata)
        atomicWrite(File(dir, SUMMARY_PUBLICATION), pending.toString())
        recoverSummaryPublication(dir)
    }

    private fun recoverSummaryPublication(dir: File) {
        val journal = File(dir, SUMMARY_PUBLICATION)
        if (!journal.isFile) return
        val pending = JSONObject(journal.readText())
        val summary = pending.getString("summary")
        val summaryFile = File(dir, "summary.md")
        val current = summaryFile.takeIf(File::isFile)?.readText()?.trim().orEmpty()
        val currentHash = sha256Full(current)
        // A real user edit after the interrupted publication must not be overwritten.
        if (currentHash == pending.getString("previousSummaryHash") || currentHash == sha256Full(summary)) {
            atomicWrite(summaryFile, "$summary\n")
            atomicWrite(File(dir, "meta.json"), pending.getJSONObject("metadata").toString(2))
        }
        check(journal.delete()) { "无法完成摘要发布记录清理" }
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile(".${target.name}.", ".new", target.parentFile)
        temporary.writeText(content, StandardCharsets.UTF_8)
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw IllegalStateException("无法发布阅读伴侣文件：${target.name}", error)
        }
    }

    private fun atomicWriteIfChanged(target: File, content: String) {
        val unchanged =
            target.isFile &&
                runCatching { target.readText(StandardCharsets.UTF_8) == content }.getOrDefault(false)
        if (!unchanged) atomicWrite(target, content)
    }

    companion object {
        private const val SUMMARY_PUBLICATION = ".summary-publication.json"

        internal fun migrateInstallationRoot(legacyRoot: File, packageName: String): File {
            require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
            val installation = File(legacyRoot.parentFile, "installations/$packageName")
            val destination = File(installation, "books")
            if (destination.isDirectory) return destination
            val staging = File(installation, "books.migrating")
            check(staging.mkdirs() || staging.isDirectory)
            if (legacyRoot.isDirectory) {
                // Only the private staging copy is replaced on retry; shared originals are retained.
                legacyRoot.copyRecursively(staging, overwrite = true)
            }
            Files.move(staging.toPath(), destination.toPath())
            return destination
        }

        const val SCHEMA_VERSION = 1
        const val CHAPTERS_PER_GROUP = 100
        const val CONTENT_FILE_NAME = "content.md"
        const val CONTENT_FILE_HASH_FIELD = "contentFileHash"
        const val CONTENT_FILE_HASH_KIND_FIELD = "contentFileHashKind"
        const val META_FILE_NAME = "meta.json"
        const val PARAGRAPH_FINGERPRINT_FIELD = "paragraphFingerprint"
        const val PARAGRAPH_FINGERPRINT_UNIQUE_FIELD = "paragraphFingerprintUnique"
        const val PARAGRAPH_MAPPING_VERSION_FIELD = "anchorMappingVersion"
        const val PARAGRAPH_MAPPING_VERSION = "paragraph-fingerprint-v1"
        const val CONTENT_HASH_KIND_ANNOTATION = "annotation_content"
        const val CONTENT_HASH_KIND_READABLE = "readable_content"
        const val CONTENT_HASH_KIND_UNKNOWN = "unknown"
        const val DEFAULT_CHARACTER_EVIDENCE_CHARACTERS = 12_000
        const val MAX_PERSISTED_FILE_READ_CHARACTERS = 64_000
        private const val CHARACTER_PROFILE_MAX_CHARACTERS = 6_000
        private const val CHAPTER_EVIDENCE_MAX_CHARACTERS = 2_000
        private const val CHARACTER_DOCUMENT_SCAN_MAX_CHARACTERS = 64_000
        private const val FILE_READ_BUFFER_CHARACTERS = 8_192
        val PERSISTED_FILE_ALLOWLIST: Set<String> =
            setOf(
                "book.md",
                "characters.md",
                "ai-memory.md",
                CONTENT_FILE_NAME,
                "summary.md",
                "comments.json",
                META_FILE_NAME,
                "catalog.json",
            )
        private const val LEGACY_MIGRATION_MARKER = ".legacy-db-migration-v1"
        private val PROCESS_LOCK = Any()

        fun chapterRef(bookId: String, sourceId: String): String =
            "ch_${sha256("$bookId\u0000$sourceId")}"

        fun contentHash(content: String): String = sha256Full(content)

        fun paragraphFingerprint(text: String): String =
            sha256Full("$PARAGRAPH_FINGERPRINT_VERSION\u0000$text")

        fun annotationContractHash(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ANNOTATION_CONTRACT_VERSION.toByteArray(StandardCharsets.UTF_8))
            content.split('\n').forEachIndexed { index, text ->
                digest.update(0)
                digest.update((index + 1).toString().toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
                digest.update(text.toByteArray(StandardCharsets.UTF_8))
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun sha256(value: String): String =
            sha256Full(value).take(24)

        private fun sha256Full(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun paragraphFingerprintMetadata(
            content: String,
            expectedContractHash: String,
        ): Map<Int, ParagraphFingerprintMetadata>? {
            if (annotationContractHash(content) != expectedContractHash) return null
            val fingerprints =
                content.split('\n').map(::paragraphFingerprint)
            val counts = fingerprints.groupingBy { it }.eachCount()
            return fingerprints.mapIndexed { index, fingerprint ->
                index + 1 to
                    ParagraphFingerprintMetadata(
                        value = fingerprint,
                        isUnique = counts[fingerprint] == 1,
                    )
            }.toMap()
        }

        private const val ANNOTATION_CONTRACT_VERSION = "legado-review-paragraphs-v1"
        private const val PARAGRAPH_FINGERPRINT_VERSION =
            "operit-review-paragraph-fingerprint-v1"
    }

    private inline fun <T> withFileStoreLock(block: () -> T): T =
        synchronized(PROCESS_LOCK, block)

    private fun boundedChapterSearchPaths(
        bookId: String,
        chapters: List<ReaderChapter>,
        include: (ReaderChapter) -> Boolean,
    ): JSONArray = withFileStoreLock {
        val paths = JSONArray()
        val directories = File(bookDir(bookId), "chapters").listFiles().orEmpty()
            .filter(File::isDirectory).flatMap { it.listFiles().orEmpty().filter(File::isDirectory) }
            .associateBy(File::getName)
        chapters.sortedBy(ReaderChapter::index).filter(include).forEach { chapter ->
            val directory = directories[chapterDirectoryName(bookId, chapter.sourceId)] ?: return@forEach
            recoverSummaryPublication(directory)
            listOf(File(directory, CONTENT_FILE_NAME), File(directory, "summary.md"),
                File(publishedCommentsDirectory(directory), "comments.json"))
                .filter(File::isFile).forEach { paths.put(it.canonicalPath) }
        }
        paths
    }

    private fun moveDirectory(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }
}

private data class ParagraphFingerprintMetadata(
    val value: String,
    val isUnique: Boolean,
)
