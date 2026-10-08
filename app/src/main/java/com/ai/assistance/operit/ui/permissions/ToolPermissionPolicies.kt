package com.ai.assistance.operit.ui.permissions

import android.content.Context
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.terminal.provider.filesystem.PRootMountMapping
import com.ai.assistance.operit.terminal.utils.SSHFileConnectionManager
import java.io.File

data class ToolPermissionReviewContext(
    val callerChatId: String? = null,
    val conversationLabel: String? = null,
    val workspacePath: String? = null,
    val workspaceEnv: String? = null,
    val parentModelConfigId: String? = null,
    val parentModelIndex: Int? = null,
    val timingScopeId: String? = null,
    val targetId: String? = null,
    val invocationIndex: Int = -1,
    val batchPosition: Int = 1,
    val batchSize: Int = 1,
    val deferCircuitBreaker: Boolean = false,
    val liveAssistantContent: CharSequence? = null,
)

/**
 * Resource-scoped workspace approval inspired by OpenCode's external_directory checks.
 *
 * The workspace branch of AUTO_REVIEW is deliberately fail-closed: only fixed file-tool schemas
 * and a conservative subset of shell commands can be proven to stay inside the bound workspace.
 * Everything else returns false, so those calls go to the independent reviewer (or to the manual
 * permission overlay) instead of being auto-approved.
 */
internal object WorkspaceToolPermissionPolicy {
    private const val ANDROID_ENV = "android"
    private const val LINUX_ENV = "linux"

    private val confinedSinglePathTools =
        setOf(
            "read_file",
            "read_file_part",
            "read_file_full",
            "read_file_binary",
            "list_files",
            "grep_code",
            "grep_context",
            "write_file",
            "write_file_binary",
            "file_exists",
            "make_directory",
            "file_info",
            "create_file",
            "edit_file",
            "apply_file",
        )

    // Persistent and hidden terminals are deliberately excluded. Their cwd/provider/interactive
    // state can change after permission evaluation, so only the stateless Android shell path can
    // currently be proven at execution scope.
    private val terminalTools = setOf("execute_shell")

    private val trustedAndroidExecutables =
        setOf(
            "/system/bin/ls",
            "/system/bin/cat",
            "/system/bin/head",
            "/system/bin/tail",
            "/system/bin/wc",
            "/system/bin/stat",
            "/system/bin/readlink",
            "/system/bin/realpath",
            "/system/bin/touch",
            "/system/bin/mkdir",
            "/system/bin/rmdir",
            "/system/bin/rm",
            "/system/bin/cp",
            "/system/bin/mv",
        )

    fun hasActiveWorkspace(workspacePath: String?): Boolean = !workspacePath.isNullOrBlank()

    fun isAutoApproved(
        context: Context,
        tool: AITool,
        workspacePath: String?,
        workspaceEnv: String?,
        callerChatId: String?,
    ): Boolean {
        if (!hasActiveWorkspace(workspacePath)) return false

        val normalizedName = tool.name.lowercase()
        if (
            normalizedName == "create_terminal_session" ||
            normalizedName == "start_terminal_task" ||
            normalizedName == "cancel_terminal_task" ||
            normalizedName == "execute_hidden_terminal_command" ||
                normalizedName == "execute_in_terminal_session" ||
                normalizedName == "execute_in_terminal_session_streaming" ||
                normalizedName == "super_admin:terminal" ||
                normalizedName == "super_admin:bash"
        ) {
            return false
        }

        val sshActive =
            runCatching {
                    SSHFileConnectionManager.getInstance(context).getFileSystemProvider() != null
                }
                .getOrDefault(true)
        val resolver: (String, String) -> String? = { path, environment ->
            resolveCanonicalPath(context, path, environment, sshActive)
        }
        return isAutoApproved(
            tool = tool,
            workspacePath = workspacePath,
            workspaceEnv = workspaceEnv,
            canonicalPathResolver = resolver,
            terminalCurrentDirectory = null,
        )
    }

    internal fun isAutoApproved(
        tool: AITool,
        workspacePath: String?,
        workspaceEnv: String?,
        canonicalPathResolver: (path: String, environment: String) -> String?,
        terminalCurrentDirectory: String? = null,
    ): Boolean {
        val root = workspacePath?.takeIf { it.isNotBlank() } ?: return false
        val boundEnvironment = normalizeEnvironment(workspaceEnv)
        val name = tool.name.lowercase()

        if (name in confinedSinglePathTools) {
            val target = tool.parameter("path") ?: return false
            val environment = normalizeEnvironment(tool.parameter("environment"))
            return isPathInsideWorkspace(
                path = target,
                pathEnvironment = environment,
                workspacePath = root,
                workspaceEnvironment = boundEnvironment,
                canonicalPathResolver = canonicalPathResolver,
            )
        }

        if (name == "delete_file") {
            if (tool.parameter("recursive")?.equals("true", ignoreCase = true) == true) return false
            val destination = tool.parameter("path") ?: return false
            val environment = normalizeEnvironment(tool.parameter("environment"))
            return isPathInsideWorkspace(
                destination,
                environment,
                root,
                boundEnvironment,
                canonicalPathResolver,
            )
        }

        if (name in terminalTools) {
            val command = tool.parameter("command") ?: return false
            val commandEnvironment = ANDROID_ENV
            if (commandEnvironment != boundEnvironment) return false
            return ShellWorkspaceScopeAnalyzer.isConfined(
                command = command,
                workspacePath = root,
                environment = commandEnvironment,
                initialDirectory = terminalCurrentDirectory,
                canonicalPathResolver = canonicalPathResolver,
                trustedExecutables = trustedAndroidExecutables,
            )
        }

        return false
    }

