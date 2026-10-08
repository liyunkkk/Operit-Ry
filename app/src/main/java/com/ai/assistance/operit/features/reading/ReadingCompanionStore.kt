package com.ai.assistance.operit.features.reading

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

data class ReadingSearchHit(
    val id: Long,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val startPosition: Int,
    val endPosition: Int,
    val text: String,
    val score: Int,
    val source: String = "full_text",
    val entityName: String? = null,
)

data class ChapterKnowledge(
    val summary: String,
    val characters: List<ChapterCharacter>,
    val events: List<String>,
    val locations: List<String>,
    val items: List<String>,
    val relationshipChanges: List<String>,
    val possibleForeshadowing: List<String>,
    val keywords: List<String>,
)

data class ChapterCharacter(
    val name: String,
    val aliases: List<String>,
    val facts: List<String>,
)

data class StoredChapterKnowledge(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val sourceEndPosition: Int,
    val isComplete: Boolean,
    val contentHash: String,
    val summary: String,
    val structuredJson: String,
    val keywords: String,
    val updatedAt: Long,
)

data class ReaderMemory(
    val id: Long,
    val bookId: String,
    val chapterIndex: Int,
    val type: String,
    val content: String,
    val createdAt: Long,
)

/**
 * Per-book manual summary-batch preferences.  Chapters are 1-based user-facing numbers
 * (null/omitted = unbounded side); [budget] is the per-run generation cap.
 */
data class SummaryBatchPrefs(
    val startChapter: Int?,
    val endChapter: Int?,
    val budget: Int,
)

data class AutoCommentChapter(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val contentHash: String,
    val roleCardId: String?,
    val roleCardName: String?,
    val generationRunId: Long?,
    val generationPolicyVersion: Int,
    val status: String,
    val updatedAt: Long,
)

data class AutoCommentRecord(
    val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val text: String,
    val kind: String,
    val roleCardId: String? = null,
    val roleCardName: String? = null,
    val evidenceJson: String,
    val createdAt: Long = 0,
)

data class AutoCommentPersona(
    val bookId: String,
    val roleCardId: String,
    val roleCardName: String,
    val updatedAt: Long,
)

enum class AutoCommentGenerationClaimStatus {
    CLAIMED,
    CACHED,
    ALREADY_GENERATING,
    RUN_INTERRUPTED,
}

data class AutoCommentGenerationClaim(
    val status: AutoCommentGenerationClaimStatus,
    val commentCount: Int = 0,
)

data class AutoCommentRun(
    val id: Long,
    val bookId: String?,
    val bookName: String?,
    val chapterIndex: Int?,
    val chapterTitle: String?,
    val trigger: String,
    val status: String,
    val stage: String,
    val stageUpdatedAt: Long,
    val roleCardId: String?,
    val roleCardName: String?,
    val modelConfigId: String?,
    val modelConfigName: String?,
    val modelIndex: Int?,
    val modelSource: String?,
    val provider: String?,
    val model: String?,
    val targetCharacterCount: Int?,
    val contextChapterCount: Int?,
    val contextCharacterCount: Int?,
    val contextWindowTokens: Int?,
    val estimatedInputTokens: Int?,
    val commentCount: Int,
    val errorMessage: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    /** Provider-reported usage for this direct model request, when available. */
    val actualUncachedInputTokens: Long? = null,
    val actualCachedInputTokens: Long? = null,
    val actualInputTokens: Long? = null,
    val actualOutputTokens: Long? = null,
    val actualReasoningTokens: Long? = null,
    val actualUsageSource: String? = null,
    val actualUsageComplete: Boolean? = null,
    /** 执行模式：direct（旧单发）或 subagent（阅读伴侣审计对话）。 */
    val executionMode: String = AUTO_COMMENT_RUN_EXECUTION_MODE_DIRECT,
    /** 弱关联：审计父聊天（后台/插件为隐藏根；对话内为用户聊天）。 */
    val parentChatId: String? = null,
    /** 弱关联：主库 subagent_runs.id。 */
    val subagentRunId: String? = null,
    /** 弱关联：subagent 子聊天 id（transcript 所在）。 */
    val childChatId: String? = null,
    /** 已执行的模型轮次计数（审计展示；不是总量上限）。 */
    val modelRoundCount: Int = 0,
    /** 已执行工具调用次数（审计展示）。 */
    val toolInvocationCount: Int = 0,
    /** 谱系：因进程重启恢复而创建的新 run 指向旧 run id；只作谱系，不续写。 */
    val restartedFromRunId: Long? = null,
)

