package com.ai.assistance.operit.data.preferences

import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigSummary
import org.junit.Assert.*
import org.junit.Test

class UnavailableFunctionModelsTest {
    private val defaultConfig = ModelConfigSummary("default", "Default", "available")

    @Test fun missingSelectionIsReportedAndReselectionClearsIt() {
        val missing = mapOf(FunctionType.MEMORY to FunctionConfigMapping("default", 0, modelName = "deleted"))
        assertEquals(listOf(FunctionType.MEMORY), unavailableFunctionModels(missing, listOf(defaultConfig)) { false })
        assertTrue(unavailableFunctionModels(emptyMap(), listOf(defaultConfig)) { false }.isEmpty())
    }

    @Test fun defaultRiskScorerFollowsReviewerInsteadOfMissingDefaultConfig() {
        val reviewer = ModelConfigSummary("reviewer", "Reviewer", "available")
        val mapping = mapOf(FunctionType.PERMISSION_REVIEWER to FunctionConfigMapping("reviewer"))
        val unavailable = unavailableFunctionModels(mapping, listOf(reviewer)) { false }
        assertFalse(FunctionType.PERMISSION_RISK_SCORER in unavailable)
        assertFalse(FunctionType.PERMISSION_REVIEWER in unavailable)
        assertTrue(FunctionType.CHAT in unavailable)
        assertFalse(FunctionType.UI_CONTROLLER in unavailable)
    }

    @Test fun unnamedPluginIsAvailableButDeletedNamedPluginSelectionIsNot() {
        val plugin = defaultConfig.copy(modelName = "", apiProviderTypeId = "plugin")
        assertTrue(unavailableFunctionModels(emptyMap(), listOf(plugin)) { it == "plugin" }.isEmpty())
        val mapping = mapOf(FunctionType.CHAT to FunctionConfigMapping(modelName = "removed"))
        assertEquals(listOf(FunctionType.CHAT), unavailableFunctionModels(mapping, listOf(plugin)) { true })
        assertTrue(FunctionType.CHAT in unavailableFunctionModels(emptyMap(), listOf(plugin)) { false })
    }
}
