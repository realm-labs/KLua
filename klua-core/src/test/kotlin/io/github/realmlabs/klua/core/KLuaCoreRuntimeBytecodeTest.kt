package io.github.realmlabs.klua.core

import io.github.realmlabs.klua.core.value.toLuaByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KLuaCoreRuntimeBytecodeTest {
    @Test
    fun `compiles and loads bytecode packages`() {
        val bytecode = assertIs<KLuaCoreBytecodeLoad.Success>(
            KLuaCoreRuntime.compileBytecode(
                """
                local function add(a, b)
                    return a + b
                end
                return add(...)
                """.trimIndent(),
                "bytecode.lua",
            ),
        ).bytes
        val chunk = assertIs<KLuaCoreLoad.Success>(KLuaCoreRuntime.loadBytecode(bytecode)).chunk

        val result = assertIs<KLuaCoreExecution.Success>(
            KLuaCoreRuntime.execute(
                chunk,
                listOf(KLuaCoreValue.IntegerValue(20), KLuaCoreValue.IntegerValue(22)),
            ),
        )

        assertEquals(listOf(KLuaCoreValue.IntegerValue(42)), result.values)
    }

    @Test
    fun `bytecode execution preserves debug metadata`() {
        val bytecode = assertIs<KLuaCoreBytecodeLoad.Success>(
            KLuaCoreRuntime.compileBytecode(
                """
                local function fail()
                    return "x" + 1
                end
                return fail()
                """.trimIndent(),
                "bytecode-error.lua",
            ),
        ).bytes
        val chunk = assertIs<KLuaCoreLoad.Success>(KLuaCoreRuntime.loadBytecode(bytecode)).chunk

        val error = assertIs<KLuaCoreExecution.RuntimeError>(KLuaCoreRuntime.execute(chunk))

        assertEquals("attempt to perform arithmetic on string", error.message)
        assertEquals("bytecode-error.lua", error.sourceName)
        assertEquals(2, error.line)
        assertEquals(listOf(2, 4), error.luaFrames.map { frame -> frame.line })
        assertEquals(listOf("fail", null), error.luaFrames.map { frame -> frame.callSiteName })
        assertEquals(listOf("local", ""), error.luaFrames.map { frame -> frame.callSiteNameWhat })
    }

    @Test
    fun `bytecode execution preserves raw byte string constants`() {
        val bytecode = assertIs<KLuaCoreBytecodeLoad.Success>(
            KLuaCoreRuntime.compileBytecode("""return "\255\195\169" """, "bytecode-raw-string.lua"),
        ).bytes
        val chunk = assertIs<KLuaCoreLoad.Success>(KLuaCoreRuntime.loadBytecode(bytecode)).chunk

        val result = assertIs<KLuaCoreExecution.Success>(KLuaCoreRuntime.execute(chunk))

        assertEquals(
            listOf(KLuaCoreValue.StringValue(byteArrayOf(255.toByte(), 195.toByte(), 169.toByte()).toLuaByteString())),
            result.values,
        )
    }

    @Test
    fun `long control flow compiles executes and round trips bytecode`() {
        val filler = List(40) { "x = x + 1" }.joinToString("\n")
        val secondaryFiller = List(40) { "y = y + 1" }.joinToString("\n")
        val arithmetic = List(70) { "1" }.joinToString(" + ")
        val cases = listOf(
            """
            local x = 0
            if false then
            $filler
            elseif true then
            $filler
            else
            $filler
            end
            return x
            """.trimIndent() to listOf(KLuaCoreValue.IntegerValue(40)),
            """
            local x = 0
            while x < 1 do
            $filler
            end
            return x
            """.trimIndent() to listOf(KLuaCoreValue.IntegerValue(40)),
            """
            local x = 0
            repeat
            $filler
            until x >= 1
            return x
            """.trimIndent() to listOf(KLuaCoreValue.IntegerValue(40)),
            """
            local x = 0
            for i = 1, 1 do
            $filler
            end
            return x
            """.trimIndent() to listOf(KLuaCoreValue.IntegerValue(40)),
            """
            local function iter(state, control)
                if control < state then
                    return control + 1
                end
            end
            local x = 0
            for i in iter, 1, 0 do
            $filler
            end
            return x
            """.trimIndent() to listOf(KLuaCoreValue.IntegerValue(40)),
            """
            local x = 0
            while true do
            $filler
                break
            $filler
            end
            return x
            """.trimIndent() to listOf(KLuaCoreValue.IntegerValue(40)),
            """
            local x = 0
            goto done
            $filler
            ::done::
            return x
            """.trimIndent() to listOf(KLuaCoreValue.IntegerValue(0)),
            """
            local x = 0
            local y = 0
            ::again::
            x = x + 1
            if x < 2 then
            $secondaryFiller
                goto again
            end
            return x, y
            """.trimIndent() to
                listOf(
                    KLuaCoreValue.IntegerValue(2),
                    KLuaCoreValue.IntegerValue(40),
                ),
            "return false and ($arithmetic), true or ($arithmetic), true and ($arithmetic), false or ($arithmetic)" to
                listOf(
                    KLuaCoreValue.BooleanValue(false),
                    KLuaCoreValue.BooleanValue(true),
                    KLuaCoreValue.IntegerValue(70),
                    KLuaCoreValue.IntegerValue(70),
                ),
        )

        cases.forEachIndexed { index, (source, expected) ->
            assertEquals(expected, executeBytecode(source, "long-control-$index.lua"), "case $index")
        }
    }

    @Test
    fun `long control flow bytecode preserves debug line metadata`() {
        val lines = buildList {
            add("local function fail()")
            add("    local x = 0")
            add("    if false then")
            repeat(40) { add("        x = x + 1") }
            add("    end")
            add("    return \"x\" + 1")
            add("end")
            add("return fail()")
        }
        val errorLine = lines.indexOf("    return \"x\" + 1") + 1
        val callLine = lines.size
        val bytecode = assertIs<KLuaCoreBytecodeLoad.Success>(
            KLuaCoreRuntime.compileBytecode(lines.joinToString("\n"), "long-control-debug.lua"),
        ).bytes
        val chunk = assertIs<KLuaCoreLoad.Success>(KLuaCoreRuntime.loadBytecode(bytecode)).chunk

        val error = assertIs<KLuaCoreExecution.RuntimeError>(KLuaCoreRuntime.execute(chunk))

        assertEquals("attempt to perform arithmetic on string", error.message)
        assertEquals("long-control-debug.lua", error.sourceName)
        assertEquals(errorLine, error.line)
        assertEquals(listOf(errorLine, callLine), error.luaFrames.map { frame -> frame.line })
    }

    @Test
    fun `bytecode load rejects corrupted packages`() {
        val bytecode = assertIs<KLuaCoreBytecodeLoad.Success>(
            KLuaCoreRuntime.compileBytecode("return 1", "corrupted.lua"),
        ).bytes
        val corrupted = bytecode.copyOf()
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()

        val error = assertIs<KLuaCoreLoad.SyntaxError>(KLuaCoreRuntime.loadBytecode(corrupted))

        assertTrue(error.message.startsWith("KLua bytecode payload checksum mismatch"))
    }

    @Test
    fun `bytecode load rejects unsupported package versions`() {
        val bytecode = assertIs<KLuaCoreBytecodeLoad.Success>(
            KLuaCoreRuntime.compileBytecode("return 1", "versioned.lua"),
        ).bytes
        val unsupported = bytecode.copyOf()
        writeInt(unsupported, "KLua".length, 4)

        val error = assertIs<KLuaCoreLoad.SyntaxError>(KLuaCoreRuntime.loadBytecode(unsupported))

        assertEquals("unsupported KLua bytecode format version 4", error.message)
    }

    @Test
    fun `bytecode compile returns source syntax errors`() {
        val error = assertIs<KLuaCoreBytecodeLoad.SyntaxError>(
            KLuaCoreRuntime.compileBytecode("return function(", "bad-bytecode-source.lua"),
        )

        assertTrue(error.message.isNotBlank())
    }

    private fun executeBytecode(source: String, chunkName: String): List<KLuaCoreValue> {
        val bytecode = assertIs<KLuaCoreBytecodeLoad.Success>(
            KLuaCoreRuntime.compileBytecode(source, chunkName),
        ).bytes
        val chunk = assertIs<KLuaCoreLoad.Success>(KLuaCoreRuntime.loadBytecode(bytecode)).chunk
        return assertIs<KLuaCoreExecution.Success>(KLuaCoreRuntime.execute(chunk)).values
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
