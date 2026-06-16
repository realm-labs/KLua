package io.github.realmlabs.klua.tools

import io.github.realmlabs.klua.api.Lua
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DebugCliMainTest {
    @Test
    fun `invocation parser accepts debug script and arguments`() {
        val invocation = DebugCliInvocationParser.parse(arrayOf("--debug", "main.lua", "left", "right"))

        assertEquals(DebugCliInvocation(DebugCliSession("main.lua", listOf("left", "right"))), invocation)
    }

    @Test
    fun `tools invocation parser accepts bytecode compile command`() {
        val invocation = ToolsCliInvocationParser.parse(arrayOf("--compile", "main.lua", "main.kluac"))

        assertEquals(
            ToolsCliInvocation.Compile(BytecodeCompileInvocation("main.lua", "main.kluac")),
            invocation,
        )
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
        assertEquals(
            listOf("usage: klua --debug <script.lua> [args...] | klua --compile <script.lua> <output.kluac>"),
            output,
        )
    }

    @Test
    fun `main compile command writes loadable bytecode package`() {
        val output = mutableListOf<String>()
        val writes = linkedMapOf<String, ByteArray>()

        val exitCode = DebugCliMain.run(
            args = arrayOf("--compile", "main.lua", "main.kluac"),
            readLine = { null },
            writeLine = { line -> output += line },
            readSource = { "return 40 + 2" },
            writeBytes = { path, bytes -> writes[path] = bytes },
        )

        assertEquals(0, exitCode)
        assertEquals(listOf("compiled main.lua -> main.kluac"), output)
        val bytecode = assertIs<ByteArray>(writes["main.kluac"])
        assertEquals(42L, Lua.create().loadBytecode(bytecode).evalLong())
    }

    @Test
    fun `main compile command reports syntax errors`() {
        val output = mutableListOf<String>()
        val writes = linkedMapOf<String, ByteArray>()

        val exitCode = DebugCliMain.run(
            args = arrayOf("--compile", "bad.lua", "bad.kluac"),
            readLine = { null },
            writeLine = { line -> output += line },
            readSource = { "return function(" },
            writeBytes = { path, bytes -> writes[path] = bytes },
        )

        assertEquals(1, exitCode)
        assertTrue(output.single().startsWith("error:"))
        assertEquals(emptyMap(), writes)
    }
}
