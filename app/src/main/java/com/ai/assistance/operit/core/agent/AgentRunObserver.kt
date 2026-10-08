package com.ai.assistance.operit.core.agent

import com.ai.assistance.operit.data.model.AITool
import java.util.concurrent.ConcurrentHashMap

data class AgentModelIdentity(
    val configId: String,
    val configName: String,
    val modelIndex: Int,
    val provider: String,
    val model: String,
)

/** Narrow opt-in lifecycle for internal agents. The shared engine knows no feature databases. */
interface AgentRunObserver {
    val capabilityTools: Set<String>
    suspend fun beforeToolBatch(tools: List<AITool>)
    fun onModelRequest()
    fun onModelRequest(identity: AgentModelIdentity) = onModelRequest()
}
object AgentRunObservers {
    private val observers = ConcurrentHashMap<String, AgentRunObserver>()
    fun register(chatId: String, observer: AgentRunObserver) { observers[chatId] = observer }
    fun forChat(chatId: String?): AgentRunObserver? = chatId?.let(observers::get)
    /** True when an observer already accounts for this chat's rounds, so callers must not double count. */
    fun hasObserver(chatId: String?): Boolean = chatId != null && observers.containsKey(chatId)
    fun unregister(chatId: String) { observers.remove(chatId) }
    fun isCapabilityTool(name: String): Boolean = observers.values.any { name in it.capabilityTools }
}
