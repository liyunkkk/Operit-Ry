package com.ai.assistance.operit.features.reading

import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.runBlockingIoPreservingToolRuntimeContext
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阅读伴侣段评子代理的 6 个专用工具。
 *
 * 执行器只信任 callerChatId -> subagent child -> 注册会话的反查结果；模型传入的任何
 * bookId/chapterIndex/书籍参数一律忽略（isolated prompts 也不暴露这些参数）。
 * submit_summary / submit_comments 只写入本次 run 会话候选，绝不直接发布。每次工具执行前后执行 claim
 * 心跳，affected != 1 即 claim_lost 并停止本 run。
 */
object ReadingCompanionSubagentTools {
    const val TOOL_LIST_CHAPTERS = "reading_commentary_list_chapters"
    const val TOOL_READ_CHAPTER = "reading_commentary_read_chapter"
    const val TOOL_READ_FILE = "reading_commentary_read_file"
    const val TOOL_GREP = "reading_commentary_grep"
    const val TOOL_SUBMIT_SUMMARY = "reading_commentary_submit_summary"
    const val TOOL_SUBMIT_COMMENTS = "reading_commentary_submit_comments"
    const val CODE_PARTIAL_CANDIDATES_REJECTED = "partial_candidates_rejected"

    /** 隔离工具面只允许这 6 个名字。 */
    val TOOL_NAMES: Set<String> =
        setOf(
            TOOL_LIST_CHAPTERS,
            TOOL_READ_CHAPTER,
            TOOL_READ_FILE,
            TOOL_GREP,
            TOOL_SUBMIT_SUMMARY,
            TOOL_SUBMIT_COMMENTS,
        )

    /** submit_comments 成功时通过 interruptTurn 收尾；失败时允许模型修正。 */
    val TERMINAL_TOOL_NAMES: Set<String> = emptySet()

    /**
     * 内部能力受限工具集合（与 PermissionReviewInternalTools 同语义）：只在校验过的审计
     * 会话内可执行，权限检查直接放行，避免后台/对话内路径弹出确认框。
     */
    val CAPABILITY_BOUND_NAMES: Set<String> = TOOL_NAMES

    private const val ERROR_NO_CALLER =
        "无法确认阅读伴读子代理身份（缺少 callerChatId），本工具仅限段评审计子代理内部调用"
    private const val ERROR_NO_SESSION =
        "未找到阅读伴读执行会话（childChatId 未注册或已完成），无法继续"

