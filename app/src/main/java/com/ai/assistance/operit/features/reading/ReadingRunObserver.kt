package com.ai.assistance.operit.features.reading

import android.content.Context
import com.ai.assistance.operit.core.agent.AgentRunObserver
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.repository.SubagentRunRepository

internal class ReadingRunObserver(
    private val context: Context,
    private val session: ReadingCompanionRunSession,
    private val childChatId: String,
    private val onExecution: (com.ai.assistance.operit.core.agent.AgentModelIdentity) -> Unit = {},
) : AgentRunObserver {
    override val capabilityTools = ReadingCompanionSubagentTools.CAPABILITY_BOUND_NAMES
    override suspend fun beforeToolBatch(tools: List<AITool>) {
        checkActive()
        tools.forEach { tool ->
            if (session.loopGuard.recordCall(tool.name, normalizeReadingCompanionToolCall(tool)) == ReadingCompanionCallVerdict.LOOP_DETECTED) {
                session.stop("loop_detected")
                throw ReadingCompanionLoopException(session.runId, "loop_detected", "Repeated tool call: ${tool.name}")
            }
        }
    }
    private fun checkActive() {
        if (!session.summaryOnly && !session.backend.heartbeatClaimIfOwned(session.bookId, session.chapterIndex, session.runId)) {
            session.stop("claim_lost")
        }
        session.stoppedReason?.let { throw ReadingCompanionLoopException(session.runId, it, "Reading task stopped: $it") }
    }
    override fun onModelRequest() {
        checkActive()
        session.backend.incrementRunModelRound(session.runId)
        kotlinx.coroutines.runBlocking {
            runCatching { SubagentRunRepository.getInstance(context).incrementModelRoundCountByChildChatId(childChatId) }
        }
    }
    override fun onModelRequest(identity: com.ai.assistance.operit.core.agent.AgentModelIdentity) {
        checkActive()
        onExecution(identity)
        onModelRequest()
    }
}