    private fun resolveCanonicalPath(
        context: Context,
        path: String,
        environment: String,
        sshActive: Boolean,
    ): String? {
        if (path.isBlank()) return null
        return runCatching {
                when (environment) {
                    LINUX_ENV -> {
                        if (sshActive || !path.startsWith('/')) return null
                        val ubuntuRoot =
                            File(
                                    context.filesDir,
                                    "usr/var/lib/proot-distro/installed-rootfs/ubuntu",
                                )
                                .canonicalFile
                        val chrootEnabled =
                            context
                                .getSharedPreferences("terminal_settings", Context.MODE_PRIVATE)
                                .getBoolean("chroot_enabled", false)
                        val mapped =
                            PRootMountMapping.mapLinuxPathToHostPath(
                                linuxPath = path,
                                ubuntuRoot = ubuntuRoot,
                                homeDir = context.filesDir.absolutePath,
                                appDataDir = context.applicationInfo.dataDir,
                                packageName = context.packageName,
                                chrootEnabled = chrootEnabled,
                            )
                        File(mapped).canonicalPath
                    }
                    ANDROID_ENV -> {
                        val file = File(path)
                        if (!file.isAbsolute) return null
                        file.canonicalPath
                    }
                    else -> null
                }
            }
            .getOrNull()
    }

    private fun isPathInsideWorkspace(
        path: String,
        pathEnvironment: String,
        workspacePath: String,
        workspaceEnvironment: String,
        canonicalPathResolver: (path: String, environment: String) -> String?,
    ): Boolean {
        if (pathEnvironment != workspaceEnvironment) return false
        if (workspaceEnvironment.startsWith("repo:")) {
            val root = normalizeVirtualPath(workspacePath) ?: return false
            val target = normalizeVirtualPath(path) ?: return false
            return containsPath(root, target, '/')
        }
        val root = canonicalPathResolver(workspacePath, workspaceEnvironment) ?: return false
        val target = canonicalPathResolver(path, pathEnvironment) ?: return false
        return containsPath(root, target, File.separatorChar)
    }

    private fun normalizeEnvironment(environment: String?): String {
        val raw = environment.orEmpty()
        return when {
            raw.equals(LINUX_ENV, ignoreCase = true) -> LINUX_ENV
            raw.startsWith("repo:", ignoreCase = true) -> {
                val bookmarkName = raw.trim().removePrefix("repo:").trim()
                "repo:$bookmarkName"
            }
            else -> ANDROID_ENV
        }
    }

    private fun normalizeVirtualPath(path: String): String? {
        if (path.isBlank()) return null
        val normalized = path.trim().replace('\\', '/')
        if (!normalized.startsWith('/') || normalized.startsWith("//")) return null
        val segments = mutableListOf<String>()
        for (segment in normalized.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return "/" + segments.joinToString("/")
    }

    private fun containsPath(root: String, target: String, separator: Char): Boolean {
        val normalizedRoot = root.trimEnd(separator).ifEmpty { separator.toString() }
        val normalizedTarget = target.trimEnd(separator).ifEmpty { separator.toString() }
        if (normalizedRoot == separator.toString()) return normalizedTarget.startsWith(separator)
        val ignoreCase = separator == '\\'
        return normalizedTarget.equals(normalizedRoot, ignoreCase = ignoreCase) ||
            normalizedTarget.startsWith("$normalizedRoot$separator", ignoreCase = ignoreCase)
    }

    private fun AITool.parameter(name: String): String? =
        parameters.firstOrNull { it.name == name }?.value?.takeIf { it.isNotEmpty() }
}

private object ShellWorkspaceScopeAnalyzer {
    private enum class TokenType {
        WORD,
        OPERATOR,
    }

    private data class Token(val type: TokenType, val value: String)

