package com.ai.assistance.operit.api.chat.protocol

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.findMarkupTagEnd
import java.util.ArrayDeque

/**
 * Parses the text-only tool protocol without asking the generic XML/Markdown renderer to do so.
 *
 * The renderer intentionally accepts arbitrary XML-like markup. That is useful for displaying
 * provider output, but it makes it unsafe to use as an execution boundary: an unclosed
 * `<bytes>`/`<usage>` tag can consume a later executable tool. This parser only recognizes the
 * app-owned tool tag grammar and treats every other tag as ordinary text.
 */
internal object ExecutableToolProtocolParser {
    /** A preempted sampling is a draft, not a tool execution. Do not repair its partial arguments. */
    fun retainBeforeUnexecutedTool(content: String): String {
        val codeMask = MarkdownCodeMask.build(content)
        val scan = buildMarkupScan(content, codeMask)
        val first = scan.executableOpeningPositions.firstOrNull {
            !codeMask[it] && !scan.opaqueMask[it] && isExecutableBoundary(content, it)
        }
        val partial = inspectTruncation(content).truncatedTool?.fragmentStart
        val cut = listOfNotNull(first, partial).minOrNull() ?: return content
        return content.take(cut)
    }
    private data class OpeningTag(
        val name: String,
        val endExclusive: Int,
        val isSelfClosing: Boolean,
    )

    private data class ParameterCandidate(
        val name: String,
        val rawValue: String,
        val start: Int,
        val endExclusive: Int,
        val protectFromDisplayOnly: Boolean,
    )

    private data class Candidate(
        val toolName: String,
        val rawText: String,
        val start: Int,
        val endExclusive: Int,
        val parameters: List<ParameterCandidate>,
    )

    private data class MarkupToken(
        val start: Int,
        val endExclusive: Int,
        val name: String,
        val isClosing: Boolean,
        val isSelfClosing: Boolean,
        val isExactClosing: Boolean,
        val parentDepth: Int,
        val parentHasProtocolContainer: Boolean,
        val isOpaqueContainer: Boolean,
    )

    private data class MarkupRange(
        val start: Int,
        val endExclusive: Int,
    )

    private data class MarkupScan(
        val tokens: List<MarkupToken>,
        val matchingClosings: Map<Int, MarkupToken>,
        val opaqueMask: BooleanArray,
        val executableOpeningPositions: IntArray,
        val malformedMarkupStart: Int?,
        val malformedMarkupParentDepth: Int,
    )

    internal data class TruncatedToolCandidate(
        val tagName: String,
        val fragmentStart: Int,
        val fragment: String,
    )

    internal data class TruncationInspection(
        val truncatedTool: TruncatedToolCandidate?,
        val completeToolNames: List<String>,
    )

    private enum class BoundaryKind {
        CANDIDATE_START,
        PARAMETER_START,
        PARAMETER_END,
        CANDIDATE_END,
    }

    private data class BoundaryReference(
        val position: Int,
        val candidateIndex: Int,
        val parameterIndex: Int = -1,
        val kind: BoundaryKind,
    )

    /**
     * Extracts complete, visible executable tool calls from [content].
     *
     * Visibility is still decided by the app-owned display-only scanner. This keeps the existing
     * fail-closed behavior for `think`, `thinking`, `search`, malformed closers, and cross-nested
     * display markup while removing the generic XML plugin from the execution path.
     */
    fun parse(content: String): List<ToolInvocation> {
        if (content.isEmpty() || !ChatMarkupRegex.containsToolTag(content)) {
            return emptyList()
        }

        val codeMask = MarkdownCodeMask.build(content)
        val markupScan = buildMarkupScan(content, codeMask)
        val candidates = mutableListOf<Candidate>()

        markupScan.tokens.forEachIndexed { tokenIndex, token ->
            if (token.isClosing ||
                token.isSelfClosing ||
                !ChatMarkupRegex.isToolTagName(token.name) ||
                codeMask[token.start] ||
                markupScan.opaqueMask[token.start] ||
                !isExecutableBoundary(content, token.start)
            ) {
                return@forEachIndexed
            }

            val opening = parseOpeningTag(content, token.start)
            if (opening == null || opening.isSelfClosing) {
                return@forEachIndexed
            }

            val toolName =
                readQuotedAttribute(
                    content = content,
                    tagStart = token.start,
                    tagEndInclusive = opening.endExclusive - 1,
                    attributeName = "name",
                )?.takeIf { it.isNotEmpty() }
            if (toolName == null) {
                return@forEachIndexed
            }

            val closing = markupScan.matchingClosings[token.start]
            if (closing == null ||
                !closing.isExactClosing ||
                codeMask[closing.start]
            ) {
                return@forEachIndexed
            }
            if (hasNestedExecutableOpening(markupScan, token.start, closing.start)) {
                return@forEachIndexed
            }

            val closingStart = closing.start
            val parameters =
                parseParameters(
                    content = content,
                    bodyStart = opening.endExclusive,
                    bodyEndExclusive = closingStart,
                    codeMask = codeMask,
                    markupScan = markupScan,
                    firstTokenIndex = tokenIndex + 1,
                )

            candidates +=
                Candidate(
                    toolName = toolName,
                    rawText = content.substring(token.start, closing.endExclusive),
                    start = token.start,
                    endExclusive = closing.endExclusive,
                    parameters = parameters,
                )
        }

        if (candidates.isEmpty()) {
            return emptyList()
        }

        val boundaryReferences = buildList {
            candidates.forEachIndexed { candidateIndex, candidate ->
                add(
                    BoundaryReference(
                        position = candidate.start,
                        candidateIndex = candidateIndex,
                        kind = BoundaryKind.CANDIDATE_START,
                    )
                )
                candidate.parameters.forEachIndexed { parameterIndex, parameter ->
                    add(
                        BoundaryReference(
                            position = parameter.start,
                            candidateIndex = candidateIndex,
                            parameterIndex = parameterIndex,
                            kind = BoundaryKind.PARAMETER_START,
                        )
                    )
                    add(
                        BoundaryReference(
                            position = parameter.endExclusive,
                            candidateIndex = candidateIndex,
                            parameterIndex = parameterIndex,
                            kind = BoundaryKind.PARAMETER_END,
                        )
                    )
                }
                add(
                    BoundaryReference(
                        position = candidate.endExclusive,
                        candidateIndex = candidateIndex,
                        kind = BoundaryKind.CANDIDATE_END,
                    )
                )
            }
        }.sortedWith(
            compareBy<BoundaryReference> { it.position }
                // At equal positions the start boundary must be observed before an end boundary.
                .thenBy { it.kind.ordinal }
        )

        val candidateStartBoundaryIndices = IntArray(candidates.size)
        val candidateEndBoundaryIndices = IntArray(candidates.size)
        val parameterStartBoundaryIndices =
            candidates.map { IntArray(it.parameters.size) }.toTypedArray()
        val parameterEndBoundaryIndices =
            candidates.map { IntArray(it.parameters.size) }.toTypedArray()

        val orderedBoundaryPositions = IntArray(boundaryReferences.size)
        boundaryReferences.forEachIndexed { boundaryIndex, reference ->
            orderedBoundaryPositions[boundaryIndex] = reference.position
            when (reference.kind) {
                BoundaryKind.CANDIDATE_START ->
                    candidateStartBoundaryIndices[reference.candidateIndex] = boundaryIndex
                BoundaryKind.CANDIDATE_END ->
                    candidateEndBoundaryIndices[reference.candidateIndex] = boundaryIndex
                BoundaryKind.PARAMETER_START ->
                    parameterStartBoundaryIndices[reference.candidateIndex][reference.parameterIndex] =
                        boundaryIndex
                BoundaryKind.PARAMETER_END ->
                    parameterEndBoundaryIndices[reference.candidateIndex][reference.parameterIndex] =
                        boundaryIndex
            }
        }

        val protectedParameterRanges =
            candidates
                .flatMap { candidate ->
                    candidate.parameters
                        .map { parameter -> parameter.start to parameter.endExclusive }
                }
                .sortedBy { it.first }
                .flatMap { (start, endExclusive) -> listOf(start, endExclusive) }
                .toIntArray()

        val visibleBoundaries =
            ChatUtils.displayOnlyBoundaryVisibility(
                content = content,
                orderedBoundaries = orderedBoundaryPositions,
                protectedRanges = protectedParameterRanges,
            )

        return candidates.mapIndexedNotNull { candidateIndex, candidate ->
            if (!visibleBoundaries[candidateStartBoundaryIndices[candidateIndex]] ||
                !visibleBoundaries[candidateEndBoundaryIndices[candidateIndex]]
            ) {
                return@mapIndexedNotNull null
            }

            val parameters =
                candidate.parameters.mapIndexedNotNull { parameterIndex, parameter ->
                    if (!visibleBoundaries[
                            parameterStartBoundaryIndices[candidateIndex][parameterIndex]
                        ] ||
                        !visibleBoundaries[
                            parameterEndBoundaryIndices[candidateIndex][parameterIndex]
                        ]
                    ) {
                        null
                    } else {
                        ToolParameter(
                            name = parameter.name,
                            value = unescapeXml(parameter.rawValue),
                        )
                    }
                }

            ToolInvocation(
                tool = AITool(name = candidate.toolName, parameters = parameters),
                rawText = candidate.rawText,
                responseLocation = candidate.start until candidate.endExclusive,
            )
        }
    }

