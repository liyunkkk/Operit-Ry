package com.ai.assistance.operit.api.chat.prediction

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt

/** Request-only copy of the effective, completed turn. Never reconstructed from stored history. */
data class ComposerPredictionSnapshot(
    val chatId: String,
    val sourceTurnId: String,
    val history: List<PromptTurn>,
    val modelConfig: ModelConfigData,
    val modelParameters: List<ModelParameter<*>>,
    val sourceMessageId: String = sourceTurnId,
    /**
     * The exact tool definitions the completed request sent. A provider reuses a prompt prefix only
     * while the tools, the history they shape and the identity stay the same, so the auxiliary
     * request must declare them too. [ToolPrompt] carries no mutable state worth copying.
     */
    val availableTools: List<ToolPrompt> = emptyList(),
    /**
     * The provider conversation identity the completed request asked under. Reusing it is what lets
     * the provider attach the auxiliary request to the prefix the conversation already warmed.
     */
    val providerSessionId: String? = null,
    /** The workspace the completed request reported, when the conversation had one bound. */
    val workspacePath: String? = null,
    /**
     * Whether the completed request ran with thinking enabled.
     *
     * The toggle is not only a top-level request field: some adapters rewrite the message history
     * from it, so a prediction that picked its own value would send a different prefix and miss the
     * cache. It also decides how much output room the bounded prediction needs.
     */
    val enableThinking: Boolean = false,
)

internal fun freezePredictionTools(tools: List<ToolPrompt>?): List<ToolPrompt> =
    java.util.Collections.unmodifiableList(tools?.toList() ?: emptyList())

/** Prompt hooks may supply mutable metadata; auxiliary work must never retain those containers. */
internal fun freezePredictionValue(value: Any?): Any? = when (value) {
    null, is String, is Byte, is Short, is Int, is Long, is Float, is Double, is Boolean, is Enum<*> -> value
    is Map<*, *> -> java.util.Collections.unmodifiableMap(value.entries.associate { (key, item) ->
        require(key is String) { "Unsupported prompt metadata key" }
        key to freezePredictionValue(item)
    })
    is List<*> -> java.util.Collections.unmodifiableList(value.map(::freezePredictionValue))
    is Set<*> -> java.util.Collections.unmodifiableSet(value.map(::freezePredictionValue).toSet())
    else -> throw IllegalArgumentException("Unsupported mutable prompt metadata")
}

internal fun freezePredictionHistory(history: List<PromptTurn>): List<PromptTurn> =
    java.util.Collections.unmodifiableList(history.map { turn ->
        @Suppress("UNCHECKED_CAST")
        turn.copy(metadata = freezePredictionValue(turn.metadata) as Map<String, Any?>)
    })

internal fun freezePredictionParameters(parameters: List<ModelParameter<*>>): List<ModelParameter<*>> =
    java.util.Collections.unmodifiableList(parameters.map { parameter ->
        ModelParameter(
            id = parameter.id, name = parameter.name, apiName = parameter.apiName,
            description = parameter.description,
            defaultValue = freezePredictionValue(parameter.defaultValue),
            currentValue = freezePredictionValue(parameter.currentValue),
            isEnabled = parameter.isEnabled, valueType = parameter.valueType,
            minValue = freezePredictionValue(parameter.minValue), maxValue = freezePredictionValue(parameter.maxValue),
            category = parameter.category, isCustom = parameter.isCustom,
        )
    })

internal fun freezePredictionConfig(config: ModelConfigData): ModelConfigData = config.copy(
    apiKeyPool = java.util.Collections.unmodifiableList(config.apiKeyPool.toList()),
    summarySectionOverrides = java.util.Collections.unmodifiableList(config.summarySectionOverrides.toList()),
    modelMultimodalCapabilities = java.util.Collections.unmodifiableMap(config.modelMultimodalCapabilities.toMap()),
    modelProtocolSettings = java.util.Collections.unmodifiableMap(config.modelProtocolSettings.mapValues { (_, settings) ->
        settings.copy(reasoningEfforts = settings.reasoningEfforts?.let { java.util.Collections.unmodifiableList(it.toList()) })
    }),
)
