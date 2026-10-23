package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ApiKeyAvailabilityStatus
import com.ai.assistance.operit.data.model.ModelConfigData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Request-local pool rotation: auxiliary work cannot reread or modify foreground configuration. */
internal class SnapshotApiKeyProvider(config: ModelConfigData) : ApiKeyProvider {
    private val enabledKeys = config.apiKeyPool.filter { it.isEnabled }
    private val hasAvailabilityMarks = enabledKeys.any { it.availabilityStatus != ApiKeyAvailabilityStatus.UNTESTED }
    private val candidates = if (hasAvailabilityMarks) {
        enabledKeys.filter { it.availabilityStatus == ApiKeyAvailabilityStatus.AVAILABLE }
    } else enabledKeys
    private val fallback = config.apiKey
    private var nextIndex = config.currentKeyIndex.coerceAtLeast(0)
    private val mutex = Mutex()

    override suspend fun getApiKey(): String = mutex.withLock {
        if (candidates.isEmpty()) {
            check(!hasAvailabilityMarks && fallback.isNotBlank()) { "No available API key in frozen configuration" }
            return@withLock fallback
        }
        val index = nextIndex % candidates.size
        nextIndex = (index + 1) % candidates.size
        candidates[index].key
    }

    override suspend fun getCandidateKeyCount(): Int = candidates.size
}
