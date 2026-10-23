package com.ai.assistance.operit.ui.permissions

import android.content.Context

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ripgrep.NativeRipgrep
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

@Serializable
enum class PermissionReviewStatus {
    IN_PROGRESS,
    APPROVED,
    DENIED,
    TIMED_OUT,
    ABORTED,
    FAILED,
}

@Serializable
enum class PermissionReviewExactOverrideState {
    PENDING,
    IN_REVIEW,
    CONSUMED,
    EXPIRED,
}

internal fun PermissionReviewEvent.effectiveExactOverrideState(
    nowMs: Long = System.currentTimeMillis(),
): PermissionReviewExactOverrideState? =
    if (exactOverrideState == PermissionReviewExactOverrideState.PENDING &&
        exactOverrideExpiresAt?.let { expiresAt -> nowMs >= expiresAt } == true
    ) {
        PermissionReviewExactOverrideState.EXPIRED
    } else {
        exactOverrideState
    }

/**
 * The text the reviewer's own message for [reviewId] carries.
 *
 * A reviewer runs its reviews in a chat of its own, one exchange per review, and the message that asks
 * for a review states the review it answers under `review_id=`. That makes this string the way back
 * from a review record to the exchange that judged it, for a reader who wants to see that one review
 * inside a conversation several reviews share: no other message of that transcript carries it.
 */
internal fun permissionReviewJumpMarker(reviewId: String): String = "review_id=$reviewId"

@Serializable
data class PermissionReviewAction(
    val targetId: String,
    val kind: String,
    val toolName: String,
    val summary: String,
    val command: String? = null,
    val cwd: String? = null,
    val paths: List<String> = emptyList(),
    val networkTarget: String? = null,
    val justification: String? = null,
    val parameters: List<ToolParameter> = emptyList(),
    val exactFingerprint: String = "",
) {
    fun fingerprint(): String {
        if (exactFingerprint.isNotBlank()) return exactFingerprint
        val canonical =
            Json.encodeToString(
                copy(
                    targetId = "",
                    summary = "",
                    parameters = parameters.sortedWith(compareBy(ToolParameter::name, ToolParameter::value)),
                )
            )
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private val PATH_PARAMETER_MARKERS =
            listOf(
                "path",
                "file",
                "directory",
                "folder",
                "cwd",
                "workdir",
                "source",
                "destination",
            )
        private val NETWORK_PARAMETER_MARKERS = listOf("url", "host", "endpoint")

        fun fromTool(
            tool: AITool,
            operationDescription: String,
            reviewContext: ToolPermissionReviewContext,
            targetId: String,
        ): PermissionReviewAction {
            val values = tool.parameters.associate { parameter -> parameter.name to parameter.value }
            val command =
                (values["command"]
                        ?: values["input"]?.takeIf {
                            tool.name.contains("terminal", ignoreCase = true)
                        })
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            val cwd =
                listOf("cwd", "workdir", "working_directory")
                    .firstNotNullOfOrNull { key -> values[key]?.trim()?.takeIf(String::isNotEmpty) }
                    ?: reviewContext.workspacePath
            val paths =
                tool.parameters
                    .filter { parameter ->
                        val name = parameter.name.lowercase()
                        PATH_PARAMETER_MARKERS.any(name::contains)
                    }
                    .map(ToolParameter::value)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .take(32)
            val networkTarget =
                tool.parameters
                    .firstOrNull { parameter ->
                        val name = parameter.name.lowercase()
                        NETWORK_PARAMETER_MARKERS.any(name::contains)
                    }
                    ?.value
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            val justification =
                listOf("justification", "reason", "description")
                    .firstNotNullOfOrNull { key -> values[key]?.trim()?.takeIf(String::isNotEmpty) }
            val kind =
                when {
                    command != null || tool.name.contains("shell", ignoreCase = true) ||
                        tool.name.contains("terminal", ignoreCase = true) -> "command"
                    tool.name.contains("mcp", ignoreCase = true) -> "mcp"
                    tool.name.contains("network", ignoreCase = true) ||
                        tool.name.contains("web", ignoreCase = true) ||
                        tool.name.contains("download", ignoreCase = true) ||
                        networkTarget != null -> "network"
                    tool.name.contains("patch", ignoreCase = true) -> "patch"
                    tool.name.contains("file", ignoreCase = true) || paths.isNotEmpty() -> "filesystem"
                    tool.name.contains("permission", ignoreCase = true) -> "permission_request"
                    else -> "tool"
                }
            val summarySource =
                command
                    ?: paths.firstOrNull()?.let { path -> "${tool.name}: $path" }
                    ?: networkTarget?.let { target -> "${tool.name}: $target" }
                    ?: operationDescription.ifBlank { tool.name }
            return PermissionReviewAction(
                targetId = targetId,
                kind = kind,
                toolName = tool.name,
                summary = summarize(summarySource),
                command = command,
                cwd = cwd,
                paths = paths,
                networkTarget = networkTarget,
                justification = justification,
                parameters = tool.parameters.map { it.copy(value = truncateMiddle(it.value, 16_000)) },
                exactFingerprint =
                    MessageDigest.getInstance("SHA-256")
                        .digest(
                            Json.encodeToString(
                                    tool.copy(
                                            parameters =
                                                tool.parameters.sortedWith(
                                                    compareBy(ToolParameter::name, ToolParameter::value)
                                                ),
                                            description = "",
                                        ) to
                                        listOf(reviewContext.workspacePath, reviewContext.workspaceEnv)
                                )
                                .toByteArray()
                        )
                        .joinToString("") { byte -> "%02x".format(byte) },
            )
        }

        private fun summarize(value: String): String =
            value.trim().replace(Regex("\\s+"), " ").let { normalized ->
                if (normalized.length <= 180) normalized else normalized.take(177) + "..."
            }

        private fun truncateMiddle(value: String, maxChars: Int): String {
            if (value.length <= maxChars) return value
            val marker = "<truncated omitted_chars=\"${value.length - maxChars}\" />"
            val remaining = (maxChars - marker.length).coerceAtLeast(0)
            val prefix = remaining / 2
            return value.take(prefix) + marker + value.takeLast(remaining - prefix)
        }
    }
}

