package com.ai.assistance.operit.data.model

import org.junit.Assert.*
import org.junit.Test

class ChatContextPolicyTest {
    private val model = ModelConfigData(id = "test", name = "test", maxContextLength = 1000f)

    @Test fun missingCompactionChoiceDefaultsOnButExplicitOffSurvives() {
        assertTrue(ChatContextPolicy.decode("""{"windowK":400}""").autoCompact)
        assertFalse(ChatContextPolicy.decode(ChatContextPolicy(autoCompact = false).encode()).autoCompact)
        assertTrue(ChatContextPolicy.fromLegacy(model).autoCompact)
    }

    @Test fun maxModeDoesNotOverwriteCustomBudget() {
        val policy = ChatContextPolicy(windowK = 300f)
        assertEquals(300f, resolveChatContext(policy, model).windowK, 0f)
        assertEquals(1000f, resolveChatContext(policy.copy(maxMode = true), model).windowK, 0f)
        assertEquals(300f, resolveChatContext(policy.copy(maxMode = true).copy(maxMode = false), model).windowK, 0f)
    }

    @Test fun smallerModelCapsUsageButPreservesChoice() {
        val resolved = resolveChatContext(ChatContextPolicy(windowK = 500f), model.copy(maxContextLength = 128f))
        assertEquals(128f, resolved.windowK, 0f)
        assertEquals(500f, resolved.policy.windowK, 0f)
    }

    @Test fun explicitCompactionCanBeEarlierButNeverBeyondSafeWindow() {
        assertEquals(100f, resolveChatContext(ChatContextPolicy(compactAtK = 100f), model).compactAtK, 0f)
        assertEquals(360f, resolveChatContext(ChatContextPolicy(compactAtK = 900f), model).compactAtK, 0f)
        assertEquals(300f, resolveChatContext(ChatContextPolicy(), model.copy(maxTokensEnabled = true,
            maxTokens = 100 * 1024)).compactAtK, 0f)
    }

    @Test fun legacyThresholdAndMaxArePreservedWhenFreezingAChat() {
        val migrated = ChatContextPolicy.fromLegacy(model.copy(contextLength = 300f,
            enableMaxContextMode = true, summaryTokenThreshold = 0.5f, enableSummary = false))
        assertEquals(300f, migrated.windowK, 0f)
        assertTrue(migrated.maxMode)
        assertFalse(migrated.autoCompact)
        assertEquals(500f, migrated.compactAtK!!, 0f)
        assertEquals(migrated, ChatContextPolicy.decode(migrated.encode()))
    }

    @Test fun archiveRoundTripKeepsConversationPolicy() {
        val policy = ChatContextPolicy(500f, true, compactAtK = 200f).encode()
        val history = ChatHistory(title = "test", messages = emptyList(), contextPolicyJson = policy)
        assertEquals(policy, OperitArchivedChat.fromChatHistory(history, emptyList())
            .toChatHistory(false).contextPolicyJson)
        assertEquals(policy, ChatEntity.fromChatHistory(history).toChatHistory(emptyList()).contextPolicyJson)
    }

    @Test(expected = IllegalArgumentException::class) fun nonFiniteBudgetIsRejected() {
        ChatContextPolicy(windowK = Float.NaN).validate()
    }

    @Test fun fractionalLegacyBudgetStillLoads() {
        val config = model.copy(contextLength = 0.5f, maxContextLength = 0.75f, summaryTokenThreshold = 0.5f)
        val migrated = ChatContextPolicy.fromLegacy(config)
        assertEquals(0.5f, resolveChatContext(migrated, config).windowK, 0f)
        assertEquals(0.25f, migrated.compactAtK!!, 0f)
        assertEquals(migrated, ChatContextPolicy.decode(migrated.encode()))
    }
}
