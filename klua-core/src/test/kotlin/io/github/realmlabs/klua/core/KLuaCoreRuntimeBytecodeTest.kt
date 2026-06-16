package io.github.realmlabs.klua.core

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
        writeInt(unsupported, "KLua".length, 3)

        val error = assertIs<KLuaCoreLoad.SyntaxError>(KLuaCoreRuntime.loadBytecode(unsupported))

        assertEquals("unsupported KLua bytecode format version 3", error.message)
    }

    @Test
    fun `bytecode compile returns source syntax errors`() {
        val error = assertIs<KLuaCoreBytecodeLoad.SyntaxError>(
            KLuaCoreRuntime.compileBytecode("return function(", "bad-bytecode-source.lua"),
        )

        assertTrue(error.message.isNotBlank())
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