    internal fun inspectTruncation(content: String): TruncationInspection {
        if (content.isEmpty() || !ChatMarkupRegex.containsToolTag(content)) {
            return TruncationInspection(
                truncatedTool = null,
                completeToolNames = emptyList(),
            )
        }

        val codeMask = MarkdownCodeMask.build(content)
        val markupScan = buildMarkupScan(content, codeMask)
        val completeToolNames = mutableListOf<String>()
        var truncatedTool: TruncatedToolCandidate? = null
        var lastUnclosedTool: TruncatedToolCandidate? = null

        markupScan.tokens.forEach { token ->
            if (token.isClosing ||
                token.isSelfClosing ||
                !ChatMarkupRegex.isToolTagName(token.name) ||
                codeMask[token.start] ||
                markupScan.opaqueMask[token.start] ||
                !isExecutableBoundary(content, token.start)
            ) {
                return@forEach
            }

            val opening = parseOpeningTag(content, token.start)
            if (opening == null || opening.isSelfClosing) {
                return@forEach
            }

            val completeToolName =
                readQuotedAttribute(
                    content = content,
                    tagStart = token.start,
                    tagEndInclusive = opening.endExclusive - 1,
                    attributeName = "name",
                )?.takeIf { it.isNotEmpty() }
            val closing = markupScan.matchingClosings[token.start]
            when {
                closing != null &&
                    closing.isExactClosing &&
                    !codeMask[closing.start] &&
                    !hasNestedExecutableOpening(markupScan, token.start, closing.start) -> {
                    completeToolName?.let { completeToolNames += it }
                }

                closing != null -> {
                    // A malformed but complete-looking closing tag must not be repaired by
                    // appending a second close. The display scanner remains responsible for
                    // deciding whether later content is hidden.
                }

                completeToolName != null &&
                    token.parentDepth == 0 -> {
                    lastUnclosedTool =
                        TruncatedToolCandidate(
                            tagName = token.name,
                            fragmentStart = token.start,
                            fragment = content.substring(token.start),
                        )
                }
            }
        }

        if (lastUnclosedTool != null &&
            !hasMaskedStrictClosing(
                content = content,
                fromIndex = lastUnclosedTool.fragmentStart,
                tagName = lastUnclosedTool.tagName,
                codeMask = codeMask,
            )
        ) {
            truncatedTool = lastUnclosedTool
        }

        val trailingPartialTag = extractTrailingPartialTag(content, codeMask)
        if (trailingPartialTag != null) {
            val partialStart = content.length - trailingPartialTag.length
            val isTopLevelMalformedPartial =
                markupScan.malformedMarkupStart == partialStart &&
                    markupScan.malformedMarkupParentDepth == 0
            if (!codeMask[partialStart] &&
                (!markupScan.opaqueMask[partialStart] || isTopLevelMalformedPartial) &&
                isExecutableBoundary(content, partialStart)
            ) {
                val partialTagName = partialToolTagName(trailingPartialTag)
                if (partialTagName != null &&
                    hasUnterminatedNameAttribute(trailingPartialTag)
                ) {
                    truncatedTool =
                        TruncatedToolCandidate(
                            tagName = partialTagName,
                            fragmentStart = partialStart,
                            fragment = trailingPartialTag,
                        )
                }
            }
        }

        val visibleTruncatedTool =
            truncatedTool?.takeIf {
                ChatUtils.displayOnlyBoundaryVisibility(
                    content = content,
                    orderedBoundaries = intArrayOf(it.fragmentStart),
                    protectedRanges = intArrayOf(),
                ).firstOrNull() == true
            }

        return TruncationInspection(
            truncatedTool = visibleTruncatedTool,
            completeToolNames = completeToolNames.toList(),
        )
    }

    internal fun countCompleteToolInvocations(content: String): Int =
        inspectTruncation(content).completeToolNames.size