    fun isConfined(
        command: String,
        workspacePath: String,
        environment: String,
        initialDirectory: String?,
        canonicalPathResolver: (path: String, environment: String) -> String?,
        trustedExecutables: Set<String>,
    ): Boolean {
        if (containsDynamicShellSyntax(command)) return false
        val tokens = tokenize(command) ?: return false
        if (tokens.isEmpty()) return false
        val workspaceRoot = canonicalPathResolver(workspacePath, environment) ?: return false
        var cwd =
            initialDirectory
                ?.let { canonicalPathResolver(it, environment) }
                ?.takeIf { contains(workspaceRoot, it) }
        var index = 0

        while (index < tokens.size) {
            while (index < tokens.size && tokens[index].type == TokenType.OPERATOR) {
                if (tokens[index].value in setOf(">", ">>", "<", "<<", "|")) return false
                index += 1
            }
            if (index >= tokens.size) break

            val words = mutableListOf<String>()
            val redirects = mutableListOf<String>()
            while (index < tokens.size && tokens[index].value !in setOf("&&", "||", ";", "\n", "|")) {
                val token = tokens[index]
                if (token.value in setOf(">", ">>", "<", "<<")) {
                    if (token.value == "<<") return false
                    val next = tokens.getOrNull(index + 1) ?: return false
                    if (next.type != TokenType.WORD) return false
                    redirects += next.value
                    index += 2
                } else {
                    if (token.type != TokenType.WORD) return false
                    words += token.value
                    index += 1
                }
            }
            if (words.isEmpty()) return false

            val commandToken = words.first()
            if (commandToken !in trustedExecutables) return false

            for (redirect in redirects) {
                val resolved = resolveArgument(redirect, cwd, environment, canonicalPathResolver)
                    ?: return false
                if (!contains(workspaceRoot, resolved)) return false
            }

            val args = positionalArguments(words.drop(1)) ?: return false
            if (args.isEmpty()) return false
            for (arg in args) {
                val resolved =
                    resolveArgument(arg, cwd, environment, canonicalPathResolver) ?: return false
                if (!contains(workspaceRoot, resolved)) return false
            }

            if (index < tokens.size) {
                if (tokens[index].value == "|") return false
                index += 1
            }
        }

        return true
    }

    private fun positionalArguments(arguments: List<String>): List<String>? {
        val result = mutableListOf<String>()
        var optionsEnded = false
        for (argument in arguments) {
            if (!optionsEnded && argument == "--") {
                optionsEnded = true
                continue
            }
            if (!optionsEnded && argument.startsWith('-')) {
                return null
            }
            result += argument
        }
        return result
    }

    private fun resolveArgument(
        argument: String,
        cwd: String?,
        environment: String,
        canonicalPathResolver: (path: String, environment: String) -> String?,
    ): String? {
        if (argument.isBlank() || containsDynamicShellSyntax(argument)) return null
        val absolute =
            argument.startsWith('/') ||
                Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(argument)
        val path =
            when {
                absolute -> argument
                cwd != null -> File(cwd, argument).path
                else -> return null
            }
        return canonicalPathResolver(path, environment)
    }

    private fun containsDynamicShellSyntax(value: String): Boolean =
        value.startsWith('~') ||
            value.contains('\\') ||
            value.contains('$') ||
            value.contains('`') ||
            value.contains('*') ||
            value.contains('?') ||
            value.contains('[') ||
            value.contains(']') ||
            value.contains('{') ||
            value.contains('}')

    private fun contains(root: String, target: String): Boolean {
        val separator = File.separatorChar
        val normalizedRoot = root.trimEnd(separator)
        val normalizedTarget = target.trimEnd(separator)
        val ignoreCase = separator == '\\'
        return normalizedTarget.equals(normalizedRoot, ignoreCase = ignoreCase) ||
            normalizedTarget.startsWith("$normalizedRoot$separator", ignoreCase = ignoreCase)
    }

    private fun tokenize(command: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flushWord() {
            if (current.isNotEmpty()) {
                tokens += Token(TokenType.WORD, current.toString())
                current.setLength(0)
            }
        }

        var index = 0
        while (index < command.length) {
            val char = command[index]
            if (escaped) {
                current.append(char)
                escaped = false
                index += 1
                continue
            }
            if (char == '\\' && quote != '\'') {
                escaped = true
                index += 1
                continue
            }
            if (quote != null) {
                if (char == quote) quote = null else current.append(char)
                index += 1
                continue
            }
            if (char == '\'' || char == '"') {
                quote = char
                index += 1
                continue
            }
            if (char.isWhitespace()) {
                flushWord()
                if (char == '\n') tokens += Token(TokenType.OPERATOR, "\n")
                index += 1
                continue
            }
            if (char in charArrayOf('&', '|', ';', '>', '<')) {
                flushWord()
                val pair = command.substring(index, minOf(index + 2, command.length))
                val operator = if (pair in setOf("&&", "||", ">>", "<<")) pair else char.toString()
                if (operator == "&") return null
                tokens += Token(TokenType.OPERATOR, operator)
                index += operator.length
                continue
            }
            if (char in charArrayOf('(', ')')) return null
            current.append(char)
            index += 1
        }
        if (escaped || quote != null) return null
        flushWord()
        return tokens
    }
}
