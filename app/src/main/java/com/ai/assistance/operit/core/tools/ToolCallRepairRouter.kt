package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import org.json.JSONTokener
import kotlinx.serialization.json.*

/** Decode once, apply each deterministic rule once, then restore the original call envelope. */
internal object ToolCallRepairRouter {
    const val READ_FILE_LINE_RANGE = "read_file_line_range"
    const val TERMINAL_SEPARATOR = "terminal_single_separator"
    const val TERMINAL_TIMEOUT = "terminal_timeout_alias"
    const val REDUNDANT_PACKAGE_NAME = "redundant_proxy_package_name"
    const val PROXY_FLATTENED_TOOL_NAME = "proxy_flattened_tool_name"
    const val MEMORY_ARGUMENT_ALIAS = "memory_argument_alias"
    const val MEMORY_FINISH_ALIAS = "memory_finish_alias"

    fun terminalToolName(invocation: ToolInvocation): String =
        if (invocation.tool.name == "memory_learning_action")
            route(invocation)?.invocation?.tool?.name ?: invocation.tool.name
        else invocation.tool.name

    data class Repair(
        val original: ToolInvocation,
        val invocation: ToolInvocation,
        val originalToolName: String,
        val targetToolName: String,
        val parameterNames: List<String>,
        val rules: List<String>,
        val originalParameterNames: List<String>,
    ) {
        val rule: String get() = rules.first()
    }

    fun route(original: ToolInvocation): Repair? {
        val tool = original.tool
        val isProxy = tool.name == "proxy" || tool.name == "package_proxy"
        val decoded = if (isProxy) (decodeProxy(tool) ?: return null) else null
        val initial = decoded?.call ?: ToolRepairCall(tool.name,
            tool.parameters.map { ToolRepairArgument(it.name, JsonPrimitive(it.value)) })
        var current = initial
        val applied = mutableListOf<String>()
        decoded?.leadingRule?.let { applied += it }
        for (rule in ToolCallRepairRules.ordered) {
            val next = rule.apply(current) ?: continue
            if (next == current) continue
            current = next
            applied += rule.id
        }
        if (applied.isEmpty()) return null
        val routed = if (isProxy) {
            val encoded = if (current.arguments == initial.arguments) null else
                JsonObject(current.arguments.associate { it.name to it.value }).toString()
            tool.copy(parameters = checkNotNull(current.proxyParameters).map {
                when {
                    it.name == "tool_name" && current.targetName != initial.targetName ->
                        it.copy(value = current.targetName)
                    it.name == "params" && encoded != null -> it.copy(value = encoded)
                    else -> it
                }
            })
        } else {
            tool.copy(name = current.targetName, parameters = current.arguments.map {
                ToolParameter(it.name, it.value.jsonPrimitive.content)
            })
        }
        // The log names the call as it arrived: the target the model declared, or the proxy tool
        // itself when the model declared no target at all.
        return Repair(original, original.copy(tool = routed),
            decoded?.reportedName ?: initial.targetName, current.targetName,
            current.arguments.map { it.name }, applied, initial.arguments.map { it.name })
    }

    private data class DecodedProxy(
        val call: ToolRepairCall,
        val leadingRule: String?,
        val reportedName: String,
    )

    /**
     * A model that loses the proxy envelope writes the target name at the end of the params text
     * instead of declaring it as a sibling parameter. Recovering it is only safe when the rest of
     * the text is still exactly one argument object, so [splitFlattenedProxyParams] decides that.
     */
    private fun decodeProxy(tool: AITool): DecodedProxy? {
        if (tool.parameters.map { it.name }.distinct().size != tool.parameters.size) return null
        val declared = tool.parameters.singleOrNull { it.name == "tool_name" }?.value?.trim()
            ?.takeIf { it.isNotEmpty() }
        val supplied = tool.parameters.singleOrNull { it.name == "params" }?.value ?: return null
        val flattened = if (declared == null) splitFlattenedProxyParams(supplied) else null
        val name = declared ?: flattened?.toolName ?: return null
        if (tool.name == "package_proxy" && ':' !in name) return null
        val raw = flattened?.params ?: supplied
        val args = parseArguments(raw) ?: return null
        // A blank tool_name counts as undeclared, so the recovered one replaces that parameter
        // instead of sitting next to it: two tool_name parameters are rejected outright.
        val parameters = if (flattened == null) tool.parameters else
            listOf(ToolParameter("tool_name", name)) + tool.parameters
                .filterNot { it.name == "tool_name" }
                .map { parameter ->
                    if (parameter.name == "params") ToolParameter("params", raw) else parameter
                }
        return DecodedProxy(ToolRepairCall(name, args, parameters),
            if (flattened == null) null else PROXY_FLATTENED_TOOL_NAME,
            declared ?: tool.name)
    }