    internal fun buildTruncatedToolRepairSuffix(
        fragment: String,
        fallbackTagName: String,
    ): String {
        val toolTagName =
            fallbackTagName.takeIf { ChatMarkupRegex.isToolTagName(it) }
                ?: ChatMarkupRegex.generateRandomToolTagName()
        val openingTagEndInclusive = findMarkupTagEnd(fragment, 0)
        if (openingTagEndInclusive < 0) {
            return completePartialOpenTag(
                partialTag = fragment,
                tagName = toolTagName,
                defaultNameValue = "truncated_tool_call",
            ) + "</$toolTagName>"
        }

        val codeMask = MarkdownCodeMask.build(fragment)
        val markupScan = buildMarkupScan(fragment, codeMask)
        val rootOpening =
            markupScan.tokens.firstOrNull {
                it.start == 0 &&
                    !it.isClosing &&
                    !it.isSelfClosing &&
                    ChatMarkupRegex.isToolTagName(it.name)
            }
        if (rootOpening != null) {
            val closing = markupScan.matchingClosings[rootOpening.start]
            if (closing != null &&
                closing.isExactClosing &&
                !codeMask[closing.start]
            ) {
                return ""
            }
        }

        var openParamCount =
            markupScan.tokens.count { token ->
                token.start > openingTagEndInclusive &&
                    !token.isClosing &&
                    !token.isSelfClosing &&
                    token.name.equals("param", ignoreCase = true) &&
                    !codeMask[token.start] &&
                    markupScan.matchingClosings[token.start] == null
            }
        val suffix = StringBuilder()
        if (hasUnclosedCdata(fragment, codeMask, openingTagEndInclusive + 1)) {
            suffix.append("]]>")
        }

        val trailingPartialTag = extractTrailingPartialTag(fragment, codeMask)
        var toolClosedBySuffix = false
        var completedToolClosingTag: String? = null

        if (trailingPartialTag != null) {
            when {
                isPartialClosingTagFor(trailingPartialTag, "param") -> {
                    suffix.append(completePartialClosingTag(trailingPartialTag, "param"))
                    if (openParamCount > 0) {
                        openParamCount--
                    }
                }

                isPartialOpeningTagFor(trailingPartialTag, "param") -> {
                    val completedTagNameSuffix =
                        completePartialTagName(trailingPartialTag, "param")
                    suffix.append(
                        completePartialOpenTag(
                            trailingPartialTag + completedTagNameSuffix,
                            "param",
                            defaultNameValue = "_truncated_fragment",
                        )
                    )
                    suffix.insert(0, completedTagNameSuffix)
                    openParamCount++
                }

                isPartialClosingTagFor(trailingPartialTag, toolTagName) -> {
                    if (openParamCount > 0) {
                        return ""
                    }
                    completedToolClosingTag =
                        completePartialClosingTag(trailingPartialTag, toolTagName)
                    toolClosedBySuffix = true
                }

                isPartialOpeningTagFor(trailingPartialTag, toolTagName) -> {
                    val completedTagNameSuffix =
                        completePartialTagName(trailingPartialTag, toolTagName)
                    suffix.append(
                        completePartialOpenTag(
                            trailingPartialTag + completedTagNameSuffix,
                            toolTagName,
                            defaultNameValue = "truncated_tool_call",
                        )
                    )
                    suffix.insert(0, completedTagNameSuffix)
                }

                trailingPartialTag == "<" -> {
                    suffix.append("!-- truncated -->")
                }
            }
        }

        repeat(openParamCount) {
            suffix.append("</param>")
        }
        if (completedToolClosingTag != null) {
            suffix.append(completedToolClosingTag)
        } else if (!toolClosedBySuffix) {
            suffix.append("</$toolTagName>")
        }
        return suffix.toString()
    }

    internal fun extractAttributeValue(
        source: String,
        attributeName: String,
    ): String? {
        val tagEndInclusive = findMarkupTagEnd(source, 0)
        if (tagEndInclusive < 0) {
            return null
        }
        return readQuotedAttribute(
            content = source,
            tagStart = 0,
            tagEndInclusive = tagEndInclusive,
            attributeName = attributeName,
        )
    }

    private fun partialToolTagName(fragment: String): String? {
        val currentName =
            Regex("^<([A-Za-z][A-Za-z0-9_]*)")
                .find(fragment)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null
        return currentName.takeIf { ChatMarkupRegex.isToolTagName(it) }
    }

    private fun hasUnterminatedNameAttribute(fragment: String): Boolean {
        var index = 1
        var hasCompleteNameAttribute = false
        while (index < fragment.length &&
            isTagNameContinuation(fragment[index])
        ) {
            index++
        }

        while (index < fragment.length) {
            while (index < fragment.length &&
                isProtocolWhitespace(fragment[index])
            ) {
                index++
            }
            if (index >= fragment.length) {
                return hasCompleteNameAttribute
            }
            if (fragment[index] == '/') {
                return false
            }

            val attributeStart = index
            while (index < fragment.length &&
                isAttributeNameContinuation(fragment[index])
            ) {
                index++
            }
            if (attributeStart == index) {
                index++
                continue
            }
            val attributeName = fragment.substring(attributeStart, index)
            while (index < fragment.length &&
                isProtocolWhitespace(fragment[index])
            ) {
                index++
            }
            if (index >= fragment.length) {
                return hasCompleteNameAttribute
            }
            if (fragment[index] != '=') {
                continue
            }
            index++
            while (index < fragment.length &&
                isProtocolWhitespace(fragment[index])
            ) {
                index++
            }
            if (index >= fragment.length) {
                return hasCompleteNameAttribute
            }
            val quote = fragment[index]
            if (quote != '"' && quote != '\'') {
                while (index < fragment.length &&
                    !isProtocolWhitespace(fragment[index])
                ) {
                    index++
                }
                continue
            }

            index++
            while (index < fragment.length && fragment[index] != quote) {
                index++
            }
            if (index >= fragment.length) {
                return hasCompleteNameAttribute ||
                    attributeName.equals("name", ignoreCase = true)
            }
            if (attributeName.equals("name", ignoreCase = true)) {
                hasCompleteNameAttribute = true
            }
            index++
        }
        return hasCompleteNameAttribute
    }

    private fun hasUnclosedCdata(
        content: String,
        codeMask: BooleanArray,
        fromIndex: Int,
    ): Boolean {
        var index = fromIndex
        while (index < content.length) {
            if (codeMask[index]) {
                index++
                continue
            }
            if (!content.startsWith("<![CDATA[", index)) {
                index++
                continue
            }

            index += "<![CDATA[".length
            var closed = false
            while (index < content.length) {
                if (!codeMask[index] && content.startsWith("]]>", index)) {
                    index += "]]>".length
                    closed = true
                    break
                }
                index++
            }
            if (!closed) {
                return true
            }
        }
        return false
    }

    private fun extractTrailingPartialTag(
        fragment: String,
        codeMask: BooleanArray,
    ): String? {
        var index = 0
        while (index < fragment.length) {
            if (codeMask[index]) {
                index++
                continue
            }
            if (fragment.startsWith("<![CDATA[", index)) {
                val cdataEnd = findCdataEnd(fragment, index + "<![CDATA[".length, codeMask)
                if (cdataEnd < 0) {
                    return null
                }
                index = cdataEnd + "]]>".length
                continue
            }
            if (fragment[index] != '<') {
                index++
                continue
            }

            val tagEndInclusive = findMarkupTagEnd(fragment, index)
            if (tagEndInclusive < 0) {
                return fragment.substring(index)
            }
            index = tagEndInclusive + 1
        }
        return null
    }

