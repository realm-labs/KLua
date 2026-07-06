package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Instruction
import io.github.realmlabs.klua.core.bytecode.OPEN_RESULT_COUNT
import io.github.realmlabs.klua.core.bytecode.Opcode
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.compiler.Compiler
import io.github.realmlabs.klua.core.compiler.CompilerException
import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaNativeFunction
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LuaVmTest {
    @Test
    fun `executes empty chunk`() {
        val result = LuaVm().execute(Compiler.compile(""))

        assertEquals(emptyList(), result)
    }

    @Test
    fun `executes non-empty chunk without explicit return`() {
        val result = LuaVm().execute(Compiler.compile("local x = 1"))

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
    fun `executes long bracket string literals`() {
        val result = LuaVm().execute(Compiler.compile("""return [[hello]], [==[a ]=] b]==]"""))

        assertEquals(listOf(LuaString("hello"), LuaString("a ]=] b")), result)
    }

    @Test
    fun `executes hexadecimal and whitespace string escapes`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                return "\x41\z
                    B"
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaString("AB")), result)
    }

    @Test
    fun `executes unicode string escapes`() {
        val result = LuaVm().execute(Compiler.compile("return \"\\u{41}\\u{1F642}\""))

        assertEquals(listOf(LuaString("A" + String(Character.toChars(0x1F642)))), result)
    }

    @Test
    fun `executes move opcode`() {
        val prototype = Prototype(
            sourceName = "move.lua",
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
    fun `executes power before unary operators`() {
        val result = LuaVm().execute(Compiler.compile("return -2 ^ 2, (-2) ^ 2, 2 ^ -2, -2 ^ -2"))

        assertEquals(
            listOf(
                LuaFloat(-4.0),
                LuaFloat(4.0),
                LuaFloat(0.25),
                LuaFloat(-0.25),
            ),
            result,
        )
    }

    @Test
    fun `executes hexadecimal numeric literals`() {
        val result = LuaVm().execute(
            Compiler.compile(
                "return 0xff, 0xffffffffffffffff, 0x10000000000000000, 0x1.8p1, 0x0.1E",
            ),
        )

        assertEquals(listOf(LuaInteger(255), LuaInteger(-1), LuaInteger(0), LuaFloat(3.0), LuaFloat(0.1171875)), result)
    }

    @Test
    fun `executes out of range decimal integer literals as floats`() {
        val result = LuaVm().execute(Compiler.compile("return 9223372036854775808"))

        assertEquals(listOf(LuaFloat(9223372036854775808.0)), result)
    }

    @Test
    fun `executes leading dot numeric literals`() {
        val result = LuaVm().execute(Compiler.compile("return .5, -.25"))

        assertEquals(listOf(LuaFloat(0.5), LuaFloat(-0.25)), result)
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
    fun `executes false to be closed locals as no-op close values`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local absent <close>
                local none <close> = nil
                local disabled <close> = false
                return absent, none, disabled
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaNil, LuaNil, LuaBoolean(false)), result)
    }

    @Test
    fun `executes dynamic false to be closed locals as no-op close values`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function maybeNil()
                    return nil
                end
                local function values()
                    return false, nil
                end
                local first <close> = maybeNil()
                local disabled, second <close> = values()
                return first, disabled, second
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaNil, LuaBoolean(false), LuaNil), result)
    }

    @Test
    fun `rejects dynamic non false to be closed local values`() {
        val directError = assertFailsWith<LuaVmException> {
            LuaVm().execute(
                Compiler.compile(
                    """
                    local function resource()
                        return {}
                    end
                    local value <close> = resource()
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("variable 'value' got a non-closable value", directError.message)

        val adjustedError = assertFailsWith<LuaVmException> {
            LuaVm().execute(
                Compiler.compile(
                    """
                    local function values()
                        return false, {}
                    end
                    local disabled, resource <close> = values()
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("variable 'resource' got a non-closable value", adjustedError.message)
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
    fun `executes const local reads and shadowing`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x <const> = 40
                do
                    local x = 2
                    x = x + 1
                end
                return x + 2
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `executes prefixed const local reads`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local <const> x, y = 20, 22
                return x + y
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
    fun `executes default environment field access`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                _ENV.answer = 42
                return answer, _ENV.answer
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42), LuaInteger(42)), result)
    }

    @Test
    fun `executes current frame environment assignment`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                _ENV = { answer = 42 }
                return answer, _ENV.answer
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42), LuaInteger(42)), result)
    }

    @Test
    fun `shares environment assignment with existing closures`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local get = function()
                    return answer
                end
                _ENV = { answer = 42 }
                return get()
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `shares repeated environment assignment across closures`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local first = function()
                    return answer
                end
                _ENV = { answer = 10 }
                local second = function()
                    return answer
                end
                _ENV = { answer = 42 }
                return first(), second()
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42), LuaInteger(42)), result)
    }

    @Test
    fun `executes global reads through local environments`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local _ENV = { answer = 42 }
                return answer
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `executes global writes through local environments`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local _ENV = {}
                answer = 42
                return _ENV.answer
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `captures local environments for nested global reads`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local _ENV = { answer = 10 }
                local get = function()
                    return answer
                end
                _ENV = { answer = 42 }
                return get()
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `executes initialized global declarations through local environments`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local _ENV = {}
                global answer = 42
                return _ENV.answer, answer
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42), LuaInteger(42)), result)
    }

    @Test
    fun `initialized global declarations reject existing local environment fields`() {
        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(
                Compiler.compile(
                    """
                    local _ENV = { answer = false }
                    global answer = 42
                    """.trimIndent(),
                ),
            )
        }

        assertEquals("global 'answer' already defined", error.message)
    }

    @Test
    fun `allows implicit environment assignment in strict global scopes`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                global answer
                _ENV = { answer = 42 }
                return answer
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `stages indexed assignment targets before writes`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local values = {10, 20}
                local index = 1
                index, values[index] = 2, 30

                local keyed = {x = 10, y = 20}
                local key = "x"
                key, keyed[key] = "y", 30

                return values[1], values[2], index, keyed.x, keyed.y, key
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(
                LuaInteger(30),
                LuaInteger(20),
                LuaInteger(2),
                LuaInteger(30),
                LuaInteger(20),
                LuaString("y"),
            ),
            result,
        )
    }

    @Test
    fun `evaluates indexed assignment targets before values`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local log = {}
                local target = {}
                local key = "first"

                local function receiver()
                    log[#log + 1] = "receiver"
                    return target
                end

                local function keyValue()
                    log[#log + 1] = "key"
                    return key
                end

                local function value()
                    log[#log + 1] = "value"
                    return 99
                end

                key, receiver()[keyValue()] = "second", value()
                return target.first, target.second, key, log[1], log[2], log[3]
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(
                LuaInteger(99),
                LuaNil,
                LuaString("second"),
                LuaString("receiver"),
                LuaString("key"),
                LuaString("value"),
            ),
            result,
        )
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
    fun `expands final call results in assignment lists`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function values()
                    return 1, nil, 3
                end

                local a, b, c, d = 0, values()
                local e, f, g, h = values(), 4
                a, b, c, d = 5, values()
                local object = {
                    method = function(self)
                        return self.value, nil, 8
                    end,
                    value = 7,
                }
                local i, j, k = 6, object:method()
                return a, b, c == nil, d, e, f, g, h, i, j, k
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(
                LuaInteger(5),
                LuaInteger(1),
                LuaBoolean(true),
                LuaInteger(3),
                LuaInteger(1),
                LuaInteger(4),
                LuaNil,
                LuaNil,
                LuaInteger(6),
                LuaInteger(7),
                LuaNil,
            ),
            result,
        )
    }

    @Test
    fun `evaluates extra assignment values for side effects`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local count = 0
                local function mark(value)
                    count = count + 1
                    return value
                end

                local a = mark(1), mark(2)
                a = mark(3), mark(4)
                local b = mark(5), (function() return mark(6), mark(7) end)()
                return a, b, count
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(3), LuaInteger(5), LuaInteger(7)), result)
    }

    @Test
    fun `expands final vararg values in assignment lists`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function probe(...)
                    local a, b, c, d = 0, ...
                    a, b, c, d = 1, ...
                    return a, b, c, d
                end

                return probe("x", nil, "z")
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1), LuaString("x"), LuaNil, LuaString("z")), result)
    }

    @Test
    fun `reads global values from vm globals`() {
        val globals = LuaTable()
        globals.rawSet(LuaString("answer"), LuaInteger(42))

        val result = LuaVm(globals).execute(Compiler.compile("return answer"))

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `writes global values to vm globals`() {
        val globals = LuaTable()

        val result = LuaVm(globals).execute(
            Compiler.compile(
                """
                answer = 41
                return answer
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(41)), result)
        assertEquals(LuaInteger(41), globals.rawGet(LuaString("answer")))
    }

    @Test
    fun `calls native functions from globals`() {
        val globals = LuaTable()
        globals.rawSet(
            LuaString("add"),
            LuaNativeFunction { arguments ->
                val left = arguments[0] as LuaInteger
                val right = arguments[1] as LuaInteger
                listOf(LuaInteger(left.value + right.value))
            },
        )

        val result = LuaVm(globals).execute(Compiler.compile("return add(20, 22)"))

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `rejects native yield signals from non yieldable lua calls`() {
        val globals = LuaTable()
        globals.rawSet(
            LuaString("yield"),
            LuaNativeFunction { arguments ->
                throw LuaYieldSignal(arguments)
            },
        )

        val error = assertFailsWith<LuaVmException> {
            LuaVm(globals).execute(
                Compiler.compile(
                    """
                    local function nested(value)
                        return yield(value)
                    end
                    return nested(42)
                    """.trimIndent(),
                ),
            )
        }

        assertEquals("attempt to yield across a C-call boundary", error.message)
    }

    @Test
    fun `preserves active frames for yielded lua calls`() {
        val globals = LuaTable()
        globals.rawSet(
            LuaString("yield"),
            LuaNativeFunction { arguments ->
                throw LuaYieldSignal(arguments)
            }.copy(yieldable = true),
        )
        val vm = LuaVm(globals)

        val result = vm.executeYieldable(
            Compiler.compile(
                """
                local function nested(value)
                    return yield(value)
                end
                return nested(42)
                """.trimIndent(),
            ),
        )

        assertEquals(LuaExecutionResult.Yielded(listOf(LuaInteger(42))), result)
        assertEquals(2, vm.activeCallDepth)
    }

    @Test
    fun `records pending call result slots when lua call yields`() {
        val globals = LuaTable()
        globals.rawSet(
            LuaString("yield"),
            LuaNativeFunction { arguments ->
                throw LuaYieldSignal(arguments)
            }.copy(yieldable = true),
        )
        val vm = LuaVm(globals)

        val result = vm.executeYieldable(
            Compiler.compile(
                """
                local function nested(value)
                    return yield(value)
                end
                return nested(42)
                """.trimIndent(),
            ),
        )

        val frame = vm.currentFrame
        assertEquals(LuaExecutionResult.Yielded(listOf(LuaInteger(42))), result)
        assertEquals(1, frame?.pendingCallResultBase)
        assertEquals(OPEN_RESULT_COUNT, frame?.pendingCallExpectedResults)
    }

    @Test
    fun `resumes yielded lua call with supplied values`() {
        val globals = LuaTable()
        globals.rawSet(
            LuaString("yield"),
            LuaNativeFunction { arguments ->
                throw LuaYieldSignal(arguments)
            }.copy(yieldable = true),
        )
        val vm = LuaVm(globals)

        val yielded = vm.executeYieldable(
            Compiler.compile(
                """
                local function nested(value)
                    local resumed = yield(value)
                    return resumed + 1
                end
                return nested(42)
                """.trimIndent(),
            ),
        )
        val resumed = vm.resumeYieldable(listOf(LuaInteger(99)))

        assertEquals(LuaExecutionResult.Yielded(listOf(LuaInteger(42))), yielded)
        assertEquals(LuaExecutionResult.Returned(listOf(LuaInteger(100))), resumed)
        assertEquals(0, vm.activeCallDepth)
    }

    @Test
    fun `resumes lua call across multiple yields`() {
        val globals = LuaTable()
        globals.rawSet(
            LuaString("yield"),
            LuaNativeFunction { arguments ->
                throw LuaYieldSignal(arguments)
            }.copy(yieldable = true),
        )
        val vm = LuaVm(globals)

        val firstYield = vm.executeYieldable(
            Compiler.compile(
                """
                local first = yield(1)
                local second = yield(first + 1)
                return second + 1
                """.trimIndent(),
            ),
        )
        val secondYield = vm.resumeYieldable(listOf(LuaInteger(41)))
        val returned = vm.resumeYieldable(listOf(LuaInteger(99)))

        assertEquals(LuaExecutionResult.Yielded(listOf(LuaInteger(1))), firstYield)
        assertEquals(LuaExecutionResult.Yielded(listOf(LuaInteger(42))), secondYield)
        assertEquals(LuaExecutionResult.Returned(listOf(LuaInteger(100))), returned)
        assertEquals(0, vm.activeCallDepth)
    }

    @Test
    fun `clears yielded frames when top level execution rejects yield`() {
        val globals = LuaTable()
        globals.rawSet(
            LuaString("yield"),
            LuaNativeFunction { arguments ->
                throw LuaYieldSignal(arguments)
            }.copy(yieldable = true),
        )
        val vm = LuaVm(globals)

        assertFailsWith<LuaVmException> {
            vm.execute(Compiler.compile("return yield(42)"))
        }

        assertEquals(0, vm.activeCallDepth)
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
    fun `executes numeric equality without losing integer precision`() {
        val globals = LuaTable()
        globals.rawSet(LuaString("max"), LuaInteger(Long.MAX_VALUE))
        globals.rawSet(LuaString("maxMinusOne"), LuaInteger(Long.MAX_VALUE - 1L))
        globals.rawSet(LuaString("min"), LuaInteger(Long.MIN_VALUE))
        globals.rawSet(LuaString("minPlusOne"), LuaInteger(Long.MIN_VALUE + 1L))
        globals.rawSet(LuaString("oneFloat"), LuaFloat(1.0))
        globals.rawSet(LuaString("onePointFive"), LuaFloat(1.5))
        globals.rawSet(LuaString("maxFloat"), LuaFloat(Long.MAX_VALUE.toDouble()))
        globals.rawSet(LuaString("minFloat"), LuaFloat(Long.MIN_VALUE.toDouble()))

        val result = LuaVm(globals).execute(
            Compiler.compile(
                """
                return max == maxMinusOne,
                    max ~= maxMinusOne,
                    min == minPlusOne,
                    min ~= minPlusOne,
                    1 == oneFloat,
                    1 == onePointFive,
                    max == maxFloat,
                    min == minFloat
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(
                LuaBoolean(false),
                LuaBoolean(true),
                LuaBoolean(false),
                LuaBoolean(true),
                LuaBoolean(true),
                LuaBoolean(false),
                LuaBoolean(false),
                LuaBoolean(true),
            ),
            result,
        )
    }

    @Test
    fun `executes numeric ordering without losing integer precision`() {
        val globals = LuaTable()
        globals.rawSet(LuaString("max"), LuaInteger(Long.MAX_VALUE))
        globals.rawSet(LuaString("maxMinusOne"), LuaInteger(Long.MAX_VALUE - 1L))
        globals.rawSet(LuaString("min"), LuaInteger(Long.MIN_VALUE))
        globals.rawSet(LuaString("minPlusOne"), LuaInteger(Long.MIN_VALUE + 1L))
        globals.rawSet(LuaString("maxFloat"), LuaFloat(Long.MAX_VALUE.toDouble()))
        globals.rawSet(LuaString("minFloat"), LuaFloat(Long.MIN_VALUE.toDouble()))
        globals.rawSet(LuaString("nan"), LuaFloat(Double.NaN))

        val result = LuaVm(globals).execute(
            Compiler.compile(
                """
                return maxMinusOne < max,
                    max <= maxMinusOne,
                    min < minPlusOne,
                    minPlusOne <= min,
                    max < maxFloat,
                    maxFloat < max,
                    minFloat <= min,
                    minFloat < min,
                    nan < nan,
                    nan <= nan
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(
                LuaBoolean(true),
                LuaBoolean(false),
                LuaBoolean(true),
                LuaBoolean(false),
                LuaBoolean(true),
                LuaBoolean(false),
                LuaBoolean(true),
                LuaBoolean(false),
                LuaBoolean(false),
                LuaBoolean(false),
            ),
            result,
        )
    }

    @Test
    fun `executes string equality over canonical raw bytes`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local splitUtf8 = "\195" .. "\169"
                local literalUtf8 = "é"
                local raw = "\255"
                local rebuiltRaw = "\255" .. ""
                return splitUtf8 == literalUtf8,
                    splitUtf8 ~= literalUtf8,
                    raw == rebuiltRaw,
                    raw == literalUtf8
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(LuaBoolean(true), LuaBoolean(false), LuaBoolean(true), LuaBoolean(false)),
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
    fun `executes string length over raw byte escapes`() {
        val result = LuaVm().execute(
            Compiler.compile("""return #"\255\128", #"\195\169", #"\255é" """),
        )

        assertEquals(listOf(LuaInteger(2), LuaInteger(2), LuaInteger(3)), result)
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
    fun `executes do block with scoped locals`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local x = 1
                do
                    local x = 2
                    x = x + 1
                end
                return x
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1)), result)
    }

    @Test
    fun `do block closes captured locals before register reuse`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local getter
                do
                    local captured = "block"
                    getter = function()
                        return captured
                    end
                end
                local captured = "shadow"
                return getter()
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaString("block")), result)
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
    fun `repeat closes captured body locals before next iteration`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local getters = {}
                local i = 1
                repeat
                    local captured = i
                    getters[i] = function()
                        return captured
                    end
                    i = i + 1
                until i > 2
                return getters[1](), getters[2]()
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1), LuaInteger(2)), result)
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
    fun `executes forward goto to label`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local value = "before"
                goto done
                value = "skipped"
                ::done::
                return value
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaString("before")), result)
    }

    @Test
    fun `executes backward goto from nested block to outer label`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local value = 0
                ::again::
                value = value + 1
                if value < 3 then
                    goto again
                end
                return value
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(3)), result)
    }

    @Test
    fun `executes goto to label at end of block after locals`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local value = 0
                do
                    goto done
                    local skipped = 1
                    value = skipped
                    ::done::
                end
                return value
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(0)), result)
    }

    @Test
    fun `executes goto to first label in trailing label run after locals`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local value = 0
                do
                    goto first
                    local skipped = 1
                    value = skipped
                    ::first::
                    ::second::
                end
                return value
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(0)), result)
    }

    @Test
    fun `executes goto to trailing label in numeric for body`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local value = 0
                for i = 1, 3 do
                    value = value + 1
                    goto continue
                    local skipped = i
                    value = value + skipped
                    ::continue::
                end
                return value
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(3)), result)
    }

    @Test
    fun `executes exported goto to following outer label`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local value = 0
                do
                    local skipped = 1
                    goto done
                    value = skipped
                end
                ::done::
                return value
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(0)), result)
    }

    @Test
    fun `executes nested goto to enclosing end label after locals`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local value = 0
                do
                    do
                        goto done
                    end
                    local skipped = 1
                    value = skipped
                    ::done::
                end
                return value
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(0)), result)
    }

    @Test
    fun `rejects goto into nested label before execution`() {
        assertFailsWith<CompilerException> {
            Compiler.compile(
                """
                local value = 0
                goto done
                do
                    value = 1
                    ::done::
                end
                return value
                """.trimIndent(),
            )
        }
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
    fun `numeric for closes captured loop locals before next iteration`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local getters = {}
                for i = 1, 2 do
                    getters[i] = function()
                        return i
                    end
                end
                return getters[1](), getters[2]()
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1), LuaInteger(2)), result)
    }

    @Test
    fun `executes generic for loop`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function iter(state, key)
                    local next = key + 1
                    if next > state.n then
                        return nil
                    end
                    return next, state[next]
                end
                local function iterator()
                    return iter, {n = 3, "a", "b", "c"}, 0
                end
                local text = ""
                for index, value in iterator() do
                    text = text .. index .. value
                    if index == 2 then
                        break
                    end
                end
                return text
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaString("1a2b")), result)
    }

    @Test
    fun `expands final call results in generic for iterator lists`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function iter(limit, key)
                    local next = key + 1
                    if next > limit then
                        return nil
                    end
                    return next
                end

                local function stateAndInitial()
                    return 5, 2
                end

                local text = ""
                for value in iter, stateAndInitial() do
                    text = text .. value
                end
                return text
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaString("345")), result)
    }

    @Test
    fun `generic for closes captured loop locals before next iteration`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local function iter(state, key)
                    local next = key + 1
                    if next > state.n then
                        return nil
                    end
                    return next, state[next]
                end
                local function iterator()
                    return iter, {n = 2, "a", "b"}, 0
                end
                local indexGetters = {}
                local valueGetters = {}
                for index, value in iterator() do
                    indexGetters[index] = function()
                        return index
                    end
                    valueGetters[index] = function()
                        return value
                    end
                end
                return indexGetters[1](), valueGetters[1](),
                    indexGetters[2](), valueGetters[2]()
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(LuaInteger(1), LuaString("a"), LuaInteger(2), LuaString("b")),
            result,
        )
    }

    @Test
    fun `evaluates extra generic for iterator values for side effects`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local count = 0
                local function mark(value)
                    count = count + 1
                    return value
                end

                local function iter()
                    return nil
                end

                for value in iter, nil, nil, mark("extra") do
                end

                return count
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1)), result)
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
    fun `reports source line for runtime errors`() {
        val error = assertFailsWith<LuaVmException> {
            LuaVm().execute(
                Compiler.compile(
                    """
                    local x = 1
                    return "x" + x
                    """.trimIndent(),
                    "runtime-line.lua",
                ),
            )
        }

        assertEquals("attempt to perform arithmetic on string", error.message)
        assertEquals("runtime-line.lua", error.sourceName)
        assertEquals(2, error.line)
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