    private data class FlattenedProxyParams(val toolName: String, val params: String)

    /** `{...}, {"tool_name": "x"}` — the model kept a separator but dropped the sibling key. */
    private val flattenedTrailerWithObject = Regex(
        """^\s*,\s*\{\s*"tool_name"\s*:\s*"([^"\\]*)"\s*\}\s*\z""")

    /** `{...}, "tool_name": "x"}` — the same flattening with one closing brace left behind. */
    private val flattenedTrailerWithStrayBrace = Regex(
        """^\s*,\s*"tool_name"\s*:\s*"([^"\\]*)"\s*\}\s*\z""")

    /**
     * Both accepted shapes must leave exactly one argument object, and that object must not carry a
     * tool_name of its own, so the recovered pair is never a choice between two candidates. The
     * leading object is delimited by string-aware brace counting, so arguments that themselves
     * contain braces are still recovered. A shape that fails any check is left alone, not guessed.
     */
    private fun splitFlattenedProxyParams(raw: String): FlattenedProxyParams? {
        val end = endOfLeadingObject(raw) ?: return null
        val params = raw.substring(0, end)
        if (!hasNoOwnToolNameKey(params)) return null
        val name = (flattenedTrailerWithObject.matchEntire(raw.substring(end))
            ?: flattenedTrailerWithStrayBrace.matchEntire(raw.substring(end)))
            ?.groupValues?.get(1)
        return name?.trim()?.takeIf { it.isNotEmpty() }?.let { FlattenedProxyParams(it, params) }
    }

    /** Index just past the balanced object that starts the text, ignoring braces inside strings. */
    private fun endOfLeadingObject(raw: String): Int? {
        if (raw.firstOrNull() != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        raw.forEachIndexed { index, character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else when (character) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
        }
        return null
    }

    /** False unless the text is one object that is proven not to carry a tool_name of its own. */
    private fun hasNoOwnToolNameKey(raw: String): Boolean =
        (runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull())
            ?.containsKey("tool_name") == false

    private fun parseArguments(raw: String): List<ToolRepairArgument>? =
        parseStrictJsonObject(raw)?.map { ToolRepairArgument(it.key, it.value) }

    /**
     * Reject duplicate keys, bare literals and raw controls before a repair can rewrite an argument
     * object. Shared so a nested argument object is held to the same standard as a proxy one.
     */
    internal fun parseStrictJsonObject(raw: String): JsonObject? = runCatching {
        // kotlinx accepts bare literals and raw controls in strings; validate those explicitly.
        var inString = false
        var escaped = false
        raw.forEach { character ->
            if (inString) {
                require(character.code >= 0x20)
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                if (character == '"') inString = true
                if (character.isWhitespace()) require(character in " \t\r\n")
            }
        }
        val parsed = Json.parseToJsonElement(raw) as? JsonObject ?: error("Expected object")
        validateLiterals(parsed)
        val input = JSONTokener(raw)
        consumeUniqueJsonValue(input)
        require(input.nextClean() == '\u0000')
        parsed
    }.getOrNull()

    private fun consumeUniqueJsonValue(input: JSONTokener) {
        when (input.nextClean()) {
            '{' -> {
                val names = hashSetOf<String>()
                if (input.nextClean() == '}') return
                input.back()
                while (true) {
                    require(input.nextClean() == '"')
                    require(names.add(input.nextString('"')))
                    require(input.nextClean() == ':')
                    consumeUniqueJsonValue(input)
                    when (input.nextClean()) {
                        '}' -> return
                        ',' -> Unit
                        else -> error("Invalid argument object")
                    }
                }
            }
            '[' -> {
                if (input.nextClean() == ']') return
                input.back()
                while (true) {
                    consumeUniqueJsonValue(input)
                    when (input.nextClean()) {
                        ']' -> return
                        ',' -> Unit
                        else -> error("Invalid argument array")
                    }
                }
            }
            else -> {
                input.back()
                input.nextValue()
            }
        }
    }

    private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    private fun validateLiterals(value: JsonElement) {
        when (value) {
            is JsonObject -> value.values.forEach(::validateLiterals)
            is JsonArray -> value.forEach(::validateLiterals)
            is JsonPrimitive -> if (!value.isString) {
                require(value.content in setOf("true", "false", "null") || jsonNumber.matches(value.content))
            }
        }
    }
}
