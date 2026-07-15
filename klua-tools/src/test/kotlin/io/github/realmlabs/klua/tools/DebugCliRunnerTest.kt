package io.github.realmlabs.klua.tools

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.api.LuaConfig
import io.github.realmlabs.klua.debug.DebugFrameView
import io.github.realmlabs.klua.debug.DebugVariable
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
    fun `runner rejects control and frame commands before a program is run`() {
        val runner = DebugCliRunner(DebugCliSession(program = "main.lua"))

        assertEquals(DebugCliResult(success = false, message = "program has not been run"), runner.execute("continue"))
        assertEquals(DebugCliResult(success = false, message = "program has not been run"), runner.execute("next"))
        assertEquals(DebugCliResult(success = false, message = "program has not been run"), runner.execute("step"))
        assertEquals(DebugCliResult(success = false, message = "program has not been run"), runner.execute("out"))
        assertFalse(runner.execute("bt").success)
        assertFalse(runner.execute("locals").success)
    }

    @Test
    fun `runner stops live execution and serves backtrace locals evaluation and stepping`() {
        val runner = DebugCliRunner(
            session = DebugCliSession(program = "main.lua"),
            readSource = {
                """
                local captured = 10
                local value = 1
                value = value + captured
                return value
                """.trimIndent()
            },
        )
        runner.execute("break main.lua:3")

        val stopped = runner.execute("run")

        assertEquals(DebugCliResult(success = true, message = "stopped breakpoint main.lua:3"), stopped)
        assertEquals(
            DebugCliResult(success = true, message = "backtrace", values = listOf("#0 main.lua:3")),
            runner.execute("bt"),
        )
        assertTrue(runner.execute("locals").values.containsAll(listOf("captured = 10", "value = 1")))
        assertEquals(
            DebugCliResult(success = true, message = "printed", values = listOf(11L)),
            runner.execute("print value + captured"),
        )
        assertEquals(
            DebugCliResult(success = true, message = "stopped step main.lua:4"),
            runner.execute("next"),
        )
        assertEquals(
            DebugCliResult(success = true, message = "completed", values = listOf(11L)),
            runner.execute("continue"),
        )
        assertFalse(runner.execute("bt").success)
    }

    @Test
    fun `runner debugs a packaged bytecode chunk using its original source name`() {
        val bytecode = Lua.create().compileBytecode(
            """
            local value = 41
            value = value + 1
            return value
            """.trimIndent(),
            "packed.lua",
        )
        val runner = DebugCliRunner(
            session = DebugCliSession(program = "main.kluac"),
            readBytecode = { bytecode },
        )
        runner.execute("break packed.lua:2")

        assertEquals(
            DebugCliResult(success = true, message = "stopped breakpoint packed.lua:2"),
            runner.execute("run"),
        )
        assertEquals(
            DebugCliResult(success = true, message = "completed", values = listOf(42L)),
            runner.execute("continue"),
        )
    }

    @Test
    fun `runner cannot attach when debugging is disabled in the runtime config`() {
        val runner = DebugCliRunner(
            session = DebugCliSession(program = "main.lua"),
            luaFactory = { Lua.create(LuaConfig(debugEnabled = false)) },
            readSource = { "return 1" },
        )

        val result = runner.execute("run")

        assertFalse(result.success)
        assertEquals("debugging is disabled for this Lua runtime", result.message)
    }

    @Test
    fun `runner renders supplied backtrace and locals`() {
        val runner = DebugCliRunner(
            session = DebugCliSession(program = "main.lua"),
            frameProvider = DebugCliFrameProvider {
                listOf(
                    DebugFrameView(
                        level = 0,
                        sourceName = "main.lua",
                        line = 8,
                        locals = listOf(
                            DebugVariable("answer", 42L, "number", "42"),
                            DebugVariable("name", "KLua", "string", "KLua"),
                        ),
                    ),
                    DebugFrameView(level = 1, sourceName = "lib.lua", line = 3),
                )
            },
        )

        assertEquals(
            DebugCliResult(success = true, message = "backtrace", values = listOf("#0 main.lua:8", "#1 lib.lua:3")),
            runner.execute("bt"),
        )
        assertEquals(
            DebugCliResult(success = true, message = "locals", values = listOf("answer = 42", "name = KLua")),
            runner.execute("locals"),
        )
    }
}