@Serializable
data class PermissionReviewEvent(
    val id: String,
    val parentChatId: String,
    val timingScopeId: String?,
    val invocationIndex: Int,
    val batchPosition: Int,
    val batchSize: Int,
    val action: PermissionReviewAction,
    val actionFingerprint: String,
    val status: PermissionReviewStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    val riskLevel: PermissionReviewRiskLevel? = null,
    val userAuthorization: PermissionReviewAuthorization? = null,
    val rationale: String? = null,
    val failureKind: PermissionReviewFailureKind? = null,
    val attemptCount: Int = 0,
    val reviewerTaskId: String? = null,
    val exactOverrideRecorded: Boolean = false,
    val exactOverrideState: PermissionReviewExactOverrideState? = null,
    val exactOverrideExpiresAt: Long? = null,
    val exactOverrideApplied: Boolean = false,
    val resolutionSource: String? = null,
    /**
     * The authorization the reviewer decided under: the policy version it read, the hash of the
     * retained instructions it was given, and the workspace block its prompt carried. A decision is
     * only shown to the classifier while all three still match, which is this codebase's equivalent
     * of the reference implementation filtering prior reviews by authorization version.
     */
    val policyVersion: String? = null,
    val retainedInstructionsHash: String? = null,
    val workspaceKey: String? = null,
)

object PermissionReviewEventRepository {
    private const val TAG = "PermissionReviewEvents"
    private const val MAX_EVENTS = 200
    private const val PREFERENCES_NAME = "permission_review_events"
    private const val EVENTS_KEY = "events_v1"
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var applicationContext: Context? = null
    @Volatile private var storedEventsLoaded = false
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableStateFlow<List<PermissionReviewEvent>>(emptyList())
    val events: StateFlow<List<PermissionReviewEvent>> = _events.asStateFlow()

