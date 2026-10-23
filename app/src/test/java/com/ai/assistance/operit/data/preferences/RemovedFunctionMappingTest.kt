package com.ai.assistance.operit.data.preferences

import com.ai.assistance.operit.data.model.FunctionType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RemovedFunctionMappingTest {
    @Test
    fun removedGrepBindingDoesNotResetModernModelSelections() {
        val raw = """{
            "CHAT":{"configId":"chat-config","modelIndex":2,"modelName":"chat-model","thinkingQualityLevel":4},
            "GREP":{"configId":"search-config"},
            "PERMISSION_REVIEWER":{"configId":"review-config","modelIndex":1}
        }"""
        val mapping = Json.decodeFromString<Map<String, FunctionConfigMapping>>(raw)
            .knownFunctionMappings()

        assertEquals(setOf(FunctionType.CHAT, FunctionType.PERMISSION_REVIEWER), mapping.keys)
        assertEquals(FunctionConfigMapping("chat-config", 2, 4, "chat-model"), mapping[FunctionType.CHAT])
        assertEquals(FunctionConfigMapping("review-config", 1), mapping[FunctionType.PERMISSION_REVIEWER])
    }

    @Test
    fun removedGrepBindingDoesNotResetLegacyConfigIds() {
        val raw = """{"CHAT":"chat-config","GREP":"search-config","SUMMARY":"summary-config"}"""
        val mapping = Json.decodeFromString<Map<String, String>>(raw).knownFunctionMappings()

        assertEquals(
            mapOf(FunctionType.CHAT to "chat-config", FunctionType.SUMMARY to "summary-config"),
            mapping,
        )
    }
}
