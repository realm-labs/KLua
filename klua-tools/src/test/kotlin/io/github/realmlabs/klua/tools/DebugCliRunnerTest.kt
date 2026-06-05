package io.github.realmlabs.klua.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugCliRunnerTest {
    @Test
    fun `parser reads debugger commands`() {
        assertEquals(DebugCliCommand.Break("main.lua", 12), DebugCliCommandParser.parse("break main.lua:12"))
        assertEquals(DebugCliCommand.Run, DebugCliCommandParser.parse("run"))
        assertEquals(DebugCliCommand.Continue, DebugCliCommandParser.parse("continue"))
        assertEquals(DebugCliCommand.Next, DebugCliCommandParser.parse("next"))
        assertEquals(DebugCliCommand.Step, DebugCliCommandParser.parse("step"))
        assertEquals(DebugCliCommand.Out, DebugCliCommandParser.parse("out"))
        assertEquals(DebugCliCommand.Backtrace, DebugCliCommandParser.parse("bt"))
        assertEquals(DebugCliCommand.Locals, DebugCliCommandParser.parse("locals"))
        assertEquals(DebugCliCommand.Print("answer + 1"), DebugCliCommandParser.parse("print answer + 1"))
        assertEquals(DebugCliCommand.Quit, DebugCliCommandParser.parse("quit"))
    }

    @Test
    fun `parser rejects malformed commands`() {
        assertFailsWith<IllegalArgumentException> {
            DebugCliCommandParser.parse("")
        }
        assertFailsWith<IllegalArgumentException> {
            DebugCliCommandParser.parse("break main.lua")
        }
        assertFailsWith<IllegalArgumentException> {
            DebugCliCommandParser.parse("print ")
        }
        assertFailsWith<IllegalArgumentException> {
            DebugCliCommandParser.parse("unknown")
        }
    }

    @Test
    fun `runner sets source breakpoints`() {
        val runner = DebugCliRunner(DebugCliSession(program = "main.lua"))

        val result = runner.execute("break main.lua:4")

        assertTrue(result.success)
        assertEquals("breakpoint main.lua:4", result.message)
        assertTrue(runner.breakpointAt("main.lua", 4))
    }

    @Test
    fun `runner executes configured script with arguments`() {
        val runner = DebugCliRunner(
            session = DebugCliSession(program = "main.lua", args = listOf("left", "right")),
            readSource = { "return ..." },
        )

        val result = runner.execute(DebugCliCommand.Run)

        assertTrue(result.success)
        assertEquals("completed", result.message)
        assertEquals(listOf("left", "right"), result.values)
    }

    @Test
    fun `runner reports script load failures`() {
        val runner = DebugCliRunner(
            session = DebugCliSession(program = "missing.lua"),
            readSource = { error("missing source") },
        )

        val result = runner.execute(DebugCliCommand.Run)

        assertFalse(result.success)
        assertEquals("missing source", result.message)
    }

    @Test
    fun `runner evaluates print expressions`() {
        val runner = DebugCliRunner(DebugCliSession(program = "main.lua"))

        val result = runner.execute(DebugCliCommand.Print("40 + 2"))

        assertTrue(result.success)
        assertEquals("printed", result.message)
        assertEquals(listOf(42L), result.values)
    }

    @Test
    fun `runner handles control and unavailable frame commands`() {
        val runner = DebugCliRunner(DebugCliSession(program = "main.lua"))

        assertEquals(DebugCliResult(success = true, message = "continued"), runner.execute("continue"))
        assertEquals(DebugCliResult(success = true, message = "next"), runner.execute("next"))
        assertEquals(DebugCliResult(success = true, message = "step"), runner.execute("step"))
        assertEquals(DebugCliResult(success = true, message = "out"), runner.execute("out"))
        assertFalse(runner.execute("bt").success)
        assertFalse(runner.execute("locals").success)
    }
}
