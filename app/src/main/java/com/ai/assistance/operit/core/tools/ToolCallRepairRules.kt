package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.ToolParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class ToolRepairArgument(val name: String, val value: JsonElement)

internal data class ToolRepairCall(
    val targetName: String,
    val arguments: List<ToolRepairArgument>,
    val proxyParameters: List<ToolParameter>? = null,
)

/** Rules are pure: no IO, permission decisions, model calls or mutation of the input. */
internal data class ToolCallRepairRule(
    val id: String,
    val apply: (ToolRepairCall) -> ToolRepairCall?,
)

internal object ToolCallRepairRules {
    private val repeatedTerminalSeparator = Regex("""^super_admin::+terminal$""")

    // Name normalization precedes canonical-name rules. One pass, no retry loop.
    val ordered = listOf(
        ToolCallRepairRule(ToolCallRepairRouter.MEMORY_FINISH_ALIAS) { call ->
            if (call.targetName != "memory_learning_action" || call.proxyParameters != null ||
                call.arguments.any { it.name !in setOf("action","arguments") } ||
                call.arguments.groupBy { it.name }.any { it.value.size != 1 } ||
                (call.arguments.singleOrNull { it.name == "action" }?.value as? JsonPrimitive)?.content != "memory_learning_finish")
                return@ToolCallRepairRule null
            val field = call.arguments.singleOrNull { it.name == "arguments" }
            if (field != null) {
                val raw = (field.value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return@ToolCallRepairRule null
                if (ToolCallRepairRouter.parseStrictJsonObject(raw)?.isEmpty() != true)
                    return@ToolCallRepairRule null
            }
            call.copy(targetName = "memory_learning_finish", arguments = emptyList())
        },
        ToolCallRepairRule(ToolCallRepairRouter.TERMINAL_SEPARATOR) { call ->
            // Any run of separators is the same typo; every other name stays untouched.
            if (repeatedTerminalSeparator.matches(call.targetName))
                call.copy(targetName = "super_admin:terminal") else null
        },
        ToolCallRepairRule(ToolCallRepairRouter.REDUNDANT_PACKAGE_NAME) { call ->
            val outer = call.proxyParameters
            val packageName = outer?.singleOrNull { it.name == "package_name" }?.value
            if (packageName != null && ':' in call.targetName &&
                packageName == call.targetName.substringBefore(':')) {
                call.copy(proxyParameters = checkNotNull(outer).filterNot { it.name == "package_name" })
            } else null
        },
        ToolCallRepairRule(ToolCallRepairRouter.READ_FILE_LINE_RANGE) { call ->
            if (call.targetName == "read_file" &&
                call.arguments.any { it.name == "start_line" || it.name == "end_line" })
                call.copy(targetName = "read_file_part") else null
        },
        ToolCallRepairRule(ToolCallRepairRouter.TERMINAL_TIMEOUT) { call ->
            if (call.targetName != "super_admin:terminal") return@ToolCallRepairRule null
            val alias = call.arguments.singleOrNull { it.name == "timeout" }
                ?: return@ToolCallRepairRule null
            val rawTimeout = (alias.value as? JsonPrimitive)?.content?.toLongOrNull()
                ?: return@ToolCallRepairRule null
            // Legacy millisecond aliases remain unchanged. Small integral values have an explicit
            // seconds compatibility range; the ambiguous gap is deliberately not guessed.
            val timeout = when (rawTimeout) {
                in 3..300 -> rawTimeout * 1_000
                in 3_000..Int.MAX_VALUE.toLong() -> rawTimeout
                else -> return@ToolCallRepairRule null
            }
            val canonical = call.arguments.filter { it.name == "timeoutMs" }
            if (canonical.size > 1 || (canonical.size == 1 &&
                (canonical.single().value as? JsonPrimitive)?.content?.toLongOrNull() != timeout)) {
                return@ToolCallRepairRule null
            }
            call.copy(arguments = if (canonical.isEmpty()) {
                call.arguments.map {
                    if (it.name != "timeout") it else it.copy(name = "timeoutMs",
                        value = if (rawTimeout == timeout) it.value
                            else if ((it.value as JsonPrimitive).isString) JsonPrimitive(timeout.toString())
                            else JsonPrimitive(timeout))
                }
            } else {
                call.arguments.filterNot { it.name == "timeout" }
            })
        },
        ToolCallRepairRule(ToolCallRepairRouter.MEMORY_ARGUMENT_ALIAS) { call ->
            if (call.targetName !in MEMORY_REVIEW_TOOLS) return@ToolCallRepairRule null
            val action = (call.arguments.singleOrNull { it.name == MEMORY_ACTION_FIELD }?.value
                as? JsonPrimitive)?.content
            if (action == null || action !in MEMORY_CONTENT_ACTIONS) return@ToolCallRepairRule null
            val field = call.arguments.singleOrNull { it.name == MEMORY_ARGUMENT_FIELD }
                ?: return@ToolCallRepairRule null
            val raw = (field.value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@ToolCallRepairRule null
            val renamed = renameMemoryArgumentAlias(raw) ?: return@ToolCallRepairRule null
            call.copy(arguments = call.arguments.map {
                if (it.name == MEMORY_ARGUMENT_FIELD) it.copy(value = JsonPrimitive(renamed)) else it
            })
        },
    )
}

private const val MEMORY_ACTION_FIELD = "action"
private const val MEMORY_ARGUMENT_FIELD = "arguments"
private const val MEMORY_ALIASED_KEY = "new_text"
private const val MEMORY_CANONICAL_KEY = "content"

/**
 * Both model-visible entry points carry the same contract: `action` plus one opaque `arguments`
 * object, executed by the same [com.ai.assistance.operit.api.chat.library.MemoryLearningActions].
 * `memory_review` is deliberately absent: its parameters are flat, with no `arguments` object.
 */
private val MEMORY_REVIEW_TOOLS = setOf("memory_learning_action", "learning_manage")

/** Only these actions read the argument object's `content`, so only these gain from the rename. */
private val MEMORY_CONTENT_ACTIONS = setOf(
    "memory_change", "skill_create", "skill_write", "skill_patch", "skill_remove_file",
)

/**
 * The review tool takes one opaque argument object, so a model can reach for the old_text/new_text
 * pair that diff-style editors use. `content` is the only key the tool reads, and an edit sent as
 * `new_text` is applied as an empty replacement, which deletes the matched text instead of
 * rewriting it. Sending both keys is ambiguous, so nothing is rewritten in that case, and the same
 * strict object parse the proxy path uses rejects duplicate keys instead of collapsing them.
 */
private fun renameMemoryArgumentAlias(raw: String): String? {
    val target = ToolCallRepairRouter.parseStrictJsonObject(raw) ?: return null
    if (target.containsKey(MEMORY_CANONICAL_KEY)) return null
    val alias = target[MEMORY_ALIASED_KEY] as? JsonPrimitive ?: return null
    if (!alias.isString) return null
    return JsonObject(target.map { (key, value) ->
        if (key == MEMORY_ALIASED_KEY) MEMORY_CANONICAL_KEY to value else key to value
    }.toMap()).toString()
}