    /**
     * Registers the stored history. Reading and decoding it happen off the calling thread, because
     * the chat screen's first composition used to do that work itself and blocked the main thread
     * for over a second once the list was full.
     */
    fun initialize(context: Context) {
        if (applicationContext != null) return
        synchronized(lock) {
            if (applicationContext != null) return
            applicationContext = context.applicationContext
        }
        loadScope.launch { ensureStoredEventsLoaded() }
    }

    /**
     * Makes sure the stored events are in memory before a caller reads or rewrites them, so a call
     * that arrives while the background load is still running cannot drop the part of the history
     * it has not seen yet. A caller that arrives first therefore performs the load itself, on its
     * own thread: in practice that is the review pipeline right after a cold start, where the work
     * belongs, and not the composition that made the load asynchronous in the first place.
     */
    private fun ensureStoredEventsLoaded() {
        if (storedEventsLoaded) return
        synchronized(lock) {
            if (storedEventsLoaded) return
            loadStoredEventsLocked()
        }
    }

    private fun loadStoredEventsLocked() {
        // Nothing has been registered to load before initialize stores the context, and that is not
        // the same as having loaded: leaving the flag down lets the next caller retry.
        val appContext = applicationContext ?: return
        try {
            val stored =
                runCatching {
                        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                            .getString(EVENTS_KEY, null)
                    }
                    .onFailure { error ->
                        AppLogger.e(TAG, "Failed to read stored permission review events", error)
                    }
                    .getOrNull()
            if (!stored.isNullOrBlank()) {
                val decoded =
                    runCatching { json.decodeFromString<List<PermissionReviewEvent>>(stored) }
                        .onFailure { error ->
                            AppLogger.e(
                                TAG,
                                "Stored permission review events are invalid; preserving raw data",
                                error,
                            )
                        }
                        .getOrNull()
                if (decoded != null) {
                    val storedEvents =
                        decoded
                        .map { event ->
                            val base =
                                if (event.status == PermissionReviewStatus.IN_PROGRESS) {
                                    event.copy(
                                        status = PermissionReviewStatus.ABORTED,
                                        completedAt = System.currentTimeMillis(),
                                        rationale = null,
                                        exactOverrideRecorded = false,
                                        exactOverrideState = null,
                                        exactOverrideExpiresAt = null,
                                        exactOverrideApplied = false,
                                    )
                                } else {
                                    // The override lifecycle is pending state and goes; whether the
                                    // one-shot override was actually applied is a record of what
                                    // happened, and the classifier has to keep reading it as one.
                                    event.copy(
                                        exactOverrideRecorded = false,
                                        exactOverrideState = null,
                                        exactOverrideExpiresAt = null,
                                    )
                                }
                            // Older builds wrote a single neutral value for both outcomes.
                            // Re-derive allow/deny from the enforced status so the resolution
                            // label stays consistent with the lifecycle.
                            if (base.resolutionSource == "settings_refreshed_during_review") {
                                base.copy(
                                    resolutionSource =
                                        if (base.status == PermissionReviewStatus.APPROVED) {
                                            "settings_refreshed_allow"
                                        } else {
                                            "settings_refreshed_deny"
                                        }
                                )
                            } else {
                                base
                            }
                        }
                        .takeLast(MAX_EVENTS)
                    // Kept for the writers that run before initialize has a context: they add an
                    // event and their rewrite writes nothing, so without this merge the assignment
                    // below would drop that event together with the history it is joining.
                    val pending =
                        _events.value.filterNot { event -> storedEvents.any { it.id == event.id } }
                    _events.value = (storedEvents + pending).takeLast(MAX_EVENTS)
                    // Rewrite immediately so older builds cannot leave sensitive summaries or stale
                    // in-progress/override flags on disk until the next review event.
                    persistLocked()
                    // Reviews recorded before the per-chat archive existed move into it, so they
                    // stay under their tool calls once newer reviews push them out of this list.
                    PermissionReviewArchive.record(appContext, _events.value.map(::archivedCopy))
                }
            }
        } finally {
            // A failed rewrite must not send every later caller through the read again.
            storedEventsLoaded = true
        }
    }

