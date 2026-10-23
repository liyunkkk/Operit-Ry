package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.api.chat.prediction.composerPredictionConfigIdentity
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.CharacterCardChatModelBindingMode
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.forSelectedModel
import com.ai.assistance.operit.data.preferences.FunctionConfigMapping
import org.junit.Assert.*
import org.junit.Test

class ComposerPredictionModelTargetTest {
    @Test fun lockedCharacterCardUsesItsOwnConfigurationAndSelectedModel() {
        val global = FunctionConfigMapping(configId = "global", modelIndex = 0)
        val card = CharacterCard(
            id = "card", name = "Card", chatModelBindingMode = CharacterCardChatModelBindingMode.FIXED_CONFIG,
            chatModelConfigId = "card-model", chatModelIndex = 1,
        )
        val target = resolveEffectiveChatConfigTarget(card, global)
        val effective = ModelConfigData(id = "card-model", name = "Card model", modelName = "first,second")
        val snapshot = effective.forSelectedModel(1)
        assertTrue(target.isResolved)
        assertEquals(snapshot.id, target.configId)
        assertEquals(1, target.modelIndex)
        assertEquals(composerPredictionConfigIdentity(snapshot), composerPredictionConfigIdentity(effective.forSelectedModel(target.modelIndex)))
        assertEquals(target, resolveEffectiveChatConfigTarget(card, global.copy(configId = "unrelated", modelIndex = 4)))
        assertNotEquals(target, resolveEffectiveChatConfigTarget(card.copy(chatModelIndex = 0), global))
    }

    @Test fun followingGlobalTracksTheSelectedIndexAndEmptyLocksFallBack() {
        val card = CharacterCard(id = "card", name = "Card")
        val global = FunctionConfigMapping(configId = "global", modelIndex = 2)
        val target = resolveEffectiveChatConfigTarget(card, global)
        assertEquals("global", target.configId)
        assertEquals(2, target.modelIndex)
        assertNotEquals(target, resolveEffectiveChatConfigTarget(card, global.copy(modelIndex = 1)))
        assertEquals(target, resolveEffectiveChatConfigTarget(card.copy(
            chatModelBindingMode = CharacterCardChatModelBindingMode.FIXED_CONFIG,
            chatModelConfigId = " ", chatModelIndex = 0,
        ), global))
    }
}
