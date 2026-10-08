package com.ai.assistance.operit.terminal

import android.graphics.Color
import android.util.Log
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.QueuedCommand
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.view.domain.OutputProcessor
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mockStatic

/** Exercises terminal events through the app's existing mocking test runtime. */
class CommandSessionCloseTest {
    @Test
    fun `closing detached session finishes running and queued callers exactly once`() {
        mockStatic(Log::class.java).use {
            mockStatic(Color::class.java).use {
                val events = mutableListOf<CommandExecutionEvent>()
                var queueAdvances = 0
                val processor = OutputProcessor(
                    onCommandExecutionEvent = events::add,
                    onCommandCompleted = { queueAdvances++ },
                )
                val session = TerminalSessionData(title = "test")
                val running = CommandHistoryItem("a", "$ ", "less file", "", true)
                running.outputPages.add("first page")
                session.currentExecutingCommand = running
                session.currentCommandOutput.append("last page")
                session.rawBuffer.append(" tail without newline")
                session.commandQueue.addAll(listOf(QueuedCommand("b", "touch b"), QueuedCommand("c", "touch c")))

                processor.finishClosedSession(session, "closed")
                // A read-job EOF racing with explicit close must not emit duplicates.
                processor.finishClosedSession(session.copy(), "closed again")

                assertEquals(listOf("a", "b", "c"), events.map { it.commandId })
                assertTrue(events.all { it.isCompleted && it.terminationReason == "session_closed" })
                assertTrue(events.first().outputChunk.contains("first page\nlast page"))
                assertTrue(events.first().outputChunk.contains("last page tail without newline\nclosed"))
                assertFalse(running.isExecuting)
                assertNull(session.currentExecutingCommand)
                assertTrue(session.commandQueue.isEmpty())
                assertEquals(0, queueAdvances)
            }
        }
    }
}