    fun publish(event: PermissionReviewEvent) {
        ensureStoredEventsLoaded()
        synchronized(lock) {
            val retained = _events.value.filterNot { existing -> existing.id == event.id }
            _events.value = (retained + event).takeLast(MAX_EVENTS)
            persistLocked()
        }
        applicationContext?.let { PermissionReviewArchive.record(it, listOf(archivedCopy(event))) }
    }

    fun update(id: String, transform: (PermissionReviewEvent) -> PermissionReviewEvent) {
        ensureStoredEventsLoaded()
        val changed =
            synchronized(lock) {
                var changed: PermissionReviewEvent? = null
                _events.value =
                    _events.value.map { event ->
                        if (event.id == id) transform(event).also { changed = it } else event
                    }
                persistLocked()
                changed
            }
        val context = applicationContext
        if (changed != null && context != null) {
            PermissionReviewArchive.record(context, listOf(archivedCopy(changed)))
        }
    }

    fun findById(id: String): PermissionReviewEvent? {
        ensureStoredEventsLoaded()
        return _events.value.firstOrNull { event -> event.id == id }
    }

    /**
     * The recorded reviews of these chats, oldest first. A caller passes the chat it is judging and
     * the conversation that chat belongs to, because a sub-agent's reviews are recorded under the
     * sub-agent's own chat while the classifier reasons about the root conversation.
     *
     * The stored history is loaded first for the same reason the other readers do it: a caller that
     * arrives before the background load would otherwise read a list that is still being decoded and
     * see the chat's own earlier decisions as absent.
     */
    fun decisionsFor(parentChatIds: Set<String>): List<PermissionReviewEvent> {
        ensureStoredEventsLoaded()
        return _events.value.filter { event -> event.parentChatId in parentChatIds }
    }

    fun findForInvocation(
        parentChatId: String,
        timingScopeId: String?,
        invocationIndex: Int,
    ): PermissionReviewEvent? {
        ensureStoredEventsLoaded()
        return _events.value.lastOrNull { event ->
            event.parentChatId == parentChatId &&
                event.timingScopeId == timingScopeId &&
                event.invocationIndex == invocationIndex
        }
    }

    fun recentDenials(parentChatId: String): kotlinx.coroutines.flow.Flow<List<PermissionReviewEvent>> =
        events.map { allEvents ->
            allEvents
                .asReversed()
                .filter { event ->
                    event.parentChatId == parentChatId && event.status == PermissionReviewStatus.DENIED
                }
                .distinctBy(PermissionReviewEvent::actionFingerprint)
                .take(10)
        }

    suspend fun approveExactActionOnce(reviewId: String): Boolean {
        ensureStoredEventsLoaded()
        val event = _events.value.firstOrNull { it.id == reviewId } ?: return false
        if (event.status != PermissionReviewStatus.DENIED) return false
        // A subagent reviews its actions in its own child chat, but the button lives in the chat the
        // user sees and tells them to retry there. Keying the approval by the root chat makes the
        // retry in that chat find it again; the reviewer resolves the same chat before it reserves.
        val approvalChatId =
            applicationContext
                ?.let { context ->
                    PermissionReviewChatScope.resolveRootChatId(context, event.parentChatId)
                }
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: event.parentChatId
        val expiresAt = PermissionReviewExactOverrideStore.record(
            parentChatId = approvalChatId,
            actionFingerprint = event.actionFingerprint,
            originalReviewId = event.id,
        )
        update(reviewId) {
            current ->
            current.copy(
                exactOverrideRecorded = true,
                exactOverrideState = PermissionReviewExactOverrideState.PENDING,
                exactOverrideExpiresAt = expiresAt,
            )
        }
        return true
    }

