package com.ai.assistance.operit.api.voice

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only unambiguous standard base URLs are expanded; custom paths are never guessed. */
internal fun resolveOpenAiSpeechEndpoint(raw: String): String {
    val trimmed = raw.trim()
    val url = trimmed.toHttpUrlOrNull() ?: return trimmed
    val path = url.encodedPath.trimEnd('/')
    val target = when (path) {
        "" -> "/v1/audio/speech"
        "/v1" -> "/v1/audio/speech"
        else -> return trimmed
    }
    return url.newBuilder().encodedPath(target).build().toString()
}

internal fun isOpenAiSpeechEndpoint(raw: String): Boolean =
    raw.toHttpUrlOrNull()?.encodedPath?.trimEnd('/')?.endsWith("/audio/speech") == true
