package com.ai.assistance.operit.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.min

/** User choices belong to a conversation; model capacity remains a model property. */
@Serializable
data class ChatContextPolicy(
    val windowK: Float = ModelConfigDefaults.DEFAULT_CONTEXT_LENGTH,
    val maxMode: Boolean = false,
    val autoCompact: Boolean = true,
    val compactAtK: Float? = null,
    val compactEveryUserTurns: Int? = null,
) {
    fun validate(): ChatContextPolicy {
        require(windowK.isFinite() && windowK > 0f && windowK <= 1_000_000f)
        require(compactAtK == null || (compactAtK.isFinite() && compactAtK > 0f &&
            compactAtK <= 1_000_000f))
        require(compactEveryUserTurns == null || compactEveryUserTurns > 0)
        return this
    }

    fun encode(): String = json.encodeToString(serializer(), validate())

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(value: String): ChatContextPolicy =
            json.decodeFromString(serializer(), value).validate()

        fun fromLegacy(config: ModelConfigData): ChatContextPolicy {
            val effective = if (config.enableMaxContextMode) config.maxContextLength else config.contextLength
            return ChatContextPolicy(
                windowK = config.contextLength,
                maxMode = config.enableMaxContextMode,
                autoCompact = config.enableSummary,
                compactAtK = if (config.summaryTokenThreshold == ModelConfigDefaults.DEFAULT_SUMMARY_TOKEN_THRESHOLD)
                    null else (effective * config.summaryTokenThreshold).coerceAtLeast(Float.MIN_VALUE),
                compactEveryUserTurns = config.summaryMessageCountThreshold.takeIf {
                    config.enableSummaryByMessageCount
                },
            ).validate()
        }
    }
}

data class ResolvedChatContext(
    val policy: ChatContextPolicy,
    val capacityK: Float,
    val windowK: Float,
    val compactAtK: Float,
) {
    val compactRatio: Float get() = compactAtK / windowK
}

fun resolveChatContext(policy: ChatContextPolicy, config: ModelConfigData): ResolvedChatContext {
    policy.validate()
    val capacity = config.maxContextLength
    require(capacity.isFinite() && capacity > 0f) { "Invalid model context capacity" }
    val window = if (policy.maxMode) capacity else min(policy.windowK, capacity)
    // Keep room for the next reply; a configured output limit may require earlier compaction.
    val outputReserve = if (config.maxTokensEnabled && config.maxTokens > 0)
        config.maxTokens / 1024f else window * 0.1f
    val automatic = min(window * 0.9f, (window - outputReserve).coerceAtLeast(window * 0.1f))
    return ResolvedChatContext(policy, capacity, window,
        min(policy.compactAtK ?: automatic, automatic))
}