    private fun persistLocked() {
        val context = applicationContext ?: return
        val sanitized = _events.value.map(::sanitizedForDisk)
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(EVENTS_KEY, json.encodeToString(sanitized))
            .apply()
    }

    /** What may be written to disk: no command, path, parameter or reviewer text. */
    private fun sanitizedForDisk(event: PermissionReviewEvent): PermissionReviewEvent =
        event.copy(
            action =
                event.action.copy(
                    command = null,
                    cwd = null,
                    paths = emptyList(),
                    networkTarget = null,
                    justification = null,
                    parameters = emptyList(),
                    summary = "${event.action.toolName} (${event.action.kind})",
                ),
            rationale = null,
        )

    /** The archive keeps the outcome only; a one-time override is live state that ends with the turn. */
    private fun archivedCopy(event: PermissionReviewEvent): PermissionReviewEvent =
        sanitizedForDisk(event).copy(
            exactOverrideRecorded = false,
            exactOverrideState = null,
            exactOverrideExpiresAt = null,
        )
}

data class PermissionReviewExactOverride(
    val originalReviewId: String,
    val approvedAt: Long,
)

object PermissionReviewExactOverrideStore {
    private const val TTL_MS = 5 * 60 * 1000L
    private val approvals = ConcurrentHashMap<String, PermissionReviewExactOverride>()
    private val reservations = ConcurrentHashMap<String, Pair<String, PermissionReviewExactOverride>>()

    private fun key(parentChatId: String, actionFingerprint: String): String =
        "$parentChatId:$actionFingerprint"

    fun record(
        parentChatId: String,
        actionFingerprint: String,
        originalReviewId: String,
    ): Long {
        val approvedAt = System.currentTimeMillis()
        approvals[key(parentChatId, actionFingerprint)] =
            PermissionReviewExactOverride(originalReviewId, approvedAt)
        return approvedAt + TTL_MS
    }

    fun reserve(
        parentChatId: String,
        actionFingerprint: String,
        reviewId: String,
    ): PermissionReviewExactOverride? {
        val approvalKey = key(parentChatId, actionFingerprint)
        val approval = approvals.remove(approvalKey) ?: return null
        if (System.currentTimeMillis() - approval.approvedAt > TTL_MS) {
            PermissionReviewEventRepository.update(approval.originalReviewId) { event ->
                event.copy(
                    exactOverrideRecorded = false,
                    exactOverrideState = PermissionReviewExactOverrideState.EXPIRED,
                    exactOverrideExpiresAt = null,
                )
            }
            return null
        }
        reservations[reviewId] = approvalKey to approval
        PermissionReviewEventRepository.update(approval.originalReviewId) { event ->
            event.copy(exactOverrideState = PermissionReviewExactOverrideState.IN_REVIEW)
        }
        return approval
    }

    fun commit(reviewId: String) {
        val (_, approval) = reservations.remove(reviewId) ?: return
        PermissionReviewEventRepository.update(approval.originalReviewId) { event ->
            event.copy(
                exactOverrideRecorded = false,
                exactOverrideState = PermissionReviewExactOverrideState.CONSUMED,
                exactOverrideExpiresAt = null,
            )
        }
    }

    fun release(reviewId: String) {
        val (approvalKey, approval) = reservations.remove(reviewId) ?: return
        if (System.currentTimeMillis() - approval.approvedAt <= TTL_MS) {
            approvals.putIfAbsent(approvalKey, approval)
            PermissionReviewEventRepository.update(approval.originalReviewId) { event ->
                event.copy(exactOverrideState = PermissionReviewExactOverrideState.PENDING)
            }
        } else {
            PermissionReviewEventRepository.update(approval.originalReviewId) { event ->
                event.copy(
                    exactOverrideRecorded = false,
                    exactOverrideState = PermissionReviewExactOverrideState.EXPIRED,
                    exactOverrideExpiresAt = null,
                )
            }
        }
    }
}

data class PermissionReviewCircuitBreakerResult(
    val interruptTurn: Boolean,
    val consecutiveDenials: Int,
    val recentDenials: Int,
)