    private fun findCdataEnd(
        content: String,
        fromIndex: Int,
        codeMask: BooleanArray,
    ): Int {
        var index = fromIndex
        while (index < content.length) {
            if (!codeMask[index] && content.startsWith("]]>", index)) {
                return index
            }
            index++
        }
        return -1
    }

    private fun completePartialOpenTag(
        partialTag: String,
        tagName: String,
        defaultNameValue: String,
    ): String {
        val suffix = StringBuilder()
        val normalizedPartial = partialTag.lowercase()
        val normalizedTagName = tagName.lowercase()
        val tagPrefix = "<$normalizedTagName"
        val attrValueOpenPattern =
            Regex("\\bname\\s*=\\s*([\"'])[^\"']*$", RegexOption.IGNORE_CASE)
        val attrEqPattern = Regex("\\bname\\s*=\\s*$", RegexOption.IGNORE_CASE)
        val defaultAttrPattern =
            Regex("^<${Regex.escape(tagName)}\\s*$", RegexOption.IGNORE_CASE)
        val partialNamePatterns =
            listOf(
                Regex("\\bn$", RegexOption.IGNORE_CASE) to "ame=\"\"",
                Regex("\\bna$", RegexOption.IGNORE_CASE) to "me=\"\"",
                Regex("\\bnam$", RegexOption.IGNORE_CASE) to "e=\"\"",
                Regex("\\bname$", RegexOption.IGNORE_CASE) to "=\"\"",
            )
        val partialNameCompletion =
            partialNamePatterns.firstOrNull { it.first.containsMatchIn(partialTag) }?.second

        when {
            attrValueOpenPattern.containsMatchIn(partialTag) -> {
                val quote =
                    Regex("\\bname\\s*=\\s*([\"'])", RegexOption.IGNORE_CASE)
                        .find(partialTag)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: "\""
                suffix.append(quote)
            }

            attrEqPattern.containsMatchIn(partialTag) -> suffix.append("\"\"")
            partialNameCompletion != null -> suffix.append(partialNameCompletion)
            normalizedPartial == tagPrefix || defaultAttrPattern.matches(partialTag) -> {
                suffix.append(" name=\"")
                suffix.append(defaultNameValue)
                suffix.append("\"")
            }
        }

        if (((partialTag.length + suffix.length) > 0) &&
            ((partialTag + suffix.toString()).count { it == '"' } % 2 != 0) &&
            !hasOddSingleQuoteCount(partialTag + suffix.toString())
        ) {
            suffix.append("\"")
        } else if (hasOddSingleQuoteCount(partialTag + suffix.toString())) {
            suffix.append("'")
        }
        if (!(partialTag + suffix.toString()).endsWith(">")) {
            suffix.append(">")
        }
        return suffix.toString()
    }

    private fun hasOddSingleQuoteCount(value: String): Boolean =
        value.count { it == '\'' } % 2 != 0

    private fun isPartialOpeningTagFor(partialTag: String, tagName: String): Boolean {
        if (!partialTag.startsWith("<") || partialTag.startsWith("</")) {
            return false
        }
        val currentName =
            Regex("^<([A-Za-z_]*)", RegexOption.IGNORE_CASE)
                .find(partialTag)
                ?.groupValues
                ?.getOrNull(1)
                ?.lowercase()
                ?: return false
        if (currentName.isEmpty()) {
            return false
        }
        return tagName.lowercase().startsWith(currentName)
    }

    private fun isPartialClosingTagFor(partialTag: String, tagName: String): Boolean {
        if (!partialTag.startsWith("</")) {
            return false
        }
        val currentName =
            Regex("^</([A-Za-z_]*)", RegexOption.IGNORE_CASE)
                .find(partialTag)
                ?.groupValues
                ?.getOrNull(1)
                ?.lowercase()
                ?: return false
        if (currentName.isEmpty()) {
            return false
        }
        return tagName.lowercase().startsWith(currentName)
    }

    private fun completePartialTagName(partialTag: String, tagName: String): String {
        val currentName =
            Regex("^</?([A-Za-z_]*)", RegexOption.IGNORE_CASE)
                .find(partialTag)
                ?.groupValues
                ?.getOrNull(1)
                ?.lowercase()
                .orEmpty()
        return tagName.substring(currentName.length.coerceAtMost(tagName.length))
    }

    private fun completePartialClosingTag(partialTag: String, tagName: String): String {
        return buildString {
            append(completePartialTagName(partialTag, tagName))
            append(">")
        }
    }

    private fun hasMaskedStrictClosing(
        content: String,
        fromIndex: Int,
        tagName: String,
        codeMask: BooleanArray,
    ): Boolean {
        var index = content.indexOf("</", fromIndex)
        while (index >= 0) {
            val closingEndExclusive = index + tagName.length + 3
            if (closingEndExclusive <= content.length &&
                !codeMask[index] &&
                content.regionMatches(index + 2, tagName, 0, tagName.length, ignoreCase = true) &&
                content[index + tagName.length + 2] == '>'
            ) {
                return true
            }
            index = content.indexOf("</", index + 2)
        }
        return false
    }

    private fun parseOpeningTag(content: String, start: Int): OpeningTag? {
        if (content.getOrNull(start) != '<') {
            return null
        }

        var nameEnd = start + 1
        if (!isAsciiLetter(content.getOrNull(nameEnd))) {
            return null
        }
        nameEnd++
        while (isTagNameContinuation(content.getOrNull(nameEnd))) {
            nameEnd++
        }

        val boundary = content.getOrNull(nameEnd)
        if (boundary != null &&
            boundary != '>' &&
            boundary != '/' &&
            !boundary.isWhitespace()
        ) {
            return null
        }

        val tagEndInclusive = findMarkupTagEnd(content, start)
        if (tagEndInclusive < 0) {
            return null
        }

        // Strict attribute validation: reject stray characters like '!' outside quotes.
        // Without this, '<tool name="x"!>' would be treated as a valid opening.
        var attrScanIndex = nameEnd
        while (attrScanIndex < tagEndInclusive) {
            while (attrScanIndex < tagEndInclusive && content[attrScanIndex].isWhitespace()) {
                attrScanIndex++
            }
            if (attrScanIndex >= tagEndInclusive) break
            if (content[attrScanIndex] == '/') {
                attrScanIndex++
                while (attrScanIndex < tagEndInclusive && content[attrScanIndex].isWhitespace()) {
                    attrScanIndex++
                }
                if (attrScanIndex != tagEndInclusive) {
                    return null
                }
                break
            }
            val attrNameStart = attrScanIndex
            while (attrScanIndex < tagEndInclusive && isAttributeNameContinuation(content[attrScanIndex])) {
                attrScanIndex++
            }
            if (attrNameStart == attrScanIndex) {
                return null
            }
            while (attrScanIndex < tagEndInclusive && content[attrScanIndex].isWhitespace()) {
                attrScanIndex++
            }
            if (attrScanIndex >= tagEndInclusive || content[attrScanIndex] != '=') {
                return null
            }
            attrScanIndex++
            while (attrScanIndex < tagEndInclusive && content[attrScanIndex].isWhitespace()) {
                attrScanIndex++
            }
            if (attrScanIndex >= tagEndInclusive) {
                return null
            }
            val quote = content[attrScanIndex]
            if (quote != '"' && quote != '\'') {
                return null
            }
            attrScanIndex++
            while (attrScanIndex < tagEndInclusive && content[attrScanIndex] != quote) {
                attrScanIndex++
            }
            if (attrScanIndex >= tagEndInclusive) {
                return null
            }
            attrScanIndex++
        }

        var lastMeaningfulIndex = tagEndInclusive - 1
        while (lastMeaningfulIndex >= nameEnd && content[lastMeaningfulIndex].isWhitespace()) {
            lastMeaningfulIndex--
        }

        return OpeningTag(
            name = content.substring(start + 1, nameEnd),
            endExclusive = tagEndInclusive + 1,
            isSelfClosing =
                lastMeaningfulIndex >= nameEnd && content[lastMeaningfulIndex] == '/',
        )
    }

