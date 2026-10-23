package com.ai.assistance.operit.core.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Serializes input acceptance with the model loop's decision to finish the turn. */
class TurnInputInbox {
    val turnId: String = java.util.UUID.randomUUID().toString()
    private val lock = Any()
    private var open = true
    private val revision = MutableStateFlow(0L)
    internal val changes: kotlinx.coroutines.flow.StateFlow<Long> = revision
    data class Input(
        val text: String,
        val consumed: () -> Unit = {},
        val returned: () -> Unit = {},
        val agentPath: String? = null,
        val startsAgentTurn: Boolean = false,
    )
    private val pending = ArrayDeque<Input>()
    private var delivering: List<Input> = emptyList()
    enum class PendingInputKind { USER, AGENT }
    fun pendingInputKind(): PendingInputKind? = synchronized(lock) {
        when {
            pending.any { it.agentPath == null } -> PendingInputKind.USER
            pending.isNotEmpty() -> PendingInputKind.AGENT
            else -> null
        }
    }
    fun hasPending(): Boolean = synchronized(lock) { pending.isNotEmpty() }
    fun hasPendingUserInput(): Boolean = synchronized(lock) { pending.any { it.agentPath == null } }

    /** StateFlow replays the current version, including input offered before subscription. */
    suspend fun awaitInput(userOnly: Boolean = false) {
        revision.first { if (userOnly) hasPendingUserInput() else hasPending() }
    }

    fun offer(input: Input): Boolean = synchronized(lock) {
        if (!open || input.text.isBlank()) return@synchronized false
        pending.addLast(input)
        revision.value += 1
        true
    }

    fun drain(): List<Input> = synchronized(lock) {
        check(delivering.isEmpty())
        pending.toList().also { pending.clear(); delivering = it }
    }

    fun acknowledge() {
        val inputs = synchronized(lock) { delivering.also { delivering = emptyList() } }
        inputs.forEach { it.consumed() }
    }

    fun seal() = synchronized(lock) {
        open = false
    }

    fun finishIfEmpty(): Boolean = synchronized(lock) {
        if (pending.isNotEmpty()) return@synchronized false
        open = false
        true
    }

    fun close(): List<Input> = synchronized(lock) {
        open = false
        (delivering + pending).also { pending.clear(); delivering = emptyList() }
    }
}
