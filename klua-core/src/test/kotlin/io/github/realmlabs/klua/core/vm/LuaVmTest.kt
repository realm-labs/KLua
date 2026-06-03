package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Instruction
import io.github.realmlabs.klua.core.bytecode.Opcode
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.compiler.Compiler
import io.github.realmlabs.klua.core.runtime.LuaSourceVersion
import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LuaVmTest {
    @Test
    fun `executes empty chunk`() {
        val result = LuaVm().execute(Compiler.compile(""))

        assertEquals(emptyList(), result)
    }

    @Test
    fun `executes literal return chunk`() {
        val result = LuaVm().execute(Compiler.compile("""return nil, true, false, 42, -5, 1024, "ok", 2.5"""))

        assertEquals(
            listOf(
                LuaNil,
                LuaBoolean(true),
                LuaBoolean(false),
                LuaInteger(42),
                LuaInteger(-5),
                LuaInteger(1024),
                LuaString("ok"),
                LuaFloat(2.5),
            ),
            result,
        )
    }

    @Test
    fun `executes move opcode`() {
        val prototype = Prototype(
            sourceName = "move.lua",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, 0, 0),
                Instruction.abc(Opcode.MOVE, 1, 0),
                Instruction.abc(Opcode.RETURN, 1, 1),
            ),
            constants = arrayOf(LuaString("copied")),
            lineInfo = intArrayOf(1, 1, 1),
            maxStackSize = 2,
        )

        assertEquals(listOf(LuaString("copied")), LuaVm().execute(prototype))
    }

    @Test
    fun `executes integer arithmetic expressions`() {
        val result = LuaVm().execute(Compiler.compile("return 1 + 2 * 3 - 4 % 3, 7 // 2"))

        assertEquals(
            listOf(
                LuaInteger(6),
                LuaInteger(3),
            ),
            result,
        )
    }

    @Test
    fun `executes float arithmetic expressions`() {
        val result = LuaVm().execute(Compiler.compile("return 7 / 2, 2 ^ 3, -(1 + 2.5)"))

        assertEquals(
            listOf(
                LuaFloat(3.5),
                LuaFloat(8.0),
                LuaFloat(-3.5),
            ),
            result,
        )
    }

    @Test
    fun `executes local declaration and local return`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 40 + 2
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `preserves local values while staging return values`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x, y = 1, 2
                return y, x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(2), LuaInteger(1)), result)
    }

    @Test
    fun `initializes missing local values to nil at runtime`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x, y = 1
                return y
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaNil), result)
    }

    @Test
    fun `executes local reassignment`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 1
                x = x + 41
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `executes staged multi assignment`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x, y = 1, 2
                x, y = y, x
                return x, y
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(2), LuaInteger(1)), result)
    }

    @Test
    fun `assigns nil when reassignment has fewer values than targets`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x, y = 1, 2
                x, y = 3
                return x, y
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(3), LuaNil), result)
    }

    @Test
    fun `executes equality and comparison expressions`() {
        val result = LuaVm().execute(
            Compiler.compile("""return 1 == 1.0, 2 ~= 3, 2 < 3, 3 <= 3, 4 > 3, 4 >= 5, "a" < "b""""),
        )

        assertEquals(
            listOf(
                LuaBoolean(true),
                LuaBoolean(true),
                LuaBoolean(true),
                LuaBoolean(true),
                LuaBoolean(true),
                LuaBoolean(false),
                LuaBoolean(true),
            ),
            result,
        )
    }

    @Test
    fun `executes not with lua truthiness`() {
        val result = LuaVm().execute(
            Compiler.compile("""return not nil, not false, not true, not 0, not "text""""),
        )

        assertEquals(
            listOf(
                LuaBoolean(true),
                LuaBoolean(true),
                LuaBoolean(false),
                LuaBoolean(false),
                LuaBoolean(false),
            ),
            result,
        )
    }

    @Test
    fun `executes logical expressions with lua operand results`() {
        val result = LuaVm().execute(
            Compiler.compile("""return false and "right", true and "right", nil or "fallback", false or 7, "left" or "right""""),
        )

        assertEquals(
            listOf(
                LuaBoolean(false),
                LuaString("right"),
                LuaString("fallback"),
                LuaInteger(7),
                LuaString("left"),
            ),
            result,
        )
    }

    @Test
    fun `short circuits logical expressions`() {
        val result = LuaVm().execute(
            Compiler.compile("""return false and ("x" + 1), true or ("x" + 1)"""),
        )

        assertEquals(listOf(LuaBoolean(false), LuaBoolean(true)), result)
    }

    @Test
    fun `executes string concatenation`() {
        val result = LuaVm().execute(
            Compiler.compile("""return "a" .. 1 .. "b", 1.5 .. "x""""),
        )

        assertEquals(listOf(LuaString("a1b"), LuaString("1.5x")), result)
    }

    @Test
    fun `executes bitwise expressions`() {
        val result = LuaVm().execute(
            Compiler.compile("return ~1, 6 & 3, 4 | 1, 5 ~ 3, 6.0 & 3"),
        )

        assertEquals(
            listOf(
                LuaInteger(-2),
                LuaInteger(2),
                LuaInteger(5),
                LuaInteger(6),
                LuaInteger(2),
            ),
            result,
        )
    }

    @Test
    fun `executes shift expressions`() {
        val result = LuaVm().execute(
            Compiler.compile("return 1 << 3, 8 >> 1, 8 << -1, 8 >> -1, -1 >> 1, 1 << 64, 1 >> 64"),
        )

        assertEquals(
            listOf(
                LuaInteger(8),
                LuaInteger(4),
                LuaInteger(4),
                LuaInteger(16),
                LuaInteger(Long.MAX_VALUE),
                LuaInteger(0),
                LuaInteger(0),
            ),
            result,
        )
    }

    @Test
    fun `executes string length expressions`() {
        val result = LuaVm().execute(
            Compiler.compile("""return #"abc", #("a" .. "b"), #"""""),
        )

        assertEquals(listOf(LuaInteger(3), LuaInteger(2), LuaInteger(0)), result)
    }

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

    @Test
    fun `executes true if branch`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 0
                if true then
                    x = 1
                else
                    x = 2
                end
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1)), result)
    }

    @Test
    fun `executes false if branch else path`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 0
                if false then
                    x = 1
                else
                    x = 2
                end
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(2)), result)
    }

    @Test
    fun `executes elseif path`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 0
                if false then
                    x = 1
                elseif true then
                    x = 2
                else
                    x = 3
                end
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(2)), result)
    }

    @Test
    fun `falls through if without else`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 1
                if false then
                    x = 2
                end
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1)), result)
    }

    @Test
    fun `uses lua truthiness for if conditions`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x, y = 0, 0
                if 0 then
                    x = 1
                end
                if nil then
                    y = 1
                else
                    y = 2
                end
                return x, y
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1), LuaInteger(2)), result)
    }

    @Test
    fun `keeps branch local declarations scoped`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 1
                if true then
                    local x = 2
                end
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1)), result)
    }

    @Test
    fun `executes while loop`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 0
                while x < 3 do
                    x = x + 1
                end
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(3)), result)
    }

    @Test
    fun `skips false while loop`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 4
                while x < 3 do
                    x = x + 1
                end
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(4)), result)
    }

    @Test
    fun `keeps while body local declarations scoped`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 0
                while x < 2 do
                    local next = x + 1
                    x = next
                end
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(2)), result)
    }

    @Test
    fun `executes repeat until loop`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 0
                repeat
                    x = x + 1
                until x >= 3
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(3)), result)
    }

    @Test
    fun `executes repeat body before checking condition`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 4
                repeat
                    x = x + 1
                until x > 3
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(5)), result)
    }

    @Test
    fun `allows repeat condition to see body locals`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 0
                repeat
                    local done = x >= 2
                    x = x + 1
                until done
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(3)), result)
    }

    @Test
    fun `executes break in while loop`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 0
                while true do
                    x = x + 1
                    if x == 2 then
                        break
                    end
                end
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(2)), result)
    }

    @Test
    fun `executes break in repeat loop`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 0
                repeat
                    x = x + 1
                    break
                until false
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1)), result)
    }

    @Test
    fun `break exits nearest nested loop`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local outer, inner = 0, 0
                while outer < 2 do
                    outer = outer + 1
                    while true do
                        inner = inner + 1
                        break
                    end
                end
                return outer, inner
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(2), LuaInteger(2)), result)
    }

    @Test
    fun `executes numeric for loop`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local sum = 0
                for i = 1, 3 do
                    sum = sum + i
                end
                return sum
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(6)), result)
    }

    @Test
    fun `executes numeric for loop with negative step`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local sum = 0
                for i = 3, 1, -1 do
                    sum = sum + i
                end
                return sum
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(6)), result)
    }

    @Test
    fun `executes break in numeric for loop`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local sum = 0
                for i = 1, 5 do
                    if i == 3 then
                        break
                    end
                    sum = sum + i
                end
                return sum
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(3)), result)
    }

    @Test
    fun `keeps numeric for loop variable scoped`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local i = 9
                for i = 1, 2 do
                    local copy = i
                end
                return i
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(9)), result)
    }

    @Test
    fun `rejects comparison between incompatible values`() {
        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(Compiler.compile("""return "x" < 1"""))
        }

        assertEquals("attempt to compare string with number", error.message)
    }

    @Test
    fun `rejects arithmetic on non numeric values`() {
        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(Compiler.compile("""return "x" + 1"""))
        }

        assertEquals("attempt to perform arithmetic on string", error.message)
    }

    @Test
    fun `rejects concatenation of non stringable values`() {
        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(Compiler.compile("""return "x" .. nil"""))
        }

        assertEquals("attempt to concatenate nil", error.message)
    }

    @Test
    fun `rejects bitwise operation on non integer values`() {
        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(Compiler.compile("return 1.5 & 1"))
        }
        val rangeError = assertFailsWith<LuaVmException> {
            LuaVm().execute(Compiler.compile("return 9.223372036854776e18 & 1"))
        }
        val shiftError = assertFailsWith<LuaVmException> {
            LuaVm().execute(Compiler.compile("return 1 << 1.5"))
        }

        assertEquals("attempt to perform bitwise operation on number", error.message)
        assertEquals("attempt to perform bitwise operation on number", rangeError.message)
        assertEquals("attempt to perform bitwise operation on number", shiftError.message)
    }

    @Test
    fun `rejects length of non string values`() {
        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(Compiler.compile("return #1"))
        }

        assertEquals("attempt to get length of number", error.message)
    }

    @Test
    fun `rejects missing return`() {
        val prototype = Prototype(
            sourceName = "broken.lua",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(Instruction.abc(Opcode.LOAD_NIL, 0)),
            constants = emptyArray(),
            lineInfo = intArrayOf(1),
            maxStackSize = 1,
        )

        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(prototype)
        }

        assertEquals("prototype completed without RETURN", error.message)
    }

    @Test
    fun `rejects invalid constant index`() {
        val prototype = Prototype(
            sourceName = "bad-constant.lua",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(Instruction.abc(Opcode.LOAD_K, 0, 0)),
            constants = emptyArray(),
            lineInfo = intArrayOf(1),
            maxStackSize = 1,
        )

        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(prototype)
        }

        assertEquals("constant index out of range: K0", error.message)
    }
}
