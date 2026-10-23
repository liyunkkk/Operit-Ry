package com.ai.assistance.operit.util

/**
 * Removes XML-like tags from message text, e.g. the `<mood>...</mood>` envelope a voice reply
 * may carry. Shared by message rollback and the voice screen so both agree on what the user sees.
 */
object XmlTagStripper {
    fun stripXmlLikeTags(text: String): String {
        var s = text
        // Paired tags, including nested ones. A single pass only removes the innermost pairs, so
        // the replacement repeats until the text stops changing (five levels is plenty).
        val paired = Regex(
            pattern = "<([A-Za-z][A-Za-z0-9:_-]*)(\\s[^>]*)?>[\\s\\S]*?</\\1>",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        repeat(5) {
            val updated = s.replace(paired, "")
            if (updated == s) return@repeat
            s = updated
        }
        // Self-closing tags.
        s = s.replace(
            Regex("<[A-Za-z][A-Za-z0-9:_-]*(\\s[^>]*)?/\\s*>", RegexOption.IGNORE_CASE),
            ""
        )
        // Any remaining tag.
        s = s.replace(Regex("</?[^>]+>", RegexOption.IGNORE_CASE), "")
        return s.trim()
    }
}
