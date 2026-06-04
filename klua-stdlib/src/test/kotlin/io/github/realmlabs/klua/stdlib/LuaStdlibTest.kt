package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaStatus
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LuaStdlibTest {
    @Test
    fun `openBase installs type and tostring globals`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return type(nil), type(false), type(1), type("x"), type({}),
                    tostring(nil), tostring(false), tostring(12), tostring("ok")
                """.trimIndent(),
                "stdlib-base.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("nil", state.toString(1))
        assertEquals("boolean", state.toString(2))
        assertEquals("number", state.toString(3))
        assertEquals("string", state.toString(4))
        assertEquals("table", state.toString(5))
        assertEquals("nil", state.toString(6))
        assertEquals("false", state.toString(7))
        assertEquals("12", state.toString(8))
        assertEquals("ok", state.toString(9))
    }

    @Test
    fun `tonumber converts numbers and decimal strings`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return tonumber("42"), tonumber("3.5"), tonumber("bad"), tonumber(7)""", "tonumber.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(3.5, state.toNumber(2))
        assertTrue(state.isNil(3))
        assertEquals(7L, state.toInteger(4))
    }

    @Test
    fun `tonumber converts strings with explicit base`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return tonumber("ff", 16), tonumber("-101", 2), tonumber("gg", 16)""", "tonumber-base.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(255L, state.toInteger(1))
        assertEquals(-5L, state.toInteger(2))
        assertTrue(state.isNil(3))
    }

    @Test
    fun `tonumber rejects out of range base`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return tonumber("10", 1)""", "tonumber-bad-base.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'tonumber' (base out of range)", state.toString(-1))
    }

    @Test
    fun `assert returns arguments when condition is truthy`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return assert("ok", 42)""", "assert-ok.lua"))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("ok", state.toString(1))
        assertEquals(42L, state.toInteger(2))
    }

    @Test
    fun `assert preserves table arguments when condition is truthy`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local value = {name = "kept"}
                local returned = assert(value)
                return returned == value, returned.name
                """.trimIndent(),
                "assert-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("kept", state.toString(2))
    }

    @Test
    fun `assert raises runtime error when condition is false`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return assert(false, "nope")""", "assert-false.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("nope", state.toString(-1))
    }

    @Test
    fun `error raises runtime error with message`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return error("boom")""", "error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("boom", state.toString(-1))
    }

    @Test
    fun `pcall returns protected success and failure results`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function pair(first, second)
                    return first, second
                end
                local ok, first, second = pcall(pair, "a", 2)
                local failed, message = pcall(function() error("boom") end)
                return ok, first, second, failed, message
                """.trimIndent(),
                "pcall.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("a", state.toString(2))
        assertEquals(2L, state.toInteger(3))
        assertFalse(state.toBoolean(4))
        assertEquals("boom", state.toString(5))
    }

    @Test
    fun `pcall preserves table arguments and results`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {name = "klua"}
                local ok, returned, name = pcall(function(input)
                    return input, input.name
                end, values)
                return ok, returned == values, name
                """.trimIndent(),
                "pcall-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("klua", state.toString(3))
    }

    @Test
    fun `xpcall invokes error handlers`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, value = xpcall(function(input)
                    return input .. "!"
                end, function(message)
                    return "handled:" .. message
                end, "done")
                local failed, handled = xpcall(function()
                    error("boom")
                end, function(message)
                    return "handled:" .. message
                end)
                return ok, value, failed, handled
                """.trimIndent(),
                "xpcall.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("done!", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("handled:boom", state.toString(4))
    }

    @Test
    fun `select returns vararg count and suffixes`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local count = select("#", "a", "b", "c")
                local second, third = select(2, "a", "b", "c")
                local last = select(-1, "a", "b", "c")
                return count, second, third, last
                """.trimIndent(),
                "select.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3L, state.toInteger(1))
        assertEquals("b", state.toString(2))
        assertEquals("c", state.toString(3))
        assertEquals("c", state.toString(4))
    }

    @Test
    fun `select preserves table values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local value = {name = "selected"}
                local returned = select(2, "skip", value)
                return returned == value, returned.name
                """.trimIndent(),
                "select-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("selected", state.toString(2))
    }

    @Test
    fun `select rejects out of range indexes`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return select(0, "a")""", "select-bad.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'select' (index out of range)", state.toString(-1))
    }

    @Test
    fun `print writes tab separated line to configured output`() {
        val state = LuaState.create()
        val output = mutableListOf<String>()
        LuaStdlib.openBase(state, Consumer { line -> output += line })

        assertEquals(LuaStatus.OK, state.load("""print("level", 42, false, nil)""", "print.lua"))
        val status = state.pcall(0, -1)
        assertEquals(LuaStatus.OK, status, state.toString(-1))

        assertEquals(listOf("level\t42\tfalse\tnil"), output)
        assertEquals(0, state.getTop())
    }

    @Test
    fun `next iterates raw table entries`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {"a", "b"}
                local firstKey, firstValue = next(values)
                local secondKey, secondValue = next(values, firstKey)
                local done = next(values, secondKey)
                return firstKey, firstValue, secondKey, secondValue, done
                """.trimIndent(),
                "next.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals("a", state.toString(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals("b", state.toString(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `next preserves table keys and values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local key = {}
                local value = {name = "entry"}
                local values = {}
                rawset(values, key, value)
                local foundKey, foundValue = next(values)
                local done = next(values, foundKey)
                return foundKey == key, foundValue == value, foundValue.name, done
                """.trimIndent(),
                "next-table-entries.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("entry", state.toString(3))
        assertTrue(state.isNil(4))
    }

    @Test
    fun `pairs and ipairs return iterator triplets`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {"a", "b"}
                local pairIterator, pairState, pairKey = pairs(values)
                local pairFirstKey, pairFirstValue = pairIterator(pairState, pairKey)
                local pairSecondKey, pairSecondValue = pairIterator(pairState, pairFirstKey)
                local ipairsIterator, ipairsState, ipairsIndex = ipairs(values)
                local ipairsFirstKey, ipairsFirstValue = ipairsIterator(ipairsState, ipairsIndex)
                local ipairsSecondKey, ipairsSecondValue = ipairsIterator(ipairsState, ipairsFirstKey)
                local ipairsDone = ipairsIterator(ipairsState, ipairsSecondKey)
                return pairFirstKey, pairFirstValue, pairSecondKey, pairSecondValue,
                    ipairsFirstKey, ipairsFirstValue, ipairsSecondKey, ipairsSecondValue, ipairsDone
                """.trimIndent(),
                "pairs-ipairs.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals("a", state.toString(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals("b", state.toString(4))
        assertEquals(1L, state.toInteger(5))
        assertEquals("a", state.toString(6))
        assertEquals(2L, state.toInteger(7))
        assertEquals("b", state.toString(8))
        assertTrue(state.isNil(9))
    }

    @Test
    fun `pairs and ipairs report table argument errors`() {
        val pairState = LuaState.create()
        LuaStdlib.openBase(pairState)

        assertEquals(LuaStatus.OK, pairState.load("""return pairs("not-table")""", "pairs-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, pairState.pcall(0, -1))

        assertIs<LuaRuntimeException>(pairState.getLastError())
        assertEquals("bad argument #1 to 'pairs' (table expected)", pairState.toString(-1))

        val ipairsState = LuaState.create()
        LuaStdlib.openBase(ipairsState)

        assertEquals(LuaStatus.OK, ipairsState.load("""return ipairs("not-table")""", "ipairs-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, ipairsState.pcall(0, -1))

        assertIs<LuaRuntimeException>(ipairsState.getLastError())
        assertEquals("bad argument #1 to 'ipairs' (table expected)", ipairsState.toString(-1))
    }

    @Test
    fun `next reports table argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return next("not-table")""", "next-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'next' (table expected)", state.toString(-1))
    }

    @Test
    fun `rawequal compares primitive values and table identity`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local tableValue = {}
                return rawequal(nil, nil),
                    rawequal(1, 1.0),
                    rawequal("x", "x"),
                    rawequal(false, false),
                    rawequal(1, "1"),
                    rawequal(tableValue, tableValue),
                    rawequal({}, {})
                """.trimIndent(),
                "rawequal.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertFalse(state.toBoolean(5))
        assertTrue(state.toBoolean(6))
        assertFalse(state.toBoolean(7))
    }

    @Test
    fun `rawget reads table fields without invoking access syntax`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return rawget({name = "klua"}, "name"), rawget({"a", "b"}, 2), rawget({}, "missing")""", "rawget.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("klua", state.toString(1))
        assertEquals("b", state.toString(2))
        assertTrue(state.isNil(3))
    }

    @Test
    fun `rawget and rawset preserve table keys and values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local key = {}
                local value = {name = "stored"}
                local values = {}
                local returned = rawset(values, key, value)
                local found = rawget(values, key)
                return returned == values, found == value, found.name
                """.trimIndent(),
                "rawget-rawset-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("stored", state.toString(3))
    }

    @Test
    fun `rawget reports table argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawget("not-table", "key")""", "rawget-table-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'rawget' (table expected)", state.toString(-1))
    }

    @Test
    fun `rawget rejects nil keys`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawget({}, nil)""", "rawget-key-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("table index is nil", state.toString(-1))
    }

    @Test
    fun `rawset mutates table fields and returns table`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {}
                local returned = rawset(values, "name", "klua")
                rawset(values, 1, "first")
                rawset(values, "name", nil)
                return returned == values, values[1], values.name, #values
                """.trimIndent(),
                "rawset.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("first", state.toString(2))
        assertTrue(state.isNil(3))
        assertEquals(1L, state.toInteger(4))
    }

    @Test
    fun `rawlen returns string and raw table lengths`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {"a", "b"}
                return rawlen("KLua"), rawlen(values)
                """.trimIndent(),
                "rawlen.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(4L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
    }

    @Test
    fun `rawlen reports argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawlen(42)""", "rawlen-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'rawlen' (table or string expected)", state.toString(-1))
    }

    @Test
    fun `rawset reports table argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawset("not-table", "key", "value")""", "rawset-table-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'rawset' (table expected)", state.toString(-1))
    }

    @Test
    fun `rawset rejects nil keys`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawset({}, nil, "value")""", "rawset-key-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("table index is nil", state.toString(-1))
    }

    @Test
    fun `setmetatable installs table metatable and getmetatable returns it`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local object = {}
                local fallback = {name = "from-metatable"}
                local metatable = {__index = fallback}
                local returned = setmetatable(object, metatable)
                local actualMetatable = getmetatable(object)
                return returned == object, actualMetatable == metatable, object.name, getmetatable({}) == nil
                """.trimIndent(),
                "setmetatable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("from-metatable", state.toString(3))
        assertTrue(state.toBoolean(4))
    }

    @Test
    fun `setmetatable clears table metatable with nil`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local object = {}
                setmetatable(object, {__index = {name = "from-metatable"}})
                setmetatable(object, nil)
                return getmetatable(object), object.name
                """.trimIndent(),
                "setmetatable-clear.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
    }

    @Test
    fun `getmetatable returns nil for values without metatables`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return getmetatable("text"), getmetatable(nil)""", "getmetatable-nil.lua"))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
    }

    @Test
    fun `setmetatable reports table argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return setmetatable("not-table", {})""", "setmetatable-table-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'setmetatable' (table expected)", state.toString(-1))
    }

    @Test
    fun `setmetatable reports metatable argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return setmetatable({}, "not-table")""", "setmetatable-meta-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'setmetatable' (nil or table expected)", state.toString(-1))
    }

    @Test
    fun `openLibs installs base library`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        state.getGlobal("type")

        assertEquals("function", state.typeName(-1))
        assertNull(state.getLastError())
    }

    @Test
    fun `openLibs installs table library`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(LuaStatus.OK, state.load("""return table.concat({"a", "b", "c"})""", "open-libs-table.lua"))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("abc", state.toString(1))
    }

    @Test
    fun `openLibs installs utf8 library`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(LuaStatus.OK, state.load("""return utf8.len("KLua")""", "open-libs-utf8.lua"))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(4L, state.toInteger(1))
    }

    @Test
    fun `openMath installs math numeric functions`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return math.abs(-5), math.floor(3.9), math.ceil(3.1), math.min(4, -2, 8), math.max(4, -2, 8)
                """.trimIndent(),
                "math.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(5L, state.toInteger(1))
        assertEquals(3L, state.toInteger(2))
        assertEquals(4L, state.toInteger(3))
        assertEquals(-2L, state.toInteger(4))
        assertEquals(8L, state.toInteger(5))
    }

    @Test
    fun `math floor and ceil preserve numeric subtype by range`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local floorInteger = math.floor(3.9)
                local ceilInteger = math.ceil(3.1)
                local floorHuge = math.floor(1e20)
                local ceilHuge = math.ceil(-1e20)
                local ceilInfinity = math.ceil(math.huge)
                return floorInteger, math.type(floorInteger),
                    ceilInteger, math.type(ceilInteger),
                    floorHuge, math.type(floorHuge),
                    ceilHuge, math.type(ceilHuge),
                    ceilInfinity, math.type(ceilInfinity)
                """.trimIndent(),
                "math-floor-ceil-type.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3L, state.toInteger(1))
        assertEquals("integer", state.toString(2))
        assertEquals(4L, state.toInteger(3))
        assertEquals("integer", state.toString(4))
        assertEquals(1e20, state.toNumber(5) ?: error("missing floor huge result"), 0.0)
        assertEquals("float", state.toString(6))
        assertEquals(-1e20, state.toNumber(7) ?: error("missing ceil huge result"), 0.0)
        assertEquals("float", state.toString(8))
        assertTrue((state.toNumber(9) ?: error("missing ceil infinity result")).isInfinite())
        assertEquals("float", state.toString(10))
    }

    @Test
    fun `openMath installs trigonometric functions`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return math.sin(0), math.cos(0), math.tan(0)""", "math-trig.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0.0, state.toNumber(1) ?: error("missing sin result"), 1e-12)
        assertEquals(1.0, state.toNumber(2) ?: error("missing cos result"), 1e-12)
        assertEquals(0.0, state.toNumber(3) ?: error("missing tan result"), 1e-12)
    }

    @Test
    fun `openMath installs inverse trigonometric functions`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return math.asin(0), math.acos(1), math.atan(1), math.atan(1, 0)""", "math-inverse-trig.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0.0, state.toNumber(1) ?: error("missing asin result"), 1e-12)
        assertEquals(0.0, state.toNumber(2) ?: error("missing acos result"), 1e-12)
        assertEquals(Math.PI / 4, state.toNumber(3) ?: error("missing atan result"), 1e-12)
        assertEquals(Math.PI / 2, state.toNumber(4) ?: error("missing atan2 result"), 1e-12)
    }

    @Test
    fun `openMath installs exponential and angle functions`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return math.sqrt(9), math.exp(0), math.log(math.exp(1)), math.log(8, 2),
                    math.deg(math.pi), math.rad(180)
                """.trimIndent(),
                "math-exp-angle.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3.0, state.toNumber(1) ?: error("missing sqrt result"), 1e-12)
        assertEquals(1.0, state.toNumber(2) ?: error("missing exp result"), 1e-12)
        assertEquals(1.0, state.toNumber(3) ?: error("missing log result"), 1e-12)
        assertEquals(3.0, state.toNumber(4) ?: error("missing log base result"), 1e-12)
        assertEquals(180.0, state.toNumber(5) ?: error("missing deg result"), 1e-12)
        assertEquals(Math.PI, state.toNumber(6) ?: error("missing rad result"), 1e-12)
    }

    @Test
    fun `openMath installs remainder and fractional functions`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local positiveInteger, positiveFraction = math.modf(3.75)
                local negativeInteger, negativeFraction = math.modf(-3.75)
                return math.fmod(7, 3), math.fmod(-7, 3),
                    positiveInteger, positiveFraction, negativeInteger, negativeFraction
                """.trimIndent(),
                "math-remainder.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1.0, state.toNumber(1) ?: error("missing fmod result"), 1e-12)
        assertEquals(-1.0, state.toNumber(2) ?: error("missing negative fmod result"), 1e-12)
        assertEquals(3.0, state.toNumber(3) ?: error("missing modf integer result"), 1e-12)
        assertEquals(0.75, state.toNumber(4) ?: error("missing modf fraction result"), 1e-12)
        assertEquals(-3.0, state.toNumber(5) ?: error("missing negative modf integer result"), 1e-12)
        assertEquals(-0.75, state.toNumber(6) ?: error("missing negative modf fraction result"), 1e-12)
    }

    @Test
    fun `math fmod preserves integer subtype for integer operands`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local integerRemainder = math.fmod(7, 3)
                local floatRemainder = math.fmod(7.0, 3)
                local minIntegerRemainder = math.fmod(math.mininteger, -1)
                return integerRemainder, math.type(integerRemainder),
                    floatRemainder, math.type(floatRemainder),
                    minIntegerRemainder, math.type(minIntegerRemainder)
                """.trimIndent(),
                "math-fmod-type.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals("integer", state.toString(2))
        assertEquals(1.0, state.toNumber(3) ?: error("missing float fmod result"), 1e-12)
        assertEquals("float", state.toString(4))
        assertEquals(0L, state.toInteger(5))
        assertEquals("integer", state.toString(6))
    }

    @Test
    fun `math fmod reports zero integer divisor errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.fmod(1, 0)""", "math-fmod-zero-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'math.fmod' (zero)", state.toString(-1))
    }

    @Test
    fun `math modf preserves integer subtype for integer parts`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local integerInput, integerFraction = math.modf(3)
                local floatInteger, floatFraction = math.modf(3.75)
                local negativeInteger, negativeFraction = math.modf(-3.75)
                return integerInput, math.type(integerInput), integerFraction, math.type(integerFraction),
                    floatInteger, math.type(floatInteger), floatFraction, math.type(floatFraction),
                    negativeInteger, math.type(negativeInteger), negativeFraction, math.type(negativeFraction)
                """.trimIndent(),
                "math-modf-type.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3L, state.toInteger(1))
        assertEquals("integer", state.toString(2))
        assertEquals(0.0, state.toNumber(3) ?: error("missing integer fraction"), 1e-12)
        assertEquals("float", state.toString(4))
        assertEquals(3L, state.toInteger(5))
        assertEquals("integer", state.toString(6))
        assertEquals(0.75, state.toNumber(7) ?: error("missing float fraction"), 1e-12)
        assertEquals("float", state.toString(8))
        assertEquals(-3L, state.toInteger(9))
        assertEquals("integer", state.toString(10))
        assertEquals(-0.75, state.toNumber(11) ?: error("missing negative fraction"), 1e-12)
        assertEquals("float", state.toString(12))
    }

    @Test
    fun `openMath installs numeric constants`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return math.pi, math.huge > 1e308, math.maxinteger, math.mininteger""", "math-constants.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(Math.PI, state.toNumber(1) ?: error("missing pi result"), 1e-12)
        assertTrue(state.toBoolean(2))
        assertEquals(Long.MAX_VALUE, state.toInteger(3))
        assertEquals(Long.MIN_VALUE, state.toInteger(4))
    }

    @Test
    fun `math integer helpers convert and compare integers`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return math.tointeger(3.0),
                    math.tointeger(3.5),
                    math.tointeger("42"),
                    math.ult(0, -1),
                    math.ult(-1, 0),
                    math.ult(math.mininteger, math.maxinteger)
                """.trimIndent(),
                "math-integers.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3L, state.toInteger(1))
        assertTrue(state.isNil(2))
        assertEquals(42L, state.toInteger(3))
        assertTrue(state.toBoolean(4))
        assertFalse(state.toBoolean(5))
        assertFalse(state.toBoolean(6))
    }

    @Test
    fun `math tointeger reports missing argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.tointeger()""", "math-tointeger-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'math.tointeger' (value expected)", state.toString(-1))
    }

    @Test
    fun `math type classifies integer and float numbers`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return math.type(1),
                    math.type(1.5),
                    math.type("1"),
                    math.type(nil),
                    math.type({})
                """.trimIndent(),
                "math-type.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("integer", state.toString(1))
        assertEquals("float", state.toString(2))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `math type reports missing argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.type()""", "math-type-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'math.type' (value expected)", state.toString(-1))
    }

    @Test
    fun `math min and max preserve selected numeric subtype`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local maxInteger = math.max(2, 1.5)
                local minFloat = math.min(2, 1.5)
                local maxFloat = math.max(1, 2.0)
                local minInteger = math.min(1, 2.0)
                return maxInteger, math.type(maxInteger),
                    minFloat, math.type(minFloat),
                    maxFloat, math.type(maxFloat),
                    minInteger, math.type(minInteger)
                """.trimIndent(),
                "math-min-max-type.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(2L, state.toInteger(1))
        assertEquals("integer", state.toString(2))
        assertEquals(1.5, state.toNumber(3) ?: error("missing min float"), 1e-12)
        assertEquals("float", state.toString(4))
        assertEquals(2.0, state.toNumber(5) ?: error("missing max float"), 1e-12)
        assertEquals("float", state.toString(6))
        assertEquals(1L, state.toInteger(7))
        assertEquals("integer", state.toString(8))
    }

    @Test
    fun `math random supports ranges and deterministic seeds`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                math.randomseed(123)
                local first = math.random()
                local ranged = math.random(10)
                local offset = math.random(5, 7)
                math.randomseed(123)
                return first == math.random(),
                    ranged == math.random(10),
                    offset == math.random(5, 7),
                    first >= 0 and first < 1,
                    ranged >= 1 and ranged <= 10,
                    offset >= 5 and offset <= 7
                """.trimIndent(),
                "math-random.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        for (index in 1..6) {
            assertTrue(state.toBoolean(index))
        }
    }

    @Test
    fun `math randomseed returns effective seeds and supports two seed values`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local firstSeed, secondSeed = math.randomseed(123, 456)
                local first = math.random(100)
                local generatedFirstSeed, generatedSecondSeed = math.randomseed()
                local generated = math.random(100)
                math.randomseed(123, 456)
                local repeated = math.random(100)
                local singleFirstSeed, singleSecondSeed = math.randomseed(789)
                return firstSeed, secondSeed, first == repeated,
                    generatedFirstSeed ~= nil, generatedSecondSeed == 0, generated >= 1 and generated <= 100,
                    singleFirstSeed, singleSecondSeed
                """.trimIndent(),
                "math-randomseed.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(123L, state.toInteger(1))
        assertEquals(456L, state.toInteger(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.toBoolean(6))
        assertEquals(789L, state.toInteger(7))
        assertEquals(0L, state.toInteger(8))
    }

    @Test
    fun `math random supports wide integer ranges`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                math.randomseed(456)
                local upperOnly = math.random(3000000000)
                local wideRange = math.random(-3000000000, 3000000000)
                local fullRange = math.random(math.mininteger, math.maxinteger)
                math.randomseed(456)
                local repeated = math.random(3000000000)
                return upperOnly >= 1 and upperOnly <= 3000000000,
                    wideRange >= -3000000000 and wideRange <= 3000000000,
                    fullRange ~= nil,
                    upperOnly == repeated
                """.trimIndent(),
                "math-random-wide.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        for (index in 1..4) {
            assertTrue(state.toBoolean(index))
        }
    }

    @Test
    fun `math functions report numeric argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.abs("x")""", "math-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'math.abs' (number expected)", state.toString(-1))
    }

    @Test
    fun `math random reports empty interval errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.random(10, 1)""", "math-random-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'math.random' (interval is empty)", state.toString(-1))
    }

    @Test
    fun `math randomseed reports arity errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.randomseed(1, 2, 3)""", "math-randomseed-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("wrong number of arguments to 'math.randomseed'", state.toString(-1))
    }

    @Test
    fun `trigonometric functions report numeric argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.sin("x")""", "math-trig-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'math.sin' (number expected)", state.toString(-1))
    }

    @Test
    fun `openString installs basic string functions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return string.len("hello"), string.lower("HeLLo"), string.upper("HeLLo"),
                    string.reverse("abc"), string.rep("ha", 3), string.sub("abcdef", 2, 4),
                    string.sub("abcdef", -3, -1), string.sub("abcdef", 4, 2)
                """.trimIndent(),
                "string.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(5L, state.toInteger(1))
        assertEquals("hello", state.toString(2))
        assertEquals("HELLO", state.toString(3))
        assertEquals("cba", state.toString(4))
        assertEquals("hahaha", state.toString(5))
        assertEquals("bcd", state.toString(6))
        assertEquals("def", state.toString(7))
        assertEquals("", state.toString(8))
    }

    @Test
    fun `string rep supports separators and empty repetitions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return string.rep("ha", 3, "-"), string.rep("ha", 0, "-")""", "string-rep.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("ha-ha-ha", state.toString(1))
        assertEquals("", state.toString(2))
    }

    @Test
    fun `string byte and char convert byte values`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first, second, third = string.byte("ABC", 1, 3)
                local last = string.byte("ABC", -1)
                local empty = string.char()
                return first, second, third, last, string.char(65, 66, 67), empty
                """.trimIndent(),
                "string-byte-char.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(65L, state.toInteger(1))
        assertEquals(66L, state.toInteger(2))
        assertEquals(67L, state.toInteger(3))
        assertEquals(67L, state.toInteger(4))
        assertEquals("ABC", state.toString(5))
        assertEquals("", state.toString(6))
    }

    @Test
    fun `string find locates literal substrings`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local firstStart, firstEnd = string.find("hello", "ell")
                local secondStart, secondEnd = string.find("banana", "an", 3)
                local lastStart, lastEnd = string.find("banana", "an", -4)
                local dotStart, dotEnd = string.find("a.b", ".", 1, true)
                local patternStart, patternEnd = string.find("abc", "a.c")
                local digitStart, digitEnd = string.find("a1", "%d")
                local literalDotStart, literalDotEnd = string.find("a.b", "%.")
                local anchorStart, anchorEnd = string.find("abc", "^a")
                local endStart, endEnd = string.find("abc", "c$")
                local vowelStart, vowelEnd = string.find("cat", "[aeiou]")
                local rangeStart, rangeEnd = string.find("x5", "[0-9]")
                local negatedStart, negatedEnd = string.find("abc", "[^a]")
                return firstStart, firstEnd, secondStart, secondEnd, lastStart, lastEnd,
                    dotStart, dotEnd, patternStart, patternEnd, digitStart, digitEnd,
                    literalDotStart, literalDotEnd, anchorStart, anchorEnd, endStart, endEnd,
                    vowelStart, vowelEnd, rangeStart, rangeEnd, negatedStart, negatedEnd,
                    string.find("hello", "xyz"), string.find("abc", "^b")
                """.trimIndent(),
                "string-find.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(2L, state.toInteger(1))
        assertEquals(4L, state.toInteger(2))
        assertEquals(4L, state.toInteger(3))
        assertEquals(5L, state.toInteger(4))
        assertEquals(4L, state.toInteger(5))
        assertEquals(5L, state.toInteger(6))
        assertEquals(2L, state.toInteger(7))
        assertEquals(2L, state.toInteger(8))
        assertEquals(1L, state.toInteger(9))
        assertEquals(3L, state.toInteger(10))
        assertEquals(2L, state.toInteger(11))
        assertEquals(2L, state.toInteger(12))
        assertEquals(2L, state.toInteger(13))
        assertEquals(2L, state.toInteger(14))
        assertEquals(1L, state.toInteger(15))
        assertEquals(1L, state.toInteger(16))
        assertEquals(3L, state.toInteger(17))
        assertEquals(3L, state.toInteger(18))
        assertEquals(2L, state.toInteger(19))
        assertEquals(2L, state.toInteger(20))
        assertEquals(2L, state.toInteger(21))
        assertEquals(2L, state.toInteger(22))
        assertEquals(2L, state.toInteger(23))
        assertEquals(2L, state.toInteger(24))
        assertTrue(state.isNil(25))
        assertTrue(state.isNil(26))
    }

    @Test
    fun `string find reports unsupported patterns`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.find("abc", "a^")""", "string-find-pattern.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("string patterns are not supported", state.toString(-1))
    }

    @Test
    fun `string patterns match additional lua classes`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local zero = string.char(0)
                local newline = string.char(10)
                return string.match("abc", "%l"),
                    string.match("ABC", "%u"),
                    string.match("id=2f", "%x%x"),
                    string.match("a!", "%p"),
                    string.match("a" .. newline, "%c"),
                    string.match(" x", "%g"),
                    string.match("a" .. zero .. "b", "%z"),
                    string.match("a1", "%L"),
                    string.match("A1", "%U"),
                    string.match("!a", "%P"),
                    string.match("f!", "%X"),
                    string.match(zero .. "a", "%Z"),
                    string.match(newline .. "A", "%C"),
                    string.match("A ", "%G")
                """.trimIndent(),
                "string-pattern-classes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("a", state.toString(1))
        assertEquals("A", state.toString(2))
        assertEquals("2f", state.toString(3))
        assertEquals("!", state.toString(4))
        assertEquals("\n", state.toString(5))
        assertEquals("x", state.toString(6))
        assertEquals("\u0000", state.toString(7))
        assertEquals("1", state.toString(8))
        assertEquals("1", state.toString(9))
        assertEquals("a", state.toString(10))
        assertEquals("!", state.toString(11))
        assertEquals("a", state.toString(12))
        assertEquals("A", state.toString(13))
        assertEquals(" ", state.toString(14))
    }

    @Test
    fun `string bracket patterns support percent classes`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return string.match("a5", "[%d]"),
                    string.match("aF", "[A-F%d]"),
                    string.match("a-", "[%-]"),
                    string.match("a!", "[%p]"),
                    string.match("5a", "[^%d]")
                """.trimIndent(),
                "string-bracket-percent-classes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("5", state.toString(1))
        assertEquals("F", state.toString(2))
        assertEquals("-", state.toString(3))
        assertEquals("!", state.toString(4))
        assertEquals("a", state.toString(5))
    }

    @Test
    fun `string patterns support optional items`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local shortStart, shortEnd = string.find("ac", "ab?c")
                local longStart, longEnd = string.find("abc", "ab?c")
                local replaced, count = string.gsub("color colour", "colou?r", "x")
                local iterator = string.gmatch("ab ac abc", "ab?c")
                return shortStart, shortEnd, longStart, longEnd,
                    string.match("color", "colou?r"),
                    string.match("colour", "colou?r"),
                    replaced, count,
                    iterator(), iterator(), iterator()
                """.trimIndent(),
                "string-pattern-optional.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(3L, state.toInteger(4))
        assertEquals("color", state.toString(5))
        assertEquals("colour", state.toString(6))
        assertEquals("x x", state.toString(7))
        assertEquals(2L, state.toInteger(8))
        assertEquals("ac", state.toString(9))
        assertEquals("abc", state.toString(10))
        assertTrue(state.isNil(11))
    }

    @Test
    fun `string patterns support greedy repetitions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local plusStart, plusEnd = string.find("aaab", "a+b")
                local starStart, starEnd = string.find("b", "a*b")
                local replaced, count = string.gsub("a1 22 333", "%d+", "n")
                local iterator = string.gmatch("a12b3", "%d+")
                return plusStart, plusEnd, starStart, starEnd,
                    string.match("aaab", "a*"),
                    string.match("b", "a+"),
                    replaced, count,
                    iterator(), iterator(), iterator()
                """.trimIndent(),
                "string-pattern-repetition.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(4L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals("aaa", state.toString(5))
        assertTrue(state.isNil(6))
        assertEquals("an n n", state.toString(7))
        assertEquals(3L, state.toInteger(8))
        assertEquals("12", state.toString(9))
        assertEquals("3", state.toString(10))
        assertTrue(state.isNil(11))
    }

    @Test
    fun `string patterns support minimal repetitions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local greedyStart, greedyEnd = string.find("a123b456b", "a.*b")
                local minimalStart, minimalEnd = string.find("a123b456b", "a.-b")
                local replaced, count = string.gsub("a1b a22b", "a.-b", "x")
                local iterator = string.gmatch("a1b a22b", "a.-b")
                return greedyStart, greedyEnd, minimalStart, minimalEnd,
                    string.match("a123b456b", "a.*b"),
                    string.match("a123b456b", "a.-b"),
                    replaced, count,
                    iterator(), iterator(), iterator()
                """.trimIndent(),
                "string-pattern-minimal.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(9L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(5L, state.toInteger(4))
        assertEquals("a123b456b", state.toString(5))
        assertEquals("a123b", state.toString(6))
        assertEquals("x x", state.toString(7))
        assertEquals(2L, state.toInteger(8))
        assertEquals("a1b", state.toString(9))
        assertEquals("a22b", state.toString(10))
        assertTrue(state.isNil(11))
    }

    @Test
    fun `string patterns advance after empty matches`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local replaced, count = string.gsub("abc", "x*", "-")
                local iterator = string.gmatch("abc", "x*")
                return replaced, count,
                    iterator(), iterator(), iterator(), iterator(), iterator()
                """.trimIndent(),
                "string-pattern-empty.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("-a-b-c-", state.toString(1))
        assertEquals(4L, state.toInteger(2))
        assertEquals("", state.toString(3))
        assertEquals("", state.toString(4))
        assertEquals("", state.toString(5))
        assertEquals("", state.toString(6))
        assertTrue(state.isNil(7))
    }

    @Test
    fun `string patterns return captures`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first, last, letters, digits = string.find("abc123", "(%a+)(%d+)")
                local matchLetters, matchDigits = string.match("abc123", "(%a+)(%d+)")
                local replaced, count = string.gsub("abc123", "(%a+)(%d+)", "%2-%1")
                return first, last, letters, digits, matchLetters, matchDigits, replaced, count
                """.trimIndent(),
                "string-pattern-captures.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(6L, state.toInteger(2))
        assertEquals("abc", state.toString(3))
        assertEquals("123", state.toString(4))
        assertEquals("abc", state.toString(5))
        assertEquals("123", state.toString(6))
        assertEquals("123-abc", state.toString(7))
        assertEquals(1L, state.toInteger(8))
    }

    @Test
    fun `string patterns support backreferences`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first, last, word = string.find("go go stop", "(%a+)%s+%1")
                local matched = string.match("go go stop", "(%a+)%s+%1")
                local replaced, count = string.gsub("go go stop", "(%a+)%s+%1", "%1")
                return first, last, word, matched, replaced, count
                """.trimIndent(),
                "string-pattern-backreferences.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(5L, state.toInteger(2))
        assertEquals("go", state.toString(3))
        assertEquals("go", state.toString(4))
        assertEquals("go stop", state.toString(5))
        assertEquals(1L, state.toInteger(6))
    }

    @Test
    fun `string patterns support balanced matches`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first, last = string.find("a (b (c) d) e", "%b()")
                local matched = string.match("a (b (c) d) e", "%b()")
                local replaced, count = string.gsub("a (b) c (d)", "%b()", "x")
                local iterator = string.gmatch("[a] [b]", "%b[]")
                local missing = string.match("(abc", "%b()")
                local quoted = string.match("'a' b", "%b''")
                return first, last, matched, replaced, count, iterator(), iterator(), iterator(), missing, quoted
                """.trimIndent(),
                "string-pattern-balanced.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3L, state.toInteger(1))
        assertEquals(11L, state.toInteger(2))
        assertEquals("(b (c) d)", state.toString(3))
        assertEquals("a x c x", state.toString(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals("[a]", state.toString(6))
        assertEquals("[b]", state.toString(7))
        assertTrue(state.isNil(8))
        assertTrue(state.isNil(9))
        assertEquals("'a'", state.toString(10))
    }

    @Test
    fun `string patterns support frontier matches`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first, last = string.find("hello world", "%f[%a]world")
                local matched = string.match("hello world", "%f[%a]world")
                local replaced, count = string.gsub("one two", "%f[%a]", "|")
                local iterator = string.gmatch("one, two", "%f[%a]%a+")
                local endFirst, endLast = string.find("word!", "%f[%A]")
                local missing = string.match("hello", "%f[%d]%a")
                return first, last, matched, replaced, count, iterator(), iterator(), iterator(),
                    endFirst, endLast, missing
                """.trimIndent(),
                "string-pattern-frontier.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(7L, state.toInteger(1))
        assertEquals(11L, state.toInteger(2))
        assertEquals("world", state.toString(3))
        assertEquals("|one |two", state.toString(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals("one", state.toString(6))
        assertEquals("two", state.toString(7))
        assertTrue(state.isNil(8))
        assertEquals(5L, state.toInteger(9))
        assertEquals(4L, state.toInteger(10))
        assertTrue(state.isNil(11))
    }

    @Test
    fun `string format renders common conversions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return string.format("name=%s count=%03d int=%i unsigned=%u ratio=%.2f pct=%% char=%c quoted=%q hex=%x",
                    "klua", 7, -2, 9, 1.25, 65, "a\"b", 255)
                """.trimIndent(),
                "string-format.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("""name=klua count=007 int=-2 unsigned=9 ratio=1.25 pct=% char=A quoted="a\"b" hex=ff""", state.toString(1))
    }

    @Test
    fun `string format quotes primitive literals`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return string.format("%q %q %q %q", nil, true, false, 42),
                    string.format("%q", string.char(7, 8, 12, 11)),
                    string.format("%q", "a" .. string.char(0, 31, 127) .. "b")
                """.trimIndent(),
                "string-format-quote.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("nil true false 42", state.toString(1))
        assertEquals("\"\\a\\b\\f\\v\"", state.toString(2))
        assertEquals("\"a\\000\\031\\127b\"", state.toString(3))
    }

    @Test
    fun `string format applies width to character conversions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.format("%3c|%-3c", 65, 66)""",
                "string-format-char-width.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("  A|B  ", state.toString(1))
    }

    @Test
    fun `string format renders unsigned integer conversions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.format("%u|%021u", -1, -1)""",
                "string-format-unsigned.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("18446744073709551615|018446744073709551615", state.toString(1))
    }

    @Test
    fun `string format applies precision to integer conversions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.format("%.3d|%8.3d|%-8.3d|%+.3d|%.4x|%.21u", 7, 7, 7, 7, 255, -1)""",
                "string-format-integer-precision.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("007|     007|007     |+007|00ff|018446744073709551615", state.toString(1))
    }

    @Test
    fun `string format handles integer precision edge cases`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.format("%.0d|%#.0o|%#.4x|%#.4X", 0, 0, 255, 255)""",
                "string-format-integer-precision-edges.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("|0|0x00ff|0X00FF", state.toString(1))
    }

    @Test
    fun `string format reports argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.format("%d", "bad")""", "string-format-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'string.format' (integer expected)", state.toString(-1))
    }

    @Test
    fun `string format reports char range errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.format("%c", 256)""", "string-format-char-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'string.format' (value out of range)", state.toString(-1))
    }

    @Test
    fun `string gsub replaces literal matches`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local all, allCount = string.gsub("banana", "an", "ON")
                local one, oneCount = string.gsub("banana", "an", "ON", 1)
                local none, noneCount = string.gsub("banana", "zz", "ON")
                local unchanged, unchangedCount = string.gsub("banana", "an", "ON", 0)
                local wildcard, wildcardCount = string.gsub("abc", ".", "x", 2)
                local digits, digitsCount = string.gsub("a1b2", "%d", "x")
                local bracketDigits, bracketDigitsCount = string.gsub("a1b2", "[0-9]", "x")
                return all, allCount, one, oneCount, none, noneCount, unchanged, unchangedCount,
                    wildcard, wildcardCount, digits, digitsCount, bracketDigits, bracketDigitsCount
                """.trimIndent(),
                "string-gsub.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("bONONa", state.toString(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals("bONana", state.toString(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals("banana", state.toString(5))
        assertEquals(0L, state.toInteger(6))
        assertEquals("banana", state.toString(7))
        assertEquals(0L, state.toInteger(8))
        assertEquals("xxc", state.toString(9))
        assertEquals(2L, state.toInteger(10))
        assertEquals("axbx", state.toString(11))
        assertEquals(2L, state.toInteger(12))
        assertEquals("axbx", state.toString(13))
        assertEquals(2L, state.toInteger(14))
    }

    @Test
    fun `string gsub expands replacement percents`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local quoted, quotedCount = string.gsub("abc", "%a+", "[%0]")
                local percent, percentCount = string.gsub("a.b", "%.", "%%")
                return quoted, quotedCount, percent, percentCount
                """.trimIndent(),
                "string-gsub-replacement.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("[abc]", state.toString(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals("a%b", state.toString(3))
        assertEquals(1L, state.toInteger(4))
    }

    @Test
    fun `string gsub accepts function and table replacements`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local swapped, swappedCount = string.gsub("a1 b22", "(%a+)(%d+)", function(letters, digits)
                    return digits .. letters
                end)
                local wrapped, wrappedCount = string.gsub("ab", "%a", function(match)
                    return "[" .. match .. "]"
                end)
                local kept, keptCount = string.gsub("a b", "%a", function(match)
                    if match == "a" then
                        return false
                    end
                    return "B"
                end)
                local mapped, mappedCount = string.gsub("hello world unknown", "%a+", {
                    hello = "hi",
                    world = "earth",
                })
                local numbered, numberedCount = string.gsub("one two", "%a+", {
                    one = 1,
                    two = false,
                })
                return swapped, swappedCount, wrapped, wrappedCount, kept, keptCount,
                    mapped, mappedCount, numbered, numberedCount
                """.trimIndent(),
                "string-gsub-replacement-types.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("1a 22b", state.toString(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals("[a][b]", state.toString(3))
        assertEquals(2L, state.toInteger(4))
        assertEquals("a B", state.toString(5))
        assertEquals(2L, state.toInteger(6))
        assertEquals("hi earth unknown", state.toString(7))
        assertEquals(3L, state.toInteger(8))
        assertEquals("1 two", state.toString(9))
        assertEquals(2L, state.toInteger(10))
    }

    @Test
    fun `string gsub reports unsupported patterns`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.gsub("abc", "a^", "x")""", "string-gsub-pattern.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("string patterns are not supported", state.toString(-1))
    }

    @Test
    fun `string gsub reports unsupported replacement captures`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.gsub("abc", "a", "%1")""", "string-gsub-capture-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #3 to 'string.gsub' (invalid capture index %1)", state.toString(-1))
    }

    @Test
    fun `string gmatch iterates literal matches`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator = string.gmatch("banana", "an")
                local first = iterator()
                local second = iterator()
                local done = iterator()
                local wildcard = string.gmatch("ab", ".")
                local wildcardFirst = wildcard()
                local wildcardSecond = wildcard()
                local wildcardDone = wildcard()
                local letters = string.gmatch("a1b", "%a")
                local letterFirst = letters()
                local letterSecond = letters()
                local letterDone = letters()
                local bracketLetters = string.gmatch("a1b2", "[a-z]")
                local bracketFirst = bracketLetters()
                local bracketSecond = bracketLetters()
                local bracketDone = bracketLetters()
                local collector = string.gmatch("xx-xx", "xx")
                local collected = ""
                while true do
                    local match = collector()
                    if match == nil then
                        break
                    end
                    collected = collected .. match
                end
                return first, second, done, wildcardFirst, wildcardSecond, wildcardDone,
                    letterFirst, letterSecond, letterDone, bracketFirst, bracketSecond, bracketDone, collected
                """.trimIndent(),
                "string-gmatch.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("an", state.toString(1))
        assertEquals("an", state.toString(2))
        assertTrue(state.isNil(3))
        assertEquals("a", state.toString(4))
        assertEquals("b", state.toString(5))
        assertTrue(state.isNil(6))
        assertEquals("a", state.toString(7))
        assertEquals("b", state.toString(8))
        assertTrue(state.isNil(9))
        assertEquals("a", state.toString(10))
        assertEquals("b", state.toString(11))
        assertTrue(state.isNil(12))
        assertEquals("xxxx", state.toString(13))
    }

    @Test
    fun `string gmatch returns captures`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator = string.gmatch("a1 b22", "(%a+)(%d+)")
                local firstLetters, firstDigits = iterator()
                local secondLetters, secondDigits = iterator()
                local done = iterator()
                return firstLetters, firstDigits, secondLetters, secondDigits, done
                """.trimIndent(),
                "string-gmatch-captures.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("a", state.toString(1))
        assertEquals("1", state.toString(2))
        assertEquals("b", state.toString(3))
        assertEquals("22", state.toString(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `string gmatch reports unsupported patterns`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator = string.gmatch("abc", "a^")
                return iterator()
                """.trimIndent(),
                "string-gmatch-pattern.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("string patterns are not supported", state.toString(-1))
    }

    @Test
    fun `openUtf8 installs char codepoint and len`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local smile = utf8.char(128512)
                local text = "A" .. smile .. "Z"
                return smile, utf8.len(text), utf8.codepoint(text, 1), utf8.codepoint(text, 2), utf8.codepoint(text, -1)
                """.trimIndent(),
                "utf8.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("\uD83D\uDE00", state.toString(1))
        assertEquals(3L, state.toInteger(2))
        assertEquals(65L, state.toInteger(3))
        assertEquals(128512L, state.toInteger(4))
        assertEquals(90L, state.toInteger(5))
    }

    @Test
    fun `utf8 codepoint returns ranges`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(LuaStatus.OK, state.load("""return utf8.codepoint("abc", 2, 3)""", "utf8-codepoint-range.lua"))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(98L, state.toInteger(1))
        assertEquals(99L, state.toInteger(2))
    }

    @Test
    fun `utf8 codes returns codepoint iterator`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator = utf8.codes("A" .. utf8.char(128512))
                local firstIndex, firstCode = iterator()
                local secondIndex, secondCode = iterator()
                local done = iterator()
                return firstIndex, firstCode, secondIndex, secondCode, done
                """.trimIndent(),
                "utf8-codes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(65L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals(128512L, state.toInteger(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `utf8 charpattern matches utf8 characters`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator = string.gmatch("A" .. utf8.char(233) .. "Z", utf8.charpattern)
                return iterator(), iterator(), iterator(), iterator()
                """.trimIndent(),
                "utf8-charpattern.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("A", state.toString(1))
        assertEquals("é", state.toString(2))
        assertEquals("Z", state.toString(3))
        assertTrue(state.isNil(4))
    }

    @Test
    fun `utf8 codes reports string argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(LuaStatus.OK, state.load("""return utf8.codes({})""", "utf8-codes-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'utf8.codes' (string expected)", state.toString(-1))
    }

    @Test
    fun `utf8 len returns zero for empty strings`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(LuaStatus.OK, state.load("""return utf8.len("")""", "utf8-len-empty.lua"))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0L, state.toInteger(1))
    }

    @Test
    fun `utf8 offset returns codepoint positions`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local text = "A" .. utf8.char(128512) .. "Z"
                return utf8.offset(text, 2),
                    utf8.offset(text, -1),
                    utf8.offset(text, 1, 2),
                    utf8.offset(text, 0, 2),
                    utf8.offset(text, 3, 2)
                """.trimIndent(),
                "utf8-offset.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(2L, state.toInteger(1))
        assertEquals(3L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals(2L, state.toInteger(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `utf8 offset reports position errors`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(LuaStatus.OK, state.load("""return utf8.offset("abc", 1, 5)""", "utf8-offset-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #3 to 'utf8.offset' (position out of range)", state.toString(-1))
    }

    @Test
    fun `utf8 char rejects invalid code points`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(LuaStatus.OK, state.load("""return utf8.char(55296)""", "utf8-char-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'utf8.char' (value out of range)", state.toString(-1))
    }

    @Test
    fun `string match returns literal matches`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return string.match("hello", "ell"),
                    string.match("banana", "an", 3),
                    string.match("banana", "an", -4),
                    string.match("abc", "a.c"),
                    string.match("a1", "%a%d"),
                    string.match("abc", "^a"),
                    string.match("abc", "c$"),
                    string.match("cat", "[aeiou]"),
                    string.match("x5", "[0-9]"),
                    string.match("abc", "[^a]"),
                    string.match("hello", "xyz")
                """.trimIndent(),
                "string-match.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("ell", state.toString(1))
        assertEquals("an", state.toString(2))
        assertEquals("an", state.toString(3))
        assertEquals("abc", state.toString(4))
        assertEquals("a1", state.toString(5))
        assertEquals("a", state.toString(6))
        assertEquals("c", state.toString(7))
        assertEquals("a", state.toString(8))
        assertEquals("5", state.toString(9))
        assertEquals("b", state.toString(10))
        assertTrue(state.isNil(11))
    }

    @Test
    fun `string match reports unsupported patterns`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.match("abc", "a^")""", "string-match-pattern.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("string patterns are not supported", state.toString(-1))
    }

    @Test
    fun `string byte returns no values for empty ranges`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.byte("ABC", 4, 2)""", "string-byte-empty.lua"))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0, state.getTop())
    }

    @Test
    fun `string functions report argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.rep("x", "bad")""", "string-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'string.rep' (integer expected)", state.toString(-1))
    }

    @Test
    fun `string char reports byte range errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.char(256)""", "string-char-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'string.char' (value out of range)", state.toString(-1))
    }

    @Test
    fun `openTable installs table concat`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return table.concat({"a", "b", "c"}),
                    table.concat({"a", "b", "c"}, "-"),
                    table.concat({"a", "b", "c"}, "-", 2, 3),
                    table.concat({"a", "b", "c"}, "-", 4, 3)
                """.trimIndent(),
                "table-concat.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("abc", state.toString(1))
        assertEquals("a-b-c", state.toString(2))
        assertEquals("b-c", state.toString(3))
        assertEquals("", state.toString(4))
    }

    @Test
    fun `table concat reports table argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.concat("not-table")""", "table-concat-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'table.concat' (table expected)", state.toString(-1))
    }

    @Test
    fun `table insert mutates list values`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {"a", "c"}
                table.insert(values, "d")
                table.insert(values, 2, "b")
                return values[1], values[2], values[3], values[4], #values
                """.trimIndent(),
                "table-insert.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("a", state.toString(1))
        assertEquals("b", state.toString(2))
        assertEquals("c", state.toString(3))
        assertEquals("d", state.toString(4))
        assertEquals(4L, state.toInteger(5))
    }

    @Test
    fun `table insert reports argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.insert("not-table", "x")""", "table-insert-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'table.insert' (table expected)", state.toString(-1))
    }

    @Test
    fun `table pack returns table with argument count`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local packed = table.pack("a", nil, "c")
                local count = select("#", table.unpack(packed, 1, packed.n))
                return packed[1], packed[2], packed[3], packed.n, count
                """.trimIndent(),
                "table-pack.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("a", state.toString(1))
        assertTrue(state.isNil(2))
        assertEquals("c", state.toString(3))
        assertEquals(3L, state.toInteger(4))
        assertEquals(3L, state.toInteger(5))
    }

    @Test
    fun `table pack and unpack preserve table values`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local nested = {name = "inner"}
                local packed = table.pack(nested)
                local unpacked = table.unpack(packed, 1, packed.n)
                return packed[1] == nested, unpacked == nested, unpacked.name
                """.trimIndent(),
                "table-pack-unpack-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("inner", state.toString(3))
    }

    @Test
    fun `table move copies ranges and returns destination table`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local source = {"a", "b", "c"}
                local destination = {"x"}
                local returned = table.move(source, 1, 2, 2, destination)
                returned[4] = "tail"
                return destination[1], destination[2], destination[3], destination[4], returned == destination
                """.trimIndent(),
                "table-move-destination.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("x", state.toString(1))
        assertEquals("a", state.toString(2))
        assertEquals("b", state.toString(3))
        assertEquals("tail", state.toString(4))
        assertTrue(state.toBoolean(5))
    }

    @Test
    fun `table move preserves table values`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local nested = {name = "inner"}
                local source = {nested}
                local destination = {}
                table.move(source, 1, 1, 1, destination)
                return source[1] == nested, destination[1] == nested, destination[1].name
                """.trimIndent(),
                "table-move-table-values.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("inner", state.toString(3))
    }

    @Test
    fun `table move handles overlapping self moves`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {"a", "b", "c", "d"}
                local returned = table.move(values, 1, 3, 2)
                return values[1], values[2], values[3], values[4], returned == values
                """.trimIndent(),
                "table-move-overlap.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("a", state.toString(1))
        assertEquals("a", state.toString(2))
        assertEquals("b", state.toString(3))
        assertEquals("c", state.toString(4))
        assertTrue(state.toBoolean(5))
    }

    @Test
    fun `table move reports argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.move({}, 1, 1, 1, "not-table")""", "table-move-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #5 to 'table.move' (table expected)", state.toString(-1))
    }

    @Test
    fun `table remove mutates list values and returns removed values`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {"a", "b", "c", "d"}
                local middle = table.remove(values, 2)
                local last = table.remove(values)
                local trailing = table.remove(values, #values + 1)
                local empty = {}
                local emptyRemoved = table.remove(empty, 0)
                return middle, last, trailing, emptyRemoved, values[1], values[2], values[3], #values
                """.trimIndent(),
                "table-remove.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("b", state.toString(1))
        assertEquals("d", state.toString(2))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
        assertEquals("a", state.toString(5))
        assertEquals("c", state.toString(6))
        assertTrue(state.isNil(7))
        assertEquals(2L, state.toInteger(8))
    }

    @Test
    fun `table remove reports table argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.remove("not-table")""", "table-remove-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'table.remove' (table expected)", state.toString(-1))
    }

    @Test
    fun `table remove reports position argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.remove({"a"}, 3)""", "table-remove-position-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'table.remove' (position out of bounds)", state.toString(-1))
    }

    @Test
    fun `table sort orders numeric and string lists`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numbers = {3, 1, 2}
                local names = {"beta", "alpha", "gamma"}
                table.sort(numbers)
                table.sort(names)
                return numbers[1], numbers[2], numbers[3], names[1], names[2], names[3]
                """.trimIndent(),
                "table-sort.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(3L, state.toInteger(3))
        assertEquals("alpha", state.toString(4))
        assertEquals("beta", state.toString(5))
        assertEquals("gamma", state.toString(6))
    }

    @Test
    fun `table sort uses comparator functions`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numbers = {1, 3, 2}
                local names = {"ccc", "a", "bb"}
                table.sort(numbers, function(a, b) return a > b end)
                table.sort(names, function(a, b) return #a < #b end)
                return numbers[1], numbers[2], numbers[3], names[1], names[2], names[3]
                """.trimIndent(),
                "table-sort-comparator.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals("a", state.toString(4))
        assertEquals("bb", state.toString(5))
        assertEquals("ccc", state.toString(6))
    }

    @Test
    fun `table unpack returns list values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local a, b, c = table.unpack({"a", "b", "c"})
                local d, e = table.unpack({"a", "b", "c"}, 2, 3)
                local count = select("#", table.unpack({"first", nil, "third"}, 1, 3))
                local first, second, third = table.unpack({"first", nil, "third"}, 1, 3)
                return a, b, c, d, e, count, first, second, third
                """.trimIndent(),
                "table-unpack.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("a", state.toString(1))
        assertEquals("b", state.toString(2))
        assertEquals("c", state.toString(3))
        assertEquals("b", state.toString(4))
        assertEquals("c", state.toString(5))
        assertEquals(3L, state.toInteger(6))
        assertEquals("first", state.toString(7))
        assertTrue(state.isNil(8))
        assertEquals("third", state.toString(9))
    }

    @Test
    fun `table unpack returns no values for empty ranges`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return select("#", table.unpack({"a", "b"}, 3, 2))""", "table-unpack-empty.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0L, state.toInteger(1))
    }

    @Test
    fun `table unpack reports table argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.unpack("not-table")""", "table-unpack-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'table.unpack' (table expected)", state.toString(-1))
    }
}
