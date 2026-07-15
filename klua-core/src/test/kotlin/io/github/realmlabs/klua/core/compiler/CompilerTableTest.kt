package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.bytecode.Disassembler
import io.github.realmlabs.klua.core.bytecode.Instruction
import kotlin.test.Test
import kotlin.test.assertEquals

class CompilerTableTest {
    @Test
    fun `caps table capacity hints at the bytecode operand limit`() {
        val entries = List(256) { index -> (index + 1).toString() }.joinToString(", ")

        val prototype = Compiler.compile("return {$entries}")

        assertEquals(255, Instruction.b(prototype.code.first()))
    }

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
            0000  [1]  NEW_TABLE R0 entries=2
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

    @Test
    fun `compiles named table constructor fields`() {
        val prototype = Compiler.compile("return { answer = 42 }")

        assertEquals(3, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  NEW_TABLE R0 entries=1
            0001  [1]  LOAD_INT R2 42
            0002  [1]  SET_FIELD R0 K0 R2 ; "answer"
            0003  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles keyed table constructor fields`() {
        val prototype = Compiler.compile("""return { ["answer"] = 42 }""")

        assertEquals(3, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  NEW_TABLE R0 entries=1
            0001  [1]  LOAD_K R1 K0 ; "answer"
            0002  [1]  LOAD_INT R2 42
            0003  [1]  SET_TABLE R0 R1 R2
            0004  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles indexed table assignments`() {
        val prototype = Compiler.compile(
            """
            local table = {}
            table[1] = 42
            return table[1]
            """.trimIndent(),
        )

        assertEquals(4, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  NEW_TABLE R0
            0001  [2]  MOVE R1 R0
            0002  [2]  LOAD_INT R2 1
            0003  [2]  LOAD_INT R3 42
            0004  [2]  SET_TABLE R1 R2 R3
            0005  [3]  MOVE R1 R0
            0006  [3]  LOAD_INT R2 1
            0007  [3]  GET_TABLE R1 R1 R2
            0008  [3]  MOVE R0 R1
            0009  [3]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles dot field assignments and reads`() {
        val prototype = Compiler.compile(
            """
            local table = {}
            table.answer = 42
            return table.answer
            """.trimIndent(),
        )

        assertEquals(3, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  NEW_TABLE R0
            0001  [2]  MOVE R1 R0
            0002  [2]  LOAD_INT R2 42
            0003  [2]  SET_FIELD R1 K0 R2 ; "answer"
            0004  [3]  MOVE R1 R0
            0005  [3]  GET_FIELD R1 R1 K0 ; "answer"
            0006  [3]  MOVE R0 R1
            0007  [3]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }
}
