package io.github.realmlabs.klua.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DebugCliMainTest {
    @Test
    fun `invocation parser accepts debug script and arguments`() {
        val invocation = DebugCliInvocationParser.parse(arrayOf("--debug", "main.lua", "left", "right"))

        assertEquals(DebugCliInvocation(DebugCliSession("main.lua", listOf("left", "right"))), invocation)
    }

    @Test
    fun `invocation parser rejects unsupported command lines`() {
        assertFailsWith<IllegalArgumentException> {
            DebugCliInvocationParser.parse(emptyArray())
        }
        assertFailsWith<IllegalArgumentException> {
            DebugCliInvocationParser.parse(arrayOf("main.lua"))
        }
        assertFailsWith<IllegalArgumentException> {
            DebugCliInvocationParser.parse(arrayOf("--debug"))
        }
    }

    @Test
    fun `main command loop executes debug commands until quit`() {
        val input = ArrayDeque(listOf("break main.lua:2", "run", "print 40 + 2", "quit"))
        val output = mutableListOf<String>()

        val exitCode = DebugCliMain.run(
            args = arrayOf("--debug", "main.lua", "left"),
            readLine = { input.removeFirstOrNull() },
            writeLine = { line -> output += line },
            readSource = { "return ..." },
        )

        assertEquals(0, exitCode)
        assertEquals(
            listOf(
                "debugging main.lua",
                "ok: breakpoint main.lua:2",
                "ok: completed left",
                "ok: printed 42",
                "ok: quit",
            ),
            output,
        )
    }

    @Test
    fun `main command loop reports bad commands and continues`() {
        val input = ArrayDeque(listOf("bad", "quit"))
        val output = mutableListOf<String>()

        val exitCode = DebugCliMain.run(
            args = arrayOf("--debug", "main.lua"),
            readLine = { input.removeFirstOrNull() },
            writeLine = { line -> output += line },
            readSource = { "return 1" },
        )

        assertEquals(0, exitCode)
        assertEquals(
            listOf(
                "debugging main.lua",
                "error: unknown debug command: bad",
                "ok: quit",
            ),
            output,
        )
    }

    @Test
    fun `main returns usage error for invalid invocation`() {
        val output = mutableListOf<String>()

        val exitCode = DebugCliMain.run(
            args = arrayOf("main.lua"),
            readLine = { null },
            writeLine = { line -> output += line },
            readSource = { "return 1" },
        )

        assertEquals(2, exitCode)
        assertEquals(listOf("usage: klua --debug <script.lua> [args...]"), output)
    }
}
