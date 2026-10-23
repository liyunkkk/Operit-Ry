package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.ADBResultData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.core.tools.system.ShizukuAuthorizer
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import com.ai.assistance.operit.core.tools.system.ShellTaskRegistry

/**
 * Tool for executing ADB commands directly. This provides direct access to ADB shell commands for
 * system operations. Note: This requires Shizuku service to be running with proper permissions.
 */
open class StandardShellToolExecutor(private val context: Context) {

    companion object {
        private const val TAG = "ADBToolExecutor"
        private val tasks = ShellTaskRegistry(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    fun task(tool: AITool, action: String): ToolResult {
        val owner = com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
            .currentToolRuntimeContext()?.callerChatId
        val inbox = owner?.let {
            com.ai.assistance.operit.api.chat.ChatRuntimeHolder.getInstance(context)
                .getCore(com.ai.assistance.operit.api.chat.ChatRuntimeSlot.MAIN)
                .getMessageProcessingDelegate().turnInputInbox(it)
        }
        return runBlocking {
            try {
                fun param(name: String) = tool.parameters.find { it.name == name }?.value
                fun number(name: String, default: Long, range: LongRange): Long =
                    (param(name)?.let { it.toLongOrNull() ?: error("$name must be an integer.") } ?: default)
                        .also { require(it in range) { "$name must be in $range." } }
                val yieldMs = number("yield_ms", 10_000, 0L..30_000L)
                val id = if (action == "start") {
                    val validation = validateParameters(tool)
                    require(validation.valid) { validation.errorMessage ?: "Invalid command." }
                    val command = requireNotNull(param("command"))
                    val timeoutMs = number("timeout_ms", 120_000, 1_000L..1_800_000L)
                    tasks.start(owner, command, timeoutMs) { AndroidShellExecutor.managedShellLauncher() }
                } else requireNotNull(param("run_id")) { "run_id is required." }
                val result = if (action == "cancel") tasks.cancel(owner, id)
                    else tasks.poll(owner, id, yieldMs, inbox?.let { { it.awaitInput() } })
                ToolResult(tool.name, true, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult(tool.name, false, StringResultData(""), e.message)
            }
        }
    }

    fun invoke(tool: AITool): ToolResult {
        if (tool.parameters.any { it.name == "managed" && it.value == "true" }) return task(tool, "start")
        // Validate parameters
        val validationResult = validateParameters(tool)
        if (!validationResult.valid) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = validationResult.errorMessage
            )
        }

        val command = tool.parameters.find { it.name == "command" }?.value ?: ""
        // Timeout parameter is kept for API compatibility but not used by AdbCommandExecutor

        return try {
            // Use AdbCommandExecutor to execute the command
            val result = runBlocking { AndroidShellExecutor.executeShellCommand(command) }

            if (result.success) {
                ToolResult(
                        toolName = tool.name,
                        success = true,
                        result =
                                ADBResultData(
                                        command = command,
                                        output = result.stdout,
                                        exitCode = result.exitCode
                                )
                )
            } else {
                // Combine stdout and stderr for error reporting
                val errorOutput =
                        if (result.stderr.isNotEmpty()) {
                            "${result.stderr.trim()}\n${result.stdout.trim()}"
                        } else {
                            result.stdout.trim()
                        }

                ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error =
                                "ADB command execution failed (exit code: ${result.exitCode}): $errorOutput"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error executing ADB command", e)
            ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "ADB command execution failed: ${e.message}"
            )
        }
    }

    /** Validates the parameters for the ADB tool. */
    fun validateParameters(tool: AITool): ToolValidationResult {
        val command = tool.parameters.find { it.name == "command" }?.value

        return when {
            command.isNullOrBlank() -> {
                ToolValidationResult(valid = false, errorMessage = "Command parameter is required")
            }
            command.contains("rm -rf") || command.contains("format") -> {
                ToolValidationResult(
                        valid = false,
                        errorMessage = "Potentially dangerous command detected"
                )
            }
            else -> {
                ToolValidationResult(valid = true)
            }
        }
    }
}
