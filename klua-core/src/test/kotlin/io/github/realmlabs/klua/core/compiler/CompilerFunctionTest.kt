package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.bytecode.Disassembler
import io.github.realmlabs.klua.core.bytecode.UpvalueSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        assertEquals(1, function.lineDefined)
        assertEquals(1, function.lastLineDefined)
        assertEquals(1, function.debugInfo.lineDefined)
        assertEquals(1, function.debugInfo.lastLineDefined)
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
        assertEquals(1, prototype.nested.single().lineDefined)
        assertEquals(3, prototype.nested.single().lastLineDefined)
    }

    @Test
    fun `compiles global function declarations as global closures`() {
        val prototype = Compiler.compile(
            """
            function add(a, b)
                return a + b
            end
            return add(20, 22)
            """.trimIndent(),
        )

        assertEquals(3, prototype.maxStackSize)
        assertEquals(1, prototype.nested.size)
        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [1]  SET_GLOBAL K0 R0 ; "add"
            0002  [4]  GET_GLOBAL R0 K0 ; "add"
            0003  [4]  LOAD_INT R1 20
            0004  [4]  LOAD_INT R2 22
            0005  [4]  CALL R0 2 *
            0006  [4]  RETURN R0 *
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
        assertEquals(2, prototype.nested.single().numParams)
    }

    @Test
    fun `compiles global function declarations with redeclaration checks`() {
        val prototype = Compiler.compile(
            """
            global function add(a, b)
                return a + b
            end
            return add(20, 22)
            """.trimIndent(),
        )

        assertEquals(3, prototype.maxStackSize)
        assertEquals(1, prototype.nested.size)
        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [1]  CHECK_GLOBAL_NIL K0 ; "add"
            0002  [1]  SET_GLOBAL K0 R0 ; "add"
            0003  [4]  GET_GLOBAL R0 K0 ; "add"
            0004  [4]  LOAD_INT R1 20
            0005  [4]  LOAD_INT R2 22
            0006  [4]  CALL R0 2 *
            0007  [4]  RETURN R0 *
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
        assertEquals(2, prototype.nested.single().numParams)
    }

    @Test
    fun `nested functions can read declared parent globals`() {
        val prototype = Compiler.compile(
            """
            global answer
            return function()
                return answer
            end
            """.trimIndent(),
            "nested-global-declaration.lua",
        )

        assertEquals(
            """
            0000  [2]  CLOSURE R0 P0
            0001  [2]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
        assertEquals(
            """
            0000  [3]  GET_GLOBAL R0 K0 ; "answer"
            0001  [3]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype.nested.single()),
        )
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
    fun `compiles indexed function call statements with discarded results`() {
        val prototype = Compiler.compile(
            """
            local t = {}
            t.tick = function(value) return value end
            t.tick(1)
            return 2
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  NEW_TABLE R0
            0001  [2]  MOVE R1 R0
            0002  [2]  CLOSURE R2 P0
            0003  [2]  SET_FIELD R1 K0 R2 ; "tick"
            0004  [3]  MOVE R1 R0
            0005  [3]  GET_FIELD R1 R1 K0 ; "tick"
            0006  [3]  LOAD_INT R2 1
            0007  [3]  CALL R1 1 0
            0008  [4]  LOAD_INT R1 2
            0009  [4]  MOVE R0 R1
            0010  [4]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )
    }

    @Test
    fun `compiles method calls with self argument`() {
        val prototype = Compiler.compile(
            """
            local player = {}
            return player:addExp(100)
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  NEW_TABLE R0
            0001  [2]  MOVE R2 R0
            0002  [2]  GET_FIELD R1 R2 K0 ; "addExp"
            0003  [2]  LOAD_INT R3 100
            0004  [2]  CALL R1 2 *
            0005  [2]  RETURN R1 *
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
    fun `compiles captured parent local reads`() {
        val prototype = Compiler.compile(
            """
            local x = 42
            local function get()
                return x
            end
            return get()
            """.trimIndent(),
        )

        assertEquals(3, prototype.maxStackSize)
        assertEquals(
            """
            0000  [1]  LOAD_INT R0 42
            0001  [2]  CLOSURE R1 P0
            0002  [5]  MOVE R2 R1
            0003  [5]  CALL R2 0 *
            0004  [5]  RETURN R2 *
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )

        val function = prototype.nested.single()
        assertEquals("x", function.upvalues.single().name)
        assertContentEquals(arrayOf("x"), function.upvalueNames)
        assertContentEquals(arrayOf("x"), function.debugInfo.upvalueNames)
        assertEquals(listOf("x"), function.debugInfo.toSnapshot().upvalueNames)
        assertEquals(UpvalueSource.LOCAL, function.upvalues.single().source)
        assertEquals(0, function.upvalues.single().sourceIndex)
        assertEquals(
            """
            0000  [3]  GET_UPVALUE R0 U0
            0001  [3]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(function),
        )
    }

    @Test
    fun `compiles captured parent local assignments`() {
        val prototype = Compiler.compile(
            """
            local function counter()
                local x = 0
                return function()
                    x = x + 1
                    return x
                end
            end
            return counter
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [8]  MOVE R1 R0
            0002  [8]  MOVE R0 R1
            0003  [8]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )

        val counter = prototype.nested.single()
        assertEquals(
            """
            0000  [2]  LOAD_INT R0 0
            0001  [3]  CLOSURE R1 P0
            0002  [3]  CLOSE_UPVALUES R0
            0003  [3]  MOVE R0 R1
            0004  [3]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(counter),
        )

        val increment = counter.nested.single()
        assertEquals("x", increment.upvalues.single().name)
        assertEquals(UpvalueSource.LOCAL, increment.upvalues.single().source)
        assertEquals(0, increment.upvalues.single().sourceIndex)
        assertEquals(
            """
            0000  [4]  GET_UPVALUE R0 U0
            0001  [4]  LOAD_INT R1 1
            0002  [4]  ADD R0 R0 R1
            0003  [4]  SET_UPVALUE U0 R0
            0004  [5]  GET_UPVALUE R0 U0
            0005  [5]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(increment),
        )
    }

    @Test
    fun `compiles transitive upvalue captures`() {
        val prototype = Compiler.compile(
            """
            local function outer()
                local x = 42
                local function middle()
                    return function()
                        return x
                    end
                end
                return middle
            end
            return outer
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [10]  MOVE R1 R0
            0002  [10]  MOVE R0 R1
            0003  [10]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )

        val outer = prototype.nested.single()
        assertEquals(
            """
            0000  [2]  LOAD_INT R0 42
            0001  [3]  CLOSURE R1 P0
            0002  [8]  MOVE R2 R1
            0003  [8]  CLOSE_UPVALUES R0
            0004  [8]  MOVE R0 R2
            0005  [8]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(outer),
        )

        val middle = outer.nested.single()
        assertEquals("x", middle.upvalues.single().name)
        assertContentEquals(arrayOf("x"), middle.upvalueNames)
        assertEquals(UpvalueSource.LOCAL, middle.upvalues.single().source)
        assertEquals(0, middle.upvalues.single().sourceIndex)
        assertEquals(
            """
            0000  [4]  CLOSURE R0 P0
            0001  [4]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(middle),
        )

        val inner = middle.nested.single()
        assertEquals("x", inner.upvalues.single().name)
        assertContentEquals(arrayOf("x"), inner.upvalueNames)
        assertEquals(UpvalueSource.UPVALUE, inner.upvalues.single().source)
        assertEquals(0, inner.upvalues.single().sourceIndex)
        assertEquals(
            """
            0000  [5]  GET_UPVALUE R0 U0
            0001  [5]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(inner),
        )
    }

    @Test
    fun `compiles sibling closures sharing an upvalue`() {
        val prototype = Compiler.compile(
            """
            local function pair()
                local x = 0
                local function increment()
                    x = x + 1
                    return x
                end
                local function get()
                    return x
                end
                return increment, get
            end
            return pair
            """.trimIndent(),
        )

        assertEquals(
            """
            0000  [1]  CLOSURE R0 P0
            0001  [12]  MOVE R1 R0
            0002  [12]  MOVE R0 R1
            0003  [12]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(prototype),
        )

        val pair = prototype.nested.single()
        assertEquals(
            """
            0000  [2]  LOAD_INT R0 0
            0001  [3]  CLOSURE R1 P0
            0002  [7]  CLOSURE R2 P1
            0003  [10]  MOVE R3 R1
            0004  [10]  MOVE R4 R2
            0005  [10]  CLOSE_UPVALUES R0
            0006  [10]  MOVE R0 R3
            0007  [10]  MOVE R1 R4
            0008  [10]  RETURN R0 2
            """.trimIndent(),
            Disassembler.disassemble(pair),
        )

        val increment = pair.nested[0]
        assertEquals("x", increment.upvalues.single().name)
        assertEquals(UpvalueSource.LOCAL, increment.upvalues.single().source)
        assertEquals(0, increment.upvalues.single().sourceIndex)
        assertEquals(
            """
            0000  [4]  GET_UPVALUE R0 U0
            0001  [4]  LOAD_INT R1 1
            0002  [4]  ADD R0 R0 R1
            0003  [4]  SET_UPVALUE U0 R0
            0004  [5]  GET_UPVALUE R0 U0
            0005  [5]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(increment),
        )

        val get = pair.nested[1]
        assertEquals("x", get.upvalues.single().name)
        assertEquals(UpvalueSource.LOCAL, get.upvalues.single().source)
        assertEquals(0, get.upvalues.single().sourceIndex)
        assertEquals(
            """
            0000  [8]  GET_UPVALUE R0 U0
            0001  [8]  RETURN R0 1
            """.trimIndent(),
            Disassembler.disassemble(get),
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