    private fun readQuotedAttribute(
        content: String,
        tagStart: Int,
        tagEndInclusive: Int,
        attributeName: String,
    ): String? {
        val opening = parseOpeningTag(content, tagStart) ?: return null
        var index = tagStart + 1 + opening.name.length
        val limit = tagEndInclusive

        while (index < limit) {
            while (index < limit && content[index].isWhitespace()) {
                index++
            }
            if (index >= limit || content[index] == '/') {
                break
            }

            val attributeStart = index
            while (index < limit && isAttributeNameContinuation(content[index])) {
                index++
            }
            if (attributeStart == index) {
                index++
                continue
            }
            val currentAttribute = content.substring(attributeStart, index)

            while (index < limit && content[index].isWhitespace()) {
                index++
            }
            if (index >= limit || content[index] != '=') {
                continue
            }
            index++
            while (index < limit && content[index].isWhitespace()) {
                index++
            }
            if (index >= limit) {
                break
            }

            val quote = content[index]
            if (quote != '"' && quote != '\'') {
                while (index < limit && !content[index].isWhitespace()) {
                    index++
                }
                continue
            }
            index++
            val valueStart = index
            while (index < limit && content[index] != quote) {
                index++
            }
            if (index >= limit) {
                break
            }
            if (currentAttribute.equals(attributeName, ignoreCase = true)) {
                return content.substring(valueStart, index)
            }
            index++
        }
        return null
    }

    private fun buildMarkupScan(
        content: String,
        codeMask: BooleanArray,
    ): MarkupScan {
        val tokens = mutableListOf<MarkupToken>()
        val matchingClosings = mutableMapOf<Int, MarkupToken>()
        val opaqueRanges = mutableListOf<MarkupRange>()
        val stack = ArrayDeque<MarkupToken>()
        var protocolContainerDepth = 0
        var malformedMarkupStart: Int? = null
        var malformedMarkupParentDepth = -1
        var index = 0

        while (index < content.length) {
            if (content.startsWith("<![CDATA[", index)) {
                val cdataEnd = content.indexOf("]]>", index + "<![CDATA[".length)
                if (cdataEnd < 0) {
                    opaqueRanges +=
                        MarkupRange(
                            start = index,
                            endExclusive = content.length,
                        )
                    break
                }
                index = cdataEnd + "]]>".length
                continue
            }
            if (codeMask[index] || content[index] != '<') {
                index++
                continue
            }

            if (content.startsWith("<!--", index)) {
                val commentEnd = content.indexOf("-->", index + "<!--".length)
                if (commentEnd < 0) {
                    opaqueRanges +=
                        MarkupRange(
                            start = index + "<!--".length,
                            endExclusive = content.length,
                        )
                    break
                }
                opaqueRanges +=
                    MarkupRange(
                        start = index + "<!--".length,
                        endExclusive = commentEnd,
                    )
                index = commentEnd + "-->".length
                continue
            }

            val tagEndInclusive = findMarkupTagEnd(content, index)
            if (tagEndInclusive < 0) {
                if (isOpaqueMarkupStart(content, index) &&
                    !isAllowedMalformedDisplayCloser(
                        content = content,
                        start = index,
                        parentDepth = stack.size,
                    )
                ) {
                    malformedMarkupStart = index
                    malformedMarkupParentDepth = stack.size
                    opaqueRanges +=
                        MarkupRange(
                            start = index,
                            endExclusive = content.length,
                        )
                    break
                }
                index++
                continue
            }

            val endExclusive = tagEndInclusive + 1
            val token =
                parseMarkupToken(
                    content = content,
                    start = index,
                    endExclusive = endExclusive,
                    parentDepth = stack.size,
                    parentHasProtocolContainer = protocolContainerDepth > 0,
                )
            if (token == null) {
                if (!content.startsWith("</", index) &&
                    isOpaqueMarkupStart(content, index)
                ) {
                    malformedMarkupStart = index
                    malformedMarkupParentDepth = stack.size
                    opaqueRanges +=
                        MarkupRange(
                            start = index,
                            endExclusive = content.length,
                        )
                    break
                }
                index = endExclusive
                continue
            }

            tokens += token
            if (token.isClosing) {
                val opener = stack.peekLast()
                if (opener != null &&
                    isMatchingMarkupContainer(opener.name, token.name)
                ) {
                    stack.removeLast()
                    if (isProtocolContainer(opener.name)) {
                        protocolContainerDepth--
                    }
                    matchingClosings[opener.start] = token

                    if (opener.parentDepth == 0) {
                        opaqueRanges +=
                            MarkupRange(
                                start = opener.endExclusive,
                                endExclusive = token.start,
                            )
                    } else if (opener.parentHasProtocolContainer) {
                        opaqueRanges +=
                            MarkupRange(
                                start = opener.start,
                                endExclusive = token.endExclusive,
                            )
                    }
                }
            } else if (!token.isSelfClosing && token.isOpaqueContainer) {
                stack.addLast(token)
                if (isProtocolContainer(token.name)) {
                    protocolContainerDepth++
                }
            }
            index = endExclusive
        }

        stack.forEach { opener ->
            if (opener.isOpaqueContainer &&
                (opener.parentDepth == 0 ||
                    opener.parentHasProtocolContainer ||
                    opener.isOpaqueContainer)
            ) {
                opaqueRanges +=
                    MarkupRange(
                        start = opener.endExclusive,
                        endExclusive = content.length,
                    )
            }
        }

        val opaqueMask = BooleanArray(content.length)
        val difference = IntArray(content.length + 1)
        opaqueRanges.forEach { range ->
            val start = range.start.coerceIn(0, content.length)
            val endExclusive = range.endExclusive.coerceIn(start, content.length)
            if (start < endExclusive) {
                difference[start]++
                difference[endExclusive]--
            }
        }
        var opaqueDepth = 0
        opaqueMask.indices.forEach { position ->
            opaqueDepth += difference[position]
            opaqueMask[position] = opaqueDepth > 0
        }

        return MarkupScan(
            tokens = tokens,
            matchingClosings = matchingClosings,
            opaqueMask = opaqueMask,
            executableOpeningPositions =
                tokens
                    .asSequence()
                    .filter {
                        !it.isClosing &&
                            !it.isSelfClosing &&
                            ChatMarkupRegex.isToolTagName(it.name)
                    }
                    .map { it.start }
                    .toList()
                    .toIntArray(),
            malformedMarkupStart = malformedMarkupStart,
            malformedMarkupParentDepth = malformedMarkupParentDepth,
        )
    }

