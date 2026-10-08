package com.ai.assistance.operit.features.reading

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class AutoCommentaryGenerationResult(
    val bookId: String,
    val chapterIndex: Int?,
    val status: String,
    val commentCount: Int,
    val runId: Long? = null,
    val execution: AutoCommentModelExecution? = null,
)

private fun AutoCommentaryGenerationResult.toJson(): JSONObject = JSONObject().apply {
    put("bookId", bookId)
    put("chapterIndex", chapterIndex)
    put("chapterNumber", chapterIndex?.plus(1))
    put("status", status)
    put("commentCount", commentCount)
    put("runId", runId)
    execution?.let { model ->
        put(
            "execution",
            JSONObject()
                .put("roleCardId", model.roleCardId)
                .put("roleCardName", model.roleCardName)
                .put("modelConfigId", model.configId)
                .put("modelConfigName", model.configName)
                .put("modelIndex", model.modelIndex)
                .put("modelSource", model.modelSource)
                .put("provider", model.provider)
                .put("model", model.model),
        )
    }
}

/**
 * Selects a bounded, deterministic set of manual-batch targets. The caller still performs the
 * per-chapter claim/freshness check before generating, but the explicit indices ensure a batch
 * cannot fall back to the same "next" chapter on every iteration.
 */
internal const val MANUAL_COMMENTARY_SCOPE_AHEAD = "ahead"
internal const val MANUAL_COMMENTARY_SCOPE_READ = "read"

internal fun manualCommentaryAnchorMatches(
    expectedBookId: String,
    expectedSourceId: String,
    actualBookId: String,
    actualSourceId: String?,
): Boolean =
    actualBookId == expectedBookId &&
        actualSourceId == expectedSourceId

internal fun selectManualCommentaryTargets(
    currentChapterIndex: Int,
    upperChapterIndex: Int,
    availableChapterIndices: Iterable<Int>,
    count: Int,
    startChapterIndex: Int? = null,
    scope: String = MANUAL_COMMENTARY_SCOPE_AHEAD,
): List<Int> {
    if (count <= 0) return emptyList()
    val explicitRange = scope == MANUAL_COMMENTARY_SCOPE_READ
    val first =
        if (explicitRange) {
            maxOf(0, startChapterIndex ?: 0)
        } else {
            maxOf(currentChapterIndex + 1, startChapterIndex ?: (currentChapterIndex + 1))
        }
    val last = upperChapterIndex
    if (first > last) return emptyList()
    val available = availableChapterIndices.toSet()
    return (first..last)
        .filter { it in available }
        .distinct()
        .take(count)
}

class AutoCommentRoleNotSelectedException :
    IllegalStateException("请先为当前书籍选择段评角色卡")

class AutoCommentRoleUnavailableException :
    IllegalStateException("所选段评角色卡已不存在，请重新选择")

/** Only expose known protocol failures; provider stack traces can contain private book data. */
internal fun readingCompanionFailureDetail(error: Throwable): String? {
    if (error !is ReaderProviderException) return null
    val known = listOf(
        "未找到书籍", "未找到章节", "未找到书源",
        "该章节使用特殊排版，暂不生成 AI 段评",
        "当前章节的安全正文位置暂不可用", "当前章节的安全正文暂不可用",
        "拒绝读取未读章节", "目录已更新，请重试读取章节",
        "Legado AI 段评章节响应不是对象", "Legado AI 段评章节身份不一致",
        "Legado AI 段评章节缺少段落契约", "Legado AI 段评章节缺少段落",
        "Legado AI 段评段落格式错误", "Legado AI 段评段落编号不连续",
        "Legado AI 段评章节正文为空", "Legado 目录缺少目标章节身份",
        "Legado 目录在正文读取期间发生变化",
        "Legado ContentProvider 返回空游标", "Legado ContentProvider 缺少 result 列",
    )
    val firstLine = error.message.orEmpty().lineSequence().firstOrNull().orEmpty()
    return known.firstOrNull { firstLine == it || firstLine.endsWith(": $it") }
}

internal fun safeReadingCompanionError(error: Throwable): String = when (error) {
    is AutoCommentModelTimeoutException -> "model_timeout"
    is AutoCommentContextTooSmallException -> "model_context_too_small"
    is AutoCommentRoleNotSelectedException -> "role_not_selected"
    is AutoCommentRoleUnavailableException -> "role_unavailable"
    is ReaderProviderException -> when (error.reason) {
        ReaderProviderException.Reason.LEGADO_NOT_INSTALLED -> "legado_not_installed"
        ReaderProviderException.Reason.CONNECTION_FAILED -> "legado_connection_failed"
        ReaderProviderException.Reason.EMPTY_BOOKSHELF -> "legado_empty_bookshelf"
        ReaderProviderException.Reason.NO_RECENT_BOOK -> "legado_no_recent_book"
        ReaderProviderException.Reason.CHAPTER_READ_FAILED -> "legado_chapter_read_failed"
        ReaderProviderException.Reason.UNSAFE_POSITION -> "legado_unsafe_position"
        ReaderProviderException.Reason.INVALID_RESPONSE -> "legado_invalid_response"
    }
    is IllegalArgumentException -> "invalid_model_response"
    else -> "unknown_error"
}

