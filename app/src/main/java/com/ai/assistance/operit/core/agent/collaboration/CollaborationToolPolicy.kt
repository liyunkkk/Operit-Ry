package com.ai.assistance.operit.core.agent.collaboration

import android.content.Context

object CollaborationToolPolicy {
    fun visibility(context: Context, chatId: String?, isSubTask: Boolean): Map<String, Boolean> {
        val v2Child = CollaborationCoordinator.getInstance(context).isAgent(chatId)
        return visibilityForCaller(chatId, isSubTask, v2Child)
    }

    internal fun visibilityForCaller(chatId: String?, isSubTask: Boolean, v2Child: Boolean): Map<String, Boolean> {
        // User chats always use v2, including chats with obsolete saved v1 preferences.
        // Internal v1 tasks invoke the coordinator directly and cannot recursively delegate.
        val enabled = v2Child || (!isSubTask && !chatId.isNullOrBlank())
        return CollaborationTools.names.associateWith { enabled } +
            (CollaborationTools.LEGACY_TASK_NAME to false)
    }
}
