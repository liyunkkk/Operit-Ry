package com.ai.assistance.operit.core.tools.system

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic commands and isolated in-memory task IDs; no app data or preferences are changed. */
@RunWith(AndroidJUnit4::class)
class ManagedShellAndroidTest {
    @Test fun standardProcessGroups() = runBlocking {
        exercise(ManagedShellLauncher { script ->
            LocalManagedShellProcess(ProcessBuilder("/system/bin/sh", "-c", script).start())
        })
    }

    @Test fun shizukuProcessGroups() = runBlocking {
        val executor = com.ai.assistance.operit.core.tools.system.shell.DebuggerShellExecutor(
            InstrumentationRegistry.getInstrumentation().targetContext)
        executor.initialize()
        assertTrue("Shizuku permission required for this device check", executor.hasPermission().granted)
        exercise(executor.managedLauncher())
    }

    @Test fun detachedShortLivedChildDoesNotBlockTaskCleanup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tasks = ShellTaskRegistry(scope)
        try {
            val id = tasks.start("shell-test", "setsid sh -c 'echo detached; sleep 8' & sleep 0.2", 1500) {
                ManagedShellLauncher { script ->
                    LocalManagedShellProcess(ProcessBuilder("/system/bin/sh", "-c", script).start())
                }
            }
            val result = tasks.poll("shell-test", id, 4000)
            assertEquals(result.toString(), "completed", result.status)
            assertTrue(result.output, result.output.contains("detached"))
        } finally { scope.cancel() }
    }

    private suspend fun exercise(launcher: ManagedShellLauncher) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tasks = ShellTaskRegistry(scope)
        try {
            val completed = tasks.start("shell-test", "printf out; printf err >&2; exit 7", 5000) { launcher }
            val result = tasks.poll("shell-test", completed, 8000)
            assertEquals(result.toString(), "failed", result.status)
            assertEquals(result.toString(), 7, result.exitCode)
            assertTrue(result.output.contains("out"))
            assertTrue(result.output.contains("err"))

            // A regular child must die with the task even if the shell is waiting on it.
            val slow = tasks.start("shell-test", "sleep 30 & child=\$!; echo child=\$child; wait", 1000) { launcher }
            val timeout = tasks.poll("shell-test", slow, 5000)
            assertEquals(timeout.toString(), "timed_out", timeout.status)
            val childPid = Regex("child=(\\d+)").find(timeout.output)?.groupValues?.get(1)
            assertNotNull(timeout.output, childPid)
            val check = tasks.start("shell-test", "if kill -0 $childPid 2>/dev/null; then cat /proc/$childPid/stat; else echo gone; fi", 3000) { launcher }
            val checked = tasks.poll("shell-test", check, 5000)
            assertEquals(checked.toString(), "completed", checked.status)
            // Android init can briefly leave a killed child as a zombie pending reap.
            assertTrue(checked.output, checked.output.contains("gone") || checked.output.contains(") Z "))

            val cancel = tasks.start("shell-test", "echo ready; sleep 30", 5000) { launcher }
            delay(250)
            assertEquals("cancelled", tasks.cancel("shell-test", cancel).status)

            val large = tasks.start("shell-test",
                "head -c 100000 /dev/zero | tr '\\000' x; printf tail", 5000) { launcher }
            val tail = tasks.poll("shell-test", large, 8000)
            assertEquals(tail.toString(), "completed", tail.status)
            assertTrue(tail.outputTruncated)
            assertTrue(tail.output.endsWith("tail"))
            assertTrue(tail.output.length <= 12000)
        } finally { scope.cancel() }
    }
}
