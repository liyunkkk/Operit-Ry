package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionModelBindingTest {
    @Test fun removingAnEarlierModelKeepsTheSelectedIdentity() {
        val binding = FunctionConfigMapping("provider", 1).bindModelName("free, paid, other")
        assertEquals(0, binding.resolveModelIndex("paid, other").modelIndex)
        assertEquals("paid", binding.modelName)
    }

    @Test fun removingSelectedModelDoesNotChooseTheNextOrFirstModel() {
        val binding = FunctionConfigMapping("provider", 1).bindModelName("first, selected, last")
        assertEquals(-1, binding.resolveModelIndex("first, last").modelIndex)
        assertEquals(-1, binding.resolveModelIndex(null).modelIndex)
        assertEquals(-1, binding.resolveModelIndex("").modelIndex)
    }

    @Test fun reorderedModelsResolveByName() {
        val binding = FunctionConfigMapping("provider", 0).bindModelName("a, b, c")
        assertEquals(2, binding.resolveModelIndex("c, b, a").modelIndex)
    }

    @Test fun invalidLegacyIndexCannotBecomeTheFirstModel() {
        val binding = FunctionConfigMapping("provider", 8).bindModelName("a, b")
        assertEquals(-1, binding.resolveModelIndex("a, b").modelIndex)
    }

    @Test fun changingThinkingDoesNotReviveDeletedModel() {
        val deleted = FunctionConfigMapping("provider", 0).bindModelName("old, other")
            .resolveModelIndex("other")
        val changed = deleted.copy(thinkingQualityLevel = 4).bindModelName("other")
        assertEquals("old", changed.modelName)
        assertEquals(-1, changed.resolveModelIndex("other").modelIndex)
    }

    @Test fun explicitReselectionBindsTheNewModel() {
        val selected = FunctionConfigMapping("provider", 0).bindModelName("replacement")
        assertEquals(0, selected.resolveModelIndex("replacement").modelIndex)
        assertEquals("replacement", selected.modelName)
    }

    @Test fun unnamedPluginSelectionStaysDistinctFromADeletedNamedModel() {
        val unnamed = FunctionConfigMapping("plugin", 0).bindModelName("")
        assertEquals(null, unnamed.modelName)
        assertEquals(0, unnamed.resolveModelIndex("").modelIndex)
        val named = FunctionConfigMapping("plugin", 0).bindModelName("old")
        assertEquals(-1, named.resolveModelIndex("").modelIndex)
    }
}
