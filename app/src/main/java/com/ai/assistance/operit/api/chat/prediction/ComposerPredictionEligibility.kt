package com.ai.assistance.operit.api.chat.prediction

import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.InputProcessingState

/** Applied at the persisted logical-turn boundary, not at provider stream EOF. */
internal fun isComposerPredictionCompletion(
    state: InputProcessingState?,
    options: ChatTurnOptions,
    groupOrchestration: Boolean,
): Boolean = state is InputProcessingState.Completed && !groupOrchestration &&
    options.persistTurn && !options.isSubTask && !options.isCollaborationAgent &&
    !options.deliveredByTool && !options.hideUserMessage && options.functionType == FunctionType.CHAT

internal fun regeneratedPredictionTurnId(messageTimestamp: Long, generation: String): String =
    "$messageTimestamp:$generation"

/** Foreground key rotation is bookkeeping, not a model/configuration change. */
internal fun composerPredictionConfigIdentity(config: com.ai.assistance.operit.data.model.ModelConfigData) =
    config.copy(currentKeyIndex = 0)