data class AutoCommentRunStageEvent(
    val id: Long,
    val runId: Long,
    val stage: String,
    val startedAt: Long,
    val finishedAt: Long?,
) {
    val durationMs: Long
        get() = ((finishedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0)
}

/**
 * Immutable copy of a generated comment. The latest-per-chapter table is intentionally mutable
 * (a forced run replaces it), so history reads this table instead of re-querying that cache.
 */
data class AutoCommentRunCommentSnapshot(
    val id: Long,
    val runId: Long,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val contentHash: String,
    val paragraphIndex: Int,
    val text: String,
    val kind: String,
    val roleCardId: String?,
    val roleCardName: String?,
    val evidenceJson: String,
    val createdAt: Long,
)

/** A safe, structured operation event captured for a single generation run. */
data class AutoCommentRunTraceEvent(
    val id: Long,
    val runId: Long,
    val operation: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val metadataJson: String?,
) {
    val durationMs: Long
        get() = ((finishedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0)
}

/** 主库侧 subagent run 的弱关联摘要（reconciliation 对账用）。 */
data class ReadingCompanionSubagentRunLink(
    val subagentRunId: String,
    val parentChatId: String? = null,
    val childChatId: String? = null,
)

/** 主库侧仍活跃的阅读所有者 subagent run（reconciliation 反方向对账用）。 */
data class ReadingCompanionMainOwnerRef(
    val subagentRunId: String,
    val readingRunId: Long,
)

/** 阅读库侧有弱关联的 run 摘要（reconciliation 正向对账用）。 */
data class ReadingCompanionRunLinkRef(
    val runId: Long,
    val status: String,
    val parentChatId: String? = null,
    val subagentRunId: String? = null,
    val childChatId: String? = null,
)

/** 跨库弱关联对账结果。 */
data class ReadingCompanionReconcileOutcome(
    val relinkedRuns: Int = 0,
    val interruptedReadingRuns: Int = 0,
    val interruptedSubagentRuns: Int = 0,
)

/**
 * 跨库弱关联对账编排（纯函数；生产 [ReadingCompanionStore.reconcileWithChatGraph] 与
 * JVM 单测共用同一实现，避免测试验证的是测试自带的副本）。
 *
 * 正向（阅读侧已有链接）：主库 run 不存在 => 阅读侧仍活跃的 run 标 interrupted、
 * 释放 claim；主库 run 存在但缺 child_chat_id => 用主库 child 补链；child 聊天已不存在
 * => interrupted。反向（主库仍活跃的阅读所有者 run）：阅读侧 run 不存在 =>
 * 经 [interruptSubagentRun] 回调把主库 run 标 interrupted。
 *
 * 对账只 interrupt + 释放 claim + 补链；绝不删除 transcript。
 */
internal suspend fun runReadingCompanionReconcile(
    linkedRuns: List<ReadingCompanionRunLinkRef>,
    activeMainOwners: List<ReadingCompanionMainOwnerRef>,
    lookupSubagentRun: suspend (subagentRunId: String) -> ReadingCompanionSubagentRunLink?,
    chatExists: suspend (chatId: String) -> Boolean,
    relinkChildChat: (runId: Long, parentChatId: String?, childChatId: String) -> Unit,
    interruptReadingRun: (runId: Long, errorMessage: String) -> Boolean,
    readingRunExists: (runId: Long) -> Boolean,
    interruptSubagentRun: suspend (subagentRunId: String) -> Boolean,
): ReadingCompanionReconcileOutcome {
    var relinkedRuns = 0
    var interruptedReadingRuns = 0
    for (ref in linkedRuns) {
        val link = ref.subagentRunId?.let { id -> lookupSubagentRun(id) }
        if (link == null) {
            if (
                ref.status == ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_GENERATING &&
                interruptReadingRun(ref.runId, "orphaned_subagent_run")
            ) {
                interruptedReadingRuns++
            }
            continue
        }
        var childChatId = ref.childChatId
        if (childChatId == null && link.childChatId != null) {
            relinkChildChat(
                ref.runId,
                ref.parentChatId ?: link.parentChatId,
                link.childChatId,
            )
            childChatId = link.childChatId
            relinkedRuns++
        }
        if (
            childChatId != null &&
            ref.status == ReadingCompanionStore.AUTO_COMMENT_RUN_STATUS_GENERATING &&
            !chatExists(childChatId)
        ) {
            if (interruptReadingRun(ref.runId, "missing_child_chat")) {
                interruptedReadingRuns++
            }
        }
    }
    var interruptedSubagentRuns = 0
    for (owner in activeMainOwners) {
        if (!readingRunExists(owner.readingRunId) && interruptSubagentRun(owner.subagentRunId)) {
            interruptedSubagentRuns++
        }
    }
    return ReadingCompanionReconcileOutcome(
        relinkedRuns = relinkedRuns,
        interruptedReadingRuns = interruptedReadingRuns,
        interruptedSubagentRuns = interruptedSubagentRuns,
    )
}

internal fun ReadingSearchHit.matchesCharacterIdentity(query: String): Boolean =
    entityName.equals(query, ignoreCase = true) ||
        text.substringBefore('：').contains(query, ignoreCase = true)

internal object ReadingTextIndexSupport {
    private const val CHUNK_SIZE = 1800
    private const val CHUNK_OVERLAP = 220
    private val tokenRegex = Regex("[\\p{L}\\p{N}·]{1,40}")
    private val cjkRegex = Regex("[\\u3400-\\u9FFF]+")

    data class Chunk(
        val start: Int,
        val end: Int,
        val text: String,
    )

    fun chunk(content: String): List<Chunk> {
        if (content.isEmpty()) return emptyList()
        val result = mutableListOf<Chunk>()
        var start = 0
        while (start < content.length) {
            var end = min(start + CHUNK_SIZE, content.length)
            if (end < content.length) {
                val paragraphBreak = content.lastIndexOf('\n', end)
                if (paragraphBreak > start + CHUNK_SIZE / 2) {
                    end = paragraphBreak + 1
                }
            }
            result += Chunk(start = start, end = end, text = content.substring(start, end))
            if (end >= content.length) break
            start = max(start + 1, end - CHUNK_OVERLAP)
        }
        return result
    }

    fun chunkFrom(content: String, startPosition: Int): List<Chunk> {
        val safeStart = startPosition.coerceIn(0, content.length)
        return chunk(content.substring(safeStart)).map { chunk ->
            Chunk(
                start = chunk.start + safeStart,
                end = chunk.end + safeStart,
                text = chunk.text,
            )
        }
    }

    fun extractQueryTerms(query: String): List<String> {
        val terms = linkedSetOf<String>()
        tokenRegex.findAll(query.lowercase()).forEach { match ->
            val token = match.value.trim('·')
            if (token.isBlank()) return@forEach
            terms += token
            cjkRegex.findAll(token).forEach { cjk ->
                if (cjk.value.length >= 2) {
                    cjk.value.windowed(2).forEach(terms::add)
                }
            }
        }
        return terms.take(16)
    }

    fun buildSearchTerms(text: String): String {
        val terms = linkedSetOf<String>()
        tokenRegex.findAll(text.lowercase()).forEach { match ->
            val token = match.value.trim('·')
            if (token.isBlank()) return@forEach
            if (token.length <= 24) terms += token
            cjkRegex.findAll(token).forEach { cjk ->
                cjk.value.windowed(2).forEach(terms::add)
            }
        }
        return terms.joinToString(" ")
    }

    fun buildFtsExpression(terms: List<String>): String {
        return terms
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(16)
            .joinToString(" OR ") { term ->
                val escaped = term.replace("\"", "\"\"")
                "\"$escaped\""
            }
    }

    fun score(text: String, title: String, terms: List<String>): Int {
        val normalizedText = text.lowercase()
        val normalizedTitle = title.lowercase()
        return terms.sumOf { term ->
            val normalized = term.lowercase()
            var score = if (normalizedTitle.contains(normalized)) 12 else 0
            var index = normalizedText.indexOf(normalized)
            while (index >= 0) {
                score += 4 + normalized.length.coerceAtMost(8)
                index = normalizedText.indexOf(normalized, index + normalized.length.coerceAtLeast(1))
            }
            score
        }
    }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * v13 新增的子代理执行列（名称 → 定义）。单一事实来源：
 * 新装库 `createAutoCommentRunTable` 用同一份定义建列，v12→v13 升级
 * `onUpgrade` 用 [addColumnIfMissing] 逐列 ALTER，保证两路径永久一致。
 *
 * 全部为纯新增，旧行以默认值无损升级（execution_mode='direct' 兼容旧单发路径）。
 */
internal val AUTO_COMMENT_RUN_V13_SUBAGENT_COLUMN_DEFINITIONS: List<Pair<String, String>> =
    listOf(
        "execution_mode" to "TEXT NOT NULL DEFAULT '$AUTO_COMMENT_RUN_EXECUTION_MODE_DIRECT'",
        "parent_chat_id" to "TEXT",
        "subagent_run_id" to "TEXT",
        "child_chat_id" to "TEXT",
        "model_round_count" to "INTEGER NOT NULL DEFAULT 0",
        "tool_invocation_count" to "INTEGER NOT NULL DEFAULT 0",
        "restarted_from_run_id" to "INTEGER",
    )

/**
 * v14 新增的 run 存活心跳列。它与 stage_updated_at 分离，避免 60 秒存活心跳篡改审计页
 * 展示的真实阶段开始时间。新装与升级共用同一列定义。
 */
internal val AUTO_COMMENT_RUN_V14_LIVENESS_COLUMN_DEFINITION =
    "run_heartbeat_at" to "INTEGER NOT NULL DEFAULT 0"

/**
 * claim 归属判定 WHERE（heartbeat 与 JVM 心跳测试共用同一来源）。
 *
 * 只有 claim owner（book+chapter+runId 全匹配）能刷新 updated_at；affected != 1 即
 * claim 已被抢占/释放/丢失，调用方必须立即停止生成（claim_lost）。
 */
internal const val READING_CLAIM_OWNER_WHERE_SQL =
    "book_id = ? AND chapter_index = ? AND run_id = ?"

/**
 * “claim 仍未过期”子查询（stale 清扫与 JVM 心跳测试共用同一来源）。
 *
 * 心跳只是刷新 claims.updated_at（不是 run.stage_updated_at）；清扫窗口内 claim 仍新鲜的
 * run 不会被 5 分钟 stale 误杀。
 */
internal const val READING_CLAIMS_NOT_STALE_SUBQUERY_SQL =
    "(SELECT run_id FROM auto_comment_generation_claims WHERE updated_at > ?)"

/**
 * 通用 stale run 选择条件。claim-owned 段评由 claim 心跳保护；无 claim 的 summary-only
 * run 由 run_heartbeat_at 保护。进程启动清扫传入启动时刻，因此旧进程遗留 run 即使曾
 * 心跳也仍会被中断。
 */
internal const val READING_STALE_RUN_WHERE_SQL =
    "status = ? AND run_heartbeat_at < ? AND " +
        "id NOT IN $READING_CLAIMS_NOT_STALE_SUBQUERY_SQL"

/**
 * 剪枝归档 SQL：把被剪枝的任务关联 run 拷贝进 reading_task_attempts。
 *
 * `auto_comment_runs.chapter_index` 可空（「无下一章」收尾或未解析到章节即中断的 run），而
 * `reading_task_attempts.chapter_index` 为 NOT NULL；直接拷贝会触发约束异常并回滚整个剪枝
 * 事务，留下的脏 run 永不删除。因此跳过空章节 run——它们仍会被 [pruneAutoCommentRuns] 的
 * DELETE（同等 selection，不含 task_id/chapter_index 条件）一并清除。
 */
internal fun pruneAutoCommentArchiveSql(selection: String): String =
    """
    INSERT OR REPLACE INTO reading_task_attempts
        (id, task_id, chapter_index, chapter_title, status, stage, subagent_run_id, child_chat_id,
         actual_input_tokens, actual_output_tokens, started_at, finished_at)
        SELECT id, task_id, chapter_index, chapter_title, status, stage, subagent_run_id, child_chat_id,
               actual_input_tokens, actual_output_tokens, started_at, finished_at
        FROM auto_comment_runs
        WHERE task_id IS NOT NULL AND chapter_index IS NOT NULL AND ($selection)
    """.trimIndent()

/** 段评生成执行模式：单发（v13 前兼容值）。 */
const val AUTO_COMMENT_RUN_EXECUTION_MODE_DIRECT = "direct"

/** 段评生成执行模式：对话内 subagent（审计形态）。 */
const val AUTO_COMMENT_RUN_EXECUTION_MODE_SUBAGENT = "subagent"

/** 主库 subagent_runs.externalOwnerType 的阅读伴侣取值（跨库弱关联）。 */
const val READING_COMPANION_SUBAGENT_OWNER_TYPE = "reading_companion_run"

/** 阅读伴侣每书审计根聊天 hiddenReason 前缀（root:<bookId>）。 */
const val READING_COMPANION_HIDDEN_ROOT_REASON_PREFIX = "READING_COMPANION_AUDIT_ROOT:"

/** 阅读伴侣每次 run 隐藏子聊天 hiddenReason 前缀（run:<runId>）。 */
const val READING_COMPANION_HIDDEN_RUN_REASON_PREFIX = "READING_COMPANION_AUDIT_RUN:"

class ReadingCompanionStore(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        DATABASE_NAME,
        null,
        DATABASE_VERSION,
    ) {

    private val appContext = context.applicationContext

    /**
     * 剪枝（保留 generating 行 + 最近 50）删掉的旧 run 的弱关联聊天挂账。
     *
     * pruneAutoCommentRuns 在 @Synchronized 的 SQLite 事务内只收集不删除；真正的主库
     * 子树删除由 [flushPrunedRunChatCleanup] 在挂起语境统一排空（ChatHistoryManager 是
     * 挂起 API，不能在事务内调用）。队列有独立锁，避免与数据库锁互相嵌套。
     */
    private val prunedRunChatQueue = ArrayDeque<PrunedRunChatRef>()

    data class IndexedChapter(
        val title: String,
        val contentHash: String,
        val indexedUntil: Int,
        val isComplete: Boolean,
    )

    private data class StoredChunk(
        val start: Int,
        val end: Int,
        val text: String,
    )

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createCoreTables(db)
        createKnowledgeTables(db)
        createAutoCommentTables(db)
        createTaskTables(db)
        createCatalogSourcesTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 1 already exists on development devices. Keep its indexed text and only add
            // the knowledge, memory, and selection tables introduced by the ToolPkg version.
            createKnowledgeTables(db)
        }
        if (oldVersion < 3) {
            createAutoCommentTables(db)
        }
        if (oldVersion < 4) {
            createAutoCommentRunTable(db)
        } else if (oldVersion < 5) {
            migrateAutoCommentRunTableToWorkerAttempts(db)
        }
        if (oldVersion in 3..5) {
            db.execSQL("ALTER TABLE auto_comment_chapters ADD COLUMN role_card_id TEXT")
            db.execSQL("ALTER TABLE auto_comment_chapters ADD COLUMN role_card_name TEXT")
            db.execSQL("ALTER TABLE auto_comments ADD COLUMN role_card_id TEXT")
            db.execSQL("ALTER TABLE auto_comments ADD COLUMN role_card_name TEXT")
        }
        if (oldVersion < 6) {
            createAutoCommentPersonaTable(db)
        }
        if (oldVersion < 7) {
            db.delete(
                "auto_comments",
                "role_card_id IS NULL OR TRIM(role_card_id) = '' OR " +
                    "role_card_name IS NULL OR TRIM(role_card_name) = ''",
                null,
            )
            db.delete(
                "auto_comment_chapters",
                "role_card_id IS NULL OR TRIM(role_card_id) = '' OR " +
                    "role_card_name IS NULL OR TRIM(role_card_name) = ''",
                null,
            )
        }
        if (oldVersion in 3..7) {
            db.execSQL(
                "ALTER TABLE auto_comment_chapters ADD COLUMN generation_run_id INTEGER"
            )
        }
        if (oldVersion in 3..8) {
            db.execSQL(
                "ALTER TABLE auto_comment_chapters " +
                    "ADD COLUMN generation_policy_version INTEGER NOT NULL DEFAULT 1"
            )
        }
        if (oldVersion in 5..9) {
            db.execSQL(
                "ALTER TABLE auto_comment_runs " +
                    "ADD COLUMN stage TEXT NOT NULL DEFAULT 'starting'"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs " +
                    "ADD COLUMN stage_updated_at INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs ADD COLUMN target_character_count INTEGER"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs ADD COLUMN context_chapter_count INTEGER"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs ADD COLUMN context_character_count INTEGER"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs ADD COLUMN context_window_tokens INTEGER"
            )
            db.execSQL(
                "ALTER TABLE auto_comment_runs ADD COLUMN estimated_input_tokens INTEGER"
            )
            db.execSQL(
                "UPDATE auto_comment_runs SET stage_updated_at = " +
                    "COALESCE(finished_at, started_at) WHERE stage_updated_at = 0"
            )
        }
        if (oldVersion in 5..10) {
            db.execSQL("ALTER TABLE auto_comment_runs ADD COLUMN model_source TEXT")
        }
        if (oldVersion < 11) {
            createAutoCommentRunStageEventTable(db)
            db.execSQL(
                """
                INSERT INTO auto_comment_run_stage_events (
                    run_id, stage, started_at, finished_at
                )
                SELECT
                    id,
                    COALESCE(NULLIF(stage, ''), '${AutoCommentRunStages.STARTING}'),
                    CASE
                        WHEN stage_updated_at > 0 THEN stage_updated_at
                        ELSE started_at
                    END,
                    finished_at
                FROM auto_comment_runs
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM auto_comment_run_stage_events existing
                    WHERE existing.run_id = auto_comment_runs.id
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 12) {
            ensureAutoCommentRunUsageColumns(db)
            createAutoCommentRunHistoryTables(db)
            // Preserve the latest comments already present on devices upgraded from v11.
            // Future runs write immutable snapshots transactionally in replaceAutoComments().
            db.execSQL(
                """
                INSERT INTO auto_comment_run_comments (
                    run_id, book_id, chapter_index, chapter_title, content_hash,
                    paragraph_index, comment_text, comment_kind, role_card_id,
                    role_card_name, evidence_json, created_at
                )
                SELECT
                    chapter.generation_run_id,
                    comment.book_id,
                    comment.chapter_index,
                    chapter.chapter_title,
                    chapter.content_hash,
                    comment.paragraph_index,
                    comment.comment_text,
                    comment.comment_kind,
                    comment.role_card_id,
                    comment.role_card_name,
                    comment.evidence_json,
                    comment.created_at
                FROM auto_comments comment
                JOIN auto_comment_chapters chapter
                  ON chapter.book_id = comment.book_id
                 AND chapter.chapter_index = comment.chapter_index
                JOIN auto_comment_runs run
                  ON run.id = chapter.generation_run_id
                WHERE chapter.generation_run_id IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1
                    FROM auto_comment_run_comments existing
                    WHERE existing.run_id = chapter.generation_run_id
                  )
                """.trimIndent()
            )
        }
        if (oldVersion < 13) {
            ensureAutoCommentRunSubagentColumns(db)
        }
        if (oldVersion < 14) {
            ensureAutoCommentRunLivenessColumn(db)
            db.execSQL(
                """
                UPDATE auto_comment_runs
                SET run_heartbeat_at = CASE
                    WHEN finished_at IS NOT NULL THEN finished_at
                    WHEN stage_updated_at > 0 THEN stage_updated_at
                    ELSE started_at
                END
                WHERE run_heartbeat_at = 0
                """.trimIndent(),
            )
        }
        if (oldVersion < 15) createTaskTables(db)
        if (oldVersion < 16) createCatalogSourcesTable(db)
    }

    private fun createCatalogSourcesTable(db: SQLiteDatabase) {
        db.execSQL(READING_CATALOG_SOURCES_SQL)
    }

    /** Derived text/knowledge must not survive a catalog entry being replaced at the same index. */
    @Synchronized
    fun synchronizeCatalogSources(bookId: String, chapters: List<ReaderChapter>) {
        val expected = chapters.filterNot { it.isVolume }.associate { it.index to it.sourceId }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = mutableMapOf<Int, String>()
            db.rawQuery("SELECT chapter_index, source_id FROM indexed_catalog_sources WHERE book_id = ?",
                arrayOf(bookId)).use { cursor ->
                while (cursor.moveToNext()) previous[cursor.getInt(0)] = cursor.getString(1)
            }
            val indexed = mutableSetOf<Int>()
            db.rawQuery("""SELECT chapter_index FROM chapters WHERE book_id = ?
                UNION SELECT chapter_index FROM chapter_knowledge WHERE book_id = ?""",
                arrayOf(bookId, bookId)).use { cursor ->
                while (cursor.moveToNext()) indexed.add(cursor.getInt(0))
            }
            staleReadingChapterIndices(indexed, previous, expected)
                .forEach { deleteChapter(db, bookId, it) }
            (previous.keys - expected.keys).forEach {
                db.delete("indexed_catalog_sources", "book_id = ? AND chapter_index = ?",
                    arrayOf(bookId, it.toString()))
            }
            expected.filter { (index, source) -> previous[index] != source }.forEach { (index, source) ->
                db.insertWithOnConflict("indexed_catalog_sources", null, ContentValues().apply {
                    put("book_id", bookId); put("chapter_index", index); put("source_id", source)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createTaskTables(db: SQLiteDatabase) {
        addColumnIfMissing(db, "auto_comment_runs", "task_id", "TEXT")
        ReadingTaskRepository.createTables(db)
        db.execSQL("""CREATE TABLE IF NOT EXISTS reading_publications (
            chapter_ref TEXT PRIMARY KEY NOT NULL, book_id TEXT NOT NULL, source_id TEXT NOT NULL,
            revision TEXT NOT NULL, contract_hash TEXT NOT NULL, role_card_id TEXT NOT NULL,
            run_id INTEGER NOT NULL, committed_at INTEGER NOT NULL
        )""")
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS books (
                book_id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                author TEXT NOT NULL,
                last_read_chapter INTEGER NOT NULL,
                last_read_position INTEGER,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapters (
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                chapter_title TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                indexed_until INTEGER NOT NULL,
                is_complete INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (book_id, chapter_index),
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS text_chunks USING fts4(
                book_id,
                chapter_index,
                chapter_title,
                start_pos,
                end_pos,
                text,
                search_terms,
                tokenize=unicode61
            )
            """.trimIndent()
        )
    }

    private fun createKnowledgeTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapter_knowledge (
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                chapter_title TEXT NOT NULL,
                source_end_pos INTEGER NOT NULL,
                is_complete INTEGER NOT NULL,
                content_hash TEXT NOT NULL,
                summary TEXT NOT NULL,
                structured_json TEXT NOT NULL,
                keywords TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (book_id, chapter_index),
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_fts USING fts4(
                book_id,
                chapter_index,
                chapter_title,
                source_end_pos,
                kind,
                entity_name,
                text,
                search_terms,
                tokenize=unicode61
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS reader_memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                memory_type TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS reader_memories_fts USING fts4(
                memory_id,
                book_id,
                chapter_index,
                memory_type,
                content,
                search_terms,
                tokenize=unicode61
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS reading_settings (
                setting_key TEXT PRIMARY KEY NOT NULL,
                setting_value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createAutoCommentTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_chapters (
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                chapter_title TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                role_card_id TEXT,
                role_card_name TEXT,
                generation_run_id INTEGER,
                generation_policy_version INTEGER NOT NULL,
                status TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (book_id, chapter_index),
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                paragraph_index INTEGER NOT NULL,
                comment_text TEXT NOT NULL,
                comment_kind TEXT NOT NULL,
                role_card_id TEXT,
                role_card_name TEXT,
                evidence_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (book_id, chapter_index)
                    REFERENCES auto_comment_chapters(book_id, chapter_index)
                    ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS auto_comments_chapter_paragraph
            ON auto_comments(book_id, chapter_index, paragraph_index)
            """.trimIndent()
        )
        createAutoCommentRunTable(db)
        createAutoCommentPersonaTable(db)
    }

    private fun createAutoCommentPersonaTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_personas (
                book_id TEXT PRIMARY KEY NOT NULL,
                role_card_id TEXT NOT NULL,
                role_card_name TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    private fun createAutoCommentRunTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT,
                chapter_index INTEGER,
                chapter_title TEXT,
                trigger_source TEXT NOT NULL,
                status TEXT NOT NULL,
                stage TEXT NOT NULL DEFAULT 'starting',
                stage_updated_at INTEGER NOT NULL DEFAULT 0,
                ${AUTO_COMMENT_RUN_V14_LIVENESS_COLUMN_DEFINITION.first} ${AUTO_COMMENT_RUN_V14_LIVENESS_COLUMN_DEFINITION.second},
                role_card_id TEXT,
                role_card_name TEXT,
                model_config_id TEXT,
                model_config_name TEXT,
                model_index INTEGER,
                model_source TEXT,
                provider TEXT,
                model TEXT,
                target_character_count INTEGER,
                context_chapter_count INTEGER,
                context_character_count INTEGER,
                context_window_tokens INTEGER,
                estimated_input_tokens INTEGER,
                actual_uncached_input_tokens INTEGER,
                actual_cached_input_tokens INTEGER,
                actual_input_tokens INTEGER,
                actual_output_tokens INTEGER,
                actual_reasoning_tokens INTEGER,
                actual_usage_source TEXT,
                actual_usage_complete INTEGER,
                ${AUTO_COMMENT_RUN_V13_SUBAGENT_COLUMN_DEFINITIONS.joinToString(",\n                ") { (name, definition) -> "$name $definition" }},
                comment_count INTEGER NOT NULL DEFAULT 0,
                error_message TEXT,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS auto_comment_runs_started
            ON auto_comment_runs(started_at DESC)
            """.trimIndent()
        )
        createAutoCommentRunStageEventTable(db)
        createAutoCommentRunHistoryTables(db)
    }

    private fun createAutoCommentRunStageEventTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_run_stage_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                stage TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                FOREIGN KEY (run_id) REFERENCES auto_comment_runs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS auto_comment_run_stage_events_run
            ON auto_comment_run_stage_events(run_id, started_at, id)
            """.trimIndent()
        )
    }

    private fun ensureAutoCommentRunUsageColumns(db: SQLiteDatabase) {
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_uncached_input_tokens",
            definition = "INTEGER",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_cached_input_tokens",
            definition = "INTEGER",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_input_tokens",
            definition = "INTEGER",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_output_tokens",
            definition = "INTEGER",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_reasoning_tokens",
            definition = "INTEGER",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_usage_source",
            definition = "TEXT",
        )
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = "actual_usage_complete",
            definition = "INTEGER",
        )
    }

    /** v13：子代理执行列（幂等 ALTER；与 createAutoCommentRunTable 同一事实来源）。 */
    private fun ensureAutoCommentRunSubagentColumns(db: SQLiteDatabase) {
        AUTO_COMMENT_RUN_V13_SUBAGENT_COLUMN_DEFINITIONS.forEach { (name, definition) ->
            addColumnIfMissing(
                db,
                table = "auto_comment_runs",
                column = name,
                definition = definition,
            )
        }
    }

    /** v14：run 存活心跳列（幂等 ALTER；与 fresh create 共用定义）。 */
    private fun ensureAutoCommentRunLivenessColumn(db: SQLiteDatabase) {
        val (name, definition) = AUTO_COMMENT_RUN_V14_LIVENESS_COLUMN_DEFINITION
        addColumnIfMissing(
            db,
            table = "auto_comment_runs",
            column = name,
            definition = definition,
        )
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String,
    ) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    private fun createAutoCommentRunHistoryTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_generation_claims (
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                run_id INTEGER NOT NULL UNIQUE,
                content_hash TEXT NOT NULL,
                role_card_id TEXT NOT NULL,
                role_card_name TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (book_id, chapter_index),
                FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE,
                FOREIGN KEY (run_id) REFERENCES auto_comment_runs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_run_comments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                book_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                chapter_title TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                paragraph_index INTEGER NOT NULL,
                comment_text TEXT NOT NULL,
                comment_kind TEXT NOT NULL,
                role_card_id TEXT,
                role_card_name TEXT,
                evidence_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (run_id) REFERENCES auto_comment_runs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS auto_comment_run_comments_run
            ON auto_comment_run_comments(run_id, paragraph_index, id)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_comment_run_trace (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                operation TEXT NOT NULL,
                status TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                metadata_json TEXT,
                FOREIGN KEY (run_id) REFERENCES auto_comment_runs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS auto_comment_run_trace_run
            ON auto_comment_run_trace(run_id, started_at, id)
            """.trimIndent()
        )
    }

    private fun migrateAutoCommentRunTableToWorkerAttempts(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE auto_comment_runs RENAME TO auto_comment_runs_v4")
        db.execSQL("DROP INDEX IF EXISTS auto_comment_runs_started")
        createAutoCommentRunTable(db)
        db.execSQL(
            """
            INSERT INTO auto_comment_runs (
                id, book_id, chapter_index, chapter_title, trigger_source, status,
                role_card_id, role_card_name, model_config_id, model_config_name,
                model_index, provider, model, comment_count, error_message, started_at, finished_at
            )
            SELECT
                id, book_id, chapter_index, chapter_title, trigger_source, status,
                role_card_id, role_card_name, model_config_id, model_config_name,
                model_index, provider, model, comment_count, error_message, started_at, finished_at
            FROM auto_comment_runs_v4
            """.trimIndent()
        )
        db.execSQL("DROP TABLE auto_comment_runs_v4")
    }

    @Synchronized
    fun updateBook(state: ReadingState) {
        val values = ContentValues().apply {
            put("book_id", state.book.id)
            put("name", state.book.name)
            put("author", state.book.author)
            put("last_read_chapter", state.chapterIndex)
            if (state.bodyPosition == null) putNull("last_read_position")
            else put("last_read_position", state.bodyPosition)
            put("updated_at", state.capturedAt)
        }
        val db = writableDatabase
        db.insertWithOnConflict("books", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        db.update("books", values, "book_id = ?", arrayOf(state.book.id))
    }

    @Synchronized
    fun setSelectedBookId(bookId: String?) {
        val db = writableDatabase
        if (bookId.isNullOrBlank()) {
            db.delete("reading_settings", "setting_key = ?", arrayOf(SELECTED_BOOK_KEY))
            return
        }
        db.insertWithOnConflict(
            "reading_settings",
            null,
            ContentValues().apply {
                put("setting_key", SELECTED_BOOK_KEY)
                put("setting_value", bookId)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun getSelectedBookId(): String? {
        readableDatabase.query(
            "reading_settings",
            arrayOf("setting_value"),
            "setting_key = ?",
            arrayOf(SELECTED_BOOK_KEY),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0).takeIf(String::isNotBlank) else null
        }
    }

    /**
     * Remembers the most recent successfully resolved book id (explicit selection or automatic
     * recent book), so the local summaries/files surfaces can still address the on-disk book
     * workspace while Legado is unreachable.  Never resolves to a fabricated placeholder id.
     */
    @Synchronized
    fun setLastResolvedBookId(bookId: String) {
        if (bookId.isBlank()) return
        writableDatabase.insertWithOnConflict(
            "reading_settings",
            null,
            ContentValues().apply {
                put("setting_key", LAST_RESOLVED_BOOK_KEY)
                put("setting_value", bookId)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun getLastResolvedBookId(): String? {
        readableDatabase.query(
            "reading_settings",
            arrayOf("setting_value"),
            "setting_key = ?",
            arrayOf(LAST_RESOLVED_BOOK_KEY),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0).takeIf(String::isNotBlank) else null
        }
    }

    /**
     * Saved manual summary-batch preferences for [bookId], or null when nothing has been saved.
     * Values are stored as JSON under a book-scoped key in reading_settings.
     */
    fun getSummaryBatchPrefs(bookId: String): SummaryBatchPrefs? {
        if (bookId.isBlank()) return null
        readableDatabase.query(
            "reading_settings",
            arrayOf("setting_value"),
            "setting_key = ?",
            arrayOf(SUMMARY_BATCH_PREFS_KEY_PREFIX + bookId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val raw = cursor.getString(0).takeIf(String::isNotBlank) ?: return null
            return runCatching {
                val json = JSONObject(raw)
                val rawStart = json.optNullableInt("startChapter")?.takeIf { it >= 1 }
                val rawEnd = json.optNullableInt("endChapter")?.takeIf { it >= 1 }
                val validRange = rawStart == null || rawEnd == null || rawEnd >= rawStart
                SummaryBatchPrefs(
                    startChapter = rawStart.takeIf { validRange },
                    endChapter = rawEnd.takeIf { validRange },
                    budget =
                        json.optInt("budget", DEFAULT_SUMMARY_BATCH_BUDGET)
                            .coerceIn(1, MAX_SUMMARY_BATCH_BUDGET),
                )
            }.getOrNull()
        }
    }

    @Synchronized
    fun setSummaryBatchPrefs(bookId: String, prefs: SummaryBatchPrefs) {
        if (bookId.isBlank()) return
        writableDatabase.insertWithOnConflict(
            "reading_settings",
            null,
            ContentValues().apply {
                put("setting_key", SUMMARY_BATCH_PREFS_KEY_PREFIX + bookId)
                put(
                    "setting_value",
                    JSONObject()
                        .put("startChapter", prefs.startChapter ?: JSONObject.NULL)
                        .put("endChapter", prefs.endChapter ?: JSONObject.NULL)
                        .put("budget", prefs.budget)
                        .toString(),
                )
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Removes novel-derived data beyond the latest observed boundary. Reader memories are kept:
     * they are explicitly user-authored and are independently boundary-filtered on retrieval.
     */
    @Synchronized
    fun prepareForState(state: ReadingState) {
        // 段评预取覆盖表跟随预取窗口：清理边界必须与 firstMissingPrefetchChapter
        // 的扫描窗口一致，否则每次 prepare 都会删掉窗口内“已覆盖”记录，
        // 导致流水线反复生成同一章段评（Token 烧毁循环）。
        val autoCommentRetentionIndex = AutoCommentSupport.prefetchWindowUpperIndex(
            state.chapterIndex,
            getPrefetchAheadChapters(),
        )
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                "text_chunks",
                "book_id = ? AND CAST(chapter_index AS INTEGER) > ?",
                arrayOf(state.book.id, state.chapterIndex.toString()),
            )
            db.delete(
                "knowledge_fts",
                "book_id = ? AND CAST(chapter_index AS INTEGER) > ?",
                arrayOf(state.book.id, state.chapterIndex.toString()),
            )
            db.delete(
                "chapter_knowledge",
                "book_id = ? AND chapter_index > ?",
                arrayOf(state.book.id, state.chapterIndex.toString()),
            )
            db.delete(
                "chapters",
                "book_id = ? AND chapter_index > ?",
                arrayOf(state.book.id, state.chapterIndex.toString()),
            )
            db.delete(
                "auto_comment_chapters",
                "book_id = ? AND chapter_index > ?",
                arrayOf(state.book.id, autoCommentRetentionIndex.toString()),
            )
            val bodyPosition = state.bodyPosition
            if (bodyPosition == null) {
                deleteChapter(db, state.book.id, state.chapterIndex)
            } else {
                val removedChunks = db.delete(
                    "text_chunks",
                    """
                    book_id = ? AND CAST(chapter_index AS INTEGER) = ?
                    AND CAST(end_pos AS INTEGER) > ?
                    """.trimIndent(),
                    arrayOf(state.book.id, state.chapterIndex.toString(), bodyPosition.toString()),
                )
                val indexedUntil = getIndexedChapter(db, state.book.id, state.chapterIndex)?.indexedUntil
                val unsafeKnowledge = db.delete(
                    "knowledge_fts",
                    """
                    book_id = ? AND CAST(chapter_index AS INTEGER) = ?
                    AND CAST(source_end_pos AS INTEGER) > ?
                    """.trimIndent(),
                    arrayOf(state.book.id, state.chapterIndex.toString(), bodyPosition.toString()),
                )
                if (unsafeKnowledge > 0) {
                    db.delete(
                        "chapter_knowledge",
                        "book_id = ? AND chapter_index = ?",
                        arrayOf(state.book.id, state.chapterIndex.toString()),
                    )
                }
                if (removedChunks > 0 || (indexedUntil != null && indexedUntil > bodyPosition)) {
                    deleteChapter(db, state.book.id, state.chapterIndex)
                } else {
                    db.execSQL(
                        """
                        UPDATE chapters SET is_complete = 0
                        WHERE book_id = ? AND chapter_index = ?
                        """.trimIndent(),
                        arrayOf<Any>(state.book.id, state.chapterIndex),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun getAutoCommentChapter(
        bookId: String,
        chapterIndex: Int,
    ): AutoCommentChapter? =
        getAutoCommentChapter(readableDatabase, bookId, chapterIndex)

    private fun getAutoCommentChapter(
        db: SQLiteDatabase,
        bookId: String,
        chapterIndex: Int,
    ): AutoCommentChapter? {
        db.query(
            "auto_comment_chapters",
            arrayOf(
                "book_id",
                "chapter_index",
                "chapter_title",
                "content_hash",
                "role_card_id",
                "role_card_name",
                "generation_run_id",
                "generation_policy_version",
                "status",
                "updated_at",
            ),
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return AutoCommentChapter(
                bookId = cursor.getString(0),
                chapterIndex = cursor.getInt(1),
                chapterTitle = cursor.getString(2),
                contentHash = cursor.getString(3),
                roleCardId = if (cursor.isNull(4)) null else cursor.getString(4),
                roleCardName = if (cursor.isNull(5)) null else cursor.getString(5),
                generationRunId = if (cursor.isNull(6)) null else cursor.getLong(6),
                generationPolicyVersion = cursor.getInt(7),
                status = cursor.getString(8),
                updatedAt = cursor.getLong(9),
            )
        }
    }

    @Synchronized
    fun tryClaimAutoCommentGeneration(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String,
        contentHash: String,
        roleCardId: String,
        roleCardName: String,
        generationRunId: Long,
        force: Boolean,
        staleAfterMs: Long,
    ): AutoCommentGenerationClaim {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            // 已中断的 run 不再参与任何 claim/缓存决策：先验证 run 仍为 generating，
            // 防止被 stale 清理标记 interrupted 的协程重新取得所有权或返回与 DB 不一致
            // 的 cached/already_generating 结果。
            val runStillGenerating = db.query(
                "auto_comment_runs",
                arrayOf("id"),
                "id = ? AND status = ?",
                arrayOf(
                    generationRunId.toString(),
                    AUTO_COMMENT_RUN_STATUS_GENERATING,
                ),
                null,
                null,
                null,
                "1",
            ).use { cursor -> cursor.moveToFirst() }
            if (!runStillGenerating) {
                db.setTransactionSuccessful()
                return AutoCommentGenerationClaim(
                    status = AutoCommentGenerationClaimStatus.RUN_INTERRUPTED,
                )
            }
            val existing = getAutoCommentChapter(db, bookId, chapterIndex)
            val sameIdentity =
                existing?.contentHash == contentHash &&
                    existing.roleCardId == roleCardId &&
                    existing.roleCardName == roleCardName &&
                    existing.generationPolicyVersion ==
                        AutoCommentSupport.GENERATION_POLICY_VERSION
            val activeClaim = db.query(
                "auto_comment_generation_claims",
                arrayOf("run_id", "updated_at"),
                "book_id = ? AND chapter_index = ?",
                arrayOf(bookId, chapterIndex.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) null
                else cursor.getLong(0) to cursor.getLong(1)
            }
            if (
                activeClaim != null &&
                activeClaim.first != generationRunId &&
                now - activeClaim.second < staleAfterMs
            ) {
                db.setTransactionSuccessful()
                return AutoCommentGenerationClaim(
                    status = AutoCommentGenerationClaimStatus.ALREADY_GENERATING,
                )
            }
            if (!force && sameIdentity && existing.status == AUTO_COMMENT_STATUS_READY) {
                val commentCount = db.rawQuery(
                    """
                    SELECT COUNT(*)
                    FROM auto_comments
                    WHERE book_id = ? AND chapter_index = ?
                    """.trimIndent(),
                    arrayOf(bookId, chapterIndex.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                db.setTransactionSuccessful()
                return AutoCommentGenerationClaim(
                    status = AutoCommentGenerationClaimStatus.CACHED,
                    commentCount = commentCount,
                )
            }
            if (activeClaim != null) {
                db.delete(
                    "auto_comment_generation_claims",
                    "book_id = ? AND chapter_index = ?",
                    arrayOf(bookId, chapterIndex.toString()),
                )
            }
            db.insertOrThrow(
                "auto_comment_generation_claims",
                null,
                ContentValues().apply {
                    put("book_id", bookId)
                    put("chapter_index", chapterIndex)
                    put("run_id", generationRunId)
                    put("content_hash", contentHash)
                    put("role_card_id", roleCardId)
                    put("role_card_name", roleCardName)
                    put("updated_at", now)
                },
            )
            db.setTransactionSuccessful()
            return AutoCommentGenerationClaim(
                status = AutoCommentGenerationClaimStatus.CLAIMED,
            )
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun markAutoCommentGenerationFailedIfOwned(
        bookId: String,
        chapterIndex: Int,
        generationRunId: Long,
    ): Boolean {
        return writableDatabase.delete(
            "auto_comment_generation_claims",
            "book_id = ? AND chapter_index = ? AND run_id = ?",
            arrayOf(
                bookId,
                chapterIndex.toString(),
                generationRunId.toString(),
            ),
        ) > 0
    }

    private val prefetchPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.getSharedPreferences(PREFETCH_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun getPrefetchAheadChapters(): Int =
        prefetchPreferences
            .getInt(
                PREFETCH_AHEAD_CHAPTERS_KEY,
                AutoCommentSupport.DEFAULT_PREFETCH_AHEAD_CHAPTERS,
            )
            .let(AutoCommentSupport::clampPrefetchAheadChapters)

    @Synchronized
    fun setPrefetchAheadChapters(value: Int): Int {
        val clamped = AutoCommentSupport.clampPrefetchAheadChapters(value)
        prefetchPreferences
            .edit()
            .putInt(PREFETCH_AHEAD_CHAPTERS_KEY, clamped)
            .apply()
        return clamped
    }

    /**
     * 提前生成窗口的“已覆盖”判定：章节已 READY、生成策略版本一致，且（已选角色时）
     * 角色一致。角色卡变更后旧角色章节视为未覆盖，允许新角色重新生成。
     */
    @Synchronized
    fun isAutoCommentChapterCovered(
        bookId: String,
        chapterIndex: Int,
        roleCardId: String? = null,
    ): Boolean {
        val chapter = getAutoCommentChapter(bookId, chapterIndex) ?: return false
        if (chapter.status != AUTO_COMMENT_STATUS_READY) return false
        if (
            chapter.generationPolicyVersion !=
            AutoCommentSupport.GENERATION_POLICY_VERSION
        ) {
            return false
        }
        if (roleCardId == null) return true
        return chapter.roleCardId == roleCardId
    }

    @Synchronized
    fun hasRecentAutoCommentClaim(
        bookId: String,
        chapterIndex: Int,
        staleAfterMs: Long,
    ): Boolean {
        val now = System.currentTimeMillis()
        return readableDatabase.query(
            "auto_comment_generation_claims",
            arrayOf("updated_at"),
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            cursor.moveToFirst() && now - cursor.getLong(0) < staleAfterMs
        }
    }

    /**
     * 该章最近一次终态 run 是否处于“失败重试冷静期”：终态为 failed /
     * no_valid_comments / superseded（均表示该章未产出有效段评），且完成时间距现在
     * 小于 cooldownMs 时返回 true，供流水线跳过该章，避免反复烧 Token 重试同一章。
     */
    @Synchronized
    fun isAutoCommentChapterInRetryCooldown(
        bookId: String,
        chapterIndex: Int,
        cooldownMs: Long,
    ): Boolean {
        if (cooldownMs <= 0) return false
        val now = System.currentTimeMillis()
        return readableDatabase.query(
            "auto_comment_runs",
            arrayOf("finished_at"),
            """
            book_id = ? AND chapter_index = ? AND finished_at IS NOT NULL
            AND status IN (?, ?, ?)
            """.trimIndent(),
            arrayOf(
                bookId,
                chapterIndex.toString(),
                AUTO_COMMENT_RUN_STATUS_FAILED,
                AUTO_COMMENT_RUN_STATUS_NO_VALID_COMMENTS,
                AUTO_COMMENT_RUN_STATUS_SUPERSEDED,
            ),
            null,
            null,
            "finished_at DESC, id DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            // SQL 已过滤为真实失败终态；纯协调状态（already_generating 等）
            // 不会遮住更早的真实失败 run，按最新完成时间计算冷却。
            now - cursor.getLong(0) < cooldownMs
        }
    }

    @Synchronized
    fun setAutoCommentPersona(
        bookId: String,
        roleCardId: String,
        roleCardName: String,
    ): AutoCommentPersona {
        val normalizedBookId = bookId.trim()
        val normalizedRoleCardId = roleCardId.trim()
        val normalizedRoleCardName = roleCardName.trim()
        require(normalizedBookId.isNotBlank()) { "bookId is required" }
        require(normalizedRoleCardId.isNotBlank()) { "roleCardId is required" }
        require(normalizedRoleCardName.isNotBlank()) { "roleCardName is required" }
        val updatedAt = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict(
            "auto_comment_personas",
            null,
            ContentValues().apply {
                put("book_id", normalizedBookId)
                put("role_card_id", normalizedRoleCardId)
                put("role_card_name", normalizedRoleCardName)
                put("updated_at", updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return AutoCommentPersona(
            bookId = normalizedBookId,
            roleCardId = normalizedRoleCardId,
            roleCardName = normalizedRoleCardName,
            updatedAt = updatedAt,
        )
    }

    @Synchronized
    fun getAutoCommentPersona(bookId: String): AutoCommentPersona? {
        readableDatabase.query(
            "auto_comment_personas",
            arrayOf("book_id", "role_card_id", "role_card_name", "updated_at"),
            "book_id = ?",
            arrayOf(bookId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return AutoCommentPersona(
                bookId = cursor.getString(0),
                roleCardId = cursor.getString(1),
                roleCardName = cursor.getString(2),
                updatedAt = cursor.getLong(3),
            )
        }
    }

    @Synchronized
    fun updateAutoCommentPersonaNameIfRoleMatches(
        bookId: String,
        roleCardId: String,
        roleCardName: String,
    ): Boolean {
        return writableDatabase.update(
            "auto_comment_personas",
            ContentValues().apply {
                put("role_card_name", roleCardName)
                put("updated_at", System.currentTimeMillis())
            },
            "book_id = ? AND role_card_id = ?",
            arrayOf(bookId, roleCardId),
        ) > 0
    }

    @Synchronized
    fun publicationSnapshot(): Map<String, String> = readableDatabase.rawQuery(
        "SELECT chapter_ref, revision FROM reading_publications", null,
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) } }

    @Synchronized
    fun replaceAutoComments(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String,
        contentHash: String,
        roleCardId: String,
        roleCardName: String,
        generationRunId: Long,
        comments: List<AutoCommentRecord>,
        publication: PreparedReadingPublication? = null,
    ): Boolean {
        AutoCommentSupport.requireReplacementComments(comments)
        return publishAutoComments(
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            contentHash = contentHash,
            roleCardId = roleCardId,
            roleCardName = roleCardName,
            generationRunId = generationRunId,
            comments = comments,
            publication = publication,
        )
    }

    /**
     * A successful submit_comments may deliberately contain zero comments while still publishing
     * its chapter summary. Keep this explicit so parser/validation failures can never erase old
     * comments by accidentally passing an empty list through [replaceAutoComments].
     */
    @Synchronized
    fun completeAutoCommentGenerationWithNoComments(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String,
        contentHash: String,
        roleCardId: String,
        roleCardName: String,
        generationRunId: Long,
        publication: PreparedReadingPublication? = null,
    ): Boolean =
        publishAutoComments(
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            contentHash = contentHash,
            roleCardId = roleCardId,
            roleCardName = roleCardName,
            generationRunId = generationRunId,
            comments = emptyList(),
            publication = publication,
        )

    private fun publishAutoComments(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String,
        contentHash: String,
        roleCardId: String,
        roleCardName: String,
        generationRunId: Long,
        comments: List<AutoCommentRecord>,
        publication: PreparedReadingPublication? = null,
    ): Boolean {
        val db = writableDatabase
        val createdAt = System.currentTimeMillis()
        var replaced = false
        db.beginTransaction()
        try {
            val ownsGeneration = db.query(
                "auto_comment_generation_claims",
                arrayOf("run_id"),
                "book_id = ? AND chapter_index = ? AND run_id = ?",
                arrayOf(
                    bookId,
                    chapterIndex.toString(),
                    generationRunId.toString(),
                ),
                null,
                null,
                null,
                "1",
            ).use { cursor -> cursor.moveToFirst() }
            if (!ownsGeneration) {
                db.setTransactionSuccessful()
                return false
            }
            if (publication != null) {
                require(publication.bookId == bookId && publication.roleCardId == roleCardId)
                db.insertWithOnConflict("reading_publications", null, ContentValues().apply {
                    put("chapter_ref", publication.chapterRef); put("book_id", bookId)
                    put("source_id", publication.sourceId); put("revision", publication.revision)
                    put("contract_hash", publication.contractHash); put("role_card_id", roleCardId)
                    put("run_id", generationRunId); put("committed_at", createdAt)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            }
            db.delete(
                "auto_comments",
                "book_id = ? AND chapter_index = ?",
                arrayOf(bookId, chapterIndex.toString()),
            )
            val chapterValues = ContentValues().apply {
                put("chapter_title", chapterTitle)
                put("content_hash", contentHash)
                put("role_card_id", roleCardId)
                put("role_card_name", roleCardName)
                put("generation_run_id", generationRunId)
                put(
                    "generation_policy_version",
                    AutoCommentSupport.GENERATION_POLICY_VERSION,
                )
                put("status", AUTO_COMMENT_STATUS_READY)
                put("updated_at", createdAt)
            }
            val updated = db.update(
                "auto_comment_chapters",
                chapterValues,
                "book_id = ? AND chapter_index = ?",
                arrayOf(bookId, chapterIndex.toString()),
            )
            if (updated == 0) {
                db.insertOrThrow(
                    "auto_comment_chapters",
                    null,
                    ContentValues(chapterValues).apply {
                        put("book_id", bookId)
                        put("chapter_index", chapterIndex)
                    },
                )
            }
            comments.forEach { comment ->
                db.insertOrThrow(
                    "auto_comments",
                    null,
                    ContentValues().apply {
                        put("book_id", bookId)
                        put("chapter_index", chapterIndex)
                        put("paragraph_index", comment.paragraphIndex)
                        put("comment_text", comment.text)
                        put("comment_kind", comment.kind)
                        if (comment.roleCardId == null) putNull("role_card_id")
                        else put("role_card_id", comment.roleCardId)
                        if (comment.roleCardName == null) putNull("role_card_name")
                        else put("role_card_name", comment.roleCardName)
                        put("evidence_json", comment.evidenceJson)
                        put("created_at", createdAt)
                    },
                )
                db.insertOrThrow(
                    "auto_comment_run_comments",
                    null,
                    ContentValues().apply {
                        put("run_id", generationRunId)
                        put("book_id", bookId)
                        put("chapter_index", chapterIndex)
                        put("chapter_title", chapterTitle)
                        put("content_hash", contentHash)
                        put("paragraph_index", comment.paragraphIndex)
                        put("comment_text", comment.text)
                        put("comment_kind", comment.kind)
                        if (comment.roleCardId == null) putNull("role_card_id")
                        else put("role_card_id", comment.roleCardId)
                        if (comment.roleCardName == null) putNull("role_card_name")
                        else put("role_card_name", comment.roleCardName)
                        put("evidence_json", comment.evidenceJson)
                        put("created_at", createdAt)
                    },
                )
            }
            db.delete(
                "auto_comment_generation_claims",
                "book_id = ? AND chapter_index = ? AND run_id = ?",
                arrayOf(bookId, chapterIndex.toString(), generationRunId.toString()),
            )
            finishAutoCommentRunInTransaction(
                db = db,
                runId = generationRunId,
                status = AUTO_COMMENT_RUN_STATUS_GENERATED,
                commentCount = comments.size,
                errorMessage = null,
                finishedAt = createdAt,
                finalStage = AutoCommentRunStages.COMPLETED,
            )
            pruneAutoCommentRuns(db)
            replaced = true
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return replaced
    }

    @Synchronized
    fun getAutoComments(
        bookId: String,
        chapterIndex: Int,
        paragraphIndex: Int? = null,
    ): List<AutoCommentRecord> {
        val result = mutableListOf<AutoCommentRecord>()
        val selection = buildString {
            append("book_id = ? AND chapter_index = ?")
            if (paragraphIndex != null) append(" AND paragraph_index = ?")
        }
        val selectionArgs = buildList {
            add(bookId)
            add(chapterIndex.toString())
            paragraphIndex?.let { add(it.toString()) }
        }.toTypedArray()
        readableDatabase.query(
            "auto_comments",
            arrayOf(
                "id",
                "book_id",
                "chapter_index",
                "paragraph_index",
                "comment_text",
                "comment_kind",
                "role_card_id",
                "role_card_name",
                "evidence_json",
                "created_at",
            ),
            selection,
            selectionArgs,
            null,
            null,
            "paragraph_index ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRecord(
                    id = cursor.getLong(0),
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    paragraphIndex = cursor.getInt(3),
                    text = cursor.getString(4),
                    kind = cursor.getString(5),
                    roleCardId = if (cursor.isNull(6)) null else cursor.getString(6),
                    roleCardName = if (cursor.isNull(7)) null else cursor.getString(7),
                    evidenceJson = cursor.getString(8),
                    createdAt = cursor.getLong(9),
                )
            }
        }
        return result
    }

    @Synchronized
    fun getRecentUnlockedAutoComments(
        bookId: String,
        currentChapterIndex: Int,
        currentUnlockedParagraph: Int,
        roleCardId: String?,
        limit: Int,
    ): List<AutoCommentRecord> {
        val normalizedRoleCardId = roleCardId?.trim()?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val selection = buildString {
            append(
                "auto_comments.book_id = ? AND " +
                    "(auto_comments.chapter_index < ? OR " +
                    "(auto_comments.chapter_index = ? AND auto_comments.paragraph_index <= ?))"
            )
            append(" AND auto_comments.role_card_id = ?")
            append(
                " AND EXISTS (" +
                    "SELECT 1 FROM auto_comment_chapters chapter " +
                    "WHERE chapter.book_id = auto_comments.book_id " +
                    "AND chapter.chapter_index = auto_comments.chapter_index " +
                    "AND chapter.status = ? " +
                    "AND chapter.generation_policy_version = ?)"
            )
        }
        val selectionArgs = buildList {
            add(bookId)
            add(currentChapterIndex.toString())
            add(currentChapterIndex.toString())
            add(currentUnlockedParagraph.coerceAtLeast(0).toString())
            add(normalizedRoleCardId)
            add(AUTO_COMMENT_STATUS_READY)
            add(AutoCommentSupport.GENERATION_POLICY_VERSION.toString())
        }.toTypedArray()
        val result = mutableListOf<AutoCommentRecord>()
        readableDatabase.query(
            "auto_comments",
            arrayOf(
                "id",
                "book_id",
                "chapter_index",
                "paragraph_index",
                "comment_text",
                "comment_kind",
                "role_card_id",
                "role_card_name",
                "evidence_json",
                "created_at",
            ),
            selection,
            selectionArgs,
            null,
            null,
            "chapter_index DESC, paragraph_index DESC, id DESC",
            limit.coerceIn(1, 50).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRecord(
                    id = cursor.getLong(0),
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    paragraphIndex = cursor.getInt(3),
                    text = cursor.getString(4),
                    kind = cursor.getString(5),
                    roleCardId = if (cursor.isNull(6)) null else cursor.getString(6),
                    roleCardName = if (cursor.isNull(7)) null else cursor.getString(7),
                    evidenceJson = cursor.getString(8),
                    createdAt = cursor.getLong(9),
                )
            }
        }
        return result
    }

    @Synchronized
    fun startAutoCommentRun(
        trigger: String,
        executionMode: String = AUTO_COMMENT_RUN_EXECUTION_MODE_DIRECT,
        restartedFromRunId: Long? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val runId = db.insertOrThrow(
                "auto_comment_runs",
                null,
                ContentValues().apply {
                    ReadingTaskContext.taskId?.let { put("task_id", it) }
                    put("trigger_source", trigger)
                    put("execution_mode", executionMode)
                    // 进程重启恢复语义：新 run 只作谱系记录旧 run id，绝不续写旧 child。
                    if (restartedFromRunId != null) {
                        put("restarted_from_run_id", restartedFromRunId)
                    }
                    put("status", AUTO_COMMENT_RUN_STATUS_GENERATING)
                    put("stage", AutoCommentRunStages.STARTING)
                    put("stage_updated_at", now)
                    put("run_heartbeat_at", now)
                    put("comment_count", 0)
                    put("started_at", now)
                },
            )
            db.insertOrThrow(
                "auto_comment_run_stage_events",
                null,
                ContentValues().apply {
                    put("run_id", runId)
                    put("stage", AutoCommentRunStages.STARTING)
                    put("started_at", now)
                },
            )
            db.setTransactionSuccessful()
            runId
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateAutoCommentRunStage(
        runId: Long,
        stage: String,
    ) {
        val normalizedStage = stage.trim().ifBlank { AutoCommentRunStages.STARTING }
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val currentStage = db.rawQuery(
                """
                SELECT stage
                FROM auto_comment_run_stage_events
                WHERE run_id = ? AND finished_at IS NULL
                ORDER BY id DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(runId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (currentStage != normalizedStage) {
                db.update(
                    "auto_comment_run_stage_events",
                    ContentValues().apply { put("finished_at", now) },
                    "run_id = ? AND finished_at IS NULL",
                    arrayOf(runId.toString()),
                )
                db.insertOrThrow(
                    "auto_comment_run_stage_events",
                    null,
                    ContentValues().apply {
                        put("run_id", runId)
                        put("stage", normalizedStage)
                        put("started_at", now)
                    },
                )
            }
            db.update(
                "auto_comment_runs",
                ContentValues().apply {
                    put("stage", normalizedStage)
                    put("stage_updated_at", now)
                },
                "id = ?",
                arrayOf(runId.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateAutoCommentRunTarget(
        runId: Long,
        bookId: String,
        chapterIndex: Int? = null,
        chapterTitle: String? = null,
    ) {
        writableDatabase.update(
            "auto_comment_runs",
            ContentValues().apply {
                put("book_id", bookId)
                if (chapterIndex == null) putNull("chapter_index")
                else put("chapter_index", chapterIndex)
                if (chapterTitle == null) putNull("chapter_title")
                else put("chapter_title", chapterTitle)
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    @Synchronized
    fun updateAutoCommentRunExecution(
        runId: Long,
        execution: AutoCommentModelExecution,
    ) {
        writableDatabase.update(
            "auto_comment_runs",
            ContentValues().apply {
                put("role_card_id", execution.roleCardId)
                put("role_card_name", execution.roleCardName)
                put("model_config_id", execution.configId)
                put("model_config_name", execution.configName)
                put("model_index", execution.modelIndex)
                put("model_source", execution.modelSource)
                put("provider", execution.provider)
                put("model", execution.model)
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    @Synchronized
    fun updateAutoCommentRunPromptMetrics(
        runId: Long,
        targetCharacterCount: Int,
        metrics: AutoCommentPromptMetrics,
    ) {
        writableDatabase.update(
            "auto_comment_runs",
            ContentValues().apply {
                put("target_character_count", targetCharacterCount.coerceAtLeast(0))
                put("context_chapter_count", metrics.previousContextChapterCount.coerceAtLeast(0))
                put(
                    "context_character_count",
                    metrics.previousContextCharacterCount.coerceAtLeast(0),
                )
                put("context_window_tokens", metrics.contextWindowTokens.coerceAtLeast(0))
                put("estimated_input_tokens", metrics.estimatedInputTokens.coerceAtLeast(0))
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    @Synchronized
    fun updateAutoCommentRunUsage(
        runId: Long,
        usage: com.ai.assistance.operit.data.stats.ProviderUsageSnapshot,
    ) {
        writableDatabase.update(
            "auto_comment_runs",
            ContentValues().apply {
                if (usage.uncachedInputTokens == null) putNull("actual_uncached_input_tokens")
                else put("actual_uncached_input_tokens", usage.uncachedInputTokens)
                if (usage.cachedInputTokens == null) putNull("actual_cached_input_tokens")
                else put("actual_cached_input_tokens", usage.cachedInputTokens)
                if (usage.totalInputTokens == null) putNull("actual_input_tokens")
                else put("actual_input_tokens", usage.totalInputTokens)
                if (usage.outputTokens == null) putNull("actual_output_tokens")
                else put("actual_output_tokens", usage.outputTokens)
                if (usage.reasoningTokens == null) putNull("actual_reasoning_tokens")
                else put("actual_reasoning_tokens", usage.reasoningTokens)
                put("actual_usage_source", usage.source)
                put("actual_usage_complete", if (usage.completeSnapshot) 1 else 0)
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    /**
     * 建立 run 与主库 subagent 执行的弱关联（reading 侧字段）。
     *
     * 无跨库 FK；主库侧 externalOwnerType/externalOwnerId 由
     * [ReadingCompanionSubagentCoordinator]（阶段 2 起）经 SubagentRunRepository 写入。
     */
    @Synchronized
    fun linkSubagentExecution(
        runId: Long,
        parentChatId: String?,
        subagentRunId: String,
        childChatId: String,
    ) {
        writableDatabase.update(
            "auto_comment_runs",
            ContentValues().apply {
                if (parentChatId == null) putNull("parent_chat_id")
                else put("parent_chat_id", parentChatId)
                put("subagent_run_id", subagentRunId)
                put("child_chat_id", childChatId)
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    /**
     * claim 心跳：刷新 claims.updated_at（stale 窗口误杀防护）。
     *
     * 只允许 claim owner 刷新；affected != 1 => claim 已被抢占/释放/丢失，返回 false，
     * 调用方必须立即停止生成（claim_lost）。
     */
    @Synchronized
    fun heartbeatClaimIfOwned(
        bookId: String,
        chapterIndex: Int,
        runId: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        writableDatabase.update(
            "auto_comment_generation_claims",
            ContentValues().apply { put("updated_at", now) },
            READING_CLAIM_OWNER_WHERE_SQL,
            arrayOf(bookId, chapterIndex.toString(), runId.toString()),
        ) == 1

    /**
     * 刷新通用 run 存活时间。summary-only 没有段评 claim，靠此心跳避免被周期 stale 清扫
     * 误杀；只有仍为 generating 的 run 才能刷新。
     */
    @Synchronized
    fun heartbeatRunIfGenerating(
        runId: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        writableDatabase.update(
            "auto_comment_runs",
            ContentValues().apply { put("run_heartbeat_at", now) },
            "id = ? AND status = ?",
            arrayOf(runId.toString(), AUTO_COMMENT_RUN_STATUS_GENERATING),
        ) == 1

    @Synchronized
    fun incrementRunModelRound(runId: Long): Boolean =
        writableDatabase.execSQL(
            """
            UPDATE auto_comment_runs
            SET model_round_count = model_round_count + 1
            WHERE id = ?
            """.trimIndent(),
            arrayOf<Any>(runId),
        ).let { true }

    @Synchronized
    fun incrementRunToolInvocation(runId: Long): Boolean =
        writableDatabase.execSQL(
            """
            UPDATE auto_comment_runs
            SET tool_invocation_count = tool_invocation_count + 1
            WHERE id = ?
            """.trimIndent(),
            arrayOf<Any>(runId),
        ).let { true }

    /**
     * 把阅读侧 run 标为 interrupted 并释放其 claim（transcript 保留，不删除）。
     * 用于：进程重启恢复、跨库对账发现目标不存在、claim_lost。
     */
    @Synchronized
    fun markRunInterrupted(
        runId: Long,
        errorMessage: String = "interrupted",
    ): Boolean {
        val db = writableDatabase
        val finishedAt = System.currentTimeMillis()
        db.beginTransaction()
        return try {
            db.delete(
                "auto_comment_generation_claims",
                "run_id = ?",
                arrayOf(runId.toString()),
            )
            db.update(
                "auto_comment_run_stage_events",
                ContentValues().apply { put("finished_at", finishedAt) },
                "run_id = ? AND finished_at IS NULL",
                arrayOf(runId.toString()),
            )
            val updated =
                db.update(
                    "auto_comment_runs",
                    ContentValues().apply {
                        put("status", AUTO_COMMENT_RUN_STATUS_INTERRUPTED)
                        put("error_message", errorMessage)
                        put("finished_at", finishedAt)
                    },
                    "id = ? AND status = ?",
                    arrayOf(runId.toString(), AUTO_COMMENT_RUN_STATUS_GENERATING),
                )
            db.setTransactionSuccessful()
            updated > 0
        } finally {
            db.endTransaction()
        }
    }

    /** 阅读库侧全部带弱关联的 run（reconciliation 正向输入）。 */
    @Synchronized
    fun getSubagentLinkedRuns(): List<ReadingCompanionRunLinkRef> =
        readableDatabase.query(
            "auto_comment_runs",
            arrayOf("id", "status", "parent_chat_id", "subagent_run_id", "child_chat_id"),
            "subagent_run_id IS NOT NULL",
            null,
            null,
            null,
            "started_at ASC, id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ReadingCompanionRunLinkRef(
                            runId = cursor.getLong(0),
                            status = cursor.getString(1),
                            parentChatId =
                                if (cursor.isNull(2)) null else cursor.getString(2),
                            subagentRunId =
                                if (cursor.isNull(3)) null else cursor.getString(3),
                            childChatId =
                                if (cursor.isNull(4)) null else cursor.getString(4),
                        )
                    )
                }
            }
        }

    /**
     * 跨库弱关联对账（唯一一致性机制；无跨库 FK）。编排逻辑见
     * [runReadingCompanionReconcile]（生产与 JVM 单测共用同一实现）。
     */
    suspend fun reconcileWithChatGraph(
        lookupSubagentRun: suspend (subagentRunId: String) -> ReadingCompanionSubagentRunLink?,
        chatExists: suspend (chatId: String) -> Boolean,
        interruptSubagentRun: suspend (subagentRunId: String) -> Boolean,
        activeMainOwners: List<ReadingCompanionMainOwnerRef>,
    ): ReadingCompanionReconcileOutcome =
        runReadingCompanionReconcile(
            linkedRuns = getSubagentLinkedRuns(),
            activeMainOwners = activeMainOwners,
            lookupSubagentRun = lookupSubagentRun,
            chatExists = chatExists,
            relinkChildChat = { runId, parentChatId, childChatId ->
                writableDatabase.update(
                    "auto_comment_runs",
                    ContentValues().apply {
                        put("child_chat_id", childChatId)
                        if (parentChatId != null) {
                            put("parent_chat_id", parentChatId)
                        }
                    },
                    "id = ?",
                    arrayOf(runId.toString()),
                )
            },
            interruptReadingRun = { runId, errorMessage ->
                markRunInterrupted(runId, errorMessage)
            },
            readingRunExists = { runId ->
                readableDatabase.query(
                    "auto_comment_runs",
                    arrayOf("id"),
                    "id = ?",
                    arrayOf(runId.toString()),
                    null,
                    null,
                    null,
                    "1",
                ).use { it.moveToFirst() }
            },
            interruptSubagentRun = interruptSubagentRun,
        )

    /**
     * 进程启动对账（与 SubagentRunRepository.reconcileIncompleteRuns 对称）。
     *
     * 1. 重启后没有存活协程：把所有 started_at 早于进程启动时刻的 generating run 标
     *    interrupted 并释放 claim（旧单发/旧 subagent run 都不续写）。
     * 2. 与主库 chat 图双向对账（补链/打断孤儿；链接列写出前为惰性空转）。
     */
    suspend fun reconcileAfterProcessStart(now: Long): ReadingCompanionReconcileOutcome {
        interruptStaleAutoCommentRuns(staleBefore = now)
        val outcome = reconcileCrossDatabase()
        // 剪枝挂账的重建式兜底（幂等）：启动对账用临时 Store 实例触发 prune 后，即使挂账
        // 队列随实例丢弃/进程退出而丢失，也能从 reading.db 现状重建并子树删除隐藏 child/
        // 空根。只 interrupt + 清理孤儿聊天，绝不续跑或删除 transcript。
        try {
            runOrphanChatCleanup()
        } catch (error: Throwable) {
            com.ai.assistance.operit.util.AppLogger.w(
                "ReadingCompanion",
                "Startup orphan chat cleanup failed: ${error.message}",
                error,
            )
        }
        return outcome
    }

    /**
     * 进程内轻量对账（Worker 开头/启动阶段之后调用）：只做跨库 chat 图对账
     * （补链/打断孤儿），**不做**“重启清扫”（interruptStaleAutoCommentRuns(staleBefore=now)）。
     *
     * 清扫只在真实进程启动时执行一次（[reconcileAfterProcessStart]），否则 Worker 开头的清扫
     * 会把同一进程内正在 heartbeat 的手动/对话内生成误杀（它们的 claim.updated_at 必然早于
     * “现在”）。对账只 interrupt + 释放 claim + 补链，绝不删除 transcript。
     */
    suspend fun reconcileCrossDatabase(): ReadingCompanionReconcileOutcome {
        val subagentRepository =
            com.ai.assistance.operit.data.repository.SubagentRunRepository
                .getInstance(appContext)
        val chatDao =
            com.ai.assistance.operit.data.db.AppDatabase
                .getDatabase(appContext)
                .chatDao()
        return try {
            reconcileWithChatGraph(
                lookupSubagentRun = { subagentRunId ->
                    try {
                        subagentRepository.getById(subagentRunId)
                    } catch (_: Throwable) {
                        null
                    }
                        ?.let {
                            ReadingCompanionSubagentRunLink(
                                subagentRunId = it.id,
                                parentChatId = it.parentChatId,
                                childChatId = it.childChatId,
                            )
                        }
                },
                chatExists = { chatId ->
                    try {
                        chatDao.getChatById(chatId) != null
                    } catch (_: Throwable) {
                        false
                    }
                },
                interruptSubagentRun = { subagentRunId ->
                    try {
                        subagentRepository.updateStatus(
                            taskId = subagentRunId,
                            status = com.ai.assistance.operit.data.model.SubagentRunStatus.INTERRUPTED,
                            error =
                                com.ai.assistance.operit.data.repository.SubagentInterruption
                                    .READING_COMPANION,
                        )
                    } catch (_: Throwable) {
                        false
                    }
                },
                activeMainOwners = activeReadingOwnersOnMain(subagentRepository),
            )
        } catch (_: Throwable) {
            ReadingCompanionReconcileOutcome()
        }
    }

    private suspend fun activeReadingOwnersOnMain(
        subagentRepository: com.ai.assistance.operit.data.repository.SubagentRunRepository,
    ): List<ReadingCompanionMainOwnerRef> {
        val activeStatuses =
            listOf(
                com.ai.assistance.operit.data.model.SubagentRunStatus.CREATED.name,
                com.ai.assistance.operit.data.model.SubagentRunStatus.QUEUED.name,
                com.ai.assistance.operit.data.model.SubagentRunStatus.RUNNING.name,
            )
        return subagentRepository
            .getByExternalOwnerType(READING_COMPANION_SUBAGENT_OWNER_TYPE)
            .filter { it.status in activeStatuses }
            .mapNotNull { run ->
                run.externalOwnerId
                    ?.toLongOrNull()
                    ?.let { readingRunId ->
                        ReadingCompanionMainOwnerRef(
                            subagentRunId = run.id,
                            readingRunId = readingRunId,
                        )
                    }
            }
    }

    @Synchronized
    fun recordAutoCommentRunTrace(
        runId: Long,
        operation: String,
        status: String,
        startedAt: Long,
        finishedAt: Long? = null,
        metadataJson: String? = null,
    ): Long {
        val normalizedOperation = operation.trim().take(80).ifBlank { "unknown" }
        val normalizedStatus = status.trim().take(40).ifBlank { "completed" }
        return writableDatabase.insertOrThrow(
            "auto_comment_run_trace",
            null,
            ContentValues().apply {
                put("run_id", runId)
                put("operation", normalizedOperation)
                put("status", normalizedStatus)
                put("started_at", startedAt)
                if (finishedAt == null) putNull("finished_at") else put("finished_at", finishedAt)
                if (metadataJson == null) putNull("metadata_json")
                else put("metadata_json", metadataJson.take(4000))
            },
        )
    }

    @Synchronized
    fun finishAutoCommentRun(
        runId: Long,
        status: String,
        commentCount: Int = 0,
        errorMessage: String? = null,
    ) {
        val db = writableDatabase
        val finishedAt = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete(
                "auto_comment_generation_claims",
                "run_id = ?",
                arrayOf(runId.toString()),
            )
            finishAutoCommentRunInTransaction(
                db = db,
                runId = runId,
                status = status,
                commentCount = commentCount,
                errorMessage = errorMessage,
                finishedAt = finishedAt,
            )
            pruneAutoCommentRuns(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun finishAutoCommentRunInTransaction(
        db: SQLiteDatabase,
        runId: Long,
        status: String,
        commentCount: Int,
        errorMessage: String?,
        finishedAt: Long,
        finalStage: String? = null,
    ) {
        val isGenerating = db.query(
            "auto_comment_runs",
            arrayOf("id"),
            "id = ? AND status = ?",
            arrayOf(runId.toString(), AUTO_COMMENT_RUN_STATUS_GENERATING),
            null,
            null,
            null,
            "1",
        ).use { cursor -> cursor.moveToFirst() }
        if (!isGenerating) return
        db.update(
            "auto_comment_run_stage_events",
            ContentValues().apply { put("finished_at", finishedAt) },
            "run_id = ? AND finished_at IS NULL",
            arrayOf(runId.toString()),
        )
        if (finalStage != null) {
            db.insertOrThrow(
                "auto_comment_run_stage_events",
                null,
                ContentValues().apply {
                    put("run_id", runId)
                    put("stage", finalStage)
                    put("started_at", finishedAt)
                    put("finished_at", finishedAt)
                },
            )
        }
        db.update(
            "auto_comment_runs",
            ContentValues().apply {
                put("status", status)
                put("comment_count", commentCount.coerceAtLeast(0))
                if (errorMessage == null) putNull("error_message")
                else put("error_message", errorMessage)
                if (finalStage != null) {
                    put("stage", finalStage)
                    put("stage_updated_at", finishedAt)
                }
                put("run_heartbeat_at", finishedAt)
                put("finished_at", finishedAt)
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
    }

    @Synchronized
    fun getRecentAutoCommentRuns(limit: Int = 10): List<AutoCommentRun> {
        val result = mutableListOf<AutoCommentRun>()
        readableDatabase.query(
            "auto_comment_runs LEFT JOIN books ON books.book_id = auto_comment_runs.book_id",
            arrayOf(
                "auto_comment_runs.id",
                "auto_comment_runs.book_id",
                "books.name",
                "auto_comment_runs.chapter_index",
                "auto_comment_runs.chapter_title",
                "auto_comment_runs.trigger_source",
                "auto_comment_runs.status",
                "auto_comment_runs.stage",
                "auto_comment_runs.stage_updated_at",
                "auto_comment_runs.role_card_id",
                "auto_comment_runs.role_card_name",
                "auto_comment_runs.model_config_id",
                "auto_comment_runs.model_config_name",
                "auto_comment_runs.model_index",
                "auto_comment_runs.model_source",
                "auto_comment_runs.provider",
                "auto_comment_runs.model",
                "auto_comment_runs.target_character_count",
                "auto_comment_runs.context_chapter_count",
                "auto_comment_runs.context_character_count",
                "auto_comment_runs.context_window_tokens",
                "auto_comment_runs.estimated_input_tokens",
                "auto_comment_runs.actual_uncached_input_tokens",
                "auto_comment_runs.actual_cached_input_tokens",
                "auto_comment_runs.actual_input_tokens",
                "auto_comment_runs.actual_output_tokens",
                "auto_comment_runs.actual_reasoning_tokens",
                "auto_comment_runs.actual_usage_source",
                "auto_comment_runs.actual_usage_complete",
                "auto_comment_runs.comment_count",
                "auto_comment_runs.error_message",
                "auto_comment_runs.started_at",
                "auto_comment_runs.finished_at",
                "auto_comment_runs.execution_mode",
                "auto_comment_runs.parent_chat_id",
                "auto_comment_runs.subagent_run_id",
                "auto_comment_runs.child_chat_id",
                "auto_comment_runs.model_round_count",
                "auto_comment_runs.tool_invocation_count",
                "auto_comment_runs.restarted_from_run_id",
            ),
            null,
            null,
            null,
            null,
            "started_at DESC, id DESC",
            limit.coerceIn(1, 50).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRun(
                    id = cursor.getLong(0),
                    bookId = if (cursor.isNull(1)) null else cursor.getString(1),
                    bookName = if (cursor.isNull(2)) null else cursor.getString(2),
                    chapterIndex = if (cursor.isNull(3)) null else cursor.getInt(3),
                    chapterTitle = if (cursor.isNull(4)) null else cursor.getString(4),
                    trigger = cursor.getString(5),
                    status = cursor.getString(6),
                    stage = cursor.getString(7),
                    stageUpdatedAt = cursor.getLong(8),
                    roleCardId = if (cursor.isNull(9)) null else cursor.getString(9),
                    roleCardName = if (cursor.isNull(10)) null else cursor.getString(10),
                    modelConfigId = if (cursor.isNull(11)) null else cursor.getString(11),
                    modelConfigName = if (cursor.isNull(12)) null else cursor.getString(12),
                    modelIndex = if (cursor.isNull(13)) null else cursor.getInt(13),
                    modelSource = if (cursor.isNull(14)) null else cursor.getString(14),
                    provider = if (cursor.isNull(15)) null else cursor.getString(15),
                    model = if (cursor.isNull(16)) null else cursor.getString(16),
                    targetCharacterCount =
                        if (cursor.isNull(17)) null else cursor.getInt(17),
                    contextChapterCount =
                        if (cursor.isNull(18)) null else cursor.getInt(18),
                    contextCharacterCount =
                        if (cursor.isNull(19)) null else cursor.getInt(19),
                    contextWindowTokens =
                        if (cursor.isNull(20)) null else cursor.getInt(20),
                    estimatedInputTokens =
                        if (cursor.isNull(21)) null else cursor.getInt(21),
                    actualUncachedInputTokens =
                        if (cursor.isNull(22)) null else cursor.getLong(22),
                    actualCachedInputTokens =
                        if (cursor.isNull(23)) null else cursor.getLong(23),
                    actualInputTokens =
                        if (cursor.isNull(24)) null else cursor.getLong(24),
                    actualOutputTokens =
                        if (cursor.isNull(25)) null else cursor.getLong(25),
                    actualReasoningTokens =
                        if (cursor.isNull(26)) null else cursor.getLong(26),
                    actualUsageSource =
                        if (cursor.isNull(27)) null else cursor.getString(27),
                    actualUsageComplete =
                        if (cursor.isNull(28)) null else cursor.getInt(28) != 0,
                    commentCount = cursor.getInt(29),
                    errorMessage = if (cursor.isNull(30)) null else cursor.getString(30),
                    startedAt = cursor.getLong(31),
                    finishedAt = if (cursor.isNull(32)) null else cursor.getLong(32),
                    executionMode = cursor.getString(33),
                    parentChatId = if (cursor.isNull(34)) null else cursor.getString(34),
                    subagentRunId = if (cursor.isNull(35)) null else cursor.getString(35),
                    childChatId = if (cursor.isNull(36)) null else cursor.getString(36),
                    modelRoundCount = cursor.getInt(37),
                    toolInvocationCount = cursor.getInt(38),
                    restartedFromRunId =
                        if (cursor.isNull(39)) null else cursor.getLong(39),
                )
            }
        }
        return result
    }

    @Synchronized
    fun getAutoCommentRun(runId: Long): AutoCommentRun? =
        getRecentAutoCommentRuns(AUTO_COMMENT_RUN_RETENTION)
            .firstOrNull { run -> run.id == runId }

    @Synchronized
    fun getAutoCommentRunStageEvents(runId: Long): List<AutoCommentRunStageEvent> {
        val result = mutableListOf<AutoCommentRunStageEvent>()
        readableDatabase.query(
            "auto_comment_run_stage_events",
            arrayOf("id", "run_id", "stage", "started_at", "finished_at"),
            "run_id = ?",
            arrayOf(runId.toString()),
            null,
            null,
            "started_at ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRunStageEvent(
                    id = cursor.getLong(0),
                    runId = cursor.getLong(1),
                    stage = cursor.getString(2),
                    startedAt = cursor.getLong(3),
                    finishedAt = if (cursor.isNull(4)) null else cursor.getLong(4),
                )
            }
        }
        return result
    }

    @Synchronized
    fun getAutoCommentRunComments(runId: Long): List<AutoCommentRunCommentSnapshot> {
        val result = mutableListOf<AutoCommentRunCommentSnapshot>()
        readableDatabase.query(
            "auto_comment_run_comments",
            arrayOf(
                "id",
                "run_id",
                "book_id",
                "chapter_index",
                "chapter_title",
                "content_hash",
                "paragraph_index",
                "comment_text",
                "comment_kind",
                "role_card_id",
                "role_card_name",
                "evidence_json",
                "created_at",
            ),
            "run_id = ?",
            arrayOf(runId.toString()),
            null,
            null,
            "chapter_index ASC, paragraph_index ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRunCommentSnapshot(
                    id = cursor.getLong(0),
                    runId = cursor.getLong(1),
                    bookId = cursor.getString(2),
                    chapterIndex = cursor.getInt(3),
                    chapterTitle = cursor.getString(4),
                    contentHash = cursor.getString(5),
                    paragraphIndex = cursor.getInt(6),
                    text = cursor.getString(7),
                    kind = cursor.getString(8),
                    roleCardId = if (cursor.isNull(9)) null else cursor.getString(9),
                    roleCardName = if (cursor.isNull(10)) null else cursor.getString(10),
                    evidenceJson = cursor.getString(11),
                    createdAt = cursor.getLong(12),
                )
            }
        }
        return result
    }

    @Synchronized
    fun getAutoCommentRunTraceEvents(runId: Long): List<AutoCommentRunTraceEvent> {
        val result = mutableListOf<AutoCommentRunTraceEvent>()
        readableDatabase.query(
            "auto_comment_run_trace",
            arrayOf(
                "id",
                "run_id",
                "operation",
                "status",
                "started_at",
                "finished_at",
                "metadata_json",
            ),
            "run_id = ?",
            arrayOf(runId.toString()),
            null,
            null,
            "started_at ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AutoCommentRunTraceEvent(
                    id = cursor.getLong(0),
                    runId = cursor.getLong(1),
                    operation = cursor.getString(2),
                    status = cursor.getString(3),
                    startedAt = cursor.getLong(4),
                    finishedAt = if (cursor.isNull(5)) null else cursor.getLong(5),
                    metadataJson = if (cursor.isNull(6)) null else cursor.getString(6),
                )
            }
        }
        return result
    }

    /**
     * 是否存在仍在进行的自动段评生成。
     *
     * claim 表是并发权威信号：真正在生成的 run 一定持有 claim，且 finish/失败时会删除。
     * 这里同时 JOIN auto_comment_runs 确认 run 状态仍为 generating，避免过期 claim 残留
     * 导致误报；并兜底覆盖 startAutoCommentRun 创建 run 到 tryClaimAutoCommentGeneration
     * 写入 claim 之间的极小窗口（run 已生成但 claim 尚未写入）。
     */
    @Synchronized
    fun hasActiveAutoCommentGeneration(staleAfterMs: Long): Boolean {
        val staleBefore = System.currentTimeMillis() - staleAfterMs
        val claimActive = readableDatabase.rawQuery(
            """
            SELECT 1
            FROM auto_comment_generation_claims
            JOIN auto_comment_runs ON auto_comment_runs.id = auto_comment_generation_claims.run_id
            WHERE auto_comment_generation_claims.updated_at > ?
              AND auto_comment_runs.status = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(staleBefore.toString(), AUTO_COMMENT_RUN_STATUS_GENERATING),
        ).use { cursor ->
            cursor.moveToFirst()
        }
        if (claimActive) return true
        // 兜底：无 claim 的 summary-only run，以及 claim 尚未写入/已释放但尚未收尾的窗口。
        return readableDatabase.query(
            "auto_comment_runs",
            arrayOf("id"),
            "status = ? AND run_heartbeat_at >= ?",
            arrayOf(AUTO_COMMENT_RUN_STATUS_GENERATING, staleBefore.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    @Synchronized
    fun interruptStaleAutoCommentRuns(staleBefore: Long): Int {
        val db = writableDatabase
        val finishedAt = System.currentTimeMillis()
        db.beginTransaction()
        return try {
            val staleRunIds = mutableListOf<Long>()
            db.query(
                "auto_comment_runs",
                arrayOf("id"),
                READING_STALE_RUN_WHERE_SQL,
                arrayOf(
                    AUTO_COMMENT_RUN_STATUS_GENERATING,
                    staleBefore.toString(),
                    staleBefore.toString(),
                ),
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) staleRunIds += cursor.getLong(0)
            }
            if (staleRunIds.isNotEmpty()) {
                val placeholders = staleRunIds.joinToString(",") { "?" }
                db.delete(
                    "auto_comment_generation_claims",
                    "run_id IN ($placeholders)",
                    staleRunIds.map(Long::toString).toTypedArray(),
                )
                db.update(
                    "auto_comment_run_stage_events",
                    ContentValues().apply { put("finished_at", finishedAt) },
                    "run_id IN ($placeholders) AND finished_at IS NULL",
                    staleRunIds.map(Long::toString).toTypedArray(),
                )
            }
            val updated = db.update(
                "auto_comment_runs",
                ContentValues().apply {
                    put("status", AUTO_COMMENT_RUN_STATUS_INTERRUPTED)
                    put("error_message", "interrupted")
                    put("finished_at", finishedAt)
                },
                READING_STALE_RUN_WHERE_SQL,
                arrayOf(
                    AUTO_COMMENT_RUN_STATUS_GENERATING,
                    staleBefore.toString(),
                    staleBefore.toString(),
                ),
            )
            pruneAutoCommentRuns(db)
            db.setTransactionSuccessful()
            updated
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 剪枝旧 run（保留 generating 行 + 最近 [AUTO_COMMENT_RUN_RETENTION]）。
     *
     * 删除**前**先收集被删 run 的 child_chat_id/book_id 挂账；主库 hidden child 与隐藏根的
     * 子树删除由 [flushPrunedRunChatCleanup] 在挂起语境执行（施工图阶段 4 prune 联动）。
     */
    private fun pruneAutoCommentRuns(db: SQLiteDatabase) {
        val selection =
            """
            status != ? AND id NOT IN (
                SELECT id
                FROM auto_comment_runs
                ORDER BY started_at DESC, id DESC
                LIMIT ?
            )
            """.trimIndent()
        val selectionArgs =
            arrayOf(
                AUTO_COMMENT_RUN_STATUS_GENERATING,
                AUTO_COMMENT_RUN_RETENTION.toString(),
            )
        val prunedRefs =
            db.query(
                "auto_comment_runs",
                arrayOf("id", "book_id", "child_chat_id"),
                selection,
                selectionArgs,
                null,
                null,
                null,
            ).use { cursor ->
                val refs = mutableListOf<PrunedRunChatRef>()
                while (cursor.moveToNext()) {
                    val childChatId =
                        if (cursor.isNull(2)) null else cursor.getString(2)
                    if (childChatId.isNullOrBlank()) continue
                    refs +=
                        PrunedRunChatRef(
                            childChatId = childChatId,
                            bookId = if (cursor.isNull(1)) null else cursor.getString(1),
                        )
                }
                refs
            }
        db.execSQL(pruneAutoCommentArchiveSql(selection), selectionArgs)
        db.delete("auto_comment_runs", selection, selectionArgs)
        if (prunedRefs.isNotEmpty()) {
            synchronized(prunedRunChatQueue) {
                prunedRunChatQueue.addAll(prunedRefs)
            }
        }
    }

    /** 排空剪枝挂账（事务外调用；生产由 [flushPrunedRunChatCleanup] 驱动）。 */
    private fun drainPrunedRunChatRefs(): List<PrunedRunChatRef> =
        synchronized(prunedRunChatQueue) {
            val drained = prunedRunChatQueue.toList()
            prunedRunChatQueue.clear()
            drained
        }

    /**
     * 剪枝联动：把挂账的 hidden 审计 child / 空根同步到主库聊天删除（子树删除）。
     *
     * 只删 hidden 审计 child；isHidden=false 的对话内 child 绝不删；某书隐藏根下已无剩余
     * hidden child 才删该根。任何一次都不会回退到单发段评生成。
     */
    suspend fun flushPrunedRunChatCleanup(): PruneCleanupOutcome {
        val pending = drainPrunedRunChatRefs()
        if (pending.isEmpty()) {
            return PruneCleanupOutcome(deletedChildChatIds = emptyList(), deletedRootChatIds = emptyList())
        }
        val chatDao =
            com.ai.assistance.operit.data.db.AppDatabase
                .getDatabase(appContext)
                .chatDao()
        val chatHistoryManager =
            com.ai.assistance.operit.data.repository.ChatHistoryManager
                .getInstance(appContext)
        val cleanup =
            ReadingCompanionPruneCleanup(
                listHiddenChats = { chatDao.getHiddenChatsDirectly() },
                deleteChat = { chatId ->
                    try {
                        chatHistoryManager.deleteChatHistory(chatId)
                    } catch (error: Throwable) {
                        com.ai.assistance.operit.util.AppLogger.w(
                            "ReadingCompanion",
                            "Prune chat cleanup delete failed for $chatId: ${error.message}",
                            error,
                        )
                        false
                    }
                },
            )
        val outcome = cleanup.run(pending)
        if (outcome.deletedChildChatIds.isNotEmpty() || outcome.deletedRootChatIds.isNotEmpty()) {
            val message =
                "Prune chat cleanup: children=${outcome.deletedChildChatIds.size} " +
                    "roots=${outcome.deletedRootChatIds.size}"
            com.ai.assistance.operit.util.AppLogger.i(
                "ReadingCompanion",
                message,
            )
        }
        return outcome
    }

    /**
     * 幂等孤儿重建清理（阶段 4 终审 BLOCKING-3）：从数据库现状重建被剪 run 的 hidden
     * 审计 child / 空根并子树删除，不依赖本次进程内任何挂账队列。
     *
     * 由以下入口统一排空：进程启动对账（[reconcileAfterProcessStart]）、两个 Worker 开头、
     * status/history/detail（[settleInterruptedRuns]）、生成结束 finally。删除仍走
     * ChatHistoryManager 子树删除；isHidden=false 的对话内 child 绝不删。
     */
    suspend fun runOrphanChatCleanup(): PruneCleanupOutcome {
        val chatDao =
            com.ai.assistance.operit.data.db.AppDatabase
                .getDatabase(appContext)
                .chatDao()
        val chatHistoryManager =
            com.ai.assistance.operit.data.repository.ChatHistoryManager
                .getInstance(appContext)
        val cleanup =
            ReadingCompanionOrphanChatCleanup(
                listHiddenChats = { chatDao.getHiddenChatsDirectly() },
                runExists = { runId -> getAutoCommentRun(runId) != null },
                deleteChat = { chatId ->
                    try {
                        chatHistoryManager.deleteChatHistory(chatId)
                    } catch (error: Throwable) {
                        com.ai.assistance.operit.util.AppLogger.w(
                            "ReadingCompanion",
                            "Orphan chat cleanup delete failed for $chatId: ${error.message}",
                            error,
                        )
                        false
                    }
                },
            )
        return try {
            cleanup.run()
        } catch (error: Throwable) {
            com.ai.assistance.operit.util.AppLogger.w(
                "ReadingCompanion",
                "Orphan chat cleanup failed: ${error.message}",
                error,
            )
            PruneCleanupOutcome(
                deletedChildChatIds = emptyList(),
                deletedRootChatIds = emptyList(),
            )
        }
    }

    @Synchronized
    fun isCompleteChapterIndexed(bookId: String, chapterIndex: Int): Boolean =
        getIndexedChapter(readableDatabase, bookId, chapterIndex)?.isComplete == true

    @Synchronized
    fun getIndexedChapter(bookId: String, chapterIndex: Int): IndexedChapter? =
        getIndexedChapter(readableDatabase, bookId, chapterIndex)

    @Synchronized
    fun replaceChapter(content: ReadableChapterContent) {
        val db = writableDatabase
        val contentHash = ReadingTextIndexSupport.sha256(content.content)
        db.beginTransaction()
        try {
            requireCatalogSource(db, content.bookId, content.chapterIndex, content.sourceId)
            val indexedChapter = getIndexedChapter(db, content.bookId, content.chapterIndex)
            val storedChunks = getStoredChunks(db, content.bookId, content.chapterIndex)
            val unchanged = indexedChapter?.contentHash == contentHash &&
                indexedChapter.indexedUntil == content.readableUntil &&
                indexedChapter.title == content.chapterTitle
            val canExtend = !unchanged &&
                indexedChapter != null &&
                indexedChapter.title == content.chapterTitle &&
                indexedChapter.indexedUntil <= content.readableUntil &&
                storedChunks.all { chunk ->
                    chunk.start >= 0 &&
                        chunk.end in chunk.start..content.content.length &&
                        content.content.substring(chunk.start, chunk.end) == chunk.text
                }
            val rebuildFrom = when {
                unchanged -> content.readableUntil
                canExtend -> storedChunks.lastOrNull()?.start ?: 0
                else -> 0
            }
            if (!unchanged) {
                db.delete(
                    "text_chunks",
                    """
                    book_id = ? AND CAST(chapter_index AS INTEGER) = ?
                    AND CAST(start_pos AS INTEGER) >= ?
                    """.trimIndent(),
                    arrayOf(
                        content.bookId,
                        content.chapterIndex.toString(),
                        rebuildFrom.toString(),
                    ),
                )
                val knowledgeHash = getKnowledgeContentHash(db, content.bookId, content.chapterIndex)
                if (knowledgeHash != null && knowledgeHash != contentHash) {
                    deleteKnowledge(db, content.bookId, content.chapterIndex)
                }
            }
            val chunks = if (unchanged) emptyList()
            else ReadingTextIndexSupport.chunkFrom(content.content, rebuildFrom)
            chunks.forEach { chunk ->
                db.insertOrThrow(
                    "text_chunks",
                    null,
                    ContentValues().apply {
                        put("book_id", content.bookId)
                        put("chapter_index", content.chapterIndex)
                        put("chapter_title", content.chapterTitle)
                        put("start_pos", chunk.start)
                        put("end_pos", chunk.end)
                        put("text", chunk.text)
                        put("search_terms", ReadingTextIndexSupport.buildSearchTerms(chunk.text))
                    },
                )
            }
            db.insertWithOnConflict(
                "chapters",
                null,
                ContentValues().apply {
                    put("book_id", content.bookId)
                    put("chapter_index", content.chapterIndex)
                    put("chapter_title", content.chapterTitle)
                    put("content_hash", contentHash)
                    put("indexed_until", content.readableUntil)
                    put("is_complete", if (content.isComplete) 1 else 0)
                    put("updated_at", content.capturedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Index the actual durable summary, never the discarded model candidate. */
    @Synchronized
    fun indexPublishedSummary(bookId: String, chapterIndex: Int, sourceId: String, summary: PublishedSummary) {
        val db = writableDatabase
        requireCatalogSource(db, bookId, chapterIndex, sourceId)
        val indexed = getIndexedChapter(db, bookId, chapterIndex) ?: return
        if (summary.sourceHashKind != ReadingCompanionFileStore.CONTENT_HASH_KIND_READABLE ||
            summary.sourceHash != indexed.contentHash || summary.text.isBlank()) {
            db.beginTransaction()
            try {
                deleteKnowledge(db, bookId, chapterIndex)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            return
        }
        val previous = getChapterKnowledge(bookId, chapterIndex)
        if (previous?.contentHash == indexed.contentHash && previous.summary == summary.text) return
        db.beginTransaction()
        try {
            deleteKnowledge(db, bookId, chapterIndex)
            db.insertOrThrow("chapter_knowledge", null, ContentValues().apply {
                put("book_id", bookId); put("chapter_index", chapterIndex)
                put("chapter_title", indexed.title); put("source_end_pos", indexed.indexedUntil)
                put("is_complete", if (indexed.isComplete) 1 else 0)
                put("content_hash", indexed.contentHash); put("summary", summary.text)
                put("structured_json", "{}"); put("keywords", ""); put("updated_at", System.currentTimeMillis())
            })
            db.insertOrThrow("knowledge_fts", null, ContentValues().apply {
                put("book_id", bookId); put("chapter_index", chapterIndex)
                put("chapter_title", indexed.title); put("source_end_pos", indexed.indexedUntil)
                put("kind", "summary"); put("entity_name", ""); put("text", summary.text)
                put("search_terms", ReadingTextIndexSupport.buildSearchTerms(summary.text))
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun storeKnowledge(
        content: ReadableChapterContent,
        knowledge: ChapterKnowledge,
        structuredJson: String,
    ) {
        val db = writableDatabase
        val contentHash = ReadingTextIndexSupport.sha256(content.content)
        val indexed = getIndexedChapter(db, content.bookId, content.chapterIndex)
        require(
            indexed?.contentHash == contentHash &&
                indexed.indexedUntil == content.readableUntil
        ) { "章节正文在生成摘要期间发生变化" }
        db.beginTransaction()
        try {
            deleteKnowledge(db, content.bookId, content.chapterIndex)
            db.insertOrThrow(
                "chapter_knowledge",
                null,
                ContentValues().apply {
                    put("book_id", content.bookId)
                    put("chapter_index", content.chapterIndex)
                    put("chapter_title", content.chapterTitle)
                    put("source_end_pos", content.readableUntil)
                    put("is_complete", if (content.isComplete) 1 else 0)
                    put("content_hash", contentHash)
                    put("summary", knowledge.summary)
                    put("structured_json", structuredJson)
                    put("keywords", knowledge.keywords.joinToString(" "))
                    put("updated_at", System.currentTimeMillis())
                },
            )
            insertKnowledgeFts(
                db = db,
                content = content,
                kind = "summary",
                entityName = "",
                text = buildString {
                    append(knowledge.summary)
                    if (knowledge.events.isNotEmpty()) {
                        append("\n事件：")
                        append(knowledge.events.joinToString("；"))
                    }
                },
                extraTerms = knowledge.keywords,
            )
            knowledge.characters.forEach { character ->
                insertKnowledgeFts(
                    db = db,
                    content = content,
                    kind = "character",
                    entityName = character.name,
                    text = buildString {
                        append(character.name)
                        if (character.aliases.isNotEmpty()) {
                            append("（")
                            append(character.aliases.joinToString("、"))
                            append("）")
                        }
                        if (character.facts.isNotEmpty()) {
                            append("：")
                            append(character.facts.joinToString("；"))
                        }
                    },
                    extraTerms = character.aliases + knowledge.keywords,
                )
            }
            listOf(
                "event" to knowledge.events,
                "location" to knowledge.locations,
                "item" to knowledge.items,
                "relationship" to knowledge.relationshipChanges,
                "foreshadowing" to knowledge.possibleForeshadowing,
            ).forEach { (kind, values) ->
                values.forEach { value ->
                    insertKnowledgeFts(
                        db = db,
                        content = content,
                        kind = kind,
                        entityName = "",
                        text = value,
                        extraTerms = knowledge.keywords,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun getChapterKnowledge(bookId: String, chapterIndex: Int): StoredChapterKnowledge? {
        readableDatabase.query(
            "chapter_knowledge",
            arrayOf(
                "book_id",
                "chapter_index",
                "chapter_title",
                "source_end_pos",
                "is_complete",
                "content_hash",
                "summary",
                "structured_json",
                "keywords",
                "updated_at",
            ),
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return StoredChapterKnowledge(
                bookId = cursor.getString(0),
                chapterIndex = cursor.getInt(1),
                chapterTitle = cursor.getString(2),
                sourceEndPosition = cursor.getInt(3),
                isComplete = cursor.getInt(4) == 1,
                contentHash = cursor.getString(5),
                summary = cursor.getString(6),
                structuredJson = cursor.getString(7),
                keywords = cursor.getString(8),
                updatedAt = cursor.getLong(9),
            )
        }
    }

    @Synchronized
    fun getRecentKnowledge(
        bookId: String,
        throughChapterIndex: Int,
        limit: Int,
    ): List<StoredChapterKnowledge> {
        val result = mutableListOf<StoredChapterKnowledge>()
        readableDatabase.query(
            "chapter_knowledge",
            arrayOf(
                "book_id",
                "chapter_index",
                "chapter_title",
                "source_end_pos",
                "is_complete",
                "content_hash",
                "summary",
                "structured_json",
                "keywords",
                "updated_at",
            ),
            "book_id = ? AND chapter_index <= ?",
            arrayOf(bookId, throughChapterIndex.toString()),
            null,
            null,
            "chapter_index DESC",
            limit.coerceIn(1, 20).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredChapterKnowledge(
                    bookId = cursor.getString(0),
                    chapterIndex = cursor.getInt(1),
                    chapterTitle = cursor.getString(2),
                    sourceEndPosition = cursor.getInt(3),
                    isComplete = cursor.getInt(4) == 1,
                    contentHash = cursor.getString(5),
                    summary = cursor.getString(6),
                    structuredJson = cursor.getString(7),
                    keywords = cursor.getString(8),
                    updatedAt = cursor.getLong(9),
                )
            }
        }
        return result
    }

    @Synchronized
    fun missingKnowledgeChapterIndices(
        bookId: String,
        throughChapterIndexExclusive: Int,
        limit: Int,
    ): List<Int> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<Int>()
        readableDatabase.rawQuery(
            """
            SELECT c.chapter_index
            FROM chapters c
            LEFT JOIN chapter_knowledge k
              ON k.book_id = c.book_id AND k.chapter_index = c.chapter_index
            WHERE c.book_id = ? AND c.is_complete = 1
              AND c.chapter_index < ? AND k.chapter_index IS NULL
            ORDER BY c.chapter_index DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                bookId,
                throughChapterIndexExclusive.toString(),
                limit.coerceIn(1, 50).toString(),
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getInt(0)
        }
        return result
    }

    @Synchronized
    fun countMissingKnowledge(bookId: String, throughChapterIndexExclusive: Int): Int {
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*)
            FROM chapters c
            LEFT JOIN chapter_knowledge k
              ON k.book_id = c.book_id AND k.chapter_index = c.chapter_index
            WHERE c.book_id = ? AND c.is_complete = 1
              AND c.chapter_index < ? AND k.chapter_index IS NULL
            """.trimIndent(),
            arrayOf(bookId, throughChapterIndexExclusive.toString()),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    @Synchronized
    fun searchText(
        bookId: String,
        terms: List<String>,
        limit: Int = 40,
    ): List<ReadingSearchHit> {
        val expression = ReadingTextIndexSupport.buildFtsExpression(terms)
        if (expression.isBlank()) return emptyList()
        val hits = mutableListOf<ReadingSearchHit>()
        readableDatabase.rawQuery(
            """
            SELECT rowid, book_id, chapter_index, chapter_title, start_pos, end_pos, text
            FROM text_chunks
            WHERE text_chunks MATCH ? AND book_id = ?
            LIMIT ?
            """.trimIndent(),
            arrayOf(expression, bookId, limit.coerceIn(1, 100).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val text = cursor.getString(6)
                val title = cursor.getString(3)
                hits += ReadingSearchHit(
                    id = cursor.getLong(0) * 2,
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    chapterTitle = title,
                    startPosition = cursor.getInt(4),
                    endPosition = cursor.getInt(5),
                    text = text,
                    score = ReadingTextIndexSupport.score(text, title, terms),
                )
            }
        }
        return sortHits(hits)
    }

    @Synchronized
    fun searchKnowledge(
        bookId: String,
        terms: List<String>,
        summaryOnly: Boolean,
        limit: Int = 30,
    ): List<ReadingSearchHit> {
        val expression = ReadingTextIndexSupport.buildFtsExpression(terms)
        if (expression.isBlank()) return emptyList()
        val comparison = if (summaryOnly) "kind = 'summary'" else "kind != 'summary'"
        val hits = mutableListOf<ReadingSearchHit>()
        readableDatabase.rawQuery(
            """
            SELECT rowid, book_id, chapter_index, chapter_title, source_end_pos,
                   kind, entity_name, text
            FROM knowledge_fts
            WHERE knowledge_fts MATCH ? AND book_id = ? AND $comparison
            LIMIT ?
            """.trimIndent(),
            arrayOf(expression, bookId, limit.coerceIn(1, 100).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val text = cursor.getString(7)
                val title = cursor.getString(3)
                val kind = cursor.getString(5)
                hits += ReadingSearchHit(
                    id = cursor.getLong(0) * 2 + 1,
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    chapterTitle = title,
                    startPosition = 0,
                    endPosition = cursor.getInt(4),
                    text = text,
                    score = ReadingTextIndexSupport.score(text, title, terms) +
                        if (kind == "character") 16 else 8,
                    source = if (kind == "summary") "chapter_summary" else "structured_$kind",
                    entityName = cursor.getString(6).takeIf(String::isNotBlank),
                )
            }
        }
        return sortHits(hits)
    }

    @Synchronized
    fun getCharacterEvidence(
        bookId: String,
        name: String,
        limit: Int,
    ): List<ReadingSearchHit> {
        val terms = ReadingTextIndexSupport.extractQueryTerms(name)
        val ftsHits = searchKnowledge(bookId, terms, summaryOnly = false, limit = limit * 3)
            .filter { it.source == "structured_character" }
        val identityMatches = ftsHits.filter { it.matchesCharacterIdentity(name) }
        if (identityMatches.isNotEmpty()) return identityMatches.take(limit)
        val hits = mutableListOf<ReadingSearchHit>()
        readableDatabase.rawQuery(
            """
            SELECT rowid, book_id, chapter_index, chapter_title, source_end_pos,
                   entity_name, text
            FROM knowledge_fts
            WHERE book_id = ? AND kind = 'character' AND entity_name LIKE ?
            ORDER BY CAST(chapter_index AS INTEGER) DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(bookId, "%${name.replace("%", "\\%").replace("_", "\\_")}%", limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                hits += ReadingSearchHit(
                    id = cursor.getLong(0) * 2 + 1,
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    chapterTitle = cursor.getString(3),
                    startPosition = 0,
                    endPosition = cursor.getInt(4),
                    text = cursor.getString(6),
                    score = 1,
                    source = "structured_character",
                    entityName = cursor.getString(5),
                )
            }
        }
        return hits
    }

    @Synchronized
    fun addMemory(
        bookId: String,
        chapterIndex: Int,
        type: String,
        content: String,
    ): ReaderMemory {
        val createdAt = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow(
                "reader_memories",
                null,
                ContentValues().apply {
                    put("book_id", bookId)
                    put("chapter_index", chapterIndex)
                    put("memory_type", type)
                    put("content", content)
                    put("created_at", createdAt)
                },
            )
            db.insertOrThrow(
                "reader_memories_fts",
                null,
                ContentValues().apply {
                    put("memory_id", id)
                    put("book_id", bookId)
                    put("chapter_index", chapterIndex)
                    put("memory_type", type)
                    put("content", content)
                    put("search_terms", ReadingTextIndexSupport.buildSearchTerms("$type $content"))
                },
            )
            db.setTransactionSuccessful()
            return ReaderMemory(id, bookId, chapterIndex, type, content, createdAt)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun searchMemories(
        bookId: String,
        terms: List<String>,
        throughChapterIndex: Int,
        limit: Int,
    ): List<ReaderMemory> {
        val expression = ReadingTextIndexSupport.buildFtsExpression(terms)
        if (expression.isBlank()) return emptyList()
        val result = mutableListOf<ReaderMemory>()
        readableDatabase.rawQuery(
            """
            SELECT m.id, m.book_id, m.chapter_index, m.memory_type, m.content, m.created_at
            FROM reader_memories_fts f
            JOIN reader_memories m ON m.id = CAST(f.memory_id AS INTEGER)
            WHERE reader_memories_fts MATCH ? AND f.book_id = ?
              AND CAST(f.chapter_index AS INTEGER) <= ?
            ORDER BY m.created_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                expression,
                bookId,
                throughChapterIndex.toString(),
                limit.coerceIn(1, 50).toString(),
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ReaderMemory(
                    id = cursor.getLong(0),
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    type = cursor.getString(3),
                    content = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                )
            }
        }
        return result
    }

    /**
     * Returns the reader's newest memories for a book without requiring a search expression.
     *
     * Reader memories are authored notes/reactions/predictions, not novel-derived facts. The
     * chapter bound keeps this read surface aligned with the ordinary companion spoiler boundary.
     */
    @Synchronized
    fun getRecentMemories(
        bookId: String,
        throughChapterIndex: Int,
        limit: Int,
    ): List<ReaderMemory> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<ReaderMemory>()
        readableDatabase.query(
            "reader_memories",
            arrayOf("id", "book_id", "chapter_index", "memory_type", "content", "created_at"),
            "book_id = ? AND chapter_index <= ?",
            arrayOf(bookId, throughChapterIndex.toString()),
            null,
            null,
            "created_at DESC, id DESC",
            limit.coerceIn(1, 50).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ReaderMemory(
                    id = cursor.getLong(0),
                    bookId = cursor.getString(1),
                    chapterIndex = cursor.getInt(2),
                    type = cursor.getString(3),
                    content = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                )
            }
        }
        return result
    }

    private fun getIndexedChapter(
        db: SQLiteDatabase,
        bookId: String,
        chapterIndex: Int,
    ): IndexedChapter? {
        db.query(
            "chapters",
            arrayOf("chapter_title", "content_hash", "indexed_until", "is_complete"),
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return IndexedChapter(
                title = cursor.getString(0),
                contentHash = cursor.getString(1),
                indexedUntil = cursor.getInt(2),
                isComplete = cursor.getInt(3) == 1,
            )
        }
    }

    private fun getStoredChunks(
        db: SQLiteDatabase,
        bookId: String,
        chapterIndex: Int,
    ): List<StoredChunk> {
        val chunks = mutableListOf<StoredChunk>()
        db.query(
            "text_chunks",
            arrayOf("start_pos", "end_pos", "text"),
            "book_id = ? AND CAST(chapter_index AS INTEGER) = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            "CAST(start_pos AS INTEGER) ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                chunks += StoredChunk(cursor.getInt(0), cursor.getInt(1), cursor.getString(2))
            }
        }
        return chunks
    }

    private fun getKnowledgeContentHash(
        db: SQLiteDatabase,
        bookId: String,
        chapterIndex: Int,
    ): String? {
        db.query(
            "chapter_knowledge",
            arrayOf("content_hash"),
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun insertKnowledgeFts(
        db: SQLiteDatabase,
        content: ReadableChapterContent,
        kind: String,
        entityName: String,
        text: String,
        extraTerms: List<String>,
    ) {
        if (text.isBlank()) return
        db.insertOrThrow(
            "knowledge_fts",
            null,
            ContentValues().apply {
                put("book_id", content.bookId)
                put("chapter_index", content.chapterIndex)
                put("chapter_title", content.chapterTitle)
                put("source_end_pos", content.readableUntil)
                put("kind", kind)
                put("entity_name", entityName)
                put("text", text)
                put(
                    "search_terms",
                    ReadingTextIndexSupport.buildSearchTerms(
                        "$entityName $text ${extraTerms.joinToString(" ")}"
                    ),
                )
            },
        )
    }

    private fun deleteKnowledge(db: SQLiteDatabase, bookId: String, chapterIndex: Int) {
        db.delete(
            "knowledge_fts",
            "book_id = ? AND CAST(chapter_index AS INTEGER) = ?",
            arrayOf(bookId, chapterIndex.toString()),
        )
        db.delete(
            "chapter_knowledge",
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
        )
    }

    private fun requireCatalogSource(db: SQLiteDatabase, bookId: String, index: Int, sourceId: String) {
        val current = db.rawQuery(
            "SELECT source_id FROM indexed_catalog_sources WHERE book_id = ? AND chapter_index = ?",
            arrayOf(bookId, index.toString()),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        check(current == sourceId) { "章节目录已更新，请重新读取正文" }
    }

    private fun deleteChapter(db: SQLiteDatabase, bookId: String, chapterIndex: Int) {
        db.delete(
            "text_chunks",
            "book_id = ? AND CAST(chapter_index AS INTEGER) = ?",
            arrayOf(bookId, chapterIndex.toString()),
        )
        deleteKnowledge(db, bookId, chapterIndex)
        db.delete(
            "chapters",
            "book_id = ? AND chapter_index = ?",
            arrayOf(bookId, chapterIndex.toString()),
        )
    }

    private fun sortHits(hits: List<ReadingSearchHit>): List<ReadingSearchHit> =
        hits.sortedWith(
            compareByDescending<ReadingSearchHit> { it.score }
                .thenByDescending { it.chapterIndex }
                .thenBy { it.startPosition }
        )

    companion object {
        private const val DATABASE_NAME = "reading_companion.db"
        internal const val DATABASE_VERSION = 16
        private const val SELECTED_BOOK_KEY = "selected_book_id"
        private const val LAST_RESOLVED_BOOK_KEY = "last_resolved_book_id"
        private const val SUMMARY_BATCH_PREFS_KEY_PREFIX = "summary_batch|"
        const val DEFAULT_SUMMARY_BATCH_BUDGET = 20
        const val MAX_SUMMARY_BATCH_BUDGET = 999
        const val AUTO_COMMENT_STATUS_GENERATING = "generating"
        const val AUTO_COMMENT_STATUS_READY = "ready"
        const val AUTO_COMMENT_STATUS_FAILED = "failed"
        const val AUTO_COMMENT_RUN_STATUS_GENERATING = "generating"
        const val AUTO_COMMENT_RUN_STATUS_GENERATED = "generated"
        const val AUTO_COMMENT_RUN_STATUS_FAILED = "failed"
        const val AUTO_COMMENT_RUN_STATUS_CANCELLED = "cancelled"
        const val AUTO_COMMENT_RUN_STATUS_SUPERSEDED = "superseded"
        const val AUTO_COMMENT_RUN_STATUS_INTERRUPTED = "interrupted"
        const val AUTO_COMMENT_RUN_STATUS_NO_VALID_COMMENTS = "no_valid_comments"
        private const val AUTO_COMMENT_RUN_RETENTION = 50
        private const val PREFETCH_PREFERENCES_NAME = "reading_companion_prefs"
        private const val PREFETCH_AHEAD_CHAPTERS_KEY = "prefetch_ahead_chapters"
    }
}

private fun JSONObject.optNullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else getInt(name)