object PermissionReviewCircuitBreaker {
    private const val MAX_TURNS = 64
    private const val MAX_CONSECUTIVE_DENIALS = 3
    private const val MAX_RECENT_DENIALS = 10
    private const val WINDOW_SIZE = 50

    private data class TurnState(
        var consecutiveDenials: Int = 0,
        val recent: ArrayDeque<Boolean> = ArrayDeque(),
        var interrupted: Boolean = false,
    )

    private val states = object : LinkedHashMap<String, TurnState>(MAX_TURNS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TurnState>?): Boolean =
            size > MAX_TURNS
    }

    @Synchronized
    fun isInterrupted(parentChatId: String, turnScopeId: String?): Boolean =
        states["$parentChatId:${turnScopeId ?: "unknown"}"]?.interrupted == true

    @Synchronized
    fun recordDenial(parentChatId: String, turnScopeId: String?): PermissionReviewCircuitBreakerResult {
        val key = "$parentChatId:${turnScopeId ?: "unknown"}"
        val state = states.getOrPut(key) { TurnState() }
        state.consecutiveDenials += 1
        state.recent.addLast(true)
        while (state.recent.size > WINDOW_SIZE) state.recent.removeFirst()
        val recentDenials = state.recent.count { denied -> denied }
        val interrupt =
            !state.interrupted &&
                (state.consecutiveDenials >= MAX_CONSECUTIVE_DENIALS || recentDenials >= MAX_RECENT_DENIALS)
        if (interrupt) state.interrupted = true
        return PermissionReviewCircuitBreakerResult(
            interruptTurn = interrupt,
            consecutiveDenials = state.consecutiveDenials,
            recentDenials = recentDenials,
        )
    }

    @Synchronized
    fun recordNonDenial(parentChatId: String, turnScopeId: String?) {
        val key = "$parentChatId:${turnScopeId ?: "unknown"}"
        val state = states.getOrPut(key) { TurnState() }
        state.consecutiveDenials = 0
        state.recent.addLast(false)
        while (state.recent.size > WINDOW_SIZE) state.recent.removeFirst()
    }

    /**
     * The turn scope starts over. A regenerated message keeps its turn scope id, so without this
     * the breaker would abort the first reviewed call of the new attempt and a one-time approval
     * could never be used: the user would be stuck with a turn that stops on its own.
     */
    @Synchronized
    fun clearTurn(parentChatId: String, turnScopeId: String?) {
        if (parentChatId.isBlank()) return
        states.remove("$parentChatId:${turnScopeId ?: "unknown"}")
    }
}

data class PermissionReviewInspectionScope(
    val reviewId: String,
    val workspaceRoot: File?,
    val environment: String?,
)

object PermissionReviewInspectionRegistry {
    private const val MAX_TEXT_CHARS = 64 * 1024
    private const val MAX_DIRECTORY_ENTRIES = 80
    private const val MAX_GREP_RESULTS = 100
    private val scopes = ConcurrentHashMap<String, PermissionReviewInspectionScope>()

    fun newReviewId(): String = UUID.randomUUID().toString()

    fun register(
        reviewId: String,
        workspacePath: String?,
        workspaceEnv: String?,
        action: PermissionReviewAction,
    ) {
        // A repo:* workspace is virtual: its paths (commonly "/") have no local Android
        // counterpart, so it must not anchor relative paths or .git discovery to a local
        // directory.
        val localWorkspace =
            if (workspaceEnv?.startsWith("repo:", ignoreCase = true) == true) {
                null
            } else {
                workspacePath?.takeIf(String::isNotBlank)?.let(::File)?.canonicalOrNull()
            }
        scopes[reviewId] =
            PermissionReviewInspectionScope(
                reviewId,
                localWorkspace,
                workspaceEnv,
            )
    }

    fun unregister(reviewId: String) {
        scopes.remove(reviewId)
    }

