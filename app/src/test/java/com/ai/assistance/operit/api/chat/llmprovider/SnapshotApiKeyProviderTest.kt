package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ApiKeyAvailabilityStatus
import com.ai.assistance.operit.data.model.ApiKeyInfo
import com.ai.assistance.operit.data.model.ModelConfigData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SnapshotApiKeyProviderTest {
    @Test fun rotationIsRequestLocalAndUsesFrozenPool() = runBlocking {
        val pool = mutableListOf(ApiKeyInfo("one", "test-one"), ApiKeyInfo("two", "test-two"))
        val config = ModelConfigData("id", "name", apiKeyPool = pool, currentKeyIndex = 1)
        val provider = SnapshotApiKeyProvider(config)
        pool.clear()
        assertEquals("test-two", provider.getApiKey())
        assertEquals("test-one", provider.getApiKey())
        assertEquals(1, config.currentKeyIndex)
        assertEquals(2, provider.getCandidateKeyCount())
    }

    @Test fun testedUnavailablePoolCannotFallBackToAnotherKey() = runBlocking {
        val provider = SnapshotApiKeyProvider(ModelConfigData("id", "name", apiKey = "fallback",
            apiKeyPool = listOf(ApiKeyInfo("one", "test-one", availabilityStatus = ApiKeyAvailabilityStatus.UNAVAILABLE))))
        try {
            provider.getApiKey()
            fail("Unavailable pool must be rejected")
        } catch (_: IllegalStateException) { }
    }
}
