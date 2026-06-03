package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.compiler.Compiler
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LuaVmFunctionTest {
    @Test
    fun `loads function expression as closure value`() {
        val result = LuaVm().execute(
            Compiler.compile("return function(a, b) return a + b end"),
        )

        val closure = assertIs<LuaClosure>(result.single())
        assertEquals(2, closure.prototype.numParams)
        assertEquals(false, closure.prototype.isVararg)
    }

    @Test
    fun `executes simple function calls`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function add(a, b)
                    return a + b
                end
                return add(20, 22)
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `executes global function declarations`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                function add(a, b)
                    return a + b
                end
                return add(20, 22)
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `executes local call initializers with multiple results`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function pair()
                    return 1, 2
                end
                local a, b = pair()
                return a + b
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(3)), result)
    }

    @Test
    fun `fills missing local call results with nil`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function single()
                    return 1
                end
                local a, b = single()
                return a, b
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1), LuaNil), result)
    }

    @Test
    fun `executes assignment calls with multiple results`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function pair()
                    return 1, 2
                end
                local a, b = 0, 0
                a, b = pair()
                return a + b
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(3)), result)
    }

    @Test
    fun `executes function call statements and discards results`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function value()
                    return 42
                end
                value()
                return 1
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1)), result)
    }

    @Test
    fun `executes local vararg expansion`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function add(_, ...)
                    local a, b = ...
                    return a + b
                end
                return add(0, 20, 22)
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `fills missing varargs with nil`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function second(...)
                    local a, b = ...
                    return b
                end
                return second(42)
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaNil), result)
    }

    @Test
    fun `returns all varargs from vararg functions`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function pass(...)
                    return ...
                end
                return pass(1, 2, 3)
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1), LuaInteger(2), LuaInteger(3)), result)
    }

    @Test
    fun `returns empty open call results`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function none()
                    return
                end
                return none()
                """.trimIndent(),
            ),
        )

        assertEquals(emptyList(), result)
    }

    @Test
    fun `treats non-final vararg returns as single values`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function prefix(...)
                    return ..., 9
                end
                return prefix(1, 2)
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1), LuaInteger(9)), result)
    }

    @Test
    fun `expands final call arguments`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function pair()
                    return 2, 3
                end
                local function add(a, b, c)
                    return a + b + c
                end
                return add(1, pair())
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(6)), result)
    }

    @Test
    fun `forwards varargs as final call arguments`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function add(a, b, c)
                    return a + b + c
                end
                local function forward(fn, ...)
                    return fn(...)
                end
                return forward(add, 10, 20, 12)
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `executes captured parent local reads`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 42
                local function get()
                    return x
                end
                return get()
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `captured parent local reads observe later assignments`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 1
                local function get()
                    return x
                end
                x = 42
                return get()
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `executes captured parent local assignments`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function counter()
                    local x = 0
                    return function()
                        x = x + 1
                        return x
                    end
                end
                local c = counter()
                return c(), c()
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1), LuaInteger(2)), result)
    }

    @Test
    fun `executes transitive captured local assignments`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function outer()
                    local x = 1
                    local function middle()
                        return function()
                            x = x + 1
                            return x
                        end
                    end
                    return middle
                end
                local increment = outer()()
                return increment(), increment()
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(2), LuaInteger(3)), result)
    }

    @Test
    fun `sibling closures share captured local assignments`() {
        val result = LuaVm().execute(
            Compiler.compile(
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
                local increment, get = pair()
                return get(), increment(), get(), increment(), get()
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(LuaInteger(0), LuaInteger(1), LuaInteger(1), LuaInteger(2), LuaInteger(2)),
            result,
        )
    }

    @Test
    fun `passes no arguments from empty open call arguments`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function none()
                    return
                end
                local function first(value)
                    return value
                end
                return first(none())
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaNil), result)
    }

    @Test
    fun `propagates errors from function call statements`() {
        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(
                Compiler.compile(
                    """
                    local function fail()
                        return #1
                    end
                    fail()
                    return 1
                    """.trimIndent(),
                ),
            )
        }

        assertEquals("attempt to get length of number", error.message)
    }

    @Test
    fun `rejects calls to non function values`() {
        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(Compiler.compile("local value = 1 return value()"))
        }

        assertEquals("attempt to call number", error.message)
    }
}