    fun inspect(
        reviewId: String,
        operation: String,
        requestedPath: String?,
        environment: String? = null,
        startLine: Int? = null,
        endLine: Int? = null,
        pattern: String? = null,
        caseInsensitive: Boolean = false,
    ): String {
        val scope = scopes[reviewId] ?: return "Inspection rejected: review is not active."
        return when (operation.trim().lowercase()) {
            "path_metadata" -> inspectPath(scope, requestedPath, includeText = false, environment)
            "read_text" ->
                inspectPath(scope, requestedPath, includeText = true, environment, startLine, endLine)
            "grep" -> inspectGrep(scope, requestedPath, environment, pattern, caseInsensitive)
            "git_context" -> inspectGitContext(scope)
            else -> "Inspection rejected: unsupported operation '$operation'."
        }
    }

    private fun inspectPath(
        scope: PermissionReviewInspectionScope,
        requestedPath: String?,
        includeText: Boolean,
        environment: String?,
        startLine: Int? = null,
        endLine: Int? = null,
    ): String {
        val raw = requestedPath?.trim()?.takeIf(String::isNotEmpty)
            ?: return "Inspection rejected: path is required."
        val file = resolvePath(raw, scope, environment)?.canonicalOrNull()
            ?: return "Inspection rejected: path could not be resolved."
        val metadata = buildString {
            appendLine("path=${file.path}")
            appendLine("exists=${file.exists()}")
            appendLine("is_file=${file.isFile}")
            appendLine("is_directory=${file.isDirectory}")
            if (file.exists()) appendLine("size_bytes=${file.length()}")
            if (file.isDirectory) {
                val children = file.listFiles().orEmpty().sortedBy(File::getName).take(MAX_DIRECTORY_ENTRIES)
                appendLine("entries_shown=${children.size}")
                children.forEach { child -> appendLine("- ${child.name}${if (child.isDirectory) "/" else ""}") }
            }
        }
        if (!includeText || !file.isFile) return metadata.trimEnd()
        val text =
            if (startLine != null || endLine != null) {
                runCatching {
                    runBlocking(Dispatchers.IO) { readBoundedLines(file, startLine ?: 1, endLine) }
                }.getOrElse { error -> return metadata + "read_error=${error.message}" }
            } else {
                runCatching {
                    runBlocking(Dispatchers.IO) { readBoundedText(file) }
                }.getOrElse { error -> return metadata + "read_error=${error.message}" }
            }
        return metadata + "\ntext_preview:\n" + text
    }

    private fun inspectGrep(
        scope: PermissionReviewInspectionScope,
        requestedPath: String?,
        environment: String?,
        pattern: String?,
        caseInsensitive: Boolean,
    ): String {
        val rawPath = requestedPath?.trim()?.takeIf(String::isNotEmpty)
            ?: return "Inspection rejected: path is required."
        val root = resolvePath(rawPath, scope, environment)
            ?: return "Inspection rejected: path could not be resolved."
        val normalizedPattern = pattern?.trim()?.takeIf(String::isNotEmpty)
            ?: return "Inspection rejected: pattern is required."
        return try {
            val raw =
                runBlocking(Dispatchers.IO) {
                    NativeRipgrep.searchJson(
                        path = root.path,
                        patterns = arrayOf(normalizedPattern),
                        filePattern = "*",
                        caseInsensitive = caseInsensitive,
                        literal = false,
                        contextLines = 3,
                        maxResults = MAX_GREP_RESULTS,
                    )
                }
            val json = JSONObject(raw)
            if (!json.optBoolean("success", false)) {
                return "grep error: " + json.optString("error").ifBlank { "native ripgrep search failed" }
            }
            val blocks = json.optJSONArray("blocks")
            if (blocks == null || blocks.length() == 0) {
                return "grep: no matches."
            }
            // Each native block carries up to 4000 context chars, so the raw result may exceed
            // the review output budget. Trim per-block context to stay within a bounded response.
            val perBlockContextLimit = 800
            val sb = StringBuilder()
            sb.appendLine("grep matches (${blocks.length()}, searched ${json.optInt("filesSearched", 0)} files):")
            for (index in 0 until blocks.length()) {
                val block = blocks.optJSONObject(index) ?: continue
                sb.appendLine("- ${block.optString("filePath")}:${block.optInt("firstMatchLine", 0)}")
                    .appendLine("  ${block.optString("lineContent")}")
                val context = block.optString("matchContext").trim()
                if (context.isNotEmpty()) {
                    val clippedContext =
                        if (context.length > perBlockContextLimit) {
                            context.take(perBlockContextLimit) + "...(context clipped)"
                        } else {
                            context
                        }
                    sb.append("  context:\n")
                    clippedContext.lineSequence().forEach { line -> sb.appendLine("    $line") }
                }
            }
            sb.trimEnd().toString()
        } catch (error: LinkageError) {
            "grep error: native ripgrep is unavailable: ${error.message}"
        } catch (error: Exception) {
            "grep error: ${error.message}"
        }
    }