    private fun isPotentialMarkupStart(
        content: String,
        start: Int,
    ): Boolean {
        val next = content.getOrNull(start + 1)
        return isAsciiLetter(next) || next == '/'
    }

    private fun isOpaqueMarkupStart(
        content: String,
        start: Int,
    ): Boolean =
        isPotentialMarkupStart(content, start) ||
            content.startsWith("<!", start) ||
            content.startsWith("<?", start)

    /**
     * Preserve the legacy recovery point for a top-level, incomplete display-only closer such as
     * `</think` followed by a new line and a real tool. Attribute-bearing incomplete closers are
     * still opaque: they are not a safe boundary because their remainder may be hidden markup.
     */
    private fun isAllowedMalformedDisplayCloser(
        content: String,
        start: Int,
        parentDepth: Int,
    ): Boolean {
        if (parentDepth != 0 || !content.startsWith("</", start)) {
            return false
        }

        val nameStart = start + 2
        val displayTagNames = arrayOf("think", "thinking", "search")
        val tagName =
            displayTagNames.firstOrNull { candidate ->
                content.regionMatches(
                    nameStart,
                    candidate,
                    0,
                    candidate.length,
                    ignoreCase = true,
                )
            } ?: return false
        val suffixStart = nameStart + tagName.length
        val nextMarkup = content.indexOf('<', suffixStart)
        val suffixEndExclusive =
            if (nextMarkup >= 0) nextMarkup else content.length
        return content
            .substring(suffixStart, suffixEndExclusive)
            .all(::isProtocolWhitespace)
    }

    private fun hasNestedExecutableOpening(
        markupScan: MarkupScan,
        candidateStart: Int,
        closingStart: Int,
    ): Boolean {
        val positions = markupScan.executableOpeningPositions
        var low = 0
        var high = positions.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (positions[middle] <= candidateStart) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low < positions.size && positions[low] < closingStart
    }

    private fun isMatchingMarkupContainer(
        openerName: String,
        closerName: String,
    ): Boolean =
        openerName.equals(closerName, ignoreCase = true) ||
            (isThinkingDisplayTag(openerName) && isThinkingDisplayTag(closerName))

    private fun isThinkingDisplayTag(name: String): Boolean =
        name.equals("think", ignoreCase = true) ||
            name.equals("thinking", ignoreCase = true)

    private fun parseMarkupToken(
        content: String,
        start: Int,
        endExclusive: Int,
        parentDepth: Int,
        parentHasProtocolContainer: Boolean,
    ): MarkupToken? {
        if (content.startsWith("</", start)) {
            var index = start + 2
            if (!isAsciiLetter(content.getOrNull(index))) {
                return null
            }
            val nameStart = index
            index++
            while (isTagNameContinuation(content.getOrNull(index))) {
                index++
            }
            val name = content.substring(nameStart, index)
            val isExactClosing = index == endExclusive - 1
            while (index < endExclusive - 1 &&
                isProtocolWhitespace(content[index])
            ) {
                index++
            }
            if (index != endExclusive - 1) {
                return null
            }
            return MarkupToken(
                start = start,
                endExclusive = endExclusive,
                name = name,
                isClosing = true,
                isSelfClosing = false,
                isExactClosing = isExactClosing,
                parentDepth = parentDepth,
                parentHasProtocolContainer = parentHasProtocolContainer,
                isOpaqueContainer = false,
            )
        }

        val opening = parseOpeningTag(content, start) ?: return null
        if (opening.endExclusive != endExclusive) {
            return null
        }
        return MarkupToken(
            start = start,
            endExclusive = endExclusive,
            name = opening.name,
            isClosing = false,
            isSelfClosing = opening.isSelfClosing,
            isExactClosing = false,
            parentDepth = parentDepth,
            parentHasProtocolContainer = parentHasProtocolContainer,
            isOpaqueContainer =
                !opening.isSelfClosing &&
                    (isProtocolContainer(opening.name) ||
                        !isLikelyLeafUnknownOpening(
                            content = content,
                            openingStart = start,
                            tagName = opening.name,
                            afterOpeningTag = opening.endExclusive,
                        )),
        )
    }

    private fun isLikelyLeafUnknownOpening(
        content: String,
        openingStart: Int,
        tagName: String,
        afterOpeningTag: Int,
    ): Boolean {
        if (!isLegacyUsagePlaceholderTagName(tagName)) {
            return false
        }

        var colonIndex = openingStart - 1
        while (colonIndex >= 0 && isProtocolWhitespace(content[colonIndex])) {
            colonIndex--
        }
        if (colonIndex < 0 || content[colonIndex] != ':') {
            return false
        }

        var keyEndExclusive = colonIndex
        while (keyEndExclusive > 0 &&
            isProtocolWhitespace(content[keyEndExclusive - 1])
        ) {
            keyEndExclusive--
        }
        while (keyEndExclusive > 0 &&
            isAttributeNameContinuation(content[keyEndExclusive - 1])
        ) {
            keyEndExclusive--
        }
        val keyStart = keyEndExclusive
        val key = content.substring(keyStart, colonIndex)
        if (!key.equals("usage", ignoreCase = true) &&
            !key.equals("limit", ignoreCase = true)
        ) {
            return false
        }

        var indexAfterTag = afterOpeningTag
        var sawLineBreak = false
        while (indexAfterTag < content.length &&
            isProtocolWhitespace(content[indexAfterTag])
        ) {
            sawLineBreak =
                sawLineBreak ||
                    content[indexAfterTag] == '\n' ||
                    content[indexAfterTag] == '\r'
            indexAfterTag++
        }
        if (indexAfterTag >= content.length ||
            isProtocolPunctuation(content[indexAfterTag])
        ) {
            return true
        }
        if (sawLineBreak && content[indexAfterTag] == '<') {
            val nextOpening = parseOpeningTag(content, indexAfterTag)
            return nextOpening != null &&
                ChatMarkupRegex.isToolTagName(nextOpening.name)
        }
        return false
    }

    private fun isLegacyUsagePlaceholderTagName(tagName: String): Boolean =
        tagName.equals("bytes", ignoreCase = true)

    private fun isProtocolContainer(name: String): Boolean =
        ChatMarkupRegex.isToolTagName(name) ||
            ChatMarkupRegex.isToolResultTagName(name) ||
            name.equals("param", ignoreCase = true) ||
            isDisplayOnlyTag(name)

