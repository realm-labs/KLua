package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.bytecode.Disassembler
import kotlin.test.Test
import kotlin.test.assertEquals

class CompilerTableTest {
    @Test
    fun `compiles empty table constructors`() {
        val prototype = Compiler.compile("return {}")

        assertEquals(1, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  NEW_TABLE R0
            0001  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles list table constructors and bracket indexes`() {
        val prototype = Compiler.compile("return {10, 20}[1]")

        assertEquals(3, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  NEW_TABLE R0
            0001  [1]  LOAD_INT R2 10
            0002  [1]  LOAD_INT R1 1
            0003  [1]  SET_TABLE R0 R1 R2
            0004  [1]  LOAD_INT R2 20
            0005  [1]  LOAD_INT R1 2
            0006  [1]  SET_TABLE R0 R1 R2
            0007  [1]  LOAD_INT R1 1
            0008  [1]  GET_TABLE R0 R0 R1
            0009  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }
}
