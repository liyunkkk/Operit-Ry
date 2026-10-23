package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.json.JSONObject

/** Error envelopes must not become successful page content through the legacy text fallback. */
internal fun webVisitFailure(content: String): String? =
    runCatching { JSONObject(content).optString("error").takeIf { it.isNotBlank() } }.getOrNull()
