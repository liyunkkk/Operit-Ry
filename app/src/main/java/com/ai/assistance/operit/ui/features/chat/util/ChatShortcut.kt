package com.ai.assistance.operit.ui.features.chat.util

enum class ChatShortcut(val command: String) {
    COMPACT("compact"), AUTO_COMPACT("autocompact"), CONTEXT("context"), MAX("max")
}

fun formatContextK(value: Float): String =
    java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

/** Discovery only. Sending this text must never execute a shortcut. */
fun chatShortcutSuggestions(text: String): List<ChatShortcut> {
    if (!text.startsWith("/") || text.any { it == '\n' || it == '\r' }) return emptyList()
    val name = text.drop(1).substringBefore(' ').lowercase()
    if (name.any { !it.isLetter() }) return emptyList()
    return ChatShortcut.entries.filter { it.command.startsWith(name) }
}

fun chatShortcutArgument(text: String, shortcut: ChatShortcut): String =
    if (text.substringBefore(' ').equals("/${shortcut.command}", ignoreCase = true))
        text.substringAfter(' ', "").trim() else ""

fun parseContextK(text: String): Float? =
    Regex("""(?i)^(\d+(?:\.\d+)?)\s*k$""").matchEntire(text.trim())
        ?.groupValues?.get(1)?.toFloatOrNull()
        ?.takeIf { it.isFinite() && it > 0f && it <= 1_000_000f }

fun isValidShortcutArgument(shortcut: ChatShortcut, argument: String): Boolean =
    argument.isEmpty() || when (shortcut) {
        ChatShortcut.COMPACT -> false
        ChatShortcut.AUTO_COMPACT -> argument.equals("auto", true) || parseContextK(argument) != null
        ChatShortcut.CONTEXT -> parseContextK(argument) != null
        ChatShortcut.MAX -> argument.equals("on", true) || argument.equals("off", true)
    }