    private fun inspectGitContext(scope: PermissionReviewInspectionScope): String {
        val workspace = scope.workspaceRoot ?: return "Git inspection unavailable: no local workspace."
        val gitDir = generateSequence(workspace) { it.parentFile }
            .map { candidate -> File(candidate, ".git") }
            .firstOrNull(File::exists)
            ?: return "Git inspection: no .git directory found."
        val head = runCatching { readBoundedText(File(gitDir, "HEAD")).trim() }.getOrNull()
        return buildString {
            appendLine("git_dir=${gitDir.path}")
            appendLine("head=${head ?: "unknown"}")
            append("config_redacted=true")
        }.trimEnd()
    }

    private fun resolvePath(path: String, scope: PermissionReviewInspectionScope, environment: String?): File? {
        val direct = File(path)
        return when {
            direct.isAbsolute -> direct
            // A repo:* workspace is virtual: its paths (commonly "/") have no local Android
            // counterpart, so relative paths cannot be anchored to a local directory.
            scope.environment?.startsWith("repo:", ignoreCase = true) == true -> null
            scope.workspaceRoot != null -> File(scope.workspaceRoot, path)
            // No local workspace to anchor a relative path. Only android environment has a
            // stable local root; virtual environments must pass absolute paths.
            environment.isNullOrBlank() || environment.equals("android", ignoreCase = true) -> File(path)
            else -> null
        }
    }

    private fun File.canonicalOrNull(): File? = runCatching { canonicalFile }.getOrNull()

    private fun readBoundedText(file: File): String =
        file.bufferedReader().use { reader ->
            val buffer = CharArray(MAX_TEXT_CHARS)
            val count = reader.read(buffer, 0, buffer.size).coerceAtLeast(0)
            String(buffer, 0, count)
        }

    private fun readBoundedLines(file: File, startLine: Int, endLine: Int?): String {
        val firstLine = startLine.coerceAtLeast(1)
        val lastLine = endLine?.coerceAtLeast(firstLine)
        val marker = "...(line truncated)"
        val sb = StringBuilder()
        file.bufferedReader().use { reader ->
            var lineNumber = 0
            while (sb.length < MAX_TEXT_CHARS && (lastLine == null || lineNumber < lastLine)) {
                val line = reader.readLine() ?: break
                lineNumber++
                if (lineNumber < firstLine) continue
                val prefix = "$lineNumber: "
                val remaining = MAX_TEXT_CHARS - sb.length
                if (remaining <= prefix.length) {
                    sb.append(prefix.take(remaining))
                    break
                }
                sb.append(prefix)
                val textBudget = remaining - prefix.length
                if (textBudget >= line.length + 1) {
                    sb.append(line).append('\n')
                } else {
                    val fit = (textBudget - marker.length).coerceAtLeast(0)
                    if (fit > 0) sb.append(line.take(fit))
                    if (textBudget - fit >= marker.length) sb.append(marker)
                    break
                }
            }
        }
        return sb.toString().trimEnd()
    }
}