class ReadingCompanionAutoCommentary private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val provider: ReaderProvider = LegadoReaderProvider(appContext)
    private val store = ReadingCompanionStore(appContext)
    private val modelGateway = ReadingCompanionModelGateway(appContext)
    private val subagentCoordinator = ReadingCompanionSubagentCoordinator.getInstance(appContext)
    @Volatile
    private var activeManualCommentaryBatchId: String? = null
    @Volatile
    private var manualCommentaryStopRequested = false

    suspend fun generateNextChapter(
        force: Boolean = false,
        runtime: ToolExecutionManager.ToolRuntimeContext? = null,
        trigger: String = TRIGGER_BACKGROUND,
        restartedFromRunId: Long? = null,
        targetChapterIndex: Int? = null,
        allowExplicitTarget: Boolean = false,
        expectedManualBookId: String? = null,
        expectedManualTargetSourceId: String? = null,
    ): AutoCommentaryGenerationResult {
        store.interruptStaleAutoCommentRuns(
            staleBefore = System.currentTimeMillis() - GENERATING_STALE_AFTER_MS,
        )
        // 阶段 3 起三条路径（后台/手动/对话内）全部走专用 subagent 执行器。
        val runId =
            store.startAutoCommentRun(
                trigger = trigger,
                executionMode = AUTO_COMMENT_RUN_EXECUTION_MODE_SUBAGENT,
                restartedFromRunId = restartedFromRunId,
            )
        return try {
            generateNextChapterInRun(
                runId = runId,
                force = force,
                runtime = runtime,
                trigger = trigger,
                targetChapterIndex = targetChapterIndex,
                allowExplicitTarget = allowExplicitTarget,
                expectedManualBookId = expectedManualBookId,
                expectedManualTargetSourceId = expectedManualTargetSourceId,
            )
        } catch (cancelled: CancellationException) {
            // 进程/Worker 停止：标 interrupted + 释放 claim；child transcript 保留（不删除），
            // 重启后由对账放行下一次全新 run。
            val now = System.currentTimeMillis()
            store.recordAutoCommentRunTrace(
                runId = runId,
                operation = "generation_cancelled",
                status = "interrupted",
                startedAt = now,
                finishedAt = now,
                metadataJson =
                    JSONObject()
                        .put("trigger", trigger)
                        .put("reason", "cancelled")
                        .toString(),
            )
            store.markRunInterrupted(runId = runId, errorMessage = "cancelled")
            throw cancelled
        } catch (error: Throwable) {
            store.finishAutoCommentRun(
                runId = runId,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_FAILED,
                errorMessage = safeReadingCompanionError(error),
            )
            throw error
        } finally {
            // 剪枝联动：把被删旧 run 的 hidden audit child / 空根同步到主库子树删除
            // （fail-safe：排空本身异常不掩盖生成结果）。
            runCatching { store.flushPrunedRunChatCleanup() }
            // 挂账队列丢失兜底：从 reading.db 现状重建（见 Store.runOrphanChatCleanup）。
            runCatching { store.runOrphanChatCleanup() }
        }
    }

    /**
     * Explicit user-triggered commentary batch. Targets are fixed before the first model run and
     * each index is visited at most once. Ahead batches skip fresh chapters; an explicit `read`
     * transport scope force-regenerates its selected range, including current or unread chapters.
     */
    suspend fun generateManualBatch(
        count: Int,
        startChapterIndex: Int? = null,
        endChapterIndex: Int? = null,
        scope: String = MANUAL_COMMENTARY_SCOPE_AHEAD,
        batchId: String? = null,
        expectedBookId: String? = null,
        runtime: ToolExecutionManager.ToolRuntimeContext? = null,
        onProgress: ((JSONObject) -> Unit)? = null,
    ): JSONObject {
        val normalizedBatchId = batchId.orEmpty().trim().takeIf(String::isNotEmpty)
        if (normalizedBatchId != null) {
            activeManualCommentaryBatchId = normalizedBatchId
            manualCommentaryStopRequested = false
        }
        return try {
            generateManualBatchInternal(
                count = count,
                startChapterIndex = startChapterIndex,
                endChapterIndex = endChapterIndex,
                scope = scope,
                batchId = normalizedBatchId,
                expectedBookId = expectedBookId?.trim()?.takeIf(String::isNotEmpty),
                runtime = runtime,
                onProgress = onProgress,
            )
        } finally {
            if (
                normalizedBatchId != null &&
                activeManualCommentaryBatchId == normalizedBatchId
            ) {
                activeManualCommentaryBatchId = null
                manualCommentaryStopRequested = false
            }
        }
    }

    private suspend fun generateManualBatchInternal(
        count: Int,
        startChapterIndex: Int?,
        endChapterIndex: Int?,
        scope: String,
        batchId: String?,
        expectedBookId: String?,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
        onProgress: ((JSONObject) -> Unit)?,
    ): JSONObject {
        require(
            count in
                AutoCommentSupport.MIN_PREFETCH_AHEAD_CHAPTERS..AutoCommentSupport.MAX_PREFETCH_AHEAD_CHAPTERS,
        ) { "段评批量数量必须为 1～${AutoCommentSupport.MAX_PREFETCH_AHEAD_CHAPTERS}" }
        require(startChapterIndex == null || startChapterIndex >= 0) {
            "段评批量起始章节必须为非负索引"
        }
        require(endChapterIndex == null || endChapterIndex >= 0) {
            "段评批量结束章节必须为非负索引"
        }
        if (startChapterIndex != null && endChapterIndex != null) {
            require(endChapterIndex >= startChapterIndex) { "段评批量结束章节不能早于起始章节" }
        }
        require(
            scope == MANUAL_COMMENTARY_SCOPE_AHEAD ||
                scope == MANUAL_COMMENTARY_SCOPE_READ,
        ) { "段评批量范围必须为 ahead 或 read" }
        val explicitRange = scope == MANUAL_COMMENTARY_SCOPE_READ
        if (explicitRange) {
            require(startChapterIndex != null && endChapterIndex != null) {
                "指定章节段评必须填写起始章节和结束章节"
            }
            require(
                endChapterIndex - startChapterIndex + 1 <=
                    AutoCommentSupport.MAX_PREFETCH_AHEAD_CHAPTERS,
            ) {
                "指定章节段评每次最多生成 ${AutoCommentSupport.MAX_PREFETCH_AHEAD_CHAPTERS} 章"
            }
        }
        val requestedCount =
            if (explicitRange) {
                endChapterIndex!! - startChapterIndex!! + 1
            } else {
                count
            }
        val state = provider.getReadingState(expectedBookId ?: store.getSelectedBookId())
        require(expectedBookId == null || state.book.id == expectedBookId) {
            "当前书籍已变化，请刷新页面后重试"
        }
        val chapters = provider.getChapters(state.book.id)
        val upper =
            if (explicitRange) {
                chapters.maxOfOrNull(ReaderChapter::index) ?: -1
            } else {
                AutoCommentSupport.prefetchWindowUpperIndex(
                    state.chapterIndex,
                    store.getPrefetchAheadChapters(),
                )
            }
        // Ahead batches scan the bounded prefetch window before taking [count], so fresh chapters
        // do not consume the requested fill count. Explicit ranges use their inclusive end and
        // regenerate every catalog chapter selected by the user.
        val rangeEnd = endChapterIndex?.coerceAtMost(upper) ?: upper
        val candidateTargets = selectManualCommentaryTargets(
            currentChapterIndex = state.chapterIndex,
            upperChapterIndex = minOf(upper, rangeEnd),
            availableChapterIndices = chapters.filterNot(ReaderChapter::isVolume).map(ReaderChapter::index),
            // Scan the whole requested range before taking [count], otherwise already valid
            // chapters at the front would consume the batch and leave later missing chapters
            // unfilled.
            count = chapters.size,
            startChapterIndex = startChapterIndex,
            scope = scope,
        )
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        val chapterByIndex = chapters.associateBy(ReaderChapter::index)
        val targets =
            candidateTargets
                .mapNotNull(chapterByIndex::get)
                .let { candidates ->
                    if (explicitRange) {
                        candidates
                    } else {
                        candidates.filterNot { chapter ->
                            isFreshCommentary(
                                bookId = state.book.id,
                                chapter = chapter,
                                fileStore = fileStore,
                            )
                        }
                    }
                }
                .take(requestedCount)
        val results = JSONArray()
        val failures = JSONArray()
        val processedTargets = mutableListOf<ReaderChapter>()
        var completedCount = 0
        var modelTaskCount = 0
        var supersededChapterIndex: Int? = null
        for (targetChapter in targets) {
            if (isManualCommentaryStopRequested(batchId)) break
            val chapterIndex = targetChapter.index
            val result = try {
                generateNextChapter(
                    force = explicitRange,
                    runtime = runtime,
                    trigger = TRIGGER_MANUAL,
                    targetChapterIndex = chapterIndex,
                    allowExplicitTarget = explicitRange,
                    expectedManualBookId = state.book.id,
                    expectedManualTargetSourceId = targetChapter.sourceId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failures.put(
                    JSONObject()
                        .put("chapterIndex", chapterIndex)
                        .put("chapterNumber", chapterIndex + 1)
                        .put("status", "failed")
                        .put("error", safeReadingCompanionError(error)),
                )
                AutoCommentaryGenerationResult(
                    bookId = state.book.id,
                    chapterIndex = chapterIndex,
                    status = "failed",
                    commentCount = 0,
                )
            }
            results.put(result.toJson())
            if (result.status == STATUS_SUPERSEDED) {
                supersededChapterIndex = chapterIndex
                break
            }
            processedTargets += targetChapter
            if (
                result.execution != null ||
                result.status == STATUS_GENERATED ||
                result.status == "failed"
            ) {
                modelTaskCount += 1
            }
            if (result.status == STATUS_GENERATED || result.status == STATUS_CACHED) {
                completedCount += 1
            }
            onProgress?.invoke(
                JSONObject()
                    .put("completedCount", completedCount)
                    .put("processedCount", processedTargets.size)
                    .put("failedCount", failures.length())
                    .put("failures", JSONArray(failures.toString()))
                    .put("modelTaskCount", modelTaskCount)
                    .put("targetChapterIndices", JSONArray(processedTargets.map { it.index }))
                    .put("currentChapterNumber", chapterIndex + 1),
            )
        }
        val stopped = isManualCommentaryStopRequested(batchId)
        return JSONObject()
            .put(
                "status",
                when {
                    stopped -> "stopped"
                    supersededChapterIndex != null -> STATUS_SUPERSEDED
                    failures.length() == 0 -> "completed"
                    else -> "completed_with_failures"
                },
            )
            .put("scope", scope)
            .put("requestedCount", requestedCount)
            .put(
                "targetChapterIndices",
                JSONArray().apply { processedTargets.forEach { put(it.index) } },
            )
            .put("modelTaskCount", modelTaskCount)
            .put("completedCount", completedCount)
            .put("supersededChapterIndex", supersededChapterIndex)
            .put("failedCount", failures.length())
            .put("failures", failures)
            .put("results", results)
    }

    fun requestManualCommentaryBatchStop(batchId: String): Boolean {
        val normalizedBatchId = batchId.trim()
        if (
            normalizedBatchId.isEmpty() ||
            activeManualCommentaryBatchId != normalizedBatchId
        ) {
            return false
        }
        manualCommentaryStopRequested = true
        return true
    }

    private fun isManualCommentaryStopRequested(batchId: String?): Boolean =
        batchId != null &&
            activeManualCommentaryBatchId == batchId &&
            manualCommentaryStopRequested

    private suspend fun isFreshCommentary(
        bookId: String,
        chapter: ReaderChapter,
        fileStore: ReadingCompanionFileStore,
    ): Boolean {
        val roleCardId = store.getAutoCommentPersona(bookId)?.roleCardId ?: return false
        val storedContractHash =
            fileStore.publishedContractHash(bookId, chapter.sourceId, roleCardId)
                ?: return false
        val current =
            try {
                provider.getAnnotationChapterContent(bookId, chapter.index)
            } catch (_: ReaderProviderException) {
                return false
            }
        return current.sourceId == chapter.sourceId && current.contractHash == storedContractHash
    }

    private suspend fun generateNextChapterInRun(
        runId: Long,
        force: Boolean,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
        trigger: String,
        targetChapterIndex: Int?,
        allowExplicitTarget: Boolean,
        expectedManualBookId: String?,
        expectedManualTargetSourceId: String?,
    ): AutoCommentaryGenerationResult {
        store.updateAutoCommentRunStage(runId, AutoCommentRunStages.READING_TARGET)
        val initialState = traceOperation(
            runId = runId,
            operation = "legado_reading_state",
            metadata = JSONObject()
                .put("route", "reading/progress/query")
                .put("providerAuthority", "${appContext.packageName}.readingCompanionAnnotations"),
        ) {
            provider.getReadingState(
                expectedManualBookId
                    ?: store.getSelectedBookId().takeUnless { trigger == TRIGGER_BACKGROUND },
            )
        }
        if (
            expectedManualBookId != null &&
            initialState.book.id != expectedManualBookId
        ) {
            store.finishAutoCommentRun(
                runId = runId,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_SUPERSEDED,
            )
            return AutoCommentaryGenerationResult(
                bookId = expectedManualBookId,
                chapterIndex = targetChapterIndex,
                status = STATUS_SUPERSEDED,
                commentCount = 0,
                runId = runId,
            )
        }
        store.updateBook(initialState)
        store.prepareForState(initialState)
        val chapters = traceOperation(
            runId = runId,
            operation = "legado_chapter_list",
            metadata = JSONObject()
                .put("route", "books/chapters/query")
                .put("bookId", initialState.book.id),
        ) {
            provider.getChapters(initialState.book.id)
        }
        if (
            expectedManualBookId != null &&
            expectedManualTargetSourceId != null &&
            !manualCommentaryAnchorMatches(
                expectedBookId = expectedManualBookId,
                expectedSourceId = expectedManualTargetSourceId,
                actualBookId = initialState.book.id,
                actualSourceId =
                    targetChapterIndex?.let { expectedIndex ->
                        chapters.firstOrNull { it.index == expectedIndex }?.sourceId
                    },
            )
        ) {
            store.finishAutoCommentRun(
                runId = runId,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_SUPERSEDED,
            )
            return AutoCommentaryGenerationResult(
                bookId = expectedManualBookId,
                chapterIndex = targetChapterIndex,
                status = STATUS_SUPERSEDED,
                commentCount = 0,
                runId = runId,
            )
        }
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        fileStore.syncBookCatalog(initialState.book, chapters)
        val prefetchAhead = store.getPrefetchAheadChapters()
        val storedPersona = store.getAutoCommentPersona(initialState.book.id)
        val nextChapterIndex = firstChapterNeedingGeneration(
            runId = runId,
            bookId = initialState.book.id,
            currentChapterIndex = initialState.chapterIndex,
            prefetchAhead = prefetchAhead,
            chapters = chapters,
            roleCardId = storedPersona?.roleCardId,
            force = force,
            targetChapterIndex = targetChapterIndex,
            allowExplicitTarget = allowExplicitTarget,
            fileStore = fileStore,
        )
        if (nextChapterIndex == null) {
            store.updateAutoCommentRunTarget(
                runId = runId,
                bookId = initialState.book.id,
                chapterIndex = null,
            )
            store.finishAutoCommentRun(
                runId = runId,
                status = STATUS_NO_NEXT_CHAPTER,
            )
            return AutoCommentaryGenerationResult(
                bookId = initialState.book.id,
                chapterIndex = null,
                status = STATUS_NO_NEXT_CHAPTER,
                commentCount = 0,
                runId = runId,
            )
        }
        val expectedTargetChapter = chapters.first { it.index == nextChapterIndex }
        store.updateAutoCommentRunTarget(
            runId = runId,
            bookId = initialState.book.id,
            chapterIndex = nextChapterIndex,
            chapterTitle = expectedTargetChapter.title,
        )

        val selectedPersona = store.getAutoCommentPersona(initialState.book.id)
            ?: throw AutoCommentRoleNotSelectedException()
        val resolvedRole = modelGateway.resolveAutoCommentRole(selectedPersona.roleCardId)
        val persona = selectedPersona.copy(
            roleCardId = resolvedRole.id,
            roleCardName = resolvedRole.name,
        )
        store.updateAutoCommentPersonaNameIfRoleMatches(
            bookId = initialState.book.id,
            roleCardId = resolvedRole.id,
            roleCardName = resolvedRole.name,
        )
        val content = traceOperation(
            runId = runId,
            operation = "legado_annotation_read",
            metadata = JSONObject()
                .put("route", "book/annotationContent/query")
                .put("bookId", initialState.book.id)
                .put("chapterIndex", nextChapterIndex)
                .put("chapterTitle", expectedTargetChapter.title)
                .put("chapterSourceId", expectedTargetChapter.sourceId)
                .put("contentPolicy", "full_annotation_contract_for_prefetch"),
        ) {
            provider.getAnnotationChapterContent(
                initialState.book.id,
                nextChapterIndex,
            )
        }
        if (content.sourceId != expectedTargetChapter.sourceId) {
            error("Legado 目录在读取目标章节时发生变化，请稍后重试")
        }
        // Prefetching a target chapter is itself a processed read. Persist the exact annotation
        // body immediately after identity validation so a failed/abstained model run still leaves
        // a content-only directory for later grep/read_file access.
        fileStore.writeChapterContent(
            book = initialState.book,
            chapter = expectedTargetChapter,
            sourceContent = content.content,
            contentHashKind = ReadingCompanionFileStore.CONTENT_HASH_KIND_ANNOTATION,
        )
        store.updateAutoCommentRunTarget(
            runId = runId,
            bookId = initialState.book.id,
            chapterIndex = nextChapterIndex,
            chapterTitle = content.chapterTitle,
        )
        val contentHash = content.contractHash
        val latestPersonaBeforeGeneration =
            store.getAutoCommentPersona(initialState.book.id)
        if (
            latestPersonaBeforeGeneration?.roleCardId != persona.roleCardId ||
            latestPersonaBeforeGeneration?.roleCardName != persona.roleCardName
        ) {
            store.finishAutoCommentRun(
                runId = runId,
                status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_SUPERSEDED,
            )
            return AutoCommentaryGenerationResult(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                status = STATUS_SUPERSEDED,
                commentCount = 0,
                runId = runId,
            )
        }
        val claim = store.tryClaimAutoCommentGeneration(
            bookId = initialState.book.id,
            chapterIndex = nextChapterIndex,
            chapterTitle = content.chapterTitle,
            contentHash = contentHash,
            roleCardId = persona.roleCardId,
            roleCardName = persona.roleCardName,
            generationRunId = runId,
            force = force,
            staleAfterMs = GENERATING_STALE_AFTER_MS,
        )
        when (claim.status) {
            AutoCommentGenerationClaimStatus.CACHED -> {
                store.finishAutoCommentRun(
                    runId = runId,
                    status = STATUS_CACHED,
                    commentCount = claim.commentCount,
                )
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_CACHED,
                    commentCount = claim.commentCount,
                    runId = runId,
                )
            }
            AutoCommentGenerationClaimStatus.ALREADY_GENERATING -> {
                store.finishAutoCommentRun(
                    runId = runId,
                    status = STATUS_ALREADY_GENERATING,
                )
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_ALREADY_GENERATING,
                    commentCount = 0,
                    runId = runId,
                )
            }
            AutoCommentGenerationClaimStatus.RUN_INTERRUPTED -> {
                // run 已被 stale 清理标记为 interrupted，阻止已中断协程重新取得所有权。
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_INTERRUPTED,
                    commentCount = 0,
                    runId = runId,
                )
            }
            AutoCommentGenerationClaimStatus.CLAIMED -> Unit
        }
        return try {
            store.updateAutoCommentRunStage(runId, AutoCommentRunStages.PREPARING_CONTEXT)
            val previousContext = traceOperation(
                runId = runId,
                operation = "local_context_prepare",
                metadata = JSONObject()
                    .put("source", "reading_companion.db+legado")
                    .put("maxPreviousChapters", AutoCommentSupport.MAX_PREVIOUS_CONTEXT_CHAPTERS)
                    .put("maxPreviousCharacters", AutoCommentSupport.MAX_PREVIOUS_CONTEXT_CHARS),
            ) {
                loadPreviousCommentaryContext(
                    bookId = initialState.book.id,
                    targetChapterIndex = nextChapterIndex,
                    availableChapters = chapters,
                )
            }
            store.updateAutoCommentRunStage(runId, AutoCommentRunStages.RESOLVING_MODEL)
            val generated =
                generateViaSubagent(
                    runId = runId,
                    trigger = trigger,
                    runtime = runtime,
                    initialState = initialState,
                    nextChapterIndex = nextChapterIndex,
                    content = content,
                    chapters = chapters,
                    previousContext = previousContext,
                    persona = persona,
                )
            store.updateAutoCommentRunStage(runId, AutoCommentRunStages.SAVING_COMMENTS)
            if (trigger == TRIGGER_BACKGROUND && !isEnabled(appContext)) {
                throw CancellationException("AI 自动段评已关闭")
            }
            val latestState = traceOperation(
                runId = runId,
                operation = "legado_reading_state_recheck",
                metadata = JSONObject()
                    .put("route", "reading/progress/query")
                    .put("purpose", "pre_save_consistency_check"),
            ) {
                provider.getReadingState(
                    initialState.book.id.takeUnless { trigger == TRIGGER_BACKGROUND },
                )
            }
            val latestPersona = store.getAutoCommentPersona(initialState.book.id)
            val latestChapters = traceOperation(
                runId = runId,
                operation = "legado_chapter_list_recheck",
                metadata = JSONObject()
                    .put("route", "books/chapters/query")
                    .put("purpose", "pre_save_chapter_identity_check"),
            ) {
                provider.getChapters(initialState.book.id)
            }
            ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot()).syncBookCatalog(initialState.book, latestChapters)
            val targetIdentityUnchanged =
                latestChapters.firstOrNull { it.index == nextChapterIndex }?.sourceId ==
                    expectedTargetChapter.sourceId
            val targetStillAllowed =
                if (allowExplicitTarget) {
                    latestChapters.any { it.index == nextChapterIndex }
                } else {
                    nextChapterIndex <=
                        AutoCommentSupport.prefetchWindowUpperIndex(
                            latestState.chapterIndex,
                            prefetchAhead,
                        )
                }
            if (
                latestState.book.id != initialState.book.id ||
                !targetStillAllowed ||
                !targetIdentityUnchanged ||
                latestPersona?.roleCardId != generated.execution.roleCardId
            ) {
                store.markAutoCommentGenerationFailedIfOwned(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    generationRunId = runId,
                )
                store.finishAutoCommentRun(
                    runId = runId,
                    status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_SUPERSEDED,
                )
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_SUPERSEDED,
                    commentCount = 0,
                    runId = runId,
                    execution = generated.execution,
                )
            }
            val records = generated.comments.map { draft ->
                AutoCommentRecord(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    paragraphIndex = draft.paragraphIndex,
                    text = draft.text,
                    kind = draft.kind,
                    roleCardId = generated.execution.roleCardId,
                    roleCardName = generated.execution.roleCardName,
                    evidenceJson = AutoCommentSupport.evidenceJson(draft),
                )
            }
            check(
                store.heartbeatClaimIfOwned(
                    initialState.book.id,
                    nextChapterIndex,
                    runId,
                ),
            ) { "claim_lost：保存段评文件前生成所有权已失效" }
            val publication = fileStore.writeGeneratedChapter(
                book = initialState.book,
                chapter = expectedTargetChapter,
                sourceContent = content.content,
                contentHash = ReadingCompanionFileStore.contentHash(content.content),
                contractHash = content.contractHash,
                roleCardId = requireNotNull(generated.execution.roleCardId),
                roleCardName = requireNotNull(generated.execution.roleCardName),
                summary = generated.summary,
                comments = records,
                publishSummary = trigger != TRIGGER_BACKGROUND,
                prepareOnly = true,
            )
            fileStore.ensureCompanionMemory(
                initialState.book.id,
                requireNotNull(generated.execution.roleCardId),
            )
            val saveStartedAt = System.currentTimeMillis()
            val stored = try {
                val completed =
                    if (records.isEmpty()) {
                        store.completeAutoCommentGenerationWithNoComments(
                            bookId = initialState.book.id,
                            chapterIndex = nextChapterIndex,
                            chapterTitle = content.chapterTitle,
                            contentHash = contentHash,
                            roleCardId = requireNotNull(generated.execution.roleCardId),
                            roleCardName = requireNotNull(generated.execution.roleCardName),
                            generationRunId = runId,
                            publication = publication,
                        )
                    } else {
                        store.replaceAutoComments(
                            bookId = initialState.book.id,
                            chapterIndex = nextChapterIndex,
                            chapterTitle = content.chapterTitle,
                            contentHash = contentHash,
                            roleCardId = requireNotNull(generated.execution.roleCardId),
                            roleCardName = requireNotNull(generated.execution.roleCardName),
                            generationRunId = runId,
                            publication = publication,
                            comments = records,
                        )
                    }
                completed.also {
                    store.recordAutoCommentRunTrace(
                        runId = runId,
                        operation = "db_save_comments",
                        status = if (it) "completed" else "skipped",
                        startedAt = saveStartedAt,
                        finishedAt = System.currentTimeMillis(),
                        metadataJson = JSONObject()
                            .put("database", "reading_companion.db")
                            .put("table", "auto_comments+auto_comment_run_comments")
                            .put("bookId", initialState.book.id)
                            .put("chapterIndex", nextChapterIndex)
                            .put("commentCount", records.size)
                            .toString(),
                    )
                }
            } catch (error: Throwable) {
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = "db_save_comments",
                    status = "failed",
                    startedAt = saveStartedAt,
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = JSONObject()
                        .put("database", "reading_companion.db")
                        .put("table", "auto_comments+auto_comment_run_comments")
                        .put("error", safeReadingCompanionError(error))
                        .toString(),
                )
                throw error
            }
            if (!stored) {
                store.finishAutoCommentRun(
                    runId = runId,
                    status = ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_SUPERSEDED,
                )
                return AutoCommentaryGenerationResult(
                    bookId = initialState.book.id,
                    chapterIndex = nextChapterIndex,
                    status = STATUS_SUPERSEDED,
                    commentCount = 0,
                    runId = runId,
                    execution = generated.execution,
                )
            }
            val notifyStartedAt = System.currentTimeMillis()
            try {
                notifyAnnotationChanged()
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = "legado_annotation_notify",
                    status = "completed",
                    startedAt = notifyStartedAt,
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = JSONObject()
                        .put("authority", "${appContext.packageName}.readingCompanionAnnotations")
                        .put("uri", "content://${appContext.packageName}.readingCompanionAnnotations/reviews")
                        .put("reason", "generated_comments_ready")
                        .toString(),
                )
            } catch (error: Throwable) {
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = "legado_annotation_notify",
                    status = "failed",
                    startedAt = notifyStartedAt,
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = JSONObject()
                        .put("authority", "${appContext.packageName}.readingCompanionAnnotations")
                        .put("error", safeReadingCompanionError(error))
                        .toString(),
                )
            }
            AutoCommentaryGenerationResult(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                status = STATUS_GENERATED,
                commentCount = records.size,
                runId = runId,
                execution = generated.execution,
            )
        } catch (cancelled: CancellationException) {
            store.markAutoCommentGenerationFailedIfOwned(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                generationRunId = runId,
            )
            throw cancelled
        } catch (error: Throwable) {
            store.markAutoCommentGenerationFailedIfOwned(
                bookId = initialState.book.id,
                chapterIndex = nextChapterIndex,
                generationRunId = runId,
            )
            throw error
        }
    }

    private fun usesSubagentExecution(trigger: String): Boolean =
        trigger == TRIGGER_BACKGROUND ||
            trigger == TRIGGER_MANUAL ||
            trigger == TRIGGER_CONVERSATION

    /**
     * 手动/对话内路径：走专用 subagent 执行器（完整 transcript、5 个专用工具、非交互护栏）。
     *
     * 只负责产生候选；提交底线（claim owner -> 重读进度/角色 -> 校验仍是下一章 ->
     * replaceAutoComments）由调用方原样保留；显式提交 0 条是成功结果，解析/校验失败不会进入发布。
     */
    private suspend fun generateViaSubagent(
        runId: Long,
        trigger: String,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
        initialState: ReadingState,
        nextChapterIndex: Int,
        content: AnnotationChapterContent,
        chapters: List<ReaderChapter>,
        previousContext: List<AutoCommentContextChapter>,
        persona: AutoCommentPersona,
    ): GeneratedAutoComments {
        val modelTraceStartedAt = System.currentTimeMillis()
        val outcome =
            try {
                store.updateAutoCommentRunStage(runId, AutoCommentRunStages.WAITING_MODEL)
                // 完整人设与旧单发路径同源（combinePrompts(CHAT)），随任务 prompt 与
                // get_constraints 进入子代理上下文，而不是只给角色名。
                val rolePrompt = modelGateway.resolveAutoCommentRolePrompt(persona.roleCardId)
                subagentCoordinator.runGeneration(
                    runId = runId,
                    trigger = trigger,
                    runtime = runtime,
                    bookId = initialState.book.id,
                    bookName = initialState.book.name,
                    chapterIndex = nextChapterIndex,
                    chapterTitle = content.chapterTitle,
                    contentHash = content.contractHash,
                    persona = persona,
                    rolePrompt = rolePrompt,
                    targetContent = content.content,
                    chapters = chapters,
                    previousContext = previousContext,
                )
            } catch (error: Throwable) {
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = "model_subagent_turn",
                    status = "failed",
                    startedAt = modelTraceStartedAt,
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = JSONObject()
                        .put("trigger", trigger)
                        .put("error", safeReadingCompanionError(error))
                        .toString(),
                )
                throw error
            }
        store.updateAutoCommentRunExecution(runId, outcome.execution)
        store.updateAutoCommentRunPromptMetrics(
            runId = runId,
            targetCharacterCount =
                content.content.count { character -> !character.isWhitespace() },
            metrics =
                AutoCommentPromptMetrics(
                    previousContextChapterCount = previousContext.size,
                    previousContextCharacterCount =
                        previousContext.sumOf { it.content.trim().length },
                    contextWindowTokens = 0,
                    estimatedInputTokens =
                        (
                            content.content.trim().length +
                                previousContext.sumOf { it.content.trim().length }
                            ) / 2,
                ),
        )
        store.updateAutoCommentRunStage(runId, AutoCommentRunStages.VALIDATING_RESPONSE)
        store.recordAutoCommentRunTrace(
            runId = runId,
            operation = "model_subagent_turn",
            status = "completed",
            startedAt = modelTraceStartedAt,
            finishedAt = System.currentTimeMillis(),
            metadataJson = modelTraceMetadata(
                execution = outcome.execution,
                usage = null,
                estimatedInputTokens = store.getAutoCommentRun(runId)?.estimatedInputTokens,
            )
                .put("trigger", trigger)
                .put("subagentRunId", outcome.subagentRunId)
                .put("childChatId", outcome.childChatId)
                .put("candidateCount", outcome.comments.size)
                .put("abstained", outcome.abstained)
                .toString(),
        )
        return GeneratedAutoComments(
            comments = outcome.comments,
            execution = outcome.execution,
            summary = outcome.summary,
            usage = null,
        )
    }

    private suspend fun loadPreviousCommentaryContext(
        bookId: String,
        targetChapterIndex: Int,
        availableChapters: List<ReaderChapter>,
    ): List<AutoCommentContextChapter> {
        val chapterByIndex = availableChapters.associateBy(ReaderChapter::index)
        val chaptersNearestFirst = buildList {
            for (
                chapterIndex in (targetChapterIndex - 1) downTo
                    maxOf(0, targetChapterIndex - REQUIRED_PREVIOUS_RAW_CHAPTERS)
            ) {
                val expectedChapter = chapterByIndex[chapterIndex] ?: continue
                if (expectedChapter.isVolume) continue
                val chapter = try {
                    provider.getAnnotationChapterContent(bookId, chapterIndex)
                } catch (_: ReaderProviderException) {
                    continue
                }
                if (chapter.sourceId != expectedChapter.sourceId) continue
                add(chapter)
            }
        }
        return chaptersNearestFirst.asReversed().map { chapter ->
            AutoCommentContextChapter(
                sourceId = chapter.sourceId,
                chapterIndex = chapter.chapterIndex,
                chapterTitle = chapter.chapterTitle,
                content = chapter.content.trim(),
                excerptFromEnd = false,
            )
        }
    }

    private suspend fun firstChapterNeedingGeneration(
        runId: Long,
        bookId: String,
        currentChapterIndex: Int,
        prefetchAhead: Int,
        chapters: List<ReaderChapter>,
        roleCardId: String?,
        force: Boolean,
        targetChapterIndex: Int?,
        allowExplicitTarget: Boolean,
        fileStore: ReadingCompanionFileStore,
    ): Int? {
        val chapterByIndex = chapters.associateBy(ReaderChapter::index)
        val upper =
            AutoCommentSupport.prefetchWindowUpperIndex(currentChapterIndex, prefetchAhead)
        if (!allowExplicitTarget && currentChapterIndex >= upper) return null
        val candidateIndices =
            targetChapterIndex?.let { listOf(it) }
                ?: ((currentChapterIndex + 1)..upper).toList()
        for (chapterIndex in candidateIndices) {
            val inAllowedRange =
                if (allowExplicitTarget) {
                    chapterIndex in chapterByIndex
                } else {
                    chapterIndex in (currentChapterIndex + 1)..upper
                }
            if (!inAllowedRange) continue
            val chapter = chapterByIndex[chapterIndex] ?: continue
            if (chapter.isVolume) continue
            if (force) return chapterIndex
            val storedContractHash =
                fileStore.publishedContractHash(bookId, chapter.sourceId, roleCardId)
            if (storedContractHash != null) {
                // Freshness checks also read the annotation endpoint and can fail before
                // generation starts. Preserve the catalog identity before that request.
                store.updateAutoCommentRunTarget(
                    runId = runId,
                    bookId = bookId,
                    chapterIndex = chapter.index,
                    chapterTitle = chapter.title,
                )
                val current = provider.getAnnotationChapterContent(bookId, chapterIndex)
                if (
                    current.sourceId == chapter.sourceId &&
                    current.contractHash == storedContractHash
                ) {
                    continue
                }
            }
            if (
                store.isAutoCommentChapterInRetryCooldown(
                    bookId,
                    chapterIndex,
                    AutoCommentSupport.RETRY_FAILED_CHAPTER_AFTER_MS,
                )
            ) {
                continue
            }
            return chapterIndex
        }
        return null
    }

    private suspend fun <T> traceOperation(
        runId: Long,
        operation: String,
        metadata: JSONObject,
        block: suspend () -> T,
    ): T {
        val startedAt = System.currentTimeMillis()
        return try {
            block().also {
                store.recordAutoCommentRunTrace(
                    runId = runId,
                    operation = operation,
                    status = "completed",
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    metadataJson = metadata.toString(),
                )
            }
        } catch (error: Throwable) {
            AppLogger.w(
                "ReadingCompanion",
                "Reader operation failed: operation=$operation runId=$runId " +
                    "code=${safeReadingCompanionError(error)} " +
                    "detail=${readingCompanionFailureDetail(error) ?: "unclassified"}",
            )
            store.recordAutoCommentRunTrace(
                runId = runId,
                operation = operation,
                status = "failed",
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                metadataJson = metadata
                    .put("error", safeReadingCompanionError(error))
                    .put("errorDetail", readingCompanionFailureDetail(error))
                    .toString(),
            )
            throw error
        }
    }

    private fun modelTraceMetadata(
        execution: AutoCommentModelExecution?,
        usage: ProviderUsageSnapshot?,
        estimatedInputTokens: Int?,
        error: String? = null,
    ): JSONObject = JSONObject().apply {
        put("functionType", "CHAT")
        put("statsCategory", TokenStatCategory.READING_COMPANION.name)
        put("stream", false)
        put("enableThinking", false)
        put("availableTools", JSONArray())
        put("toolsEnabled", false)
        put("modelSource", execution?.modelSource)
        put("provider", execution?.provider)
        put("model", execution?.model)
        put("modelConfigId", execution?.configId)
        put("modelIndex", execution?.modelIndex)
        put("estimatedInputTokens", estimatedInputTokens)
        if (usage == null) {
            put("usageStatus", "unavailable_estimate_only")
        } else {
            put("usageStatus", "provider_reported")
            put("usageSource", usage.source)
            put("usageComplete", usage.completeSnapshot)
            put("actualUncachedInputTokens", usage.uncachedInputTokens)
            put("actualCachedInputTokens", usage.cachedInputTokens)
            put("actualInputTokens", usage.totalInputTokens)
            put("actualOutputTokens", usage.outputTokens)
            put("actualReasoningTokens", usage.reasoningTokens)
        }
        if (error != null) put("error", error)
    }

    suspend fun status(): JSONObject {
        settleInterruptedRuns()
        val configuration = runCatching {
            val state = provider.getReadingState(store.getSelectedBookId())
            val persona = store.getAutoCommentPersona(state.book.id)
                ?: return@runCatching null
            modelGateway.previewAutoCommentConfiguration(persona.roleCardId)
        }.getOrNull()
        return JSONObject().apply {
            put("enabled", isEnabled(appContext))
            put("mode", "subagent_multi_turn")
            put(
                "prefetchChapters",
                store.getPrefetchAheadChapters(),
            )
            put(
                "prefetchAheadChapters",
                store.getPrefetchAheadChapters(),
            )
            put("prefetchAheadRange", JSONArray().apply {
                put(AutoCommentSupport.MIN_PREFETCH_AHEAD_CHAPTERS)
                put(AutoCommentSupport.MAX_PREFETCH_AHEAD_CHAPTERS)
            })
            put("generationRequestsPerChapter", JSONObject.NULL)
            put("generationPolicyVersion", AutoCommentSupport.GENERATION_POLICY_VERSION)
            put("previousContextChapterLimit", 4)
            put("previousContextCharacterLimit", JSONObject.NULL)
            put("roleCardPolicy", "per_book_selected_character_card")
            put("modelPolicy", "caller_model_or_character_card_or_global_chat")
            put("tokenStatsCategory", TokenStatCategory.READING_COMPANION.name)
            put(
                "historyStorage",
                "reading_companion.db:auto_comment_runs+auto_comment_run_stage_events+" +
                    "auto_comment_run_comments+auto_comment_run_trace+" +
                    "auto_comment_generation_claims",
            )
            put(
                "configuration",
                configuration?.toJson() ?: JSONObject.NULL,
            )
            put("latestRun", store.getRecentAutoCommentRuns(1).firstOrNull()?.toJson())
        }
    }

    suspend fun history(limit: Int = 10): JSONObject {
        settleInterruptedRuns()
        return JSONObject().apply {
            put(
                "storage",
                "reading_companion.db:auto_comment_runs+auto_comment_run_stage_events+" +
                    "auto_comment_run_comments+auto_comment_run_trace+" +
                    "auto_comment_generation_claims",
            )
            put(
                "runs",
                JSONArray().apply {
                    store.getRecentAutoCommentRuns(limit).forEach { run -> put(run.toJson()) }
                },
            )
        }
    }

    suspend fun detail(runId: Long): JSONObject {
        require(runId > 0) { "runId is required" }
        settleInterruptedRuns()
        val run = store.getAutoCommentRun(runId)
            ?: throw IllegalArgumentException("段评任务记录不存在或已过期")
        val comments = store.getAutoCommentRunComments(runId)
        val snapshotAvailability = when {
            comments.isNotEmpty() -> "available"
            run.commentCount > 0 -> "unavailable_legacy"
            else -> "not_applicable"
        }
        return JSONObject().apply {
            put(
                "storage",
                "reading_companion.db:auto_comment_runs+auto_comment_run_stage_events+" +
                    "auto_comment_run_comments+auto_comment_run_trace+" +
                    "auto_comment_generation_claims",
            )
            put("run", run.toJson())
            put("snapshotAvailability", snapshotAvailability)
            put(
                "stages",
                JSONArray().apply {
                    store.getAutoCommentRunStageEvents(runId).forEach { event ->
                        put(event.toJson())
                    }
                },
            )
            put(
                "comments",
                JSONArray().apply {
                    comments.forEach { comment ->
                        put(comment.toJson())
                    }
                },
            )
            put(
                "operations",
                JSONArray().apply {
                    store.getAutoCommentRunTraceEvents(runId).forEach { event ->
                        put(event.toJson())
                    }
                },
            )
        }
    }

    /**
     * 列出审计隐藏聊天（hiddenReason 为 READING_COMPANION_AUDIT_ 前缀），按书分组返回
     * 根与 run 子聊天摘要；只含摘要字段，不含任何正文。对话内（isHidden=false）child
     * 不在此列。bookId 为空时列出全部书。run 子聊天按 startedAt 降序，仅返回最近
     * limit 条，同时返回未截断的总数供 UI 提示。
     */
    suspend fun listAuditChats(
        bookId: String? = null,
        limit: Int = AUDIT_CHAT_LIST_DEFAULT_LIMIT,
    ): JSONObject {
        val boundedLimit = limit.coerceIn(1, AUDIT_CHAT_LIST_MAX_LIMIT)
        val hiddenChats =
            com.ai.assistance.operit.data.db.AppDatabase
                .getDatabase(appContext)
                .chatDao()
                .getHiddenChatsDirectly()
        val rootPrefix = ReadingCompanionAudit.HIDDEN_ROOT_PREFIX
        val runPrefix = ReadingCompanionAudit.HIDDEN_RUN_PREFIX
        val runChildren =
            hiddenChats
                .mapNotNull { child ->
                    val runId =
                        child.hiddenReason.orEmpty().removePrefix(runPrefix).toLongOrNull()
                            ?: return@mapNotNull null
                    val run = store.getAutoCommentRun(runId) ?: return@mapNotNull null
                    val childBookId = run.bookId
                    if (bookId != null && childBookId != bookId) return@mapNotNull null
                    Triple(child, runId, run)
                }
                .sortedByDescending { (_, _, run) -> run.startedAt }
        val totalRunChats = runChildren.size
        val shownRunChats = runChildren.take(boundedLimit)
        val groups = LinkedHashMap<String, JSONObject>()
        shownRunChats.forEach { (child, runId, run) ->
            val childBookId = run.bookId
            val group =
                groups.getOrPut(childBookId.orEmpty()) {
                    JSONObject()
                        .put("bookId", childBookId)
                        .put("bookName", run.bookName)
                        .put("root", JSONObject.NULL)
                        .put("chats", JSONArray())
                }
            if (!group.has("bookName") || group.isNull("bookName")) {
                group.put("bookName", run.bookName)
            }
            group
                .getJSONArray("chats")
                .put(
                    JSONObject()
                        .put("chatId", child.id)
                        .put("title", child.title)
                        .put("runId", runId)
                        .put("status", run.status)
                        .put("trigger", run.trigger),
                )
        }
        val rootsByBookId =
            hiddenChats
                .filter { chat -> chat.hiddenReason?.startsWith(rootPrefix) == true }
                .associate { root ->
                    root.hiddenReason.orEmpty().removePrefix(rootPrefix) to root
                }
        // 只给“截断后仍展示 child”的书附加 root，保证列表长度跟随 limit；
        // 没有展示 child 的书不再渲染成空分组。
        rootsByBookId.forEach { (rootBookId, root) ->
            val group = groups[rootBookId] ?: return@forEach
            group.put(
                "root",
                JSONObject()
                    .put("chatId", root.id)
                    .put("title", root.title),
            )
        }
        return JSONObject()
            .put("groups", JSONArray(groups.values.toList()))
            .put("totalRunChats", totalRunChats)
            .put("shownRunChats", shownRunChats.size)
    }

    suspend fun hasCaughtUp(result: AutoCommentaryGenerationResult): Boolean {
        if (
            result.status != STATUS_GENERATED &&
            result.status != STATUS_CACHED &&
            result.status != STATUS_NO_NEXT_CHAPTER
        ) {
            return false
        }
        val latestState = provider.getReadingState()
        if (latestState.book.id != result.bookId) return false
        return !hasPendingPrefetchWork(latestState)
    }

    /**
     * 提前生成窗口 (当前章, 当前章 + prefetchAhead] 内是否还有需要本 Worker 补的章节：
     * 存在于章节列表、未被覆盖、且无其他活跃 claim。
     */
    private suspend fun hasPendingPrefetchWork(state: ReadingState): Boolean {
        val prefetchAhead = store.getPrefetchAheadChapters()
        if (prefetchAhead <= 0) return false
        val upper = AutoCommentSupport.prefetchWindowUpperIndex(state.chapterIndex, prefetchAhead)
        if (state.chapterIndex >= upper) return false
        val chapters = provider.getChapters(state.book.id)
        val chapterByIndex = chapters.associateBy(ReaderChapter::index)
        val fileStore = ReadingCompanionFileStore(appContext, publicationSnapshot = store.publicationSnapshot())
        fileStore.syncBookCatalog(state.book, chapters)
        val roleCardId = store.getAutoCommentPersona(state.book.id)?.roleCardId
        for (chapterIndex in (state.chapterIndex + 1)..upper) {
            val chapter = chapterByIndex[chapterIndex] ?: continue
            if (chapter.isVolume) continue
            val storedContractHash =
                fileStore.publishedContractHash(state.book.id, chapter.sourceId, roleCardId)
            if (storedContractHash != null) {
                val current = provider.getAnnotationChapterContent(state.book.id, chapterIndex)
                if (
                    current.sourceId == chapter.sourceId &&
                    current.contractHash == storedContractHash
                ) {
                    continue
                }
            }
            if (
                store.hasRecentAutoCommentClaim(
                    state.book.id,
                    chapterIndex,
                    GENERATING_STALE_AFTER_MS,
                )
            ) {
                continue
            }
            if (
                store.isAutoCommentChapterInRetryCooldown(
                    state.book.id,
                    chapterIndex,
                    AutoCommentSupport.RETRY_FAILED_CHAPTER_AFTER_MS,
                )
            ) {
                continue
            }
            return true
        }
        return false
    }

    fun shouldStopWithoutCatchUp(result: AutoCommentaryGenerationResult): Boolean =
        result.status == STATUS_ALREADY_GENERATING

    fun prefetchAheadChapters(): Int = store.getPrefetchAheadChapters()

    fun setPrefetchAheadChapters(value: Int): Int {
        val updated = store.setPrefetchAheadChapters(value)
        // 新窗口在空闲时立即开始补足；已有生成中的任务不会被覆盖（KEEP）。
        schedule(appContext)
        return updated
    }

    fun recentComments(bookId: String, chapterIndex: Int): JSONObject {
        val chapter = store.getAutoCommentChapter(bookId, chapterIndex)
        val comments = if (
            chapter?.status == ReadingCompanionStore.AUTO_COMMENT_STATUS_READY &&
            chapter.generationPolicyVersion == AutoCommentSupport.GENERATION_POLICY_VERSION
        ) {
            store.getAutoComments(bookId, chapterIndex)
        } else {
            emptyList()
        }
        return JSONObject().apply {
            put("bookId", bookId)
            put("chapterIndex", chapterIndex)
            put("chapterTitle", chapter?.chapterTitle)
            put("status", chapter?.status ?: "missing")
            put(
                "comments",
                JSONArray().apply {
                    comments.forEach { comment ->
                        put(
                            JSONObject()
                                .put("id", comment.id)
                                .put("paragraphIndex", comment.paragraphIndex)
                                .put("text", comment.text)
                                .put("kind", comment.kind)
                                .put("createdAt", comment.createdAt)
                        )
                    }
                },
            )
        }
    }

    private fun notifyAnnotationChanged() {
        val authority = "${appContext.packageName}.readingCompanionAnnotations"
        appContext.contentResolver.notifyChange(
            Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath("reviews")
                .build(),
            null,
        )
    }

    private fun AutoCommentRun.toJson(): JSONObject = JSONObject().apply {
        put("runId", id)
        put("bookName", bookName)
        put("chapterIndex", chapterIndex)
        put("chapterNumber", chapterIndex?.plus(1))
        put("chapterTitle", chapterTitle)
        put("trigger", trigger)
        put("status", status)
        put("stage", stage)
        put("stageUpdatedAt", stageUpdatedAt)
        put("roleCardId", roleCardId)
        put("roleCardName", roleCardName)
        put("modelConfigId", modelConfigId)
        put("modelConfigName", modelConfigName)
        put("modelIndex", modelIndex)
        put("modelSource", modelSource)
        put("provider", provider)
        put("model", model)
        put("targetCharacterCount", targetCharacterCount)
        put("contextChapterCount", contextChapterCount)
        put("contextCharacterCount", contextCharacterCount)
        put("contextWindowTokens", contextWindowTokens)
        put("estimatedInputTokens", estimatedInputTokens)
        put("actualUncachedInputTokens", actualUncachedInputTokens)
        put("actualCachedInputTokens", actualCachedInputTokens)
        put("actualInputTokens", actualInputTokens)
        put("actualOutputTokens", actualOutputTokens)
        put("actualReasoningTokens", actualReasoningTokens)
        put("actualUsageSource", actualUsageSource)
        put("actualUsageComplete", actualUsageComplete)
        put("commentCount", commentCount)
        put("error", errorMessage)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put(
            "durationMs",
            ((finishedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0),
        )
        put("executionMode", executionMode)
        put("parentChatId", parentChatId)
        put("childChatId", childChatId)
        put("subagentRunId", subagentRunId)
    }

    private fun AutoCommentRunStageEvent.toJson(): JSONObject = JSONObject().apply {
        put("stage", stage)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("durationMs", durationMs)
    }

    private fun AutoCommentRunCommentSnapshot.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("runId", runId)
        put("bookId", bookId)
        put("chapterIndex", chapterIndex)
        put("chapterNumber", chapterIndex + 1)
        put("chapterTitle", chapterTitle)
        put("contentHash", contentHash)
        put("paragraphIndex", paragraphIndex)
        put("paragraphNumber", paragraphIndex + 1)
        put("text", text)
        put("kind", kind)
        put("roleCardId", roleCardId)
        put("roleCardName", roleCardName)
        put("evidenceJson", evidenceJson)
        put("createdAt", createdAt)
    }

    private fun AutoCommentRunTraceEvent.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("runId", runId)
        put("operation", operation)
        put("status", status)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("durationMs", durationMs)
        val metadata = metadataJson?.trim().orEmpty()
        if (metadata.isBlank()) {
            put("metadata", JSONObject())
        } else {
            put(
                "metadata",
                runCatching { JSONObject(metadata) }.getOrElse { JSONObject().put("raw", metadata) },
            )
        }
    }

    private fun AutoCommentConfigurationPreview.toJson(): JSONObject = JSONObject().apply {
        put("roleCardId", roleCardId)
        put("roleCardName", roleCardName)
        put("modelSource", modelSource)
        put("modelConfigId", modelConfigId)
        put("modelConfigName", modelConfigName)
        put("modelIndex", modelIndex)
        put("provider", provider)
        put("model", model)
        put("contextWindowTokens", contextWindowTokens)
    }

    private suspend fun settleInterruptedRuns() {
        store.interruptStaleAutoCommentRuns(
            staleBefore = System.currentTimeMillis() - GENERATING_STALE_AFTER_MS,
        )
        // 剪枝联动与 status/history/detail 同一入口排空（fail-safe）。
        runCatching { store.flushPrunedRunChatCleanup() }
        // 挂账队列丢失兜底：从 reading.db 现状重建（见 Store.runOrphanChatCleanup）。
        runCatching { store.runOrphanChatCleanup() }
    }

    companion object {
        const val AUTO_COMMENT_WORK_NAME = "reading_companion_auto_commentary"
        const val AUTO_COMMENT_MANUAL_WORK_NAME =
            "reading_companion_auto_commentary_manual"
        private const val AUTO_COMMENT_DELAY_SECONDS = 20L
        private const val GENERATING_STALE_AFTER_MS = 5 * 60_000L
        private const val STATUS_GENERATED = "generated"
        private const val STATUS_CACHED = "cached"
        private const val STATUS_ALREADY_GENERATING = "already_generating"
        private const val STATUS_NO_NEXT_CHAPTER = "no_next_chapter"
        private const val STATUS_SUPERSEDED = "superseded"
        private const val STATUS_INTERRUPTED = "interrupted"
        private const val STATUS_NO_VALID_COMMENTS = "no_valid_comments"
        const val ALREADY_GENERATING_QUEUED_AT = -1L
        const val TRIGGER_BACKGROUND = "background"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_CONVERSATION = ReadingCompanionAudit.TRIGGER_CONVERSATION
        const val AUDIT_CHAT_LIST_DEFAULT_LIMIT = 10
        const val AUDIT_CHAT_LIST_MAX_LIMIT = 50
        const val REQUIRED_PREVIOUS_RAW_CHAPTERS = 4

        @Volatile
        private var instance: ReadingCompanionAutoCommentary? = null

        fun getInstance(context: Context): ReadingCompanionAutoCommentary =
            instance ?: synchronized(this) {
                instance ?: ReadingCompanionAutoCommentary(context).also { instance = it }
            }

        fun isEnabled(context: Context): Boolean {
            return AutoCommentSurfacePolicy.canGenerate(
                readingCompanionEnabled = isBasePackageEnabled(context),
                autoCommentaryEnabled = isAutoCommentaryPackageEnabled(context),
            )
        }

        /**
         * Stored comments remain readable while only the optional generator is disabled.
         *
         * The parent ToolPkg switch is the explicit boundary for the whole Reading Companion
         * surface (including the Legado read-only provider); the auto-commentary subpackage switch
         * controls future generation and scheduling only.
         */
        fun isBasePackageEnabled(context: Context): Boolean {
            val packageManager = packageManager(context)
            return AutoCommentSurfacePolicy.canReadStoredComments(
                packageManager.isPackageEnabled(ReadingCompanionService.TOOLPKG_ID),
            )
        }

        private fun isAutoCommentaryPackageEnabled(context: Context): Boolean {
            val packageManager = packageManager(context)
            return packageManager.isPackageEnabled(
                ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME,
            )
        }

        private fun packageManager(context: Context): PackageManager =
            PackageManager.getInstance(
                context.applicationContext,
                AIToolHandler.getInstance(context.applicationContext),
            )

        fun schedule(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                AUTO_COMMENT_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReadingCompanionAutoCommentWorker>()
                    .setInitialDelay(AUTO_COMMENT_DELAY_SECONDS, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        fun rescheduleAfterPersonaChange(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                AUTO_COMMENT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReadingCompanionAutoCommentWorker>()
                    .setInitialDelay(AUTO_COMMENT_DELAY_SECONDS, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /**
         * Queues a manual generation request. Returns the request timestamp when the work was
         * actually enqueued, or [ALREADY_GENERATING_QUEUED_AT] when a manual task is still
         * running (ExistingWorkPolicy.KEEP would have silently dropped the new request).
         */
        fun enqueueManual(context: Context): Long {
            val store = ReadingCompanionStore(context.applicationContext)
            store.interruptStaleAutoCommentRuns(
                staleBefore = System.currentTimeMillis() - GENERATING_STALE_AFTER_MS,
            )
            val latestRun = store.getRecentAutoCommentRuns(1).firstOrNull()
            if (
                AutoCommentSupport.shouldRejectManualEnqueue(
                    latestRunStatus = latestRun?.status,
                    activeGenerationRunning = store.hasActiveAutoCommentGeneration(
                        GENERATING_STALE_AFTER_MS,
                    ),
                )
            ) {
                return ALREADY_GENERATING_QUEUED_AT
            }
            val queuedAt = System.currentTimeMillis()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                AUTO_COMMENT_MANUAL_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReadingCompanionManualAutoCommentWorker>().build(),
            )
            return queuedAt
        }

        fun cancelIfDisabledPackage(
            context: Context,
            packageName: String,
        ) {
            if (packageName in setOf(
                    ReadingCompanionService.TOOLPKG_ID,
                    ReadingCompanionService.SUBPACKAGE_NAME,
                    "reading_companion_tasks",
                )
            ) {
                ReadingCompanionTasks.getInstance(context).cancelAll()
            }
            if (
                packageName != ReadingCompanionService.TOOLPKG_ID &&
                packageName != ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME
            ) {
                return
            }
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(AUTO_COMMENT_WORK_NAME)
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(AUTO_COMMENT_MANUAL_WORK_NAME)
        }
    }
}

class ReadingCompanionManualAutoCommentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!ReadingCompanionAutoCommentary.isEnabled(applicationContext)) {
            return Result.success()
        }
        return try {
            // 手动/后台共用：Worker 开头先做跨库对账（只 interrupt 孤儿 + 补链，不做重启式
            // 清扫，避免误杀同一进程内正在 heartbeat 的其他生成）。
            val store = ReadingCompanionStore(applicationContext)
            store.reconcileCrossDatabase()
            // 剪枝挂账队列丢失兜底：从 reading.db 现状重建（见 Store.runOrphanChatCleanup）。
            runCatching { store.runOrphanChatCleanup() }
            val commentary = ReadingCompanionAutoCommentary.getInstance(applicationContext)
            val prefetchAhead = store.getPrefetchAheadChapters().coerceAtLeast(1)
            commentary.generateManualBatch(
                count = prefetchAhead,
                runtime = null,
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.failure()
        }
    }
}

class ReadingCompanionAutoCommentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!ReadingCompanionAutoCommentary.isEnabled(applicationContext)) {
            return Result.success()
        }
        return try {
            val store = ReadingCompanionStore(applicationContext)
            // Worker 开头跨库对账（只 interrupt 孤儿 + 补链，不清扫；进程重启语义由启动时的
            // reconcileAfterProcessStart 负责，这里不再重复清扫，避免误杀并发中的对话内 run）。
            store.reconcileCrossDatabase()
            // 剪枝挂账队列丢失兜底：从 reading.db 现状重建（见 Store.runOrphanChatCleanup）。
            runCatching { store.runOrphanChatCleanup() }
            // 恢复谱系：本次后台任务是上一次被中断 run 的重启，restarted_from_run_id 只作
            // 谱系记录，绝不续写旧 child（第二、三次追更迭代不重复标注旧 run）。
            val restartedFromRunId =
                store
                    .getRecentAutoCommentRuns(READING_RECENT_RUN_SCAN)
                    .firstOrNull {
                        it.status ==
                            ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_INTERRUPTED
                    }
                    ?.id
            val commentary = ReadingCompanionAutoCommentary.getInstance(applicationContext)
            repeat(MAX_CATCH_UP_GENERATIONS) { index ->
                if (!ReadingCompanionAutoCommentary.isEnabled(applicationContext)) {
                    return Result.success()
                }
                val generated = commentary.generateNextChapter(
                    trigger = ReadingCompanionAutoCommentary.TRIGGER_BACKGROUND,
                    restartedFromRunId = if (index == 0) restartedFromRunId else null,
                )
                if (commentary.shouldStopWithoutCatchUp(generated)) return Result.success()
                if (commentary.hasCaughtUp(generated)) return Result.success()
            }
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AutoCommentModelTimeoutException) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } catch (_: ReaderProviderException) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    private companion object {
        const val MAX_CATCH_UP_GENERATIONS = 3
        /** 谱系扫描窗口（与 prune 保留量一致即可，只取最近一条 interrupted）。 */
        const val READING_RECENT_RUN_SCAN = 10
    }
}