    /**
     * 6 个工具的 isolated 提示词（同一来源：ToolRegistration 注册、SubagentCoordinator
     * 透传、测试断言）。
     */
    fun prompts(summaryOnly: Boolean = false): List<ToolPrompt> =
        listOf(
            ToolPrompt(
                name = TOOL_LIST_CHAPTERS,
                description =
                    "List the target chapter and its four immediately preceding catalog entries. " +
                        "Use the returned opaque chapterRef values; never guess chapter numbers. " +
                        "Read only entries with contentAvailable=true; unavailable content cannot be retried in this run.",
            ),
            ToolPrompt(
                name = TOOL_READ_CHAPTER,
                description =
                    "Read the target chapter, or preceding context only when needed, using chapterRef. " +
                        "The target chapter includes paragraph anchor ids; previous chapters are " +
                        "context only. Follow nextOffset until hasMore is false to read the whole chapter. " +
                        "Offsets address the returned labeled text; paragraph anchors may continue across pages.",
                parametersStructured =
                    listOf(
                        ToolParameterSchema(
                            name = "chapterRef",
                            description = "Opaque chapterRef returned by list_chapters.",
                        ),
                        ToolParameterSchema(name = "offset", description = "Character offset, default 0; continue with nextOffset."),
                        ToolParameterSchema(name = "maxCharacters", description = "Page size, 1 to 8000 characters, default 8000."),
                    ),
            ),
            ToolPrompt(
                name = TOOL_READ_FILE,
                description = "Read a file returned by grep; use character offset and maxCharacters for pagination.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", description = "Exact file path returned by grep."),
                    ToolParameterSchema(name = "offset", description = "Character offset, default 0."),
                    ToolParameterSchema(name = "maxCharacters", description = "Page size, default 12000 characters."),
                ),
            ),
            ToolPrompt(
                name = TOOL_GREP,
                description =
                    "Grep literal text and summaries through the target chapter only; future chapters and untimed memory are excluded. Return paths, offsets and " +
                    "line numbers, then read original text to verify facts for the " +
                    "commentary. Never invents facts beyond the returned evidence.",
                parametersStructured =
                    listOf(
                        ToolParameterSchema(name = "offset", description = "Match offset, default 0."),
                        ToolParameterSchema(name = "limit", description = "Page size, 1 to 100, default 30."),
                        ToolParameterSchema(
                            name = "query",
                            description = "Literal text to locate.",
                        ),
                    ),
            ),
            ToolPrompt(
                name = TOOL_SUBMIT_SUMMARY,
                description =
                    "Stage the factual target-chapter summary before submitting comments. Usually " +
                        "write 100 to 200 Chinese characters; use up to about 300 only for an " +
                        "unusually dense chapter. Keep key events, character or relationship " +
                        "changes, and unresolved questions; do not retell paragraph by paragraph. " +
                        "This tool does not end the turn and may be called again to revise the summary.",
                parametersStructured =
                    listOf(
                        ToolParameterSchema(
                            name = "summary",
                            description =
                                "Factual target-chapter summary without persona voice. Usually " +
                                    "100-200 Chinese characters; dense chapters may use up to about 300.",
                        ),
                    ),
            ),
            ToolPrompt(
                name = TOOL_SUBMIT_COMMENTS,
                description =
                    "Submit 0 to 6 sparse in-character comments after submit_summary. Use an empty " +
                        "JSON array when no comment fits. Each comment uses anchorId, text, kind, " +
                        "evidenceIds and optional evidenceQuote. If supplied, evidenceQuote must be an exact " +
                        "continuous substring copied from a single paragraph in evidenceIds, without " +
                        "paraphrasing, ellipses or paragraph anchor labels. Set anchorId to the latest (highest) " +
                        "paragraph needed to understand the comment. evidenceIds must include anchorId, " +
                        "and every evidenceId must be less than or equal to anchorId. If a comment " +
                        "depends on a later paragraph, move anchorId to that later paragraph. The whole " +
                        "array is rejected for correction if any candidate is invalid; a fully valid " +
                        "submission hands the summary and comments to the publisher, then ends the turn.",
                parametersStructured =
                    listOf(
                        ToolParameterSchema(
                            name = "comments",
                            description =
                                "Complete raw JSON array of candidate comments. anchorId must be the " +
                                    "latest supporting paragraph; evidenceIds must include anchorId and " +
                                    "must never point after it. For example " +
                                    "[{\"anchorId\":\"p0001\",\"text\":\"...\",\"kind\":\"reaction\"," +
                                    "\"evidenceIds\":[\"p0001\"],\"evidenceQuote\":\"...\"}]",
                        ),
                    ),
            ),
        ).filterNot { summaryOnly && it.name == TOOL_SUBMIT_COMMENTS }.map {
            if (summaryOnly && it.name == TOOL_SUBMIT_SUMMARY)
                it.copy(description = "Submit the factual target-chapter summary. Usually 100-200 Chinese characters, " +
                    "up to about 300 for dense chapters. Successful submission ends the task immediately; " +
                    "a validation error allows correction. Do not call other tools in the same batch.")
            else it
        }

    /** ToolRegistration 使用的统一执行器入口。 */
    fun execute(tool: AITool): ToolResult {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        val callerChatId = runtime?.callerChatId?.takeIf(String::isNotBlank)
        if (callerChatId == null) return failure(tool, ERROR_NO_CALLER)
        val session = ReadingCompanionSubagentSessionRegistry.sessionForChildChat(callerChatId)
        if (session == null) return failure(tool, ERROR_NO_SESSION)
        if (session.submissionFinalized) {
            return failure(
                tool,
                "候选已提交完成，本 run 不再接受任何工具调用；如需调整请等待下一次生成",
            )
        }
        val stopped = session.stoppedReason
        if (stopped != null) return failure(tool, "生成已停止（$stopped）").copy(interruptTurn = true)
        if (
            !session.summaryOnly &&
            !session.backend.heartbeatClaimIfOwned(
                session.bookId,
                session.chapterIndex,
                session.runId,
            )
        ) {
            session.stop("claim_lost")
            return failure(tool, "claim_lost：段评 claim 已被抢占或释放，本 run 停止生成")
        }
        val startedAt = System.currentTimeMillis()
        val executed = try {
            dispatch(tool, session)
        } catch (error: Throwable) {
            failure(tool, safeReadingCompanionError(error))
        }
        val finishedAt = System.currentTimeMillis()
        if (
            !session.summaryOnly &&
            session.stoppedReason == null &&
                !session.backend.heartbeatClaimIfOwned(
                    session.bookId,
                    session.chapterIndex,
                    session.runId,
                )
        ) {
            session.stop("claim_lost")
        }
        session.backend.recordAutoCommentRunTrace(
            runId = session.runId,
            operation = "subagent_tool",
            status = if (executed.success) "completed" else "failed",
            startedAt = startedAt,
            finishedAt = finishedAt,
            metadataJson =
                JSONObject()
                    .put("runId", session.runId)
                    .put("tool", tool.name)
                    .put("stoppedReason", session.stoppedReason)
                    .apply {
                        if (!executed.success) put("error", executed.error)
                    }
                    .toString(),
        )
        session.backend.incrementRunToolInvocation(session.runId)
        val evidenceAdvanced =
            executed.success && (
                tool.name in setOf(TOOL_SUBMIT_SUMMARY, TOOL_SUBMIT_COMMENTS) ||
                    (tool.name in setOf(TOOL_READ_CHAPTER, TOOL_READ_FILE, TOOL_GREP) &&
                        recordReturnedEvidence(tool.name, executed.result.toString(), session))
                )
        return when (
            session.loopGuard.recordResult(
                toolName = tool.name,
                normalizedArguments = normalizeReadingCompanionToolCall(tool),
                resultFingerprint = executed.result.toString(),
                evidenceAdvanced = evidenceAdvanced,
            )
        ) {
            ReadingCompanionProgressVerdict.NO_PROGRESS -> {
                session.stop("no_progress")
                executed.copy(
                    success = false,
                    interruptTurn = true,
                    error =
                        (executed.error?.let { "$it；" } ?: "") +
                            "no_progress：同一调用与结果重复 3 次且无新增证据，本 run 停止",
                )
            }
            ReadingCompanionProgressVerdict.OK -> executed
        }
    }

    private fun recordReturnedEvidence(name: String, result: String, session: ReadingCompanionRunSession): Boolean {
        val payload = JSONObject(result)
        var advanced = false
        fun record(kind: String, item: JSONObject) {
            val identity = listOf(kind, item.optString("id"), item.optString("source"),
                item.optString("chapterRef"), item.optString("path"), item.optString("offset"),
                item.optString("lineNumber"), item.optString("text"),
                item.optString("content"), item.optString("summary")).joinToString("\u0001")
            advanced = session.recordEvidence(identity) || advanced
        }
        if (name == TOOL_READ_CHAPTER || name == TOOL_READ_FILE) record("file", payload)
        val arrays = if (name == TOOL_GREP) listOf("results", "readerMemories") else listOf("summaries")
        for (key in arrays) {
            val entries = payload.optJSONArray(key) ?: continue
            repeat(entries.length()) { index ->
                entries.optJSONObject(index)?.let { record(key, it) }
            }
        }
        return advanced
    }

    private fun dispatch(
        tool: AITool,
        session: ReadingCompanionRunSession,
    ): ToolResult =
        when (tool.name) {
            TOOL_LIST_CHAPTERS -> listChapters(tool, session)
            TOOL_READ_CHAPTER -> readChapter(tool, session)
            TOOL_READ_FILE -> readFile(tool, session)
            TOOL_GREP -> grep(tool, session)
            TOOL_SUBMIT_SUMMARY -> submitSummary(tool, session)
            TOOL_SUBMIT_COMMENTS -> submitComments(tool, session)
            else -> failure(tool, "未知的阅读伴读审计工具：${tool.name}")
        }

    private fun requiredWindow(session: ReadingCompanionRunSession): List<ReaderChapter> {
        val ordered = session.chapters.sortedBy(ReaderChapter::index)
        val targetPosition = ordered.indexOfFirst { it.index == session.chapterIndex }
        if (targetPosition < 0) return emptyList()
        return ordered.subList(maxOf(0, targetPosition - 4), targetPosition + 1)
    }

    private fun listChapters(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        val targetRef = ReadingCompanionFileStore.chapterRef(
            session.bookId,
            session.targetChapter().sourceId,
        )
        val chapters =
            JSONArray().apply {
                requiredWindow(session).forEach { chapter ->
                    put(
                        JSONObject()
                            .put(
                                "chapterRef",
                                ReadingCompanionFileStore.chapterRef(
                                    session.bookId,
                                    chapter.sourceId,
                                ),
                            )
                            .put("chapterIndex", chapter.index)
                            .put("chapterNumber", chapter.index + 1)
                            .put("chapterTitle", chapter.title)
                            .put("isTarget", chapter.index == session.chapterIndex)
                            .put("contentAvailable", chapterContent(session, chapter) != null),
                    )
                }
            }
        return success(
            tool,
            JSONObject()
                .put("bookName", session.bookName)
                .put("targetChapterRef", targetRef)
                .put("chapterCount", chapters.length())
                .put("chapters", chapters),
        )
    }

    private fun readChapter(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        val chapterRef =
            tool.parameters.firstOrNull { it.name == "chapterRef" }?.value?.trim().orEmpty()
        val chapter = requiredWindow(session).firstOrNull {
            ReadingCompanionFileStore.chapterRef(session.bookId, it.sourceId) == chapterRef
        } ?: return failure(tool, "chapterRef 不属于目标章及其前四章，请先调用 list_chapters")
        val target = chapter.index == session.chapterIndex
        val content = chapterContent(session, chapter)
            ?: return failure(
                tool,
                JSONObject()
                    .put("code", "chapter_content_unavailable")
                    .put("chapterRef", chapterRef)
                    .put("retryable", false)
                    .put("hint", "Content was not loaded for this run. Do not retry this chapter; " +
                        "continue with available chapters and do not invent missing context.")
                    .toString(),
            )
        val payload =
            JSONObject()
                .put("chapterRef", chapterRef)
                .put("chapterNumber", chapter.index + 1)
                .put("chapterTitle", chapter.title)
                .put("isTarget", target)
        val text = if (target) {
            val paragraphs = AutoCommentSupport.paragraphs(content)
            payload.put("paragraphCount", paragraphs.size)
            AutoCommentSupport.labeledParagraphs(paragraphs)
        } else {
            content
        }
        val offset = tool.parameters.firstOrNull { it.name == "offset" }?.value?.toInt() ?: 0
        val limit = tool.parameters.firstOrNull { it.name == "maxCharacters" }?.value?.toInt() ?: 8000
        appendChapterPage(payload, text, offset, limit)
        return success(tool, payload)
    }

    private fun chapterContent(session: ReadingCompanionRunSession, chapter: ReaderChapter): String? =
        if (chapter.index == session.chapterIndex) {
            session.targetContent.takeIf { it.isNotBlank() }
        } else {
            session.previousContext.firstOrNull { it.sourceId == chapter.sourceId }
                ?.content?.takeIf { it.isNotBlank() }
        }

    // Even JSON-escaped control characters stay below the final 64K tool-message limit.
    internal fun appendChapterPage(payload: JSONObject, text: String, offset: Int, limit: Int) {
        require(offset in 0..text.length) { "offset 超出正文范围" }
        var end = (offset + limit.coerceIn(1, 8000)).coerceAtMost(text.length)
        if (end < text.length && end > offset && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
            if (end - offset == 1) end++ else end--
        }
        payload.put("content", text.substring(offset, end))
            .put("offset", offset)
            .put("nextOffset", end)
            .put("totalCharacters", text.length)
            .put("hasMore", end < text.length)
    }

    private fun readFile(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        fun parameter(name: String) = tool.parameters.firstOrNull { it.name == name }?.value
        return success(tool, session.backend.readFile(session.bookId, parameter("path").orEmpty(),
            parameter("offset")?.toInt() ?: 0, parameter("maxCharacters")?.toInt() ?: 12000))
    }

    private fun grep(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        val query = tool.parameters.firstOrNull { it.name == "query" }?.value?.trim()
        if (query.isNullOrBlank()) {
            return failure(tool, "grep 需要非空 query 参数")
        }
        val result = runBlockingIoPreservingToolRuntimeContext {
            session.backend.grep(session.bookId, query,
                tool.parameters.firstOrNull { it.name == "offset" }?.value?.toInt() ?: 0,
                tool.parameters.firstOrNull { it.name == "limit" }?.value?.toInt() ?: 30)
        }
        return success(tool, result)
    }

    private fun submitSummary(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        val summary =
            tool.parameters.firstOrNull { it.name == "summary" }?.value?.trim().orEmpty()
        if (summary.isBlank() || summary.length > MAX_SUMMARY_CHARS) {
            return failure(tool, "summary 必须为 1..$MAX_SUMMARY_CHARS 字的目标章客观摘要")
        }
        if (!session.updateCandidateSummary(summary)) {
            return failure(tool, "本 run 已完成提交或停止，无法再更新摘要")
        }
        if (session.summaryOnly) {
            return when (session.tryFinalizeSummaryOnly()) {
                SubmitCommentsStatus.FINALIZED ->
                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result =
                            StringResultData(
                                JSONObject()
                                    .put("ok", true)
                                    .put("code", "summary_submitted")
                                    .put("summaryChars", summary.length)
                                    .put("status", "submitted")
                                    .toString(),
                            ),
                        // Summary-only sessions use submit_summary as their terminal action and
                        // never stage or publish persona comments.
                        interruptTurn = true,
                    )
                SubmitCommentsStatus.ALREADY_FINALIZED ->
                    failure(tool, "本 run 已完成摘要提交，不能重复提交")
                SubmitCommentsStatus.SUMMARY_REQUIRED ->
                    failure(tool, "summary_required：摘要不能为空")
            }
        }
        return success(
            tool,
            JSONObject()
                .put("ok", true)
                .put("code", "summary_staged")
                .put("summaryChars", summary.length)
                .put("nextTool", TOOL_SUBMIT_COMMENTS)
                .put("status", "awaiting_comments"),
        )
    }

    private fun submitComments(tool: AITool, session: ReadingCompanionRunSession): ToolResult {
        if (session.summaryOnly) return failure(tool,"This is a summary-only task; use submit_summary to finish.")
        if (session.candidateSummary.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error =
                    JSONObject()
                        .put("ok", false)
                        .put("code", "summary_required")
                        .put("retryable", true)
                        .put("hint", "call $TOOL_SUBMIT_SUMMARY before submitting comments")
                        .toString(),
                interruptTurn = false,
            )
        }
        val commentsJson =
            tool.parameters.firstOrNull { it.name == "comments" }?.value?.trim().orEmpty()
        val emptyComments =
            runCatching {
                val array = JSONArray(commentsJson)
                array.length() == 0
            }.getOrDefault(false)
        val report =
            if (emptyComments) {
                null
            } else {
                AutoCommentSupport.parseAndValidateReport(
                    rawJson = commentsJson,
                    paragraphs = session.targetParagraphs(),
                    maximumComments = AutoCommentSupport.MAX_COMMENTS,
                )
            }
        val drafts = report?.accepted.orEmpty()
        val hasRejectedCandidates = (report?.rejectedCount ?: 0) > 0
        if (
            !emptyComments &&
            (
                drafts.isEmpty() ||
                    report?.code != AutoCommentSupport.REPORT_SUBMITTED ||
                    hasRejectedCandidates
            )
        ) {
            // 失败诊断：只带序号与原因码，绝不回灌正文/引用原文。
            // 混合有效/无效也不发布：让模型重交完整修正版，避免成功响应静默丢候选。
            val diagnosticCode =
                if (drafts.isNotEmpty() && hasRejectedCandidates) {
                    CODE_PARTIAL_CANDIDATES_REJECTED
                } else {
                    report?.code ?: "invalid_json_shape"
                }
            val diagnostic =
                JSONObject()
                    .put("ok", false)
                    .put("code", diagnosticCode)
                    .put("inputCount", report?.inputCount ?: 0)
                    .put("acceptedCount", drafts.size)
                    .put("rejectedCount", report?.rejectedCount ?: 0)
                    .put(
                        "reasons",
                        JSONObject(report?.reasonCounts.orEmpty()),
                    )
                    .put(
                        "rejections",
                        JSONArray().apply {
                            report?.rejections.orEmpty().forEach { rejection ->
                                put(
                                    JSONObject()
                                        .put("candidateNumber", rejection.candidateNumber)
                                        .put("reasons", JSONArray(rejection.reasons)),
                                )
                            }
                        },
                    )
                    .put("retryable", true)
                    .put(
                        "expectedFormat",
                        "raw JSON array string: [{\"anchorId\":\"p0001\",\"text\":\"...\"," +
                            "\"kind\":\"reaction\",\"evidenceIds\":[\"p0001\"]," +
                            "\"evidenceQuote\":\"...\"}]",
                    )
                    .put(
                        "hint",
                        "nothing from this attempt was finalized; resubmit the complete corrected " +
                            "array. anchorId must be the latest supporting paragraph, evidenceIds " +
                            "must include it, and no evidenceId may be after it. Use [] when this " +
                            "chapter should have no comments. For quote_not_found, re-read the " +
                            "referenced target paragraph and copy an exact continuous substring from " +
                            "that single paragraph; do not paraphrase, combine paragraphs, include " +
                            "anchor labels or add ellipses. evidenceQuote is optional. For " +
                            "evidence_after_anchor, move anchorId to the latest supporting paragraph " +
                            "and include it in evidenceIds.",
                    )
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = diagnostic.toString(),
                interruptTurn = false,
            )
        }
        when (session.tryFinalizeComments(drafts)) {
            SubmitCommentsStatus.SUMMARY_REQUIRED ->
                return failure(tool, "summary_required：请先调用 $TOOL_SUBMIT_SUMMARY")
            SubmitCommentsStatus.ALREADY_FINALIZED ->
                return failure(tool, "本 run 已有成功的 submit_comments，忽略并发重复提交")
            SubmitCommentsStatus.FINALIZED -> Unit
        }
        return ToolResult(
            toolName = tool.name,
            success = true,
            result =
                StringResultData(
                    JSONObject()
                        .put("ok", true)
                        .put("code", AutoCommentSupport.REPORT_SUBMITTED)
                        .put("summaryFinalized", true)
                        .put("inputCount", report?.inputCount ?: 0)
                        .put("acceptedCount", drafts.size)
                        .put("rejectedCount", 0)
                        .put(
                            "anchors",
                            JSONArray().apply {
                                drafts.forEach {
                                    put(AutoCommentSupport.paragraphId(it.paragraphIndex))
                                }
                            },
                        )
                        .put("status", "submitted")
                        .toString(),
                ),
            interruptTurn = true,
        )
    }

    private fun success(tool: AITool, payload: JSONObject): ToolResult =
        ToolResult(
            toolName = tool.name,
            success = true,
            result = StringResultData(payload.toString()),
        )

    private fun failure(tool: AITool, message: String): ToolResult =
        ToolResult(
            toolName = tool.name,
            success = false,
            result = StringResultData(""),
            error = message,
        )

    private const val MAX_SUMMARY_CHARS = 4_000
}

