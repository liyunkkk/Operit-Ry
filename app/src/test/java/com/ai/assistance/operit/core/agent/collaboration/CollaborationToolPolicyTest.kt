package com.ai.assistance.operit.core.agent.collaboration

import org.junit.Assert.*
import org.junit.Test

class CollaborationToolPolicyTest {
    @Test fun userChatsOnlyExposeV2() {
        val tools = CollaborationToolPolicy.visibilityForCaller("existing-chat", false, false)
        assertTrue(CollaborationTools.names.all { tools[it] == true })
        assertEquals(false, tools["task"])
    }

    @Test fun internalTasksAndMissingCallersCannotDelegate() {
        for ((chatId, subTask) in listOf("review" to true, "reading" to true, null to false, "" to false)) {
            assertTrue(CollaborationToolPolicy.visibilityForCaller(chatId, subTask, false).values.none { it })
        }
    }

    @Test fun v2ChildrenRetainCollaborationButNeverLegacyTask() {
        val tools = CollaborationToolPolicy.visibilityForCaller("child", true, true)
        assertTrue(CollaborationTools.names.all { tools[it] == true })
        assertEquals(false, tools["task"])
    }
}
