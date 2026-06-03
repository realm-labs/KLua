package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.bytecode.Disassembler
import kotlin.test.Test
import kotlin.test.assertEquals

class CompilerFunctionTest {
    @Test
    fun `compiles function expression into nested prototype`() {
        val prototype = Compiler.compile("return function(a, b) return a + b end")

        assertEquals(1, prototype.maxStackSize)
        assertEquals(1, prototype.nested.size)
        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )

        val function = prototype.nested.single()
        assertEquals(2, function.numParams)
        assertEquals(false, function.isVararg)
        assertEquals(4, function.maxStackSize)
        assertEquals(
            """
            0000  [1]  MOVE R2 R0
            0001  [1]  MOVE R3 R1
            0002  [1]  ADD R2 R2 R3
            0003  [1]  MOVE R0 R2
            0004  [1]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(function),
        )
    }

    @Test
    fun `compiles local function declarations as closure locals`() {
        val prototype = Compiler.compile(
            """
            local function identity(value)
                return value
            end
            return identity
            """.trimIndent(),
        )

        assertEquals(2, prototype.maxStackSize)
        assertEquals(1, prototype.nested.size)
        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [4]  MOVE R1 R0
            0002  [4]  MOVE R0 R1
            0003  [4]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
        assertEquals(1, prototype.nested.single().numParams)
    }

    @Test
    fun `compiles simple function calls`() {
        val prototype = Compiler.compile(
            """
            local function add(a, b)
                return a + b
            end
            return add(20, 22)
            """.trimIndent(),
        )

        assertEquals(4, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [4]  MOVE R1 R0
            0002  [4]  LOAD_INT R2 20
            0003  [4]  LOAD_INT R3 22
            0004  [4]  CALL R1 2 *
            0005  [4]  RETURN R1 *
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles local call initializer with multiple results`() {
        val prototype = Compiler.compile(
            """
            local function pair()
                return 1, 2
            end
            local a, b = pair()
            return a + b
            """.trimIndent(),
        )

        assertEquals(5, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [4]  MOVE R1 R0
            0002  [4]  CALL R1 0 2
            0003  [5]  MOVE R3 R1
            0004  [5]  MOVE R4 R2
            0005  [5]  ADD R3 R3 R4
            0006  [5]  MOVE R0 R3
            0007  [5]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles assignment call with multiple results`() {
        val prototype = Compiler.compile(
            """
            local function pair()
                return 1, 2
            end
            local a, b = 0, 0
            a, b = pair()
            return a + b
            """.trimIndent(),
        )

        assertEquals(5, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [4]  LOAD_INT R1 0
            0002  [4]  LOAD_INT R2 0
            0003  [5]  MOVE R3 R0
            0004  [5]  CALL R3 0 2
            0005  [5]  MOVE R1 R3
            0006  [5]  MOVE R2 R4
            0007  [6]  MOVE R3 R1
            0008  [6]  MOVE R4 R2
            0009  [6]  ADD R3 R3 R4
            0010  [6]  MOVE R0 R3
            0011  [6]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles function call statements with discarded results`() {
        val prototype = Compiler.compile(
            """
            local function value()
                return 42
            end
            value()
            return 1
            """.trimIndent(),
        )

        assertEquals(2, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [4]  MOVE R1 R0
            0002  [4]  CALL R1 0 0
            0003  [5]  LOAD_INT R1 1
            0004  [5]  MOVE R0 R1
            0005  [5]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles local vararg expansion`() {
        val prototype = Compiler.compile(
            """
            local function add(_, ...)
                local a, b = ...
                return a + b
            end
            return add(0, 20, 22)
            """.trimIndent(),
        )

        val function = prototype.nested.single()
        assertEquals(1, function.numParams)
        assertEquals(true, function.isVararg)
        assertEquals(
            """
            0000  [2]  VARARG R1 2
            0001  [3]  MOVE R3 R1
            0002  [3]  MOVE R4 R2
            0003  [3]  ADD R3 R3 R4
            0004  [3]  MOVE R0 R3
            0005  [3]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(function),
        )
    }

    @Test
    fun `compiles open vararg and call returns`() {
        val prototype = Compiler.compile(
            """
            local function pass(...)
                return ...
            end
            return pass(1, 2)
            """.trimIndent(),
        )

        assertEquals(4, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [4]  MOVE R1 R0
            0002  [4]  LOAD_INT R2 1
            0003  [4]  LOAD_INT R3 2
            0004  [4]  CALL R1 2 *
            0005  [4]  RETURN R1 *
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
        assertEquals(
            """
            0000  [2]  VARARG R0 *
            0001  [2]  RETURN R0 *
            """.trimIndent(),
            Disassembler.disassemble(prototype.nested.single()),
        )
    }

    @Test
    fun `compiles final call arguments as open results`() {
        val prototype = Compiler.compile(
            """
            local function pair()
                return 2, 3
            end
            local function add(a, b, c)
                return a + b + c
            end
            return add(1, pair())
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [4]  CLOSURE R1 P1
            0002  [7]  MOVE R2 R1
            0003  [7]  LOAD_INT R3 1
            0004  [7]  MOVE R4 R0
            0005  [7]  CALL R4 0 *
            0006  [7]  CALL R2 * *
            0007  [7]  RETURN R2 *
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `adds implicit empty return to function bodies`() {
        val prototype = Compiler.compile("return function() local x = 1 end")

        assertEquals(
            """
            0000  [1]  LOAD_INT R0 1
            0001  [1]  RETURN R0 0
            """.trimIndent(),
            Disassembler.disassemble(prototype.nested.single()),
        )
    }
}
