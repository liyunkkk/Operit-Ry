package com.ai.assistance.operit.core.tools.system

import com.ai.assistance.operit.terminal.CommandExecutionEvent
import com.ai.assistance.operit.core.tools.TerminalTaskResultData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class TerminalTaskRegistryTest {
    private fun event(id: String, text: String, done: Boolean = false) =
        CommandExecutionEvent(id, "session", text, done)

    @Test fun `bridge JSON preserves timeout and output semantics`() {
        val result = TerminalTaskResultData("run", "session", "timed_out", "tail", false,
            "execution_timeout", "tail_snapshot", true)
        val json = Json.parseToJsonElement(result.toJson()).jsonObject
        assertEquals("true", json.getValue("timedOut").jsonPrimitive.content)
        assertEquals("tail_snapshot", json.getValue("outputMode").jsonPrimitive.content)
    }

    @Test fun `yield and cancelled poll leave collector alive and final result is repeatable`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = TerminalTaskRegistry(scope)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var collectorCancelled = false
            val id = registry.start("session", 10_000) { id -> flow {
                try {
                    emit(event(id, "working"))
                    emit(event(id, "second line"))
                    started.complete(Unit)
                    finish.await()
                    emit(event(id, "finished", true))
                } finally { collectorCancelled = true }
            } }
            started.await()
            assertEquals("running", registry.poll(id, 5).status)
            assertEquals("working\nsecond line", registry.poll(id, 0).output)
            val poller = launch { registry.poll(id, 30_000) }
            yield()
            poller.cancelAndJoin()
            assertFalse(collectorCancelled)
            finish.complete(Unit)
            val result = registry.poll(id, 1_000)
            assertEquals("completed", result.status)
            assertEquals("finished", result.output)
            assertEquals(result, registry.poll(id, 0))
        } finally { scope.cancel() }
    }

    @Test fun `cancel targets only requested run and waits for collector cleanup`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = TerminalTaskRegistry(scope)
            val started = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val a = registry.start("session", 10_000) { flow {
                try { started.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { delay(10); cleaned.complete(Unit) } }
            } }
            val b = registry.start("session", 10_000) { flow { awaitCancellation() } }
            started.await()
            assertEquals("cancelled", registry.cancel(a).status)
            assertTrue(cleaned.isCompleted)
            assertEquals("running", registry.poll(b, 0).status)
        } finally { scope.cancel() }
    }

    @Test fun `execution deadline survives yield and reports timeout`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = TerminalTaskRegistry(scope)
            val id = registry.start("session", 30) { flow { awaitCancellation() } }
            assertEquals("timed_out", registry.poll(id, 1_000).status)
        } finally { scope.cancel() }
    }

    @Test fun `bounded output and registry evict only finished tasks`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = TerminalTaskRegistry(scope, capacity = 1, outputLimit = 8)
            val id = registry.start("session", 10_000) { id -> flow {
                emit(event(id, "0123456789", true))
            } }
            val result = registry.poll(id, 1_000)
            assertEquals("23456789", result.output)
            assertTrue(result.outputTruncated)
            val active = registry.start("session", 10_000) { flow { awaitCancellation() } }
            assertEquals(active, registry.latest("session"))
            try {
                registry.start("session", 10_000) { flow { awaitCancellation() } }
                fail("Must not evict active tasks")
            } catch (_: IllegalStateException) { }
            assertEquals("running", registry.poll(active, 0).status)
        } finally { scope.cancel() }
    }

    @Test fun `cancelled owner scope does not leave a permanently running entry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.cancel()
        val registry = TerminalTaskRegistry(scope)
        val id = registry.start("session", 1000) { flow { awaitCancellation() } }
        assertEquals("cancelled", registry.poll(id, 1000).status)
    }
}
