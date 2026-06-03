package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.bytecode.Disassembler
import io.github.realmlabs.klua.core.runtime.LuaSourceVersion
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CompilerTest {
    @Test
    fun `compiles integer return with inline load`() {
        val prototype = Compiler.compile("return 42", "return-int.lua")

        assertEquals("return-int.lua", prototype.sourceName)
        assertEquals(LuaSourceVersion.LUA_54, prototype.version)
        assertEquals(1, prototype.maxStackSize)
        assertEquals(0, prototype.constants.size)
        assertContentEquals(intArrayOf(1, 1), prototype.lineInfo)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 42
            0001  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles multiple literal return values`() {
        val prototype = Compiler.compile("""return nil, true, false, "ok", 2.5""")

        assertEquals(2, prototype.constants.size)
        assertEquals("ok", assertIs<LuaString>(prototype.constants[0]).value)
        assertEquals(2.5, assertIs<LuaFloat>(prototype.constants[1]).value)
        assertEquals(5, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_NIL R0
            0001  [1]  LOAD_BOOL R1 true
            0002  [1]  LOAD_BOOL R2 false
            0003  [1]  LOAD_K R3 K0 ; "ok"
            0004  [1]  LOAD_FLOAT R4 2.5
            0005  [1]  RETURN R0 5
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `stores large integers in constant pool`() {
        val prototype = Compiler.compile("return 1024")

        assertEquals(1, prototype.constants.size)
        assertEquals(1024L, assertIs<LuaInteger>(prototype.constants.single()).value)
        assertEquals(
            """
            0000  [1]  LOAD_K R0 K0 ; 1024
            0001  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles negative numeric literals`() {
        val prototype = Compiler.compile("return -5, -1.5")

        assertEquals(1, prototype.constants.size)
        assertEquals(-1.5, assertIs<LuaFloat>(prototype.constants.single()).value)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 -5
            0001  [1]  LOAD_FLOAT R1 -1.5
            0002  [1]  RETURN R0 2
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `deduplicates equal constants`() {
        val prototype = Compiler.compile("""return "same", "same", 1000, 1000""")

        assertEquals(2, prototype.constants.size)
        assertEquals(
            """
            0000  [1]  LOAD_K R0 K0 ; "same"
            0001  [1]  LOAD_K R1 K0 ; "same"
            0002  [1]  LOAD_K R2 K1 ; 1000
            0003  [1]  LOAD_K R3 K1 ; 1000
            0004  [1]  RETURN R0 4
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `emits empty chunk as zero value return`() {
        val prototype = Compiler.compile("")

        assertEquals(
            "0000  [1]  RETURN R0 0",
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `rejects non literal return expressions in this slice`() {
        val error = assertFailsWith<CompilerException> {
            Compiler.compile("return 1 + 2", "unsupported.lua")
        }

        assertEquals("unsupported.lua", error.position.sourceName)
        assertEquals(1, error.position.line)
        assertEquals(8, error.position.column)
    }
}
