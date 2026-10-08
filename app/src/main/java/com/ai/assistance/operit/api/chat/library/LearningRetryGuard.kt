package com.ai.assistance.operit.api.chat.library

import java.security.MessageDigest

/** Session-lock confined. A successful read/edit can change the conditions behind a rejection. */
internal class LearningRetryGuard {
    private val failures = HashMap<String, Int>()
    fun isBlocked(key: String) = (failures[key] ?: 0) >= LEARNING_REPEAT_FAILURE_LIMIT
    fun onFailure(key: String): Int =
        ((failures[key] ?: 0) + 1).also { failures[key] = it }
    fun onSuccess() = failures.clear()
}

/** Retain only a digest, not another copy of potentially large document/tool arguments. */
internal fun learningRetryKey(action: String, args: Map<String, String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (part in listOf(action) + args.toSortedMap().flatMap { listOf(it.key,it.value) }) {
        digest.update(part.length.toString().toByteArray(Charsets.UTF_8))
        digest.update(':'.code.toByte())
        digest.update(part.toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
