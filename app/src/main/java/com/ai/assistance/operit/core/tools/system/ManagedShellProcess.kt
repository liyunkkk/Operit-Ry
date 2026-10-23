package com.ai.assistance.operit.core.tools.system

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** A private process, never the shared libsu shell or a Terminal session. */
internal interface ManagedShellProcess {
    val input: InputStream
    val output: OutputStream
    val errors: InputStream
    fun exitCode(): Int?
    fun destroy()
}

internal class LocalManagedShellProcess(private val process: Process) : ManagedShellProcess {
    override val input get() = process.inputStream
    override val output get() = process.outputStream
    override val errors get() = process.errorStream
    override fun exitCode(): Int? = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
    override fun destroy() {
        process.destroy()
        if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }
}

/** Must launch every process with the same permission identity captured when the task starts. */
internal fun interface ManagedShellLauncher {
    fun launch(script: String): ManagedShellProcess
    fun prepareCommand(command: String): String = command
}

internal object ManagedShellScript {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    fun supervisor(command: String, token: String): String {
        // stdout is a private two-line control protocol. User output (both streams) goes to stderr.
        // Keep the group leader alive after completion, so its ID cannot be recycled before cleanup.
        // The user command cannot start until the app knows the group ID and grants the handshake.
        val script = """
            task_token=${quote(token)}
            printf '%s\n' "${'$'}${'$'}"
            IFS= read -r gate || exit 125
            [ "${'$'}gate" = go ] || exit 125
            /system/bin/sh -c ${quote(command)} </dev/null 1>&2
            result=${'$'}?
            printf '%s\n' "${'$'}result"
            IFS= read -r gate
        """.trimIndent()
        return "exec /system/bin/setsid -w /system/bin/sh -c ${quote(script)}"
    }

    fun killGroup(pid: Int, token: String): String {
        require(pid > 1)
        // A delayed su authorization must never kill a recycled process-group ID.
        return "/system/bin/grep -F -q ${quote(token)} /proc/$pid/cmdline && kill -KILL -- -$pid"
    }
}
