package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolCallRepairRouterTest {
    @Test fun terminalSecondsCompatibilityConvertsAndChecksCanonicalConflict() {
        for (seconds in listOf(3,60,90,300)) {
            val result = ToolCallRepairRouter.route(call("super_admin:terminal","timeout" to "$seconds"))!!.invocation
            assertEquals("${seconds*1000}",result.tool.parameters.single().value)
            assertNull(ToolCallRepairRouter.route(result))
        }
        assertNull(ToolCallRepairRouter.route(call("super_admin:terminal","timeout" to "301")))
        assertNull(ToolCallRepairRouter.route(call("super_admin:terminal","timeout" to "60","timeoutMs" to "60")))
        assertEquals(listOf(ToolParameter("timeoutMs","60000")),ToolCallRepairRouter.route(
            call("super_admin:terminal","timeout" to "60","timeoutMs" to "60000"))!!.invocation.tool.parameters)
        val proxy = ToolCallRepairRouter.route(call("proxy","tool_name" to "super_admin:terminal",
            "params" to """{"timeout":90}"""))!!.invocation
        assertEquals(90000,JSONObject(proxy.tool.parameters.last().value).getInt("timeoutMs"))
    }

    @Test fun finishAliasRequiresEmptyInputAndRetainsIdentityAndTerminalSemantics() {
        val original = call("memory_learning_action","action" to "memory_learning_finish","arguments" to "{}")
        val result = ToolCallRepairRouter.route(original)!!.invocation
        assertEquals("memory_learning_finish",result.tool.name)
        assertTrue(result.tool.parameters.isEmpty())
        assertEquals(original.callId,result.callId)
        assertEquals(result.tool.name,ToolCallRepairRouter.terminalToolName(original))
        assertNull(ToolCallRepairRouter.route(result))
        for (raw in listOf("""{"name":"x"}""","null","", """{"a":1,"a":2}""")) {
            assertNull(ToolCallRepairRouter.route(call("memory_learning_action",
                "action" to "memory_learning_finish","arguments" to raw)))
        }
        assertNull(ToolCallRepairRouter.route(call("memory_learning_action","action" to "memory_learning_finish","extra" to "x")))
        assertNull(ToolCallRepairRouter.route(call("learning_manage","action" to "memory_learning_finish","arguments" to "{}")))
    }
    private fun call(name: String, vararg params: Pair<String, String>) =
        ToolInvocation(AITool(name, params.map { ToolParameter(it.first, it.second) }),
            rawText = "original provider response", responseLocation = 4..12,
            callId = "call-7", invocationIndex = 2)

    @Test fun lineReadPreservesValuesAndProviderIdentity() {
        val original = call("read_file", "path" to "/tmp/a.txt", "environment" to "linux",
            "start_line" to "20", "end_line" to "30")
        val repaired = ToolCallRepairRouter.route(original)!!.invocation
        assertEquals("read_file_part", repaired.tool.name)
        assertEquals(original.tool.parameters, repaired.tool.parameters)
        assertEquals(original, repaired.copy(tool = original.tool))
        assertNull(ToolCallRepairRouter.route(repaired))
    }

    @Test fun eitherLineBoundarySelectsLineReaderWithoutGuessingValues() {
        for (parameter in listOf("start_line", "end_line")) {
            val original = call("read_file", "path" to "/tmp/a", parameter to "7")
            assertEquals(original.tool.parameters,
                ToolCallRepairRouter.route(original)!!.invocation.tool.parameters)
        }
    }

    @Test fun ordinaryReadsAndOtherToolsAreUnchanged() {
        assertNull(ToolCallRepairRouter.route(call("read_file", "path" to "/tmp/a")))
        assertNull(ToolCallRepairRouter.route(call("read_file_part", "start_line" to "2")))
        assertNull(ToolCallRepairRouter.route(call("other:read_file", "start_line" to "2")))
    }

    @Test fun hiddenReadKeepsProxyEnvelopeAndExactParamsText() {
        val raw = """{ "path": "/tmp/a", "environment":"linux", "start_line":2 }"""
        val original = call("proxy", "tool_name" to "read_file", "params" to raw)
        val repaired = ToolCallRepairRouter.route(original)!!.invocation
        assertEquals("proxy", repaired.tool.name)
        assertEquals("read_file_part", repaired.tool.parameters.first().value)
        assertEquals(raw, repaired.tool.parameters.last().value)
        assertEquals(original.callId, repaired.callId)
    }

    @Test fun malformedAndAmbiguousProxyCallsAreNotRepaired() {
        assertNull(ToolCallRepairRouter.route(call("proxy",
            "tool_name" to "read_file", "params" to "not json")))
        assertNull(ToolCallRepairRouter.route(call("proxy",
            "tool_name" to "read_file", "tool_name" to "edit_file", "params" to """{"start_line":2}""")))
        assertNull(ToolCallRepairRouter.route(call("proxy",
            "tool_name" to "read_file")))
    }

    @Test fun repairLogDoesNotContainArgumentValuesOrRawResponse() {
        val original = call("read_file", "path" to "/secret/file", "start_line" to "42")
        val text = ToolCallRepairLogger.entry(ToolCallRepairRouter.route(original)!!, 123)
        val record = JSONObject(text)
        assertEquals("read_file", record.getString("originalToolName"))
        assertEquals("read_file_part", record.getString("targetToolName"))
        assertEquals("call-7", record.getString("callId"))
        assertEquals(ToolCallRepairRouter.READ_FILE_LINE_RANGE, record.getString("rule"))
        assertFalse(text.contains("/secret/file"))
        assertFalse(text.contains(original.rawText))
    }

    @Test fun repairedParametersAreCheckedAgainstActualToolDeclaration() {
        val original = call("read_file", "path" to "/tmp/a", "start_line" to "2", "unexpected" to "x")
        val routed = ToolCallRepairRouter.route(original)!!.invocation.tool
        val observer = ToolParameterObservation(routed, routed) { throw AssertionError(it) }
        observer.inspect(routed) { setOf("path", "environment", "start_line", "end_line") }
        assertEquals(listOf("unexpected"), observer.snapshot())
    }

    @Test fun terminalTimeoutAliasPreservesValueAndDoesNotAffectShell() {
        val original = call("super_admin:terminal", "command" to "sleep 120", "timeout" to "180000")
        val repair = ToolCallRepairRouter.route(original)!!
        assertEquals("180000", repair.invocation.tool.parameters.single { it.name == "timeoutMs" }.value)
        assertEquals(original.rawText, repair.invocation.rawText)
        assertNull(ToolCallRepairRouter.route(repair.invocation))
        assertNull(ToolCallRepairRouter.route(call("super_admin:shell", "timeout" to "180000")))
        assertNull(ToolCallRepairRouter.route(call("super_admin:terminal", "timeout" to "180seconds")))
    }

    @Test fun equalTimeoutAliasesAreDeduplicatedAndConflictsAreLeftAlone() {
        val same = call("super_admin:terminal", "timeout" to "180000", "timeoutMs" to "180000")
        assertEquals(listOf(ToolParameter("timeoutMs", "180000")),
            ToolCallRepairRouter.route(same)!!.invocation.tool.parameters)
        assertNull(ToolCallRepairRouter.route(call("super_admin:terminal",
            "timeout" to "180000", "timeoutMs" to "15000")))
        assertNull(ToolCallRepairRouter.route(call("super_admin:terminal",
            "timeout" to "180000", "timeout" to "30000")))
        for (value in listOf("0", "-1", "2999", "2147483648", "null", "true", "3000.5")) {
            assertNull(value, ToolCallRepairRouter.route(call("super_admin:terminal", "timeout" to value)))
        }
    }

    @Test fun composedRulesPreserveProxyTypesAndAreLoggedTogether() {
        val original = call("proxy", "tool_name" to "super_admin::terminal",
            "package_name" to "super_admin",
            "params" to """{"command":"echo \"test\"","timeout":180000,"background":false}""")
        val repair = ToolCallRepairRouter.route(original)!!
        assertEquals(listOf(ToolCallRepairRouter.TERMINAL_SEPARATOR,
            ToolCallRepairRouter.REDUNDANT_PACKAGE_NAME, ToolCallRepairRouter.TERMINAL_TIMEOUT), repair.rules)
        assertEquals("proxy", repair.invocation.tool.name)
        assertFalse(repair.invocation.tool.parameters.any { it.name == "package_name" })
        val args = JSONObject(repair.invocation.tool.parameters.single { it.name == "params" }.value)
        assertEquals("echo \"test\"", args.getString("command"))
        assertTrue(args.get("timeoutMs") is Number)
        assertEquals(180000, args.getInt("timeoutMs"))
        assertEquals(false, args.get("background"))
        assertNull(ToolCallRepairRouter.route(repair.invocation))
        val log = JSONObject(ToolCallRepairLogger.entry(repair, 123))
        assertEquals(3, log.getJSONArray("rules").length())
        assertEquals("super_admin::terminal", log.getString("originalToolName"))
        assertEquals("super_admin:terminal", log.getString("targetToolName"))
        assertFalse(log.toString().contains("echo"))
    }

    @Test fun redundantPackageIsRemovedOnlyWhenExactlyMatching() {
        for (proxy in listOf("proxy", "package_proxy")) {
            val original = call(proxy, "tool_name" to "reading_companion:get_local_files",
                "package_name" to "reading_companion", "params" to "{ }")
            val repair = ToolCallRepairRouter.route(original)!!
            assertEquals(proxy, repair.invocation.tool.name)
            assertEquals("{ }", repair.invocation.tool.parameters.single { it.name == "params" }.value)
            assertNull(ToolCallRepairRouter.route(call(proxy,
                "tool_name" to "reading_companion:get_local_files",
                "package_name" to "another_package", "params" to "{}")))
        }
        assertNull(ToolCallRepairRouter.route(call("package_proxy",
            "tool_name" to "read_file", "params" to """{"start_line":1}""")))
    }

    @Test fun separatorRuleIsAnExactAllowlist() {
        for (name in listOf("super_admin::terminal", "super_admin:::terminal")) {
            assertEquals(name, "super_admin:terminal",
                ToolCallRepairRouter.route(call(name))!!.invocation.tool.name)
        }
        for (name in listOf("super_admin::shell", "another::terminal",
            "super_admin::terminal_wait", "super_admin:terminal")) {
            assertNull(ToolCallRepairRouter.route(call(name)))
        }
    }

    @Test fun flattenedProxyParamsRestoreTheToolNameAndItsArguments() {
        val inner = """{"command":"echo hi","timeoutMs":150000}"""
        for (params in listOf(
            """$inner, {"tool_name": "super_admin:terminal"}""",
            """$inner, "tool_name": "super_admin:terminal"}""",
        )) {
            val repair = ToolCallRepairRouter.route(call("package_proxy", "params" to params))!!
            assertEquals(listOf(ToolCallRepairRouter.PROXY_FLATTENED_TOOL_NAME), repair.rules)
            assertEquals("package_proxy", repair.invocation.tool.name)
            assertEquals("super_admin:terminal",
                repair.invocation.tool.parameters.single { it.name == "tool_name" }.value)
            assertEquals(2, repair.invocation.tool.parameters.size)
            val args = JSONObject(repair.invocation.tool.parameters.single { it.name == "params" }.value)
            assertEquals("echo hi", args.getString("command"))
            assertEquals(150000, args.getInt("timeoutMs"))
            assertFalse(args.has("tool_name"))
            assertNull(ToolCallRepairRouter.route(repair.invocation))
            val log = JSONObject(ToolCallRepairLogger.entry(repair, 123))
            // No target was declared, so the log names the proxy tool the model actually called.
            assertEquals("package_proxy", log.getString("originalToolName"))
            assertEquals("super_admin:terminal", log.getString("targetToolName"))
            assertEquals(ToolCallRepairRouter.PROXY_FLATTENED_TOOL_NAME, log.getString("rule"))
            assertFalse(log.toString().contains("echo hi"))
        }
    }

    @Test fun flattenedProxyShapeIsRejectedWhenItWouldNeedAGuess() {
        val inner = """{"command":"echo hi"}"""
        for (params in listOf(
            // Two candidate names: the first object would keep a tool_name of its own.
            """$inner, {"tool_name": "super_admin:terminal"}, {"tool_name": "read_file"}""",
            // Missing separator, unseparated inside one object, and a brace inside the arguments.
            """$inner "tool_name": "super_admin:terminal"}""",
            """{"command":"echo hi","tool_name":"super_admin:terminal"}""",
            // An empty name cannot address anything.
            """$inner, {"tool_name": ""}""",
        )) {
            assertNull(params, ToolCallRepairRouter.route(call("package_proxy", "params" to params)))
        }
        // package_proxy still requires an unqualified name to be qualified by the recovered target.
        assertNull(ToolCallRepairRouter.route(call("package_proxy",
            "params" to """$inner, {"tool_name": "read_file"}""")))
        assertEquals("read_file", ToolCallRepairRouter.route(call("proxy",
            "params" to """$inner, {"tool_name": "read_file"}"""))!!.targetToolName)
    }

    @Test fun flattenedProxyParamsTolerateBracesInsideArguments() {
        // Braces inside the command text are common; they must not hide the shape from the rule.
        val params = """{"command":"echo ${'$'}{VAR}; awk '{print}'","timeoutMs":1}, "tool_name": "super_admin:terminal"}"""
        val repair = ToolCallRepairRouter.route(call("package_proxy", "params" to params))!!
        assertEquals(listOf(ToolCallRepairRouter.PROXY_FLATTENED_TOOL_NAME), repair.rules)
        assertEquals("super_admin:terminal",
            repair.invocation.tool.parameters.single { it.name == "tool_name" }.value)
        val args = JSONObject(repair.invocation.tool.parameters.single { it.name == "params" }.value)
        assertTrue(args.getString("command").contains("{VAR}"))
        assertTrue(args.getString("command").contains("'{print}'"))
        assertEquals(1, args.getInt("timeoutMs"))
        // An unclosed object stays unrepaired rather than being repaired with a truncated argument.
        assertNull(ToolCallRepairRouter.route(call("package_proxy",
            "params" to """{"command":"echo hi, "tool_name": "super_admin:terminal"}""")))
    }

    @Test fun flattenedProxyRepairLeavesTheEnvelopeTheProxyParserRequires() {
        // The real gate is ToolRegistration.parseProxyInvocation, which needs an Android context and
        // is not reachable from a JVM test; this pins exactly the shape that gate accepts: one
        // non-blank tool_name, one params, a qualified target for package_proxy, and params that is
        // a JSON object.
        val repair = ToolCallRepairRouter.route(call("package_proxy",
            "params" to """{"command":"echo hi"}, {"tool_name": "super_admin:terminal"}"""))!!
        val parameters = repair.invocation.tool.parameters
        assertEquals(2, parameters.size)
        assertEquals(setOf("tool_name", "params"), parameters.map { it.name }.toSet())
        val name = parameters.single { it.name == "tool_name" }.value
        assertTrue(name.isNotBlank())
        assertTrue(name.contains(':'))
        assertEquals(name, name.trim())
        assertTrue(parameters.single { it.name == "params" }.value.startsWith("{"))
    }

    @Test fun flattenedProxyTrailerToleratesWhitespaceOnBothShapes() {
        for (params in listOf(
            """{"command":"echo hi"} , "tool_name": "super_admin:terminal"}""",
            """{"command":"echo hi"}, "tool_name": "super_admin:terminal" }""",
            """{"command":"echo hi"}, {"tool_name": "super_admin:terminal" }""",
        )) {
            assertEquals(params, "super_admin:terminal",
                ToolCallRepairRouter.route(call("package_proxy", "params" to params))!!.targetToolName)
        }
        // The recovered name is trimmed; an escaped quote in it cannot be resolved and is left alone.
        assertEquals("super_admin:terminal", ToolCallRepairRouter.route(call("package_proxy",
            "params" to """{"command":"echo hi"}, "tool_name": " super_admin:terminal "}"""))!!.targetToolName)
        assertNull(ToolCallRepairRouter.route(call("package_proxy",
            "params" to """{"command":"echo hi"}, "tool_name": "super_admin:termi\"nal"}""")))
    }

    @Test fun flattenedProxyParamsNeverOverrideADeclaredToolName() {
        val original = call("package_proxy", "tool_name" to "super_admin:terminal",
            "params" to """{"command":"echo hi"}, {"tool_name": "read_file"}""")
        assertNull(ToolCallRepairRouter.route(original))
    }

    @Test fun flattenedProxyReplacesABlankToolNameInsteadOfDuplicatingIt() {
        for (blank in listOf("", "   ")) {
            val repair = ToolCallRepairRouter.route(call("package_proxy", "tool_name" to blank,
                "params" to """{"command":"echo hi"}, {"tool_name": "super_admin:terminal"}"""))!!
            assertEquals(listOf(ToolCallRepairRouter.PROXY_FLATTENED_TOOL_NAME), repair.rules)
            assertEquals("super_admin:terminal", repair.targetToolName)
            assertEquals(listOf("tool_name", "params"),
                repair.invocation.tool.parameters.map { it.name })
            assertEquals("super_admin:terminal",
                repair.invocation.tool.parameters.single { it.name == "tool_name" }.value)
            val args = JSONObject(repair.invocation.tool.parameters.single { it.name == "params" }.value)
            assertEquals("echo hi", args.getString("command"))
        }
        // A blank tool_name with nothing folded into params is a different fault and stays put.
        assertNull(ToolCallRepairRouter.route(call("package_proxy", "tool_name" to "",
            "params" to """{"command":"echo hi"}""")))
    }

    @Test fun memoryArgumentAliasRenamesNewTextToContent() {
        for (action in listOf("memory_change", "skill_create", "skill_write", "skill_patch",
            "skill_remove_file")) {
            val repair = ToolCallRepairRouter.route(call("memory_learning_action",
                "action" to action,
                "arguments" to """{"path":"SKILL.md","old_text":"x","new_text":"第一行\n第二行","force":true,"index":123456789012345678901234567890}"""))!!
            assertEquals(action, listOf(ToolCallRepairRouter.MEMORY_ARGUMENT_ALIAS), repair.rules)
            assertEquals("memory_learning_action", repair.invocation.tool.name)
            assertEquals(action,
                repair.invocation.tool.parameters.single { it.name == "action" }.value)
            val text = repair.invocation.tool.parameters.single { it.name == "arguments" }.value
            val args = JSONObject(text)
            // Every other value keeps its exact form: escaped text, booleans, large integers.
            assertEquals("第一行\n第二行", args.getString("content"))
            assertEquals("x", args.getString("old_text"))
            assertTrue(args.getBoolean("force"))
            assertTrue(text.contains("123456789012345678901234567890"))
            assertFalse(args.has("new_text"))
            assertNull(ToolCallRepairRouter.route(repair.invocation))
        }
    }

    @Test fun memoryArgumentAliasIsRejectedWhenAmbiguousOrAbsent() {
        // Both keys present, so renaming would have to choose between two stated values.
        assertNull(ToolCallRepairRouter.route(call("memory_learning_action", "action" to "memory_change",
            "arguments" to """{"old_text":"a","content":"b","new_text":"c"}""")))
        // Already canonical, not an object, a non-string alias, and a different tool.
        assertNull(ToolCallRepairRouter.route(call("memory_learning_action", "action" to "memory_change",
            "arguments" to """{"old_text":"a","content":"b"}""")))
        assertNull(ToolCallRepairRouter.route(call("memory_learning_action", "action" to "memory_change",
            "arguments" to "new_text=1")))
        assertNull(ToolCallRepairRouter.route(call("memory_learning_action", "action" to "memory_change",
            "arguments" to """{"new_text":7}""")))
        assertNull(ToolCallRepairRouter.route(call("memory_learning_action", "action" to "memory_change")))
        assertNull(ToolCallRepairRouter.route(call("other_tool",
            "arguments" to """{"new_text":"c"}""")))
        // A duplicate key is collapsed by a lenient parser, which would silently drop a stated edit.
        assertNull(ToolCallRepairRouter.route(call("memory_learning_action", "action" to "memory_change",
            "arguments" to """{"new_text":"a","new_text":"b"}""")))
        assertNull(ToolCallRepairRouter.route(call("memory_learning_action", "action" to "memory_change",
            "arguments" to """{"path":"a","path":"b","new_text":"c"}""")))
    }

    @Test fun memoryArgumentAliasOnlyAppliesToActionsThatReadContent() {
        for (action in listOf("memory_read", "history", "skill_read", "skill_list", "skill_delete", "")) {
            assertNull(action, ToolCallRepairRouter.route(call("memory_learning_action",
                "action" to action, "arguments" to """{"old_text":"x","new_text":"y"}""")))
        }
        // A repeated action parameter is left alone rather than resolved to one of the two values.
        assertNull(ToolCallRepairRouter.route(call("memory_learning_action",
            "action" to "memory_change", "action" to "history",
            "arguments" to """{"old_text":"x","new_text":"y"}""")))
        // The same contract is also exposed as learning_manage; memory_review has flat parameters.
        for (tool in listOf("memory_learning_action", "learning_manage")) {
            assertEquals(tool, listOf(ToolCallRepairRouter.MEMORY_ARGUMENT_ALIAS),
                ToolCallRepairRouter.route(call(tool, "action" to "memory_change",
                    "arguments" to """{"old_text":"x","new_text":"y"}"""))!!.rules)
        }
        assertNull(ToolCallRepairRouter.route(call("memory_review", "action" to "audit",
            "arguments" to """{"new_text":"y"}""")))
    }

    @Test fun memoryArgumentAliasAlsoAppliesThroughTheProxyEnvelope() {
        // The review tool is not a packaged tool, so only the CLI proxy can carry it.
        val repair = ToolCallRepairRouter.route(call("proxy",
            "tool_name" to "memory_learning_action",
            "params" to """{"action":"memory_change","arguments":"{\"old_text\":\"x\",\"new_text\":\"y\"}"}"""))!!
        assertEquals(listOf(ToolCallRepairRouter.MEMORY_ARGUMENT_ALIAS), repair.rules)
        assertEquals("proxy", repair.invocation.tool.name)
        val params = JSONObject(repair.invocation.tool.parameters.single { it.name == "params" }.value)
        val inner = JSONObject(params.getString("arguments"))
        assertEquals("y", inner.getString("content"))
        assertFalse(inner.has("new_text"))
    }

    @Test fun brokenOrDuplicateArgumentJsonCannotBeRewritten() {
        for (raw in listOf(
            """{"timeout":3000,"timeout":4000}""",
            """{"timeout":3000,"timeoutMs":3000,"timeoutMs":4000}""",
            """{'timeout':3000}""", """{"timeout":3000,}""", """{"timeout":3000} junk""",
            """{"timeout":3000,"background":TRUE}""",
            """{"timeout":3000,"command":unquoted}""",
            """{"timeout":3000,"extra":{"x":unquoted,"x":1}}""",
            """{"timeout":3000,"extra":[{"x":1,"x":2}]}""",
            "{\"timeout\":3000,\"command\":\"raw\ttab\"}",
            "{\"timeout\":3000,\"command\":\"raw\nnewline\"}",
            "{\"timeout\":3000,\"command\":\"raw\u0001control\"}",
        )) {
            assertNull(raw, ToolCallRepairRouter.route(call("proxy",
                "tool_name" to "super_admin:terminal", "params" to raw)))
        }
    }

    @Test fun ruleIdsAreUnique() {
        val ids = ToolCallRepairRules.ordered.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test fun renamingDoesNotRoundOtherJsonNumbers() {
        val original = call("proxy", "tool_name" to "super_admin:terminal",
            "params" to """{"timeout":3000,"command":"echo\nok","extra":{"large":123456789012345678901234567890,"decimal":0.12345678901234567890123456789}}""")
        val routed = ToolCallRepairRouter.route(original)!!.invocation
        val text = routed.tool.parameters.single { it.name == "params" }.value
        assertTrue(text.contains("123456789012345678901234567890"))
        assertTrue(text.contains("0.12345678901234567890123456789"))
        assertEquals("echo\nok", JSONObject(text).getString("command"))
    }
}
