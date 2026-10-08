package com.ai.assistance.operit.core.tools.system

import com.ai.assistance.operit.terminal.CommandExecutionEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.util.UUID

/** Owns command collectors independently of any individual start/poll request. */
internal class TerminalTaskRegistry(
    private val scope: CoroutineScope,
    private val capacity: Int = 32,
    private val outputLimit: Int = 64_000,
) {
    internal data class Snapshot(
        val runId: String,
        val sessionId: String,
        val status: String,
        val output: String,
        val outputTruncated: Boolean,
        val terminationReason: String?,
    )

    private class Run(val id: String, val sessionId: String) {
        val done = CompletableDeferred<Unit>()
        lateinit var job: Job
        var status = "running"
        var output = ""
        var truncated = false
        var reason: String? = null
    }

    private val runs = linkedMapOf<String, Run>()

    @Synchronized
    fun start(sessionId: String, timeoutMs: Long, collect: (String) -> Flow<CommandExecutionEvent>): String {
        while (runs.size >= capacity) {
            val expired = runs.entries.firstOrNull { it.value.done.isCompleted }
                ?: error("Too many active terminal tasks; wait for or cancel an existing task.")
            runs.remove(expired.key)
        }
        val id = UUID.randomUUID().toString()
        val run = Run(id, sessionId)
        runs[id] = run
        run.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(timeoutMs) {
                    collect(id).collect { event ->
                        synchronized(this@TerminalTaskRegistry) {
                            val text = if (event.isCompleted) {
                                event.outputChunk.ifEmpty { run.output }
                            } else if (run.output.isEmpty()) event.outputChunk
                            else run.output + "\n" + event.outputChunk
                            run.truncated = run.truncated || text.length > outputLimit
                            run.output = text.takeLast(outputLimit)
                            if (event.isCompleted) {
                                run.reason = event.terminationReason
                                run.status = when (event.terminationReason) {
                                    null -> "completed"
                                    "cancelled" -> "cancelled"
                                    else -> "failed"
                                }
                            }
                        }
                    }
                }
                synchronized(this@TerminalTaskRegistry) {
                    if (run.status == "running") {
                        run.status = "failed"
                        run.reason = "missing_completion"
                    }
                }
            } catch (e: TimeoutCancellationException) {
                finish(run, "timed_out", "execution_timeout")
            } catch (e: CancellationException) {
                finish(run, "cancelled", "cancelled")
            } catch (e: Exception) {
                finish(run, "failed", e.message ?: e.javaClass.simpleName)
            } finally {
                run.done.complete(Unit)
            }
        }
        run.job.invokeOnCompletion { cause ->
            synchronized(this) {
                if (!run.done.isCompleted) {
                    if (run.status == "running" || run.status == "cancelling") {
                        run.status = if (cause is CancellationException) "cancelled" else "failed"
                        run.reason = cause?.message ?: "missing_completion"
                    }
                    run.done.complete(Unit)
                }
            }
        }
        run.job.start()
        return id
    }

    @Synchronized private fun finish(run: Run, status: String, reason: String) {
        run.status = status
        run.reason = reason
    }

    @Synchronized private fun get(id: String): Run =
        runs[id] ?: error("Terminal task not found or expired; do not rerun the command automatically.")

    @Synchronized fun latest(sessionId: String): String? =
        runs.values.lastOrNull { it.sessionId == sessionId }?.id

    suspend fun poll(id: String, yieldMs: Long): Snapshot {
        val run = get(id)
        if (yieldMs > 0) withTimeoutOrNull(yieldMs.coerceAtMost(30_000L)) { run.done.await() }
        return synchronized(this) {
            Snapshot(run.id, run.sessionId, run.status, run.output.takeLast(12_000),
                run.truncated || run.output.length > 12_000, run.reason)
        }
    }

    suspend fun cancel(id: String): Snapshot {
        val run = get(id)
        synchronized(this) { if (!run.done.isCompleted) run.status = "cancelling" }
        // executeCommandFlow's finally cancels precisely this command, including queued work.
        run.job.cancel()
        withTimeoutOrNull(10_000L) { run.done.await() }
        return poll(id, 0)
    }
}
