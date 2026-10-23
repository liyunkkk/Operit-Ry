package com.ai.assistance.operit.core.tools.system

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import com.ai.assistance.operit.core.tools.ShellTaskResultData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ShellTaskRegistryTest {
    private class FakeProcess(output: String = "") : ManagedShellProcess {
        private val controlWriter = PipedOutputStream()
        override val input = PipedInputStream(controlWriter)
        override val output = ByteArrayOutputStream()
        override val errors = ByteArrayInputStream(output.toByteArray())
        @Volatile var destroyed = false
        init { controlWriter.write("12345\n".toByteArray()); controlWriter.flush() }
        fun complete(code: Int) { controlWriter.write("$code\n".toByteArray()); controlWriter.flush() }
        override fun exitCode(): Int? = if (destroyed) 137 else null
        override fun destroy() { destroyed = true; controlWriter.close() }
    }
    private class Fixture(text: String = "") {
        val process = FakeProcess(text)
        val started = CompletableDeferred<Unit>()
        @Volatile var kills = 0
        val launcher = ManagedShellLauncher { script ->
            if (script.startsWith("/system/bin/grep ")) {
                kills++
                process.destroy()
                object : ManagedShellProcess {
                    override val input = ByteArrayInputStream(byteArrayOf())
                    override val output = ByteArrayOutputStream()
                    override val errors = ByteArrayInputStream(byteArrayOf())
                    override fun exitCode() = 0
                    override fun destroy() {}
                }
            } else { started.complete(Unit); process }
        }
    }
    private fun checkTask(block: suspend (ShellTaskRegistry) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { withTimeout(8_000) { block(ShellTaskRegistry(scope, outputLimit = 64)) } }
        finally { scope.cancel() }
    }

    @Test fun `bridge includes output mode and null exit code for running tasks`() {
        val data = ShellTaskResultData("id", "cmd", "running", "", false, null, null, false, "tail_snapshot")
        val json = Json.parseToJsonElement(data.toJson()).jsonObject
        assertEquals("tail_snapshot", json.getValue("outputMode").jsonPrimitive.content)
        assertEquals("null", json.getValue("exitCode").toString())
    }

    @Test fun `yield and new input do not cancel execution`() = checkTask { registry ->
        val f = Fixture()
        val id = registry.start("a", "sleep", 5000) { f.launcher }
        f.started.await()
        assertEquals("running", registry.poll("a", id, 0).status)
        assertEquals("running", registry.poll("a", id, 3000) { }.status)
        assertEquals(0, f.kills)
        f.process.complete(0)
        assertEquals("completed", registry.poll("a", id, 3000).status)
        assertEquals(1, f.kills)
    }

    @Test fun `timeout kills process group and preserves bounded partial output`() = checkTask { registry ->
        val f = Fixture("x".repeat(20000) + "tail")
        val id = registry.start("a", "slow", 200) { f.launcher }
        val result = registry.poll("a", id, 3000)
        assertEquals("timed_out", result.status)
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertTrue(result.outputTruncated)
        assertEquals(64, result.output.length)
        assertTrue(result.output.endsWith("tail"))
        assertEquals(1, f.kills)
    }

    @Test fun `cancel owns only its task and rejects other chats`() = checkTask { registry ->
        val a = Fixture()
        val b = Fixture()
        val first = registry.start("a", "one", 5000) { a.launcher }
        val second = registry.start("b", "two", 5000) { b.launcher }
        a.started.await(); b.started.await()
        try { registry.cancel("b", first); fail("Cross-chat cancellation was allowed") }
        catch (_: IllegalStateException) {}
        assertEquals("cancelled", registry.cancel("a", first).status)
        assertEquals(0, b.kills)
        b.process.complete(7)
        val result = registry.poll("b", second, 3000)
        assertEquals("failed", result.status)
        assertEquals(7, result.exitCode)
        assertEquals(result, registry.poll("b", second, 0))
    }

    @Test fun `cancel before handshake never authorizes user command`() = checkTask { registry ->
        val f = Fixture()
        val ready = java.util.concurrent.CountDownLatch(1)
        val id = registry.start("a", "danger", 5000) {
            ready.await(2, java.util.concurrent.TimeUnit.SECONDS)
            f.launcher
        }
        val cancel = CoroutineScope(currentCoroutineContext()).async { registry.cancel("a", id) }
        delay(30)
        ready.countDown()
        assertEquals("cancelled", cancel.await().status)
        assertEquals("", f.process.output.toString())
    }

    @Test fun `cleanup failure is not reported as successful cancellation`() = checkTask { registry ->
        val f = Fixture()
        val id = registry.start("a", "slow", 200) {
            ManagedShellLauncher { script ->
                if (script.startsWith("/system/bin/grep ")) error("permission revoked")
                f.launcher.launch(script)
            }
        }
        val result = registry.poll("a", id, 3000)
        assertEquals("failed", result.status)
        assertTrue(result.terminationReason!!.contains("cleanup_failed"))
    }
}