/** 生产实现：读取任务绑定书籍的本地文件；检索不调用模型。 */
class ProductionReadingCompanionSubagentBackend(
    private val store: ReadingCompanionStore,
    private val fileStore: ReadingCompanionFileStore,
    private val targetChapterIndex: Int,
) : ReadingCompanionSubagentBackend {
    override fun heartbeatClaimIfOwned(bookId: String, chapterIndex: Int, runId: Long): Boolean =
        store.heartbeatClaimIfOwned(bookId, chapterIndex, runId)

    override fun recordAutoCommentRunTrace(
        runId: Long,
        operation: String,
        status: String,
        startedAt: Long,
        finishedAt: Long?,
        metadataJson: String?,
    ) {
        store.recordAutoCommentRunTrace(
            runId = runId,
            operation = operation,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            metadataJson = metadataJson,
        )
    }

    override fun incrementRunToolInvocation(runId: Long): Boolean =
        store.incrementRunToolInvocation(runId)

    override fun incrementRunModelRound(runId: Long): Boolean =
        store.incrementRunModelRound(runId)

    override fun readFile(bookId: String, path: String, offset: Int, maxCharacters: Int): JSONObject =
        fileStore.readPersistedFile(bookId, path, offset, maxCharacters, throughChapterIndex = targetChapterIndex)

    override suspend fun grep(bookId: String, query: String, offset: Int, limit: Int): JSONObject =
        fileStore.grepPersistedFiles(bookId, query, offset, limit, throughChapterIndex = targetChapterIndex)
            .put("coverage", fileStore.chapterCacheCoverage(bookId, targetChapterIndex))
}
