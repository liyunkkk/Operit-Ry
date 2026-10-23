package com.ai.assistance.operit.core.tools.system

import com.ai.assistance.operit.core.tools.ShellTaskResultData
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*

/** Request waits never own the task lifetime; each task owns its isolated Android process group. */
internal class ShellTaskRegistry(
    private val scope: CoroutineScope,
    private val capacity: Int = 16,
    private val outputLimit: Int = 12_000,
) {
    private class Run(val id: String, val owner: String?, val command: String) {
        val done = CompletableDeferred<Unit>()
        @Volatile var cancelRequested = false
        var status = "running"
        var reason: String? = null
        var exitCode: Int? = null
        var output = ""
        var truncated = false
    }
    private val runs = linkedMapOf<String, Run>()

    @Synchronized
    fun start(owner: String?, command: String, timeoutMs: Long, launcher: () -> ManagedShellLauncher): String {
        while (runs.size >= capacity) {
            val expired = runs.entries.firstOrNull { it.value.done.isCompleted }
                ?: error("Too many active shell tasks; wait for or cancel a task first.")
            runs.remove(expired.key)
        }
        val run = Run(UUID.randomUUID().toString(), owner, command)
        runs[run.id] = run
        scope.launch { execute(run, timeoutMs, launcher) }
        return run.id
    }

    private suspend fun execute(run: Run, timeoutMs: Long, factory: () -> ManagedShellLauncher) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var process: ManagedShellProcess? = null
        var launcher: ManagedShellLauncher? = null
        var groupId: Int? = null
        var outputThread: Thread? = null
        var controlThread: Thread? = null
        val control = ArrayBlockingQueue<String>(3)
        try {
            launcher = factory()
            process = launcher.launch(ManagedShellScript.supervisor(launcher.prepareCommand(run.command), run.id))
            val active = process
            outputThread = thread(isDaemon = true, name = "shell-output") {
                try {
                    active.errors.reader().use { reader ->
                        val buffer = CharArray(2048)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            synchronized(this) {
                                val text = run.output + String(buffer, 0, count)
                                run.truncated = run.truncated || text.length > outputLimit
                                run.output = text.takeLast(outputLimit)
                            }
                        }
                    }
                } catch (_: Exception) { /* Closing pipes during cleanup unblocks the reader. */ }
            }
            controlThread = thread(isDaemon = true, name = "shell-control") {
                try {
                    // Only the supervisor writes here, but still bound each line.
                    repeat(2) { control.offer(readControlLine(active.input)) }
                } catch (_: Exception) { control.offer("protocol_error") }
            }
            var permitted = false
            while (true) {
                val line = control.poll()
                if (line != null) {
                    if (groupId == null) {
                        groupId = line.toIntOrNull()?.takeIf { it > 1 }
                            ?: error("Shell supervisor could not establish an isolated process group.")
                    } else {
                        val code = line.toIntOrNull() ?: error("Invalid shell completion result.")
                        synchronized(this) {
                            run.exitCode = code
                            run.status = if (code == 0) "completed" else "failed"
                            run.reason = if (code == 0) null else "nonzero_exit"
                        }
                        break
                    }
                }
                if (run.cancelRequested || System.nanoTime() >= deadline) {
                    synchronized(this) {
                        run.status = if (run.cancelRequested) "cancelled" else "timed_out"
                        run.reason = if (run.cancelRequested) "cancelled" else "execution_timeout"
                    }
                    break
                }
                if (groupId != null && !permitted) {
                    active.output.write("go\n".toByteArray())
                    active.output.flush()
                    permitted = true
                }
                if (active.exitCode() != null) error("Shell supervisor exited before completion (setsid is required).")
                delay(20)
            }
        } catch (e: Exception) {
            synchronized(this) {
                run.status = "failed"
                run.reason = e.message ?: e.javaClass.simpleName
            }
        } finally {
            // Do not expose a terminal result until cleanup has run. A completed command may have
            // left background children holding its output pipes; kill the whole owned group.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (groupId != null && launcher != null) {
                        val killer = launcher.launch(ManagedShellScript.killGroup(groupId, run.id))
                        try {
                            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                            while (killer.exitCode() == null && System.nanoTime() < end) delay(20)
                            check(killer.exitCode() == 0) { "Shell process-group cleanup failed." }
                        } finally { closeProcess(killer) }
                    }
                } catch (e: Exception) {
                    synchronized(this@ShellTaskRegistry) {
                        run.status = "failed"
                        run.reason = "cleanup_failed: ${e.message}; previous=${run.reason}"
                    }
                } finally {
                    process?.let { active ->
                        // Give output readers a bounded chance to drain after group termination.
                        outputThread?.join(500)
                        closeProcess(active)
                        controlThread?.join(200)
                    }
                    run.done.complete(Unit)
                }
            }
        }
    }

    private fun readControlLine(input: InputStream): String {
        val result = StringBuilder()
        repeat(32) {
            val ch = input.read()
            check(ch >= 0) { "Supervisor pipe closed." }
            if (ch == 10) return result.toString()
            result.append(ch.toChar())
        }
        error("Oversized supervisor message.")
    }

    private fun closeProcess(process: ManagedShellProcess) {
        runCatching { process.destroy() }
        runCatching { process.output.close() }
        runCatching { process.input.close() }
        runCatching { process.errors.close() }
    }

    @Synchronized private fun get(owner: String?, id: String): Run =
        runs[id]?.takeIf { it.owner == owner }
            ?: error("Shell task not found or expired; do not rerun the command automatically.")

    suspend fun poll(owner: String?, id: String, yieldMs: Long, awaitInput: (suspend () -> Unit)? = null): ShellTaskResultData {
        val run = get(owner, id)
        if (yieldMs > 0) withTimeoutOrNull(yieldMs.coerceAtMost(30_000)) {
            if (awaitInput == null) run.done.await()
            else coroutineScope {
                val input = async { awaitInput() }
                try {
                    kotlinx.coroutines.selects.select<Unit> {
                        run.done.onAwait { }
                        input.onAwait { }
                    }
                } finally { input.cancelAndJoin() }
            }
        }
        return synchronized(this) {
            ShellTaskResultData(run.id, run.command,
                if (!run.done.isCompleted) {
                    if (run.cancelRequested || run.status != "running") "cancelling" else "running"
                } else run.status,
                run.output, run.truncated, run.reason,
                if (run.done.isCompleted) run.exitCode else null,
                run.reason == "execution_timeout", "tail_snapshot")
        }
    }

    suspend fun cancel(owner: String?, id: String): ShellTaskResultData {
        val run = get(owner, id)
        if (!run.done.isCompleted) run.cancelRequested = true
        return poll(owner, id, 3_000)
    }
}