    private fun parseParameters(
        content: String,
        bodyStart: Int,
        bodyEndExclusive: Int,
        codeMask: BooleanArray,
        markupScan: MarkupScan,
        firstTokenIndex: Int,
    ): List<ParameterCandidate> {
        val parameters = mutableListOf<ParameterCandidate>()
        var tokenIndex = firstTokenIndex

        while (tokenIndex >= 0 && tokenIndex < markupScan.tokens.size) {
            val token = markupScan.tokens[tokenIndex]
            if (token.start >= bodyEndExclusive) {
                break
            }
            if (token.isClosing ||
                token.isSelfClosing ||
                !token.name.equals("param", ignoreCase = true) ||
                // The root tool is at depth 0, so only depth 1 is a direct child parameter.
                // Parameters nested in unknown/display containers must remain ordinary text.
                token.parentDepth != 1 ||
                codeMask[token.start]
            ) {
                tokenIndex++
                continue
            }

            val opening =
                parseParameterOpeningTag(
                    content = content,
                    start = token.start,
                    limitExclusive = bodyEndExclusive,
                )
            val closing = markupScan.matchingClosings[token.start]
            if (opening == null ||
                closing == null ||
                !closing.isExactClosing ||
                closing.start >= bodyEndExclusive ||
                codeMask[closing.start]
            ) {
                tokenIndex++
                continue
            }

            val closingEndExclusive = closing.endExclusive
            val rawValue = content.substring(opening.endExclusive, closing.start)
            parameters +=
                ParameterCandidate(
                    name = opening.name,
                    rawValue = rawValue,
                    start = token.start,
                    endExclusive = closingEndExclusive,
                    protectFromDisplayOnly = rawValue.trimStart().startsWith("<![CDATA["),
                )
            while (tokenIndex < markupScan.tokens.size &&
                markupScan.tokens[tokenIndex].start < closingEndExclusive
            ) {
                tokenIndex++
            }
        }

        return parameters
    }

    private fun parseParameterOpeningTag(
        content: String,
        start: Int,
        limitExclusive: Int,
    ): ParameterOpeningTag? {
        if (!content.startsWith("<param", start) || start + "<param".length >= limitExclusive) {
            return null
        }

        var index = start + "<param".length
        if (!isProtocolWhitespace(content[index])) {
            return null
        }
        while (index < limitExclusive && isProtocolWhitespace(content[index])) {
            index++
        }

        val attributeName = "name"
        if (!content.regionMatches(index, attributeName, 0, attributeName.length, ignoreCase = true)) {
            return null
        }
        index += attributeName.length
        while (index < limitExclusive && isProtocolWhitespace(content[index])) {
            index++
        }
        if (index >= limitExclusive || content[index] != '=') {
            return null
        }
        index++
        while (index < limitExclusive && isProtocolWhitespace(content[index])) {
            index++
        }
        if (index >= limitExclusive ||
            (content[index] != '"' && content[index] != '\'')
        ) {
            return null
        }
        val quote = content[index]
        index++

        val valueStart = index
        while (index < limitExclusive && content[index] != quote) {
            index++
        }
        if (index >= limitExclusive || index == valueStart) {
            return null
        }
        val parameterName = content.substring(valueStart, index)
        index++
        while (index < limitExclusive && isProtocolWhitespace(content[index])) {
            index++
        }
        if (index >= limitExclusive || content[index] != '>') {
            return null
        }

        return ParameterOpeningTag(
            name = parameterName,
            endExclusive = index + 1,
        )
    }

    private data class ParameterOpeningTag(
        val name: String,
        val endExclusive: Int,
    )

    /**
     * Mirrors the execution boundaries of the legacy XML stream without delegating parsing to it.
     *
     * In particular, an arbitrary self-closing HTML/XML tag such as `<br/>` is not an executable
     * boundary. This prevents inline markup from turning the following text into a tool call,
     * while line starts and the app-owned display block boundaries remain valid protocol starts.
     */
    private fun isExecutableBoundary(content: String, candidateStart: Int): Boolean {
        if (candidateStart == 0) {
            return true
        }

        if (content[candidateStart - 1] == '\n') {
            return true
        }

        var cursor = candidateStart - 1
        while (cursor >= 0 && isProtocolContinuation(content[cursor])) {
            cursor--
        }
        if (cursor < 0 || content[cursor] == '\n') {
            return true
        }

        if (isProtocolPunctuation(content[cursor])) {
            return true
        }

        if (content[candidateStart - 1] != '>') {
            return false
        }

        val previousTagStart = content.lastIndexOf('<', candidateStart - 1)
        if (previousTagStart < 0) {
            return false
        }

        val previousTag = content.substring(previousTagStart, candidateStart)
        if (isDisplayClosingTag(previousTag) || isStrictClosingTag(previousTag)) {
            return true
        }

        val previousOpening = parseOpeningTag(content, previousTagStart)
        if (previousOpening == null || previousOpening.endExclusive != candidateStart) {
            return false
        }

        // Only app-owned display self-closing tags may expose an immediately adjacent tool. An
        // arbitrary opening tag must not become a nesting escape hatch for executable markup.
        return previousOpening.isSelfClosing && isDisplayOnlyTag(previousOpening.name)
    }

    private fun isDisplayClosingTag(tag: String): Boolean {
        val trimmed = tag.trim()
        if (!trimmed.startsWith("</") || !trimmed.endsWith(">")) {
            return false
        }
        val name = trimmed.substring(2, trimmed.length - 1).trim()
        return name.equals("think", ignoreCase = true) ||
            name.equals("thinking", ignoreCase = true) ||
            name.equals("search", ignoreCase = true)
    }

    private fun isDisplayOnlyTag(name: String): Boolean =
        name.equals("think", ignoreCase = true) ||
            name.equals("thinking", ignoreCase = true) ||
            name.equals("search", ignoreCase = true)

    private fun isStrictClosingTag(tag: String): Boolean {
        if (!tag.startsWith("</") || !tag.endsWith(">")) {
            return false
        }
        var index = 2
        if (!isAsciiLetter(tag.getOrNull(index))) {
            return false
        }
        index++
        while (isTagNameContinuation(tag.getOrNull(index))) {
            index++
        }
        return index == tag.length - 1
    }

    private fun isProtocolPunctuation(char: Char): Boolean =
        char in PROTOCOL_PUNCTUATION ||
            Character.isSurrogate(char) ||
            Character.getType(char) == Character.OTHER_SYMBOL.toInt()

    private fun isProtocolContinuation(char: Char): Boolean =
        char == ' ' || char == '\t' || char in EMOJI_CONTINUATION_CHARS

    private fun isProtocolWhitespace(char: Char): Boolean =
        char == ' ' ||
            char == '\t' ||
            char == '\n' ||
            char == '\r' ||
            char == '\u000B' ||
            char == '\u000C'

