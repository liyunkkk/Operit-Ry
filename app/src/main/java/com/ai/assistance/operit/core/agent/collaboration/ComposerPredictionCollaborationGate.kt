package com.ai.assistance.operit.core.agent.collaboration

/** Pure snapshot gate, with no caller/root creation or mailbox acknowledgement side effects. */
internal fun composerPredictionBlockedByCollaboration(
    rootChatId: String,
    callerIsAgent: Boolean,
    deleting: Boolean,
    stopping: Boolean,
    jobKeys: Set<String>,
    reservationKeys: Set<String>,
    agents: List<CollaborationAgent>,
): Boolean = callerIsAgent || deleting || stopping ||
    jobKeys.any { it.startsWith("$rootChatId:") } ||
    reservationKeys.any { it.startsWith("$rootChatId:") } ||
    agents.any { it.rootChatId == rootChatId &&
        (it.status == CollaborationStatus.RUNNING || it.messages.isNotEmpty()) }
