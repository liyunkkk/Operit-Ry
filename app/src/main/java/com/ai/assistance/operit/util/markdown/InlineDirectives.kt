package com.ai.assistance.operit.util.markdown

/**
 * Small inline elements a model may place inside a Markdown paragraph:
 * `:badge[已完成]{tone=success icon=check}` and `:icon[time]` / `:icon[check]{tone=success}`.
 *
 * Only plain text is scanned, so the same syntax inside inline code stays literal.
 * Malformed syntax is left as ordinary text; an unknown icon name renders nothing.
 */
object InlineDirectives {
    val TONES = setOf("neutral", "accent", "success", "info", "warning", "error")

    sealed interface Segment
    data class Text(val text: String) : Segment
    data class Badge(val label: String, val tone: String?, val icon: String?) : Segment
    data class Icon(val name: String, val tone: String?, val size: String? = null) : Segment

    /** Badges never wrap, so the label stays short enough to fit a narrow bubble or table cell. */
    const val MAX_LABEL = 16
    const val MAX_ICON_NAME = 64

    private val pattern = Regex(""":(badge|icon)\[([^\[\]\n]{1,$MAX_ICON_NAME})\](?:\{([^{}\n]{0,160})\})?""")

    fun mayContain(text: String): Boolean = text.contains(":badge[") || text.contains(":icon[")

    /** Splits [text] into text and inline elements; an unknown icon becomes an empty text. */
    fun parse(text: String, isKnownIcon: (String) -> Boolean): List<Segment> {
        if (!mayContain(text)) return listOf(Text(text))
        val out = ArrayList<Segment>()
        var cursor = 0
        for (match in pattern.findAll(text)) {
            val segment = toSegment(match, isKnownIcon) ?: continue
            if (match.range.first > cursor) out += Text(text.substring(cursor, match.range.first))
            out += segment
            cursor = match.range.last + 1
        }
        if (cursor < text.length) out += Text(text.substring(cursor))
        return out
    }

    /** Text for copy and speech: a badge keeps its label, an icon disappears. */
    fun toPlainText(text: String): String {
        if (!mayContain(text)) return text
        return pattern.replace(text) { match ->
            val body = match.groupValues[2].trim()
            when {
                body.isEmpty() || (match.groupValues[1] == "badge" && match.groupValues[2].length > MAX_LABEL) -> match.value
                match.groupValues[1] == "badge" -> body
                else -> ""
            }
        }
    }

    private fun toSegment(match: MatchResult, isKnownIcon: (String) -> Boolean): Segment? {
        val body = match.groupValues[2].trim()
        if (body.isEmpty() || (match.groupValues[1] == "badge" && match.groupValues[2].length > MAX_LABEL)) return null
        val attrs = parseAttributes(match.groupValues[3])
        val tone = attrs["tone"]?.takeIf { it in TONES }
        return if (match.groupValues[1] == "badge") {
            Badge(body, tone, attrs["icon"]?.takeIf(isKnownIcon))
        } else {
            if (isKnownIcon(body)) Icon(body, tone, attrs["size"]?.takeIf { it in setOf("sm", "md", "lg") }) else Text("")
        }
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(' ', ',', '\t').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            part.substring(0, eq).trim() to part.substring(eq + 1).trim().trim('"', '\'')
        }.toMap()
    }
}
