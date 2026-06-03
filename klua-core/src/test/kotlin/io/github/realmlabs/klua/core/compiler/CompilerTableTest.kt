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
}