    private val PROTOCOL_PUNCTUATION =
        setOf(
            '\uFF0C', // fullwidth comma
            '\u3002', // ideographic full stop
            '\uFF1F', // fullwidth question
            '\uFF01', // fullwidth exclamation
            '\uFF1A', // fullwidth colon
            '\uFF08', // fullwidth left paren
            '\uFF09', // fullwidth right paren
            '\u3010', // left black lenticular
            '\u3011', // right black lenticular
            '\u300A', // left double angle
            '\u300B', // right double angle
            ':',
            ',',
            '.',
            '?',
            '!',
            '~',
            '\uFF5E', // fullwidth tilde
            '}',
            ']',
        )

    private val EMOJI_CONTINUATION_CHARS = setOf('\u200D', '\uFE0E', '\uFE0F', '\u20E3')

    private fun isAsciiLetter(char: Char?): Boolean =
        char != null && (char in 'A'..'Z' || char in 'a'..'z')

    private fun isTagNameContinuation(char: Char?): Boolean =
        char != null &&
            ((char in 'A'..'Z') ||
                (char in 'a'..'z') ||
                (char in '0'..'9') ||
                char == '_' ||
                char == '-' ||
                char == '.' ||
                char == ':')

    private fun isAttributeNameContinuation(char: Char): Boolean =
        (char in 'A'..'Z') ||
            (char in 'a'..'z') ||
            (char in '0'..'9') ||
            char == '_' ||
            char == '-' ||
            char == '.' ||
            char == ':'

    private fun unescapeXml(input: String): String {
        var result = input
        if (result.startsWith("<![CDATA[") && result.endsWith("]]>")) {
            result = result.substring(9, result.length - 3)
        }
        if (result.endsWith("]]>")) {
            result = result.substring(0, result.length - 3)
        }
        if (result.startsWith("<![CDATA[")) {
            result = result.substring(9)
        }
        return result.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    /**
     * Marks Markdown code spans so tool-shaped examples are never considered executable.
     *
     * The generic stream renderer owns the final Markdown presentation. This small lexical pass
     * only supplies an execution boundary and deliberately fails closed for an unterminated code
     * span until the next newline/fence boundary.
     */
    private object MarkdownCodeMask {
        private val parameterCloser = Regex("</param\\s*>", RegexOption.IGNORE_CASE)

        fun build(content: String): BooleanArray {
            val mask = BooleanArray(content.length)
            var index = 0
            var atStartOfLine = true
            var fenceLength = 0
            var insideTool = false
            var parameterEnd = -1

            while (index < content.length) {
                // Parameter Markdown is data. Its code spans cannot hide the protocol closer
                // or leak into the next parameter/tool, even when the file contains an open fence.
                if (parameterEnd >= 0 && index >= parameterEnd) {
                    fenceLength = 0
                    parameterEnd = -1
                }
                // Providers escape the reasoning body and own its envelope. Markdown inside
                // that body must not open a fence spanning the following native tool calls.
                // Keep the envelope visible to the markup scanner, which still excludes its
                // contents from execution. Never reinterpret envelopes inside code or parameters.
                if (fenceLength == 0 && parameterEnd < 0 &&
                    content.startsWith(ChatUtils.PROVIDER_REASONING_OPEN_TAG, index)
                ) {
                    val bodyStart = index + ChatUtils.PROVIDER_REASONING_OPEN_TAG.length
                    val bodyEnd = content.indexOf("</think>", bodyStart)
                        .takeIf { it >= 0 } ?: content.length
                    mask.fill(true, bodyStart, bodyEnd)
                    index = bodyEnd
                    atStartOfLine = false
                    continue
                }
                if (fenceLength == 0 && parameterEnd < 0 && content[index] == '<') {
                    if (content.startsWith("</", index)) {
                        val closeEnd = content.indexOf('>', index + 2)
                        if (closeEnd >= 0 &&
                            ChatMarkupRegex.isToolTagName(content.substring(index + 2, closeEnd).trim())
                        ) {
                            insideTool = false
                        }
                    }
                    val opening = parseOpeningTag(content, index)
                    if (opening != null && ChatMarkupRegex.isToolTagName(opening.name)) {
                        insideTool = !opening.isSelfClosing
                    } else if (insideTool && opening != null &&
                        opening.name.equals("param", ignoreCase = true) && !opening.isSelfClosing
                    ) {
                        var searchStart = opening.endExclusive
                        while (true) {
                            val closing = parameterCloser.find(content, searchStart) ?: break
                            var cdataStart = searchStart
                            while (cdataStart < closing.range.first &&
                                !content.startsWith("<![CDATA[", cdataStart)
                            ) {
                                cdataStart++
                            }
                            if (cdataStart < closing.range.first) {
                                val cdataEnd = content.indexOf("]]>", cdataStart + 9)
                                if (cdataEnd < 0) break
                                searchStart = cdataEnd + 3
                            } else {
                                parameterEnd = closing.range.first
                                break
                            }
                        }
                    }
                }
                if (fenceLength > 0) {
                    if (atStartOfLine && (content[index] == ' ' || content[index] == '\t')) {
                        mask[index] = true
                        index++
                        continue
                    }

                    val runLength = backtickRunLength(content, index)
                    if (atStartOfLine && runLength >= fenceLength) {
                        repeat(runLength) { offset ->
                            mask[index + offset] = true
                        }
                        index += runLength
                        fenceLength = 0
                        atStartOfLine = false
                        continue
                    }

                    mask[index] = true
                    atStartOfLine = content[index] == '\n'
                    index++
                    continue
                }

                val runLength = backtickRunLength(content, index)
                if (runLength >= 3) {
                    repeat(runLength) { offset ->
                        mask[index + offset] = true
                    }
                    index += runLength
                    fenceLength = runLength
                    atStartOfLine = false
                    continue
                }

                if (runLength > 0) {
                    val closing =
                        findInlineClosingRun(
                            content = content,
                            fromIndex = index + runLength,
                            runLength = runLength,
                            limitExclusive = if (parameterEnd >= 0) parameterEnd else content.length,
                        )
                    if (closing >= 0) {
                        for (position in index..(closing + runLength - 1)) {
                            mask[position] = true
                        }
                        index = closing + runLength
                        atStartOfLine = false
                        continue
                    }

                    // An unclosed inline code span is non-executable through this line.
                    var end = index + runLength
                    val limit = if (parameterEnd >= 0) parameterEnd else content.length
                    while (end < limit && content[end] != '\n') {
                        mask[end] = true
                        end++
                    }
                    index = end
                    continue
                }

                atStartOfLine = content[index] == '\n'
                index++
            }

            return mask
        }

        private fun backtickRunLength(content: String, start: Int): Int {
            if (content.getOrNull(start) != '`') {
                return 0
            }
            var index = start
            while (index < content.length && content[index] == '`') {
                index++
            }
            return index - start
        }

        private fun findInlineClosingRun(
            content: String,
            fromIndex: Int,
            runLength: Int,
            limitExclusive: Int,
        ): Int {
            var index = fromIndex
            while (index < limitExclusive && content[index] != '\n') {
                if (content[index] == '`' && backtickRunLength(content, index) == runLength) {
                    return index
                }
                index++
            }
            return -1
        }
    }
}
