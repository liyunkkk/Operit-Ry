package com.ai.assistance.operit.terminal

import android.graphics.Color
import android.util.Log
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import com.ai.assistance.operit.terminal.view.domain.OutputProcessor
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class TerminalCommandProtocolTest {
    @Test fun `split control sequences preserve output and require the matching completion token`() {
        mockStatic(Log::class.java).use {
            mockStatic(Color::class.java).use {
                val manager = SessionManager(mock(TerminalManager::class.java))
                val original = manager.createNewSession(terminalType = TerminalType.LOCAL, automation = true)
                manager.updateSession(original.id) { it.copy(initState = SessionInitState.READY) }
                val session = manager.getSession(original.id)!!
                val events = mutableListOf<CommandExecutionEvent>()
                var advanced = 0
                val processor = OutputProcessor(events::add, onCommandCompleted = { advanced++ })
                session.currentExecutingCommand = CommandHistoryItem("run", "$ ", "watch; sleep 3; false", "", true)
                session.commandLifecycle.protocol.ready = true
                session.commandLifecycle.protocol.command("watch; sleep 3; false")
                val token = session.commandLifecycle.protocol.activeToken!!
                fun output(text: String) = processor.processOutput(session.id, text, manager)

                output("before")
                output("\u001b[?104")
                output("9h\u001b[2J\u001b[Hbuilding")
                assertTrue(events.any { it.screen?.contains("building") == true })
                assertFalse(events.any { it.isCompleted })
                output("\u001b[?1049l")
                output("not done #\n")
                assertEquals(0, advanced)
                output("value #\r")
                output("\u001b]633;Operit;deadbeef;0\u0007")
                assertEquals(0, advanced)
                output("\u001b]633;Operit;$token;")
                output("1\u0007")
                val end = events.single { it.isCompleted }
                assertEquals(1, end.exitCode)
                assertNull(end.sessionExitCode)
                assertFalse(end.outputChunk.contains('\u001b'))
                assertTrue(end.outputChunk.contains("before"))
                assertTrue(end.outputChunk.contains("value #"))
                assertEquals(1, advanced)
            }
        }
    }

    @Test fun `CR and unterminated output publish a replaceable screen during execution`() {
        mockStatic(Log::class.java).use {
            mockStatic(Color::class.java).use {
                val manager = SessionManager(mock(TerminalManager::class.java))
                val original = manager.createNewSession(terminalType = TerminalType.LOCAL, automation = true)
                manager.updateSession(original.id) { it.copy(initState = SessionInitState.READY) }
                val session = manager.getSession(original.id)!!
                session.currentExecutingCommand = CommandHistoryItem("run", "$ ", "progress", "", true)
                val events = mutableListOf<CommandExecutionEvent>()
                val processor = OutputProcessor(events::add)
                processor.processOutput(session.id, "10%\r", manager)
                processor.processOutput(session.id, "\u001b[2K20%", manager)
                assertTrue(events.last().screen.orEmpty().contains("20%"))
                assertFalse(events.last().screen.orEmpty().contains("10%"))
                assertFalse(events.any { it.isCompleted })
                session.commandLifecycle.protocol.ready = true
                session.commandLifecycle.protocol.command("progress")
                val token = session.commandLifecycle.protocol.activeToken
                processor.processOutput(session.id, "\r\u001b[2K30%\r\u001b]633;Operit;$token;0\u0007", manager)
                assertTrue(events.last { it.isCompleted }.screen.orEmpty().contains("30%"))
            }
        }
    }
}
