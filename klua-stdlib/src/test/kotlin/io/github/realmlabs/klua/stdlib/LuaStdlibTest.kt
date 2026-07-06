package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaConfig
import io.github.realmlabs.klua.api.LuaExitException
import io.github.realmlabs.klua.api.LuaExitHandler
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaStandardLibrary
import io.github.realmlabs.klua.api.LuaStatus
import io.github.realmlabs.klua.api.LuaYieldException
import io.github.realmlabs.klua.api.LuaYieldableFunction
import io.github.realmlabs.klua.api.withContinuation
import io.github.realmlabs.klua.core.value.luaRawBytes
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `openBase installs lua 55 version global`() {
        val defaultState = LuaState.create()
        LuaStdlib.openBase(defaultState)

        assertEquals(LuaStatus.OK, defaultState.load("""return _VERSION""", "version-default.lua"))
        assertEquals(LuaStatus.OK, defaultState.pcall(0, -1))
        assertEquals("Lua 5.5", defaultState.toString(1))
    }

    @Test
    fun `tostring uses table tostring metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local named = setmetatable({name = "value"}, {
                    __tostring = function(value)
                        return "table:" .. value.name
                    end,
                })
                local numeric = setmetatable({}, {
                    __tostring = function()
                        return 42
                    end,
                })
                local protected = setmetatable({}, {
                    __metatable = "locked",
                    __tostring = function()
                        return "hidden"
                    end,
                })
                return tostring(named), tostring(numeric), tostring(protected), getmetatable(protected)
                """.trimIndent(),
                "tostring-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table:value", state.toString(1))
        assertEquals("42", state.toString(2))
        assertEquals("hidden", state.toString(3))
        assertEquals("locked", state.toString(4))
    }

    @Test
    fun `tostring formats numbers like lua`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numeric = setmetatable({}, {
                    __tostring = function()
                        return 1e15
                    end,
                })
                return tostring(1),
                    tostring(1.0),
                    tostring(1.25),
                    tostring(1e14),
                    tostring(1e15),
                    tostring(1e-5),
                    tostring(0 / 0),
                    tostring(1 / 0),
                    tostring(-1 / 0),
                    tostring(numeric)
                """.trimIndent(),
                "tostring-number-format.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("1", state.toString(1))
        assertEquals("1.0", state.toString(2))
        assertEquals("1.25", state.toString(3))
        assertEquals("100000000000000.0", state.toString(4))
        assertEquals("1e+15", state.toString(5))
        assertEquals("1e-05", state.toString(6))
        assertEquals("nan", state.toString(7))
        assertEquals("inf", state.toString(8))
        assertEquals("-inf", state.toString(9))
        assertEquals("1e+15", state.toString(10))
    }

    @Test
    fun `tostring reports table and function identities`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local tableText = tostring({})
                local functionText = tostring(function() end)
                return tableText, functionText
                """.trimIndent(),
                "tostring-identities.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toString(1)?.matches(Regex("""table: [0-9a-f]+""")) == true)
        assertTrue(state.toString(2)?.matches(Regex("""function: [0-9a-f]+""")) == true)
    }

    @Test
    fun `tostring uses table name metamethod for default identity`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local named = setmetatable({}, { __name = "Widget" })
                local numeric = setmetatable({}, { __name = 42 })
                local protected = setmetatable({}, { __name = "Hidden", __metatable = "locked" })
                return tostring(named), tostring(numeric), tostring(protected)
                """.trimIndent(),
                "tostring-name-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toString(1)?.matches(Regex("""Widget: [0-9a-f]+""")) == true)
        assertTrue(state.toString(2)?.matches(Regex("""table: [0-9a-f]+""")) == true)
        assertTrue(state.toString(3)?.matches(Regex("""Hidden: [0-9a-f]+""")) == true)
    }

    @Test
    fun `tostring uses primitive type tostring metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(1, {
                    __tostring = function(value)
                        return "number:" .. math.type(value)
                    end,
                })
                debug.setmetatable(false, {
                    __tostring = function(value)
                        return value and "true-value" or "false-value"
                    end,
                })
                debug.setmetatable(nil, {
                    __tostring = function(value)
                        return type(value)
                    end,
                })
                debug.setmetatable(function() end, {
                    __tostring = function(value)
                        return type(value)
                    end,
                })
                local co = coroutine.create(function() end)
                debug.setmetatable(co, {
                    __tostring = function(value)
                        return type(value)
                    end,
                })
                return tostring(2), tostring(true), tostring(nil), tostring(function() end), tostring(co)
                """.trimIndent(),
                "primitive-tostring-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("number:integer", state.toString(1))
        assertEquals("true-value", state.toString(2))
        assertEquals("nil", state.toString(3))
        assertEquals("function", state.toString(4))
        assertEquals("thread", state.toString(5))
    }

    @Test
    fun `tostring accepts numeric primitive tostring metamethod results`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(1, {
                    __tostring = function()
                        return 1e15
                    end,
                })
                debug.setmetatable(false, {
                    __tostring = function()
                        return {}
                    end,
                })
                local ok, message = pcall(tostring, true)
                return tostring(2), ok, message
                """.trimIndent(),
                "primitive-tostring-result.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("1e+15", state.toString(1))
        assertFalse(state.toBoolean(2))
        assertEquals("'__tostring' must return a string", state.toString(3))
    }

    @Test
    fun `tostring uses string tostring metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local original = debug.getmetatable("")
                debug.setmetatable("", {
                    __tostring = function(value)
                        return "string:" .. value
                    end,
                })
                local text = tostring("alpha")
                debug.setmetatable("", original)
                return text
                """.trimIndent(),
                "string-tostring-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("string:alpha", state.toString(1))
    }

    @Test
    fun `tostring uses primitive type name metamethod for default identities`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(function() end, {__name = "Callable"})
                local co = coroutine.create(function() end)
                debug.setmetatable(co, {__name = "LuaThread"})
                return tostring(function() end), tostring(co)
                """.trimIndent(),
                "primitive-tostring-name.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toString(1)?.matches(Regex("""Callable: [0-9a-f]+""")) == true)
        assertTrue(state.toString(2)?.matches(Regex("""LuaThread: [0-9a-f]+""")) == true)
    }

    @Test
    fun `tostring uses host userdata name metamethod for default identities`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushUserData(DebugHostObject("named"))
        state.setGlobal("named")
        state.pushUserData(DebugHostObject("numeric"))
        state.setGlobal("numeric")
        state.pushUserData(DebugHostObject("custom"))
        state.setGlobal("custom")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local plain = tostring(named)
                debug.setmetatable(named, { __name = "HostValue" })
                debug.setmetatable(numeric, { __name = 42 })
                debug.setmetatable(custom, {
                    __name = "Ignored",
                    __tostring = function(value)
                        return type(value)
                    end,
                })
                return plain, tostring(named), tostring(numeric), tostring(custom), type(named)
                """.trimIndent(),
                "userdata-tostring-name.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toString(1)?.matches(Regex("""userdata: [0-9a-f]+""")) == true)
        assertTrue(state.toString(2)?.matches(Regex("""HostValue: [0-9a-f]+""")) == true)
        assertTrue(state.toString(3)?.matches(Regex("""userdata: [0-9a-f]+""")) == true)
        assertEquals("userdata", state.toString(4))
        assertEquals("userdata", state.toString(5))
    }

    @Test
    fun `tostring reports invalid table tostring metamethod results`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local value = setmetatable({}, {
                    __tostring = function()
                        return {}
                    end,
                })
                return tostring(value)
                """.trimIndent(),
                "tostring-metamethod-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("'__tostring' must return a string", state.toString(-1))
    }

    @Test
    fun `openBase installs global environment table`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                answer = 42
                _G.created = "ok"
                return _G == _G._G, _G.answer, created, _G.type == type
                """.trimIndent(),
                "global-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals(42L, state.toInteger(2))
        assertEquals("ok", state.toString(3))
        assertTrue(state.toBoolean(4))
    }

    @Test
    fun `openLibs installs package table`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return type(package), type(package.searchpath), package.path, package.config,
                    type(package.loaded), type(package.preload), type(package.searchers), type(require),
                    type(package.loadlib), type(package.cpath),
                    type(package.searchers[1]), type(package.searchers[2]),
                    type(package.searchers[3]), type(package.searchers[4])
                """.trimIndent(),
                "package-openlibs.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("table", state.toString(1))
        assertEquals("function", state.toString(2))
        assertEquals("?.lua;?/init.lua", state.toString(3))
        assertTrue(state.toString(4)?.contains(";\n?") == true)
        assertEquals("table", state.toString(5))
        assertEquals("table", state.toString(6))
        assertEquals("table", state.toString(7))
        assertEquals("function", state.toString(8))
        assertEquals("function", state.toString(9))
        assertEquals("string", state.toString(10))
        assertEquals("function", state.toString(11))
        assertEquals("function", state.toString(12))
        assertEquals("function", state.toString(13))
        assertEquals("function", state.toString(14))
    }

    @Test
    fun `openLibs installs minimal debug library`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local info = debug.getinfo(1)
                return type(debug), type(debug.traceback), type(debug.getinfo), type(debug.getlocal),
                    type(debug.setlocal), type(debug.getupvalue), type(debug.setupvalue),
                    type(debug.upvalueid), type(debug.upvaluejoin), type(debug.getuservalue),
                    type(debug.setuservalue), type(debug.getmetatable), type(debug.setmetatable), type(debug.getregistry),
                    type(debug.sethook), type(debug.gethook), debug.traceback("boom"),
                    type(info), info.what, info.source, info.currentline,
                    type(debug.debug), pcall(debug.debug)
                """.trimIndent(),
                "debug-openlibs.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table", state.toString(1))
        assertEquals("function", state.toString(2))
        assertEquals("function", state.toString(3))
        assertEquals("function", state.toString(4))
        assertEquals("function", state.toString(5))
        assertEquals("function", state.toString(6))
        assertEquals("function", state.toString(7))
        assertEquals("function", state.toString(8))
        assertEquals("function", state.toString(9))
        assertEquals("function", state.toString(10))
        assertEquals("function", state.toString(11))
        assertEquals("function", state.toString(12))
        assertEquals("function", state.toString(13))
        assertEquals("function", state.toString(14))
        assertEquals("function", state.toString(15))
        assertEquals("function", state.toString(16))
        assertTrue(state.toString(17)?.contains("boom\nstack traceback:") == true)
        assertEquals("table", state.toString(18))
        assertEquals("main", state.toString(19))
        assertEquals("debug-openlibs.lua", state.toString(20))
        assertEquals(1L, state.toInteger(21))
        assertEquals("function", state.toString(22))
        assertTrue(state.toBoolean(23))
    }

    @Test
    fun `openLibs skips debug library when disabled in config`() {
        val state = LuaState.create(LuaConfig(debugEnabled = false))
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                "return type(debug), type(math), type(string), type(table)",
                "debug-disabled-openlibs.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("nil", state.toString(1))
        assertEquals("table", state.toString(2))
        assertEquals("table", state.toString(3))
        assertEquals("table", state.toString(4))
    }

    @Test
    fun `openLibs installs only whitelisted standard libraries`() {
        val state = LuaState.create(
            LuaConfig(
                standardLibraries = setOf(
                    LuaStandardLibrary.BASE,
                    LuaStandardLibrary.MATH,
                ),
            ),
        )
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return type(math), type(string), type(table), type(utf8), type(coroutine),
                    type(package), type(require), type(os), type(debug)
                """.trimIndent(),
                "stdlib-whitelist.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table", state.toString(1))
        assertEquals("nil", state.toString(2))
        assertEquals("nil", state.toString(3))
        assertEquals("nil", state.toString(4))
        assertEquals("nil", state.toString(5))
        assertEquals("nil", state.toString(6))
        assertEquals("nil", state.toString(7))
        assertEquals("nil", state.toString(8))
        assertEquals("nil", state.toString(9))
    }

    @Test
    fun `debug traceback includes lua stack frames`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function leaf()
                    return debug.traceback("boom")
                end

                local function branch()
                    return leaf()
                end

                return branch()
                """.trimIndent(),
                "debug-traceback.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(
            "boom\n" +
                "stack traceback:\n" +
                "\t[string \"debug-traceback.lua\"]:2\n" +
                "\t[string \"debug-traceback.lua\"]:6\n" +
                "\t[string \"debug-traceback.lua\"]:9",
            state.toString(1),
        )
    }

    @Test
    fun `debug traceback formats file chunk source names`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function leaf()
                    return debug.traceback("boom")
                end

                return leaf()
                """.trimIndent(),
                "@/tmp/debug-traceback-file.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(
            "boom\n" +
                "stack traceback:\n" +
                "\t/tmp/debug-traceback-file.lua:2\n" +
                "\t/tmp/debug-traceback-file.lua:5",
            state.toString(1),
        )
    }

    @Test
    fun `debug traceback elides deep stacks`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function dive(n)
                    if n == 0 then
                        return debug.traceback("boom")
                    end
                    local value = dive(n - 1)
                    return value
                end

                return dive(35)
                """.trimIndent(),
                "debug-traceback-deep.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        val lines = state.toString(1)!!.lines()
        assertEquals("boom", lines[0])
        assertEquals("stack traceback:", lines[1])
        assertEquals(24, lines.size)
        val skipped = lines.single { it.contains("skipping") }
        assertTrue(skipped.startsWith("\t...\t(skipping "))
        assertTrue(skipped.endsWith(" levels)"))
        assertEquals(10, lines.indexOf(skipped) - 2)
        assertEquals(11, lines.size - lines.indexOf(skipped) - 1)
    }

    @Test
    fun `debug traceback reports non numeric level errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(debug.traceback, "boom", "not-level")
                local okFraction, fractionMessage = pcall(debug.traceback, "boom", 1.5)
                return okString, stringMessage, okFraction, fractionMessage
                """.trimIndent(),
                "debug-traceback-level-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'traceback' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'traceback' (number has no integer representation)", state.toString(4))
    }

    @Test
    fun `debug traceback negative level omits lua stack frames`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function leaf()
                    return debug.traceback("boom", -1)
                end

                return leaf()
                """.trimIndent(),
                "debug-traceback-negative-level.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("boom\nstack traceback:", state.toString(1))
    }

    @Test
    fun `debug traceback coerces numbers and returns non coercible messages unchanged`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local marker = {name = "marker"}
                tostring = function()
                    return "overridden"
                end
                local returnedTable = debug.traceback(marker)
                local returnedNumber = debug.traceback(42)
                local returnedBoolean = debug.traceback(false)
                return returnedTable == marker,
                    returnedTable.name,
                    returnedNumber,
                    type(returnedNumber),
                    string.find(returnedNumber, "^42\nstack traceback:") == 1,
                    returnedBoolean,
                    type(returnedBoolean)
                """.trimIndent(),
                "debug-traceback-non-string.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("marker", state.toString(2))
        assertTrue(state.toString(3)?.startsWith("42\nstack traceback:") == true)
        assertEquals("string", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertFalse(state.toBoolean(6))
        assertEquals("boolean", state.toString(7))
    }

    @Test
    fun `debug traceback accepts suspended coroutine thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    local function leaf()
                        coroutine.yield("pause")
                    end
                    leaf()
                end)

                local ok, value = coroutine.resume(co)
                local trace = debug.traceback(co, "boom")
                return ok, value,
                    string.find(trace, "^boom\nstack traceback:") == 1,
                    string.find(trace, "%[string \"debug%-thread%-traceback%.lua\"%]:") ~= nil
                """.trimIndent(),
                "debug-thread-traceback.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("pause", state.toString(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
    }

    @Test
    fun `debug traceback accepts explicit current thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local mainThread = coroutine.running()
                local function mainProbe()
                    local info = debug.getinfo(mainThread, 1, "S")
                    return debug.traceback(mainThread, "main-current"), info.source
                end
                local mainTrace, mainSource = mainProbe()

                local co = coroutine.create(function()
                    local running = coroutine.running()
                    local function coroutineProbe()
                        local info = debug.getinfo(running, 1, "S")
                        return debug.traceback(running, "coroutine-current"), info.source
                    end
                    return coroutineProbe()
                end)
                local ok, coroutineTrace, coroutineSource = coroutine.resume(co)

                return ok,
                    string.find(mainTrace, "^main%-current\nstack traceback:") == 1,
                    string.find(mainTrace, "%[string \"debug%-current%-thread%-traceback%.lua\"%]:") ~= nil,
                    mainSource,
                    string.find(coroutineTrace, "^coroutine%-current\nstack traceback:") == 1,
                    string.find(coroutineTrace, "%[string \"debug%-current%-thread%-traceback%.lua\"%]:") ~= nil,
                    coroutineSource
                """.trimIndent(),
                "debug-current-thread-traceback.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertEquals("debug-current-thread-traceback.lua", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.toBoolean(6))
        assertEquals("debug-current-thread-traceback.lua", state.toString(7))
    }

    @Test
    fun `debug getinfo returns lua frame source and current line`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function leaf()
                    local here = debug.getinfo(1)
                    local caller = debug.getinfo(2)
                    local missing = debug.getinfo(99)
                    return here.source, here.short_src, here.currentline, here.what,
                        here.linedefined, here.lastlinedefined, caller.currentline, missing
                end

                local function branch()
                    return leaf()
                end

                return branch()
                """.trimIndent(),
                "debug-getinfo.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("debug-getinfo.lua", state.toString(1))
        assertEquals("""[string "debug-getinfo.lua"]""", state.toString(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals("Lua", state.toString(4))
        assertEquals(1L, state.toInteger(5))
        assertEquals(7L, state.toInteger(6))
        assertEquals(10L, state.toInteger(7))
        assertTrue(state.isNil(8))
    }

    @Test
    fun `debug getinfo returns lua function definition metadata`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function probe()
                    local x = 1
                    return x
                end

                local info = debug.getinfo(probe)
                return info.source, info.short_src, info.currentline, info.what,
                    info.linedefined, info.lastlinedefined, info.namewhat
                """.trimIndent(),
                "debug-getinfo-function.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("debug-getinfo-function.lua", state.toString(1))
        assertEquals("""[string "debug-getinfo-function.lua"]""", state.toString(2))
        assertEquals(-1L, state.toInteger(3))
        assertEquals("Lua", state.toString(4))
        assertEquals(1L, state.toInteger(5))
        assertEquals(4L, state.toInteger(6))
        assertEquals("", state.toString(7))
    }

    @Test
    fun `debug getinfo accepts thread argument for function subjects`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function probe()
                    return "ok"
                end
                local co = coroutine.create(function()
                    coroutine.yield("pause")
                end)
                coroutine.resume(co)
                local info = debug.getinfo(co, probe, "Suf")
                return info.func == probe, info.source, info.what,
                    info.linedefined, info.lastlinedefined,
                    info.nparams, info.isvararg
                """.trimIndent(),
                "debug-thread-getinfo-function.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("debug-thread-getinfo-function.lua", state.toString(2))
        assertEquals("Lua", state.toString(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals(3L, state.toInteger(5))
        assertEquals(0L, state.toInteger(6))
        assertFalse(state.toBoolean(7))
    }

    @Test
    fun `debug getinfo reports active frame call site names`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local seen = {}
                local function record()
                    local info = debug.getinfo(1, "n")
                    seen[#seen + 1] = info.name
                    seen[#seen + 1] = info.namewhat
                end

                local object = {}
                function object:method()
                    local info = debug.getinfo(1, "n")
                    seen[#seen + 1] = info.name
                    seen[#seen + 1] = info.namewhat
                end
                object.field = record
                object[1] = record
                local computedKey = "field"
                local callable = setmetatable({}, {
                    __call = function()
                        local info = debug.getinfo(1, "n")
                        seen[#seen + 1] = info.name
                        seen[#seen + 1] = info.namewhat
                    end,
                })
                local indexed = setmetatable({}, {
                    __index = function()
                        local info = debug.getinfo(1, "n")
                        seen[#seen + 1] = info.name
                        seen[#seen + 1] = info.namewhat
                    end,
                    __newindex = function()
                        local info = debug.getinfo(1, "n")
                        seen[#seen + 1] = info.name
                        seen[#seen + 1] = info.namewhat
                    end,
                })

                do
                    local function localProbe()
                        local info = debug.getinfo(1, "n")
                        seen[#seen + 1] = info.name
                        seen[#seen + 1] = info.namewhat
                    end
                    localProbe()
                end
                do
                    local upvalueProbe = record
                    local function nested()
                        upvalueProbe()
                    end
                    nested()
                end
                function globalProbe()
                    local info = debug.getinfo(1, "n")
                    seen[#seen + 1] = info.name
                    seen[#seen + 1] = info.namewhat
                end

                callable()
                local _ = indexed.missing
                indexed.written = true
                globalProbe()
                object.field()
                object[1]()
                object[computedKey]()
                object:method()

                return seen[1], seen[2], seen[3], seen[4], seen[5], seen[6],
                    seen[7], seen[8], seen[9], seen[10], seen[11], seen[12],
                    seen[13], seen[14], seen[15], seen[16], seen[17], seen[18],
                    seen[19], seen[20]
                """.trimIndent(),
                "debug-getinfo-names.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("localProbe", state.toString(1))
        assertEquals("local", state.toString(2))
        assertEquals("upvalueProbe", state.toString(3))
        assertEquals("upvalue", state.toString(4))
        assertEquals("callable", state.toString(5))
        assertEquals("local", state.toString(6))
        assertEquals("index", state.toString(7))
        assertEquals("metamethod", state.toString(8))
        assertEquals("newindex", state.toString(9))
        assertEquals("metamethod", state.toString(10))
        assertEquals("globalProbe", state.toString(11))
        assertEquals("global", state.toString(12))
        assertEquals("field", state.toString(13))
        assertEquals("field", state.toString(14))
        assertEquals("integer index", state.toString(15))
        assertEquals("field", state.toString(16))
        assertEquals("?", state.toString(17))
        assertEquals("field", state.toString(18))
        assertEquals("method", state.toString(19))
        assertEquals("method", state.toString(20))
    }

    @Test
    fun `debug getinfo reports environment field calls as globals`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                _ENV = _G

                function envProbe()
                    local info = debug.getinfo(1, "n")
                    return info.name, info.namewhat
                end

                local dotName, dotNameWhat = _ENV.envProbe()
                local bracketName, bracketNameWhat = _ENV["envProbe"]()
                return dotName, dotNameWhat, bracketName, bracketNameWhat
                """.trimIndent(),
                "debug-getinfo-env-global-name.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("envProbe", state.toString(1))
        assertEquals("global", state.toString(2))
        assertEquals("envProbe", state.toString(3))
        assertEquals("global", state.toString(4))
    }

    @Test
    fun `debug getinfo reports operator metamethod call site names`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local seen = {}
                local function capture(result)
                    local info = debug.getinfo(2, "n")
                    seen[#seen + 1] = info.name
                    seen[#seen + 1] = info.namewhat
                    return result
                end
                local mt = {
                    __add = function() return capture({}) end,
                    __sub = function() return capture({}) end,
                    __mul = function() return capture({}) end,
                    __mod = function() return capture({}) end,
                    __pow = function() return capture({}) end,
                    __div = function() return capture({}) end,
                    __idiv = function() return capture({}) end,
                    __unm = function() return capture({}) end,
                    __len = function() return capture(0) end,
                    __concat = function() return capture("") end,
                    __eq = function() return capture(true) end,
                    __lt = function() return capture(true) end,
                    __le = function() return capture(true) end,
                    __band = function() return capture(0) end,
                    __bor = function() return capture(0) end,
                    __bxor = function() return capture(0) end,
                    __shl = function() return capture(0) end,
                    __shr = function() return capture(0) end,
                    __bnot = function() return capture(0) end,
                }
                local left = setmetatable({}, mt)
                local right = setmetatable({}, mt)
                local _ = left + right
                _ = left - right
                _ = left * right
                _ = left % right
                _ = left ^ right
                _ = left / right
                _ = left // right
                _ = -left
                _ = #left
                _ = left .. right
                _ = left == right
                _ = left < right
                _ = left <= right
                _ = left & right
                _ = left | right
                _ = left ~ right
                _ = left << right
                _ = left >> right
                _ = ~left
                return seen[1], seen[2], seen[3], seen[4], seen[5], seen[6],
                    seen[7], seen[8], seen[9], seen[10], seen[11], seen[12],
                    seen[13], seen[14], seen[15], seen[16], seen[17], seen[18],
                    seen[19], seen[20], seen[21], seen[22], seen[23], seen[24],
                    seen[25], seen[26], seen[27], seen[28], seen[29], seen[30],
                    seen[31], seen[32], seen[33], seen[34], seen[35], seen[36],
                    seen[37], seen[38]
                """.trimIndent(),
                "debug-getinfo-operator-metamethod-names.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("add", state.toString(1))
        assertEquals("metamethod", state.toString(2))
        assertEquals("sub", state.toString(3))
        assertEquals("metamethod", state.toString(4))
        assertEquals("mul", state.toString(5))
        assertEquals("metamethod", state.toString(6))
        assertEquals("mod", state.toString(7))
        assertEquals("metamethod", state.toString(8))
        assertEquals("pow", state.toString(9))
        assertEquals("metamethod", state.toString(10))
        assertEquals("div", state.toString(11))
        assertEquals("metamethod", state.toString(12))
        assertEquals("idiv", state.toString(13))
        assertEquals("metamethod", state.toString(14))
        assertEquals("unm", state.toString(15))
        assertEquals("metamethod", state.toString(16))
        assertEquals("len", state.toString(17))
        assertEquals("metamethod", state.toString(18))
        assertEquals("concat", state.toString(19))
        assertEquals("metamethod", state.toString(20))
        assertEquals("eq", state.toString(21))
        assertEquals("metamethod", state.toString(22))
        assertEquals("lt", state.toString(23))
        assertEquals("metamethod", state.toString(24))
        assertEquals("le", state.toString(25))
        assertEquals("metamethod", state.toString(26))
        assertEquals("band", state.toString(27))
        assertEquals("metamethod", state.toString(28))
        assertEquals("bor", state.toString(29))
        assertEquals("metamethod", state.toString(30))
        assertEquals("bxor", state.toString(31))
        assertEquals("metamethod", state.toString(32))
        assertEquals("shl", state.toString(33))
        assertEquals("metamethod", state.toString(34))
        assertEquals("shr", state.toString(35))
        assertEquals("metamethod", state.toString(36))
        assertEquals("bnot", state.toString(37))
        assertEquals("metamethod", state.toString(38))
    }

    @Test
    fun `debug getinfo reports generic for iterator call site name`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local seen = {}
                local function iterator(_, control)
                    local info = debug.getinfo(1, "n")
                    seen[#seen + 1] = info.name
                    seen[#seen + 1] = info.namewhat
                    if control == nil then
                        return 1
                    end
                    return nil
                end
                for value in iterator, nil, nil do
                end
                return seen[1], seen[2]
                """.trimIndent(),
                "debug-getinfo-for-iterator-name.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("for iterator", state.toString(1))
        assertEquals("for iterator", state.toString(2))
    }

    @Test
    fun `debug getinfo reports main chunk source metadata`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local frameInfo = debug.getinfo(1, "S")
                local plain = load("return 1", "loaded-main.lua")
                local plainInfo = debug.getinfo(plain, "S")
                local literal = load("return 1", "=literal chunk")
                local literalInfo = debug.getinfo(literal, "S")
                local file = load("return 1", "@/tmp/loaded-main.lua")
                local fileInfo = debug.getinfo(file, "S")
                return frameInfo.what, frameInfo.linedefined, frameInfo.lastlinedefined,
                    plainInfo.what, plainInfo.linedefined, plainInfo.lastlinedefined, plainInfo.short_src,
                    literalInfo.source, literalInfo.short_src,
                    fileInfo.source, fileInfo.short_src
                """.trimIndent(),
                "debug-getinfo-main.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("main", state.toString(1))
        assertEquals(0L, state.toInteger(2))
        assertEquals(0L, state.toInteger(3))
        assertEquals("main", state.toString(4))
        assertEquals(0L, state.toInteger(5))
        assertEquals(0L, state.toInteger(6))
        assertEquals("""[string "loaded-main.lua"]""", state.toString(7))
        assertEquals("=literal chunk", state.toString(8))
        assertEquals("literal chunk", state.toString(9))
        assertEquals("@/tmp/loaded-main.lua", state.toString(10))
        assertEquals("/tmp/loaded-main.lua", state.toString(11))
    }

    @Test
    fun `debug getinfo returns host function metadata`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local info = debug.getinfo(print)
                return info.source, info.short_src, info.currentline, info.what,
                    info.linedefined, info.lastlinedefined, info.namewhat
                """.trimIndent(),
                "debug-getinfo-host-function.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("=[C]", state.toString(1))
        assertEquals("[C]", state.toString(2))
        assertEquals(-1L, state.toInteger(3))
        assertEquals("C", state.toString(4))
        assertEquals(-1L, state.toInteger(5))
        assertEquals(-1L, state.toInteger(6))
        assertEquals("", state.toString(7))
    }

    @Test
    fun `debug getinfo filters fields with what option`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function probe()
                    local frameLine = debug.getinfo(1, "l")
                    local frameSource = debug.getinfo(1, "S")
                    local frameFunction = debug.getinfo(1, "f")
                    local frameDefault = debug.getinfo(1)
                    return frameLine.currentline, frameLine.source,
                        frameSource.currentline, frameSource.source,
                        frameFunction.func == probe,
                        frameDefault.func == probe
                end

                local functionInfo = debug.getinfo(probe, "S")
                local functionLine = debug.getinfo(probe, "l")
                local functionValue = debug.getinfo(probe, "f")
                local hostFunction = debug.getinfo(print, "fS")
                local frameCurrentline, frameLineSource, frameSourceCurrentline, frameSource,
                    frameFunction, frameDefaultFunction = probe()
                return functionInfo.source, functionInfo.currentline,
                    functionLine.currentline, functionLine.source,
                    functionValue.func == probe, functionValue.source,
                    hostFunction.func == print, hostFunction.what, hostFunction.currentline,
                    frameCurrentline, frameLineSource, frameSourceCurrentline, frameSource,
                    frameFunction, frameDefaultFunction
                """.trimIndent(),
                "debug-getinfo-what.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("debug-getinfo-what.lua", state.toString(1))
        assertTrue(state.isNil(2))
        assertEquals(-1L, state.toInteger(3))
        assertTrue(state.isNil(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.isNil(6))
        assertTrue(state.toBoolean(7))
        assertEquals("C", state.toString(8))
        assertTrue(state.isNil(9))
        assertEquals(2L, state.toInteger(10))
        assertTrue(state.isNil(11))
        assertTrue(state.isNil(12))
        assertEquals("debug-getinfo-what.lua", state.toString(13))
        assertTrue(state.toBoolean(14))
        assertTrue(state.toBoolean(15))
    }

    @Test
    fun `debug getinfo returns function arity and upvalue metadata`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local captured = "upvalue"
                local function vararg(a, b, ...)
                    return captured, a, b, ...
                end
                local function fixed(a)
                    return a
                end
                local function active(a, b, ...)
                    local _ = captured
                    local info = debug.getinfo(1, "u")
                    return info.nups, info.nparams, info.isvararg
                end

                local varargInfo = debug.getinfo(vararg, "u")
                local fixedInfo = debug.getinfo(fixed, "u")
                local hostInfo = debug.getinfo(print, "u")
                local activeNups, activeNparams, activeIsVararg = active(1, 2, 3)
                return varargInfo.nups, varargInfo.nparams, varargInfo.isvararg,
                    fixedInfo.nups, fixedInfo.nparams, fixedInfo.isvararg,
                    hostInfo.nups, hostInfo.nparams, hostInfo.isvararg,
                    activeNups, activeNparams, activeIsVararg,
                    varargInfo.source
                """.trimIndent(),
                "debug-getinfo-upvalues.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertTrue(state.toBoolean(3))
        assertEquals(0L, state.toInteger(4))
        assertEquals(1L, state.toInteger(5))
        assertFalse(state.toBoolean(6))
        assertEquals(0L, state.toInteger(7))
        assertEquals(0L, state.toInteger(8))
        assertTrue(state.toBoolean(9))
        assertEquals(1L, state.toInteger(10))
        assertEquals(2L, state.toInteger(11))
        assertTrue(state.toBoolean(12))
        assertTrue(state.isNil(13))
    }

    @Test
    fun `debug getinfo default includes available function metadata except active lines`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local captured = "upvalue"
                local function probe(first, ...)
                    return captured, first, ...
                end

                local luaInfo = debug.getinfo(probe)
                local hostInfo = debug.getinfo(print)
                return luaInfo.func == probe,
                    luaInfo.nups,
                    luaInfo.nparams,
                    luaInfo.isvararg,
                    luaInfo.activelines,
                    hostInfo.func == print,
                    hostInfo.nups,
                    hostInfo.nparams,
                    hostInfo.isvararg,
                    hostInfo.activelines
                """.trimIndent(),
                "debug-getinfo-default.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.isNil(5))
        assertTrue(state.toBoolean(6))
        assertEquals(0L, state.toInteger(7))
        assertEquals(0L, state.toInteger(8))
        assertTrue(state.toBoolean(9))
        assertTrue(state.isNil(10))
    }

    @Test
    fun `debug getinfo returns transfer and tail call metadata`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function probe()
                    local frameInfo = debug.getinfo(1, "rt")
                    return frameInfo.ftransfer,
                        frameInfo.ntransfer,
                        frameInfo.istailcall,
                        frameInfo.extraargs,
                        frameInfo.source
                end

                local function functionProbe() end
                local functionInfo = debug.getinfo(functionProbe)
                local hostInfo = debug.getinfo(print, "rt")
                local frameFtransfer, frameNtransfer, frameIstailcall, frameExtraargs, frameSource = probe()
                return functionInfo.ftransfer,
                    functionInfo.ntransfer,
                    functionInfo.istailcall,
                    functionInfo.extraargs,
                    hostInfo.ftransfer,
                    hostInfo.ntransfer,
                    hostInfo.istailcall,
                    hostInfo.extraargs,
                    frameFtransfer,
                    frameNtransfer,
                    frameIstailcall,
                    frameExtraargs,
                    frameSource
                """.trimIndent(),
                "debug-getinfo-transfer-tail.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(0L, state.toInteger(1))
        assertEquals(0L, state.toInteger(2))
        assertFalse(state.toBoolean(3))
        assertEquals(0L, state.toInteger(4))
        assertEquals(0L, state.toInteger(5))
        assertEquals(0L, state.toInteger(6))
        assertFalse(state.toBoolean(7))
        assertEquals(0L, state.toInteger(8))
        assertEquals(0L, state.toInteger(9))
        assertEquals(0L, state.toInteger(10))
        assertFalse(state.toBoolean(11))
        assertEquals(0L, state.toInteger(12))
        assertTrue(state.isNil(13))
    }

    @Test
    fun `debug getinfo reports invalid what options`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(debug.getinfo, print, "x")
                return ok, message
                """.trimIndent(),
                "debug-getinfo-invalid-option.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'getinfo' (invalid option)", state.toString(2))
    }

    @Test
    fun `debug getinfo rejects private function option`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok1, message1 = pcall(debug.getinfo, 1, ">")
                local ok2, message2 = pcall(debug.getinfo, function() end, ">S")
                return ok1, message1, ok2, message2
                """.trimIndent(),
                "debug-getinfo-private-option.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'getinfo' (invalid option '>')", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'getinfo' (invalid option '>')", state.toString(4))
    }

    @Test
    fun `debug getinfo reports shifted invalid what options for thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    coroutine.yield("pause")
                end)
                coroutine.resume(co)
                local invalidOk, invalidMessage = pcall(debug.getinfo, co, 0, "x")
                local internalOk, internalMessage = pcall(debug.getinfo, co, 0, ">")
                return invalidOk, invalidMessage, internalOk, internalMessage
                """.trimIndent(),
                "debug-thread-getinfo-invalid-option.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #3 to 'getinfo' (invalid option)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #3 to 'getinfo' (invalid option '>')", state.toString(4))
    }

    @Test
    fun `debug getinfo rejects leading internal function option before stack level`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(debug.getinfo, "not-level", ">")
                return ok, message
                """.trimIndent(),
                "debug-getinfo-leading-internal-option.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'getinfo' (invalid option '>')", state.toString(2))
    }

    @Test
    fun `debug getinfo reports non string what errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(debug.getinfo, print, true)
                return ok, message
                """.trimIndent(),
                "debug-getinfo-what-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'getinfo' (string expected)", state.toString(2))
    }

    @Test
    fun `debug getinfo reports non numeric stack level errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(debug.getinfo, "not-level")
                local okFraction, fractionMessage = pcall(debug.getinfo, 1.5)
                return okString, stringMessage, okFraction, fractionMessage
                """.trimIndent(),
                "debug-getinfo-level-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'getinfo' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'getinfo' (number has no integer representation)", state.toString(4))
    }

    @Test
    fun `debug getinfo returns active line metadata`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function probe(value)
                    local frameInfo = debug.getinfo(1, "L")
                    if value then
                        value = value + 1
                    end
                    return frameInfo.activelines
                end

                local functionInfo = debug.getinfo(probe, "L")
                local hostInfo = debug.getinfo(print, "L")
                local frameLines = probe(1)
                return functionInfo.activelines[1],
                    functionInfo.activelines[2],
                    functionInfo.activelines[3],
                    functionInfo.activelines[4],
                    functionInfo.activelines[5],
                    functionInfo.activelines[6],
                    functionInfo.activelines[7],
                    frameLines[2],
                    frameLines[4],
                    hostInfo.activelines
                """.trimIndent(),
                "debug-getinfo-activelines.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.isNil(5))
        assertTrue(state.toBoolean(6))
        assertTrue(state.isNil(7))
        assertTrue(state.toBoolean(8))
        assertTrue(state.toBoolean(9))
        assertTrue(state.isNil(10))
    }

    @Test
    fun `debug getinfo accepts suspended coroutine thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    local function leaf()
                        coroutine.yield("pause")
                    end
                    leaf()
                end)

                local ok, value = coroutine.resume(co)
                local info = debug.getinfo(co, 0, "Sl")
                local missing = debug.getinfo(co, 99)
                return ok, value, info.source, info.what, info.currentline > 0, missing
                """.trimIndent(),
                "debug-thread-getinfo.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("pause", state.toString(2))
        assertEquals("debug-thread-getinfo.lua", state.toString(3))
        assertEquals("Lua", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.isNil(6))
    }

    @Test
    fun `debug getinfo accepts function subjects after thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    coroutine.yield("pause")
                end)
                coroutine.resume(co)

                local function probe(first, ...)
                    return first, ...
                end
                local info = debug.getinfo(co, probe, "Su")
                return info.source, info.what, info.linedefined, info.lastlinedefined,
                    info.nparams, info.isvararg
                """.trimIndent(),
                "debug-thread-getinfo-function.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("debug-thread-getinfo-function.lua", state.toString(1))
        assertEquals("Lua", state.toString(2))
        assertEquals(6L, state.toInteger(3))
        assertEquals(8L, state.toInteger(4))
        assertEquals(1L, state.toInteger(5))
        assertTrue(state.toBoolean(6))
    }

    @Test
    fun `debug thread arguments handle dead coroutine stacks`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    return "done"
                end)
                local resumeOk, value = coroutine.resume(co)
                local info = debug.getinfo(co, 0)
                local trace = debug.traceback(co, "dead")
                local localOk, localMessage = pcall(debug.getlocal, co, 0, 1)
                local setOk, setMessage = pcall(debug.setlocal, co, 0, 1, "replacement")
                local beforeHook = debug.gethook(co)
                local function hook() end
                debug.sethook(co, hook, "cr", 4)
                local installed, mask, count = debug.gethook(co)
                debug.sethook(co)
                local afterHook = debug.gethook(co)
                return resumeOk, value, coroutine.status(co), info, trace,
                    localOk, localMessage, setOk, setMessage,
                    beforeHook, installed == hook, mask, count, afterHook
                """.trimIndent(),
                "debug-dead-thread.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("done", state.toString(2))
        assertEquals("dead", state.toString(3))
        assertTrue(state.isNil(4))
        assertEquals("dead\nstack traceback:", state.toString(5))
        assertFalse(state.toBoolean(6))
        assertEquals("bad argument #2 to 'getlocal' (level out of range)", state.toString(7))
        assertFalse(state.toBoolean(8))
        assertEquals("bad argument #2 to 'setlocal' (level out of range)", state.toString(9))
        assertTrue(state.isNil(10))
        assertTrue(state.toBoolean(11))
        assertEquals("cr", state.toString(12))
        assertEquals(4L, state.toInteger(13))
        assertTrue(state.isNil(14))
    }

    @Test
    fun `debug getlocal returns active lua local names and values`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function leaf()
                    local number = 12
                    local text = "ok"
                    local n1, v1 = debug.getlocal(1, 1)
                    local n2, v2 = debug.getlocal(1, 2)
                    local missing = debug.getlocal(1, 99)
                    return n1, v1, n2, v2, missing
                end

                return leaf()
                """.trimIndent(),
                "debug-getlocal.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("number", state.toString(1))
        assertEquals(12L, state.toInteger(2))
        assertEquals("text", state.toString(3))
        assertEquals("ok", state.toString(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `debug getlocal and setlocal accept explicit current thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local mainThread = coroutine.running()
                local function mainProbe()
                    local text = "main-old"
                    local name, value = debug.getlocal(mainThread, 1, 1)
                    local changedName = debug.setlocal(mainThread, 1, 1, "main-new")
                    local readName, readValue = debug.getlocal(mainThread, 1, 1)
                    return name, value, changedName, readName, readValue, text
                end

                local co = coroutine.create(function()
                    local text = "co-old"
                    local running = coroutine.running()
                    local name, value = debug.getlocal(running, 1, 1)
                    local changedName = debug.setlocal(running, 1, 1, "co-new")
                    local readName, readValue = debug.getlocal(running, 1, 1)
                    return name, value, changedName, readName, readValue, text
                end)

                local mainName, mainValue, mainChanged, mainReadName, mainReadValue, mainText = mainProbe()
                local ok, coName, coValue, coChanged, coReadName, coReadValue, coText = coroutine.resume(co)
                return mainName, mainValue, mainChanged, mainReadName, mainReadValue, mainText,
                    ok, coName, coValue, coChanged, coReadName, coReadValue, coText
                """.trimIndent(),
                "debug-current-thread-local.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("text", state.toString(1))
        assertEquals("main-old", state.toString(2))
        assertEquals("text", state.toString(3))
        assertEquals("text", state.toString(4))
        assertEquals("main-new", state.toString(5))
        assertEquals("main-new", state.toString(6))
        assertTrue(state.toBoolean(7))
        assertEquals("text", state.toString(8))
        assertEquals("co-old", state.toString(9))
        assertEquals("text", state.toString(10))
        assertEquals("text", state.toString(11))
        assertEquals("co-new", state.toString(12))
        assertEquals("co-new", state.toString(13))
    }

    @Test
    fun `debug getlocal returns active varargs by negative index`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function leaf(fixed, ...)
                    local n1, v1 = debug.getlocal(1, -1)
                    local n2, v2 = debug.getlocal(1, -2)
                    local missingName, missingValue = debug.getlocal(1, -3)
                    return n1, v1, n2, v2 == nil, missingName, missingValue
                end

                return leaf("fixed", "left", nil)
                """.trimIndent(),
                "debug-getlocal-varargs.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("(vararg)", state.toString(1))
        assertEquals("left", state.toString(2))
        assertEquals("(vararg)", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.isNil(5))
        assertTrue(state.isNil(6))
    }

    @Test
    fun `debug getlocal reports out of range stack levels`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function leaf()
                    local missing = debug.getlocal(1, 99)
                    local ok, message = pcall(debug.getlocal, 99, 1)
                    local okZero, zeroMessage = pcall(debug.getlocal, 99, 0)
                    return missing, ok, message, okZero, zeroMessage
                end

                return leaf()
                """.trimIndent(),
                "debug-getlocal-level-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertFalse(state.toBoolean(2))
        assertEquals("bad argument #1 to 'getlocal' (level out of range)", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("bad argument #1 to 'getlocal' (level out of range)", state.toString(5))
    }

    @Test
    fun `debug getlocal reports non numeric stack level errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(debug.getlocal, "not-level", 1)
                local okFraction, fractionMessage = pcall(debug.getlocal, 1.5, 1)
                return okString, stringMessage, okFraction, fractionMessage
                """.trimIndent(),
                "debug-getlocal-level-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'getlocal' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'getlocal' (number has no integer representation)", state.toString(4))
    }

    @Test
    fun `debug getlocal reports non numeric local index errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function target(first)
                    return first
                end
                local function probe()
                    local okStack, stackMessage = pcall(debug.getlocal, 1, "not-index")
                    local okLevel, levelMessage = pcall(debug.getlocal, "not-level", "not-index")
                    local okFunction, functionMessage = pcall(debug.getlocal, target, "not-index")
                    local okFraction, fractionMessage = pcall(debug.getlocal, 1, 1.5)
                    return okStack, stackMessage,
                        okLevel, levelMessage,
                        okFunction, functionMessage,
                        okFraction, fractionMessage
                end

                return probe()
                """.trimIndent(),
                "debug-getlocal-index-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'getlocal' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'getlocal' (number expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'getlocal' (number expected)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #2 to 'getlocal' (number has no integer representation)", state.toString(8))
    }

    @Test
    fun `debug getlocal validates local index before stack level`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(debug.getlocal, "not-level", "not-index")
                return ok, message
                """.trimIndent(),
                "debug-getlocal-validation-order.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'getlocal' (number expected)", state.toString(2))
    }

    @Test
    fun `debug getlocal returns function parameter names`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function fixed(first, second)
                    return first, second
                end
                local function vararg(head, ...)
                    return head, ...
                end
                local function withLocals(first)
                    local inner = first
                    local second = inner
                    return second
                end

                local fixedFirst = debug.getlocal(fixed, 1)
                local fixedSecond = debug.getlocal(fixed, 2)
                local fixedMissing = debug.getlocal(fixed, 3)
                local fixedInvalid = debug.getlocal(fixed, 0)
                local varargFirst = debug.getlocal(vararg, 1)
                local varargSecond = debug.getlocal(vararg, 2)
                local localFirst = debug.getlocal(withLocals, 1)
                local localInner = debug.getlocal(withLocals, 2)
                local hostMissing = debug.getlocal(print, 1)
                return fixedFirst, fixedSecond, fixedMissing, fixedInvalid,
                    varargFirst, varargSecond, localFirst, localInner,
                    hostMissing
                """.trimIndent(),
                "debug-getlocal-function.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("first", state.toString(1))
        assertEquals("second", state.toString(2))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
        assertEquals("head", state.toString(5))
        assertTrue(state.isNil(6))
        assertEquals("first", state.toString(7))
        assertTrue(state.isNil(8))
        assertTrue(state.isNil(9))
    }

    @Test
    fun `debug getlocal accepts function subjects after thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    coroutine.yield("pause")
                end)
                coroutine.resume(co)

                local function fixed(first, second)
                    return first, second
                end
                local first = debug.getlocal(co, fixed, 1)
                local second = debug.getlocal(co, fixed, 2)
                local missing = debug.getlocal(co, fixed, 3)
                return first, second, missing
                """.trimIndent(),
                "debug-thread-getlocal-function.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("first", state.toString(1))
        assertEquals("second", state.toString(2))
        assertTrue(state.isNil(3))
    }

    @Test
    fun `debug getlocal and setlocal access active varargs`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function probe(...)
                    local firstName, firstValue = debug.getlocal(1, -1)
                    local secondName, secondValue = debug.getlocal(1, -2)
                    local missing = debug.getlocal(1, -3)
                    local changedName = debug.setlocal(1, -1, "changed")
                    local changedReadName, changedReadValue = debug.getlocal(1, -1)
                    local firstVararg, secondVararg = ...
                    return firstName, firstValue, secondName, secondValue, missing,
                        changedName, changedReadName, changedReadValue, firstVararg, secondVararg
                end

                return probe("first", "second")
                """.trimIndent(),
                "debug-vararg-local.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("(vararg)", state.toString(1))
        assertEquals("first", state.toString(2))
        assertEquals("(vararg)", state.toString(3))
        assertEquals("second", state.toString(4))
        assertTrue(state.isNil(5))
        assertEquals("(vararg)", state.toString(6))
        assertEquals("(vararg)", state.toString(7))
        assertEquals("changed", state.toString(8))
        assertEquals("changed", state.toString(9))
        assertEquals("second", state.toString(10))
    }

    @Test
    fun `debug getlocal and setlocal accept suspended coroutine thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    local function leaf()
                        local text = "old"
                        coroutine.yield("pause")
                        return text
                    end
                    return leaf()
                end)

                local firstOk, firstValue = coroutine.resume(co)
                local name, value = debug.getlocal(co, 0, 1)
                local changedName = debug.setlocal(co, 0, 1, "new")
                local readName, changedValue = debug.getlocal(co, 0, 1)
                local secondOk, result = coroutine.resume(co)
                return firstOk, firstValue, name, value, changedName, readName, changedValue, secondOk, result
                """.trimIndent(),
                "debug-thread-local.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("pause", state.toString(2))
        assertEquals("text", state.toString(3))
        assertEquals("old", state.toString(4))
        assertEquals("text", state.toString(5))
        assertEquals("text", state.toString(6))
        assertEquals("new", state.toString(7))
        assertTrue(state.toBoolean(8))
        assertEquals("new", state.toString(9))
    }

    @Test
    fun `debug thread arguments inspect normal coroutine stacks`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local outer
                outer = coroutine.create(function()
                    local text = "old"
                    local inner = coroutine.create(function()
                        local info = debug.getinfo(outer, 0, "Sl")
                        local name, value = debug.getlocal(outer, 0, 1)
                        local changedName = debug.setlocal(outer, 0, 1, "new")
                        local readName, changedValue = debug.getlocal(outer, 0, 1)
                        local trace = debug.traceback(outer, "normal")
                        return coroutine.status(outer), info.source, info.what, info.currentline > 0,
                            name, value, changedName, readName, changedValue,
                            string.find(trace, "debug-normal-thread.lua", 1, true) ~= nil
                    end)
                    local innerOk, status, source, what, hasLine, name, value, changedName, readName, changedValue,
                        traceHasSource = coroutine.resume(inner)
                    return innerOk, status, source, what, hasLine, name, value, changedName, readName, changedValue,
                        traceHasSource, text
                end)

                return coroutine.resume(outer)
                """.trimIndent(),
                "debug-normal-thread.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("normal", state.toString(3))
        assertEquals("debug-normal-thread.lua", state.toString(4))
        assertEquals("Lua", state.toString(5))
        assertTrue(state.toBoolean(6))
        assertEquals("text", state.toString(7))
        assertEquals("old", state.toString(8))
        assertEquals("text", state.toString(9))
        assertEquals("text", state.toString(10))
        assertEquals("new", state.toString(11))
        assertTrue(state.toBoolean(12))
        assertEquals("new", state.toString(13))
    }

    @Test
    fun `debug setlocal preserves table values in active and suspended lua locals`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function active()
                    local value = {name = "old"}
                    local replacement = {name = "active"}
                    local changedName = debug.setlocal(1, 1, replacement)
                    return changedName, value == replacement, value.name
                end

                local co = coroutine.create(function()
                    local value = {name = "old"}
                    coroutine.yield(value)
                    return value.name
                end)

                local firstOk, yielded = coroutine.resume(co)
                local replacement = {name = "suspended"}
                local changedName = debug.setlocal(co, 0, 1, replacement)
                local readName, readValue = debug.getlocal(co, 0, 1)
                local secondOk, result = coroutine.resume(co)
                local activeName, activeSame, activeValue = active()
                return firstOk, yielded.name, changedName, readName, readValue == replacement,
                    secondOk, result, activeName, activeSame, activeValue
                """.trimIndent(),
                "debug-setlocal-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("old", state.toString(2))
        assertEquals("value", state.toString(3))
        assertEquals("value", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.toBoolean(6))
        assertEquals("suspended", state.toString(7))
        assertEquals("value", state.toString(8))
        assertTrue(state.toBoolean(9))
        assertEquals("active", state.toString(10))
    }

    @Test
    fun `debug setlocal mutates active lua locals`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function leaf()
                    local number = 12
                    local text = "old"
                    local n1 = debug.setlocal(1, 1, 99)
                    local n2 = debug.setlocal(1, 2, "new")
                    local missing = debug.setlocal(1, 99, "ignored")
                    return n1, n2, missing, number, text
                end

                return leaf()
                """.trimIndent(),
                "debug-setlocal.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("number", state.toString(1))
        assertEquals("text", state.toString(2))
        assertTrue(state.isNil(3))
        assertEquals(99L, state.toInteger(4))
        assertEquals("new", state.toString(5))
    }

    @Test
    fun `debug setlocal mutates active varargs by negative index`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function leaf(...)
                    local n1 = debug.setlocal(1, -1, "new-left")
                    local n2 = debug.setlocal(1, -2, false)
                    local missing = debug.setlocal(1, -3, "ignored")
                    local first, second = ...
                    return n1, first, n2, second, missing
                end

                return leaf("left", "right")
                """.trimIndent(),
                "debug-setlocal-varargs.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("(vararg)", state.toString(1))
        assertEquals("new-left", state.toString(2))
        assertEquals("(vararg)", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `debug setlocal reports out of range stack levels`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function leaf()
                    local number = 12
                    local missing = debug.setlocal(1, 99, "ignored")
                    local ok, message = pcall(debug.setlocal, 99, 1, "ignored")
                    return missing, ok, message, number
                end

                return leaf()
                """.trimIndent(),
                "debug-setlocal-level-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertFalse(state.toBoolean(2))
        assertEquals("bad argument #1 to 'setlocal' (level out of range)", state.toString(3))
        assertEquals(12L, state.toInteger(4))
    }

    @Test
    fun `debug setlocal validates stack level before replacement argument`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(debug.setlocal, 99, 1)
                return ok, message
                """.trimIndent(),
                "debug-setlocal-level-before-value.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'setlocal' (level out of range)", state.toString(2))
    }

    @Test
    fun `debug setlocal reports non numeric stack level errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(debug.setlocal, "not-level", 1, "ignored")
                local okFraction, fractionMessage = pcall(debug.setlocal, 1.5, 1, "ignored")
                return okString, stringMessage, okFraction, fractionMessage
                """.trimIndent(),
                "debug-setlocal-level-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'setlocal' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'setlocal' (number has no integer representation)", state.toString(4))
    }

    @Test
    fun `debug setlocal validates stack level before local index`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(debug.setlocal, "not-level", "not-index", "ignored")
                return ok, message
                """.trimIndent(),
                "debug-setlocal-validation-order.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'setlocal' (number expected)", state.toString(2))
    }

    @Test
    fun `debug setlocal reports non numeric local index errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function probe()
                    local okString, stringMessage = pcall(debug.setlocal, 1, "not-index", "ignored")
                    local okFraction, fractionMessage = pcall(debug.setlocal, 1, 1.5, "ignored")
                    return okString, stringMessage, okFraction, fractionMessage
                end

                return probe()
                """.trimIndent(),
                "debug-setlocal-index-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'setlocal' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'setlocal' (number has no integer representation)", state.toString(4))
    }

    @Test
    fun `debug setlocal requires replacement value`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function leaf()
                    local target = 12
                    local okMissing, missingMessage = pcall(function()
                        return debug.setlocal(1, 1)
                    end)
                    local okInvalidIndex, invalidIndexResult = pcall(function()
                        return debug.setlocal(1, 0, "ignored")
                    end)
                    local okInvalidIndexMissing, invalidIndexMissingMessage = pcall(function()
                        return debug.setlocal(1, 0)
                    end)
                    return okMissing, missingMessage,
                        okInvalidIndex, invalidIndexResult,
                        okInvalidIndexMissing, invalidIndexMissingMessage,
                        target
                end

                return leaf()
                """.trimIndent(),
                "debug-setlocal-value-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #3 to 'setlocal' (value expected)", state.toString(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.isNil(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'setlocal' (value expected)", state.toString(6))
        assertEquals(12L, state.toInteger(7))
    }

    @Test
    fun `debug setlocal and setupvalue require replacement arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local secret = "old"
                local function capture()
                    return secret
                end

                local function leaf()
                    local number = 1
                    local missingSetOk, missingSetMessage = pcall(function()
                        return debug.setlocal(1, 1)
                    end)
                    local nilSetName = debug.setlocal(1, 1, nil)
                    local missingSetupOk, missingSetupMessage = pcall(debug.setupvalue, capture, 1)
                    local nilSetupName = debug.setupvalue(capture, 1, nil)
                    return missingSetOk, missingSetMessage,
                        nilSetName, number == nil,
                        missingSetupOk, missingSetupMessage,
                        nilSetupName, capture() == nil
                end

                return leaf()
                """.trimIndent(),
                "debug-replacement-arguments.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #3 to 'setlocal' (value expected)", state.toString(2))
        assertEquals("number", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'setupvalue' (value expected)", state.toString(6))
        assertEquals("secret", state.toString(7))
        assertTrue(state.toBoolean(8))
    }

    @Test
    fun `debug getupvalue returns lua closure upvalue names and values`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local secret = "ok"
                local count = 42
                local function capture()
                    return secret, count
                end

                local n1, v1 = debug.getupvalue(capture, 1)
                local n2, v2 = debug.getupvalue(capture, 2)
                local missingCount = select("#", debug.getupvalue(capture, 99))
                local zeroCount = select("#", debug.getupvalue(capture, 0))
                return n1, v1, n2, v2, missingCount, zeroCount
                """.trimIndent(),
                "debug-getupvalue.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("secret", state.toString(1))
        assertEquals("ok", state.toString(2))
        assertEquals("count", state.toString(3))
        assertEquals(42L, state.toInteger(4))
        assertEquals(0L, state.toInteger(5))
        assertEquals(0L, state.toInteger(6))
    }

    @Test
    fun `debug upvalueid returns stable shared upvalue identities`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local shared = "shared"
                local other = "other"

                local function left()
                    return shared
                end
                local function right()
                    return shared
                end
                local function different()
                    return other
                end

                local leftShared = debug.upvalueid(left, 1)
                local leftAgain = debug.upvalueid(left, 1)
                local rightShared = debug.upvalueid(right, 1)
                local differentOther = debug.upvalueid(different, 1)

                return type(leftShared),
                    leftShared == leftAgain,
                    leftShared == rightShared,
                    leftShared == differentOther
                """.trimIndent(),
                "debug-upvalueid.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("userdata", state.toString(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertFalse(state.toBoolean(4))
    }

    @Test
    fun `debug upvaluejoin makes lua closures share upvalues`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local leftValue = "left"
                local rightValue = "right"

                local function left()
                    return leftValue
                end
                local function right()
                    return rightValue
                end

                local beforeLeft = left()
                local beforeRight = right()
                local beforeSame = debug.upvalueid(left, 1) == debug.upvalueid(right, 1)
                local okSource, sourceMessage = pcall(function()
                    debug.upvaluejoin(left, 1, right, 99)
                end)

                debug.upvaluejoin(left, 1, right, 1)

                local afterSame = debug.upvalueid(left, 1) == debug.upvalueid(right, 1)
                local afterLeft = left()
                local afterRight = right()
                debug.setupvalue(right, 1, "joined")
                return beforeLeft, beforeRight, beforeSame, okSource, sourceMessage,
                    afterSame, afterLeft, afterRight, left(), right()
                """.trimIndent(),
                "debug-upvaluejoin.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("left", state.toString(1))
        assertEquals("right", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertFalse(state.toBoolean(4))
        assertEquals("bad argument #4 to 'upvaluejoin' (invalid upvalue index)", state.toString(5))
        assertTrue(state.toBoolean(6))
        assertEquals("right", state.toString(7))
        assertEquals("right", state.toString(8))
        assertEquals("joined", state.toString(9))
        assertEquals("joined", state.toString(10))
    }

    @Test
    fun `debug upvalue identity functions report argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function make(value)
                    return function()
                        return value
                    end
                end
                local left = make("left")
                local leftAgain = make("left-again")
                local right = make("right")

                local okGetFunction, getFunctionMessage = pcall(debug.getupvalue, "not-function", 1)
                local okGetIndex, getIndexMessage = pcall(debug.getupvalue, left, "not-index")
                local okSetupFunction, setupFunctionMessage = pcall(debug.setupvalue, "not-function", 1, "value")
                local okSetupIndex, setupIndexMessage = pcall(debug.setupvalue, left, "not-index", "value")
                local okFunction, functionMessage = pcall(debug.upvalueid, "not-function", 1)
                local okIdIndex, idIndexResult = pcall(debug.upvalueid, left, 99)
                local zeroId = debug.upvalueid(left, 0)
                local okJoinFunction, joinFunctionMessage = pcall(debug.upvaluejoin, "not-function", 1, leftAgain, 1)
                local okJoinOtherFunction, joinOtherFunctionMessage = pcall(function()
                    debug.upvaluejoin(left, 1, "not-function", 1)
                end)
                local okJoinTarget, joinTargetMessage = pcall(debug.upvaluejoin, left, 99, leftAgain, 1)
                return okGetFunction, getFunctionMessage,
                    okGetIndex, getIndexMessage,
                    okSetupFunction, setupFunctionMessage,
                    okSetupIndex, setupIndexMessage,
                    okFunction, functionMessage,
                    okIdIndex, idIndexResult, zeroId,
                    okJoinFunction, joinFunctionMessage,
                    okJoinOtherFunction, joinOtherFunctionMessage,
                    okJoinTarget, joinTargetMessage
                """.trimIndent(),
                "debug-upvalue-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'getupvalue' (function expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'getupvalue' (number expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #1 to 'setupvalue' (function expected)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #2 to 'setupvalue' (number expected)", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("bad argument #1 to 'upvalueid' (function expected)", state.toString(10))
        assertTrue(state.toBoolean(11))
        assertTrue(state.isNil(12))
        assertTrue(state.isNil(13))
        assertFalse(state.toBoolean(14))
        assertEquals("bad argument #1 to 'upvaluejoin' (function expected)", state.toString(15))
        assertFalse(state.toBoolean(16))
        assertEquals("bad argument #3 to 'upvaluejoin' (function expected)", state.toString(17))
        assertFalse(state.toBoolean(18))
        assertEquals("bad argument #2 to 'upvaluejoin' (invalid upvalue index)", state.toString(19))
    }

    @Test
    fun `debug upvalue accessors validate index before function`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okGetBoth, getBothMessage = pcall(debug.getupvalue, "not-function", "not-index")
                local okGetFunction, getFunctionMessage = pcall(debug.getupvalue, "not-function", 1)
                local okSetupMissing, setupMissingMessage = pcall(debug.setupvalue, "not-function", "not-index")
                local okSetupBoth, setupBothMessage = pcall(debug.setupvalue, "not-function", "not-index", "value")
                local okSetupFunction, setupFunctionMessage = pcall(debug.setupvalue, "not-function", 1, "value")
                return okGetBoth, getBothMessage,
                    okGetFunction, getFunctionMessage,
                    okSetupMissing, setupMissingMessage,
                    okSetupBoth, setupBothMessage,
                    okSetupFunction, setupFunctionMessage
                """.trimIndent(),
                "debug-upvalue-validation-order.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'getupvalue' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'getupvalue' (function expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'setupvalue' (value expected)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #2 to 'setupvalue' (number expected)", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("bad argument #1 to 'setupvalue' (function expected)", state.toString(10))
    }

    @Test
    fun `debug upvalue identity functions validate index before function`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function make(value)
                    return function()
                        return value
                    end
                end
                local left = make("left")
                local right = make("right")

                local okIdBoth, idBothMessage = pcall(debug.upvalueid, "not-function", "not-index")
                local okIdFunction, idFunctionMessage = pcall(debug.upvalueid, "not-function", 1)
                local okJoinTargetBoth, joinTargetBothMessage =
                    pcall(debug.upvaluejoin, "not-function", "not-index", right, 1)
                local okJoinTargetFunction, joinTargetFunctionMessage =
                    pcall(debug.upvaluejoin, "not-function", 1, right, 1)
                local okJoinSourceBoth, joinSourceBothMessage =
                    pcall(debug.upvaluejoin, left, 1, "not-function", "not-index")
                local okJoinSourceFunction, joinSourceFunctionMessage =
                    pcall(debug.upvaluejoin, left, 1, "not-function", 1)
                local okJoinTargetBeforeSource, joinTargetBeforeSourceMessage =
                    pcall(debug.upvaluejoin, left, "not-index", "not-function", "also-not-index")

                return okIdBoth, idBothMessage,
                    okIdFunction, idFunctionMessage,
                    okJoinTargetBoth, joinTargetBothMessage,
                    okJoinTargetFunction, joinTargetFunctionMessage,
                    okJoinSourceBoth, joinSourceBothMessage,
                    okJoinSourceFunction, joinSourceFunctionMessage,
                    okJoinTargetBeforeSource, joinTargetBeforeSourceMessage
                """.trimIndent(),
                "debug-upvalue-identity-validation-order.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'upvalueid' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'upvalueid' (function expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'upvaluejoin' (number expected)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #1 to 'upvaluejoin' (function expected)", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("bad argument #4 to 'upvaluejoin' (number expected)", state.toString(10))
        assertFalse(state.toBoolean(11))
        assertEquals("bad argument #3 to 'upvaluejoin' (function expected)", state.toString(12))
        assertFalse(state.toBoolean(13))
        assertEquals("bad argument #2 to 'upvaluejoin' (number expected)", state.toString(14))
    }

    @Test
    fun `debug upvalue helpers validate indexes before function arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local value = "upvalue"
                local function left()
                    return value
                end
                local function right()
                    return value
                end

                local okGet, getMessage = pcall(debug.getupvalue, "not-function", "not-index")
                local okSetupMissing, setupMissingMessage = pcall(debug.setupvalue, "not-function", "not-index")
                local okSetupIndex, setupIndexMessage = pcall(debug.setupvalue, "not-function", "not-index", "value")
                local okId, idMessage = pcall(debug.upvalueid, "not-function", "not-index")
                local okJoinTargetIndex, joinTargetIndexMessage =
                    pcall(debug.upvaluejoin, "not-function", "not-index", "not-function", "not-index")
                local okJoinTargetFunction, joinTargetFunctionMessage =
                    pcall(debug.upvaluejoin, "not-function", 1, "not-function", "not-index")
                local okJoinSourceIndex, joinSourceIndexMessage = pcall(function()
                    debug.upvaluejoin(left, 1, "not-function", "not-index")
                end)
                local okJoinSourceFunction, joinSourceFunctionMessage = pcall(function()
                    debug.upvaluejoin(left, 1, "not-function", 1)
                end)
                local okJoinValid, joinValidMessage = pcall(function()
                    debug.upvaluejoin(left, 1, right, 1)
                end)

                return okGet, getMessage,
                    okSetupMissing, setupMissingMessage,
                    okSetupIndex, setupIndexMessage,
                    okId, idMessage,
                    okJoinTargetIndex, joinTargetIndexMessage,
                    okJoinTargetFunction, joinTargetFunctionMessage,
                    okJoinSourceIndex, joinSourceIndexMessage,
                    okJoinSourceFunction, joinSourceFunctionMessage,
                    okJoinValid, joinValidMessage
                """.trimIndent(),
                "debug-upvalue-validation-order.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'getupvalue' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #3 to 'setupvalue' (value expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'setupvalue' (number expected)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #2 to 'upvalueid' (number expected)", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("bad argument #2 to 'upvaluejoin' (number expected)", state.toString(10))
        assertFalse(state.toBoolean(11))
        assertEquals("bad argument #1 to 'upvaluejoin' (function expected)", state.toString(12))
        assertFalse(state.toBoolean(13))
        assertEquals("bad argument #4 to 'upvaluejoin' (number expected)", state.toString(14))
        assertFalse(state.toBoolean(15))
        assertEquals("bad argument #3 to 'upvaluejoin' (function expected)", state.toString(16))
        assertTrue(state.toBoolean(17), state.toString(18))
        assertTrue(state.isNil(18))
    }

    @Test
    fun `debug upvalue helpers report nonintegral index errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function make(value)
                    return function()
                        return value
                    end
                end
                local left = make("left")
                local right = make("right")
                local okGet, getMessage = pcall(debug.getupvalue, left, 1.5)
                local okSetup, setupMessage = pcall(debug.setupvalue, left, 1.5, "value")
                local okId, idMessage = pcall(debug.upvalueid, left, 1.5)
                local okJoinTarget, joinTargetMessage = pcall(debug.upvaluejoin, left, 1.5, right, 1)
                return okGet, getMessage,
                    okSetup, setupMessage,
                    okId, idMessage,
                    okJoinTarget, joinTargetMessage
                """.trimIndent(),
                "debug-upvalue-nonintegral-index-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'getupvalue' (number has no integer representation)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'setupvalue' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'upvalueid' (number has no integer representation)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #2 to 'upvaluejoin' (number has no integer representation)", state.toString(8))
    }

    @Test
    fun `debug setupvalue mutates lua closure upvalues`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local secret = "old"
                local count = 1
                local function capture()
                    return secret, count
                end

                local n1 = debug.setupvalue(capture, 1, "new")
                local n2 = debug.setupvalue(capture, 2, 99)
                local missingCount = select("#", debug.setupvalue(capture, 99, "ignored"))
                local zeroCount = select("#", debug.setupvalue(capture, 0, "ignored"))
                local v1, v2 = capture()
                return n1, n2, missingCount, zeroCount, v1, v2
                """.trimIndent(),
                "debug-setupvalue.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("secret", state.toString(1))
        assertEquals("count", state.toString(2))
        assertEquals(0L, state.toInteger(3))
        assertEquals(0L, state.toInteger(4))
        assertEquals("new", state.toString(5))
        assertEquals(99L, state.toInteger(6))
    }

    @Test
    fun `debug setupvalue preserves table values in lua closure upvalues`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local value = {name = "old"}
                local function capture()
                    return value
                end
                local replacement = {name = "new"}
                local changedName = debug.setupvalue(capture, 1, replacement)
                local readName, readValue = debug.getupvalue(capture, 1)
                local captured = capture()
                return changedName, readName, readValue == replacement,
                    captured == replacement, captured.name
                """.trimIndent(),
                "debug-setupvalue-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("value", state.toString(1))
        assertEquals("value", state.toString(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertEquals("new", state.toString(5))
    }

    @Test
    fun `debug setupvalue requires replacement value`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local value = "old"
                local function capture()
                    return value
                end

                local okMissing, missingMessage = pcall(debug.setupvalue, capture, 1)
                local okMissingType, missingTypeMessage = pcall(debug.setupvalue, "not-function", 1)
                local okInvalidIndex, invalidIndexResult = pcall(debug.setupvalue, capture, 99, "ignored")
                local invalidPcallCount = select("#", pcall(debug.setupvalue, capture, 99, "ignored"))
                return okMissing, missingMessage,
                    okMissingType, missingTypeMessage,
                    okInvalidIndex, invalidIndexResult,
                    invalidPcallCount, capture()
                """.trimIndent(),
                "debug-setupvalue-value-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #3 to 'setupvalue' (value expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #3 to 'setupvalue' (value expected)", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.isNil(6))
        assertEquals(1L, state.toInteger(7))
        assertEquals("old", state.toString(8))
    }

    @Test
    fun `debug uservalue functions preserve host userdata values`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        val hostUserData = Any()
        state.register("hostUserData") { LuaReturn.of(hostUserData) }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local userdata = hostUserData()
                local initial, initialOk = debug.getuservalue(userdata)
                local marker = {name = "marker"}
                local returned = debug.setuservalue(userdata, marker)
                local stored, storedOk = debug.getuservalue(userdata)
                local nilReturned = debug.setuservalue(userdata, nil, 2)
                local nilValue, nilOk = debug.getuservalue(userdata, 2)
                local invalidSet = debug.setuservalue(userdata, "ignored", 0)
                local invalidValue, invalidOk = debug.getuservalue(userdata, 0)
                local nonUserdata, nonUserdataOk = debug.getuservalue({})
                return initial, initialOk,
                    returned == userdata, stored == marker, stored.name, storedOk,
                    nilReturned == userdata, nilValue, nilOk,
                    invalidSet, invalidValue, invalidOk,
                    nonUserdata, nonUserdataOk
                """.trimIndent(),
                "debug-uservalue.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertEquals("marker", state.toString(5))
        assertTrue(state.toBoolean(6))
        assertTrue(state.toBoolean(7))
        assertTrue(state.isNil(8))
        assertTrue(state.toBoolean(9))
        assertTrue(state.isNil(10))
        assertTrue(state.isNil(11))
        assertTrue(state.isNil(12))
        assertTrue(state.isNil(13))
        assertTrue(state.isNil(14))
    }

    @Test
    fun `debug uservalue functions validate arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.register("hostUserData") { LuaReturn.of(Any()) }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local userdata = hostUserData()
                local setTargetOk, setTargetMessage = pcall(debug.setuservalue, {}, "value")
                local setValueOk, setValueMessage = pcall(debug.setuservalue, userdata)
                local getIndexOk, getIndexMessage = pcall(debug.getuservalue, userdata, "bad")
                local setIndexOk, setIndexMessage = pcall(debug.setuservalue, userdata, "value", "bad")
                local getIndexFractionOk, getIndexFractionMessage = pcall(debug.getuservalue, userdata, 1.5)
                local setIndexFractionOk, setIndexFractionMessage = pcall(debug.setuservalue, userdata, "value", 1.5)
                local setIndexFirstOk, setIndexFirstMessage =
                    pcall(debug.setuservalue, "not-userdata", "value", "bad")
                return setTargetOk, setTargetMessage,
                    setValueOk, setValueMessage,
                    getIndexOk, getIndexMessage,
                    setIndexOk, setIndexMessage,
                    getIndexFractionOk, getIndexFractionMessage,
                    setIndexFractionOk, setIndexFractionMessage,
                    setIndexFirstOk, setIndexFirstMessage
                """.trimIndent(),
                "debug-uservalue-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'setuservalue' (userdata expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'setuservalue' (value expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'getuservalue' (number expected)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #3 to 'setuservalue' (number expected)", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("bad argument #2 to 'getuservalue' (number has no integer representation)", state.toString(10))
        assertFalse(state.toBoolean(11))
        assertEquals("bad argument #3 to 'setuservalue' (number has no integer representation)", state.toString(12))
        assertFalse(state.toBoolean(13))
        assertEquals("bad argument #3 to 'setuservalue' (number expected)", state.toString(14))
    }

    @Test
    fun `debug sethook and gethook preserve line hook state`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local events = {}
                local function hook(event, line)
                    events[#events + 1] = event
                    events[#events + 1] = line
                end

                debug.sethook(hook, "l", 0)
                local installed, mask, count = debug.gethook()
                local value = 1
                value = value + 1
                debug.sethook()
                local after = value + 1

                return installed == hook, mask, count, events[1], type(events[2]), #events, after
                """.trimIndent(),
                "debug-sethook-line.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("l", state.toString(2))
        assertEquals(0L, state.toInteger(3))
        assertEquals("line", state.toString(4))
        assertEquals("number", state.toString(5))
        assertTrue((state.toInteger(6) ?: 0L) >= 2L)
        assertEquals(3L, state.toInteger(7))
    }

    @Test
    fun `debug sethook and gethook accept explicit current thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local mainThread = coroutine.running()
                local function hook() end
                debug.sethook(mainThread, hook, "cr", 3)
                local mainInstalled, mainMask, mainCount = debug.gethook(mainThread)
                debug.sethook(mainThread)
                local mainCleared = debug.gethook(mainThread)

                local co = coroutine.create(function()
                    local running = coroutine.running()
                    local function coHook() end
                    debug.sethook(running, coHook, "l", 0)
                    local installed, mask, count = debug.gethook(running)
                    debug.sethook(running)
                    local cleared = debug.gethook(running)
                    return installed == coHook, mask, count, cleared
                end)

                local ok, coInstalled, coMask, coCount, coCleared = coroutine.resume(co)
                return mainInstalled == hook, mainMask, mainCount, mainCleared,
                    ok, coInstalled, coMask, coCount, coCleared
                """.trimIndent(),
                "debug-current-thread-hook.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("cr", state.toString(2))
        assertEquals(3L, state.toInteger(3))
        assertTrue(state.isNil(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.toBoolean(6))
        assertEquals("l", state.toString(7))
        assertEquals(0L, state.toInteger(8))
        assertTrue(state.isNil(9))
    }

    @Test
    fun `debug sethook and gethook accept suspended coroutine thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local events = {}
                local co = coroutine.create(function()
                    coroutine.yield("pause")
                    local value = 1
                    value = value + 1
                    return value
                end)
                local firstOk, firstValue = coroutine.resume(co)

                local function hook(event, line)
                    if event == "line" then
                        events[#events + 1] = line
                    end
                end

                debug.sethook(co, hook, "l", 0)
                local installed, mask, count = debug.gethook(co)
                local secondOk, result = coroutine.resume(co)
                debug.sethook(co)
                local cleared = debug.gethook(co)
                return firstOk, firstValue, installed == hook, mask, count,
                    secondOk, result, #events > 0, cleared
                """.trimIndent(),
                "debug-thread-sethook.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("pause", state.toString(2))
        assertTrue(state.toBoolean(3))
        assertEquals("l", state.toString(4))
        assertEquals(0L, state.toInteger(5))
        assertTrue(state.toBoolean(6))
        assertEquals(2L, state.toInteger(7))
        assertTrue(state.toBoolean(8))
        assertTrue(state.isNil(9))
    }

    @Test
    fun `debug sethook and gethook accept normal coroutine thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local outer
                outer = coroutine.create(function()
                    local inner = coroutine.create(function()
                        local before = debug.gethook(outer)
                        local function hook() end
                        debug.sethook(outer, hook, "cr", 3)
                        local installed, mask, count = debug.gethook(outer)
                        debug.sethook(outer)
                        local after = debug.gethook(outer)
                        return coroutine.status(outer), before, installed == hook, mask, count, after
                    end)
                    return coroutine.resume(inner)
                end)

                return coroutine.resume(outer)
                """.trimIndent(),
                "debug-normal-thread-hook.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("normal", state.toString(3))
        assertTrue(state.isNil(4))
        assertTrue(state.toBoolean(5))
        assertEquals("cr", state.toString(6))
        assertEquals(3L, state.toInteger(7))
        assertTrue(state.isNil(8))
    }

    @Test
    fun `debug sethook clears inert suspended thread hooks and preserves negative active counts`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    coroutine.yield("pause")
                    return "done"
                end)
                coroutine.resume(co)

                local hook = function() end
                debug.sethook(co, hook, "", 0)
                local clearedZero = debug.gethook(co)
                debug.sethook(co, hook, "", -1)
                local clearedNegative = debug.gethook(co)
                debug.sethook(co, hook, "l", -4)
                local installed, mask, count = debug.gethook(co)
                debug.sethook(co)
                return clearedZero, clearedNegative, installed == hook, mask, count
                """.trimIndent(),
                "debug-thread-sethook-inert-count.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
        assertTrue(state.toBoolean(3))
        assertEquals("l", state.toString(4))
        assertEquals(-4L, state.toInteger(5))
    }

    @Test
    fun `debug sethook reports shifted thread argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    coroutine.yield("pause")
                end)
                coroutine.resume(co)
                local okHook, hookMessage = pcall(debug.sethook, co, "not-function", "l", 0)
                local okMask, maskMessage = pcall(debug.sethook, co, function() end, nil, 0)
                local okCount, countMessage = pcall(debug.sethook, co, function() end, "", "bad")
                return okHook, hookMessage, okMask, maskMessage, okCount, countMessage
                """.trimIndent(),
                "debug-thread-sethook-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'sethook' (function expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #3 to 'sethook' (string expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #4 to 'sethook' (number expected)", state.toString(6))
    }

    @Test
    fun `debug gethook ignores non thread arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function hook() end
                debug.sethook(hook, "l", 0)
                local installed, mask, count = debug.gethook("not-thread")
                debug.sethook()
                return installed == hook, mask, count
                """.trimIndent(),
                "debug-gethook-non-thread.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("l", state.toString(2))
        assertEquals(0L, state.toInteger(3))
    }

    @Test
    fun `debug count hook runs after configured instruction intervals`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local count = 0
                debug.sethook(function(event, line)
                    if event == "count" and line == nil then
                        count = count + 1
                    end
                end, "", 2)

                local total = 0
                for i = 1, 5 do
                    total = total + i
                end
                debug.sethook()

                return count, total
                """.trimIndent(),
                "debug-sethook-count.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue((state.toInteger(1) ?: 0L) > 0L)
        assertEquals(15L, state.toInteger(2))
    }

    @Test
    fun `debug call and return hooks run for lua frames`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local events = {}
                local function hook(event, line)
                    if event == "call" or event == "return" then
                        events[#events + 1] = event
                    end
                end

                local function leaf()
                    return 42
                end

                debug.sethook(hook, "cr", 0)
                local result = leaf()
                debug.sethook()

                local calls = 0
                local returns = 0
                for i = 1, #events do
                    if events[i] == "call" then
                        calls = calls + 1
                    elseif events[i] == "return" then
                        returns = returns + 1
                    end
                end

                return result, calls, returns
                """.trimIndent(),
                "debug-sethook-call-return.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(42L, state.toInteger(1))
        assertTrue((state.toInteger(2) ?: 0L) >= 2L)
        assertTrue((state.toInteger(3) ?: 0L) >= 2L)
    }

    @Test
    fun `debug getinfo reports transfer metadata inside call and return hooks`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local records = {}
                local function hook(event, line)
                    if event == "call" or event == "return" then
                        local info = debug.getinfo(2, "nr")
                        if info.name == "leaf" then
                            records[#records + 1] = event
                            records[#records + 1] = info.ftransfer
                            records[#records + 1] = info.ntransfer
                        end
                    end
                end

                local function leaf(a, b)
                    return a + b, a - b
                end

                debug.sethook(hook, "cr", 0)
                local sum, diff = leaf(5, 3)
                debug.sethook()

                return sum, diff,
                    records[1], records[2], records[3],
                    records[4], records[5], records[6]
                """.trimIndent(),
                "debug-getinfo-hook-transfer.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(8L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals("call", state.toString(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals("return", state.toString(6))
        assertEquals(1L, state.toInteger(7))
        assertEquals(2L, state.toInteger(8))
    }

    @Test
    fun `debug getinfo reports hook call site names`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local hookName
                local hookNameWhat
                debug.sethook(function()
                    local info = debug.getinfo(1, "n")
                    hookName = info.name
                    hookNameWhat = info.namewhat
                    debug.sethook()
                end, "l", 0)

                local value = 1
                value = value + 1

                return hookName, hookNameWhat, value
                """.trimIndent(),
                "debug-getinfo-hook-name.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("?", state.toString(1))
        assertEquals("hook", state.toString(2))
        assertEquals(2L, state.toInteger(3))
    }

    @Test
    fun `debug sethook ignores unknown mask characters`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.sethook(function() end, "x", 0)
                local none = {debug.gethook()}
                debug.sethook(function() end, "x", 2)
                local countHook, countMask, countInterval = debug.gethook()
                debug.sethook(function() end, "cxr", 0)
                local eventHook, eventMask, eventInterval = debug.gethook()
                debug.sethook(function() end, "llrccx", 0)
                local orderedHook, orderedMask, orderedInterval = debug.gethook()
                debug.sethook()
                return #none,
                    type(countHook), countMask, countInterval,
                    type(eventHook), eventMask, eventInterval,
                    type(orderedHook), orderedMask, orderedInterval
                """.trimIndent(),
                "debug-sethook-unknown-mask.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(0L, state.toInteger(1))
        assertEquals("function", state.toString(2))
        assertEquals("", state.toString(3))
        assertEquals(2L, state.toInteger(4))
        assertEquals("function", state.toString(5))
        assertEquals("cr", state.toString(6))
        assertEquals(0L, state.toInteger(7))
        assertEquals("function", state.toString(8))
        assertEquals("crl", state.toString(9))
        assertEquals(0L, state.toInteger(10))
    }

    @Test
    fun `debug sethook clears inert hooks and preserves negative active counts`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local hook = function() end
                debug.sethook(hook, "", 0)
                local clearedZero = debug.gethook()
                debug.sethook(hook, "", -1)
                local clearedNegative = debug.gethook()
                debug.sethook(hook, "l", -3)
                local installed, mask, count = debug.gethook()
                debug.sethook()
                return clearedZero, clearedNegative, installed == hook, mask, count
                """.trimIndent(),
                "debug-sethook-inert-count.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
        assertTrue(state.toBoolean(3))
        assertEquals("l", state.toString(4))
        assertEquals(-3L, state.toInteger(5))
    }

    @Test
    fun `debug sethook preserves negative count for event hooks`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.sethook(function() end, "l", -1)
                local lineHook, lineMask, lineCount = debug.gethook()
                debug.sethook(function() end, "", -1)
                local none = {debug.gethook()}
                return type(lineHook), lineMask, lineCount, #none
                """.trimIndent(),
                "debug-sethook-negative-count.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("function", state.toString(1))
        assertEquals("l", state.toString(2))
        assertEquals(-1L, state.toInteger(3))
        assertEquals(0L, state.toInteger(4))
    }

    @Test
    fun `debug sethook reports missing mask errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return debug.sethook(function() end)""", "debug-sethook-mask-missing-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'sethook' (string expected)", state.toString(-1))
    }

    @Test
    fun `debug sethook reports non string mask errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return debug.sethook(function() end, true, 0)""", "debug-sethook-mask-type-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'sethook' (string expected)", state.toString(-1))
    }

    @Test
    fun `debug sethook reports hook function errors after mask validation`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return debug.sethook("not-function", "x", 0)""", "debug-sethook-function-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'sethook' (function expected)", state.toString(-1))

        assertEquals(
            LuaStatus.OK,
            state.load("""return debug.sethook("not-function", true, 0)""", "debug-sethook-mask-before-function-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'sethook' (string expected)", state.toString(-1))
    }

    @Test
    fun `debug sethook reports non numeric count errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(debug.sethook, function() end, "", "not-count")
                local okFraction, fractionMessage = pcall(debug.sethook, function() end, "", 1.5)
                return okString, stringMessage, okFraction, fractionMessage
                """.trimIndent(),
                "debug-sethook-count-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #3 to 'sethook' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #3 to 'sethook' (number has no integer representation)", state.toString(4))
    }

    @Test
    fun `debug integer arguments report fractional number errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local value = "upvalue"
                local function closure()
                    return value
                end
                local _, tracebackMessage = pcall(debug.traceback, "boom", 1.5)
                local _, getinfoMessage = pcall(debug.getinfo, 1.5)
                local _, getlocalLevelMessage = pcall(debug.getlocal, 1.5, 1)
                local _, getlocalIndexMessage = pcall(debug.getlocal, 1, 1.5)
                local _, setlocalLevelMessage = pcall(debug.setlocal, 1.5, 1, "x")
                local _, setlocalIndexMessage = pcall(debug.setlocal, 1, 1.5, "x")
                local _, getupvalueMessage = pcall(debug.getupvalue, closure, 1.5)
                local _, setupvalueMessage = pcall(debug.setupvalue, closure, 1.5, "x")
                local _, upvalueidMessage = pcall(debug.upvalueid, closure, 1.5)
                local _, upvaluejoinTargetMessage = pcall(debug.upvaluejoin, closure, 1.5, closure, 1)
                local _, upvaluejoinSourceMessage = pcall(debug.upvaluejoin, closure, 1, closure, 1.5)
                local _, sethookMessage = pcall(debug.sethook, function() end, "", 1.5)
                return tracebackMessage,
                    getinfoMessage,
                    getlocalLevelMessage,
                    getlocalIndexMessage,
                    setlocalLevelMessage,
                    setlocalIndexMessage,
                    getupvalueMessage,
                    setupvalueMessage,
                    upvalueidMessage,
                    upvaluejoinTargetMessage,
                    upvaluejoinSourceMessage,
                    sethookMessage
                """.trimIndent(),
                "debug-fractional-integer-arguments.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("bad argument #2 to 'traceback' (number has no integer representation)", state.toString(1))
        assertEquals("bad argument #1 to 'getinfo' (number has no integer representation)", state.toString(2))
        assertEquals("bad argument #1 to 'getlocal' (number has no integer representation)", state.toString(3))
        assertEquals("bad argument #2 to 'getlocal' (number has no integer representation)", state.toString(4))
        assertEquals("bad argument #1 to 'setlocal' (number has no integer representation)", state.toString(5))
        assertEquals("bad argument #2 to 'setlocal' (number has no integer representation)", state.toString(6))
        assertEquals("bad argument #2 to 'getupvalue' (number has no integer representation)", state.toString(7))
        assertEquals("bad argument #2 to 'setupvalue' (number has no integer representation)", state.toString(8))
        assertEquals("bad argument #2 to 'upvalueid' (number has no integer representation)", state.toString(9))
        assertEquals(
            "bad argument #2 to 'upvaluejoin' (number has no integer representation)",
            state.toString(10),
        )
        assertEquals(
            "bad argument #4 to 'upvaluejoin' (number has no integer representation)",
            state.toString(11),
        )
        assertEquals("bad argument #3 to 'sethook' (number has no integer representation)", state.toString(12))
    }

    @Test
    fun `debug sethook nil clears hook without validating mask`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.sethook(function() end, "l", 0)
                debug.sethook(nil, "x", -1)
                local hook, mask, count = debug.gethook()
                return hook, mask, count
                """.trimIndent(),
                "debug-sethook-nil-clear.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
        assertTrue(state.isNil(3))
    }

    @Test
    fun `package searchpath returns first readable template match`() {
        val root = Files.createTempDirectory("klua-searchpath")
        Files.createDirectories(root.resolve("alpha"))
        val module = root.resolve("alpha").resolve("beta.lua")
        Files.writeString(module, "return 42")
        val template = "${root.luaPath()}/?.lua;${root.luaPath()}/?/init.lua"

        val state = LuaState.create()
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return package.searchpath("alpha.beta", "$template", ".", "/")
                """.trimIndent(),
                "package-searchpath-found.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("${root}/alpha/beta.lua", state.toString(1))
    }

    @Test
    fun `package searchpath keeps names unchanged with empty separator`() {
        val root = Files.createTempDirectory("klua-searchpath-empty-separator")
        val module = root.resolve("alpha.beta.lua")
        Files.writeString(module, "return 42")
        val template = "${root.luaPath()}/?.lua"

        val state = LuaState.create()
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return package.searchpath("alpha.beta", "$template", "", "/")
                """.trimIndent(),
                "package-searchpath-empty-separator.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("${root}/alpha.beta.lua", state.toString(1))
    }

    @Test
    fun `package searchpath returns readable directory candidates like lua readable check`() {
        val root = Files.createTempDirectory("klua-searchpath-readable-directory")
        val moduleDirectory = root.resolve("alpha").resolve("beta.lua")
        Files.createDirectories(moduleDirectory)
        val fallback = root.resolve("alpha").resolve("beta").resolve("init.lua")
        Files.createDirectories(fallback.parent)
        Files.writeString(fallback, "return 42")
        val template = "${root.luaPath()}/?.lua;${root.luaPath()}/?/init.lua"

        val state = LuaState.create()
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return package.searchpath("alpha.beta", "$template", ".", "/")
                """.trimIndent(),
                "package-searchpath-readable-directory.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("${root}/alpha/beta.lua", state.toString(1))
    }

    @Test
    fun `package searchpath returns missing path diagnostics`() {
        val root = Files.createTempDirectory("klua-searchpath-missing")
        val template = "${root.luaPath()}/?.lua;${root.luaPath()}/?/init.lua"

        val state = LuaState.create()
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return package.searchpath("alpha.beta", "$template", ".", "/")
                """.trimIndent(),
                "package-searchpath-missing.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        val message = state.toString(2) ?: ""
        assertTrue(message.startsWith("no file '${root}/alpha/beta.lua'"))
        assertTrue(message.contains("no file '${root}/alpha/beta.lua'"))
        assertTrue(message.contains("no file '${root}/alpha/beta/init.lua'"))
    }

    @Test
    fun `package searchpath preserves empty path templates in diagnostics`() {
        val root = Files.createTempDirectory("klua-searchpath-empty-template")
        val template = "${root.luaPath()}/?.lua;;${root.luaPath()}/?/init.lua;"

        val state = LuaState.create()
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return package.searchpath("alpha.beta", "$template", ".", "/")
                """.trimIndent(),
                "package-searchpath-empty-template.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertEquals(
            "no file '${root}/alpha/beta.lua'\n\t" +
                "no file ''\n\t" +
                "no file '${root}/alpha/beta/init.lua'\n\t" +
                "no file ''",
            state.toString(2),
        )
    }

    @Test
    fun `package searchpath reports registered argument names`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okName, nameMessage = pcall(package.searchpath, false, "?.lua")
                local okPath, pathMessage = pcall(package.searchpath, "x", false)
                local okSeparator, separatorMessage = pcall(package.searchpath, "x", "?.lua", false)
                local okReplacement, replacementMessage = pcall(package.searchpath, "x", "?.lua", ".", false)
                return okName, nameMessage,
                    okPath, pathMessage,
                    okSeparator, separatorMessage,
                    okReplacement, replacementMessage
                """.trimIndent(),
                "package-searchpath-argument-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'searchpath' (string expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'searchpath' (string expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'searchpath' (string expected)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #4 to 'searchpath' (string expected)", state.toString(8))
    }

    @Test
    fun `package loadlib reports disabled native libraries`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local result, message, where = package.loadlib("missing.so", "luaopen_missing")
                local badPathOk, badPathMessage = pcall(package.loadlib, false, "luaopen_missing")
                local badInitOk, badInitMessage = pcall(package.loadlib, "missing.so", false)
                return result, message, where,
                    badPathOk, badPathMessage,
                    badInitOk, badInitMessage
                """.trimIndent(),
                "package-loadlib-disabled.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertEquals("dynamic libraries not enabled; check your Lua installation", state.toString(2))
        assertEquals("absent", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("bad argument #1 to 'loadlib' (string expected)", state.toString(5))
        assertFalse(state.toBoolean(6))
        assertEquals("bad argument #2 to 'loadlib' (string expected)", state.toString(7))
    }

    @Test
    fun `require loads preload modules once and caches their return value`() {
        val state = LuaState.create()
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local calls = 0
                package.preload.demo = function(name)
                    calls = calls + 1
                    return { name = name, calls = calls }
                end

                local first, firstData = require("demo")
                local second, secondData = require("demo")
                return first.name, first.calls, firstData,
                    first == second, package.loaded.demo == first, secondData
                """.trimIndent(),
                "require-preload-cache.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("demo", state.toString(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(":preload:", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.isNil(6))
    }

    @Test
    fun `require caches true when preload module returns nil`() {
        val state = LuaState.create()
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.preload.empty = function() end
                local loaded, extra = require("empty")
                local cached, cachedExtra = require("empty")
                return loaded, extra, package.loaded.empty, cached, cachedExtra == nil
                """.trimIndent(),
                "require-preload-nil.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals(":preload:", state.toString(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
    }

    @Test
    fun `require uses original loaded and preload tables after package field replacement`() {
        val state = LuaState.create()
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local originalLoaded = package.loaded
                local originalPreload = package.preload
                package.loaded = { swapped = "visible-cache" }
                package.preload = {
                    swapped = function()
                        return "visible-preload"
                    end,
                }
                originalPreload.swapped = function()
                    return "registry-preload"
                end

                local first, firstExtra = require("swapped")
                originalLoaded.swapped = "registry-cache"
                local second, secondExtra = require("swapped")
                return first, firstExtra, originalLoaded.swapped,
                    package.loaded.swapped, second, secondExtra == nil
                """.trimIndent(),
                "require-registry-tables.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("registry-preload", state.toString(1))
        assertEquals(":preload:", state.toString(2))
        assertEquals("registry-cache", state.toString(3))
        assertEquals("visible-cache", state.toString(4))
        assertEquals("registry-cache", state.toString(5))
        assertTrue(state.toBoolean(6))
    }

    @Test
    fun `require normalizes numeric module names and rejects non string names`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.preload["1"] = function(name)
                    return "loaded:" .. name
                end
                local loaded = require(1)
                local nilOk, nilMessage = pcall(require, nil)
                local booleanOk, booleanMessage = pcall(require, false)
                local tableOk, tableMessage = pcall(require, {})
                return loaded, package.loaded["1"],
                    nilOk, nilMessage,
                    booleanOk, booleanMessage,
                    tableOk, tableMessage
                """.trimIndent(),
                "require-name-types.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("loaded:1", state.toString(1))
        assertEquals("loaded:1", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'require' (string expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #1 to 'require' (string expected)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #1 to 'require' (string expected)", state.toString(8))
    }

    @Test
    fun `require reports missing preload modules`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(LuaStatus.OK, state.load("""return require("missing")""", "require-missing.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        val message = state.toString(-1) ?: ""
        assertTrue(message.startsWith("""[string "require-missing.lua"]:"""), message)
        assertTrue(message.contains("module 'missing' not found"))
        assertTrue(message.contains("no field package.preload['missing']"))
    }

    @Test
    fun `package searchers return source compatible miss diagnostics`() {
        val root = Files.createTempDirectory("klua-searcher-miss")

        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.path = "${root.luaPath()}/?.lua"
                local preloadMessage, preloadExtra = package.searchers[1]("missing")
                local luaMessage, luaExtra = package.searchers[2]("missing")
                return preloadMessage, preloadExtra, luaMessage, luaExtra
                """.trimIndent(),
                "package-searcher-miss.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("no field package.preload['missing']", state.toString(1))
        assertTrue(state.isNil(2))
        assertTrue(state.toString(3)?.startsWith("no file '") == true, state.toString(3))
        assertTrue(state.toString(3)?.contains("no file '${root}/missing.lua'") == true)
        assertTrue(state.isNil(4))
    }

    @Test
    fun `package loaders report source compatible package field type errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.path = false
                local pathOk, pathMessage = pcall(package.searchers[2], "missing")

                package.path = "?.lua"
                package.searchers = false
                local searchersOk, searchersMessage = pcall(require, "missing")

                return pathOk, pathMessage, searchersOk, searchersMessage
                """.trimIndent(),
                "package-loader-field-type-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("'package.path' must be a string", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("'package.searchers' must be a table", state.toString(4))
    }

    @Test
    fun `package c searcher reports cpath misses and dynamic loading failures`() {
        val root = Files.createTempDirectory("klua-c-searcher")
        val module = root.resolve("native.dll")
        Files.createDirectories(root.resolve("dotted"))
        Files.createDirectories(root.resolve("v1-compat"))
        Files.writeString(module, "not really a dynamic library")
        Files.writeString(root.resolve("dotted").resolve("native.dll"), "not really a dynamic library")
        Files.writeString(root.resolve("v1-compat").resolve("native.dll"), "not really a dynamic library")

        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.cpath = "${root.luaPath()}/?.dll"
                local missingMessage, missingExtra = package.searchers[3]("missing")
                local foundOk, foundMessage = pcall(package.searchers[3], "native")
                local requestedInit
                package.loadlib = function(_, init)
                    requestedInit = init
                    return nil, "recorded"
                end
                local dottedOk, dottedMessage = pcall(package.searchers[3], "dotted.native")
                local attempts = {}
                package.loadlib = function(_, init)
                    attempts[#attempts + 1] = init
                    if init == "luaopen_compat_native" then
                        return function()
                            return "hyphen-loaded"
                        end
                    end
                    return nil, "missing init", "init"
                end
                local hyphenLoader, hyphenFile = package.searchers[3]("v1-compat.native")
                package.cpath = false
                local cpathOk, cpathMessage = pcall(package.searchers[3], "native")
                return missingMessage, missingExtra, foundOk, foundMessage,
                    dottedOk, dottedMessage, requestedInit,
                    attempts[1], attempts[2], hyphenLoader(), hyphenFile,
                    cpathOk, cpathMessage
                """.trimIndent(),
                "package-c-searcher.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toString(1)?.contains("no file '${root}/missing.dll'") == true)
        assertTrue(state.isNil(2))
        assertFalse(state.toBoolean(3))
        assertTrue(state.toString(4)?.startsWith("""[string "stdlib-package.lua"]:""") == true, state.toString(4))
        assertTrue(state.toString(4)?.contains("error loading module 'native' from file") == true, state.toString(4))
        assertTrue(
            state.toString(4)?.endsWith("dynamic libraries not enabled; check your Lua installation") == true,
            state.toString(4),
        )
        assertFalse(state.toBoolean(5))
        assertTrue(state.toString(6)?.contains("recorded") == true, state.toString(6))
        assertEquals("luaopen_dotted_native", state.toString(7))
        assertEquals("luaopen_v1", state.toString(8))
        assertEquals("luaopen_compat_native", state.toString(9))
        assertEquals("hyphen-loaded", state.toString(10))
        assertTrue(state.toString(11)?.endsWith("native.dll") == true, state.toString(11))
        assertFalse(state.toBoolean(12))
        assertEquals("'package.cpath' must be a string", state.toString(13))
    }

    @Test
    fun `package c root searcher reports root misses and module file diagnostics`() {
        val root = Files.createTempDirectory("klua-c-root-searcher")
        val module = root.resolve("root.dll")
        Files.writeString(module, "not really a dynamic library")

        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.cpath = "${root.luaPath()}/?.dll"
                local plainMessage, plainExtra = package.searchers[4]("plain")
                local plainCount = select("#", package.searchers[4]("plain"))
                local missingMessage, missingExtra = package.searchers[4]("missing.child")
                local loadFailureOk, loadFailureMessage = pcall(package.searchers[4], "root.child")
                local initMissName
                package.loadlib = function(_, init)
                    initMissName = init
                    return nil, "missing init", "init"
                end
                local foundMessage, foundExtra = package.searchers[4]("root.child")
                local successName
                package.loadlib = function(_, init)
                    successName = init
                    return function(moduleName, filename)
                        return moduleName .. "@" .. filename
                    end
                end
                local loader, loadedFile = package.searchers[4]("root.child")
                local loadedValue = loader("root.child", loadedFile)
                package.cpath = false
                local cpathOk, cpathMessage = pcall(package.searchers[4], "root.child")
                return plainMessage, plainExtra, plainCount, missingMessage, missingExtra,
                    loadFailureOk, loadFailureMessage, initMissName, foundMessage, foundExtra,
                    successName, loadedValue, loadedFile, cpathOk, cpathMessage, package._moduleRoot
                """.trimIndent(),
                "package-c-root-searcher.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
        assertEquals(0L, state.toInteger(3))
        assertTrue(state.toString(4)?.contains("no file '${root}/missing.dll'") == true)
        assertTrue(state.isNil(5))
        assertFalse(state.toBoolean(6))
        assertTrue(state.toString(7)?.startsWith("""[string "stdlib-package.lua"]:""") == true, state.toString(7))
        assertTrue(state.toString(7)?.contains("error loading module 'root.child' from file") == true, state.toString(7))
        assertTrue(
            state.toString(7)?.endsWith("dynamic libraries not enabled; check your Lua installation") == true,
            state.toString(7),
        )
        assertEquals("luaopen_root_child", state.toString(8))
        assertEquals("no module 'root.child' in file '${root}/root.dll'", state.toString(9))
        assertTrue(state.isNil(10))
        assertEquals("luaopen_root_child", state.toString(11))
        assertEquals("root.child@${root}/root.dll", state.toString(12))
        assertEquals("${root}/root.dll", state.toString(13))
        assertFalse(state.toBoolean(14))
        assertEquals("'package.cpath' must be a string", state.toString(15))
        assertTrue(state.isNil(16))
    }

    @Test
    fun `require appends string searcher diagnostics and skips false searchers`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.searchers = {
                    function()
                        return "custom miss"
                    end,
                    function()
                        return false
                    end,
                    function()
                        return false, "\n\tignored extra miss"
                    end,
                }
                local ok, message = pcall(require, "custom")
                return ok, message, package._searcherResultType
                """.trimIndent(),
                "require-searcher-protocol.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("module 'custom' not found:\n\tcustom miss", state.toString(2))
        assertTrue(state.isNil(3))
    }

    @Test
    fun `require reads searchers without invoking searchers table metatable`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.searchers = setmetatable({}, {
                    __index = function(_, key)
                        if key == 1 then
                            return function()
                                return function()
                                    return "synthetic-loader"
                                end
                            end
                        end
                    end,
                })
                local ok, message = pcall(require, "synthetic")
                return ok, message, package.loaded.synthetic, package._rawget
                """.trimIndent(),
                "require-searchers-raw.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        val message = state.toString(2) ?: ""
        assertTrue(message.contains("module 'synthetic' not found"))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
    }

    @Test
    fun `require ignores secondary searcher diagnostics unless first result is string`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.searchers = {
                    function()
                        return nil, "\n\tignored nil extra"
                    end,
                    function()
                        return false, "\n\tignored false extra"
                    end,
                    function()
                        return "kept first"
                    end,
                }
                return pcall(require, "custom")
                """.trimIndent(),
                "require-searcher-secondary-diagnostics.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        val message = state.toString(2) ?: ""
        assertTrue(message.contains("module 'custom' not found"))
        assertTrue(message.contains("\n\tkept first"))
        assertFalse(message.contains("ignored nil extra"))
        assertFalse(message.contains("ignored false extra"))
    }

    @Test
    fun `require reports non table searchers`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.searchers = false
                return pcall(require, "custom")
                """.trimIndent(),
                "require-searchers-type.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("'package.searchers' must be a table", state.toString(2))
    }

    @Test
    fun `require reports non string package path`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.path = false
                local falseOk, falseMessage = pcall(require, "missing")
                package.path = nil
                local nilOk, nilMessage = pcall(require, "missing")
                return falseOk, falseMessage, nilOk, nilMessage
                """.trimIndent(),
                "require-package-path-type.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("'package.path' must be a string", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("'package.path' must be a string", state.toString(4))
    }

    @Test
    fun `require coerces numeric package paths like lua tostring path lookup`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.path = 123
                package.cpath = ""
                local pathOk, pathMessage = pcall(require, "missing")
                package.path = ""
                package.cpath = 456
                local cpathOk, cpathMessage = pcall(require, "missing")
                return pathOk, pathMessage, cpathOk, cpathMessage
                """.trimIndent(),
                "require-numeric-package-paths.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        val pathMessage = state.toString(2) ?: ""
        assertTrue(pathMessage.contains("module 'missing' not found"), pathMessage)
        assertTrue(pathMessage.contains("no file '123'"), pathMessage)
        assertFalse(pathMessage.contains("'package.path' must be a string"), pathMessage)

        assertFalse(state.toBoolean(3))
        val cpathMessage = state.toString(4) ?: ""
        assertTrue(cpathMessage.contains("module 'missing' not found"), cpathMessage)
        assertTrue(cpathMessage.contains("no file '456'"), cpathMessage)
        assertFalse(cpathMessage.contains("'package.cpath' must be a string"), cpathMessage)
    }

    @Test
    fun `require reports C searcher diagnostics and cpath type errors`() {
        val root = Files.createTempDirectory("klua-require-cpath")
        val nativeModule = root.resolve("native.so")
        Files.writeString(nativeModule, "")

        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)
        LuaStdlib.openPackage(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.path = ""
                package.cpath = "${root.luaPath()}/?.so"
                local missingOk, missingMessage = pcall(require, "missing")
                local rootOk, rootMessage = pcall(require, "native.child")
                local nativeOk, nativeMessage = pcall(require, "native")
                package.cpath = false
                local cpathOk, cpathMessage = pcall(require, "missing")
                return missingOk, missingMessage, rootOk, rootMessage, nativeOk, nativeMessage, cpathOk, cpathMessage
                """.trimIndent(),
                "require-cpath-diagnostics.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        val missingMessage = state.toString(2) ?: ""
        assertTrue(missingMessage.contains("module 'missing' not found"), missingMessage)
        assertTrue(missingMessage.contains("no file '${root}/missing.so'"), missingMessage)
        assertFalse(state.toBoolean(3))
        val rootMessage = state.toString(4) ?: ""
        assertTrue(rootMessage.contains("error loading module 'native.child' from file"), rootMessage)
        assertTrue(
            rootMessage.endsWith("dynamic libraries not enabled; check your Lua installation"),
            rootMessage,
        )
        assertFalse(state.toBoolean(5))
        assertTrue(state.toString(6)?.contains("error loading module 'native' from file") == true, state.toString(6))
        assertTrue(
            state.toString(6)?.endsWith("dynamic libraries not enabled; check your Lua installation") == true,
            state.toString(6),
        )
        assertFalse(state.toBoolean(7))
        assertEquals("'package.cpath' must be a string", state.toString(8))
    }

    @Test
    fun `require loads Lua files found on package path`() {
        val root = Files.createTempDirectory("klua-require-file")
        Files.createDirectories(root.resolve("alpha"))
        Files.writeString(
            root.resolve("alpha").resolve("beta.lua"),
            """
            local name, filename = ...
            return { name = name, filename = filename }
            """.trimIndent(),
        )

        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                package.path = "${root.luaPath()}/?.lua"
                local first, firstPath = require("alpha.beta")
                local second, secondPath = require("alpha.beta")
                return first.name, first.filename, firstPath,
                    first == second, package.loaded["alpha.beta"] == first, secondPath
                """.trimIndent(),
                "require-file.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("alpha.beta", state.toString(1))
        assertTrue(state.toString(2)?.endsWith("beta.lua") == true)
        assertTrue(state.toString(3)?.endsWith("beta.lua") == true)
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.isNil(6))
    }

    @Test
    fun `type and tostring report missing argument errors`() {
        val typeState = LuaState.create()
        LuaStdlib.openBase(typeState)

        assertEquals(LuaStatus.OK, typeState.load("""return type()""", "type-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, typeState.pcall(0, -1))

        assertIs<LuaRuntimeException>(typeState.getLastError())
        assertEquals("bad argument #1 to 'type' (value expected)", typeState.toString(-1))

        val tostringState = LuaState.create()
        LuaStdlib.openBase(tostringState)

        assertEquals(LuaStatus.OK, tostringState.load("""return tostring()""", "tostring-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, tostringState.pcall(0, -1))

        assertIs<LuaRuntimeException>(tostringState.getLastError())
        assertEquals("bad argument #1 to 'tostring' (value expected)", tostringState.toString(-1))
    }

    @Test
    fun `tonumber converts numbers and decimal strings`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return tonumber("42"),
                    tonumber("3.5"),
                    tonumber("0x1.8p1"),
                    tonumber("bad"),
                    tonumber("NaN"),
                    tonumber("Infinity"),
                    tonumber("-Infinity"),
                    tonumber(7)
                """.trimIndent(),
                "tonumber.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(3.5, state.toNumber(2))
        assertEquals(3.0, state.toNumber(3))
        assertTrue(state.isNil(4))
        assertTrue(state.isNil(5))
        assertTrue(state.isNil(6))
        assertTrue(state.isNil(7))
        assertEquals(7L, state.toInteger(8))
    }

    @Test
    fun `tonumber rejects non ascii surrounding spaces`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local emSpace = string.char(226, 128, 131)
                return tonumber(emSpace .. "10"), tonumber("10" .. emSpace)
                """.trimIndent(),
                "tonumber-ascii-spaces.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
    }

    @Test
    fun `tonumber converts hexadecimal integer strings`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return tonumber("0x10"),
                    tonumber("+0Xff"),
                    tonumber("-0x10"),
                    tonumber("-0x8000000000000000"),
                    tonumber("0xFFFFFFFFFFFFFFFF"),
                    tonumber("0x8000000000000000"),
                    tonumber("0x10000000000000000"),
                    tonumber("-0xFFFFFFFFFFFFFFFF"),
                    tonumber("0x"),
                    tonumber("0x1p2")
                """.trimIndent(),
                "tonumber-hex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(16L, state.toInteger(1))
        assertEquals(255L, state.toInteger(2))
        assertEquals(-16L, state.toInteger(3))
        assertEquals(Long.MIN_VALUE, state.toInteger(4))
        assertEquals(-1L, state.toInteger(5))
        assertEquals(Long.MIN_VALUE, state.toInteger(6))
        assertEquals(0L, state.toInteger(7))
        assertEquals(1L, state.toInteger(8))
        assertTrue(state.isNil(9))
        assertEquals(4.0, state.toNumber(10))
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
    fun `tonumber explicit base wraps unsigned 64 bit values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return tonumber("FFFFFFFFFFFFFFFF", 16),
                    tonumber("8000000000000000", 16),
                    tonumber("10000000000000000", 16),
                    tonumber("18446744073709551615", 10),
                    tonumber("-18446744073709551615", 10),
                    tonumber("  +ff  ", 16)
                """.trimIndent(),
                "tonumber-base-wrap.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(-1L, state.toInteger(1))
        assertEquals(Long.MIN_VALUE, state.toInteger(2))
        assertEquals(0L, state.toInteger(3))
        assertEquals(-1L, state.toInteger(4))
        assertEquals(1L, state.toInteger(5))
        assertEquals(255L, state.toInteger(6))
    }

    @Test
    fun `tonumber explicit base rejects non ascii digits`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local arabicIndicDigit = "\217\161"
                local fullwidthDigit = "\239\188\145"
                local fullwidthLetter = "\239\188\166"
                return tonumber(arabicIndicDigit, 10),
                    tonumber(fullwidthDigit, 10),
                    tonumber(fullwidthLetter, 16)
                """.trimIndent(),
                "tonumber-base-ascii.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
        assertTrue(state.isNil(3))
    }

    @Test
    fun `tonumber explicit base rejects non ascii surrounding spaces`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local emSpace = string.char(226, 128, 131)
                return tonumber(emSpace .. "10", 10), tonumber("10" .. emSpace, 10)
                """.trimIndent(),
                "tonumber-base-ascii-spaces.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
    }

    @Test
    fun `tonumber explicit base requires string input before nil conversion`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local plainNil = tonumber(nil)
                local nilBase = tonumber(nil, nil)
                local ok, message = pcall(tonumber, nil, 10)
                return plainNil, nilBase, ok, message
                """.trimIndent(),
                "tonumber-nil-base.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'tonumber' (string expected)", state.toString(4))
    }

    @Test
    fun `tonumber reports missing and base conversion argument errors`() {
        val missingState = LuaState.create()
        LuaStdlib.openBase(missingState)

        assertEquals(LuaStatus.OK, missingState.load("""return tonumber()""", "tonumber-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, missingState.pcall(0, -1))

        assertIs<LuaRuntimeException>(missingState.getLastError())
        assertEquals("bad argument #1 to 'tonumber' (value expected)", missingState.toString(-1))

        val baseState = LuaState.create()
        LuaStdlib.openBase(baseState)

        assertEquals(LuaStatus.OK, baseState.load("""return tonumber(10, 16)""", "tonumber-base-string-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, baseState.pcall(0, -1))

        assertIs<LuaRuntimeException>(baseState.getLastError())
        assertEquals("bad argument #1 to 'tonumber' (string expected)", baseState.toString(-1))

        val baseRangeOrderState = LuaState.create()
        LuaStdlib.openBase(baseRangeOrderState)

        assertEquals(
            LuaStatus.OK,
            baseRangeOrderState.load("""return tonumber(10, 1)""", "tonumber-base-range-order-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, baseRangeOrderState.pcall(0, -1))

        assertIs<LuaRuntimeException>(baseRangeOrderState.getLastError())
        assertEquals("bad argument #1 to 'tonumber' (string expected)", baseRangeOrderState.toString(-1))
    }

    @Test
    fun `tonumber explicit base validates value before base range`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return tonumber(10, 1)""", "tonumber-base-order-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'tonumber' (string expected)", state.toString(-1))
    }

    @Test
    fun `tonumber explicit base rejects non integral numeric bases before value type`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okFloat, floatMessage = pcall(tonumber, 10, 1.5)
                local okString, stringMessage = pcall(tonumber, "10", "1.5")
                return okFloat, floatMessage, okString, stringMessage
                """.trimIndent(),
                "tonumber-fractional-base.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'tonumber' (number has no integer representation)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'tonumber' (number has no integer representation)", state.toString(4))
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
    fun `tonumber rejects non integer numeric base`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return tonumber("10", 1.5)""", "tonumber-non-integer-base.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'tonumber' (number has no integer representation)", state.toString(-1))
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
        assertEquals("[string \"assert-false.lua\"]:1: nope", state.toString(-1))
    }

    @Test
    fun `assert prefixes default string errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return assert(false)""", "assert-default.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("[string \"assert-default.lua\"]:1: assertion failed!", state.toString(-1))
    }

    @Test
    fun `assert preserves lua error objects`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local marker = {name = "marker"}
                local ok, err = pcall(assert, false, marker)
                local defaultOk, defaultErr = pcall(assert, false)
                local missingOk, missingErr = pcall(assert)
                local nilOk, nilErr = pcall(assert, false, nil)
                return ok, err == marker, err.name,
                    defaultOk, defaultErr,
                    missingOk, missingErr,
                    nilOk, nilErr
                """.trimIndent(),
                "assert-error-object.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("marker", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("assertion failed!", state.toString(5))
        assertFalse(state.toBoolean(6))
        assertEquals("bad argument #1 to 'assert' (value expected)", state.toString(7))
        assertFalse(state.toBoolean(8))
        assertTrue(state.isNil(9))
    }

    @Test
    fun `error raises runtime error with message`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return error("boom", nil)""", "error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("[string \"error.lua\"]:1: boom", state.toString(-1))
    }

    @Test
    fun `error formats file chunk source names`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return error("boom")""", "@/tmp/error-file.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("/tmp/error-file.lua:1: boom", state.toString(-1))
    }

    @Test
    fun `error level controls string message locations`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok0, message0 = pcall(function()
                    error("boom", 0)
                end)
                local ok2, message2 = pcall(function()
                    local function leaf()
                        error("boom", 2)
                    end
                    leaf()
                end)
                return ok0, message0, ok2, message2
                """.trimIndent(),
                "error-levels.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("boom", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("[string \"error-levels.lua\"]:8: boom", state.toString(4))
    }

    @Test
    fun `error preserves lua error objects`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local marker = {name = "marker"}
                local ok, err = pcall(error, marker)
                local nilOk, nilErr = pcall(error, nil)
                local missingOk, missingErr = pcall(error)
                local handlerSawMarker = false
                local handled = xpcall(function()
                    error(marker)
                end, function(value)
                    handlerSawMarker = value == marker
                    return value.name
                end)
                return ok, err == marker, err.name, nilOk, nilErr, missingOk, missingErr, handled, handlerSawMarker
                """.trimIndent(),
                "error-object.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("marker", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertTrue(state.isNil(5))
        assertFalse(state.toBoolean(6))
        assertTrue(state.isNil(7))
        assertFalse(state.toBoolean(8))
        assertTrue(state.toBoolean(9))
    }

    @Test
    fun `error reports non numeric level errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okBoolean, booleanMessage = pcall(error, "boom", false)
                local okFloat, floatMessage = pcall(error, "boom", 1.5)
                local okString, stringMessage = pcall(error, "boom", "1.5")
                return okBoolean, booleanMessage, okFloat, floatMessage, okString, stringMessage
                """.trimIndent(),
                "error-level-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'error' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'error' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'error' (number has no integer representation)", state.toString(6))
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
        assertEquals("[string \"pcall.lua\"]:5: boom", state.toString(5))
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
    fun `pcall invokes callable table values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local callable = setmetatable({prefix = "ok"}, {
                    __call = function(self, value)
                        return self.prefix .. value
                    end,
                })
                local ok, value = pcall(callable, "!")
                local failing = setmetatable({}, {
                    __call = function()
                        error("boom")
                    end,
                })
                local failed, message = pcall(failing)
                return ok, value, failed, message
                """.trimIndent(),
                "pcall-callable-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("ok!", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertTrue(state.toString(4)?.startsWith("""[string "pcall-callable-table.lua"]:""") == true, state.toString(4))
        assertTrue(state.toString(4)?.endsWith(": boom") == true, state.toString(4))
    }

    @Test
    fun `pcall invokes non-table call metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        val hostUserData = Any()
        state.register("hostUserData") { LuaReturn.of(hostUserData) }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local primitiveCalls = {}
                debug.setmetatable(false, {
                    __call = function(self, value)
                        primitiveCalls[#primitiveCalls + 1] = { self, value }
                        return "boolean-call"
                    end,
                })

                local userdataCalls = {}
                local userdata = hostUserData()
                debug.setmetatable(userdata, {
                    __call = function(self, value)
                        userdataCalls[#userdataCalls + 1] = { self == userdata, value }
                        return "userdata-call"
                    end,
                })

                local directPrimitive = false("x")
                local protectedPrimitiveOk, protectedPrimitive = pcall(false, "y")
                local directUserdata = userdata("z")
                local protectedUserdataOk, protectedUserdata = pcall(userdata, "w")

                debug.setmetatable(false, nil)
                debug.setmetatable(userdata, nil)

                local missingOk, missingMessage = pcall(false, "again")

                return directPrimitive, primitiveCalls[1][1], primitiveCalls[1][2],
                    protectedPrimitiveOk, protectedPrimitive, primitiveCalls[2][1], primitiveCalls[2][2],
                    directUserdata, userdataCalls[1][1], userdataCalls[1][2],
                    protectedUserdataOk, protectedUserdata, userdataCalls[2][1], userdataCalls[2][2],
                    missingOk, missingMessage
                """.trimIndent(),
                "pcall-non-table-call-metamethods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("boolean-call", state.toString(1))
        assertFalse(state.toBoolean(2))
        assertEquals("x", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertEquals("boolean-call", state.toString(5))
        assertFalse(state.toBoolean(6))
        assertEquals("y", state.toString(7))
        assertEquals("userdata-call", state.toString(8))
        assertTrue(state.toBoolean(9))
        assertEquals("z", state.toString(10))
        assertTrue(state.toBoolean(11))
        assertEquals("userdata-call", state.toString(12))
        assertTrue(state.toBoolean(13))
        assertEquals("w", state.toString(14))
        assertFalse(state.toBoolean(15))
        assertEquals("attempt to call a boolean value", state.toString(16))
    }

    @Test
    fun `state pcall invokes callable table values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return setmetatable({prefix = "ok"}, {
                    __call = function(self, value)
                        return self.prefix .. value
                    end,
                })
                """.trimIndent(),
                "state-pcall-callable-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        state.pushString("!")
        assertEquals(LuaStatus.OK, state.pcall(1, -1), state.toString(-1))
        assertEquals("ok!", state.toString(1))

        state.setTop(0)
        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return setmetatable({}, {
                    __call = function()
                        error("boom")
                    end,
                })
                """.trimIndent(),
                "state-pcall-callable-table-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))
        assertTrue(
            state.toString(-1)?.startsWith("""[string "state-pcall-callable-table-error.lua"]:""") == true,
            state.toString(-1),
        )
        assertTrue(state.toString(-1)?.endsWith(": boom") == true, state.toString(-1))
    }

    @Test
    fun `pcall can yield and resume inside coroutine`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    local ok, returned = pcall(function()
                        local first = coroutine.yield("pause")
                        local second = coroutine.yield(first .. " again")
                        return second
                    end)
                    return ok, returned
                end)
                local firstOk, yielded = coroutine.resume(co)
                local statusAfterYield = coroutine.status(co)
                local secondOk, yieldedAgain = coroutine.resume(co, "middle")
                local statusAfterSecondYield = coroutine.status(co)
                local thirdOk, protectedOk, returned = coroutine.resume(co, "done")
                return firstOk, yielded, statusAfterYield, secondOk, yieldedAgain, statusAfterSecondYield,
                    thirdOk, protectedOk, returned, coroutine.status(co)
                """.trimIndent(),
                "pcall-yield.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(true, state.toBoolean(1), "first resume failed: ${state.toString(2)}")
        assertEquals("pause", state.toString(2))
        assertEquals("suspended", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertEquals("middle again", state.toString(5))
        assertEquals("suspended", state.toString(6))
        assertTrue(state.toBoolean(7))
        assertTrue(state.toBoolean(8))
        assertEquals("done", state.toString(9))
        assertEquals("dead", state.toString(10))
    }

    @Test
    fun `pcall protects errors after coroutine yield`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    local ok, message = pcall(function()
                        coroutine.yield("pause")
                        error("boom")
                    end)
                    return ok, message
                end)
                local firstOk, yielded = coroutine.resume(co)
                local statusAfterYield = coroutine.status(co)
                local secondOk, protectedOk, message = coroutine.resume(co)
                return firstOk, yielded, statusAfterYield, secondOk, protectedOk, message, coroutine.status(co)
                """.trimIndent(),
                "pcall-yield-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("pause", state.toString(2))
        assertEquals("suspended", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertFalse(state.toBoolean(5))
        assertEquals("[string \"pcall-yield-error.lua\"]:4: boom", state.toString(6))
        assertEquals("dead", state.toString(7))
    }

    @Test
    fun `pcall protects non callable arguments`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numberOk, numberMessage = pcall(42)
                local nilOk, nilMessage = pcall(nil)
                return numberOk, numberMessage, nilOk, nilMessage
                """.trimIndent(),
                "pcall-non-callable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to call a number value", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("attempt to call a nil value", state.toString(4))
    }

    @Test
    fun `pcall reports missing function argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return pcall()""", "pcall-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'pcall' (value expected)", state.toString(-1))
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
        assertEquals("handled:[string \"xpcall.lua\"]:7: boom", state.toString(4))
    }

    @Test
    fun `xpcall uses only first error handler result`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, first, second, third = xpcall(function()
                    error("boom")
                end, function(message)
                    return "handled", "extra", nil
                end)
                return ok, first, second, third
                """.trimIndent(),
                "xpcall-handler-results.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("handled", state.toString(2))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
    }

    @Test
    fun `xpcall reports error in error handling when handler fails`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = xpcall(function()
                    error("body")
                end, function(original)
                    error("handler")
                end)
                return ok, message
                """.trimIndent(),
                "xpcall-handler-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("error in error handling", state.toString(2))
    }

    @Test
    fun `xpcall invokes callable table values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local callable = setmetatable({prefix = "ok"}, {
                    __call = function(self, value)
                        return self.prefix .. value
                    end,
                })
                local ok, value = xpcall(callable, function(message)
                    return "handled:" .. message
                end, "!")
                local failing = setmetatable({}, {
                    __call = function()
                        error("boom")
                    end,
                })
                local failed, handled = xpcall(failing, function(message)
                    return "handled:" .. message
                end)
                return ok, value, failed, handled
                """.trimIndent(),
                "xpcall-callable-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("ok!", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertTrue(state.toString(4)?.startsWith("""handled:[string "xpcall-callable-table.lua"]:""") == true, state.toString(4))
        assertTrue(state.toString(4)?.endsWith(": boom") == true, state.toString(4))
    }

    @Test
    fun `xpcall reports error handler failures`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return xpcall(function()
                    error("boom")
                end, function()
                    error("handler boom")
                end)
                """.trimIndent(),
                "xpcall-handler-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("error in error handling", state.toString(2))
    }

    @Test
    fun `xpcall returns exactly one error handler result`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local multiCount = select("#", xpcall(function()
                    error("boom")
                end, function(message)
                    return "first", "second"
                end))
                local multiOk, first, second = xpcall(function()
                    error("boom")
                end, function(message)
                    return "first", "second"
                end)
                local nilCount = select("#", xpcall(function()
                    error("boom")
                end, function(message)
                end))
                local nilOk, nilError = xpcall(function()
                    error("boom")
                end, function(message)
                end)
                return multiCount, multiOk, first, second, nilCount, nilOk, nilError
                """.trimIndent(),
                "xpcall-handler-result-count.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(2L, state.toInteger(1))
        assertFalse(state.toBoolean(2))
        assertEquals("first", state.toString(3))
        assertTrue(state.isNil(4))
        assertEquals(2L, state.toInteger(5))
        assertFalse(state.toBoolean(6))
        assertTrue(state.isNil(7))
    }

    @Test
    fun `xpcall can yield and resume inside coroutine`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    local ok, returned = xpcall(function()
                        return coroutine.yield("xpause")
                    end, function(message)
                        return "handled:" .. message
                    end)
                    return ok, returned
                end)
                local firstOk, yielded = coroutine.resume(co)
                local statusAfterYield = coroutine.status(co)
                local secondOk, protectedOk, returned = coroutine.resume(co, "xdone")
                return firstOk, yielded, statusAfterYield, secondOk, protectedOk, returned, coroutine.status(co)
                """.trimIndent(),
                "xpcall-yield.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(true, state.toBoolean(1), "first resume failed: ${state.toString(2)}")
        assertEquals("xpause", state.toString(2))
        assertEquals("suspended", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertEquals("xdone", state.toString(6))
        assertEquals("dead", state.toString(7))
    }

    @Test
    fun `xpcall invokes error handler after coroutine yield`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    local ok, message = xpcall(function()
                        coroutine.yield("xpause")
                        error("boom")
                    end, function(message)
                        return "handled:" .. message
                    end)
                    return ok, message
                end)
                local firstOk, yielded = coroutine.resume(co)
                local statusAfterYield = coroutine.status(co)
                local secondOk, protectedOk, message = coroutine.resume(co)
                return firstOk, yielded, statusAfterYield, secondOk, protectedOk, message, coroutine.status(co)
                """.trimIndent(),
                "xpcall-yield-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("xpause", state.toString(2))
        assertEquals("suspended", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertFalse(state.toBoolean(5))
        assertEquals("handled:[string \"xpcall-yield-error.lua\"]:4: boom", state.toString(6))
        assertEquals("dead", state.toString(7))
    }

    @Test
    fun `xpcall error handler can yield and resume`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    local ok, handled = xpcall(function()
                        error("boom")
                    end, function(message)
                        local first = coroutine.yield("handling:" .. message)
                        local second = coroutine.yield(first .. " again")
                        return second
                    end)
                    return ok, handled
                end)
                local firstOk, yielded = coroutine.resume(co)
                local statusAfterYield = coroutine.status(co)
                local secondOk, yieldedAgain = coroutine.resume(co, "middle")
                local statusAfterSecondYield = coroutine.status(co)
                local thirdOk, protectedOk, handled = coroutine.resume(co, "done")
                return firstOk, yielded, statusAfterYield, secondOk, yieldedAgain, statusAfterSecondYield,
                    thirdOk, protectedOk, handled, coroutine.status(co)
                """.trimIndent(),
                "xpcall-handler-yield.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(true, state.toBoolean(1), "first resume failed: ${state.toString(2)}")
        assertEquals("handling:[string \"xpcall-handler-yield.lua\"]:3: boom", state.toString(2))
        assertEquals("suspended", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertEquals("middle again", state.toString(5))
        assertEquals("suspended", state.toString(6))
        assertTrue(state.toBoolean(7))
        assertFalse(state.toBoolean(8))
        assertEquals("done", state.toString(9))
        assertEquals("dead", state.toString(10))
    }

    @Test
    fun `xpcall reports error handler failures after yield`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    return xpcall(function()
                        error("boom")
                    end, function()
                        coroutine.yield("handling")
                        error("handler boom")
                    end)
                end)
                local firstOk, yielded = coroutine.resume(co)
                local secondOk, protectedOk, message = coroutine.resume(co)
                return firstOk, yielded, secondOk, protectedOk, message, coroutine.status(co)
                """.trimIndent(),
                "xpcall-handler-yield-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("handling", state.toString(2))
        assertTrue(state.toBoolean(3))
        assertFalse(state.toBoolean(4))
        assertEquals("error in error handling", state.toString(5))
        assertEquals("dead", state.toString(6))
    }

    @Test
    fun `xpcall protects non callable arguments with handler`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return xpcall(42, function(message)
                    return message
                end)
                """.trimIndent(),
                "xpcall-non-callable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to call a number value", state.toString(2))
    }

    @Test
    fun `xpcall protects nil function arguments with handler`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return xpcall(nil, function(message) return message end)""", "xpcall-nil-function.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to call a nil value", state.toString(2))
    }

    @Test
    fun `xpcall reports missing handler argument errors`() {
        val missingState = LuaState.create()
        LuaStdlib.openBase(missingState)

        assertEquals(LuaStatus.OK, missingState.load("""return xpcall()""", "xpcall-missing-handler.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, missingState.pcall(0, -1))

        assertIs<LuaRuntimeException>(missingState.getLastError())
        assertEquals("bad argument #2 to 'xpcall' (function expected)", missingState.toString(-1))

        val functionState = LuaState.create()
        LuaStdlib.openBase(functionState)

        assertEquals(
            LuaStatus.OK,
            functionState.load("""return xpcall(function() return "ok" end)""", "xpcall-function-missing-handler.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, functionState.pcall(0, -1))

        assertIs<LuaRuntimeException>(functionState.getLastError())
        assertEquals("bad argument #2 to 'xpcall' (function expected)", functionState.toString(-1))
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
                local prefixedCount = select("#extra", "a", "b")
                local second, third = select(2, "a", "b", "c")
                local last = select(-1, "a", "b", "c")
                return count, prefixedCount, second, third, last
                """.trimIndent(),
                "select.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals("b", state.toString(3))
        assertEquals("c", state.toString(4))
        assertEquals("c", state.toString(5))
    }

    @Test
    fun `select follows luaB_select string integer and boundary rules`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local fromString = select("2", "a", "b", "c")
                local count = select("#", nil, false, "x")
                local okNegative, negativeMessage = pcall(select, -3, "a")
                local okMissing, missingMessage = pcall(select)
                local okNil, nilMessage = pcall(select, nil, "a")
                return fromString, count, okNegative, negativeMessage, okMissing, missingMessage, okNil, nilMessage
                """.trimIndent(),
                "select-luab-select-edges.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("b", state.toString(1))
        assertEquals(3L, state.toInteger(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'select' (index out of range)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #1 to 'select' (number expected)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #1 to 'select' (number expected)", state.toString(8))
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
    fun `select returns no values for positive indexes past the end`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return select("#", select(4, "a", "b", "c"))""", "select-empty.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0L, state.toInteger(1))
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
    fun `select rejects non hash string indexes`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local emptyOk, emptyMessage = pcall(select, "", "a")
                local plainOk, plainMessage = pcall(select, "extra", "a")
                return emptyOk, emptyMessage, plainOk, plainMessage
                """.trimIndent(),
                "select-string-index.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'select' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'select' (number expected)", state.toString(4))
    }

    @Test
    fun `select hash branch ignores tostring metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local value = setmetatable({}, {
                    __tostring = function()
                        return "#not-a-string"
                    end,
                })
                local ok, message = pcall(select, value, "a", "b")
                return ok, message
                """.trimIndent(),
                "select-hash-tostring-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'select' (number expected)", state.toString(2))
    }

    @Test
    fun `select rejects non integral numeric indexes`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okFloat, floatMessage = pcall(select, 1.5, "a", "b")
                local okString, stringMessage = pcall(select, "1.5", "a", "b")
                return okFloat, floatMessage, okString, stringMessage
                """.trimIndent(),
                "select-fractional-index.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("bad argument #1 to 'select' (number has no integer representation)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'select' (number has no integer representation)", state.toString(4))
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
    fun `print uses primitive tostring metamethods`() {
        val state = LuaState.create()
        val output = mutableListOf<String>()
        LuaStdlib.openLibs(state, Consumer { line -> output += line })

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(1, {
                    __tostring = function()
                        return "number-value"
                    end,
                })
                print(2)
                """.trimIndent(),
                "print-primitive-tostring.lua",
            ),
        )
        val status = state.pcall(0, -1)
        assertEquals(LuaStatus.OK, status, state.toString(-1))

        assertEquals(listOf("number-value"), output)
        assertEquals(0, state.getTop())
    }

    @Test
    fun `warn writes configured output when enabled`() {
        val state = LuaState.create()
        val output = mutableListOf<String>()
        LuaStdlib.openBase(state, Consumer { line -> output += line })

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                warn("ignored")
                warn("@on")
                warn("count ", 42)
                warn("@unknown")
                warn("still on")
                warn("@off")
                warn("ignored too")
                """.trimIndent(),
                "warn.lua",
            ),
        )
        val status = state.pcall(0, -1)
        assertEquals(LuaStatus.OK, status, state.toString(-1))

        assertEquals(listOf("Lua warning: count 42", "Lua warning: still on"), output)
        assertEquals(0, state.getTop())
    }

    @Test
    fun `warn handles multi part control messages like Lua warning API`() {
        val state = LuaState.create()
        val output = mutableListOf<String>()
        LuaStdlib.openBase(state, Consumer { line -> output += line })

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                warn("@on")
                warn("@off", "XXX", "@off")
                warn("@off")
                warn("@on", "YYY", "@on")
                warn("@off")
                warn("@on")
                warn("", "@on")
                warn("@on")
                warn("Z", "Z", "Z")
                """.trimIndent(),
                "warn-multipart-controls.lua",
            ),
        )
        val status = state.pcall(0, -1)
        assertEquals(LuaStatus.OK, status, state.toString(-1))

        assertEquals(
            listOf(
                "Lua warning: @offXXX@off",
                "Lua warning: @on",
                "Lua warning: ZZZ",
            ),
            output,
        )
        assertEquals(0, state.getTop())
    }

    @Test
    fun `warn reports missing and non string argument errors`() {
        val missingState = LuaState.create()
        LuaStdlib.openBase(missingState)

        assertEquals(LuaStatus.OK, missingState.load("""warn()""", "warn-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, missingState.pcall(0, -1))

        assertIs<LuaRuntimeException>(missingState.getLastError())
        assertEquals("bad argument #1 to 'warn' (string expected)", missingState.toString(-1))

        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""warn("@on"); warn({})""", "warn-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'warn' (string expected)", state.toString(-1))

        val partialState = LuaState.create()
        val output = mutableListOf<String>()
        LuaStdlib.openBase(partialState, Consumer { line -> output += line })

        assertEquals(LuaStatus.OK, partialState.load("""warn("@on"); warn("prefix ", {})""", "warn-partial-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, partialState.pcall(0, -1))

        assertIs<LuaRuntimeException>(partialState.getLastError())
        assertEquals("bad argument #2 to 'warn' (string expected)", partialState.toString(-1))
        assertEquals(emptyList(), output)
    }

    @Test
    fun `collectgarbage controls and reports collector state`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local running = collectgarbage("isrunning")
                local defaultCount, defaultResult = select("#", collectgarbage()), collectgarbage()
                local collectCount, collectResult = select("#", collectgarbage("collect")), collectgarbage("collect")
                local stopCount, stopResult = select("#", collectgarbage("stop")), collectgarbage("stop")
                local stopped = collectgarbage("isrunning")
                local restartCount, restartResult = select("#", collectgarbage("restart")), collectgarbage("restart")
                local restarted = collectgarbage("isrunning")
                local countType = type(collectgarbage("count"))
                local stepDone = collectgarbage("step")
                local previousMode = collectgarbage("generational")
                local restoredMode = collectgarbage("incremental")
                return running,
                    defaultCount, defaultResult,
                    collectCount, collectResult,
                    stopCount, stopResult,
                    stopped,
                    restartCount, restartResult,
                    restarted, countType, stepDone, previousMode, restoredMode
                """.trimIndent(),
                "collectgarbage.lua",
            ),
        )
        val status = state.pcall(0, -1)
        assertEquals(LuaStatus.OK, status, state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(0L, state.toInteger(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals(0L, state.toInteger(5))
        assertEquals(1L, state.toInteger(6))
        assertEquals(0L, state.toInteger(7))
        assertFalse(state.toBoolean(8))
        assertEquals(1L, state.toInteger(9))
        assertEquals(0L, state.toInteger(10))
        assertTrue(state.toBoolean(11))
        assertEquals("number", state.toString(12))
        assertFalse(state.toBoolean(13))
        assertEquals("incremental", state.toString(14))
        assertEquals("generational", state.toString(15))
    }

    @Test
    fun `collectgarbage reports invalid option errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return collectgarbage("bad")""", "collectgarbage-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'collectgarbage' (invalid option 'bad')", state.toString(-1))
    }

    @Test
    fun `collectgarbage reports non string option errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return collectgarbage(true)""", "collectgarbage-option-type-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'collectgarbage' (string expected)", state.toString(-1))
    }

    @Test
    fun `collectgarbage step validates optional size argument`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local nilStep = collectgarbage("step", nil)
                local sizedStep = collectgarbage("step", 0)
                local countType = type(collectgarbage("count", "ignored"))
                local ok, message = pcall(collectgarbage, "step", "not-size")
                local fractionalOk, fractionalMessage = pcall(collectgarbage, "step", 1.5)
                return nilStep, sizedStep, countType, ok, message, fractionalOk, fractionalMessage
                """.trimIndent(),
                "collectgarbage-step-size.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertFalse(state.toBoolean(2))
        assertEquals("number", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("bad argument #2 to 'collectgarbage' (number expected)", state.toString(5))
        assertFalse(state.toBoolean(6))
        assertEquals("bad argument #2 to 'collectgarbage' (number has no integer representation)", state.toString(7))
    }

    @Test
    fun `collectgarbage param queries and updates collector parameters`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local minormul = collectgarbage("param", "minormul")
                local majorminor = collectgarbage("param", "majorminor")
                local minormajor = collectgarbage("param", "minormajor")
                local pause = collectgarbage("param", "pause")
                local previous = collectgarbage("param", "pause", 300)
                local updated = collectgarbage("param", "pause")
                local query = collectgarbage("param", "pause", nil)
                local unchanged = collectgarbage("param", "pause", -1)
                local stepmul = collectgarbage("param", "stepmul")
                local stepsize = collectgarbage("param", "stepsize")
                local previousStepSize = collectgarbage("param", "stepsize", 8192)
                local updatedStepSize = collectgarbage("param", "stepsize")
                return minormul, majorminor, minormajor, pause, previous, updated, query, unchanged,
                    stepmul, stepsize, previousStepSize, updatedStepSize
                """.trimIndent(),
                "collectgarbage-param.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(20L, state.toInteger(1))
        assertEquals(50L, state.toInteger(2))
        assertEquals(68L, state.toInteger(3))
        assertEquals(250L, state.toInteger(4))
        assertEquals(250L, state.toInteger(5))
        assertEquals(300L, state.toInteger(6))
        assertEquals(300L, state.toInteger(7))
        assertEquals(300L, state.toInteger(8))
        assertEquals(200L, state.toInteger(9))
        assertEquals(9600L, state.toInteger(10))
        assertEquals(9600L, state.toInteger(11))
        assertEquals(8192L, state.toInteger(12))
    }

    @Test
    fun `collectgarbage param reports parameter argument errors`() {
        val invalidState = LuaState.create()
        LuaStdlib.openBase(invalidState)

        assertEquals(
            LuaStatus.OK,
            invalidState.load("""return collectgarbage("param", "bad")""", "collectgarbage-param-invalid.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, invalidState.pcall(0, -1))

        assertIs<LuaRuntimeException>(invalidState.getLastError())
        assertEquals("bad argument #2 to 'collectgarbage' (invalid option 'bad')", invalidState.toString(-1))

        val missingState = LuaState.create()
        LuaStdlib.openBase(missingState)

        assertEquals(
            LuaStatus.OK,
            missingState.load("""return collectgarbage("param")""", "collectgarbage-param-missing.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, missingState.pcall(0, -1))

        assertIs<LuaRuntimeException>(missingState.getLastError())
        assertEquals("bad argument #2 to 'collectgarbage' (string expected)", missingState.toString(-1))

        val valueState = LuaState.create()
        LuaStdlib.openBase(valueState)

        assertEquals(
            LuaStatus.OK,
            valueState.load(
                """
                local okBoolean, booleanMessage = pcall(collectgarbage, "param", "pause", false)
                local okFraction, fractionMessage = pcall(collectgarbage, "param", "pause", 1.5)
                return okBoolean, booleanMessage, okFraction, fractionMessage
                """.trimIndent(),
                "collectgarbage-param-value.lua",
            ),
        )
        assertEquals(LuaStatus.OK, valueState.pcall(0, -1), valueState.toString(-1))

        assertFalse(valueState.toBoolean(1))
        assertEquals("bad argument #3 to 'collectgarbage' (number expected)", valueState.toString(2))
        assertFalse(valueState.toBoolean(3))
        assertEquals("bad argument #3 to 'collectgarbage' (number has no integer representation)", valueState.toString(4))
    }

    @Test
    fun `load compiles string chunks with shared globals and arguments`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                value = 40
                local chunk = load("local add = ...; value = value + add; return value", "loaded.lua")
                return chunk(2), value
                """.trimIndent(),
                "load.lua",
            ),
        )
        val status = state.pcall(0, -1)
        assertEquals(LuaStatus.OK, status, state.toString(-1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(42L, state.toInteger(2))
    }

    @Test
    fun `load compiles chunks with explicit environment tables`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                value = "global"
                local env = {value = 40}
                local chunk = load("local add = ...; value = value + add; marker = 'env'; return value", "load-env.lua", "t", env)
                return chunk(2), env.value, value, env.marker
                """.trimIndent(),
                "load-env-caller.lua",
            ),
        )
        val status = state.pcall(0, -1)
        assertEquals(LuaStatus.OK, status, state.toString(-1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(42L, state.toInteger(2))
        assertEquals("global", state.toString(3))
        assertEquals("env", state.toString(4))
    }

    @Test
    fun `load accepts non table and nil environment values like lua setupvalue`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local localOnly = load("local value = 42; return value", "load-boolean-env-local.lua", "t", false)
                local globalRead = load("return missing", "load-boolean-env-global.lua", "t", false)
                local ok, message = pcall(globalRead)
                local nilLocalOnly = load("local value = 24; return value", "load-nil-env-local.lua", "t", nil)
                local nilGlobalRead = load("return missing", "load-nil-env-global.lua", "t", nil)
                local nilOk, nilMessage = pcall(nilGlobalRead)
                local stringLocalOnly = load("local value = 23; return value", "load-string-env-local.lua", "t", "environment")
                return type(localOnly), localOnly(), type(globalRead), ok, message,
                    type(nilLocalOnly), nilLocalOnly(), type(nilGlobalRead), nilOk, nilMessage,
                    type(stringLocalOnly), stringLocalOnly()
                """.trimIndent(),
                "load-boolean-env-driver.lua",
            ),
        )
        val status = state.pcall(0, -1)
        assertEquals(LuaStatus.OK, status, state.toString(-1))

        assertEquals("function", state.toString(1))
        assertEquals(42L, state.toInteger(2))
        assertEquals("function", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertTrue(state.toString(5)?.contains("attempt to index boolean") == true)
        assertEquals("function", state.toString(6))
        assertEquals(24L, state.toInteger(7))
        assertEquals("function", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertTrue(state.toString(10)?.contains("attempt to index nil") == true)
        assertEquals("function", state.toString(11))
        assertEquals(23L, state.toInteger(12))
    }

    @Test
    fun `load converts numeric chunk arguments before parsing`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local chunk, message = load(123, "number-source.lua")
                return chunk, message
                """.trimIndent(),
                "load-number-source.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertTrue(state.toString(2)?.startsWith("number-source.lua:") == true)
    }

    @Test
    fun `load compiles chunks from reader functions`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local index = 0
                local parts = {
                    "local add = ...; ",
                    "return 34 + add",
                }
                local chunk = load(function()
                    index = index + 1
                    return parts[index]
                end, "reader.lua")
                return chunk(8), index
                """.trimIndent(),
                "load-reader.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(3L, state.toInteger(2))
    }

    @Test
    fun `load reader stops after empty string chunks`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local index = 0
                local chunk = load(function()
                    index = index + 1
                    if index == 1 then
                        return ""
                    elseif index == 2 then
                        return "return 7"
                    end
                    return nil
                end, "reader-empty.lua")
                local count = select("#", chunk())
                local value = chunk()
                return type(chunk), index, count, value
                """.trimIndent(),
                "load-reader-empty.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("function", state.toString(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(0L, state.toInteger(3))
        assertTrue(state.isNil(4))
    }

    @Test
    fun `load stops reading after empty reader chunks`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local index = 0
                local parts = {
                    "return 42",
                    "",
                    "return 99",
                }
                local chunk = load(function()
                    index = index + 1
                    return parts[index]
                end, "reader-empty.lua")
                return chunk(), index
                """.trimIndent(),
                "load-reader-empty.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
    }

    @Test
    fun `load reports reader function syntax errors with default chunk name`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local done = false
                local chunk, message = load(function()
                    if done then
                        return nil
                    end
                    done = true
                    return "local x <close> = {}"
                end)
                return chunk, message
                """.trimIndent(),
                "load-reader-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertEquals("=(load):1:1: to-be-closed local variables are not supported", state.toString(2))
    }

    @Test
    fun `load reports multiple to be closed locals`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local explicitChunk, explicitMessage =
                    load("local x <close>, y <close> = nil, nil", "multiple-close.lua")
                local prefixedChunk, prefixedMessage =
                    load("local <close> x, y = nil, nil", "prefixed-multiple-close.lua")
                return explicitChunk, explicitMessage, prefixedChunk, prefixedMessage
                """.trimIndent(),
                "load-multiple-close-driver.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertEquals("multiple-close.lua:1:26: multiple to-be-closed variables in local list", state.toString(2))
        assertTrue(state.isNil(3))
        assertEquals(
            "prefixed-multiple-close.lua:1:18: multiple to-be-closed variables in local list",
            state.toString(4),
        )
    }

    @Test
    fun `load converts numeric reader function results`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local index = 0
                local parts = {"return ", 4, "2"}
                local chunk = load(function()
                    index = index + 1
                    return parts[index]
                end, "reader-number.lua")
                return chunk(), index
                """.trimIndent(),
                "load-reader-number.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(4L, state.toInteger(2))
    }

    @Test
    fun `load validates mode before reader function type`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local invalidModeOk, invalidModeMessage = pcall(load, false, nil, "B")
                local typeOk, typeMessage = pcall(load, false, nil, "t")
                return invalidModeOk, invalidModeMessage, typeOk, typeMessage
                """.trimIndent(),
                "load-validation-order.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #3 to 'load' (invalid mode)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'load' (function expected)", state.toString(4))
    }

    @Test
    fun `load converts numeric source arguments`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local chunk, message = load(123)
                local booleanOk, booleanMessage = pcall(load, false)
                local modeOk, modeMessage = pcall(load, false, nil, "B")
                return chunk, message, booleanOk, booleanMessage, modeOk, modeMessage
                """.trimIndent(),
                "load-number-source.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertTrue(state.toString(2)?.contains("123") == true)
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'load' (function expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'load' (invalid mode)", state.toString(6))
    }

    @Test
    fun `load returns reader function errors as load failures`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local chunk, message = load(function()
                    return false
                end, "reader-boolean.lua")
                local ok, protectedChunk, protectedMessage = pcall(load, function()
                    return false
                end, "reader-protected-boolean.lua")
                return chunk, message, ok, protectedChunk, protectedMessage
                """.trimIndent(),
                "load-reader-boolean.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertEquals("reader function must return a string", state.toString(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.isNil(4))
        assertEquals("reader function must return a string", state.toString(5))
    }

    @Test
    fun `load returns syntax errors without running chunks`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local chunk, message = load("local x <close> = {}", "bad-loaded.lua")
                return chunk, message
                """.trimIndent(),
                "load-error.lua",
            ),
        )
        val status = state.pcall(0, -1)
        assertEquals(LuaStatus.OK, status, state.toString(-1))

        assertTrue(state.isNil(1))
        assertEquals("bad-loaded.lua:1:1: to-be-closed local variables are not supported", state.toString(2))
    }

    @Test
    fun `load honors text chunk mode`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local textChunk = load("return 42", "text.lua", "t")
                local defaultChunk = load("return 24", "default.lua", nil)
                local binaryChunk, message = load("return 12", "binary.lua", "b")
                local invalidModeOk, invalidModeMessage = pcall(load, "return 1", "invalid.lua", "B")
                return textChunk(), defaultChunk(), binaryChunk, message, invalidModeOk, invalidModeMessage
                """.trimIndent(),
                "load-mode.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(24L, state.toInteger(2))
        assertTrue(state.isNil(3))
        assertEquals("attempt to load a text chunk (mode is 'b')", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'load' (invalid mode)", state.toString(6))
    }

    @Test
    fun `string dump round trips KLua bytecode through binary load`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function add(left, right)
                    return left + right
                end

                local dumped = string.dump(add)
                local loaded = load(dumped, "dumped-add", "b")
                local defaultLoaded = load(dumped, "dumped-add-default")
                local textOnly, textMessage = load(dumped, "dumped-add-text", "t")
                local repacked = string.pack("c" .. rawlen(dumped), dumped)
                local repackedLoaded = load(repacked, "repacked-add", "b")
                local magicK, magicL, magicU, magicA = string.byte(dumped, 1, 4)
                return type(dumped), loaded(19, 23), defaultLoaded(2, 5), textOnly, textMessage,
                    repackedLoaded(5, 6), magicK, magicL, magicU, magicA
                """.trimIndent(),
                "string-dump-roundtrip.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("string", state.toString(1))
        assertEquals(42L, state.toInteger(2))
        assertEquals(7L, state.toInteger(3))
        assertTrue(state.isNil(4))
        assertEquals("attempt to load a binary chunk (mode is 't')", state.toString(5))
        assertEquals(11L, state.toInteger(6))
        assertEquals('K'.code.toLong(), state.toInteger(7))
        assertEquals('L'.code.toLong(), state.toInteger(8))
        assertEquals('u'.code.toLong(), state.toInteger(9))
        assertEquals('a'.code.toLong(), state.toInteger(10))
    }

    @Test
    fun `string dump strip removes debug metadata from dumped chunks`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function sample(value)
                    local localValue = value .. "-done"
                    return localValue
                end

                local loaded = load(string.dump(sample, true), "stripped-sample", "b")
                local info = debug.getinfo(loaded, "SLu")
                local localName = debug.getlocal(loaded, 1)
                local outer = "upvalue"
                local function withUpvalue()
                    return outer
                end
                local loadedWithUpvalue = load(string.dump(withUpvalue, true), "stripped-upvalue", "b")
                local upvalueInfo = debug.getinfo(loadedWithUpvalue, "u")
                local upvalueName, upvalueValue = debug.getupvalue(loadedWithUpvalue, 1)
                return loaded("ok-"), info.source, info.short_src, info.activelines,
                    info.nups, info.nparams, info.isvararg, localName,
                    upvalueInfo.nups, upvalueName, upvalueValue
                """.trimIndent(),
                "string-dump-strip.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("ok--done", state.toString(1))
        assertEquals("", state.toString(2))
        assertEquals("""[string ""]""", state.toString(3))
        assertEquals("table", state.typeName(4))
        assertEquals(0L, state.toInteger(5))
        assertEquals(1L, state.toInteger(6))
        assertFalse(state.toBoolean(7))
        assertTrue(state.isNil(8))
        assertEquals(1L, state.toInteger(9))
        assertTrue(state.isNil(10))
        assertTrue(state.isNil(11))
    }

    @Test
    fun `binary load applies supplied environments to dumped chunks`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                marker = "global"
                local chunk = load("return marker", "dump-source.lua")
                local dumped = string.dump(chunk)
                local env = {marker = "env"}
                local loaded = load(dumped, "dumped-env", "b", env)
                return loaded(), marker
                """.trimIndent(),
                "string-dump-env.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("env", state.toString(1))
        assertEquals("global", state.toString(2))
    }

    @Test
    fun `string dump rejects host functions`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(string.dump, print)
                return ok, message
                """.trimIndent(),
                "string-dump-host-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'dump' (Lua function expected)", state.toString(2))
    }

    @Test
    fun `loadfile compiles chunks from configured standard input`() {
        val state = LuaState.create(
            LuaConfig(
                standardInput = Supplier {
                    "local add = ...; value = value + add; return value"
                },
            ),
        )
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local env = {value = 40}
                local chunk = loadfile(nil, "t", env)
                local info = debug.getinfo(chunk, "S")
                return chunk(2), env.value, info.source
                """.trimIndent(),
                "loadfile-stdin.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(42L, state.toInteger(2))
        assertEquals("=stdin", state.toString(3))
    }

    @Test
    fun `dofile executes configured standard input chunks`() {
        val sources = mutableListOf(
            """return "omitted", 41""",
            """return "nil", 42""",
        )
        val state = LuaState.create(
            LuaConfig(
                standardInput = Supplier { sources.removeAt(0) },
            ),
        )
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local omittedName, omittedValue = dofile()
                local nilName, nilValue = dofile(nil)
                return omittedName, omittedValue, nilName, nilValue
                """.trimIndent(),
                "dofile-stdin.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("omitted", state.toString(1))
        assertEquals(41L, state.toInteger(2))
        assertEquals("nil", state.toString(3))
        assertEquals(42L, state.toInteger(4))
        assertTrue(sources.isEmpty())
    }

    @Test
    fun `loadfile honors text chunk mode`() {
        val file = Files.createTempFile("klua-loadfile-mode", ".lua")
        try {
            Files.writeString(file, "return 42")
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    local textChunk = loadfile("${file.luaPath()}", "t")
                    local binaryChunk, message = loadfile("${file.luaPath()}", "b")
                    local invalidModeOk, invalidModeMessage = pcall(loadfile, "${file.luaPath()}", "B")
                    local invalidNilModeOk, invalidNilModeMessage = pcall(loadfile, nil, "B")
                    local invalidNumericModeOk, invalidNumericModeMessage = pcall(loadfile, 123, "B")
                    return textChunk(), binaryChunk, message, invalidModeOk, invalidModeMessage,
                        invalidNilModeOk, invalidNilModeMessage,
                        invalidNumericModeOk, invalidNumericModeMessage
                    """.trimIndent(),
                    "loadfile-mode.lua",
                ),
            )
            assertEquals(LuaStatus.OK, state.pcall(0, -1))

            assertEquals(42L, state.toInteger(1))
            assertTrue(state.isNil(2))
            assertEquals("attempt to load a text chunk (mode is 'b')", state.toString(3))
            assertFalse(state.toBoolean(4))
            assertEquals("bad argument #2 to 'loadfile' (invalid mode)", state.toString(5))
            assertFalse(state.toBoolean(6))
            assertEquals("bad argument #2 to 'loadfile' (invalid mode)", state.toString(7))
            assertFalse(state.toBoolean(8))
            assertEquals("bad argument #2 to 'loadfile' (invalid mode)", state.toString(9))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `loadfile loads KLua bytecode files in binary mode`() {
        val file = Files.createTempFile("klua-loadfile-bytecode", ".kluac")
        try {
            val dumpState = LuaState.create()
            LuaStdlib.openLibs(dumpState)
            assertEquals(
                LuaStatus.OK,
                dumpState.load(
                    """
                    local chunk = load("return marker", "loadfile-bytecode-source.lua")
                    return string.dump(chunk)
                    """.trimIndent(),
                    "loadfile-bytecode-dump.lua",
                ),
            )
            assertEquals(LuaStatus.OK, dumpState.pcall(0, -1), dumpState.toString(-1))
            val dumped = dumpState.toString(1) ?: error("missing dumped bytecode")
            Files.write(file, dumped.luaRawBytes())

            val state = LuaState.create()
            LuaStdlib.openLibs(state)
            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    marker = "global"
                    local env = {marker = "env"}
                    local binaryChunk = loadfile("${file.luaPath()}", "b", env)
                    local defaultChunk = loadfile("${file.luaPath()}")
                    local textOnly, textMessage = loadfile("${file.luaPath()}", "t")
                    return binaryChunk(), defaultChunk(), textOnly, textMessage
                    """.trimIndent(),
                    "loadfile-bytecode.lua",
                ),
            )
            assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

            assertEquals("env", state.toString(1))
            assertEquals("global", state.toString(2))
            assertTrue(state.isNil(3))
            assertEquals("attempt to load a binary chunk (mode is 't')", state.toString(4))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `dofile executes KLua bytecode files`() {
        val file = Files.createTempFile("klua-dofile-bytecode", ".kluac")
        try {
            val dumpState = LuaState.create()
            LuaStdlib.openLibs(dumpState)
            assertEquals(
                LuaStatus.OK,
                dumpState.load(
                    """
                    local chunk = load("return marker", "dofile-bytecode-source.lua")
                    return string.dump(chunk)
                    """.trimIndent(),
                    "dofile-bytecode-dump.lua",
                ),
            )
            assertEquals(LuaStatus.OK, dumpState.pcall(0, -1), dumpState.toString(-1))
            val dumped = dumpState.toString(1) ?: error("missing dumped bytecode")
            Files.write(file, dumped.luaRawBytes())

            val state = LuaState.create()
            LuaStdlib.openLibs(state)
            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    marker = "global"
                    return dofile("${file.luaPath()}")
                    """.trimIndent(),
                    "dofile-bytecode.lua",
                ),
            )
            assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

            assertEquals("global", state.toString(1))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `loadfile reads stdin when filename is nil or missing`() {
        withStandardInput("return 41 + 1") {
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    local chunk = loadfile(nil)
                    return chunk()
                    """.trimIndent(),
                    "loadfile-nil-stdin.lua",
                ),
            )
            assertEquals(LuaStatus.OK, state.pcall(0, -1))
            assertEquals(42L, state.toInteger(1))
        }

        withStandardInput("return 20 + 4") {
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    local chunk = loadfile()
                    return chunk()
                    """.trimIndent(),
                    "loadfile-missing-stdin.lua",
                ),
            )
            assertEquals(LuaStatus.OK, state.pcall(0, -1))
            assertEquals(24L, state.toInteger(1))
        }
    }

    @Test
    fun `loadfile reports stdin as chunk name`() {
        withStandardInput("local x <close> = {}") {
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(LuaStatus.OK, state.load("return loadfile(nil)", "loadfile-stdin-error.lua"))
            assertEquals(LuaStatus.OK, state.pcall(0, -1))

            assertTrue(state.isNil(1))
            assertEquals("=stdin:1:1: to-be-closed local variables are not supported", state.toString(2))
        }
    }

    @Test
    fun `loadfile compiles files with shared globals and arguments`() {
        val file = Files.createTempFile("klua-loadfile", ".lua")
        try {
            Files.writeString(file, "local add = ...; value = value + add; return value")
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    value = 40
                    local chunk = loadfile("${file.luaPath()}")
                    return chunk(2), value
                    """.trimIndent(),
                    "loadfile.lua",
                ),
            )
            val status = state.pcall(0, -1)
            assertEquals(LuaStatus.OK, status, state.toString(-1))

            assertEquals(42L, state.toInteger(1))
            assertEquals(42L, state.toInteger(2))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `loadfile compiles files with explicit environment tables`() {
        val file = Files.createTempFile("klua-loadfile-env", ".lua")
        try {
            Files.writeString(file, "local add = ...; value = value + add; loaded = true; return value")
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    value = "global"
                    local env = {value = 40}
                    local chunk = loadfile("${file.luaPath()}", "t", env)
                    return chunk(2), env.value, value, env.loaded
                    """.trimIndent(),
                    "loadfile-env.lua",
                ),
            )
            val status = state.pcall(0, -1)
            assertEquals(LuaStatus.OK, status, state.toString(-1))

            assertEquals(42L, state.toInteger(1))
            assertEquals(42L, state.toInteger(2))
            assertEquals("global", state.toString(3))
            assertTrue(state.toBoolean(4))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `loadfile accepts non table and nil environment values like lua setupvalue`() {
        val localFile = Files.createTempFile("klua-loadfile-boolean-env-local", ".lua")
        val globalFile = Files.createTempFile("klua-loadfile-boolean-env-global", ".lua")
        try {
            Files.writeString(localFile, "local value = 42; return value")
            Files.writeString(globalFile, "return missing")
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    local localOnly = loadfile("${localFile.luaPath()}", "t", false)
                    local globalRead = loadfile("${globalFile.luaPath()}", "t", false)
                    local ok, message = pcall(globalRead)
                    local nilLocalOnly = loadfile("${localFile.luaPath()}", "t", nil)
                    local nilGlobalRead = loadfile("${globalFile.luaPath()}", "t", nil)
                    local nilOk, nilMessage = pcall(nilGlobalRead)
                    return type(localOnly), localOnly(), type(globalRead), ok, message,
                        type(nilLocalOnly), nilLocalOnly(), type(nilGlobalRead), nilOk, nilMessage
                    """.trimIndent(),
                    "loadfile-boolean-env-driver.lua",
                ),
            )
            val status = state.pcall(0, -1)
            assertEquals(LuaStatus.OK, status, state.toString(-1))

            assertEquals("function", state.toString(1))
            assertEquals(42L, state.toInteger(2))
            assertEquals("function", state.toString(3))
            assertFalse(state.toBoolean(4))
            assertTrue(state.toString(5)?.contains("attempt to index boolean") == true)
            assertEquals("function", state.toString(6))
            assertEquals(42L, state.toInteger(7))
            assertEquals("function", state.toString(8))
            assertFalse(state.toBoolean(9))
            assertTrue(state.toString(10)?.contains("attempt to index nil") == true)
        } finally {
            Files.deleteIfExists(localFile)
            Files.deleteIfExists(globalFile)
        }
    }

    @Test
    fun `dofile returns file chunk values`() {
        val file = Files.createTempFile("klua-dofile", ".lua")
        try {
            Files.writeString(file, """return 42, "ok"""")
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(LuaStatus.OK, state.load("""return dofile("${file.luaPath()}")""", "dofile.lua"))
            val status = state.pcall(0, -1)
            assertEquals(LuaStatus.OK, status, state.toString(-1))

            assertEquals(42L, state.toInteger(1))
            assertEquals("ok", state.toString(2))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `dofile ignores extra arguments like lua settop before loadfile`() {
        val file = Files.createTempFile("klua-dofile-extra", ".lua")
        try {
            Files.writeString(file, """return value, missing, "ok"""")
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    value = "global"
                    local env = {value = "env", missing = "env"}
                    return dofile("${file.luaPath()}", "b", env)
                    """.trimIndent(),
                    "dofile-extra.lua",
                ),
            )
            val status = state.pcall(0, -1)
            assertEquals(LuaStatus.OK, status, state.toString(-1))

            assertEquals("global", state.toString(1))
            assertTrue(state.isNil(2))
            assertEquals("ok", state.toString(3))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `dofile reads stdin when filename is missing`() {
        withStandardInput("""return 42, "stdin"""") {
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(LuaStatus.OK, state.load("return dofile()", "dofile-stdin.lua"))
            val status = state.pcall(0, -1)
            assertEquals(LuaStatus.OK, status, state.toString(-1))

            assertEquals(42L, state.toInteger(1))
            assertEquals("stdin", state.toString(2))
        }
    }

    @Test
    fun `loadfile returns syntax errors`() {
        val file = Files.createTempFile("klua-loadfile-error", ".lua")
        try {
            Files.writeString(file, "local x <close> = {}")
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(LuaStatus.OK, state.load("""return loadfile("${file.luaPath()}")""", "loadfile-error.lua"))
            val status = state.pcall(0, -1)
            assertEquals(LuaStatus.OK, status, state.toString(-1))

            assertTrue(state.isNil(1))
            assertEquals("${file}:1:1: to-be-closed local variables are not supported", state.toString(2))
        } finally {
            Files.deleteIfExists(file)
        }
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
                local pairCount = select("#", pairs(values))
                local pairIterator, pairState, pairKey = pairs(values)
                local pairFirstKey, pairFirstValue = pairIterator(pairState, pairKey)
                local pairSecondKey, pairSecondValue = pairIterator(pairState, pairFirstKey)
                local ipairsCount = select("#", ipairs(values))
                local ipairsIterator, ipairsState, ipairsIndex = ipairs(values)
                local ipairsFirstKey, ipairsFirstValue = ipairsIterator(ipairsState, ipairsIndex)
                local ipairsSecondKey, ipairsSecondValue = ipairsIterator(ipairsState, ipairsFirstKey)
                local ipairsDone = ipairsIterator(ipairsState, ipairsSecondKey)
                return pairCount, pairFirstKey, pairFirstValue, pairSecondKey, pairSecondValue,
                    ipairsCount,
                    ipairsFirstKey, ipairsFirstValue, ipairsSecondKey, ipairsSecondValue, ipairsDone
                """.trimIndent(),
                "pairs-ipairs.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(4L, state.toInteger(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals("a", state.toString(3))
        assertEquals(2L, state.toInteger(4))
        assertEquals("b", state.toString(5))
        assertEquals(3L, state.toInteger(6))
        assertEquals(1L, state.toInteger(7))
        assertEquals("a", state.toString(8))
        assertEquals(2L, state.toInteger(9))
        assertEquals("b", state.toString(10))
        assertTrue(state.isNil(11))
    }

    @Test
    fun `pairs uses pairs metamethod`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local target = setmetatable({}, {
                    __pairs = function()
                        local emitted = false
                        return function(_, _)
                            if emitted then
                                return nil
                            end
                            emitted = true
                            return "custom", "value"
                        end, {}, nil
                    end,
                })
                local seenKey, seenValue
                for key, value in pairs(target) do
                    seenKey, seenValue = key, value
                end
                return seenKey, seenValue, select("#", pairs(target))
                """.trimIndent(),
                "pairs-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("custom", state.toString(1))
        assertEquals("value", state.toString(2))
        assertEquals(4L, state.toInteger(3))
    }

    @Test
    fun `pairs uses primitive type pairs metamethod`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(false, {
                    __pairs = function(self)
                        local emitted = false
                        return function(state, _)
                            if emitted then
                                return nil
                            end
                            emitted = true
                            return state, tostring(state)
                        end, self, nil, "closing"
                    end,
                })
                local iterator, pairState, pairKey, closing = pairs(true)
                local key, value = iterator(pairState, pairKey)
                local done = iterator(pairState, key)
                return pairState, key, value, done, closing, select("#", pairs(false))
                """.trimIndent(),
                "pairs-primitive-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("true", state.toString(3))
        assertTrue(state.isNil(4))
        assertEquals("closing", state.toString(5))
        assertEquals(4L, state.toInteger(6))
    }

    @Test
    fun `pairs and ipairs report table argument errors`() {
        val pairState = LuaState.create()
        LuaStdlib.openBase(pairState)

        assertEquals(LuaStatus.OK, pairState.load("""return pairs()""", "pairs-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, pairState.pcall(0, -1))

        assertIs<LuaRuntimeException>(pairState.getLastError())
        assertEquals("bad argument #1 to 'pairs' (value expected)", pairState.toString(-1))

        val ipairsState = LuaState.create()
        LuaStdlib.openBase(ipairsState)

        assertEquals(LuaStatus.OK, ipairsState.load("""return ipairs()""", "ipairs-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, ipairsState.pcall(0, -1))

        assertIs<LuaRuntimeException>(ipairsState.getLastError())
        assertEquals("bad argument #1 to 'ipairs' (value expected)", ipairsState.toString(-1))
    }

    @Test
    fun `pairs defers non table errors and ipairs ends non table iteration`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local pairIterator, pairState, pairKey = pairs("not-table")
                local pairOk, pairMessage = pcall(pairIterator, pairState, pairKey)
                local ipairsIterator, ipairsState, ipairsIndex = ipairs("not-table")
                local ipairsOk, ipairsMessage = pcall(ipairsIterator, ipairsState, ipairsIndex)
                local ipairsIndexOk, ipairsIndexMessage = pcall(ipairsIterator, ipairsState, "not-index")
                return type(pairIterator), pairState, pairKey, pairOk, pairMessage,
                    type(ipairsIterator), ipairsState, ipairsIndex, ipairsOk, ipairsMessage,
                    ipairsIndexOk, ipairsIndexMessage
                """.trimIndent(),
                "pairs-ipairs-non-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("function", state.toString(1))
        assertEquals("not-table", state.toString(2))
        assertTrue(state.isNil(3))
        assertFalse(state.toBoolean(4))
        assertEquals("bad argument #1 to 'next' (table expected)", state.toString(5))
        assertEquals("function", state.toString(6))
        assertEquals("not-table", state.toString(7))
        assertEquals(0L, state.toInteger(8))
        assertTrue(state.toBoolean(9))
        assertTrue(state.isNil(10))
        assertFalse(state.toBoolean(11))
        assertEquals("bad argument #2 to 'ipairs iterator' (number expected)", state.toString(12))
    }

    @Test
    fun `ipairs uses index metamethod values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = setmetatable({}, {
                    __index = function(_, index)
                        if index <= 2 then
                            return index * 10
                        end
                        return nil
                    end,
                })
                local firstIndex, firstValue
                local secondIndex, secondValue
                for index, value in ipairs(values) do
                    if index == 1 then
                        firstIndex, firstValue = index, value
                    else
                        secondIndex, secondValue = index, value
                    end
                end
                return firstIndex, firstValue, secondIndex, secondValue
                """.trimIndent(),
                "ipairs-index-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(10L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals(20L, state.toInteger(4))
    }

    @Test
    fun `ipairs uses primitive type index metamethod values`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(1, {
                    __index = {
                        [1] = "one",
                        [2] = "two",
                    },
                })
                debug.setmetatable(false, {
                    __index = function(self, index)
                        if index <= 2 then
                            return tostring(self) .. ":" .. index
                        end
                        return nil
                    end,
                })
                local numberValues = {}
                for index, value in ipairs(7) do
                    numberValues[#numberValues + 1] = index .. ":" .. value
                end
                local booleanValues = {}
                for index, value in ipairs(true) do
                    booleanValues[#booleanValues + 1] = index .. ":" .. value
                end
                return table.concat(numberValues, ","),
                    table.concat(booleanValues, ",")
                """.trimIndent(),
                "ipairs-primitive-index-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("1:one,2:two", state.toString(1))
        assertEquals("1:true:1,2:true:2", state.toString(2))
    }

    @Test
    fun `pairs and ipairs support non-table metatables`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable("proxy", {
                    __pairs = function(value)
                        local done = false
                        return function(state, key)
                            if done then
                                return nil
                            end
                            done = true
                            return "seen", state .. ":" .. tostring(key)
                        end, value, "start", "closed"
                    end,
                    __index = function(_, key)
                        if key <= 2 then
                            return "v" .. key
                        end
                        return nil
                    end,
                })

                local pairCount = select("#", pairs("proxy"))
                local pairIterator, pairState, pairKey, pairClose = pairs("proxy")
                local firstKey, firstValue = pairIterator(pairState, pairKey)
                local secondKey = pairIterator(pairState, firstKey)

                local firstIndex, firstValueFromIpairs
                local secondIndex, secondValueFromIpairs
                for index, value in ipairs("proxy") do
                    if index == 1 then
                        firstIndex, firstValueFromIpairs = index, value
                    else
                        secondIndex, secondValueFromIpairs = index, value
                    end
                end

                debug.setmetatable("proxy", nil)
                return pairCount, pairState, pairKey, pairClose, firstKey, firstValue, secondKey,
                    firstIndex, firstValueFromIpairs, secondIndex, secondValueFromIpairs
                """.trimIndent(),
                "pairs-ipairs-non-table-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(4L, state.toInteger(1))
        assertEquals("proxy", state.toString(2))
        assertEquals("start", state.toString(3))
        assertEquals("closed", state.toString(4))
        assertEquals("seen", state.toString(5))
        assertEquals("proxy:start", state.toString(6))
        assertTrue(state.isNil(7))
        assertEquals(1L, state.toInteger(8))
        assertEquals("v1", state.toString(9))
        assertEquals(2L, state.toInteger(10))
        assertEquals("v2", state.toString(11))
    }

    @Test
    fun `ipairs iterator reports index argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator, values = ipairs({10, 20})
                local okNil, nilMessage = pcall(iterator, values, nil)
                local okString, stringMessage = pcall(iterator, values, "not-index")
                local okFractional, fractionalMessage = pcall(iterator, values, 0.5)
                local okStringFraction, stringFractionMessage = pcall(iterator, values, "1.5")
                return okNil, nilMessage, okString, stringMessage,
                    okFractional, fractionalMessage, okStringFraction, stringFractionMessage
                """.trimIndent(),
                "ipairs-iterator-index-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'ipairs iterator' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'ipairs iterator' (number expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals(
            "bad argument #2 to 'ipairs iterator' (number has no integer representation)",
            state.toString(6),
        )
        assertFalse(state.toBoolean(7))
        assertEquals(
            "bad argument #2 to 'ipairs iterator' (number has no integer representation)",
            state.toString(8),
        )
    }

    @Test
    fun `ipairs iterator reports non indexable state errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator, values, index = ipairs(42)
                local ok, message = pcall(iterator, values, index)
                return ok, message
                """.trimIndent(),
                "ipairs-iterator-state-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to index a number value", state.toString(2))
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
    fun `next reports invalid key errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return next({name = "klua"}, "missing")""", "next-invalid-key.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("invalid key to 'next'", state.toString(-1))
    }

    @Test
    fun `rawequal compares primitive values and raw identities`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local tableValue = {}
                local equalByMetamethod = setmetatable({}, {
                    __eq = function()
                        return true
                    end,
                })
                local functionValue = function() end
                local otherFunction = function() end
                local threadValue = coroutine.create(function() end)
                local otherThread = coroutine.create(function() end)
                local max = math.maxinteger
                local maxMinusOne = math.maxinteger - 1
                local min = math.mininteger
                return rawequal(nil, nil),
                    rawequal(1, 1.0),
                    rawequal("x", "x"),
                    rawequal("\195" .. "\169", "é"),
                    rawequal("\255" .. "", "\255"),
                    rawequal(false, false),
                    rawequal(1, "1"),
                    rawequal(tableValue, tableValue),
                    rawequal({}, {}),
                    rawequal(functionValue, functionValue),
                    rawequal(functionValue, otherFunction),
                    rawequal(threadValue, threadValue),
                    rawequal(threadValue, otherThread),
                    rawequal(equalByMetamethod, {}),
                    rawequal(max, maxMinusOne),
                    rawequal(max, max + 0.0),
                    rawequal(min, min + 0.0)
                """.trimIndent(),
                "rawequal.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.toBoolean(6))
        assertFalse(state.toBoolean(7))
        assertTrue(state.toBoolean(8))
        assertFalse(state.toBoolean(9))
        assertTrue(state.toBoolean(10))
        assertFalse(state.toBoolean(11))
        assertTrue(state.toBoolean(12))
        assertFalse(state.toBoolean(13))
        assertFalse(state.toBoolean(14))
        assertFalse(state.toBoolean(15))
        assertFalse(state.toBoolean(16))
        assertTrue(state.toBoolean(17))
    }

    @Test
    fun `rawequal reports missing argument errors`() {
        val firstState = LuaState.create()
        LuaStdlib.openBase(firstState)

        assertEquals(LuaStatus.OK, firstState.load("""return rawequal()""", "rawequal-first-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, firstState.pcall(0, -1))

        assertIs<LuaRuntimeException>(firstState.getLastError())
        assertEquals("bad argument #1 to 'rawequal' (value expected)", firstState.toString(-1))

        val secondState = LuaState.create()
        LuaStdlib.openBase(secondState)

        assertEquals(LuaStatus.OK, secondState.load("""return rawequal(1)""", "rawequal-second-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, secondState.pcall(0, -1))

        assertIs<LuaRuntimeException>(secondState.getLastError())
        assertEquals("bad argument #2 to 'rawequal' (value expected)", secondState.toString(-1))
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
    fun `rawget reports missing key argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawget({})""", "rawget-key-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'rawget' (value expected)", state.toString(-1))
    }

    @Test
    fun `rawget returns nil for nil keys`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawget({}, nil)""", "rawget-nil-key.lua"))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
    }

    @Test
    fun `rawget returns nil for nan keys`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawget({}, 0 / 0)""", "rawget-nan-key.lua"))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
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
                return rawlen("KLua"), rawlen(values), rawlen("é")
                """.trimIndent(),
                "rawlen.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(4L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
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
    fun `rawset reports missing value argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawset({}, nil)""", "rawset-missing-value-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #3 to 'rawset' (value expected)", state.toString(-1))
    }

    @Test
    fun `rawset reports missing key argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawset({})""", "rawset-missing-key-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'rawset' (value expected)", state.toString(-1))
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
    fun `rawset rejects nan keys`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawset({}, 0 / 0, "value")""", "rawset-nan-key-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("table index is NaN", state.toString(-1))
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
    fun `getmetatable and setmetatable honor protected metatables`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local object = {}
                local marker = {name = "locked"}
                setmetatable(object, {
                    __index = {name = "from-metatable"},
                    __metatable = marker
                })
                local visible = getmetatable(object)
                local ok, message = pcall(setmetatable, object, {})
                return visible == marker, visible.name, object.name, ok, message
                """.trimIndent(),
                "protected-metatable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("locked", state.toString(2))
        assertEquals("from-metatable", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("cannot change a protected metatable", state.toString(5))
    }

    @Test
    fun `debug metatable functions bypass base metatable protection for tables`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local object = {}
                local marker = {name = "locked"}
                local hidden = {
                    __index = {name = "hidden"},
                    __metatable = marker
                }
                setmetatable(object, hidden)

                local visible = getmetatable(object)
                local raw = debug.getmetatable(object)
                local replacement = {__index = {name = "replacement"}}
                local returned = debug.setmetatable(object, replacement)
                local after = debug.getmetatable(object)
                local replacedName = object.name
                local cleared = debug.setmetatable(object, nil)
                return visible == marker,
                    raw == hidden,
                    raw.__metatable == marker,
                    returned == object,
                    after == replacement,
                    replacedName,
                    cleared == object,
                    debug.getmetatable(object)
                """.trimIndent(),
                "debug-metatable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertEquals("replacement", state.toString(6))
        assertTrue(state.toBoolean(7))
        assertTrue(state.isNil(8))
    }

    @Test
    fun `table index and newindex support native metamethod functions`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.register("nativeIndex") { context ->
            LuaReturn.of("native-index-${context.toString(2)}")
        }
        state.register("nativeNewIndex") { context ->
            context.setTableField(context.getTable(1), context.getLuaValue(2), context.getLuaValue(3))
            LuaReturn.none()
        }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local indexed = setmetatable({}, {
                    __index = nativeIndex,
                })
                local assigned = setmetatable({}, {
                    __newindex = nativeNewIndex,
                })

                local indexedValue = indexed.answer
                assigned.name = "stored"

                return indexedValue, assigned.name
                """.trimIndent(),
                "table-native-index-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("native-index-answer", state.toString(1))
        assertEquals("stored", state.toString(2))
    }

    @Test
    fun `table equality supports native metamethod functions`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        val calls = AtomicInteger()
        state.register("nativeEq") {
            calls.incrementAndGet()
            LuaReturn.of(true)
        }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local left = setmetatable({}, {
                    __eq = nativeEq,
                })
                local right = {}
                local sameIdentity = left == left
                local leftResult = left == right
                local crossTypeResult = left == 1

                local rightOnly = {}
                local rightMeta = setmetatable({}, {
                    __eq = nativeEq,
                })
                local rightResult = rightOnly == rightMeta

                return sameIdentity,
                    leftResult,
                    crossTypeResult,
                    rightResult,
                    rawequal(left, right)
                """.trimIndent(),
                "table-native-equality.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertFalse(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertFalse(state.toBoolean(5))
        assertEquals(2, calls.get())
    }

    @Test
    fun `table scalar index and newindex metamethods are retried as receivers`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local indexed = setmetatable({}, {
                    __index = 42,
                })
                local indexOk, indexMessage = pcall(function()
                    return indexed.missing
                end)

                local assigned = setmetatable({}, {
                    __newindex = 42,
                })
                local newIndexOk, newIndexMessage = pcall(function()
                    assigned.name = "stored"
                end)

                return indexOk, indexMessage,
                    newIndexOk, newIndexMessage,
                    rawget(assigned, "name")
                """.trimIndent(),
                "table-scalar-index-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertTrue(state.toString(2)?.endsWith("attempt to index number") == true, state.toString(2))
        assertFalse(state.toBoolean(3))
        assertTrue(state.toString(4)?.endsWith("attempt to index number") == true, state.toString(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `debug metatable functions can replace string metatable`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local metatable = debug.getmetatable("text")
                local replacement = {__index = {custom = 11}}
                local okTable, returned = pcall(debug.setmetatable, "text", replacement)
                local raw = debug.getmetatable("other")
                local custom = ("abc").custom
                debug.setmetatable("", metatable)
                local len = ("abc"):len()
                return type(metatable), metatable.__index == string, okTable, returned, raw == replacement, custom, len
                """.trimIndent(),
                "debug-string-metatable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table", state.toString(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertEquals("text", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertEquals(11L, state.toInteger(6))
        assertEquals(3L, state.toInteger(7))
    }

    @Test
    fun `debug metatable functions support non-table values`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        val hostUserData = Any()
        val otherUserData = Any()
        state.register("hostUserData") { LuaReturn.of(hostUserData) }
        state.register("otherUserData") { LuaReturn.of(otherUserData) }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local stringMeta = {name = "string", __metatable = "visible-string"}
                local numberMeta = {name = "number"}
                local booleanMeta = {name = "boolean"}
                local nilMeta = {name = "nil"}
                local userMeta = {name = "userdata"}
                local userdata = hostUserData()

                local stringReturned = debug.setmetatable("alpha", stringMeta)
                local numberReturned = debug.setmetatable(12, numberMeta)
                local booleanReturned = debug.setmetatable(false, booleanMeta)
                local nilReturned = debug.setmetatable(nil, nilMeta)
                local userdataReturned = debug.setmetatable(userdata, userMeta)

                local stringActual = debug.getmetatable("beta")
                local baseStringActual = getmetatable("beta")
                local numberActual = debug.getmetatable(34)
                local booleanActual = debug.getmetatable(true)
                local nilActual = debug.getmetatable(nil)
                local userdataActual = debug.getmetatable(userdata)
                local otherUserdataActual = debug.getmetatable(otherUserData())

                debug.setmetatable("alpha", nil)
                debug.setmetatable(12, nil)
                debug.setmetatable(false, nil)
                debug.setmetatable(nil, nil)
                debug.setmetatable(userdata, nil)

                return stringReturned,
                    numberReturned,
                    booleanReturned,
                    nilReturned,
                    userdataReturned == userdata,
                    stringActual == stringMeta,
                    baseStringActual,
                    numberActual == numberMeta,
                    booleanActual == booleanMeta,
                    nilActual == nilMeta,
                    userdataActual == userMeta,
                    otherUserdataActual,
                    debug.getmetatable("beta"),
                    debug.getmetatable(34),
                    debug.getmetatable(true),
                    debug.getmetatable(nil),
                    debug.getmetatable(userdata)
                """.trimIndent(),
                "debug-non-table-metatable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("alpha", state.toString(1))
        assertEquals(12L, state.toInteger(2))
        assertFalse(state.toBoolean(3))
        assertTrue(state.isNil(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.toBoolean(6))
        assertEquals("visible-string", state.toString(7))
        assertTrue(state.toBoolean(8))
        assertTrue(state.toBoolean(9))
        assertTrue(state.toBoolean(10))
        assertTrue(state.toBoolean(11))
        assertTrue(state.isNil(12))
        assertTrue(state.isNil(13))
        assertTrue(state.isNil(14))
        assertTrue(state.isNil(15))
        assertTrue(state.isNil(16))
        assertTrue(state.isNil(17))
    }

    @Test
    fun `userdata equality metamethods handle source operators`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        val leftUserData = Any()
        val rightUserData = Any()
        state.register("leftUserData") { LuaReturn.of(leftUserData) }
        state.register("rightUserData") { LuaReturn.of(rightUserData) }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local left = leftUserData()
                local right = rightUserData()
                local calls = {}

                debug.setmetatable(left, {
                    __eq = function(a, b)
                        calls[#calls + 1] = { "left", a == left, b == right }
                        return true
                    end,
                })

                local sameIdentity = left == left
                local leftResult = left == right
                local crossTypeResult = left == {}

                debug.setmetatable(left, nil)
                debug.setmetatable(right, {
                    __eq = function(a, b)
                        calls[#calls + 1] = { "right", a == left, b == right }
                        return true
                    end,
                })

                local rightResult = left == right
                debug.setmetatable(right, nil)

                return sameIdentity,
                    leftResult, calls[1][1], calls[1][2], calls[1][3],
                    crossTypeResult,
                    rightResult, calls[2][1], calls[2][2], calls[2][3]
                """.trimIndent(),
                "userdata-equality-metamethods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("left", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertFalse(state.toBoolean(6))
        assertTrue(state.toBoolean(7))
        assertEquals("right", state.toString(8))
        assertTrue(state.toBoolean(9))
        assertTrue(state.toBoolean(10))
    }

    @Test
    fun `userdata equality supports native metamethod functions`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        val leftUserData = Any()
        val rightUserData = Any()
        val calls = AtomicInteger()
        state.register("leftUserData") { LuaReturn.of(leftUserData) }
        state.register("rightUserData") { LuaReturn.of(rightUserData) }
        state.register("nativeEq") {
            calls.incrementAndGet()
            LuaReturn.of(true)
        }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local left = leftUserData()
                local right = rightUserData()
                debug.setmetatable(left, {
                    __eq = nativeEq,
                })

                local sameIdentity = left == left
                local leftResult = left == right
                local crossTypeResult = left == {}

                debug.setmetatable(left, nil)
                debug.setmetatable(right, {
                    __eq = nativeEq,
                })
                local rightResult = left == right

                return sameIdentity,
                    leftResult,
                    crossTypeResult,
                    rightResult,
                    rawequal(left, right)
                """.trimIndent(),
                "userdata-native-equality.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertFalse(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertFalse(state.toBoolean(5))
        assertEquals(2, calls.get())
    }

    @Test
    fun `userdata index metamethods handle source reads`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        val tableBackedUserData = Any()
        val functionBackedUserData = Any()
        state.register("tableBackedUserData") { LuaReturn.of(tableBackedUserData) }
        state.register("functionBackedUserData") { LuaReturn.of(functionBackedUserData) }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local tableBacked = tableBackedUserData()
                debug.setmetatable(tableBacked, {
                    __index = {
                        name = "from-table",
                    },
                })

                local functionBacked = functionBackedUserData()
                local functionCalls = {}
                debug.setmetatable(functionBacked, {
                    __index = function(self, key)
                        functionCalls[#functionCalls + 1] = { self == functionBacked, key }
                        return "from-function-" .. key
                    end,
                })

                local tableName = tableBacked.name
                local missingTableName = tableBacked.missing
                local functionName = functionBacked.name
                local functionOther = functionBacked.other

                debug.setmetatable(tableBacked, nil)
                debug.setmetatable(functionBacked, nil)

                return tableName, missingTableName,
                    functionName, functionCalls[1][1], functionCalls[1][2],
                    functionOther, functionCalls[2][1], functionCalls[2][2],
                    tableBacked.name, functionBacked.name
                """.trimIndent(),
                "userdata-index-metamethods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("from-table", state.toString(1))
        assertTrue(state.isNil(2))
        assertEquals("from-function-name", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertEquals("name", state.toString(5))
        assertEquals("from-function-other", state.toString(6))
        assertTrue(state.toBoolean(7))
        assertEquals("other", state.toString(8))
        assertTrue(state.isNil(9))
        assertTrue(state.isNil(10))
    }

    @Test
    fun `userdata newindex metamethods handle source assignments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        val tableBackedUserData = Any()
        val functionBackedUserData = Any()
        state.register("tableBackedUserData") { LuaReturn.of(tableBackedUserData) }
        state.register("functionBackedUserData") { LuaReturn.of(functionBackedUserData) }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local tableBacked = tableBackedUserData()
                local target = {}
                debug.setmetatable(tableBacked, {
                    __newindex = target,
                })
                tableBacked.name = "from-table"
                tableBacked[2] = "numeric-key"

                local functionBacked = functionBackedUserData()
                local functionCalls = {}
                debug.setmetatable(functionBacked, {
                    __newindex = function(self, key, value)
                        functionCalls[#functionCalls + 1] = { self == functionBacked, key, value }
                    end,
                })
                functionBacked.name = "from-function"
                functionBacked[3] = "numeric-function"

                debug.setmetatable(tableBacked, nil)
                debug.setmetatable(functionBacked, nil)
                local missingOk, missingMessage = pcall(function()
                    tableBacked.name = "after-clear"
                end)

                return target.name, target[2],
                    functionCalls[1][1], functionCalls[1][2], functionCalls[1][3],
                    functionCalls[2][1], functionCalls[2][2], functionCalls[2][3],
                    missingOk, missingMessage
                """.trimIndent(),
                "userdata-newindex-metamethods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("from-table", state.toString(1))
        assertEquals("numeric-key", state.toString(2))
        assertTrue(state.toBoolean(3))
        assertEquals("name", state.toString(4))
        assertEquals("from-function", state.toString(5))
        assertTrue(state.toBoolean(6))
        assertEquals(3L, state.toInteger(7))
        assertEquals("numeric-function", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertTrue(state.toString(10)?.endsWith("attempt to set userdata field 'name'") == true, state.toString(10))
    }

    @Test
    fun `userdata length metamethods handle source length operator`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        val hostUserData = Any()
        val otherUserData = Any()
        state.register("hostUserData") { LuaReturn.of(hostUserData) }
        state.register("otherUserData") { LuaReturn.of(otherUserData) }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local userdata = hostUserData()
                local args
                debug.setmetatable(userdata, {
                    __len = function(left, right)
                        args = { left == userdata, right == userdata, left == right }
                        return "userdata-len"
                    end,
                })

                local length = #userdata
                local missingOk, missingMessage = pcall(function()
                    return #otherUserData()
                end)

                debug.setmetatable(userdata, nil)
                local clearedOk, clearedMessage = pcall(function()
                    return #userdata
                end)

                return length, args[1], args[2], args[3],
                    missingOk, missingMessage,
                    clearedOk, clearedMessage
                """.trimIndent(),
                "userdata-length-metamethods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("userdata-len", state.toString(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertFalse(state.toBoolean(5))
        assertTrue(state.toString(6)?.endsWith("attempt to get length of userdata") == true, state.toString(6))
        assertFalse(state.toBoolean(7))
        assertTrue(state.toString(8)?.endsWith("attempt to get length of userdata") == true, state.toString(8))
    }

    @Test
    fun `non-table newindex metamethods handle source assignments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local calls = {}
                debug.setmetatable(42, {
                    __newindex = function(value, key, assigned)
                        calls[#calls + 1] = { value, key, assigned }
                    end,
                })
                local numberValue = 42
                numberValue.answer = "set"

                local target = {}
                debug.setmetatable(false, {
                    __newindex = target,
                })
                local booleanValue = false
                booleanValue.flag = "stored"

                local missingOk, missingMessage = pcall(function()
                    local text = "plain"
                    text.value = "missing"
                end)

                debug.setmetatable(42, nil)
                debug.setmetatable(false, nil)

                return calls[1][1], calls[1][2], calls[1][3],
                    target.flag, missingOk, missingMessage
                """.trimIndent(),
                "non-table-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(42L, state.toInteger(1))
        assertEquals("answer", state.toString(2))
        assertEquals("set", state.toString(3))
        assertEquals("stored", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertTrue(state.toString(6)?.endsWith("attempt to index string") == true, state.toString(6))
    }

    @Test
    fun `non-table length metamethods handle source length operator`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numberArgs
                debug.setmetatable(42, {
                    __len = function(left, right)
                        numberArgs = { left, right, left == right }
                        return "number-len"
                    end,
                })
                debug.setmetatable(false, {
                    __len = function(value)
                        return value == false and 17 or 0
                    end,
                })
                debug.setmetatable("abc", {
                    __len = function()
                        return 99
                    end,
                })

                local numberLength = #42
                local booleanLength = #false
                local stringLength = #"abc"
                local missingOk, missingMessage = pcall(function()
                    return #(function() end)
                end)

                debug.setmetatable(42, nil)
                debug.setmetatable(false, nil)
                debug.setmetatable("abc", nil)

                return numberLength, numberArgs[1], numberArgs[2], numberArgs[3],
                    booleanLength, stringLength, missingOk, missingMessage
                """.trimIndent(),
                "non-table-len.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("number-len", state.toString(1))
        assertEquals(42L, state.toInteger(2))
        assertEquals(42L, state.toInteger(3))
        assertTrue(state.toBoolean(4))
        assertEquals(17L, state.toInteger(5))
        assertEquals(3L, state.toInteger(6))
        assertFalse(state.toBoolean(7))
        assertTrue(state.toString(8)?.endsWith("attempt to get length of function") == true, state.toString(8))
    }

    @Test
    fun `non-table unary metamethods handle source operators`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local minusArgs
                debug.setmetatable(false, {
                    __unm = function(left, right)
                        minusArgs = { left, right, left == right }
                        return "negated"
                    end,
                })

                local marker = function() end
                local bitwiseArgs
                debug.setmetatable(marker, {
                    __bnot = function(left, right)
                        bitwiseArgs = { left == marker, right == marker, left == right }
                        return "inverted"
                    end,
                })

                local minusResult = -false
                local bitwiseResult = ~marker
                local missingMinusOk, missingMinusMessage = pcall(function()
                    return -"plain"
                end)
                local missingBitwiseOk, missingBitwiseMessage = pcall(function()
                    return ~"plain"
                end)

                debug.setmetatable(false, nil)
                debug.setmetatable(marker, nil)

                return minusResult, minusArgs[1], minusArgs[2], minusArgs[3],
                    bitwiseResult, bitwiseArgs[1], bitwiseArgs[2], bitwiseArgs[3],
                    missingMinusOk, missingMinusMessage,
                    missingBitwiseOk, missingBitwiseMessage
                """.trimIndent(),
                "non-table-unary-metamethods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("negated", state.toString(1))
        assertFalse(state.toBoolean(2))
        assertFalse(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertEquals("inverted", state.toString(5))
        assertTrue(state.toBoolean(6))
        assertTrue(state.toBoolean(7))
        assertTrue(state.toBoolean(8))
        assertFalse(state.toBoolean(9))
        assertTrue(state.toString(10)?.endsWith("attempt to perform arithmetic on string") == true, state.toString(10))
        assertFalse(state.toBoolean(11))
        assertTrue(
            state.toString(12)?.endsWith("attempt to perform bitwise operation on string") == true,
            state.toString(12),
        )
    }

    @Test
    fun `non-table arithmetic metamethods handle source operators`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local booleanArgs = {}
                debug.setmetatable(false, {
                    __add = function(left, right)
                        booleanArgs[#booleanArgs + 1] = { left, right }
                        return "boolean-add"
                    end,
                })

                local leftResult = false + 5
                local rightResult = 5 + false

                debug.setmetatable(1, {
                    __add = function()
                        return "number-add"
                    end,
                })
                local primitiveNumberResult = 4 + 5
                local missingOk, missingMessage = pcall(function()
                    return "x" - true
                end)

                debug.setmetatable(false, nil)
                debug.setmetatable(1, nil)

                return leftResult, booleanArgs[1][1], booleanArgs[1][2],
                    rightResult, booleanArgs[2][1], booleanArgs[2][2],
                    primitiveNumberResult,
                    missingOk, missingMessage
                """.trimIndent(),
                "non-table-arithmetic-metamethods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("boolean-add", state.toString(1))
        assertFalse(state.toBoolean(2))
        assertEquals(5L, state.toInteger(3))
        assertEquals("boolean-add", state.toString(4))
        assertEquals(5L, state.toInteger(5))
        assertFalse(state.toBoolean(6))
        assertEquals(9L, state.toInteger(7))
        assertFalse(state.toBoolean(8))
        assertTrue(state.toString(9)?.endsWith("attempt to perform arithmetic on string") == true, state.toString(9))
    }

    @Test
    fun `non-table bitwise metamethods handle source operators`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local booleanArgs = {}
                debug.setmetatable(false, {
                    __band = function(left, right)
                        booleanArgs[#booleanArgs + 1] = { left, right }
                        return "boolean-band"
                    end,
                })

                local leftResult = false & 3
                local rightResult = 3 & false

                debug.setmetatable(1, {
                    __band = function()
                        return "number-band"
                    end,
                })
                local primitiveIntegerResult = 6 & 3
                local missingOk, missingMessage = pcall(function()
                    return true | "x"
                end)

                debug.setmetatable(false, nil)
                debug.setmetatable(1, nil)

                return leftResult, booleanArgs[1][1], booleanArgs[1][2],
                    rightResult, booleanArgs[2][1], booleanArgs[2][2],
                    primitiveIntegerResult,
                    missingOk, missingMessage
                """.trimIndent(),
                "non-table-bitwise-metamethods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("boolean-band", state.toString(1))
        assertFalse(state.toBoolean(2))
        assertEquals(3L, state.toInteger(3))
        assertEquals("boolean-band", state.toString(4))
        assertEquals(3L, state.toInteger(5))
        assertFalse(state.toBoolean(6))
        assertEquals(2L, state.toInteger(7))
        assertFalse(state.toBoolean(8))
        assertTrue(
            state.toString(9)?.endsWith("attempt to perform bitwise operation on boolean") == true,
            state.toString(9),
        )
    }

    @Test
    fun `non-table concat metamethods handle source operators`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local booleanArgs = {}
                debug.setmetatable(false, {
                    __concat = function(left, right)
                        booleanArgs[#booleanArgs + 1] = { left, right }
                        return "boolean-concat"
                    end,
                })

                local leftResult = false .. "x"
                local rightResult = "x" .. false

                debug.setmetatable("", {
                    __concat = function()
                        return "string-concat"
                    end,
                })
                local primitiveStringResult = "x" .. 5

                debug.setmetatable(false, nil)
                debug.setmetatable("", nil)

                local missingOk, missingMessage = pcall(function()
                    return "x" .. true
                end)

                return leftResult, booleanArgs[1][1], booleanArgs[1][2],
                    rightResult, booleanArgs[2][1], booleanArgs[2][2],
                    primitiveStringResult,
                    missingOk, missingMessage
                """.trimIndent(),
                "non-table-concat-metamethods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("boolean-concat", state.toString(1))
        assertFalse(state.toBoolean(2))
        assertEquals("x", state.toString(3))
        assertEquals("boolean-concat", state.toString(4))
        assertEquals("x", state.toString(5))
        assertFalse(state.toBoolean(6))
        assertEquals("x5", state.toString(7))
        assertFalse(state.toBoolean(8))
        assertTrue(state.toString(9)?.endsWith("attempt to concatenate boolean") == true, state.toString(9))
    }

    @Test
    fun `non-table order metamethods handle source operators`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local booleanArgs = {}
                debug.setmetatable(false, {
                    __lt = function(left, right)
                        booleanArgs[#booleanArgs + 1] = { "__lt", left, right }
                        return true
                    end,
                    __le = function(left, right)
                        booleanArgs[#booleanArgs + 1] = { "__le", left, right }
                        return false
                    end,
                })

                local ltLeft = false < 7
                local ltRight = 7 < false
                local leLeft = false <= 7
                local leRight = 7 <= false

                debug.setmetatable(1, {
                    __lt = function()
                        return false
                    end,
                    __le = function()
                        return false
                    end,
                })
                debug.setmetatable("", {
                    __lt = function()
                        return false
                    end,
                    __le = function()
                        return false
                    end,
                })
                local primitiveNumberLt = 2 < 3
                local primitiveNumberLe = 2 <= 2
                local primitiveStringLt = "a" < "b"
                local primitiveStringLe = "a" <= "a"

                debug.setmetatable(false, nil)
                debug.setmetatable(1, nil)
                debug.setmetatable("", nil)

                local missingOk, missingMessage = pcall(function()
                    return true < {}
                end)

                return ltLeft, booleanArgs[1][1], booleanArgs[1][2], booleanArgs[1][3],
                    ltRight, booleanArgs[2][1], booleanArgs[2][2], booleanArgs[2][3],
                    leLeft, booleanArgs[3][1], booleanArgs[3][2], booleanArgs[3][3],
                    leRight, booleanArgs[4][1], booleanArgs[4][2], booleanArgs[4][3],
                    primitiveNumberLt, primitiveNumberLe, primitiveStringLt, primitiveStringLe,
                    missingOk, missingMessage
                """.trimIndent(),
                "non-table-order-metamethods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("__lt", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals(7L, state.toInteger(4))
        assertTrue(state.toBoolean(5))
        assertEquals("__lt", state.toString(6))
        assertEquals(7L, state.toInteger(7))
        assertFalse(state.toBoolean(8))
        assertFalse(state.toBoolean(9))
        assertEquals("__le", state.toString(10))
        assertFalse(state.toBoolean(11))
        assertEquals(7L, state.toInteger(12))
        assertFalse(state.toBoolean(13))
        assertEquals("__le", state.toString(14))
        assertEquals(7L, state.toInteger(15))
        assertFalse(state.toBoolean(16))
        assertTrue(state.toBoolean(17))
        assertTrue(state.toBoolean(18))
        assertTrue(state.toBoolean(19))
        assertTrue(state.toBoolean(20))
        assertFalse(state.toBoolean(21))
        assertTrue(state.toString(22)?.endsWith("attempt to compare boolean with table") == true, state.toString(22))
    }

    @Test
    fun `debug metatable functions validate arguments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local metatable = debug.getmetatable("text")
                local okMissing, missingMessage = pcall(debug.getmetatable)
                local explicitNil = debug.getmetatable(nil)
                local okValue, valueMessage = pcall(debug.setmetatable)
                local okMissingMeta, missingMetaMessage = pcall(debug.setmetatable, {})
                local okMeta, metaMessage = pcall(debug.setmetatable, {}, "not-table")
                return type(metatable),
                    metatable.__index == string,
                    okMissing,
                    missingMessage,
                    explicitNil,
                    okValue,
                    valueMessage,
                    okMissingMeta,
                    missingMetaMessage,
                    okMeta,
                    metaMessage
                """.trimIndent(),
                "debug-metatable-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table", state.toString(1))
        assertTrue(state.toBoolean(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'getmetatable' (value expected)", state.toString(4))
        assertTrue(state.isNil(5))
        assertFalse(state.toBoolean(6))
        assertEquals("bad argument #2 to 'setmetatable' (nil or table expected)", state.toString(7))
        assertFalse(state.toBoolean(8))
        assertEquals("bad argument #2 to 'setmetatable' (nil or table expected)", state.toString(9))
        assertFalse(state.toBoolean(10))
        assertEquals("bad argument #2 to 'setmetatable' (nil or table expected)", state.toString(11))
    }

    @Test
    fun `debug setmetatable requires metatable argument`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local object = {}
                local metatable = {}
                debug.setmetatable(object, metatable)
                local okMissing, missingMessage = pcall(debug.setmetatable, object)
                local okNone, noneMessage = pcall(debug.setmetatable)
                local okNil, returned = pcall(debug.setmetatable, object, nil)
                return okMissing, missingMessage,
                    okNone, noneMessage,
                    okNil, returned == object,
                    debug.getmetatable(object)
                """.trimIndent(),
                "debug-setmetatable-missing-meta-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'setmetatable' (nil or table expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'setmetatable' (nil or table expected)", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.toBoolean(6))
        assertTrue(state.isNil(7))
    }

    @Test
    fun `debug getmetatable reports missing argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okMissing, missingMessage = pcall(debug.getmetatable)
                local nilMetatable = debug.getmetatable(nil)
                return okMissing, missingMessage, nilMetatable
                """.trimIndent(),
                "debug-getmetatable-missing-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'getmetatable' (value expected)", state.toString(2))
        assertTrue(state.isNil(3))
    }

    @Test
    fun `debug uservalue functions store host userdata values`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushUserData(DebugHostObject("host"))
        state.setGlobal("host")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local initialValue, initialPresent = debug.getuservalue(host)
                local marker = {name = "marker"}
                local returned = debug.setuservalue(host, marker)
                local stored, storedPresent = debug.getuservalue(host)
                stored.extra = 42
                return initialValue, initialPresent,
                    returned == host,
                    stored == marker,
                    storedPresent,
                    marker.extra,
                    debug.getuservalue(host, 2),
                    debug.setuservalue(host, "ignored", 2) == host,
                    debug.getuservalue(host, 2)
                """.trimIndent(),
                "debug-uservalue.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertEquals(42L, state.toInteger(6))
        assertTrue(state.isNil(7))
        assertTrue(state.toBoolean(8))
        assertEquals("ignored", state.toString(9))
    }

    @Test
    fun `debug setmetatable stores host userdata metatables`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushUserData(DebugHostObject("host"))
        state.setGlobal("host")
        state.pushUserData(DebugHostObject("other"))
        state.setGlobal("other")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local metatable = {marker = "set"}
                local returned = debug.setmetatable(host, metatable)
                local hostMetatable = debug.getmetatable(host)
                local otherMetatable = debug.getmetatable(other)
                local cleared = debug.setmetatable(host, nil)
                return returned == host,
                    hostMetatable == metatable,
                    hostMetatable.marker,
                    otherMetatable,
                    cleared == host,
                    debug.getmetatable(host)
                """.trimIndent(),
                "debug-userdata-metatable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("set", state.toString(3))
        assertTrue(state.isNil(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.isNil(6))
    }

    @Test
    fun `debug setmetatable drives host userdata metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushUserData(DebugHostObject("host"))
        state.setGlobal("host")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local indexSelf, indexKey
                local writes = {}
                debug.setmetatable(host, {
                    __index = function(self, key)
                        indexSelf = self
                        indexKey = key
                        return "from-index"
                    end,
                    __newindex = function(self, key, value)
                        writes.self = self
                        writes.key = key
                        writes.value = value
                    end,
                })
                local read = host.answer
                host.extra = "stored"
                return read,
                    indexSelf == host,
                    indexKey,
                    writes.self == host,
                    writes.key,
                    writes.value
                """.trimIndent(),
                "debug-userdata-metamethods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("from-index", state.toString(1))
        assertTrue(state.toBoolean(2))
        assertEquals("answer", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertEquals("extra", state.toString(5))
        assertEquals("stored", state.toString(6))
    }

    @Test
    fun `debug setmetatable names host userdata index errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushUserData(DebugHostObject("named"))
        state.setGlobal("named")
        state.pushUserData(DebugHostObject("numeric"))
        state.setGlobal("numeric")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(named, {__name = "HostValue"})
                debug.setmetatable(numeric, {__name = 42})
                local readOk, readMessage = pcall(function()
                    return named.missing
                end)
                local writeOk, writeMessage = pcall(function()
                    named.missing = "value"
                end)
                local numericOk, numericMessage = pcall(function()
                    return numeric.missing
                end)
                return readOk, readMessage, writeOk, writeMessage, numericOk, numericMessage
                """.trimIndent(),
                "debug-userdata-name-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to index a HostValue value", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("attempt to index a HostValue value", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("attempt to index a userdata value", state.toString(6))
    }

    @Test
    fun `debug setmetatable drives host userdata call metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushUserData(DebugHostObject("callable"))
        state.setGlobal("callable")
        state.pushUserData(DebugHostObject("named"))
        state.setGlobal("named")
        state.pushUserData(DebugHostObject("numeric"))
        state.setGlobal("numeric")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local callSelf, callArgument
                debug.setmetatable(callable, {
                    __call = function(self, argument)
                        callSelf = self
                        callArgument = argument
                        return "called:" .. argument
                    end,
                })
                debug.setmetatable(named, {__name = "CallableHost"})
                debug.setmetatable(numeric, {__name = 42})
                local namedOk, namedMessage = pcall(function()
                    return named()
                end)
                local numericOk, numericMessage = pcall(function()
                    return numeric()
                end)
                return callable("value"),
                    callSelf == callable,
                    callArgument,
                    namedOk,
                    namedMessage,
                    numericOk,
                    numericMessage
                """.trimIndent(),
                "debug-userdata-call.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("called:value", state.toString(1))
        assertTrue(state.toBoolean(2))
        assertEquals("value", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("attempt to call a CallableHost value", state.toString(5))
        assertFalse(state.toBoolean(6))
        assertEquals("attempt to call a userdata value", state.toString(7))
    }

    @Test
    fun `debug setmetatable drives host userdata length metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushUserData(DebugHostObject("sized"))
        state.setGlobal("sized")
        state.pushUserData(DebugHostObject("named"))
        state.setGlobal("named")
        state.pushUserData(DebugHostObject("numeric"))
        state.setGlobal("numeric")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local lenSelf
                debug.setmetatable(sized, {
                    __len = function(self)
                        lenSelf = self
                        return 7
                    end,
                })
                debug.setmetatable(named, {__name = "SizedHost"})
                debug.setmetatable(numeric, {__name = 42})
                local namedOk, namedMessage = pcall(function()
                    return #named
                end)
                local numericOk, numericMessage = pcall(function()
                    return #numeric
                end)
                return #sized,
                    lenSelf == sized,
                    namedOk,
                    namedMessage,
                    numericOk,
                    numericMessage
                """.trimIndent(),
                "debug-userdata-len.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(7L, state.toInteger(1))
        assertTrue(state.toBoolean(2))
        assertFalse(state.toBoolean(3))
        assertEquals("attempt to get length of a SizedHost value", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("attempt to get length of a userdata value", state.toString(6))
    }

    @Test
    fun `debug setmetatable drives host userdata comparison metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        listOf(
            "eqLeft",
            "eqRight",
            "eqFallbackLeft",
            "eqFallbackRight",
            "ltLeft",
            "ltRight",
            "leLeft",
            "leRight",
        ).forEach { name ->
            state.pushUserData(DebugHostObject(name))
            state.setGlobal(name)
        }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local eqLeftSeen
                local eqRightSeen
                local ltSeen
                local leSeen
                debug.setmetatable(eqLeft, {
                    __eq = function(left, right)
                        eqLeftSeen = left == eqLeft and right == eqRight
                        return "truthy"
                    end,
                })
                debug.setmetatable(eqFallbackRight, {
                    __eq = function(left, right)
                        eqRightSeen = left == eqFallbackLeft and right == eqFallbackRight
                        return true
                    end,
                })
                debug.setmetatable(ltRight, {
                    __lt = function(left, right)
                        ltSeen = left == ltLeft and right == ltRight
                        return true
                    end,
                })
                debug.setmetatable(leLeft, {
                    __le = function(left, right)
                        leSeen = left == leLeft and right == leRight
                        return true
                    end,
                })
                return eqLeft == eqRight,
                    eqLeftSeen,
                    eqFallbackLeft == eqFallbackRight,
                    eqRightSeen,
                    ltLeft < ltRight,
                    ltSeen,
                    leLeft <= leRight,
                    leSeen,
                    eqLeft == eqLeft
                """.trimIndent(),
                "debug-userdata-comparison.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        for (index in 1..9) {
            assertTrue(state.toBoolean(index), "result #$index")
        }
    }

    @Test
    fun `debug setmetatable drives host userdata operator metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        listOf(
            "operatorLeft",
            "operatorRight",
            "concatLeft",
            "concatRight",
            "unaryValue",
            "bitwiseValue",
        ).forEach { name ->
            state.pushUserData(DebugHostObject(name))
            state.setGlobal(name)
        }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(operatorLeft, {
                    __add = function(left, right)
                        return left == operatorLeft and right == operatorRight and "add" or "bad-add"
                    end,
                })
                debug.setmetatable(operatorRight, {
                    __band = function(left, right)
                        return left == operatorLeft and right == operatorRight and "band" or "bad-band"
                    end,
                })
                debug.setmetatable(concatRight, {
                    __concat = function(left, right)
                        return left == concatLeft and right == concatRight and "concat" or "bad-concat"
                    end,
                })
                debug.setmetatable(unaryValue, {
                    __unm = function(left, right)
                        return left == unaryValue and right == unaryValue and "unm" or "bad-unm"
                    end,
                })
                debug.setmetatable(bitwiseValue, {
                    __bnot = function(left, right)
                        return left == bitwiseValue and right == bitwiseValue and "bnot" or "bad-bnot"
                    end,
                })
                return operatorLeft + operatorRight,
                    operatorLeft & operatorRight,
                    concatLeft .. concatRight,
                    -unaryValue,
                    ~bitwiseValue
                """.trimIndent(),
                "debug-userdata-operators.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("add", state.toString(1))
        assertEquals("band", state.toString(2))
        assertEquals("concat", state.toString(3))
        assertEquals("unm", state.toString(4))
        assertEquals("bnot", state.toString(5))
    }

    @Test
    fun `debug setmetatable names host userdata operator errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushUserData(DebugHostObject("named"))
        state.setGlobal("named")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(named, {__name = "NamedHost"})
                local addOk, addMessage = pcall(function()
                    return named + 1
                end)
                local bitwiseOk, bitwiseMessage = pcall(function()
                    return named & 1
                end)
                local concatOk, concatMessage = pcall(function()
                    return named .. 1
                end)
                local unmOk, unmMessage = pcall(function()
                    return -named
                end)
                local bnotOk, bnotMessage = pcall(function()
                    return ~named
                end)
                local compareOk, compareMessage = pcall(function()
                    return named < 1
                end)
                return addOk,
                    addMessage,
                    bitwiseOk,
                    bitwiseMessage,
                    concatOk,
                    concatMessage,
                    unmOk,
                    unmMessage,
                    bnotOk,
                    bnotMessage,
                    compareOk,
                    compareMessage
                """.trimIndent(),
                "debug-userdata-operator-names.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to perform arithmetic on a NamedHost value", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("attempt to perform bitwise operation on a NamedHost value", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("attempt to concatenate a NamedHost value", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("attempt to perform arithmetic on a NamedHost value", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("attempt to perform bitwise operation on a NamedHost value", state.toString(10))
        assertFalse(state.toBoolean(11))
        assertEquals("attempt to compare NamedHost with number", state.toString(12))
    }

    @Test
    fun `debug uservalue functions report lua style argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushUserData(DebugHostObject("error-host"))
        state.setGlobal("host")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local nonUserValue = debug.getuservalue({})
                local badGetIndexOk, badGetIndexMessage = pcall(debug.getuservalue, {}, "bad")
                local badSetTargetOk, badSetTargetMessage = pcall(debug.setuservalue, {}, "value")
                local missingValueOk, missingValueMessage = pcall(debug.setuservalue, host)
                return nonUserValue,
                    badGetIndexOk, badGetIndexMessage,
                    badSetTargetOk, badSetTargetMessage,
                    missingValueOk, missingValueMessage
                """.trimIndent(),
                "debug-uservalue-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertFalse(state.toBoolean(2))
        assertEquals("bad argument #2 to 'getuservalue' (number expected)", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("bad argument #1 to 'setuservalue' (userdata expected)", state.toString(5))
        assertFalse(state.toBoolean(6))
        assertEquals("bad argument #2 to 'setuservalue' (value expected)", state.toString(7))
    }

    @Test
    fun `debug getregistry returns stable mutable registry table`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first = debug.getregistry()
                local second = debug.getregistry()
                first.answer = 42
                second.label = "registry"
                return type(first), first == second, second.answer, first.label
                """.trimIndent(),
                "debug-registry.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table", state.toString(1))
        assertTrue(state.toBoolean(2))
        assertEquals(42L, state.toInteger(3))
        assertEquals("registry", state.toString(4))
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
    fun `getmetatable exposes installed string metatable`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local baseMetatable = getmetatable("")
                local debugMetatable = debug.getmetatable("x")
                return type(baseMetatable),
                    baseMetatable == debugMetatable,
                    baseMetatable.__index == string,
                    getmetatable(nil),
                    debug.getmetatable(nil)
                """.trimIndent(),
                "string-metatable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table", state.toString(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.isNil(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `string metatable index drives string field lookup`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local metatable = getmetatable("")
                local original = metatable.__index
                metatable.__index = {custom = 7}
                local custom = ("abc").custom
                metatable.__index = original
                return custom, ("abc"):len()
                """.trimIndent(),
                "string-metatable-index.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(7L, state.toInteger(1))
        assertEquals(3L, state.toInteger(2))
    }

    @Test
    fun `debug setmetatable clears and restores string metatable`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local original = debug.getmetatable("")
                debug.setmetatable("", nil)
                local missing = debug.getmetatable("")
                local ok, message = pcall(function()
                    return ("abc"):len()
                end)
                debug.setmetatable("", original)
                return missing, ok, type(message), ("abc"):len()
                """.trimIndent(),
                "string-metatable-clear.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertFalse(state.toBoolean(2))
        assertEquals("string", state.toString(3))
        assertEquals(3L, state.toInteger(4))
    }

    @Test
    fun `debug setmetatable tracks primitive type metatables`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numberMetatable = {name = "number"}
                local booleanMetatable = {name = "boolean"}
                local nilMetatable = {name = "nil"}
                local functionMetatable = {name = "function"}
                local threadMetatable = {name = "thread"}

                local numberReturned = debug.setmetatable(1, numberMetatable)
                local booleanReturned = debug.setmetatable(false, booleanMetatable)
                local nilReturned = debug.setmetatable(nil, nilMetatable)
                local f = function() end
                local functionReturned = debug.setmetatable(f, functionMetatable)
                local co = coroutine.create(function() end)
                local threadReturned = debug.setmetatable(co, threadMetatable)

                return numberReturned,
                    debug.getmetatable(2) == numberMetatable,
                    getmetatable(3) == numberMetatable,
                    booleanReturned == false,
                    debug.getmetatable(true) == booleanMetatable,
                    nilReturned,
                    debug.getmetatable(nil) == nilMetatable,
                    getmetatable(nil) == nilMetatable,
                    functionReturned == f,
                    debug.getmetatable(function() end) == functionMetatable,
                    threadReturned == co,
                    debug.getmetatable(coroutine.create(function() end)) == threadMetatable
                """.trimIndent(),
                "primitive-type-metatables.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(1L, state.toInteger(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.isNil(6))
        assertTrue(state.toBoolean(7))
        assertTrue(state.toBoolean(8))
        assertTrue(state.toBoolean(9))
        assertTrue(state.toBoolean(10))
        assertTrue(state.toBoolean(11))
        assertTrue(state.toBoolean(12))
    }

    @Test
    fun `debug setmetatable clears primitive type metatables`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(1, {name = "number"})
                local before = debug.getmetatable(2)
                local returned = debug.setmetatable(1, nil)
                local after = debug.getmetatable(2)
                return type(before), returned, after
                """.trimIndent(),
                "primitive-type-metatables-clear.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table", state.toString(1))
        assertEquals(1L, state.toInteger(2))
        assertTrue(state.isNil(3))
    }

    @Test
    fun `primitive type index metatables drive field and method lookup`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(1, {
                    __index = {
                        x = 42,
                        add = function(self, value)
                            return self + value
                        end,
                    },
                })
                debug.setmetatable(false, {
                    __index = function(self, key)
                        return tostring(self) .. ":" .. key
                    end,
                })
                debug.setmetatable(nil, {
                    __index = {
                        x = "nil-x",
                    },
                })
                local f = function() end
                debug.setmetatable(f, {
                    __index = type,
                })
                return (1).x,
                    (2):add(3),
                    (true).name,
                    (false).name,
                    (nil).x,
                    f.any
                """.trimIndent(),
                "primitive-index-metatables.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(5L, state.toInteger(2))
        assertEquals("true:name", state.toString(3))
        assertEquals("false:name", state.toString(4))
        assertEquals("nil-x", state.toString(5))
        assertEquals("function", state.toString(6))
    }

    @Test
    fun `table index metamethod accepts native functions`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local object = setmetatable({}, {
                    __index = type,
                })
                return object.any
                """.trimIndent(),
                "table-native-index-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table", state.toString(1))
    }

    @Test
    fun `primitive type newindex metatables handle assignments`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local writes = {}
                debug.setmetatable(1, {
                    __newindex = function(self, key, value)
                        writes[#writes + 1] = tostring(self) .. ":" .. key .. ":" .. value
                    end,
                })
                local n = 1
                n.x = "value"

                local target = {}
                debug.setmetatable(false, {
                    __newindex = target,
                })
                local b = true
                b.name = "truth"

                return writes[1], target.name
                """.trimIndent(),
                "primitive-newindex-metatables.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("1:x:value", state.toString(1))
        assertEquals("truth", state.toString(2))
    }

    @Test
    fun `table newindex metamethod accepts native functions`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local proxy = setmetatable({}, {
                    __newindex = rawset,
                })
                proxy.answer = 42
                return rawget(proxy, "answer")
                """.trimIndent(),
                "table-native-newindex-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(42L, state.toInteger(1))
    }

    @Test
    fun `primitive newindex reports missing and looping metamethod errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okNil, nilMessage = pcall(function()
                    local value = nil
                    value.x = 1
                end)
                debug.setmetatable(2, {
                    __newindex = 3,
                })
                local n = 2
                local okLoop, loopMessage = pcall(function()
                    n.x = 4
                end)
                return okNil, nilMessage, okLoop, loopMessage
                """.trimIndent(),
                "primitive-newindex-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertTrue(state.toString(2)?.contains("attempt to index nil") == true)
        assertFalse(state.toBoolean(3))
        assertTrue(state.toString(4)?.contains("'__newindex' chain too long; possible loop") == true)
    }

    @Test
    fun `primitive type operator metatables drive fallback operations`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(false, {
                    __add = function(left, right)
                        return tostring(left) .. ":" .. right
                    end,
                    __unm = function(value, copy)
                        return value == copy and "negated" or "wrong"
                    end,
                    __band = function(left, right)
                        return tostring(left) .. "&" .. right
                    end,
                    __bnot = function(value, copy)
                        return value == copy and "inverted" or "wrong"
                    end,
                    __lt = function(left, right)
                        return left == false and right == true
                    end,
                })
                debug.setmetatable(nil, {
                    __concat = function(left, right)
                        return type(left) .. "|" .. right
                    end,
                })
                return true + 5,
                    -true,
                    false & 7,
                    ~false,
                    false < true,
                    nil .. "x"
                """.trimIndent(),
                "primitive-operator-metatables.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("true:5", state.toString(1))
        assertEquals("negated", state.toString(2))
        assertEquals("false&7", state.toString(3))
        assertEquals("inverted", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertEquals("nil|x", state.toString(6))
    }

    @Test
    fun `operator metamethods accept native and callable values`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local left = setmetatable({}, {
                    __eq = type,
                })
                local right = {}
                local callable = setmetatable({name = "sum"}, {
                    __call = function(self, left, right)
                        return self.name .. ":" .. tostring(left) .. ":" .. right
                    end,
                })
                local value = setmetatable({}, {
                    __add = callable,
                })
                debug.setmetatable(false, {
                    __add = type,
                })
                return left == right, value + 3, true + 1
                """.trimIndent(),
                "operator-callable-native-metatables.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toString(2)?.startsWith("sum:table:") == true)
        assertEquals("boolean", state.toString(3))
    }

    @Test
    fun `direct operator paths bypass primitive type metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(1, {
                    __add = function()
                        return "bad-number"
                    end,
                })
                debug.setmetatable("", {
                    __concat = function()
                        return "bad-string"
                    end,
                })
                return 1 + 2, "a" .. "b"
                """.trimIndent(),
                "operator-direct-paths.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(3L, state.toInteger(1))
        assertEquals("ab", state.toString(2))
    }

    @Test
    fun `primitive type call metatables invoke callable values`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(1, {
                    __call = function(self, left, right)
                        return self + left + right
                    end,
                })
                debug.setmetatable("", {
                    __call = function(self, suffix)
                        return self .. suffix
                    end,
                })
                debug.setmetatable(false, {
                    __call = type,
                })
                return (1)(2, 3),
                    ("ab")("cd"),
                    (true)()
                """.trimIndent(),
                "primitive-call-metatables.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(6L, state.toInteger(1))
        assertEquals("abcd", state.toString(2))
        assertEquals("boolean", state.toString(3))
    }

    @Test
    fun `call metatables retry table-valued call metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local callable = {}
                setmetatable(callable, {
                    __call = function(self, original, value)
                        return self == callable, original, value
                    end,
                })
                debug.setmetatable(1, {
                    __call = callable,
                })
                return (1)("value")
                """.trimIndent(),
                "primitive-call-chain.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals("value", state.toString(3))
    }

    @Test
    fun `primitive call reports missing call metamethod errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okNil, nilMessage = pcall(function()
                    return (nil)()
                end)
                debug.setmetatable(2, {__call = {}})
                local okTable, tableMessage = pcall(function()
                    return (2)()
                end)
                return okNil, nilMessage, okTable, tableMessage
                """.trimIndent(),
                "primitive-call-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertTrue(state.toString(2)?.contains("attempt to call nil") == true)
        assertFalse(state.toBoolean(3))
        assertTrue(state.toString(4)?.contains("attempt to call table") == true)
    }

    @Test
    fun `primitive type len metatables drive length operator`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(1, {
                    __len = function(value)
                        return value + 10
                    end,
                })
                debug.setmetatable(false, {
                    __len = function(value)
                        return value and 1 or 0
                    end,
                })
                debug.setmetatable(nil, {
                    __len = function(value)
                        return type(value)
                    end,
                })
                debug.setmetatable(function() end, {
                    __len = function(value)
                        return type(value)
                    end,
                })
                return #1, #true, #false, #nil, #(function() end)
                """.trimIndent(),
                "primitive-len-metatables.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(11L, state.toInteger(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(0L, state.toInteger(3))
        assertEquals("nil", state.toString(4))
        assertEquals("function", state.toString(5))
    }

    @Test
    fun `len metamethod accepts native and callable table values`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local native = setmetatable({}, {
                    __len = type,
                })
                local callable = {}
                setmetatable(callable, {
                    __call = function(self, value)
                        return value[1]
                    end,
                })
                local chained = setmetatable({9}, {
                    __len = callable,
                })
                return #native, #chained
                """.trimIndent(),
                "len-callable-metatables.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table", state.toString(1))
        assertEquals(9L, state.toInteger(2))
    }

    @Test
    fun `string length ignores string len metatable`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local metatable = debug.getmetatable("")
                local originalLen = metatable.__len
                metatable.__len = function()
                    return 99
                end
                local length = #"abc"
                metatable.__len = originalLen
                return length
                """.trimIndent(),
                "string-len-raw.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(3L, state.toInteger(1))
    }

    @Test
    fun `getmetatable reports missing argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return getmetatable()""", "getmetatable-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'getmetatable' (value expected)", state.toString(-1))
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
    fun `setmetatable reports missing metatable argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return setmetatable({})""", "setmetatable-missing-meta-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'setmetatable' (nil or table expected)", state.toString(-1))
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
    fun `openLibs installs os library`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local now = os.time()
                return type(os), type(os.clock), type(os.date), type(os.difftime), type(os.getenv),
                    type(os.execute), type(os.remove), type(os.rename), type(os.setlocale),
                    type(os.time), type(os.tmpname),
                    type(now), os.difftime(now, now)
                """.trimIndent(),
                "open-libs-os.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table", state.toString(1))
        assertEquals("function", state.toString(2))
        assertEquals("function", state.toString(3))
        assertEquals("function", state.toString(4))
        assertEquals("function", state.toString(5))
        assertEquals("function", state.toString(6))
        assertEquals("function", state.toString(7))
        assertEquals("function", state.toString(8))
        assertEquals("function", state.toString(9))
        assertEquals("function", state.toString(10))
        assertEquals("function", state.toString(11))
        assertEquals("number", state.toString(12))
        assertEquals(0.0, state.toNumber(13) ?: error("missing difftime result"), 0.0)
    }

    @Test
    fun `openLibs installs coroutine library`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return type(coroutine), type(coroutine.close), type(coroutine.create),
                    type(coroutine.isyieldable), type(coroutine.resume),
                    type(coroutine.running), type(coroutine.status), type(coroutine.wrap),
                    type(coroutine.yield)
                """.trimIndent(),
                "open-libs-coroutine.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("table", state.toString(1))
        assertEquals("function", state.toString(2))
        assertEquals("function", state.toString(3))
        assertEquals("function", state.toString(4))
        assertEquals("function", state.toString(5))
        assertEquals("function", state.toString(6))
        assertEquals("function", state.toString(7))
        assertEquals("function", state.toString(8))
        assertEquals("function", state.toString(9))
    }

    @Test
    fun `os time converts and normalizes date tables`() {
        val state = LuaState.create()
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first = {year = 2020, month = 1, day = 1}
                local second = {year = 2020, month = 1, day = 2}
                local normalized = {year = 2020, month = 13, day = 32, hour = -1, min = 61, sec = 61}
                local firstTime = os.time(first)
                local secondTime = os.time(second)
                os.time(normalized)
                return os.difftime(secondTime, firstTime),
                    first.hour, first.min, first.sec,
                    first.wday ~= nil, first.yday,
                    normalized.year, normalized.month, normalized.day,
                    normalized.hour, normalized.min, normalized.sec
                """.trimIndent(),
                "os-time-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(86400.0, state.toNumber(1) ?: error("missing difftime result"), 0.0)
        assertEquals(12L, state.toInteger(2))
        assertEquals(0L, state.toInteger(3))
        assertEquals(0L, state.toInteger(4))
        assertTrue(state.toBoolean(5))
        assertEquals(1L, state.toInteger(6))
        assertEquals(2021L, state.toInteger(7))
        assertEquals(2L, state.toInteger(8))
        assertEquals(1L, state.toInteger(9))
        assertEquals(0L, state.toInteger(10))
        assertEquals(2L, state.toInteger(11))
        assertEquals(1L, state.toInteger(12))
    }

    @Test
    fun `os time accepts lua numeric string date fields`() {
        val state = LuaState.create()
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numeric = {year = 2020, month = 1, day = 2, hour = 3, min = 4, sec = 5}
                local strings = {year = "0x7e4", month = "0x1", day = "0x2", hour = "0x1.8p1", min = "+4", sec = "5.0"}
                local numericTime = os.time(numeric)
                local stringTime = os.time(strings)
                return os.difftime(stringTime, numericTime),
                    strings.year, strings.month, strings.day, strings.hour, strings.min, strings.sec
                """.trimIndent(),
                "os-time-numeric-string-fields.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(0.0, state.toNumber(1) ?: error("missing difftime result"), 0.0)
        assertEquals(2020L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(2L, state.toInteger(4))
        assertEquals(3L, state.toInteger(5))
        assertEquals(4L, state.toInteger(6))
        assertEquals(5L, state.toInteger(7))
    }

    @Test
    fun `os time rejects source sentinel result`() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val state = LuaState.create()
            LuaStdlib.openBase(state)
            LuaStdlib.openOs(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    local date = {year = 1969, month = 12, day = 31, hour = 23, min = 59, sec = 59}
                    local ok, message = pcall(os.time, date)
                    return ok, message
                    """.trimIndent(),
                    "os-time-sentinel-result.lua",
                ),
            )
            assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

            assertFalse(state.toBoolean(1))
            assertEquals("time result cannot be represented in this installation", state.toString(2))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `os time rejects out of range numeric string date fields`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return pcall(os.time, {year = "0x1p63", month = 1, day = 1})
                """.trimIndent(),
                "os-time-out-of-range-numeric-string-field.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("field 'year' is not an integer", state.toString(2))
    }

    @Test
    fun `os time reads date fields through index metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local fallback = {year = 2020, month = 1, day = 1}
                local tableBacked = setmetatable({}, {__index = fallback})
                local nestedTableBacked = setmetatable({}, {__index = setmetatable({}, {__index = fallback})})
                local calls = {}
                local functionBacked = setmetatable({}, {
                    __index = function(_, key)
                        calls[#calls + 1] = key
                        return fallback[key]
                    end,
                })
                local first = os.time(tableBacked)
                local second = os.time(nestedTableBacked)
                local third = os.time(functionBacked)
                return os.difftime(second, first), os.difftime(third, first), calls[1], calls[2], calls[3],
                    tableBacked.year, nestedTableBacked.year, functionBacked.year
                """.trimIndent(),
                "os-time-index-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(0.0, state.toNumber(1) ?: error("missing difftime result"), 0.0)
        assertEquals(0.0, state.toNumber(2) ?: error("missing nested difftime result"), 0.0)
        assertEquals("year", state.toString(3))
        assertEquals("month", state.toString(4))
        assertEquals("day", state.toString(5))
        assertEquals(2020L, state.toInteger(6))
        assertEquals(2020L, state.toInteger(7))
        assertEquals(2020L, state.toInteger(8))
    }

    @Test
    fun `os time honors isdst field for ambiguous local times`() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        try {
            val state = LuaState.create()
            LuaStdlib.openOs(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    local daylight = {year = 2021, month = 11, day = 7, hour = 1, min = 30, sec = 0, isdst = true}
                    local standard = {year = 2021, month = 11, day = 7, hour = 1, min = 30, sec = 0, isdst = false}
                    local daylightTime = os.time(daylight)
                    local standardTime = os.time(standard)
                    return daylightTime, standardTime, standardTime - daylightTime,
                        daylight.isdst, standard.isdst
                    """.trimIndent(),
                    "os-time-isdst.lua",
                ),
            )
            assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

            assertEquals(1636263000L, state.toInteger(1))
            assertEquals(1636266600L, state.toInteger(2))
            assertEquals(3600L, state.toInteger(3))
            assertTrue(state.toBoolean(4))
            assertFalse(state.toBoolean(5))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `os date returns source compatible utc date tables`() {
        val state = LuaState.create()
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local epoch = os.date("!*t", 0)
                local nextDay = os.date("!*t", 86400)
                return epoch.year, epoch.month, epoch.day,
                    epoch.hour, epoch.min, epoch.sec,
                    epoch.wday, epoch.yday, epoch.isdst,
                    nextDay.day, nextDay.wday, nextDay.yday
                """.trimIndent(),
                "os-date-utc-table.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(1970L, state.toInteger(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(0L, state.toInteger(4))
        assertEquals(0L, state.toInteger(5))
        assertEquals(0L, state.toInteger(6))
        assertEquals(5L, state.toInteger(7))
        assertEquals(1L, state.toInteger(8))
        assertFalse(state.toBoolean(9))
        assertEquals(2L, state.toInteger(10))
        assertEquals(6L, state.toInteger(11))
        assertEquals(2L, state.toInteger(12))
    }

    @Test
    fun `os date formats utc time strings with strftime directives`() {
        val state = LuaState.create()
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return os.date("!%Y-%m-%dT%H:%M:%S %w %u %j %U %W %%", 0),
                    os.date("!%F|%D|%R|%T", 0),
                    os.date("!%I:%M:%S %p", 47105),
                    os.date("!%EY|%Od", 0),
                    os.date("!%h", 0),
                    os.date("!%b", 0)
                """.trimIndent(),
                "os-date-format.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("1970-01-01T00:00:00 4 4 001 00 00 %", state.toString(1))
        assertEquals("1970-01-01|01/01/70|00:00|00:00:00", state.toString(2))
        assertEquals("01:05:05 PM", state.toString(3))
        assertEquals("1970|01", state.toString(4))
        assertEquals(state.toString(6), state.toString(5))
    }

    @Test
    fun `os date local table results roundtrip through os time`() {
        val state = LuaState.create()
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local source = {year = 2020, month = 1, day = 2, hour = 3, min = 4, sec = 5}
                local time = os.time(source)
                local date = os.date("*t", time)
                local roundtrip = os.time(date)
                return os.difftime(roundtrip, time),
                    date.year, date.month, date.day, date.hour, date.min, date.sec,
                    date.wday ~= nil, date.yday
                """.trimIndent(),
                "os-date-local-roundtrip.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(0.0, state.toNumber(1) ?: error("missing difftime result"), 0.0)
        assertEquals(2020L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(2L, state.toInteger(4))
        assertEquals(3L, state.toInteger(5))
        assertEquals(4L, state.toInteger(6))
        assertEquals(5L, state.toInteger(7))
        assertTrue(state.toBoolean(8))
        assertEquals(2L, state.toInteger(9))
    }

    @Test
    fun `os time normalization writes missing fields through newindex`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local writes = {}
                local date = setmetatable({
                    year = 2020,
                    month = 1,
                    day = 2,
                }, {
                    __newindex = function(_, key, value)
                        writes[key] = value
                    end,
                })
                local timestamp = os.time(date)
                local tableWrites = {}
                local tableDate = setmetatable({
                    year = 2020,
                    month = 1,
                    day = 2,
                }, {
                    __newindex = tableWrites,
                })
                os.time(tableDate)
                local nestedWrites = {}
                local proxy = setmetatable({}, {
                    __newindex = function(_, key, value)
                        nestedWrites[key] = value
                    end,
                })
                local nestedDate = setmetatable({
                    year = 2020,
                    month = 1,
                    day = 2,
                }, {
                    __newindex = proxy,
                })
                os.time(nestedDate)
                return type(timestamp),
                    date.year, date.month, date.day,
                    rawget(date, "hour"),
                    writes.hour, writes.min, writes.sec,
                    writes.wday ~= nil, writes.yday ~= nil, writes.isdst ~= nil,
                    rawget(tableDate, "hour"),
                    tableWrites.hour, tableWrites.min, tableWrites.sec,
                    tableWrites.wday ~= nil, tableWrites.yday ~= nil, tableWrites.isdst ~= nil,
                    rawget(proxy, "hour"),
                    nestedWrites.hour, nestedWrites.min, nestedWrites.sec,
                    nestedWrites.wday ~= nil, nestedWrites.yday ~= nil, nestedWrites.isdst ~= nil
                """.trimIndent(),
                "os-time-newindex-normalization.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("number", state.toString(1))
        assertEquals(2020L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(2L, state.toInteger(4))
        assertTrue(state.isNil(5))
        assertEquals(12L, state.toInteger(6))
        assertEquals(0L, state.toInteger(7))
        assertEquals(0L, state.toInteger(8))
        assertTrue(state.toBoolean(9))
        assertTrue(state.toBoolean(10))
        assertTrue(state.toBoolean(11))
        assertTrue(state.isNil(12))
        assertEquals(12L, state.toInteger(13))
        assertEquals(0L, state.toInteger(14))
        assertEquals(0L, state.toInteger(15))
        assertTrue(state.toBoolean(16))
        assertTrue(state.toBoolean(17))
        assertTrue(state.toBoolean(18))
        assertTrue(state.isNil(19))
        assertEquals(12L, state.toInteger(20))
        assertEquals(0L, state.toInteger(21))
        assertEquals(0L, state.toInteger(22))
        assertTrue(state.toBoolean(23))
        assertTrue(state.toBoolean(24))
        assertTrue(state.toBoolean(25))
    }

    @Test
    fun `os rename and remove operate on files`() {
        val root = Files.createTempDirectory("klua-os-files")
        val source = root.resolve("source.txt")
        val target = root.resolve("target.txt")
        Files.writeString(source, "payload")
        try {
            val state = LuaState.create()
            LuaStdlib.openOs(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    local renamed = os.rename("${source.luaPath()}", "${target.luaPath()}")
                    local removed = os.remove("${target.luaPath()}")
                    return renamed, removed
                    """.trimIndent(),
                    "os-rename-remove.lua",
                ),
            )
            assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

            assertTrue(state.toBoolean(1))
            assertTrue(state.toBoolean(2))
            assertFalse(Files.exists(source))
            assertFalse(Files.exists(target))
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(target)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun `os tmpname returns unique writable file names`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first = os.tmpname()
                local second = os.tmpname()
                return type(first), type(second), first ~= second, first, second
                """.trimIndent(),
                "os-tmpname.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        val first = Path.of(state.toString(4) ?: error("missing first tmpname"))
        val second = Path.of(state.toString(5) ?: error("missing second tmpname"))
        try {
            assertEquals("string", state.toString(1))
            assertEquals("string", state.toString(2))
            assertTrue(state.toBoolean(3))
            assertFalse(Files.exists(first))
            assertFalse(Files.exists(second))
            Files.writeString(first, "payload")
            assertTrue(Files.exists(first))
        } finally {
            Files.deleteIfExists(first)
            Files.deleteIfExists(second)
        }
    }

    @Test
    fun `os execute reports shell command statuses`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local shellAvailable = os.execute()
                local ok, okKind, okCode = os.execute("exit 0")
                local failed, failedKind, failedCode = os.execute("exit 7")
                local typeOk, typeMessage = pcall(os.execute, {})
                return shellAvailable,
                    ok, okKind, okCode,
                    failed, failedKind, failedCode,
                    typeOk, typeMessage
                """.trimIndent(),
                "os-execute.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("exit", state.toString(3))
        assertEquals(0L, state.toInteger(4))
        assertTrue(state.isNil(5))
        assertEquals("exit", state.toString(6))
        assertEquals(7L, state.toInteger(7))
        assertFalse(state.toBoolean(8))
        assertEquals("bad argument #1 to 'os.execute' (string expected)", state.toString(9))
    }

    @Test
    fun `os exit signals configured process exit without returning`() {
        val exits = mutableListOf<Pair<Int, Boolean>>()
        val state = LuaState.create(
            LuaConfig(
                exitHandler = LuaExitHandler { status, closeState ->
                    exits += status to closeState
                },
            ),
        )
        LuaStdlib.openBase(state)
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local caught = pcall(os.exit, false, true)
                return caught
                """.trimIndent(),
                "os-exit.lua",
            ),
        )
        val exit = assertFailsWith<LuaExitException> {
            state.pcall(0, -1)
        }

        assertEquals(1, exit.status)
        assertTrue(exit.closeState)
        assertEquals(listOf(1 to true), exits)
    }

    @Test
    fun `os exit maps integer status`() {
        val exits = mutableListOf<Pair<Int, Boolean>>()
        val state = LuaState.create(
            LuaConfig(
                exitHandler = LuaExitHandler { status, closeState ->
                    exits += status to closeState
                },
            ),
        )
        LuaStdlib.openBase(state)
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                os.exit(7)
                """.trimIndent(),
                "os-exit-status.lua",
            ),
        )
        val exit = assertFailsWith<LuaExitException> {
            state.pcall(0, -1)
        }

        assertEquals(7, exit.status)
        assertFalse(exit.closeState)
        assertEquals(listOf(7 to false), exits)
    }

    @Test
    fun `os exit reports invalid status arguments`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return pcall(os.exit, {})
                """.trimIndent(),
                "os-exit-invalid.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'os.exit' (number expected)", state.toString(2))
    }

    @Test
    fun `os setlocale queries and changes process locale`() {
        val originalLocale = Locale.getDefault()
        try {
            val state = LuaState.create()
            LuaStdlib.openBase(state)
            LuaStdlib.openOs(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    local before = os.setlocale(nil)
                    local timeCategory = os.setlocale(nil, "time")
                    local cLocale = os.setlocale("C", "all")
                    local afterC = os.setlocale(nil)
                    local rejected = os.setlocale("1-not-locale")
                    local restored = os.setlocale("")
                    return type(before), type(timeCategory), cLocale, afterC, rejected, type(restored)
                    """.trimIndent(),
                    "os-setlocale.lua",
                ),
            )
            assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

            assertEquals("string", state.toString(1))
            assertEquals("string", state.toString(2))
            assertEquals("C", state.toString(3))
            assertEquals("C", state.toString(4))
            assertTrue(state.isNil(5))
            assertEquals("string", state.toString(6))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `os time and difftime report argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openOs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local tableOk, tableMessage = pcall(os.time, "not-table")
                local missingOk, missingMessage = pcall(os.time, {month = 1, day = 1})
                local fieldOk, fieldMessage = pcall(os.time, {year = 2020, month = "jan", day = 1})
                local leftOk, leftMessage = pcall(os.difftime, "bad", 1)
                local rightOk, rightMessage = pcall(os.difftime, 1, 1.5)
                local missingEnv = os.getenv("KLUA_ENV_DOES_NOT_EXIST_0123456789")
                local envOk, envMessage = pcall(os.getenv, {})
                local dateFormatOk, dateFormatMessage = pcall(os.date, "%Q", 0)
                local dateTimeOk, dateTimeMessage = pcall(os.date, "!*t", "bad")
                local missingRemove, missingRemoveMessage, missingRemoveCode = os.remove("KLUA_OS_MISSING_FILE_0123456789")
                local missingRename, missingRenameMessage, missingRenameCode =
                    os.rename("KLUA_OS_MISSING_RENAME_0123456789", "KLUA_OS_MISSING_RENAME_TARGET_0123456789")
                local removeOk, removeMessage = pcall(os.remove, {})
                local renameOk, renameMessage = pcall(os.rename, "from")
                local localeArgOk, localeArgMessage = pcall(os.setlocale, false)
                local localeCategoryTypeOk, localeCategoryTypeMessage = pcall(os.setlocale, nil, false)
                local localeCategoryOk, localeCategoryMessage = pcall(os.setlocale, nil, "bad")
                return tableOk, tableMessage,
                    missingOk, missingMessage,
                    fieldOk, fieldMessage,
                    leftOk, leftMessage,
                    rightOk, rightMessage,
                    missingEnv,
                    envOk, envMessage,
                    dateFormatOk, dateFormatMessage,
                    dateTimeOk, dateTimeMessage,
                    missingRemove, missingRemoveMessage, missingRemoveCode,
                    missingRename, missingRenameMessage, missingRenameCode,
                    removeOk, removeMessage,
                    renameOk, renameMessage,
                    localeArgOk, localeArgMessage,
                    localeCategoryTypeOk, localeCategoryTypeMessage,
                    localeCategoryOk, localeCategoryMessage
                """.trimIndent(),
                "os-time-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'os.time' (table expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("field 'year' missing in date table", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("field 'month' is not an integer", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #1 to 'os.difftime' (number expected)", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("bad argument #2 to 'os.difftime' (number has no integer representation)", state.toString(10))
        assertTrue(state.isNil(11))
        assertFalse(state.toBoolean(12))
        assertEquals("bad argument #1 to 'os.getenv' (string expected)", state.toString(13))
        assertFalse(state.toBoolean(14))
        assertEquals("bad argument #1 to 'os.date' (invalid conversion specifier '%Q')", state.toString(15))
        assertFalse(state.toBoolean(16))
        assertEquals("bad argument #2 to 'os.date' (number expected)", state.toString(17))
        assertTrue(state.isNil(18))
        assertTrue(state.toString(19)?.contains("KLUA_OS_MISSING_FILE_0123456789") == true, state.toString(19))
        assertEquals(1L, state.toInteger(20))
        assertTrue(state.isNil(21))
        assertFalse(state.toString(22)?.contains("KLUA_OS_MISSING_RENAME_0123456789") == true, state.toString(22))
        assertFalse(state.toString(22)?.contains("KLUA_OS_MISSING_RENAME_TARGET_0123456789") == true, state.toString(22))
        assertEquals(1L, state.toInteger(23))
        assertFalse(state.toBoolean(24))
        assertEquals("bad argument #1 to 'os.remove' (string expected)", state.toString(25))
        assertFalse(state.toBoolean(26))
        assertEquals("bad argument #2 to 'os.rename' (string expected)", state.toString(27))
        assertFalse(state.toBoolean(28))
        assertEquals("bad argument #1 to 'os.setlocale' (string expected)", state.toString(29))
        assertFalse(state.toBoolean(30))
        assertEquals("bad argument #2 to 'os.setlocale' (string expected)", state.toString(31))
        assertFalse(state.toBoolean(32))
        assertEquals("bad argument #2 to 'os.setlocale' (invalid option 'bad')", state.toString(33))
    }

    @Test
    fun `coroutine create resume and status support non yielding functions`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co
                local payload = {name = "payload"}
                co = coroutine.create(function(left, right, value)
                    return coroutine.status(co), left + right, value == payload, value.name, "done"
                end)
                local before = coroutine.status(co)
                local ok, during, sum, samePayload, payloadName, marker =
                    coroutine.resume(co, 20, 22, payload)
                local after = coroutine.status(co)
                local againOk, againMessage = coroutine.resume(co)
                return before, ok, during, sum, samePayload, payloadName,
                    marker, after, againOk, againMessage
                """.trimIndent(),
                "coroutine-basic.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("suspended", state.toString(1))
        assertTrue(state.toBoolean(2))
        assertEquals("running", state.toString(3))
        assertEquals(42L, state.toInteger(4))
        assertTrue(state.toBoolean(5))
        assertEquals("payload", state.toString(6))
        assertEquals("done", state.toString(7))
        assertEquals("dead", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("cannot resume dead coroutine", state.toString(10))
    }

    @Test
    fun `coroutine resume reports protected failures`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    error("boom")
                end)
                local ok, message = coroutine.resume(co)
                local closeOk, closeMessage = coroutine.close(co)
                local closeAgainOk = coroutine.close(co)
                local marker = {name = "marker"}
                local objectCo = coroutine.create(function()
                    error(marker)
                end)
                local objectOk, objectError = coroutine.resume(objectCo)
                local objectCloseOk, objectCloseError = coroutine.close(objectCo)
                local objectCloseAgainOk = coroutine.close(objectCo)
                return ok, message, coroutine.status(co),
                    closeOk, closeMessage, closeAgainOk,
                    objectOk, objectError == marker, objectError.name, coroutine.status(objectCo),
                    objectCloseOk, objectCloseError == marker, objectCloseError.name, objectCloseAgainOk
                """.trimIndent(),
                "coroutine-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("[string \"coroutine-error.lua\"]:2: boom", state.toString(2))
        assertEquals("dead", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("[string \"coroutine-error.lua\"]:2: boom", state.toString(5))
        assertTrue(state.toBoolean(6))
        assertFalse(state.toBoolean(7))
        assertTrue(state.toBoolean(8))
        assertEquals("marker", state.toString(9))
        assertEquals("dead", state.toString(10))
        assertFalse(state.toBoolean(11))
        assertTrue(state.toBoolean(12))
        assertEquals("marker", state.toString(13))
        assertTrue(state.toBoolean(14))
    }

    @Test
    fun `coroutine resume preserves lua error objects`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local marker = {name = "marker"}
                local co = coroutine.create(function()
                    error(marker)
                end)
                local ok, err = coroutine.resume(co)
                return ok, err == marker, err.name, coroutine.status(co)
                """.trimIndent(),
                "coroutine-error-object.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("marker", state.toString(3))
        assertEquals("dead", state.toString(4))
    }

    @Test
    fun `coroutine resume preserves host error objects`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)
        state.register("hostError") { context ->
            throw LuaRuntimeException(
                "table",
                errorObject = context.getTable(1),
                hasErrorObject = true,
            )
        }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local marker = {name = "host-marker"}
                local co = coroutine.create(hostError)
                local ok, err = coroutine.resume(co, marker)
                local closeOk, closeError = coroutine.close(co)
                local secondCloseOk = coroutine.close(co)
                return ok, err == marker, err.name, coroutine.status(co),
                    closeOk, closeError == marker, closeError.name, secondCloseOk
                """.trimIndent(),
                "coroutine-host-error-object.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("host-marker", state.toString(3))
        assertEquals("dead", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertTrue(state.toBoolean(6))
        assertEquals("host-marker", state.toString(7))
        assertTrue(state.toBoolean(8))
    }

    @Test
    fun `coroutine close reports terminal resume errors once`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local stringCo = coroutine.create(function()
                    error("boom")
                end)
                local resumeOk, resumeMessage = coroutine.resume(stringCo)
                local closeOk, closeMessage = coroutine.close(stringCo)
                local secondCloseOk = coroutine.close(stringCo)

                local marker = {name = "marker"}
                local tableCo = coroutine.create(function()
                    error(marker)
                end)
                local tableResumeOk, tableResumeError = coroutine.resume(tableCo)
                local tableCloseOk, tableCloseError = coroutine.close(tableCo)
                local tableSecondCloseOk = coroutine.close(tableCo)

                local nilCo = coroutine.create(function()
                    error(nil)
                end)
                local nilResumeOk, nilResumeError = coroutine.resume(nilCo)
                local nilCloseOk, nilCloseError = coroutine.close(nilCo)
                local nilSecondCloseOk = coroutine.close(nilCo)

                local falseCo = coroutine.create(function()
                    error(false)
                end)
                local falseResumeOk, falseResumeError = coroutine.resume(falseCo)
                local falseCloseOk, falseCloseError = coroutine.close(falseCo)
                local falseSecondCloseOk = coroutine.close(falseCo)

                return resumeOk, resumeMessage, closeOk, closeMessage, secondCloseOk,
                    tableResumeOk, tableResumeError == marker, tableResumeError.name,
                    tableCloseOk, tableCloseError == marker, tableCloseError.name,
                    tableSecondCloseOk,
                    nilResumeOk, nilResumeError,
                    nilCloseOk, nilCloseError,
                    nilSecondCloseOk,
                    falseResumeOk, falseResumeError,
                    falseCloseOk, falseCloseError,
                    falseSecondCloseOk
                """.trimIndent(),
                "coroutine-close-terminal-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertTrue(state.toString(2)?.startsWith("""[string "coroutine-close-terminal-error.lua"]:""") == true, state.toString(2))
        assertTrue(state.toString(2)?.endsWith(": boom") == true, state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals(state.toString(2), state.toString(4))
        assertTrue(state.toBoolean(5))
        assertFalse(state.toBoolean(6))
        assertTrue(state.toBoolean(7))
        assertEquals("marker", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertTrue(state.toBoolean(10))
        assertEquals("marker", state.toString(11))
        assertTrue(state.toBoolean(12))
        assertFalse(state.toBoolean(13))
        assertTrue(state.isNil(14))
        assertFalse(state.toBoolean(15))
        assertTrue(state.isNil(16))
        assertTrue(state.toBoolean(17))
        assertFalse(state.toBoolean(18))
        assertFalse(state.toBoolean(19))
        assertFalse(state.toBoolean(20))
        assertFalse(state.toBoolean(21))
        assertTrue(state.toBoolean(22))
    }

    @Test
    fun `coroutine resume rejects normal coroutines`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local outer
                outer = coroutine.create(function()
                    local inner = coroutine.create(function()
                        local ok, message = coroutine.resume(outer)
                        return ok, message, coroutine.status(outer)
                    end)
                    return coroutine.resume(inner)
                end)
                local outerOk, innerOk, resumeOk, message, outerStatusDuringInner =
                    coroutine.resume(outer)
                return outerOk, innerOk, resumeOk, message,
                    outerStatusDuringInner, coroutine.status(outer)
                """.trimIndent(),
                "coroutine-resume-normal.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertFalse(state.toBoolean(3))
        assertEquals("cannot resume non-suspended coroutine", state.toString(4))
        assertEquals("normal", state.toString(5))
        assertEquals("dead", state.toString(6))
    }

    @Test
    fun `coroutine wrap resumes yielding functions and raises failures`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local payload = {name = "wrapped"}
                local wrapped = coroutine.wrap(function(left, right, value)
                    local resumeValue = coroutine.yield(left + right, value == payload, value.name)
                    return resumeValue
                end)
                local sum, samePayload, payloadName = wrapped(20, 22, payload)
                local resumeValue = wrapped("done")
                local deadOk, deadMessage = pcall(wrapped)
                local function callDead()
                    return wrapped()
                end
                local nestedDeadOk, nestedDeadMessage = pcall(callDead)

                local failing = coroutine.wrap(function()
                    error("boom")
                end)
                local failOk, failMessage = pcall(failing)
                local failingNested = coroutine.wrap(function()
                    error("boom")
                end)
                local function callFail()
                    return failingNested()
                end
                local nestedFailOk, nestedFailMessage = pcall(callFail)
                local marker = {name = "marker"}
                local failingObject = coroutine.wrap(function()
                    error(marker)
                end)
                local objectFailOk, objectError = pcall(failingObject)
                local nilFailing = coroutine.wrap(function()
                    error(nil)
                end)
                local nilFailOk, nilFailMessage = pcall(nilFailing)
                local falseFailing = coroutine.wrap(function()
                    error(false)
                end)
                local falseFailOk, falseFailMessage = pcall(falseFailing)
                local located = coroutine.wrap(function()
                    error("located")
                end)
                local function callLocated()
                    return located()
                end
                local locatedOk, locatedMessage = pcall(callLocated)
                return sum, samePayload, payloadName, resumeValue,
                    deadOk, deadMessage, nestedDeadOk, nestedDeadMessage,
                    failOk, failMessage, nestedFailOk, nestedFailMessage,
                    objectFailOk, objectError == marker, objectError.name,
                    nilFailOk, nilFailMessage, falseFailOk, falseFailMessage,
                    locatedOk, locatedMessage
                """.trimIndent(),
                "coroutine-wrap.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(42L, state.toInteger(1))
        assertTrue(state.toBoolean(2))
        assertEquals("wrapped", state.toString(3))
        assertEquals("done", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertTrue(state.toString(6)?.startsWith("""[string "coroutine-wrap.lua"]:""") == true, state.toString(6))
        assertTrue(state.toString(6)?.endsWith(": cannot resume dead coroutine") == true, state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("[string \"coroutine-wrap.lua\"]:10: cannot resume dead coroutine", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("[string \"coroutine-wrap.lua\"]:14: [string \"coroutine-wrap.lua\"]:15: boom", state.toString(10))
        assertFalse(state.toBoolean(11))
        assertEquals("[string \"coroutine-wrap.lua\"]:22: [string \"coroutine-wrap.lua\"]:19: boom", state.toString(12))
        assertFalse(state.toBoolean(13))
        assertTrue(state.toBoolean(14))
        assertEquals("marker", state.toString(15))
        assertFalse(state.toBoolean(16))
        assertTrue(state.isNil(17))
        assertFalse(state.toBoolean(18))
        assertFalse(state.toBoolean(19))
        assertFalse(state.toBoolean(20))
        assertTrue(state.toString(21)?.startsWith("""[string "coroutine-wrap.lua"]:""") == true, state.toString(21))
        assertTrue(state.toString(21)?.endsWith(": located") == true, state.toString(21))
    }

    @Test
    fun `coroutine resume and close normalize nil error objects`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    error(nil)
                end)
                local ok, err = coroutine.resume(co)
                local closeOk, closeErr = coroutine.close(co)
                return ok, err, closeOk, closeErr
                """.trimIndent(),
                "coroutine-nil-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertTrue(state.isNil(2))
        assertFalse(state.toBoolean(3))
        assertTrue(state.isNil(4))
    }

    @Test
    fun `coroutine wrap preserves nil holes in yield and return values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function pack(...)
                    return select("#", ...), ...
                end

                local wrapped = coroutine.wrap(function()
                    local first, second, third = coroutine.yield(nil, "middle", nil)
                    return first, second, third
                end)

                local yieldCount, yieldFirst, yieldSecond, yieldThird = pack(wrapped())
                local returnCount, returnFirst, returnSecond, returnThird = pack(wrapped(nil, "done", nil))
                return yieldCount, yieldFirst == nil, yieldSecond, yieldThird == nil,
                    returnCount, returnFirst == nil, returnSecond, returnThird == nil
                """.trimIndent(),
                "coroutine-wrap-nil-holes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3L, state.toInteger(1))
        assertTrue(state.toBoolean(2))
        assertEquals("middle", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertEquals(3L, state.toInteger(5))
        assertTrue(state.toBoolean(6))
        assertEquals("done", state.toString(7))
        assertTrue(state.toBoolean(8))
    }

    @Test
    fun `coroutine running reports main and active coroutine contexts`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local mainThread, mainIsMain = coroutine.running()
                local co
                co = coroutine.create(function()
                    local running, isMain = coroutine.running()
                    return running == co, isMain
                end)
                local ok, sameThread, coroutineIsMain = coroutine.resume(co)
                local afterThread, afterIsMain = coroutine.running()
                return mainThread ~= nil, mainIsMain, ok, sameThread, coroutineIsMain,
                    afterThread == mainThread, afterIsMain, coroutine.status(mainThread),
                    coroutine.isyieldable(mainThread)
                """.trimIndent(),
                "coroutine-running.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertFalse(state.toBoolean(5))
        assertTrue(state.toBoolean(6))
        assertTrue(state.toBoolean(7))
        assertEquals("running", state.toString(8))
        assertFalse(state.toBoolean(9))
    }

    @Test
    fun `coroutine isyieldable reports explicit dead coroutines as not yieldable`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    coroutine.yield("pause")
                    return "done"
                end)
                local before = coroutine.isyieldable(co)
                local firstOk, yielded = coroutine.resume(co)
                local suspended = coroutine.isyieldable(co)
                local secondOk, returned = coroutine.resume(co)
                local dead = coroutine.isyieldable(co)
                local closed = coroutine.close(co)
                local closedDead = coroutine.isyieldable(co)
                return before, firstOk, yielded, suspended, secondOk, returned, dead, closed, closedDead
                """.trimIndent(),
                "coroutine-isyieldable-dead.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("pause", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertEquals("done", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertTrue(state.toBoolean(8))
        assertFalse(state.toBoolean(9))
    }

    @Test
    fun `coroutine status reports main thread as normal inside resumed coroutine`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local mainThread = coroutine.running()
                local beforeStatus = coroutine.status(mainThread)
                local defaultCloseOk, defaultCloseMessage = pcall(coroutine.close)
                local beforeCloseOk, beforeCloseMessage = pcall(coroutine.close, mainThread)
                local co = coroutine.create(function()
                    local running, isMain = coroutine.running()
                    local mainStatus = coroutine.status(mainThread)
                    local closeOk, closeMessage = pcall(coroutine.close, mainThread)
                    return running ~= mainThread, isMain, mainStatus, closeOk, closeMessage
                end)
                local ok, differentThread, coroutineIsMain, duringStatus, duringCloseOk, duringCloseMessage =
                    coroutine.resume(co)
                return beforeStatus, defaultCloseOk, defaultCloseMessage, beforeCloseOk, beforeCloseMessage,
                    ok, differentThread, coroutineIsMain, duringStatus, duringCloseOk, duringCloseMessage,
                    coroutine.status(mainThread)
                """.trimIndent(),
                "coroutine-main-status-normal.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("running", state.toString(1))
        assertFalse(state.toBoolean(2))
        assertEquals("cannot close main thread", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("cannot close main thread", state.toString(5))
        assertTrue(state.toBoolean(6))
        assertTrue(state.toBoolean(7))
        assertFalse(state.toBoolean(8))
        assertEquals("normal", state.toString(9))
        assertFalse(state.toBoolean(10))
        assertEquals("cannot close a normal coroutine", state.toString(11))
        assertEquals("running", state.toString(12))
    }

    @Test
    fun `type reports coroutine threads`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local mainThread = coroutine.running()
                local co = coroutine.create(function() end)
                return type(mainThread), type(co)
                """.trimIndent(),
                "coroutine-type.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("thread", state.toString(1))
        assertEquals("thread", state.toString(2))
    }

    @Test
    fun `tostring reports coroutine thread identity`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local mainThread = coroutine.running()
                local co = coroutine.create(function() end)
                return tostring(mainThread), tostring(co)
                """.trimIndent(),
                "coroutine-tostring.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toString(1)?.startsWith("thread: ") == true)
        assertTrue(state.toString(2)?.startsWith("thread: ") == true)
    }

    @Test
    fun `coroutine yield suspends and resumes lua functions`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local outsideOk, outsideMessage = pcall(coroutine.yield, "outside")
                local co = coroutine.create(function()
                    local resumeValue = coroutine.yield("inside")
                    return resumeValue
                end)
                local insideOk, yielded = coroutine.resume(co)
                local suspended = coroutine.status(co)
                local resumeOk, returned = coroutine.resume(co, "after")
                local after = coroutine.status(co)
                return outsideOk, outsideMessage, insideOk, yielded, suspended,
                    resumeOk, returned, after
                """.trimIndent(),
                "coroutine-yield.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to yield from outside a coroutine", state.toString(2))
        assertTrue(state.toBoolean(3))
        assertEquals("inside", state.toString(4))
        assertEquals("suspended", state.toString(5))
        assertTrue(state.toBoolean(6))
        assertEquals("after", state.toString(7))
        assertEquals("dead", state.toString(8))
    }

    @Test
    fun `coroutine resume rejects plain host function yield`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)
        state.register("hostYield") { context ->
            context.yield((1..context.argumentCount).map { index -> context.get(index) })
        }

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    return hostYield("host")
                end)
                local ok, message = coroutine.resume(co)
                return ok, message, coroutine.status(co)
                """.trimIndent(),
                "coroutine-host-yield-boundary.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to yield across a C-call boundary", state.toString(2))
        assertEquals("dead", state.toString(3))
    }

    @Test
    fun `coroutine resume supports explicitly yieldable host function bodies`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)
        state.register(
            "hostYield",
            LuaYieldableFunction { context ->
                context.yield((1..context.argumentCount).map { index -> context.get(index) })
            },
        )

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(hostYield)
                local firstOk, yielded = coroutine.resume(co, "host")
                local statusAfterYield = coroutine.status(co)
                local secondOk, returned = coroutine.resume(co, "done")
                return firstOk, yielded, statusAfterYield, secondOk, returned, coroutine.status(co)
                """.trimIndent(),
                "coroutine-yieldable-host.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("host", state.toString(2))
        assertEquals("suspended", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertEquals("done", state.toString(5))
        assertEquals("dead", state.toString(6))
    }

    @Test
    fun `coroutine resume supports host function continuations that yield again`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)
        state.register(
            "hostYieldTwice",
            LuaYieldableFunction { context ->
                try {
                    context.yield(listOf("first", context.get(1)))
                } catch (yield: LuaYieldException) {
                    throw yield.withContinuation { firstResume ->
                        try {
                            context.yield(listOf("second", firstResume.firstOrNull()))
                        } catch (nextYield: LuaYieldException) {
                            throw nextYield.withContinuation { secondResume ->
                                LuaReturn.of("done", secondResume.firstOrNull())
                            }
                        }
                    }
                }
            },
        )

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(hostYieldTwice)
                local firstOk, firstMarker, firstArg = coroutine.resume(co, "start")
                local statusAfterFirstYield = coroutine.status(co)
                local secondOk, secondMarker, secondArg = coroutine.resume(co, "middle")
                local statusAfterSecondYield = coroutine.status(co)
                local finalOk, doneMarker, finalArg = coroutine.resume(co, "finish")
                return firstOk, firstMarker, firstArg, statusAfterFirstYield,
                    secondOk, secondMarker, secondArg, statusAfterSecondYield,
                    finalOk, doneMarker, finalArg, coroutine.status(co)
                """.trimIndent(),
                "coroutine-yieldable-host-continuation.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("first", state.toString(2))
        assertEquals("start", state.toString(3))
        assertEquals("suspended", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertEquals("second", state.toString(6))
        assertEquals("middle", state.toString(7))
        assertEquals("suspended", state.toString(8))
        assertTrue(state.toBoolean(9))
        assertEquals("done", state.toString(10))
        assertEquals("finish", state.toString(11))
        assertEquals("dead", state.toString(12))
    }

    @Test
    fun `nested coroutines restore running coroutine after inner resume`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local outer
                outer = coroutine.create(function()
                    local beforeRunning = coroutine.running()
                    local inner
                    inner = coroutine.create(function()
                        local innerRunning = coroutine.running()
                        coroutine.yield(innerRunning == inner, coroutine.status(outer))
                        return coroutine.running() == inner, coroutine.status(outer)
                    end)
                    local firstOk, innerMatched, outerStatusAtYield = coroutine.resume(inner)
                    local afterInnerYield = coroutine.running() == outer
                    local secondOk, innerReturned, outerStatusAtReturn = coroutine.resume(inner)
                    local afterInnerReturn = coroutine.running() == outer
                    local resumeValue = coroutine.yield(
                        beforeRunning == outer,
                        firstOk,
                        innerMatched,
                        outerStatusAtYield,
                        afterInnerYield,
                        secondOk,
                        innerReturned,
                        outerStatusAtReturn,
                        afterInnerReturn,
                        coroutine.status(inner)
                    )
                    return resumeValue
                end)

                local yieldedOk,
                    yieldedBeforeRunning,
                    yieldedFirstOk,
                    yieldedInnerMatched,
                    yieldedOuterStatusAtYield,
                    yieldedAfterInnerYield,
                    yieldedSecondOk,
                    yieldedInnerReturned,
                    yieldedOuterStatusAtReturn,
                    yieldedAfterInnerReturn,
                    yieldedInnerStatus = coroutine.resume(outer)
                local outerStatus = coroutine.status(outer)
                local returnedOk, returnedValue = coroutine.resume(outer, "done")
                return yieldedOk, yieldedBeforeRunning, yieldedFirstOk,
                    yieldedInnerMatched, yieldedOuterStatusAtYield,
                    yieldedAfterInnerYield, yieldedSecondOk, yieldedInnerReturned,
                    yieldedOuterStatusAtReturn, yieldedAfterInnerReturn,
                    yieldedInnerStatus, outerStatus, returnedOk, returnedValue,
                    coroutine.status(outer)
                """.trimIndent(),
                "coroutine-nested.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertEquals("normal", state.toString(5))
        assertTrue(state.toBoolean(6))
        assertTrue(state.toBoolean(7))
        assertTrue(state.toBoolean(8))
        assertEquals("normal", state.toString(9))
        assertTrue(state.toBoolean(10))
        assertEquals("dead", state.toString(11))
        assertEquals("suspended", state.toString(12))
        assertTrue(state.toBoolean(13))
        assertEquals("done", state.toString(14))
        assertEquals("dead", state.toString(15))
    }

    @Test
    fun `coroutine yield resumes across nested lua frames`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function bounce(seed)
                    local resumed = coroutine.yield("first", seed)
                    return coroutine.yield("second", resumed)
                end

                local co = coroutine.create(function(seed)
                    local final = bounce(seed)
                    return "done", final
                end)

                local firstOk, firstMarker, firstSeed = coroutine.resume(co, "seed")
                local firstStatus = coroutine.status(co)
                local secondOk, secondMarker, secondResume = coroutine.resume(co, "resume-one")
                local secondStatus = coroutine.status(co)
                local finalOk, doneMarker, finalResume = coroutine.resume(co, "resume-two")
                return firstOk, firstMarker, firstSeed, firstStatus,
                    secondOk, secondMarker, secondResume, secondStatus,
                    finalOk, doneMarker, finalResume, coroutine.status(co)
                """.trimIndent(),
                "coroutine-multiple-yield.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("first", state.toString(2))
        assertEquals("seed", state.toString(3))
        assertEquals("suspended", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertEquals("second", state.toString(6))
        assertEquals("resume-one", state.toString(7))
        assertEquals("suspended", state.toString(8))
        assertTrue(state.toBoolean(9))
        assertEquals("done", state.toString(10))
        assertEquals("resume-two", state.toString(11))
        assertEquals("dead", state.toString(12))
    }

    @Test
    fun `coroutine resume preserves nil holes in yield and return values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function packResume(...)
                    return select("#", ...), ...
                end

                local co = coroutine.create(function()
                    local first, second, third = coroutine.yield(nil, "middle", nil)
                    return first, second, third
                end)

                local yieldCount, yieldOk, yieldFirst, yieldSecond, yieldThird = packResume(coroutine.resume(co))
                local returnCount, returnOk, returnFirst, returnSecond, returnThird =
                    packResume(coroutine.resume(co, nil, "done", nil))
                return yieldCount, yieldOk, yieldFirst == nil, yieldSecond, yieldThird == nil,
                    returnCount, returnOk, returnFirst == nil, returnSecond, returnThird == nil,
                    coroutine.status(co)
                """.trimIndent(),
                "coroutine-nil-holes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(4L, state.toInteger(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertEquals("middle", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertEquals(4L, state.toInteger(6))
        assertTrue(state.toBoolean(7))
        assertTrue(state.toBoolean(8))
        assertEquals("done", state.toString(9))
        assertTrue(state.toBoolean(10))
        assertEquals("dead", state.toString(11))
    }

    @Test
    fun `coroutine close marks suspended and dead coroutines dead`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local suspended = coroutine.create(function()
                    coroutine.yield("pause")
                    return "unreachable"
                end)
                local firstOk, yielded = coroutine.resume(suspended)
                local beforeClose = coroutine.status(suspended)
                local closeOk = coroutine.close(suspended)
                local afterClose = coroutine.status(suspended)
                local resumeOk, resumeMessage = coroutine.resume(suspended)

                local dead = coroutine.create(function()
                    return "done"
                end)
                local deadResumeOk = coroutine.resume(dead)
                local deadBeforeClose = coroutine.status(dead)
                local deadCloseOk = coroutine.close(dead)
                local deadAfterClose = coroutine.status(dead)

                local fresh = coroutine.create(function()
                    return "unreachable"
                end)
                local freshBeforeClose = coroutine.status(fresh)
                local freshCloseOk = coroutine.close(fresh)
                local freshAfterClose = coroutine.status(fresh)
                local freshResumeOk, freshResumeMessage = coroutine.resume(fresh)

                return firstOk, yielded, beforeClose, closeOk, afterClose,
                    resumeOk, resumeMessage, deadResumeOk, deadBeforeClose,
                    deadCloseOk, deadAfterClose, freshBeforeClose, freshCloseOk,
                    freshAfterClose, freshResumeOk, freshResumeMessage
                """.trimIndent(),
                "coroutine-close.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertEquals("pause", state.toString(2))
        assertEquals("suspended", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertEquals("dead", state.toString(5))
        assertFalse(state.toBoolean(6))
        assertEquals("cannot resume dead coroutine", state.toString(7))
        assertTrue(state.toBoolean(8))
        assertEquals("dead", state.toString(9))
        assertTrue(state.toBoolean(10))
        assertEquals("dead", state.toString(11))
        assertEquals("suspended", state.toString(12))
        assertTrue(state.toBoolean(13))
        assertEquals("dead", state.toString(14))
        assertFalse(state.toBoolean(15))
        assertEquals("cannot resume dead coroutine", state.toString(16))
    }

    @Test
    fun `coroutine close reports suspended host continuation failures`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)
        state.register(
            "hostYieldThenFail",
            LuaYieldableFunction { context ->
                try {
                    context.yield(listOf("pause"))
                } catch (yield: LuaYieldException) {
                    throw yield.withContinuation {
                        throw LuaRuntimeException("close boom")
                    }
                }
            },
        )

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(hostYieldThenFail)
                local resumeOk, yielded = coroutine.resume(co)
                local closeOk, closeMessage = coroutine.close(co)
                return resumeOk, yielded, closeOk, closeMessage, coroutine.status(co),
                    coroutine.resume(co)
                """.trimIndent(),
                "coroutine-close-host-failure.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("pause", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("close boom", state.toString(4))
        assertEquals("dead", state.toString(5))
        assertFalse(state.toBoolean(6))
        assertEquals("cannot resume dead coroutine", state.toString(7))
    }

    @Test
    fun `coroutine close completes suspended host continuations`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)
        val closed = mutableListOf<String>()
        state.register(
            "hostYieldThenClose",
            LuaYieldableFunction { context ->
                try {
                    context.yield(listOf("pause"))
                } catch (yield: LuaYieldException) {
                    throw yield.withContinuation {
                        closed += "closed"
                        LuaReturn.none()
                    }
                }
            },
        )

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(hostYieldThenClose)
                local resumeOk, yielded = coroutine.resume(co)
                local closeOk = coroutine.close(co)
                return resumeOk, yielded, closeOk, coroutine.status(co)
                """.trimIndent(),
                "coroutine-close-host-success.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("pause", state.toString(2))
        assertTrue(state.toBoolean(3))
        assertEquals("dead", state.toString(4))
        assertEquals(listOf("closed"), closed)
    }

    @Test
    fun `coroutine close lets running coroutine close itself`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function pack(...)
                    return select("#", ...), ...
                end

                local co
                co = coroutine.create(function()
                    return "unreachable", pcall(coroutine.close, co)
                end)
                local count, ok, first, second = pack(coroutine.resume(co))

                local noArg = coroutine.create(function()
                    return pcall(coroutine.close)
                end)
                local noArgCount, noArgOk, noArgFirst = pack(coroutine.resume(noArg))

                local wrapped = coroutine.wrap(function()
                    return coroutine.close()
                end)
                local wrappedCount, wrappedFirst = pack(wrapped())

                return count, ok, first == nil, second == nil, coroutine.status(co),
                    noArgCount, noArgOk, noArgFirst == nil, coroutine.status(noArg),
                    wrappedCount, wrappedFirst == nil
                """.trimIndent(),
                "coroutine-close-self.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertEquals("dead", state.toString(5))
        assertEquals(1L, state.toInteger(6))
        assertTrue(state.toBoolean(7))
        assertTrue(state.toBoolean(8))
        assertEquals("dead", state.toString(9))
        assertEquals(0L, state.toInteger(10))
        assertTrue(state.toBoolean(11))
    }

    @Test
    fun `coroutine close reports normal coroutine`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local outer
                outer = coroutine.create(function()
                    local inner = coroutine.create(function()
                        local closeOk, message = pcall(coroutine.close, outer)
                        return closeOk, message, coroutine.status(outer)
                    end)
                    return coroutine.resume(inner)
                end)
                local ok, innerOk, closeOk, message, outerStatusDuringInner = coroutine.resume(outer)
                return ok, innerOk, closeOk, message, outerStatusDuringInner, coroutine.status(outer)
                """.trimIndent(),
                "coroutine-close-normal.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertFalse(state.toBoolean(3))
        assertEquals("cannot close a normal coroutine", state.toString(4))
        assertEquals("normal", state.toString(5))
        assertEquals("dead", state.toString(6))
    }

    @Test
    fun `coroutine isyieldable reports current and explicit coroutine states`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local mainYieldable = coroutine.isyieldable()
                local co
                co = coroutine.create(function()
                    local running, isMain = coroutine.running()
                    return coroutine.isyieldable(), coroutine.isyieldable(running), isMain
                end)
                local before = coroutine.isyieldable(co)
                local ok, currentYieldable, explicitYieldable, isMain = coroutine.resume(co)
                local after = coroutine.isyieldable(co)
                return mainYieldable, before, ok, currentYieldable,
                    explicitYieldable, isMain, after, coroutine.status(co)
                """.trimIndent(),
                "coroutine-isyieldable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertFalse(state.toBoolean(6))
        assertFalse(state.toBoolean(7))
        assertEquals("dead", state.toString(8))
    }

    @Test
    fun `coroutine isyieldable reports normal coroutine state`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local outer
                outer = coroutine.create(function()
                    local inner = coroutine.create(function()
                        return coroutine.status(outer), coroutine.isyieldable(outer)
                    end)
                    return coroutine.resume(inner)
                end)
                local ok, innerOk, outerStatus, outerYieldable = coroutine.resume(outer)
                return ok, innerOk, outerStatus, outerYieldable, coroutine.status(outer)
                """.trimIndent(),
                "coroutine-isyieldable-normal.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("normal", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("dead", state.toString(5))
    }

    @Test
    fun `coroutine isyieldable reflects host callback yield boundaries`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)
        state.register("callPlain") { context ->
            context.call(1, emptyList())
        }
        state.register(
            "callYieldable",
            LuaYieldableFunction { context ->
                context.call(1, emptyList())
            },
        )

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co = coroutine.create(function()
                    local running = coroutine.running()
                    local direct = coroutine.isyieldable()
                    local directExplicit = coroutine.isyieldable(running)
                    local plain, plainExplicit = callPlain(function()
                        return coroutine.isyieldable(), coroutine.isyieldable(running)
                    end)
                    local yieldable, yieldableExplicit = callYieldable(function()
                        return coroutine.isyieldable(), coroutine.isyieldable(running)
                    end)
                    return direct, directExplicit, plain, plainExplicit, yieldable, yieldableExplicit
                end)
                return coroutine.resume(co)
                """.trimIndent(),
                "coroutine-isyieldable-host-boundary.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1), state.toString(2))
        assertTrue(state.toBoolean(2), state.toString(2))
        assertTrue(state.toBoolean(3), state.toString(3))
        assertFalse(state.toBoolean(4), state.toString(4))
        assertFalse(state.toBoolean(5), state.toString(5))
        assertTrue(state.toBoolean(6), state.toString(6))
        assertTrue(state.toBoolean(7), state.toString(7))
    }

    @Test
    fun `coroutine yield honors host callback yield boundaries`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)
        state.register("callPlain") { context ->
            context.call(1, emptyList())
        }
        state.register(
            "callYieldable",
            LuaYieldableFunction { context ->
                context.call(1, emptyList())
            },
        )

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local blocked = coroutine.create(function()
                    return callPlain(function()
                        return coroutine.yield("blocked")
                    end)
                end)
                local blockedOk, blockedMessage = coroutine.resume(blocked)

                local allowed = coroutine.create(function()
                    return callYieldable(function()
                        local resumed = coroutine.yield("allowed")
                        return resumed
                    end)
                end)
                local firstOk, yielded = coroutine.resume(allowed)
                local statusAfterYield = coroutine.status(allowed)
                local secondOk, returned = coroutine.resume(allowed, "done")

                return blockedOk, blockedMessage, coroutine.status(blocked),
                    firstOk, yielded, statusAfterYield,
                    secondOk, returned, coroutine.status(allowed)
                """.trimIndent(),
                "coroutine-yield-host-callback-boundary.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to yield across a C-call boundary", state.toString(2))
        assertEquals("dead", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertEquals("allowed", state.toString(5))
        assertEquals("suspended", state.toString(6))
        assertTrue(state.toBoolean(7))
        assertEquals("done", state.toString(8))
        assertEquals("dead", state.toString(9))
    }

    @Test
    fun `coroutine optional thread arguments reject explicit nil`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local closeOk, closeMessage = pcall(coroutine.close, nil)
                local yieldableOk, yieldableMessage = pcall(coroutine.isyieldable, nil)
                return closeOk, closeMessage, yieldableOk, yieldableMessage
                """.trimIndent(),
                "coroutine-explicit-nil-thread.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'close' (thread expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'isyieldable' (thread expected)", state.toString(4))
    }

    @Test
    fun `coroutine functions report argument errors`() {
        val createState = LuaState.create()
        LuaStdlib.openCoroutine(createState)

        assertEquals(LuaStatus.OK, createState.load("""return coroutine.create(nil)""", "coroutine-create-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, createState.pcall(0, -1))
        assertIs<LuaRuntimeException>(createState.getLastError())
        assertEquals("bad argument #1 to 'create' (function expected)", createState.toString(-1))

        val resumeState = LuaState.create()
        LuaStdlib.openCoroutine(resumeState)

        assertEquals(
            LuaStatus.OK,
            resumeState.load("""return coroutine.resume("not-thread")""", "coroutine-resume-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, resumeState.pcall(0, -1))
        assertIs<LuaRuntimeException>(resumeState.getLastError())
        assertEquals("bad argument #1 to 'resume' (thread expected)", resumeState.toString(-1))

        val statusState = LuaState.create()
        LuaStdlib.openCoroutine(statusState)

        assertEquals(
            LuaStatus.OK,
            statusState.load("""return coroutine.status("not-thread")""", "coroutine-status-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, statusState.pcall(0, -1))
        assertIs<LuaRuntimeException>(statusState.getLastError())
        assertEquals("bad argument #1 to 'status' (thread expected)", statusState.toString(-1))

        val closeState = LuaState.create()
        LuaStdlib.openCoroutine(closeState)

        assertEquals(
            LuaStatus.OK,
            closeState.load("""return coroutine.close("not-thread")""", "coroutine-close-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, closeState.pcall(0, -1))
        assertIs<LuaRuntimeException>(closeState.getLastError())
        assertEquals("bad argument #1 to 'close' (thread expected)", closeState.toString(-1))

        val isYieldableState = LuaState.create()
        LuaStdlib.openCoroutine(isYieldableState)

        assertEquals(
            LuaStatus.OK,
            isYieldableState.load("""return coroutine.isyieldable("not-thread")""", "coroutine-isyieldable-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, isYieldableState.pcall(0, -1))
        assertIs<LuaRuntimeException>(isYieldableState.getLastError())
        assertEquals(
            "bad argument #1 to 'isyieldable' (thread expected)",
            isYieldableState.toString(-1),
        )

        val wrapState = LuaState.create()
        LuaStdlib.openCoroutine(wrapState)

        assertEquals(LuaStatus.OK, wrapState.load("""return coroutine.wrap(nil)""", "coroutine-wrap-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, wrapState.pcall(0, -1))
        assertIs<LuaRuntimeException>(wrapState.getLastError())
        assertEquals("bad argument #1 to 'wrap' (function expected)", wrapState.toString(-1))
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
    fun `math abs preserves subtype only for numeric integer arguments`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local integer = math.abs(-5)
                local stringInteger = math.abs("-5")
                local hexStringInteger = math.abs("-0x10")
                return integer, math.type(integer),
                    stringInteger, math.type(stringInteger),
                    hexStringInteger, math.type(hexStringInteger)
                """.trimIndent(),
                "math-abs-subtype.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(5L, state.toInteger(1))
        assertEquals("integer", state.toString(2))
        assertEquals(5.0, state.toNumber(3))
        assertEquals("float", state.toString(4))
        assertEquals(16.0, state.toNumber(5))
        assertEquals("float", state.toString(6))
    }

    @Test
    fun `math abs preserves mininteger wraparound`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local value = math.abs(math.mininteger)
                return value, math.type(value), value == math.mininteger
                """.trimIndent(),
                "math-abs-mininteger.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(Long.MIN_VALUE, state.toInteger(1))
        assertEquals("integer", state.toString(2))
        assertTrue(state.toBoolean(3))
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
                local floorUpperBound = math.floor("0x1p63")
                local ceilLowerBound = math.ceil("-0x1p63")
                return floorInteger, math.type(floorInteger),
                    ceilInteger, math.type(ceilInteger),
                    floorHuge, math.type(floorHuge),
                    ceilHuge, math.type(ceilHuge),
                    ceilInfinity, math.type(ceilInfinity),
                    floorUpperBound, math.type(floorUpperBound),
                    ceilLowerBound, math.type(ceilLowerBound)
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
        assertEquals(9223372036854775808.0, state.toNumber(11) ?: error("missing floor upper-bound result"), 0.0)
        assertEquals("float", state.toString(12))
        assertEquals(Long.MIN_VALUE, state.toInteger(13))
        assertEquals("integer", state.toString(14))
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
    fun `math number arguments accept hexadecimal integer strings`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(math.sin, "nan")
                return math.sin("0x10"), math.sin("0x1p2"), ok, message
                """.trimIndent(),
                "math-hex-string-number.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(kotlin.math.sin(16.0), state.toNumber(1) ?: error("missing hex int sine result"), 1e-12)
        assertEquals(kotlin.math.sin(4.0), state.toNumber(2) ?: error("missing hex float sine result"), 1e-12)
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'sin' (number expected)", state.toString(4))
    }

    @Test
    fun `openMath installs inverse trigonometric functions`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return math.asin(0), math.acos(1), math.atan(1), math.atan(1, nil), math.atan(1, 0)""",
                "math-inverse-trig.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0.0, state.toNumber(1) ?: error("missing asin result"), 1e-12)
        assertEquals(0.0, state.toNumber(2) ?: error("missing acos result"), 1e-12)
        assertEquals(Math.PI / 4, state.toNumber(3) ?: error("missing atan result"), 1e-12)
        assertEquals(Math.PI / 4, state.toNumber(4) ?: error("missing atan nil result"), 1e-12)
        assertEquals(Math.PI / 2, state.toNumber(5) ?: error("missing atan2 result"), 1e-12)
    }

    @Test
    fun `openMath installs exponential and angle functions`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return math.sqrt(9), math.exp(0), math.log(math.exp(1)), math.log(math.exp(1), nil), math.log(8, 2),
                    math.deg(math.pi), math.rad(180), math.log(1000, 10) == 3
                """.trimIndent(),
                "math-exp-angle.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3.0, state.toNumber(1) ?: error("missing sqrt result"), 1e-12)
        assertEquals(1.0, state.toNumber(2) ?: error("missing exp result"), 1e-12)
        assertEquals(1.0, state.toNumber(3) ?: error("missing log result"), 1e-12)
        assertEquals(1.0, state.toNumber(4) ?: error("missing log nil base result"), 1e-12)
        assertEquals(3.0, state.toNumber(5) ?: error("missing log base result"), 1e-12)
        assertEquals(180.0, state.toNumber(6) ?: error("missing deg result"), 1e-12)
        assertEquals(Math.PI, state.toNumber(7) ?: error("missing rad result"), 1e-12)
        assertTrue(state.toBoolean(8))
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
        assertEquals("bad argument #2 to 'fmod' (zero)", state.toString(-1))
    }

    @Test
    fun `math frexp splits numbers into mantissa and exponent`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local positiveMantissa, positiveExponent = math.frexp(8)
                local negativeMantissa, negativeExponent = math.frexp(-6)
                local zeroMantissa, zeroExponent = math.frexp(0)
                local infiniteMantissa, infiniteExponent = math.frexp(math.huge)
                local subnormal = math.ldexp(0.5, -1073)
                local subnormalMantissa, subnormalExponent = math.frexp(subnormal)
                return positiveMantissa, positiveExponent,
                    negativeMantissa, negativeExponent,
                    zeroMantissa, zeroExponent,
                    infiniteMantissa == math.huge, infiniteExponent,
                    subnormalMantissa, subnormalExponent
                """.trimIndent(),
                "math-frexp.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0.5, state.toNumber(1) ?: error("missing positive mantissa"), 0.0)
        assertEquals(4L, state.toInteger(2))
        assertEquals(-0.75, state.toNumber(3) ?: error("missing negative mantissa"), 0.0)
        assertEquals(3L, state.toInteger(4))
        assertEquals(0.0, state.toNumber(5) ?: error("missing zero mantissa"), 0.0)
        assertEquals(0L, state.toInteger(6))
        assertTrue(state.toBoolean(7))
        assertEquals(0L, state.toInteger(8))
        assertEquals(0.5, state.toNumber(9) ?: error("missing subnormal mantissa"), 0.0)
        assertEquals(-1073L, state.toInteger(10))
    }

    @Test
    fun `math ldexp scales mantissa by binary exponent`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return math.ldexp(0.5, 4),
                    math.ldexp(-0.75, 3),
                    math.ldexp(1, -1),
                    math.ldexp(1, 2048),
                    math.ldexp(1, -2048)
                """.trimIndent(),
                "math-ldexp.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(8.0, state.toNumber(1) ?: error("missing positive ldexp result"), 0.0)
        assertEquals(-6.0, state.toNumber(2) ?: error("missing negative ldexp result"), 0.0)
        assertEquals(0.5, state.toNumber(3) ?: error("missing fractional ldexp result"), 0.0)
        assertTrue((state.toNumber(4) ?: error("missing overflow ldexp result")).isInfinite())
        assertEquals(0.0, state.toNumber(5) ?: error("missing underflow ldexp result"), 0.0)
    }

    @Test
    fun `math ldexp narrows exponent like lua 55 lmathlib`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return math.ldexp(1, 2147483647),
                    math.ldexp(1, 2147483648),
                    math.ldexp(1, 2147483649),
                    math.ldexp(1, -2147483648),
                    math.ldexp(1, -2147483649)
                """.trimIndent(),
                "math-ldexp-exponent-narrowing.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue((state.toNumber(1) ?: error("missing max int exponent result")).isInfinite())
        assertEquals(0.0, state.toNumber(2) ?: error("missing wrapped min int exponent result"), 0.0)
        assertEquals(0.0, state.toNumber(3) ?: error("missing wrapped negative exponent result"), 0.0)
        assertEquals(0.0, state.toNumber(4) ?: error("missing min int exponent result"), 0.0)
        assertTrue((state.toNumber(5) ?: error("missing wrapped max int exponent result")).isInfinite())
    }

    @Test
    fun `math frexp and ldexp report argument errors`() {
        val frexpState = LuaState.create()
        LuaStdlib.openMath(frexpState)

        assertEquals(LuaStatus.OK, frexpState.load("""return math.frexp("x")""", "math-frexp-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, frexpState.pcall(0, -1))

        assertIs<LuaRuntimeException>(frexpState.getLastError())
        assertEquals("bad argument #1 to 'frexp' (number expected)", frexpState.toString(-1))

        val ldexpState = LuaState.create()
        LuaStdlib.openMath(ldexpState)
        LuaStdlib.openBase(ldexpState)

        assertEquals(
            LuaStatus.OK,
            ldexpState.load(
                """
                local okString, stringMessage = pcall(math.ldexp, 1, "x")
                local okFraction, fractionMessage = pcall(math.ldexp, 1, 1.5)
                return okString, stringMessage, okFraction, fractionMessage
                """.trimIndent(),
                "math-ldexp-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, ldexpState.pcall(0, -1), ldexpState.toString(-1))

        assertFalse(ldexpState.toBoolean(1))
        assertEquals("bad argument #2 to 'ldexp' (number expected)", ldexpState.toString(2))
        assertFalse(ldexpState.toBoolean(3))
        assertEquals("bad argument #2 to 'ldexp' (number has no integer representation)", ldexpState.toString(4))
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
                    math.tointeger("3.0"),
                    math.tointeger("0x10"),
                    math.tointeger("0x1.8p1"),
                    math.tointeger("0x1p63"),
                    math.tointeger("-0x1p63"),
                    math.tointeger("3.5"),
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
        assertEquals(3L, state.toInteger(4))
        assertEquals(16L, state.toInteger(5))
        assertEquals(3L, state.toInteger(6))
        assertTrue(state.isNil(7))
        assertEquals(Long.MIN_VALUE, state.toInteger(8))
        assertTrue(state.isNil(9))
        assertTrue(state.toBoolean(10))
        assertFalse(state.toBoolean(11))
        assertFalse(state.toBoolean(12))
    }

    @Test
    fun `math tointeger reports missing argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.tointeger()""", "math-tointeger-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'tointeger' (value expected)", state.toString(-1))
    }

    @Test
    fun `math ult reports non integer argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.ult(1.5, 2)""", "math-ult-fractional-left.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals(
            "bad argument #1 to 'ult' (number has no integer representation)",
            state.toString(-1),
        )

        val rightState = LuaState.create()
        LuaStdlib.openMath(rightState)

        assertEquals(LuaStatus.OK, rightState.load("""return math.ult(1, 2.5)""", "math-ult-fractional-right.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, rightState.pcall(0, -1))

        assertIs<LuaRuntimeException>(rightState.getLastError())
        assertEquals(
            "bad argument #2 to 'ult' (number has no integer representation)",
            rightState.toString(-1),
        )

        val stringState = LuaState.create()
        LuaStdlib.openBase(stringState)
        LuaStdlib.openMath(stringState)

        assertEquals(
            LuaStatus.OK,
            stringState.load(
                """
                local okLeft, leftMessage = pcall(math.ult, "x", 1)
                local okRight, rightMessage = pcall(math.ult, 1, "x")
                return okLeft, leftMessage, okRight, rightMessage
                """.trimIndent(),
                "math-ult-string-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, stringState.pcall(0, -1), stringState.toString(-1))

        assertFalse(stringState.toBoolean(1))
        assertEquals("bad argument #1 to 'ult' (number expected)", stringState.toString(2))
        assertFalse(stringState.toBoolean(3))
        assertEquals("bad argument #2 to 'ult' (number expected)", stringState.toString(4))
    }

    @Test
    fun `math ult reports integer argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(math.ult, "x", 1)
                local okLeftFraction, leftFractionMessage = pcall(math.ult, 1.5, 2)
                local okRightFraction, rightFractionMessage = pcall(math.ult, 1, "2.5")
                return okString, stringMessage,
                    okLeftFraction, leftFractionMessage,
                    okRightFraction, rightFractionMessage
                """.trimIndent(),
                "math-ult-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'ult' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'ult' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'ult' (number has no integer representation)", state.toString(6))
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
        assertEquals("bad argument #1 to 'type' (value expected)", state.toString(-1))
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
    fun `math min and max compare adjacent large integers exactly`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local maxMinusOne = math.maxinteger - 1
                local minPlusOne = math.mininteger + 1
                local selectedMax = math.max(maxMinusOne, math.maxinteger)
                local selectedMin = math.min(minPlusOne, math.mininteger)
                return selectedMax,
                    selectedMin,
                    math.type(selectedMax),
                    math.type(selectedMin)
                """.trimIndent(),
                "math-min-max-large-integers.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(Long.MAX_VALUE, state.toInteger(1))
        assertEquals(Long.MIN_VALUE, state.toInteger(2))
        assertEquals("integer", state.toString(3))
        assertEquals("integer", state.toString(4))
    }

    @Test
    fun `math min and max use lua comparison and return selected value`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local tableValue = {}
                local minTable = math.min(tableValue)
                local maxBoolean = math.max(true)
                local minString = math.min("3", "4")
                local maxString = math.max("3", "4")
                local raw = string.char(128)
                local utf8 = "é"
                local rawMin = math.min(raw, utf8)
                local rawMax = math.max(raw, utf8)
                return minTable == tableValue,
                    maxBoolean,
                    minString, math.type(minString),
                    maxString, math.type(maxString),
                    string.byte(rawMin), rawMax == utf8
                """.trimIndent(),
                "math-min-max-comparison.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("3", state.toString(3))
        assertTrue(state.isNil(4))
        assertEquals("4", state.toString(5))
        assertTrue(state.isNil(6))
        assertEquals(128L, state.toInteger(7))
        assertTrue(state.toBoolean(8))
    }

    @Test
    fun `math min and max use table less than metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local metatable = {
                    __lt = function(left, right)
                        return left.rank < right.rank
                    end,
                }
                local low = setmetatable({ rank = 1 }, metatable)
                local high = setmetatable({ rank = 5 }, metatable)
                local minValue = math.min(high, low)
                local maxValue = math.max(low, high)
                return minValue == low, maxValue == high
                """.trimIndent(),
                "math-min-max-lt-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
    }

    @Test
    fun `math min and max report comparison errors`() {
        val minState = LuaState.create()
        LuaStdlib.openMath(minState)

        assertEquals(LuaStatus.OK, minState.load("""return math.min("3", 4)""", "math-min-compare-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, minState.pcall(0, -1))

        assertIs<LuaRuntimeException>(minState.getLastError())
        assertEquals("attempt to compare number with string", minState.toString(-1))

        val maxState = LuaState.create()
        LuaStdlib.openMath(maxState)

        assertEquals(LuaStatus.OK, maxState.load("""return math.max("3", 2)""", "math-max-compare-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, maxState.pcall(0, -1))

        assertIs<LuaRuntimeException>(maxState.getLastError())
        assertEquals("attempt to compare string with number", maxState.toString(-1))

        val booleanState = LuaState.create()
        LuaStdlib.openMath(booleanState)

        assertEquals(LuaStatus.OK, booleanState.load("""return math.min(true, false)""", "math-min-boolean-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, booleanState.pcall(0, -1))

        assertIs<LuaRuntimeException>(booleanState.getLastError())
        assertEquals("attempt to compare two boolean values", booleanState.toString(-1))
    }

    @Test
    fun `math min and max report missing argument errors`() {
        val minState = LuaState.create()
        LuaStdlib.openMath(minState)

        assertEquals(LuaStatus.OK, minState.load("""return math.min()""", "math-min-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, minState.pcall(0, -1))

        assertIs<LuaRuntimeException>(minState.getLastError())
        assertEquals("bad argument #1 to 'min' (value expected)", minState.toString(-1))

        val maxState = LuaState.create()
        LuaStdlib.openMath(maxState)

        assertEquals(LuaStatus.OK, maxState.load("""return math.max()""", "math-max-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, maxState.pcall(0, -1))

        assertIs<LuaRuntimeException>(maxState.getLastError())
        assertEquals("bad argument #1 to 'max' (value expected)", maxState.toString(-1))
    }

    @Test
    fun `math random follows lua 55 xoshiro sequences`() {
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
                local allBits = math.random(0)
                math.randomseed(123)
                return first, ranged, offset, allBits,
                    first == math.random(),
                    ranged == math.random(10),
                    offset == math.random(5, 7),
                    allBits == math.random(0),
                    first >= 0 and first < 1,
                    ranged >= 1 and ranged <= 10,
                    offset >= 5 and offset <= 7,
                    math.type(allBits) == "integer"
                """.trimIndent(),
                "math-random.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0.21405899041481313, state.toNumber(1) ?: error("missing random result"), 0.0)
        assertEquals(9L, state.toInteger(2))
        assertEquals(5L, state.toInteger(3))
        assertEquals(-5391743551570447441L, state.toInteger(4))
        for (index in 5..12) {
            assertTrue(state.toBoolean(index))
        }
    }

    @Test
    fun `math random matches Lua xoshiro seeded sequence`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                math.randomseed(123)
                return math.random(),
                    math.random(10),
                    math.random(5, 7),
                    math.random(0)
                """.trimIndent(),
                "math-random-xoshiro-sequence.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(0.21405899041481313, state.toNumber(1) ?: error("missing random float"), 0.0)
        assertEquals(9L, state.toInteger(2))
        assertEquals(5L, state.toInteger(3))
        assertEquals(-5391743551570447441L, state.toInteger(4))
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
                math.randomseed(0, 0)
                local generatedFirstSeed, generatedSecondSeed = math.randomseed()
                local generated = math.random(100)
                math.randomseed(123, 456)
                local repeated = math.random(100)
                local singleFirstSeed, singleSecondSeed = math.randomseed(789)
                local nilSecondFirstSeed, nilSecondSecondSeed = math.randomseed(789, nil)
                local nilSecondValue = math.random(100)
                math.randomseed(789)
                local omittedSecondValue = math.random(100)
                return firstSeed, secondSeed, first, first == repeated,
                    generatedFirstSeed ~= nil, generatedSecondSeed ~= 0, generated >= 1 and generated <= 100,
                    singleFirstSeed, singleSecondSeed,
                    nilSecondFirstSeed, nilSecondSecondSeed, nilSecondValue == omittedSecondValue
                """.trimIndent(),
                "math-randomseed.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(123L, state.toInteger(1))
        assertEquals(456L, state.toInteger(2))
        assertEquals(46L, state.toInteger(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.toBoolean(6))
        assertTrue(state.toBoolean(7))
        assertEquals(789L, state.toInteger(8))
        assertEquals(0L, state.toInteger(9))
        assertEquals(789L, state.toInteger(10))
        assertEquals(0L, state.toInteger(11))
        assertTrue(state.toBoolean(12))
    }

    @Test
    fun `math random state is isolated per Lua state`() {
        val firstState = LuaState.create()
        LuaStdlib.openMath(firstState)
        assertEquals(
            LuaStatus.OK,
            firstState.load(
                """
                math.randomseed(321)
                local first = math.random()
                return first
                """.trimIndent(),
                "math-random-state-first.lua",
            ),
        )
        assertEquals(LuaStatus.OK, firstState.pcall(0, -1))
        firstState.pop()

        val secondState = LuaState.create()
        LuaStdlib.openMath(secondState)
        assertEquals(
            LuaStatus.OK,
            secondState.load(
                """
                math.randomseed(999)
                return math.random()
                """.trimIndent(),
                "math-random-state-second.lua",
            ),
        )
        assertEquals(LuaStatus.OK, secondState.pcall(0, -1))

        assertEquals(
            LuaStatus.OK,
            firstState.load("""return math.random()""", "math-random-state-first-continues.lua"),
        )
        assertEquals(LuaStatus.OK, firstState.pcall(0, -1))
        val continued = firstState.toNumber(-1) ?: error("missing continued random value")

        val referenceState = LuaState.create()
        LuaStdlib.openMath(referenceState)
        assertEquals(
            LuaStatus.OK,
            referenceState.load(
                """
                math.randomseed(321)
                math.random()
                return math.random()
                """.trimIndent(),
                "math-random-state-reference.lua",
            ),
        )
        assertEquals(LuaStatus.OK, referenceState.pcall(0, -1))

        assertEquals(referenceState.toNumber(1) ?: error("missing reference random value"), continued, 0.0)
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
                math.randomseed(789)
                local rawBits = math.random(0)
                math.randomseed(789)
                local fullRangeRepeat = math.random(math.mininteger, math.maxinteger)
                return upperOnly, wideRange, fullRange,
                    upperOnly >= 1 and upperOnly <= 3000000000,
                    wideRange >= -3000000000 and wideRange <= 3000000000,
                    fullRange ~= nil,
                    upperOnly == repeated,
                    fullRangeRepeat == rawBits + math.mininteger
                """.trimIndent(),
                "math-random-wide.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(676063042L, state.toInteger(1))
        assertEquals(2846524133L, state.toInteger(2))
        assertEquals(5533629760186076056L, state.toInteger(3))
        for (index in 4..8) {
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
        assertEquals("bad argument #1 to 'abs' (number expected)", state.toString(-1))
    }

    @Test
    fun `math random reports empty interval errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.random(10, 1)""", "math-random-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'random' (interval is empty)", state.toString(-1))
    }

    @Test
    fun `math random reports non integer interval errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.random(1.5)""", "math-random-fractional-upper.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals(
            "bad argument #1 to 'random' (number has no integer representation)",
            state.toString(-1),
        )

        val upperState = LuaState.create()
        LuaStdlib.openMath(upperState)

        assertEquals(LuaStatus.OK, upperState.load("""return math.random(1, 2.5)""", "math-random-fractional-upper-bound.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, upperState.pcall(0, -1))

        assertIs<LuaRuntimeException>(upperState.getLastError())
        assertEquals(
            "bad argument #2 to 'random' (number has no integer representation)",
            upperState.toString(-1),
        )

        val stringState = LuaState.create()
        LuaStdlib.openMath(stringState)

        assertEquals(LuaStatus.OK, stringState.load("""return math.random("x")""", "math-random-string-upper.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, stringState.pcall(0, -1))

        assertIs<LuaRuntimeException>(stringState.getLastError())
        assertEquals("bad argument #1 to 'random' (number expected)", stringState.toString(-1))
    }

    @Test
    fun `math random advances state before argument validation`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                math.randomseed(123, 456)
                local first = math.random(0)
                local second = math.random(0)
                math.randomseed(123, 456)
                local ok, message = pcall(math.random, "bad")
                local afterError = math.random(0)
                return ok, message, afterError == second, afterError ~= first
                """.trimIndent(),
                "math-random-validation-state.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'random' (number expected)", state.toString(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
    }

    @Test
    fun `math random reports integer argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okUpper, upperMessage = pcall(math.random, 1.5)
                local okLower, lowerMessage = pcall(math.random, "1.5", 3)
                local okRange, rangeMessage = pcall(math.random, 1, "3.5")
                return okUpper, upperMessage, okLower, lowerMessage, okRange, rangeMessage
                """.trimIndent(),
                "math-random-integer-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'random' (number has no integer representation)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'random' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'random' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `math random reports extra argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(math.random, 1, 2, 3)
                return ok, message
                """.trimIndent(),
                "math-random-extra-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("wrong number of arguments", state.toString(2))
    }

    @Test
    fun `math randomseed ignores extra seed arguments`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local firstSeed, secondSeed = math.randomseed(1, 2, 3)
                local first = math.random(100)
                math.randomseed(1, 2)
                local repeated = math.random(100)
                return firstSeed, secondSeed, first == repeated
                """.trimIndent(),
                "math-randomseed-extra-arguments.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertTrue(state.toBoolean(3))
    }

    @Test
    fun `math randomseed reports integer argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okFirst, firstMessage = pcall(math.randomseed, "x")
                local okFirstFraction, firstFractionMessage = pcall(math.randomseed, 1.5)
                local okSecondFraction, secondFractionMessage = pcall(math.randomseed, 1, "2.5")
                return okFirst, firstMessage,
                    okFirstFraction, firstFractionMessage,
                    okSecondFraction, secondFractionMessage
                """.trimIndent(),
                "math-randomseed-integer-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'randomseed' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'randomseed' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'randomseed' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `math randomseed reports non integer seed errors`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(LuaStatus.OK, state.load("""return math.randomseed(1.5)""", "math-randomseed-fractional.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals(
            "bad argument #1 to 'randomseed' (number has no integer representation)",
            state.toString(-1),
        )

        val secondSeedState = LuaState.create()
        LuaStdlib.openMath(secondSeedState)

        assertEquals(
            LuaStatus.OK,
            secondSeedState.load("""return math.randomseed(1, 2.5)""", "math-randomseed-fractional-second.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, secondSeedState.pcall(0, -1))

        assertIs<LuaRuntimeException>(secondSeedState.getLastError())
        assertEquals(
            "bad argument #2 to 'randomseed' (number has no integer representation)",
            secondSeedState.toString(-1),
        )

        val stringSeedState = LuaState.create()
        LuaStdlib.openBase(stringSeedState)
        LuaStdlib.openMath(stringSeedState)

        assertEquals(
            LuaStatus.OK,
            stringSeedState.load(
                """
                local okFirst, firstMessage = pcall(math.randomseed, "x")
                local okSecond, secondMessage = pcall(math.randomseed, 1, "x")
                return okFirst, firstMessage, okSecond, secondMessage
                """.trimIndent(),
                "math-randomseed-string-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, stringSeedState.pcall(0, -1), stringSeedState.toString(-1))

        assertFalse(stringSeedState.toBoolean(1))
        assertEquals("bad argument #1 to 'randomseed' (number expected)", stringSeedState.toString(2))
        assertFalse(stringSeedState.toBoolean(3))
        assertEquals("bad argument #2 to 'randomseed' (number expected)", stringSeedState.toString(4))
    }

    @Test
    fun `unary math functions report registered argument names`() {
        val functions = listOf(
            "acos",
            "asin",
            "atan",
            "ceil",
            "cos",
            "deg",
            "exp",
            "floor",
            "modf",
            "rad",
            "sin",
            "sqrt",
            "tan",
        )

        for (functionName in functions) {
            val state = LuaState.create()
            LuaStdlib.openMath(state)

            assertEquals(LuaStatus.OK, state.load("""return math.$functionName("x")""", "math-$functionName-error.lua"))
            assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

            assertIs<LuaRuntimeException>(state.getLastError())
            assertEquals("bad argument #1 to '$functionName' (number expected)", state.toString(-1))
        }
    }

    @Test
    fun `openString installs basic string functions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local reversedUtf8 = string.reverse("é")
                local reversedUtf8First, reversedUtf8Second = string.byte(reversedUtf8, 1, -1)
                local reversedInvalid = string.reverse("A" .. string.char(128))
                local reversedInvalidFirst, reversedInvalidSecond = string.byte(reversedInvalid, 1, -1)
                return string.len("hello"), string.len("é"), string.lower("HeLLo"), string.upper("HeLLo"),
                    string.reverse("abc"), string.rep("ha", 3), string.sub("abcdef", 2, 4),
                    string.sub("abcdef", -3, -1), string.sub("abcdef", 4, 2),
                    string.sub("éx", 1, 2), string.sub("éx", 3, 3),
                    reversedUtf8First, reversedUtf8Second, string.len(reversedUtf8),
                    reversedInvalidFirst, reversedInvalidSecond, string.len(reversedInvalid)
                """.trimIndent(),
                "string.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(5L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals("hello", state.toString(3))
        assertEquals("HELLO", state.toString(4))
        assertEquals("cba", state.toString(5))
        assertEquals("hahaha", state.toString(6))
        assertEquals("bcd", state.toString(7))
        assertEquals("def", state.toString(8))
        assertEquals("", state.toString(9))
        assertEquals("é", state.toString(10))
        assertEquals("x", state.toString(11))
        assertEquals(169L, state.toInteger(12))
        assertEquals(195L, state.toInteger(13))
        assertEquals(2L, state.toInteger(14))
        assertEquals(128L, state.toInteger(15))
        assertEquals(65L, state.toInteger(16))
        assertEquals(2L, state.toInteger(17))
    }

    @Test
    fun `openString supports string method calls`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local text = "AbcDef"
                return text:len(), text:lower(), text:sub(2, 4), ("xy"):rep(2, "-")
                """.trimIndent(),
                "string-methods.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(6L, state.toInteger(1))
        assertEquals("abcdef", state.toString(2))
        assertEquals("bcD", state.toString(3))
        assertEquals("xy-xy", state.toString(4))
    }

    @Test
    fun `openString preserves string method table after global reassignment`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local originalLen = ("abc"):len()
                string = nil
                local afterNil = ("abcd"):len()
                string = {}
                local afterReplace = ("abcde"):len()
                return originalLen, afterNil, afterReplace
                """.trimIndent(),
                "string-methods-reassigned.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(3L, state.toInteger(1))
        assertEquals(4L, state.toInteger(2))
        assertEquals(5L, state.toInteger(3))
    }

    @Test
    fun `string sub reports range argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okStartString, startStringMessage = pcall(string.sub, "ABC", "x", 1)
                local okStartFraction, startFractionMessage = pcall(string.sub, "ABC", 1.5, 1)
                local okEndFraction, endFractionMessage = pcall(string.sub, "ABC", 1, "1.5")
                return okStartString, startStringMessage,
                    okStartFraction, startFractionMessage,
                    okEndFraction, endFractionMessage
                """.trimIndent(),
                "string-sub-range-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'sub' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'sub' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'sub' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `string lower and upper use ascii byte case mapping`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local upperA = "\u{C4}"
                local lowerA = "\u{E4}"
                local dottedI = "\u{130}"
                local sharpS = "\u{DF}"
                return string.lower("ABCxyz"), string.upper("ABCxyz"),
                    string.lower(upperA), string.upper(lowerA),
                    string.lower(dottedI), string.upper(sharpS)
                """.trimIndent(),
                "string-ascii-case.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("abcxyz", state.toString(1))
        assertEquals("ABCXYZ", state.toString(2))
        assertEquals("\u00C4", state.toString(3))
        assertEquals("\u00E4", state.toString(4))
        assertEquals("\u0130", state.toString(5))
        assertEquals("\u00DF", state.toString(6))
    }

    @Test
    fun `string rep supports separators and empty repetitions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.rep("ha", 3, "-"), string.rep("ha", 0, "-"), string.rep("", 2147483648)""",
                "string-rep.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("ha-ha-ha", state.toString(1))
        assertEquals("", state.toString(2))
        assertEquals("", state.toString(3))
    }

    @Test
    fun `string rep preserves raw byte strings`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local bytes = string.char(128, 255)
                local separator = string.char(0)
                local repeated = string.rep(bytes, 2, separator)
                return string.len(repeated), string.byte(repeated, 1, -1)
                """.trimIndent(),
                "string-rep-raw-bytes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(5L, state.toInteger(1))
        assertEquals(128L, state.toInteger(2))
        assertEquals(255L, state.toInteger(3))
        assertEquals(0L, state.toInteger(4))
        assertEquals(128L, state.toInteger(5))
        assertEquals(255L, state.toInteger(6))
    }

    @Test
    fun `string rep reports large result errors`() {
        for ((source, chunkName) in listOf(
            """"x", 2147483648""" to "string-rep-large-error.lua",
            """"é", 1073741824""" to "string-rep-large-multibyte-error.lua",
            """"x", 715827884, "é"""" to "string-rep-large-multibyte-separator-error.lua",
            """"", 1073741825, "é"""" to "string-rep-large-empty-with-multibyte-separator-error.lua",
        )) {
            val state = LuaState.create()
            LuaStdlib.openString(state)

            assertEquals(LuaStatus.OK, state.load("""return string.rep($source)""", chunkName))
            assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

            assertIs<LuaRuntimeException>(state.getLastError())
            assertEquals("resulting string too large", state.toString(-1))
        }
    }

    @Test
    fun `string byte and char convert byte values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first, second, third = string.byte("ABC", 1, 3)
                local last = string.byte("ABC", -1)
                local utf8First, utf8Second = string.byte("é", 1, -1)
                local utf8Last = string.byte("é", -1)
                local utf8Char = string.char(195, 169)
                local charFirst, charSecond = string.byte(utf8Char, 1, -1)
                local invalidBytes = string.char(128, 255)
                local invalidFirst, invalidSecond = string.byte(invalidBytes, 1, -1)
                local slicedInvalid = string.sub(invalidBytes, 2, 2)
                local smileFirst, smileSecond, smileThird, smileFourth = string.byte("\u{1F600}", 1, -1)
                local privateFirst, privateSecond, privateThird = string.byte("\u{E080}", 1, -1)
                local empty = string.char()
                return first, second, third, last, utf8First, utf8Second, utf8Last,
                    string.char(65, 66, 67), utf8Char, charFirst, charSecond, empty,
                    invalidFirst, invalidSecond, string.len(invalidBytes), rawlen(invalidBytes),
                    string.byte(slicedInvalid),
                    smileFirst, smileSecond, smileThird, smileFourth, string.len("\u{1F600}"),
                    privateFirst, privateSecond, privateThird, string.len("\u{E080}")
                """.trimIndent(),
                "string-byte-char.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(65L, state.toInteger(1))
        assertEquals(66L, state.toInteger(2))
        assertEquals(67L, state.toInteger(3))
        assertEquals(67L, state.toInteger(4))
        assertEquals(195L, state.toInteger(5))
        assertEquals(169L, state.toInteger(6))
        assertEquals(169L, state.toInteger(7))
        assertEquals("ABC", state.toString(8))
        assertEquals("é", state.toString(9))
        assertEquals(195L, state.toInteger(10))
        assertEquals(169L, state.toInteger(11))
        assertEquals("", state.toString(12))
        assertEquals(128L, state.toInteger(13))
        assertEquals(255L, state.toInteger(14))
        assertEquals(2L, state.toInteger(15))
        assertEquals(2L, state.toInteger(16))
        assertEquals(255L, state.toInteger(17))
        assertEquals(240L, state.toInteger(18))
        assertEquals(159L, state.toInteger(19))
        assertEquals(152L, state.toInteger(20))
        assertEquals(128L, state.toInteger(21))
        assertEquals(4L, state.toInteger(22))
        assertEquals(238L, state.toInteger(23))
        assertEquals(130L, state.toInteger(24))
        assertEquals(128L, state.toInteger(25))
        assertEquals(3L, state.toInteger(26))
    }

    @Test
    fun `string byte observes source byte escapes`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local decimal = "\255"
                local hex = "\xFF"
                local escapedUtf8 = "\195\169"
                return string.len(decimal), string.byte(decimal),
                    string.len(hex), string.byte(hex),
                    escapedUtf8 == "é", string.len(escapedUtf8),
                    string.byte(escapedUtf8, 1, -1)
                """.trimIndent(),
                "string-source-byte-escapes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(255L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(255L, state.toInteger(4))
        assertEquals(true, state.toBoolean(5))
        assertEquals(2L, state.toInteger(6))
        assertEquals(195L, state.toInteger(7))
        assertEquals(169L, state.toInteger(8))
    }

    @Test
    fun `string positional arguments accept lua numeric strings`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local byte2, byte3 = string.byte("ABC", "0x2", "0x3")
                local findStart, findEnd = string.find("banana", "an", "0x3")
                local iterator = string.gmatch("banana", "an", "0x3")
                local replaced, count = string.gsub("banana", "an", "ON", "0x1")
                return string.sub("abcdef", "0x2", "0x4"),
                    byte2, byte3,
                    findStart, findEnd,
                    string.match("banana", "an", "0x3"),
                    iterator(),
                    replaced, count
                """.trimIndent(),
                "string-numeric-string-positions.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("bcd", state.toString(1))
        assertEquals(66L, state.toInteger(2))
        assertEquals(67L, state.toInteger(3))
        assertEquals(4L, state.toInteger(4))
        assertEquals(5L, state.toInteger(5))
        assertEquals("an", state.toString(6))
        assertEquals("an", state.toString(7))
        assertEquals("bONana", state.toString(8))
        assertEquals(1L, state.toInteger(9))
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
                local literalCaretStart, literalCaretEnd = string.find("a^b", "a^")
                local literalDollarStart, literalDollarEnd = string.find("a${'$'}b", "${'$'}b")
                local utf8Start, utf8End = string.find("éx", "é", 1, true)
                local byteStart, byteEnd = string.find("éx", "x", 2, true)
                local captureStart, captureEnd, capturePosition = string.find("éx", "()x")
                return firstStart, firstEnd, secondStart, secondEnd, lastStart, lastEnd,
                    dotStart, dotEnd, patternStart, patternEnd, digitStart, digitEnd,
                    literalDotStart, literalDotEnd, anchorStart, anchorEnd, endStart, endEnd,
                    vowelStart, vowelEnd, rangeStart, rangeEnd, negatedStart, negatedEnd,
                    literalCaretStart, literalCaretEnd, literalDollarStart, literalDollarEnd,
                    utf8Start, utf8End, byteStart, byteEnd, captureStart, captureEnd, capturePosition,
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
        assertEquals(1L, state.toInteger(25))
        assertEquals(2L, state.toInteger(26))
        assertEquals(2L, state.toInteger(27))
        assertEquals(3L, state.toInteger(28))
        assertEquals(1L, state.toInteger(29))
        assertEquals(2L, state.toInteger(30))
        assertEquals(3L, state.toInteger(31))
        assertEquals(3L, state.toInteger(32))
        assertEquals(3L, state.toInteger(33))
        assertEquals(3L, state.toInteger(34))
        assertEquals(3L, state.toInteger(35))
        assertTrue(state.isNil(36))
        assertTrue(state.isNil(37))
    }

    @Test
    fun `string patterns match utf8 text by raw bytes`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local utf8 = "é"
                local firstStart, firstEnd, firstCapture = string.find(utf8, "(.)")
                local secondStart, secondEnd = string.find(utf8, ".", 2)
                local first = string.match(utf8, ".")
                local second = string.match(utf8, ".", 2)
                local literal = string.match(utf8, "é")
                local iterator = string.gmatch(utf8, ".")
                local iterFirst = iterator()
                local iterSecond = iterator()
                local iterDone = iterator()
                return firstStart, firstEnd, string.len(firstCapture), string.byte(firstCapture),
                    secondStart, secondEnd,
                    string.len(first), string.byte(first), string.len(second), string.byte(second),
                    literal == utf8,
                    string.byte(iterFirst), string.byte(iterSecond), iterDone == nil
                """.trimIndent(),
                "string-pattern-raw-bytes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(195L, state.toInteger(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals(2L, state.toInteger(6))
        assertEquals(1L, state.toInteger(7))
        assertEquals(195L, state.toInteger(8))
        assertEquals(1L, state.toInteger(9))
        assertEquals(169L, state.toInteger(10))
        assertTrue(state.toBoolean(11))
        assertEquals(195L, state.toInteger(12))
        assertEquals(169L, state.toInteger(13))
        assertTrue(state.toBoolean(14))
    }

    @Test
    fun `string search functions reject init positions past the end`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local plainStart, plainEnd = string.find("abc", "", 5, true)
                local patternStart, patternEnd = string.find("abc", "", 5)
                local matched = string.match("abc", "", 5)
                local iterator = string.gmatch("abc", "", 5)
                local invalidFind = string.find("abc", "[", 5)
                local invalidMatch = string.match("abc", "[", 5)
                local invalidIterator = string.gmatch("abc", "[", 5)
                return plainStart, plainEnd, patternStart, patternEnd, matched, iterator(),
                    invalidFind, invalidMatch, invalidIterator()
                """.trimIndent(),
                "string-search-init-past-end.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
        assertTrue(state.isNil(5))
        assertTrue(state.isNil(6))
        assertTrue(state.isNil(7))
        assertTrue(state.isNil(8))
        assertTrue(state.isNil(9))
    }

    @Test
    fun `string find treats plain flag by lua truthiness`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numericStart, numericEnd = string.find("a.c", ".", 1, 1)
                local zeroStart, zeroEnd = string.find("a.c", ".", 1, 0)
                local stringStart, stringEnd = string.find("a.c", ".", 1, "plain")
                local tableStart, tableEnd = string.find("a.c", ".", 1, {})
                local falseStart, falseEnd = string.find("a.c", ".", 1, false)
                local nilStart, nilEnd = string.find("a.c", ".", 1, nil)
                return numericStart, numericEnd, zeroStart, zeroEnd, stringStart, stringEnd,
                    tableStart, tableEnd, falseStart, falseEnd, nilStart, nilEnd
                """.trimIndent(),
                "string-find-plain-truthiness.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(2L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals(2L, state.toInteger(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals(2L, state.toInteger(6))
        assertEquals(2L, state.toInteger(7))
        assertEquals(2L, state.toInteger(8))
        assertEquals(1L, state.toInteger(9))
        assertEquals(1L, state.toInteger(10))
        assertEquals(1L, state.toInteger(11))
        assertEquals(1L, state.toInteger(12))
    }

    @Test
    fun `string search functions report init argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okFindString, findStringMessage = pcall(string.find, "abc", "a", "bad")
                local okFindFraction, findFractionMessage = pcall(string.find, "abc", "a", 1.5)
                local okMatchFraction, matchFractionMessage = pcall(string.match, "abc", "a", "1.5")
                local okGmatchFraction, gmatchFractionMessage = pcall(string.gmatch, "abc", "a", 1.5)
                return okFindString, findStringMessage,
                    okFindFraction, findFractionMessage,
                    okMatchFraction, matchFractionMessage,
                    okGmatchFraction, gmatchFractionMessage
                """.trimIndent(),
                "string-search-init-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #3 to 'find' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #3 to 'find' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'match' (number has no integer representation)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #3 to 'gmatch' (number has no integer representation)", state.toString(8))
    }

    @Test
    fun `string find and match allow empty matches at string end`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local plainStart, plainEnd = string.find("abc", "", 4, true)
                local patternStart, patternEnd = string.find("abc", "", 4)
                local matched = string.match("abc", "", 4)
                return plainStart, plainEnd, patternStart, patternEnd, matched
                """.trimIndent(),
                "string-search-empty-at-end.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(4L, state.toInteger(1))
        assertEquals(3L, state.toInteger(2))
        assertEquals(4L, state.toInteger(3))
        assertEquals(3L, state.toInteger(4))
        assertEquals("", state.toString(5))
    }

    @Test
    fun `string searches map utf8 byte init positions to character boundaries`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local text = "A" .. utf8.char(128512) .. "Z"
                local foundStart, foundEnd = string.find(text, "Z", 6, true)
                local negativeStart, negativeEnd = string.find(text, "Z", -1, true)
                local matched = string.match(text, "Z", 6)
                local iterator = string.gmatch(text, "Z", 6)
                local iterated = iterator()
                return foundStart, foundEnd, negativeStart, negativeEnd, matched, iterated
                """.trimIndent(),
                "string-search-utf8-byte-init.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(6L, state.toInteger(1))
        assertEquals(6L, state.toInteger(2))
        assertEquals(6L, state.toInteger(3))
        assertEquals(6L, state.toInteger(4))
        assertEquals("Z", state.toString(5))
        assertEquals("Z", state.toString(6))
    }

    @Test
    fun `string find treats standalone magic suffix characters as literals`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local bracketStart, bracketEnd = string.find("a] * + - ?", "a]")
                local starStart, starEnd = string.find("*", "*")
                local plusStart, plusEnd = string.find("+", "+")
                local minusStart, minusEnd = string.find("-", "-")
                local questionStart, questionEnd = string.find("?", "?")
                return bracketStart, bracketEnd, starStart, starEnd, plusStart, plusEnd,
                    minusStart, minusEnd, questionStart, questionEnd
                """.trimIndent(),
                "string-find-standalone-magic.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals(1L, state.toInteger(5))
        assertEquals(1L, state.toInteger(6))
        assertEquals(1L, state.toInteger(7))
        assertEquals(1L, state.toInteger(8))
        assertEquals(1L, state.toInteger(9))
        assertEquals(1L, state.toInteger(10))
    }

    @Test
    fun `string search functions clamp zero and far negative init positions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local zeroStart, zeroEnd = string.find("abc", "a", 0)
                local farStart, farEnd = string.find("abc", "a", -99)
                local plainZeroStart, plainZeroEnd = string.find("abc", "a", 0, true)
                local matched = string.match("abc", "a", 0)
                local farMatched = string.match("abc", "a", -99)
                local iterator = string.gmatch("abc", ".", 0)
                return zeroStart, zeroEnd, farStart, farEnd, plainZeroStart, plainZeroEnd,
                    matched, farMatched, iterator()
                """.trimIndent(),
                "string-search-clamped-init.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals(1L, state.toInteger(5))
        assertEquals(1L, state.toInteger(6))
        assertEquals("a", state.toString(7))
        assertEquals("a", state.toString(8))
        assertEquals("a", state.toString(9))
    }

    @Test
    fun `string find treats closing bracket outside classes as literal`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.find("abc", "a]"), string.find("a]b", "a]")""",
                "string-find-closing-bracket.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
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
    fun `string pattern classes apply ascii predicates to raw bytes`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local e = string.char(195, 169)
                local upperE = string.char(195, 137)
                local arabicDigit = string.char(217, 163)
                local nbsp = string.char(194, 160)
                return string.match(e, "%a"),
                    string.match(e, "%w"),
                    string.match(e, "%l"),
                    string.match(upperE, "%u"),
                    string.match(arabicDigit, "%d"),
                    string.match(arabicDigit, "%x"),
                    string.match(nbsp, "%s"),
                    string.match(e, "%g"),
                    string.match(e, "%A"),
                    string.match(e, "%W")
                """.trimIndent(),
                "string-pattern-ascii-classes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
        assertTrue(state.isNil(5))
        assertTrue(state.isNil(6))
        assertTrue(state.isNil(7))
        assertTrue(state.isNil(8))
        assertEquals(listOf(195.toByte()), state.toString(9)?.luaRawBytes()?.toList())
        assertEquals(listOf(195.toByte()), state.toString(10)?.luaRawBytes()?.toList())
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
    fun `string bracket patterns treat reversed ranges as empty`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local findStart, findEnd = string.find("abc", "[z-a]")
                local replaced, count = string.gsub("abc", "[z-a]", "x")
                local negated, negatedCount = string.gsub("abc", "[^z-a]", "x")
                return string.match("m", "[z-a]"),
                    string.match("m", "[^z-a]"),
                    findStart,
                    findEnd,
                    replaced,
                    count,
                    negated,
                    negatedCount
                """.trimIndent(),
                "string-bracket-reversed-range.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertEquals("m", state.toString(2))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
        assertEquals("abc", state.toString(5))
        assertEquals(0L, state.toInteger(6))
        assertEquals("xxx", state.toString(7))
        assertEquals(3L, state.toInteger(8))
    }

    @Test
    fun `string bracket patterns support percent range endings`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(function()
                    return string.match("a", "[a-%]")
                end)
                return string.match("d", "[a-%d]"),
                    string.match("5", "[a-%d]"),
                    string.match("%", "[!-%]]"),
                    string.match("]", "[!-%]]"),
                    string.match("a", "[!-%]]"),
                    ok,
                    message
                """.trimIndent(),
                "string-bracket-percent-range-ending.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("d", state.toString(1))
        assertTrue(state.isNil(2))
        assertEquals("%", state.toString(3))
        assertEquals("]", state.toString(4))
        assertTrue(state.isNil(5))
        assertEquals(false, state.toBoolean(6))
        assertEquals("malformed pattern (missing ']')", state.toString(7))
    }

    @Test
    fun `string bracket patterns support leading closing brackets`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local closeStart, closeEnd = string.find("a]b", "[]]")
                local notCloseStart, notCloseEnd = string.find("]a", "[^]]")
                local replaced, count = string.gsub("a]b]", "[]]", "x")
                return closeStart, closeEnd, notCloseStart, notCloseEnd, replaced, count
                """.trimIndent(),
                "string-bracket-closing-bracket.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(2L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals(2L, state.toInteger(4))
        assertEquals("axbx", state.toString(5))
        assertEquals(2L, state.toInteger(6))
    }

    @Test
    fun `string bracket patterns report missing closing brackets`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local plainOk, plainMessage = pcall(string.match, "abc", "[abc")
                local negatedOk, negatedMessage = pcall(string.match, "abc", "[^abc")
                return plainOk, plainMessage, negatedOk, negatedMessage
                """.trimIndent(),
                "string-bracket-missing-close.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("malformed pattern (missing ']')", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("malformed pattern (missing ']')", state.toString(4))
    }

    @Test
    fun `string patterns use ascii character classes`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)
        state.pushString("é")
        state.setGlobal("letter")
        state.pushString("\u0663")
        state.setGlobal("digit")
        state.pushString("\u00A0")
        state.setGlobal("space")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local nonLetter = string.match(letter, "%A")
                local nonDigit = string.match(digit, "%D")
                local nonWord = string.match(letter, "%W")
                local nonGraph = string.match(letter, "%G")
                local nonSpace = string.match(space, "%S")
                return string.match(letter, "%a"), string.len(nonLetter), string.byte(nonLetter),
                    string.match(digit, "%d"), string.len(nonDigit), string.byte(nonDigit),
                    string.match(letter, "%w"), string.len(nonWord), string.byte(nonWord),
                    string.match(letter, "%g"), string.len(nonGraph), string.byte(nonGraph),
                    string.match(letter, "%l"), string.match(letter, "%u"),
                    string.match(space, "%s"), string.len(nonSpace), string.byte(nonSpace)
                """.trimIndent(),
                "string-pattern-ascii-classes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(195L, state.toInteger(3))
        assertTrue(state.isNil(4))
        assertEquals(1L, state.toInteger(5))
        assertEquals(217L, state.toInteger(6))
        assertTrue(state.isNil(7))
        assertEquals(1L, state.toInteger(8))
        assertEquals(195L, state.toInteger(9))
        assertTrue(state.isNil(10))
        assertEquals(1L, state.toInteger(11))
        assertEquals(195L, state.toInteger(12))
        assertTrue(state.isNil(13))
        assertTrue(state.isNil(14))
        assertTrue(state.isNil(15))
        assertEquals(1L, state.toInteger(16))
        assertEquals(194L, state.toInteger(17))
    }

    @Test
    fun `string patterns support escaped punctuation literals`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local commaStart, commaEnd = string.find("a,b", "%,")
                local bang = string.match("ok!", "%!")
                local replaced, count = string.gsub("a/b/c", "%/", "|")
                local dollarStart, dollarEnd = string.find("cost $", "%$")
                local dollar = string.match("end$", "%$")
                local percentAtEnd = string.match("done%", "%%$")
                local iterator = string.gmatch("a,b;c", "%p")
                return commaStart, commaEnd, bang, replaced, count, dollarStart, dollarEnd, dollar, percentAtEnd,
                    iterator(), iterator(), iterator()
                """.trimIndent(),
                "string-pattern-escaped-punctuation.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(2L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals("!", state.toString(3))
        assertEquals("a|b|c", state.toString(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals(6L, state.toInteger(6))
        assertEquals(6L, state.toInteger(7))
        assertEquals("$", state.toString(8))
        assertEquals("%", state.toString(9))
        assertEquals(",", state.toString(10))
        assertEquals(";", state.toString(11))
        assertTrue(state.isNil(12))
    }

    @Test
    fun `string patterns treat escaped trailing dollars as literals`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first, last = string.find("cost $ later", "%${'$'}")
                local matched = string.match("cost $ later", "%${'$'}")
                local replaced, count = string.gsub("a${'$'}b${'$'}", "%${'$'}", "x")
                local iterator = string.gmatch("a${'$'}b${'$'}", "%${'$'}")
                return first, last, matched, replaced, count, iterator(), iterator(), iterator()
                """.trimIndent(),
                "string-pattern-escaped-dollar.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(6L, state.toInteger(1))
        assertEquals(6L, state.toInteger(2))
        assertEquals("$", state.toString(3))
        assertEquals("axbx", state.toString(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals("$", state.toString(6))
        assertEquals("$", state.toString(7))
        assertTrue(state.isNil(8))
    }

    @Test
    fun `string patterns treat unknown percent escapes as literals`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first, last = string.find("xq", "%q")
                local matched = string.match("xq", "%q")
                local replaced, count = string.gsub("xq q", "%q", "Q")
                local iterator = string.gmatch("q q", "%q")
                return first, last, matched, replaced, count,
                    iterator(), iterator(), iterator(),
                    string.match("xq", "[%q]"),
                    string.match("xq", "[^%q]")
                """.trimIndent(),
                "string-pattern-unknown-percent-escape.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(2L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals("q", state.toString(3))
        assertEquals("xQ Q", state.toString(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals("q", state.toString(6))
        assertEquals("q", state.toString(7))
        assertTrue(state.isNil(8))
        assertEquals("q", state.toString(9))
        assertEquals("x", state.toString(10))
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
    fun `string patterns treat standalone repetition suffixes as literals`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local starStart, starEnd = string.find("a* + - ?", "*")
                local plusStart, plusEnd = string.find("a* + - ?", "+")
                local minusStart, minusEnd = string.find("a* + - ?", "-")
                local questionStart, questionEnd = string.find("a* + - ?", "?")
                local replaced, count = string.gsub("*+ -?", "[*+%-?]", "x")
                local iterator = string.gmatch("* + - ?", "[*+%-?]")
                return starStart, starEnd,
                    plusStart, plusEnd,
                    minusStart, minusEnd,
                    questionStart, questionEnd,
                    replaced, count,
                    string.match("ab", "a*b"),
                    string.match("ab", "*b"),
                    iterator(), iterator(), iterator(), iterator(), iterator()
                """.trimIndent(),
                "string-pattern-standalone-suffixes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(2L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(4L, state.toInteger(3))
        assertEquals(4L, state.toInteger(4))
        assertEquals(6L, state.toInteger(5))
        assertEquals(6L, state.toInteger(6))
        assertEquals(8L, state.toInteger(7))
        assertEquals(8L, state.toInteger(8))
        assertEquals("xx xx", state.toString(9))
        assertEquals(4L, state.toInteger(10))
        assertEquals("ab", state.toString(11))
        assertTrue(state.isNil(12))
        assertEquals("*", state.toString(13))
        assertEquals("+", state.toString(14))
        assertEquals("-", state.toString(15))
        assertEquals("?", state.toString(16))
        assertTrue(state.isNil(17))
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
    fun `string patterns return position captures`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first, last, before, after = string.find("abc", "()b()")
                local matchBefore, matchAfter = string.match("abc", "()b()")
                local iterator = string.gmatch("ab cd", "()%a+")
                local firstWord = iterator()
                local secondWord = iterator()
                local replaced, count = string.gsub("ab", "()", function(position)
                    return "[" .. position .. "]"
                end)
                local expanded, expandedCount = string.gsub("ab", "()", "%1")
                local utf8Before = string.match("éx", "()x")
                local fromSecondByte = string.match("éx", "x", 2)
                local emptyStart, emptyEnd, emptyFindPosition = string.find("éx", "()", 2)
                local emptyMatchPosition = string.match("éx", "()", 2)
                local emptyIterator = string.gmatch("éx", "()", 2)
                local emptyIteratorPosition = emptyIterator()
                local utf8Replaced = string.gsub("éx", "()x", function(position)
                    return "[" .. position .. "]"
                end)
                local utf8Expanded = string.gsub("éx", "()x", "%1")
                return first, last, before, after, matchBefore, matchAfter,
                    firstWord, secondWord, replaced, count, expanded, expandedCount,
                    utf8Before, fromSecondByte, emptyStart, emptyEnd, emptyFindPosition,
                    emptyMatchPosition, emptyIteratorPosition, utf8Replaced, utf8Expanded
                """.trimIndent(),
                "string-pattern-position-captures.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(2L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals(3L, state.toInteger(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals(3L, state.toInteger(6))
        assertEquals(1L, state.toInteger(7))
        assertEquals(4L, state.toInteger(8))
        assertEquals("[1]a[2]b[3]", state.toString(9))
        assertEquals(3L, state.toInteger(10))
        assertEquals("1a2b3", state.toString(11))
        assertEquals(3L, state.toInteger(12))
        assertEquals(3L, state.toInteger(13))
        assertEquals("x", state.toString(14))
        assertEquals(2L, state.toInteger(15))
        assertEquals(1L, state.toInteger(16))
        assertEquals(2L, state.toInteger(17))
        assertEquals(2L, state.toInteger(18))
        assertEquals(2L, state.toInteger(19))
        assertEquals("é[3]", state.toString(20))
        assertEquals("é3", state.toString(21))
    }

    @Test
    fun `string patterns enforce lua 55 capture limit`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local allowedCount = select("#", string.match("", string.rep("()", 32)))
                local ok, message = pcall(string.match, "", string.rep("()", 33))
                return allowedCount, ok, message
                """.trimIndent(),
                "string-pattern-capture-limit.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(32L, state.toInteger(1))
        assertFalse(state.toBoolean(2))
        assertEquals("too many captures", state.toString(3))
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
    fun `string patterns report invalid backreference captures`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local zeroOk, zeroMessage = pcall(string.match, "abc", "%0")
                local missingOk, missingMessage = pcall(string.match, "abc", "%1")
                local unfinishedOk, unfinishedMessage = pcall(string.match, "aa", "(a%1)")
                local position = string.match("a", "()%1")
                return zeroOk, zeroMessage, missingOk, missingMessage,
                    unfinishedOk, unfinishedMessage, position
                """.trimIndent(),
                "string-pattern-invalid-backreferences.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("invalid capture index %0", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("invalid capture index %1", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("invalid capture index %1", state.toString(6))
        assertTrue(state.isNil(7))
    }

    @Test
    fun `string patterns report invalid capture close errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local unmatchedOk, unmatchedMessage = pcall(string.match, "abc", "a)")
                local unfinishedOk, unfinishedMessage = pcall(string.match, "abc", "(a")
                return unmatchedOk, unmatchedMessage, unfinishedOk, unfinishedMessage
                """.trimIndent(),
                "string-pattern-invalid-capture-close.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("invalid pattern capture", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("unfinished capture", state.toString(4))
    }

    @Test
    fun `string patterns delay capture errors until matching path reaches them`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local missingCloseOk, missingClose = pcall(string.match, "xbc", "a)")
                local unmatchedCloseOk, unmatchedClose = pcall(string.match, "abc", "z)")
                local missingOpenOk, missingOpen = pcall(string.match, "abc", "z(a")
                local failedAfterClosed = string.match("abc", "a()z(")
                local failedAtEndAnchor = string.match("ab", "(a$")
                local reachedOpenOk, reachedOpen = pcall(string.match, "abc", "a(")
                local reachedCloseOk, reachedClose = pcall(string.match, "abc", "a)")
                local reachedOpenAtEndOk, reachedOpenAtEnd = pcall(string.match, "a", "(a$")
                return missingCloseOk, missingClose, unmatchedCloseOk, unmatchedClose,
                    missingOpenOk, missingOpen, failedAfterClosed, failedAtEndAnchor,
                    reachedOpenOk, reachedOpen, reachedCloseOk, reachedClose,
                    reachedOpenAtEndOk, reachedOpenAtEnd
                """.trimIndent(),
                "string-pattern-delayed-capture-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.isNil(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.isNil(4))
        assertTrue(state.toBoolean(5))
        assertTrue(state.isNil(6))
        assertTrue(state.isNil(7))
        assertTrue(state.isNil(8))
        assertFalse(state.toBoolean(9))
        assertEquals("unfinished capture", state.toString(10))
        assertFalse(state.toBoolean(11))
        assertEquals("invalid pattern capture", state.toString(12))
        assertFalse(state.toBoolean(13))
        assertEquals("unfinished capture", state.toString(14))
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
    fun `string patterns report malformed balanced match arguments`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local missingBothOk, missingBothMessage = pcall(string.match, "abc", "%b")
                local missingCloseOk, missingCloseMessage = pcall(string.match, "abc", "%b(")
                return missingBothOk, missingBothMessage, missingCloseOk, missingCloseMessage
                """.trimIndent(),
                "string-pattern-balanced-malformed.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("malformed pattern (missing arguments to '%b')", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("malformed pattern (missing arguments to '%b')", state.toString(4))
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
    fun `string patterns report malformed frontier arguments`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local missingSetOk, missingSetMessage = pcall(string.match, "abc", "%f")
                local missingBracketOk, missingBracketMessage = pcall(string.match, "abc", "%fa")
                return missingSetOk, missingSetMessage, missingBracketOk, missingBracketMessage
                """.trimIndent(),
                "string-pattern-frontier-malformed.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("missing '[' after '%f' in pattern", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("missing '[' after '%f' in pattern", state.toString(4))
    }

    @Test
    fun `string patterns report dangling percent errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local trailingOk, trailingMessage = pcall(string.match, "abc", "%")
                local bracketOk, bracketMessage = pcall(string.match, "abc", "[%")
                return trailingOk, trailingMessage, bracketOk, bracketMessage
                """.trimIndent(),
                "string-pattern-dangling-percent.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("malformed pattern (ends with '%')", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("malformed pattern (ends with '%')", state.toString(4))
    }

    @Test
    fun `string patterns honor anchors around literal bodies`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local startFirst, startLast = string.find("abc", "^ab")
                local startMissing = string.match("xabc", "^ab")
                local endFirst, endLast = string.find("abc", "bc$")
                local endMissing = string.match("abcy", "bc$")
                local whole = string.match("abc", "^abc$")
                local emptyStart, emptyEnd = string.find("abc", "^")
                local emptyAtEndStart, emptyAtEndEnd = string.find("abc", "$")
                return startFirst, startLast, startMissing, endFirst, endLast, endMissing, whole,
                    emptyStart, emptyEnd, emptyAtEndStart, emptyAtEndEnd
                """.trimIndent(),
                "string-pattern-literal-anchors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertTrue(state.isNil(3))
        assertEquals(2L, state.toInteger(4))
        assertEquals(3L, state.toInteger(5))
        assertTrue(state.isNil(6))
        assertEquals("abc", state.toString(7))
        assertEquals(1L, state.toInteger(8))
        assertEquals(0L, state.toInteger(9))
        assertEquals(4L, state.toInteger(10))
        assertEquals(3L, state.toInteger(11))
    }

    @Test
    fun `string patterns apply start anchors at init positions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first, last = string.find("xxabc", "^abc", 3)
                local missing = string.match("xxabc", "^abc", 2)
                local matched = string.match("xxabc", "^abc", -3)
                local emptyFirst, emptyLast = string.find("abc", "^", 2)
                return first, last, missing, matched, emptyFirst, emptyLast
                """.trimIndent(),
                "string-pattern-anchor-init.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3L, state.toInteger(1))
        assertEquals(5L, state.toInteger(2))
        assertTrue(state.isNil(3))
        assertEquals("abc", state.toString(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals(1L, state.toInteger(6))
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
    fun `string format applies valid string modifiers`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local basic = string.format("%5s|%-5s|%.2s|%5.2s", "ab", "ab", "abcd", "abcd")
                local firstUtf8Byte = string.format("%.1s", "é")
                local wideUtf8 = string.format("%5s", "é")
                local paddedFirstUtf8Byte = string.format("%3.1s", "é")
                local high = string.format("%.1s", string.char(255, 65))
                local leftHigh = string.format("%-3.1s", string.char(255))
                local w1, w2, w3, w4, w5 = string.byte(wideUtf8, 1, -1)
                local p1, p2, p3 = string.byte(paddedFirstUtf8Byte, 1, -1)
                local l1, l2, l3 = string.byte(leftHigh, 1, -1)
                return basic,
                    string.len(firstUtf8Byte), string.byte(firstUtf8Byte), string.format("%.2s", "é") == "é",
                    string.len(wideUtf8), w1, w2, w3, w4, w5,
                    string.len(paddedFirstUtf8Byte), p1, p2, p3,
                    string.len(high), string.byte(high),
                    string.len(leftHigh), l1, l2, l3
                """.trimIndent(),
                "string-format-string-modifiers.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("   ab|ab   |ab|   ab", state.toString(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(195L, state.toInteger(3))
        assertTrue(state.toBoolean(4))
        assertEquals(5L, state.toInteger(5))
        assertEquals(32L, state.toInteger(6))
        assertEquals(32L, state.toInteger(7))
        assertEquals(32L, state.toInteger(8))
        assertEquals(195L, state.toInteger(9))
        assertEquals(169L, state.toInteger(10))
        assertEquals(3L, state.toInteger(11))
        assertEquals(32L, state.toInteger(12))
        assertEquals(32L, state.toInteger(13))
        assertEquals(195L, state.toInteger(14))
        assertEquals(1L, state.toInteger(15))
        assertEquals(255L, state.toInteger(16))
        assertEquals(3L, state.toInteger(17))
        assertEquals(255L, state.toInteger(18))
        assertEquals(32L, state.toInteger(19))
        assertEquals(32L, state.toInteger(20))
    }

    @Test
    fun `string format accepts repeated valid flags`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.format("%--5s|%++5d|%005d|%--.1f|%##5x", "ab", 7, 7, 1.25, 255)""",
                "string-format-repeated-flags.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("ab   |   +7|00007|1.3| 0xff", state.toString(1))
    }

    @Test
    fun `string format applies printf flag precedence`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.format("%+- 05d|%+ 05d|%- 08.1f|%+ 08.1f", 7, 7, 1.25, 1.25)""",
                "string-format-flag-precedence.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("+7   |+0007| 1.3    |+00001.3", state.toString(1))
    }

    @Test
    fun `string format rejects overlong conversion specifications`() {
        val validState = LuaState.create()
        LuaStdlib.openString(validState)

        assertEquals(
            LuaStatus.OK,
            validState.load("""return string.format("%" .. string.rep("-", 20) .. "d", 12)""", "string-format-max-spec.lua"),
        )
        assertEquals(LuaStatus.OK, validState.pcall(0, -1), validState.toString(-1))
        assertEquals("12", validState.toString(1))

        val overlongState = LuaState.create()
        LuaStdlib.openString(overlongState)

        assertEquals(
            LuaStatus.OK,
            overlongState.load("""return string.format("%" .. string.rep("-", 21) .. "d", 12)""", "string-format-overlong-spec-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, overlongState.pcall(0, -1))

        assertIs<LuaRuntimeException>(overlongState.getLastError())
        assertEquals("invalid format (too long)", overlongState.toString(-1))
    }

    @Test
    fun `string format preserves zeros for unmodified string conversions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return string.format("%s", "a" .. string.char(0) .. "b")""", "string-format-string-zero.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("a\u0000b", state.toString(1))
    }

    @Test
    fun `string format string conversion uses tostring metadata`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local value = setmetatable({name = "formatted"}, {
                    __tostring = function(target)
                        return "table:" .. target.name
                    end,
                })
                debug.setmetatable("alpha", {
                    __tostring = function(target)
                        return "string:" .. target
                    end,
                })
                local function fn() end
                debug.setmetatable(fn, { __name = "Callable" })
                local formatted = string.format("%s", value)
                local formattedString = string.format("%s", "alpha")
                local formattedFunction = string.format("%s", fn)
                local ok, message = pcall(string.format, "%q", value)
                debug.setmetatable("alpha", nil)
                debug.setmetatable(fn, nil)
                return formatted, formattedString, formattedFunction, ok, message
                """.trimIndent(),
                "string-format-tostring-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table:formatted", state.toString(1))
        assertEquals("string:alpha", state.toString(2))
        assertTrue(state.toString(3)?.matches(Regex("""Callable: [0-9a-f]+""")) == true)
        assertFalse(state.toBoolean(4))
        assertEquals("bad argument #2 to 'format' (value has no literal form)", state.toString(5))
    }

    @Test
    fun `string format string conversion formats numbers like lua`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numeric = setmetatable({}, {
                    __tostring = function()
                        return 1e15
                    end,
                })
                return string.format("%s|%s|%s|%s|%s", 1e14, 1e15, 1e-5, 1 / 0, numeric)
                """.trimIndent(),
                "string-format-string-number-format.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("100000000000000.0|1e+15|1e-05|inf|1e+15", state.toString(1))
    }

    @Test
    fun `string format string conversion reports table and function identities`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local tableText = string.format("%s", {})
                local functionText = string.format("%s", function() end)
                return tableText, functionText
                """.trimIndent(),
                "string-format-identities.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toString(1)?.matches(Regex("""table: [0-9a-f]+""")) == true)
        assertTrue(state.toString(2)?.matches(Regex("""function: [0-9a-f]+""")) == true)
    }

    @Test
    fun `string format quotes primitive literals`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return string.format("%q %q %q %q", nil, true, false, 42),
                    string.format("%q", string.char(7, 8, 12, 11)),
                    string.format("%q", "a" .. string.char(0, 31, 127) .. "b"),
                    string.format("%q", "a" .. string.char(10) .. "\\" .. '"' .. "b"),
                    string.format("%q", "a" .. string.char(0) .. "1"),
                    string.format("%q", string.char(1) .. "١"),
                    string.format("%q|%q|%q|%q|%q|%q|%q|%q",
                        1.5, 0.5, 0.0, -0.0, math.huge, -math.huge, 0 / 0, math.mininteger)
                """.trimIndent(),
                "string-format-quote.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("nil true false 42", state.toString(1))
        assertEquals("\"\\7\\8\\12\\11\"", state.toString(2))
        assertEquals("\"a\\0\\31\\127b\"", state.toString(3))
        assertEquals("\"a\\\n\\\\\\\"b\"", state.toString(4))
        assertEquals("\"a\\0001\"", state.toString(5))
        assertEquals("\"\\1١\"", state.toString(6))
        assertEquals(
            "0x1.8p+0|0x1p-1|0x0p+0|-0x0p+0|1e9999|-1e9999|(0/0)|0x8000000000000000",
            state.toString(7),
        )
    }

    @Test
    fun `string format applies width to character conversions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local formatted = string.format("%3c|%-3c|%c", 65, 66, 256)
                local high = string.format("%c", -1)
                local paddedHigh = string.format("%3c", 255)
                local pad1, pad2, highByte = string.byte(paddedHigh, 1, -1)
                return formatted,
                    string.len(high), string.byte(high),
                    string.len(paddedHigh), pad1, pad2, highByte
                """.trimIndent(),
                "string-format-char-width.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("  A|B  |\u0000", state.toString(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(255L, state.toInteger(3))
        assertEquals(3L, state.toInteger(4))
        assertEquals(32L, state.toInteger(5))
        assertEquals(32L, state.toInteger(6))
        assertEquals(255L, state.toInteger(7))
    }

    @Test
    fun `string format renders hexadecimal float conversions`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return string.format("%a|%A|%.2a|%12a|%012a|%+12a|%.0a|%#.0a|%a|%a|%a|%a|%A|%a|%A|%a|%A",
                    1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5,
                    1.0, 0.0, -0.0, math.huge, math.huge, -math.huge, -math.huge, 0 / 0, 0 / 0)
                """.trimIndent(),
                "string-format-hex-float.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(
            "0x1.8p+0|0X1.8P+0|0x1.80p+0|    0x1.8p+0|0x00001.8p+0|   +0x1.8p+0|0x1p+0|0x1.p+0|" +
                "0x1p+0|0x0p+0|-0x0p+0|inf|INF|-inf|-INF|nan|NAN",
            state.toString(1),
        )
    }

    @Test
    fun `string format renders non finite decimal float conversions`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return string.format("%f|%e|%g|%E|%G|%+f|% f|%10g|%-10G|%f|%G",
                    math.huge, math.huge, math.huge, math.huge, math.huge,
                    math.huge, math.huge, math.huge, math.huge, -math.huge, 0 / 0)
                """.trimIndent(),
                "string-format-decimal-non-finite.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("inf|inf|inf|INF|INF|+inf| inf|       inf|INF       |-inf|NAN", state.toString(1))
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
    fun `string format omits alternate prefixes for zero integer values`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.format("%#x|%#X|%#o|%#5x|%#05x|%-#5o", 0, 0, 0, 0, 0, 0)""",
                "string-format-zero-alternate.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("0|0|0|    0|00000|0    ", state.toString(1))
    }

    @Test
    fun `string format rejects invalid integer flags`() {
        val hashState = LuaState.create()
        LuaStdlib.openString(hashState)

        assertEquals(LuaStatus.OK, hashState.load("""return string.format("%#d", 1)""", "string-format-integer-hash-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, hashState.pcall(0, -1))

        assertIs<LuaRuntimeException>(hashState.getLastError())
        assertEquals("invalid conversion specification: '%#d'", hashState.toString(-1))

        val plusState = LuaState.create()
        LuaStdlib.openString(plusState)

        assertEquals(LuaStatus.OK, plusState.load("""return string.format("%+u", 1)""", "string-format-unsigned-plus-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, plusState.pcall(0, -1))

        assertIs<LuaRuntimeException>(plusState.getLastError())
        assertEquals("invalid conversion specification: '%+u'", plusState.toString(-1))

        val spaceState = LuaState.create()
        LuaStdlib.openString(spaceState)

        assertEquals(LuaStatus.OK, spaceState.load("""return string.format("% x", 1)""", "string-format-hex-space-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, spaceState.pcall(0, -1))

        assertIs<LuaRuntimeException>(spaceState.getLastError())
        assertEquals("invalid conversion specification: '% x'", spaceState.toString(-1))
    }

    @Test
    fun `string format rejects uppercase fixed float conversion`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.format("%F", 1.5)""", "string-format-uppercase-fixed-float.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("invalid conversion '%F' to 'format'", state.toString(-1))
    }

    @Test
    fun `string format reports argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(string.format, "%d", "bad")
                local okFraction, fractionMessage = pcall(string.format, "%d", 1.5)
                local okStringNumber, stringNumberMessage = pcall(string.format, "%d", "1.5")
                return okString, stringMessage,
                    okFraction, fractionMessage,
                    okStringNumber, stringNumberMessage
                """.trimIndent(),
                "string-format-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'format' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'format' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'format' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `string format reports missing argument errors`() {
        val firstMissingState = LuaState.create()
        LuaStdlib.openString(firstMissingState)

        assertEquals(LuaStatus.OK, firstMissingState.load("""return string.format("%s")""", "string-format-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, firstMissingState.pcall(0, -1))

        assertIs<LuaRuntimeException>(firstMissingState.getLastError())
        assertEquals("bad argument #2 to 'format' (no value)", firstMissingState.toString(-1))

        val laterMissingState = LuaState.create()
        LuaStdlib.openString(laterMissingState)

        assertEquals(
            LuaStatus.OK,
            laterMissingState.load("""return string.format("%% %s %d", "x")""", "string-format-later-missing-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, laterMissingState.pcall(0, -1))

        assertIs<LuaRuntimeException>(laterMissingState.getLastError())
        assertEquals("bad argument #3 to 'format' (no value)", laterMissingState.toString(-1))
    }

    @Test
    fun `string format reports incomplete conversion errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local missingOk, missingMessage = pcall(string.format, "%")
                local percentOk, percentMessage = pcall(string.format, "%", 1)
                local widthOk, widthMessage = pcall(string.format, "%100", 1)
                local precisionOk, precisionMessage = pcall(string.format, "%.", 1)
                return missingOk, missingMessage,
                    percentOk, percentMessage,
                    widthOk, widthMessage,
                    precisionOk, precisionMessage
                """.trimIndent(),
                "string-format-incomplete-conversion-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'format' (no value)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("invalid conversion '%' to 'format'", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("invalid conversion '%100' to 'format'", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("invalid conversion '%.' to 'format'", state.toString(8))
    }

    @Test
    fun `string format rejects invalid string modifiers`() {
        val plusState = LuaState.create()
        LuaStdlib.openString(plusState)

        assertEquals(LuaStatus.OK, plusState.load("""return string.format("%+s", "x")""", "string-format-string-plus-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, plusState.pcall(0, -1))

        assertIs<LuaRuntimeException>(plusState.getLastError())
        assertEquals("invalid conversion specification: '%+s'", plusState.toString(-1))

        val zeroState = LuaState.create()
        LuaStdlib.openString(zeroState)

        assertEquals(LuaStatus.OK, zeroState.load("""return string.format("%05s", "x")""", "string-format-string-zero-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, zeroState.pcall(0, -1))

        assertIs<LuaRuntimeException>(zeroState.getLastError())
        assertEquals("invalid conversion specification: '%05s'", zeroState.toString(-1))
    }

    @Test
    fun `string format rejects modified string conversions containing zeros`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return string.format("%5s", "a" .. string.char(0) .. "b")""", "string-format-string-zero-modifier-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'format' (string contains zeros)", state.toString(-1))
    }

    @Test
    fun `string format rejects oversized width and precision`() {
        val integerState = LuaState.create()
        LuaStdlib.openString(integerState)

        assertEquals(LuaStatus.OK, integerState.load("""return string.format("%100d", 1)""", "string-format-wide-integer-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, integerState.pcall(0, -1))

        assertIs<LuaRuntimeException>(integerState.getLastError())
        assertEquals("invalid conversion specification: '%100d'", integerState.toString(-1))

        val floatState = LuaState.create()
        LuaStdlib.openString(floatState)

        assertEquals(LuaStatus.OK, floatState.load("""return string.format("%.100f", 1)""", "string-format-precise-float-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, floatState.pcall(0, -1))

        assertIs<LuaRuntimeException>(floatState.getLastError())
        assertEquals("invalid conversion specification: '%.100f'", floatState.toString(-1))

        val stringState = LuaState.create()
        LuaStdlib.openString(stringState)

        assertEquals(LuaStatus.OK, stringState.load("""return string.format("%100s", "x")""", "string-format-wide-string-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, stringState.pcall(0, -1))

        assertIs<LuaRuntimeException>(stringState.getLastError())
        assertEquals("invalid conversion specification: '%100s'", stringState.toString(-1))
    }

    @Test
    fun `string format rejects length modifiers`() {
        val stringState = LuaState.create()
        LuaStdlib.openString(stringState)

        assertEquals(LuaStatus.OK, stringState.load("""return string.format("%ls", "x")""", "string-format-string-length-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, stringState.pcall(0, -1))

        assertIs<LuaRuntimeException>(stringState.getLastError())
        assertEquals("invalid conversion '%l' to 'format'", stringState.toString(-1))

        val integerState = LuaState.create()
        LuaStdlib.openString(integerState)

        assertEquals(LuaStatus.OK, integerState.load("""return string.format("%Ld", 1)""", "string-format-integer-length-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, integerState.pcall(0, -1))

        assertIs<LuaRuntimeException>(integerState.getLastError())
        assertEquals("invalid conversion '%L' to 'format'", integerState.toString(-1))

        val hexState = LuaState.create()
        LuaStdlib.openString(hexState)

        assertEquals(LuaStatus.OK, hexState.load("""return string.format("%hhx", 1)""", "string-format-hex-length-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, hexState.pcall(0, -1))

        assertIs<LuaRuntimeException>(hexState.getLastError())
        assertEquals("invalid conversion '%h' to 'format'", hexState.toString(-1))

        val uppercaseFloatState = LuaState.create()
        LuaStdlib.openString(uppercaseFloatState)

        assertEquals(
            LuaStatus.OK,
            uppercaseFloatState.load("""return string.format("%F", 1)""", "string-format-uppercase-float-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, uppercaseFloatState.pcall(0, -1))

        assertIs<LuaRuntimeException>(uppercaseFloatState.getLastError())
        assertEquals("invalid conversion '%F' to 'format'", uppercaseFloatState.toString(-1))
    }

    @Test
    fun `string format reports char argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(string.format, "%c", "bad")
                local okFraction, fractionMessage = pcall(string.format, "%c", 65.5)
                return okString, stringMessage, okFraction, fractionMessage
                """.trimIndent(),
                "string-format-char-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'format' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'format' (number has no integer representation)", state.toString(4))
    }

    @Test
    fun `string format rejects invalid character modifiers`() {
        val plusState = LuaState.create()
        LuaStdlib.openString(plusState)

        assertEquals(LuaStatus.OK, plusState.load("""return string.format("%+c", 65)""", "string-format-char-plus-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, plusState.pcall(0, -1))

        assertIs<LuaRuntimeException>(plusState.getLastError())
        assertEquals("invalid conversion specification: '%+c'", plusState.toString(-1))

        val zeroState = LuaState.create()
        LuaStdlib.openString(zeroState)

        assertEquals(LuaStatus.OK, zeroState.load("""return string.format("%05c", 65)""", "string-format-char-zero-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, zeroState.pcall(0, -1))

        assertIs<LuaRuntimeException>(zeroState.getLastError())
        assertEquals("invalid conversion specification: '%05c'", zeroState.toString(-1))

        val precisionState = LuaState.create()
        LuaStdlib.openString(precisionState)

        assertEquals(LuaStatus.OK, precisionState.load("""return string.format("%.1c", 65)""", "string-format-char-precision-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, precisionState.pcall(0, -1))

        assertIs<LuaRuntimeException>(precisionState.getLastError())
        assertEquals("invalid conversion specification: '%.1c'", precisionState.toString(-1))
    }

    @Test
    fun `string format renders pointer conversions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.format("%p|%18p|%-18p|%p|%p|%p|%p", {}, {}, {}, nil, true, 12, "x")""",
                "string-format-pointer.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        val parts = state.toString(1)?.split("|") ?: emptyList()
        val pointerPattern = Regex("""0x[0-9a-f]+""")
        assertEquals(7, parts.size)
        assertTrue(pointerPattern.matches(parts[0]))
        assertEquals(18, parts[1].length)
        assertTrue(pointerPattern.matches(parts[1].trimStart()))
        assertEquals(18, parts[2].length)
        assertTrue(pointerPattern.matches(parts[2].trimEnd()))
        assertEquals("(null)", parts[3])
        assertEquals("(null)", parts[4])
        assertEquals("(null)", parts[5])
        assertTrue(pointerPattern.matches(parts[6]))
    }

    @Test
    fun `string format rejects invalid pointer modifiers`() {
        val plusState = LuaState.create()
        LuaStdlib.openString(plusState)

        assertEquals(LuaStatus.OK, plusState.load("""return string.format("%+p", {})""", "string-format-pointer-plus-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, plusState.pcall(0, -1))

        assertIs<LuaRuntimeException>(plusState.getLastError())
        assertEquals("invalid conversion specification: '%+p'", plusState.toString(-1))

        val zeroState = LuaState.create()
        LuaStdlib.openString(zeroState)

        assertEquals(LuaStatus.OK, zeroState.load("""return string.format("%05p", {})""", "string-format-pointer-zero-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, zeroState.pcall(0, -1))

        assertIs<LuaRuntimeException>(zeroState.getLastError())
        assertEquals("invalid conversion specification: '%05p'", zeroState.toString(-1))

        val precisionState = LuaState.create()
        LuaStdlib.openString(precisionState)

        assertEquals(LuaStatus.OK, precisionState.load("""return string.format("%.1p", {})""", "string-format-pointer-precision-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, precisionState.pcall(0, -1))

        assertIs<LuaRuntimeException>(precisionState.getLastError())
        assertEquals("invalid conversion specification: '%.1p'", precisionState.toString(-1))
    }

    @Test
    fun `string format rejects quote modifiers`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.format("%5q", "x")""", "string-format-quote-modifier-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("specifier '%q' cannot have modifiers", state.toString(-1))
    }

    @Test
    fun `string packsize returns fixed format sizes`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return string.packsize("bBhH"),
                    string.packsize("lLjJTfdn"),
                    string.packsize("iI1I2I4I8"),
                    string.packsize("=I4 <I2 >I8")
                """.trimIndent(),
                "string-packsize-fixed.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(6L, state.toInteger(1))
        assertEquals(60L, state.toInteger(2))
        assertEquals(19L, state.toInteger(3))
        assertEquals(14L, state.toInteger(4))
    }

    @Test
    fun `string packsize applies lua alignment rules`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return string.packsize("x!4h"),
                    string.packsize("x!4d"),
                    string.packsize("!4xd"),
                    string.packsize("!1xd"),
                    string.packsize("Xx"),
                    string.packsize("xXh"),
                    string.packsize("xxXh")
                """.trimIndent(),
                "string-packsize-alignment.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(4L, state.toInteger(1))
        assertEquals(12L, state.toInteger(2))
        assertEquals(12L, state.toInteger(3))
        assertEquals(9L, state.toInteger(4))
        assertEquals(0L, state.toInteger(5))
        assertEquals(1L, state.toInteger(6))
        assertEquals(2L, state.toInteger(7))
    }

    @Test
    fun `string pack preserves unpacked raw byte strings`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local byte = string.unpack("c1", string.pack("B", 255))
                local fixed = string.pack("c1", byte)
                local sized = string.pack("s1", byte)
                local zeroed = string.pack("z", byte)
                local fixedByte = string.unpack("B", fixed)
                local lengthByte, sizedByte = string.unpack("B B", sized)
                local zeroByte, terminator = string.unpack("B B", zeroed)
                local utf8Fixed = string.pack("c2", "é")
                local utf8Sized = string.pack("s1", "é")
                local utf8Zeroed = string.pack("z", "é")
                local fixedFirst, fixedSecond = string.unpack("B B", utf8Fixed)
                local sizedLength, sizedFirst, sizedSecond = string.unpack("B B B", utf8Sized)
                local zeroedFirst, zeroedSecond, zeroedTerminator = string.unpack("B B B", utf8Zeroed)
                local unpackedFixed = string.unpack("c2", utf8Fixed)
                local unpackedFirst, unpackedSecond = string.byte(unpackedFixed, 1, -1)
                local packedUtf8EqualsLiteral = string.pack("BB", 195, 169) == "é"
                return fixedByte, lengthByte, sizedByte, zeroByte, terminator,
                    fixedFirst, fixedSecond,
                    sizedLength, sizedFirst, sizedSecond,
                    zeroedFirst, zeroedSecond, zeroedTerminator,
                    unpackedFirst, unpackedSecond, packedUtf8EqualsLiteral
                """.trimIndent(),
                "string-pack-raw-byte-strings.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(255L, state.toInteger(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(255L, state.toInteger(3))
        assertEquals(255L, state.toInteger(4))
        assertEquals(0L, state.toInteger(5))
        assertEquals(195L, state.toInteger(6))
        assertEquals(169L, state.toInteger(7))
        assertEquals(2L, state.toInteger(8))
        assertEquals(195L, state.toInteger(9))
        assertEquals(169L, state.toInteger(10))
        assertEquals(195L, state.toInteger(11))
        assertEquals(169L, state.toInteger(12))
        assertEquals(0L, state.toInteger(13))
        assertEquals(195L, state.toInteger(14))
        assertEquals(169L, state.toInteger(15))
        assertTrue(state.toBoolean(16))
    }

    @Test
    fun `string packsize reports source compatible format errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local variableOk, variableMessage = pcall(string.packsize, "s")
                local zeroOk, zeroMessage = pcall(string.packsize, "i0")
                local largeOk, largeMessage = pcall(string.packsize, "i17")
                local alignOk, alignMessage = pcall(string.packsize, "!3i")
                local invalidOk, invalidMessage = pcall(string.packsize, "?")
                local charOk, charMessage = pcall(string.packsize, "c")
                local nextOk, nextMessage = pcall(string.packsize, "Xz")
                return variableOk, variableMessage,
                    zeroOk, zeroMessage,
                    largeOk, largeMessage,
                    alignOk, alignMessage,
                    invalidOk, invalidMessage,
                    charOk, charMessage,
                    nextOk, nextMessage
                """.trimIndent(),
                "string-packsize-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'packsize' (variable-length format)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("integral size (0) out of limits [1,16]", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("integral size (17) out of limits [1,16]", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals(
            "bad argument #1 to 'packsize' (format asks for alignment not power of 2)",
            state.toString(8),
        )
        assertFalse(state.toBoolean(9))
        assertEquals("invalid format option '?'", state.toString(10))
        assertFalse(state.toBoolean(11))
        assertEquals("missing size for format option 'c'", state.toString(12))
        assertFalse(state.toBoolean(13))
        assertEquals(
            "bad argument #1 to 'packsize' (invalid next option for option 'X')",
            state.toString(14),
        )
    }

    @Test
    fun `string pack and unpack fixed integers`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local packed = string.pack("<bBI2", -1, 255, 0x1234)
                local a, b, c, pos = string.unpack("<bBI2", packed)
                local big = string.pack(">I2", 0x1234)
                local bigValue, bigPos = string.unpack(">I2", big)
                return a, b, c, pos, bigValue, bigPos
                """.trimIndent(),
                "string-pack-unpack-fixed-integers.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(-1L, state.toInteger(1))
        assertEquals(255L, state.toInteger(2))
        assertEquals(0x1234L, state.toInteger(3))
        assertEquals(5L, state.toInteger(4))
        assertEquals(0x1234L, state.toInteger(5))
        assertEquals(3L, state.toInteger(6))
    }

    @Test
    fun `string pack reports integer argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(string.pack, "b", "bad")
                local okFraction, fractionMessage = pcall(string.pack, "b", 1.5)
                local okStringNumber, stringNumberMessage = pcall(string.pack, "b", "1.5")
                return okString, stringMessage,
                    okFraction, fractionMessage,
                    okStringNumber, stringNumberMessage
                """.trimIndent(),
                "string-pack-integer-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'pack' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'pack' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'pack' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `string pack reports format errors with pack caller`() {
        fun assertPackError(chunk: String, chunkName: String, expected: String) {
            val state = LuaState.create()
            LuaStdlib.openString(state)

            assertEquals(LuaStatus.OK, state.load(chunk, chunkName))
            assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))
            assertIs<LuaRuntimeException>(state.getLastError())
            assertEquals(expected, state.toString(-1))
        }

        assertPackError(
            """return string.pack("!3i4", 0)""",
            "string-pack-align-error.lua",
            "bad argument #1 to 'pack' (format asks for alignment not power of 2)",
        )
        assertPackError(
            """return string.pack("Xc1", "a")""",
            "string-pack-padding-target-error.lua",
            "bad argument #1 to 'pack' (invalid next option for option 'X')",
        )
        assertPackError(
            """return string.pack("c2147483648", "")""",
            "string-pack-fixed-string-too-long.lua",
            "bad argument #1 to 'pack' (result too long)",
        )
    }

    @Test
    fun `string pack and unpack honor padding alignment and fixed strings`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local aligned = string.pack(">xXh h", 0x1234)
                local b1, b2, b3 = string.byte(aligned, 1, 3)
                local value, pos = string.unpack(">xXh h", aligned)
                local fixed = string.pack("c4", "ab")
                local fixedValue, fixedPos = string.unpack("c4", fixed)
                return b1, b2, b3, value, pos, fixedValue, fixedPos
                """.trimIndent(),
                "string-pack-unpack-padding-alignment.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(0L, state.toInteger(1))
        assertEquals(0x12L, state.toInteger(2))
        assertEquals(0x34L, state.toInteger(3))
        assertEquals(0x1234L, state.toInteger(4))
        assertEquals(4L, state.toInteger(5))
        assertEquals("ab\u0000\u0000", state.toString(6))
        assertEquals(5L, state.toInteger(7))
    }

    @Test
    fun `string pack and unpack allow zero length fixed strings`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local packed = string.pack("c0B", "", 7)
                local empty, byte, pos = string.unpack("c0B", packed)
                local skipped, skipPos = string.unpack("c0", "abc")
                local zero = string.pack("c0", "")
                return string.packsize("c0"), #packed, #empty, byte, pos, #skipped, skipPos, #zero
                """.trimIndent(),
                "string-pack-unpack-zero-fixed-string.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(0L, state.toInteger(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(0L, state.toInteger(3))
        assertEquals(7L, state.toInteger(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals(0L, state.toInteger(6))
        assertEquals(1L, state.toInteger(7))
        assertEquals(0L, state.toInteger(8))
    }

    @Test
    fun `string pack and unpack fixed floating point values`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local float = string.pack(">f", 1.0)
                local floatBits = string.unpack(">I4", float)
                local floatValue, floatPos = string.unpack(">f", float)
                local doubleValue, doublePos = string.unpack("<d", string.pack("<d", -13.5))
                local numberValue, numberPos = string.unpack(">n", string.pack(">n", 0.25))
                return floatBits, floatValue, floatPos, doubleValue, doublePos, numberValue, numberPos
                """.trimIndent(),
                "string-pack-unpack-fixed-floating.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(0x3f800000L, state.toInteger(1))
        assertEquals(1.0, state.toNumber(2) ?: error("missing float result"), 0.0)
        assertEquals(5L, state.toInteger(3))
        assertEquals(-13.5, state.toNumber(4) ?: error("missing double result"), 0.0)
        assertEquals(9L, state.toInteger(5))
        assertEquals(0.25, state.toNumber(6) ?: error("missing number result"), 0.0)
        assertEquals(9L, state.toInteger(7))
    }

    @Test
    fun `string pack and unpack variable strings`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local prefixed = string.pack("<s1", "abc")
                local length = string.unpack("<B", prefixed)
                local value, pos = string.unpack("<s1", prefixed)
                local zero = string.pack("z", "hi")
                local zeroValue, zeroPos = string.unpack("z", zero)
                return length, value, pos, zeroValue, zeroPos
                """.trimIndent(),
                "string-pack-unpack-variable-strings.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(3L, state.toInteger(1))
        assertEquals("abc", state.toString(2))
        assertEquals(5L, state.toInteger(3))
        assertEquals("hi", state.toString(4))
        assertEquals(4L, state.toInteger(5))
    }

    @Test
    fun `string pack and unpack report fixed integer errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local signedOk, signedMessage = pcall(string.pack, "b", 128)
                local unsignedOk, unsignedMessage = pcall(string.pack, "B", 256)
                local shortOk, shortMessage = pcall(string.unpack, "I4", "a")
                local lengthOk, lengthMessage = pcall(string.pack, "s1", string.rep("a", 256))
                local zeroOk, zeroMessage = pcall(string.unpack, "z", "abc")
                return signedOk, signedMessage,
                    unsignedOk, unsignedMessage,
                    shortOk, shortMessage,
                    lengthOk, lengthMessage,
                    zeroOk, zeroMessage
                """.trimIndent(),
                "string-pack-unpack-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'pack' (integer overflow)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'pack' (unsigned overflow)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'unpack' (data string too short)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #2 to 'pack' (string length does not fit in given size)", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("bad argument #2 to 'unpack' (unfinished string for format 'z')", state.toString(10))

        val alignState = LuaState.create()
        LuaStdlib.openString(alignState)

        assertEquals(LuaStatus.OK, alignState.load("""return string.unpack("!3i4", "abcd")""", "string-unpack-align-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, alignState.pcall(0, -1))
        assertIs<LuaRuntimeException>(alignState.getLastError())
        assertEquals(
            "bad argument #1 to 'unpack' (format asks for alignment not power of 2)",
            alignState.toString(-1),
        )

        val paddingState = LuaState.create()
        LuaStdlib.openString(paddingState)

        assertEquals(
            LuaStatus.OK,
            paddingState.load("""return string.unpack("Xc1", "a")""", "string-unpack-padding-target-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, paddingState.pcall(0, -1))
        assertIs<LuaRuntimeException>(paddingState.getLastError())
        assertEquals(
            "bad argument #1 to 'unpack' (invalid next option for option 'X')",
            paddingState.toString(-1),
        )
    }

    @Test
    fun `string unpack honors start positions and alignment`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local packed = string.pack("c2!4xi4", "ab", 0x01020304)
                local value, nextPosition = string.unpack("!4xi4", packed, 3)
                local tail, tailNext = string.unpack("c2", packed, -3)
                return value, nextPosition, tail, tailNext
                """.trimIndent(),
                "string-unpack-position.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(0x01020304L, state.toInteger(1))
        assertEquals(9L, state.toInteger(2))
        assertEquals("\u0003\u0002", state.toString(3))
        assertEquals(8L, state.toInteger(4))
    }

    @Test
    fun `string unpack reports position argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(string.unpack, "b", "a", "bad")
                local okFraction, fractionMessage = pcall(string.unpack, "b", "a", 1.5)
                local okStringNumber, stringNumberMessage = pcall(string.unpack, "b", "a", "1.5")
                return okString, stringMessage,
                    okFraction, fractionMessage,
                    okStringNumber, stringNumberMessage
                """.trimIndent(),
                "string-unpack-position-argument-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #3 to 'unpack' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #3 to 'unpack' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'unpack' (number has no integer representation)", state.toString(6))
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
    fun `string gsub applies start anchors once`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local replaced, count = string.gsub("abcabc", "^abc", "x")
                local missing, missingCount = string.gsub("xabc", "^abc", "x")
                local empty, emptyCount = string.gsub("abc", "^", "|")
                return replaced, count, missing, missingCount, empty, emptyCount
                """.trimIndent(),
                "string-gsub-start-anchor.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("xabc", state.toString(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals("xabc", state.toString(3))
        assertEquals(0L, state.toInteger(4))
        assertEquals("|abc", state.toString(5))
        assertEquals(1L, state.toInteger(6))
    }

    @Test
    fun `string gsub matches utf8 text by raw bytes`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local utf8 = "é"
                local wildcard, wildcardCount = string.gsub(utf8, ".", "x")
                local captured, capturedCount = string.gsub(utf8, "(.)", "[%1]")
                local functionResult, functionCount = string.gsub(utf8, ".", function(byte)
                    return string.byte(byte) .. ","
                end)
                local tableResult, tableCount = string.gsub(utf8, ".", {
                    [string.char(195)] = "A",
                    [string.char(169)] = "B",
                })
                local c1, c2, c3, c4, c5, c6 = string.byte(captured, 1, -1)
                return wildcard, wildcardCount, capturedCount, c1, c2, c3, c4, c5, c6,
                    functionResult, functionCount, tableResult, tableCount
                """.trimIndent(),
                "string-gsub-raw-bytes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("xx", state.toString(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals(91L, state.toInteger(4))
        assertEquals(195L, state.toInteger(5))
        assertEquals(93L, state.toInteger(6))
        assertEquals(91L, state.toInteger(7))
        assertEquals(169L, state.toInteger(8))
        assertEquals(93L, state.toInteger(9))
        assertEquals("195,169,", state.toString(10))
        assertEquals(2L, state.toInteger(11))
        assertEquals("AB", state.toString(12))
        assertEquals(2L, state.toInteger(13))
    }

    @Test
    fun `string gsub supports empty patterns`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local replaced, count = string.gsub("abc", "", "-")
                local empty, emptyCount = string.gsub("", "", "-")
                local limited, limitedCount = string.gsub("abc", "", "-", 2)
                local wide = utf8.char(128512)
                local wideReplaced, wideCount = string.gsub(wide, "", "-")
                local w1, w2, w3, w4, w5, w6, w7, w8, w9 = string.byte(wideReplaced, 1, -1)
                return replaced, count, empty, emptyCount, limited, limitedCount,
                    wideCount, string.len(wideReplaced),
                    w1, w2, w3, w4, w5, w6, w7, w8, w9
                """.trimIndent(),
                "string-gsub-empty-pattern.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("-a-b-c-", state.toString(1))
        assertEquals(4L, state.toInteger(2))
        assertEquals("-", state.toString(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals("-a-bc", state.toString(5))
        assertEquals(2L, state.toInteger(6))
        assertEquals(5L, state.toInteger(7))
        assertEquals(9L, state.toInteger(8))
        assertEquals(45L, state.toInteger(9))
        assertEquals(240L, state.toInteger(10))
        assertEquals(45L, state.toInteger(11))
        assertEquals(159L, state.toInteger(12))
        assertEquals(45L, state.toInteger(13))
        assertEquals(152L, state.toInteger(14))
        assertEquals(45L, state.toInteger(15))
        assertEquals(128L, state.toInteger(16))
        assertEquals(45L, state.toInteger(17))
    }

    @Test
    fun `string gsub accepts numeric replacements`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local integerReplacement, integerCount = string.gsub("a1b2", "%d", 7)
                local floatReplacement, floatCount = string.gsub("a b", "%s", 1.5)
                local exponentReplacement = string.gsub("a b", "%s", 1e15)
                local functionReplacement = string.gsub("a b", "%s", function()
                    return 1e-5
                end)
                local tableReplacement = string.gsub("a b", "%s", {
                    [" "] = 1 / 0,
                })
                return integerReplacement, integerCount, floatReplacement, floatCount,
                    exponentReplacement, functionReplacement, tableReplacement
                """.trimIndent(),
                "string-gsub-number-replacement.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("a7b7", state.toString(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals("a1.5b", state.toString(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals("a1e+15b", state.toString(5))
        assertEquals("a1e-05b", state.toString(6))
        assertEquals("ainfb", state.toString(7))
    }

    @Test
    fun `string gsub rejects invalid replacement types with source wording`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local missingOk, missingMessage = pcall(string.gsub, "abc", "a")
                local nilOk, nilMessage = pcall(string.gsub, "abc", "a", nil)
                local falseOk, falseMessage = pcall(string.gsub, "abc", "a", false)
                local threadOk, threadMessage = pcall(string.gsub, "abc", "a", coroutine.create(function() end))
                return missingOk, missingMessage, nilOk, nilMessage, falseOk, falseMessage,
                    threadOk, threadMessage
                """.trimIndent(),
                "string-gsub-replacement-type-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #3 to 'gsub' (string/function/table expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #3 to 'gsub' (string/function/table expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'gsub' (string/function/table expected)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #3 to 'gsub' (string/function/table expected)", state.toString(8))
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
        LuaStdlib.openBase(state)
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
                local indexed, indexedCount = string.gsub("hello world unknown", "%a+", setmetatable({
                    hello = "hi",
                }, {
                    __index = {
                        world = "earth",
                    },
                }))
                local functionIndexed, functionIndexedCount = string.gsub("a b c", "%a", setmetatable({}, {
                    __index = function(_, key)
                        if key == "c" then
                            return false
                        end
                        return key .. key
                    end,
                }))
                return swapped, swappedCount, wrapped, wrappedCount, kept, keptCount,
                    mapped, mappedCount, numbered, numberedCount,
                    indexed, indexedCount, functionIndexed, functionIndexedCount
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
        assertEquals("hi earth unknown", state.toString(11))
        assertEquals(3L, state.toInteger(12))
        assertEquals("aa bb c", state.toString(13))
        assertEquals(3L, state.toInteger(14))
    }

    @Test
    fun `string gsub reports replacement type errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local argumentOk, argumentMessage = pcall(string.gsub, "abc", "a", true)
                local booleanFunctionOk, booleanFunctionMessage = pcall(string.gsub, "abc", "a", function()
                    return true
                end)
                local nestedFunctionOk, nestedFunctionMessage = pcall(string.gsub, "abc", "a", function()
                    return function() end
                end)
                local tableOk, tableMessage = pcall(string.gsub, "abc", "a", {
                    a = {},
                })
                return argumentOk, argumentMessage,
                    booleanFunctionOk, booleanFunctionMessage,
                    nestedFunctionOk, nestedFunctionMessage,
                    tableOk, tableMessage
                """.trimIndent(),
                "string-gsub-replacement-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #3 to 'gsub' (string/function/table expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("invalid replacement value (a boolean)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("invalid replacement value (a function)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("invalid replacement value (a table)", state.toString(8))
    }

    @Test
    fun `string gsub table replacements use index metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local direct = setmetatable({ a = "R" }, {
                    __index = function()
                        error("should not call")
                    end,
                })
                local directResult, directCount = string.gsub("a", "%a", direct)

                local tableIndexed = setmetatable({}, {
                    __index = {
                        a = "A",
                        b = false,
                    },
                })
                local tableResult, tableCount = string.gsub("abc", "%a", tableIndexed)

                local functionIndexed = setmetatable({}, {
                    __index = function(_, key)
                        if key == "a" then
                            return "F"
                        elseif key == "b" then
                            return false
                        end
                    end,
                })
                local functionResult, functionCount = string.gsub("abc", "%a", functionIndexed)

                local nested = setmetatable({}, {
                    __index = setmetatable({}, {
                        __index = {
                            a = "N",
                        },
                    }),
                })
                local nestedResult, nestedCount = string.gsub("abc", "%a", nested)

                return directResult, directCount, tableResult, tableCount,
                    functionResult, functionCount, nestedResult, nestedCount
                """.trimIndent(),
                "string-gsub-table-replacement-index.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("R", state.toString(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals("Abc", state.toString(3))
        assertEquals(3L, state.toInteger(4))
        assertEquals("Fbc", state.toString(5))
        assertEquals(3L, state.toInteger(6))
        assertEquals("Nbc", state.toString(7))
        assertEquals(3L, state.toInteger(8))
    }

    @Test
    fun `string gsub table replacement index reports nonindexable values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numberTable = setmetatable({}, { __index = 1 })
                local numberOk, numberMessage = pcall(string.gsub, "a", "a", numberTable)

                local booleanTable = setmetatable({}, { __index = true })
                local booleanOk, booleanMessage = pcall(string.gsub, "a", "a", booleanTable)

                local stringTable = setmetatable({}, { __index = "x" })
                local stringResult, stringCount = string.gsub("a", "a", stringTable)

                return numberOk, numberMessage, booleanOk, booleanMessage, stringResult, stringCount
                """.trimIndent(),
                "string-gsub-table-replacement-bad-index.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to index a number value", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("attempt to index a boolean value", state.toString(4))
        assertEquals("a", state.toString(5))
        assertEquals(1L, state.toInteger(6))
    }

    @Test
    fun `string gsub reports limit argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(string.gsub, "abc", "a", "x", "bad")
                local okFraction, fractionMessage = pcall(string.gsub, "abc", "a", "x", 1.5)
                local okStringNumber, stringNumberMessage = pcall(string.gsub, "abc", "a", "x", "1.5")
                return okString, stringMessage,
                    okFraction, fractionMessage,
                    okStringNumber, stringNumberMessage
                """.trimIndent(),
                "string-gsub-limit-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #4 to 'gsub' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #4 to 'gsub' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #4 to 'gsub' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `string gsub treats closing bracket outside classes as literal`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local missing, missingCount = string.gsub("abc", "a]", "x")
                local replaced, replacedCount = string.gsub("a]b", "a]", "x")
                return missing, missingCount, replaced, replacedCount
                """.trimIndent(),
                "string-gsub-closing-bracket.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("abc", state.toString(1))
        assertEquals(0L, state.toInteger(2))
        assertEquals("xb", state.toString(3))
        assertEquals(1L, state.toInteger(4))
    }

    @Test
    fun `string gsub follows lua replacement capture rules`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local whole, wholeCount = string.gsub("abc", "a", "%0")
                local firstOk, firstMessage = pcall(string.gsub, "abc", "a", "%1")
                local captureOk, captureMessage = pcall(string.gsub, "abc", "a", "%2")
                local letterOk, letterMessage = pcall(string.gsub, "abc", "a", "%x")
                local danglingOk, danglingMessage = pcall(string.gsub, "abc", "a", "%")
                return whole, wholeCount, firstOk, firstMessage, captureOk, captureMessage,
                    letterOk, letterMessage, danglingOk, danglingMessage
                """.trimIndent(),
                "string-gsub-replacement-captures.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("abc", state.toString(1))
        assertEquals(1L, state.toInteger(2))
        assertFalse(state.toBoolean(3))
        assertEquals("invalid capture index %1", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("invalid capture index %2", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("invalid use of '%' in replacement string", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("invalid use of '%' in replacement string", state.toString(10))
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
    fun `string gmatch honors init argument`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local positive = string.gmatch("one two three", "%a+", 5)
                local negative = string.gmatch("one two three", "%a+", -5)
                local clamped = string.gmatch("one two", "%a+", 0)
                local pastEnd = string.gmatch("one two", "%a+", 8)
                local utf8Position = string.gmatch("éx", "()x")
                local utf8FromSecondByte = string.gmatch("éx", "x", 2)
                return positive(), positive(), positive(),
                    negative(), negative(),
                    clamped(),
                    pastEnd(),
                    utf8Position(),
                    utf8FromSecondByte()
                """.trimIndent(),
                "string-gmatch-init.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("two", state.toString(1))
        assertEquals("three", state.toString(2))
        assertTrue(state.isNil(3))
        assertEquals("three", state.toString(4))
        assertTrue(state.isNil(5))
        assertEquals("one", state.toString(6))
        assertTrue(state.isNil(7))
        assertEquals(3L, state.toInteger(8))
        assertEquals("x", state.toString(9))
    }

    @Test
    fun `string gmatch treats leading caret as literal`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local literal = string.gmatch("a ^abc ^abc", "^abc")
                local anchoredEnd = string.gmatch("abc abc", "abc$")
                return literal(), literal(), literal(), anchoredEnd(), anchoredEnd()
                """.trimIndent(),
                "string-gmatch-caret-literal.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("^abc", state.toString(1))
        assertEquals("^abc", state.toString(2))
        assertTrue(state.isNil(3))
        assertEquals("abc", state.toString(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `string gmatch supports empty patterns`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator = string.gmatch("ab", "")
                local empty = string.gmatch("", "")
                local wide = string.gmatch(utf8.char(128512), "")
                return iterator(), iterator(), iterator(), iterator(),
                    empty(), empty(), wide(), wide(), wide(), wide(), wide(), wide()
                """.trimIndent(),
                "string-gmatch-empty-pattern.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("", state.toString(1))
        assertEquals("", state.toString(2))
        assertEquals("", state.toString(3))
        assertTrue(state.isNil(4))
        assertEquals("", state.toString(5))
        assertTrue(state.isNil(6))
        assertEquals("", state.toString(7))
        assertEquals("", state.toString(8))
        assertEquals("", state.toString(9))
        assertEquals("", state.toString(10))
        assertEquals("", state.toString(11))
        assertTrue(state.isNil(12))
    }

    @Test
    fun `string gmatch treats closing bracket outside classes as literal`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local missing = string.gmatch("abc", "a]")
                local iterator = string.gmatch("a] a]", "a]")
                return missing(), iterator(), iterator(), iterator()
                """.trimIndent(),
                "string-gmatch-closing-bracket.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertEquals("a]", state.toString(2))
        assertEquals("a]", state.toString(3))
        assertTrue(state.isNil(4))
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

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local text = "A" .. utf8.char(128512) .. "Z"
                local a, b = utf8.codepoint("abc", 2, 3)
                local c, d = utf8.codepoint(text, 2, 6)
                local e = utf8.codepoint(text, 6, 6)
                return a, b, c, d, e
                """.trimIndent(),
                "utf8-codepoint-range.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(98L, state.toInteger(1))
        assertEquals(99L, state.toInteger(2))
        assertEquals(128512L, state.toInteger(3))
        assertEquals(90L, state.toInteger(4))
        assertEquals(90L, state.toInteger(5))
    }

    @Test
    fun `utf8 codepoint returns no values for empty byte ranges`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local explicitCount = select("#", utf8.codepoint("abc", 4, 3))
                local beforeStartCount = select("#", utf8.codepoint("abc", 1, -5))
                return explicitCount, beforeStartCount
                """.trimIndent(),
                "utf8-codepoint-empty.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0L, state.toInteger(1))
        assertEquals(0L, state.toInteger(2))
    }

    @Test
    fun `utf8 codepoint rejects continuation byte starts`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local text = "A" .. utf8.char(128512) .. "Z"
                return utf8.codepoint(text, 4, 6)
                """.trimIndent(),
                "utf8-codepoint-continuation-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("invalid UTF-8 code", state.toString(-1))
    }

    @Test
    fun `utf8 codepoint honors lax invalid code points`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushString("\uD800")
        state.setGlobal("surrogate")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local strictOk, strictMessage = pcall(utf8.codepoint, surrogate)
                local laxValue = utf8.codepoint(surrogate, 1, -1, true)
                return strictOk, strictMessage, laxValue
                """.trimIndent(),
                "utf8-codepoint-lax.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(false, state.toBoolean(1))
        assertEquals("invalid UTF-8 code", state.toString(2))
        assertEquals(0xD800L, state.toInteger(3))
    }

    @Test
    fun `utf8 codepoint reports range position errors`() {
        val startState = LuaState.create()
        LuaStdlib.openUtf8(startState)

        assertEquals(LuaStatus.OK, startState.load("""return utf8.codepoint("abc", -4)""", "utf8-codepoint-start-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, startState.pcall(0, -1))

        assertIs<LuaRuntimeException>(startState.getLastError())
        assertEquals("bad argument #2 to 'codepoint' (out of bounds)", startState.toString(-1))

        val endState = LuaState.create()
        LuaStdlib.openUtf8(endState)

        assertEquals(LuaStatus.OK, endState.load("""return utf8.codepoint("abc", 1, 4)""", "utf8-codepoint-end-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, endState.pcall(0, -1))

        assertIs<LuaRuntimeException>(endState.getLastError())
        assertEquals("bad argument #3 to 'codepoint' (out of bounds)", endState.toString(-1))
    }

    @Test
    fun `utf8 integer arguments report fractional number errors`() {
        fun assertFractionalIntegerError(source: String, chunkName: String, expected: String) {
            val state = LuaState.create()
            LuaStdlib.openUtf8(state)

            assertEquals(LuaStatus.OK, state.load(source, chunkName))
            assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

            assertIs<LuaRuntimeException>(state.getLastError())
            assertEquals(expected, state.toString(-1))
        }

        assertFractionalIntegerError(
            """return utf8.char(65.5)""",
            "utf8-char-fractional.lua",
            "bad argument #1 to 'char' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return utf8.codepoint("abc", 1.5)""",
            "utf8-codepoint-fractional-start.lua",
            "bad argument #2 to 'codepoint' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return utf8.codepoint("abc", 1, 2.5)""",
            "utf8-codepoint-fractional-end.lua",
            "bad argument #3 to 'codepoint' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return utf8.len("abc", 1.5)""",
            "utf8-len-fractional-start.lua",
            "bad argument #2 to 'len' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return utf8.len("abc", 1, 2.5)""",
            "utf8-len-fractional-end.lua",
            "bad argument #3 to 'len' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return utf8.offset("abc", 1.5)""",
            "utf8-offset-fractional-offset.lua",
            "bad argument #2 to 'offset' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return utf8.offset("abc", 1, 1.5)""",
            "utf8-offset-fractional-position.lua",
            "bad argument #3 to 'offset' (number has no integer representation)",
        )
    }

    @Test
    fun `utf8 integer arguments report non numeric value errors`() {
        fun assertNonNumericIntegerError(source: String, chunkName: String, expected: String) {
            val state = LuaState.create()
            LuaStdlib.openUtf8(state)

            assertEquals(LuaStatus.OK, state.load(source, chunkName))
            assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

            assertIs<LuaRuntimeException>(state.getLastError())
            assertEquals(expected, state.toString(-1))
        }

        assertNonNumericIntegerError(
            """return utf8.char("bad")""",
            "utf8-char-string-codepoint.lua",
            "bad argument #1 to 'char' (number expected)",
        )
        assertNonNumericIntegerError(
            """return utf8.codepoint("abc", "bad")""",
            "utf8-codepoint-string-start.lua",
            "bad argument #2 to 'codepoint' (number expected)",
        )
        assertNonNumericIntegerError(
            """return utf8.codepoint("abc", 1, "bad")""",
            "utf8-codepoint-string-end.lua",
            "bad argument #3 to 'codepoint' (number expected)",
        )
        assertNonNumericIntegerError(
            """return utf8.len("abc", "bad")""",
            "utf8-len-string-start.lua",
            "bad argument #2 to 'len' (number expected)",
        )
        assertNonNumericIntegerError(
            """return utf8.len("abc", 1, "bad")""",
            "utf8-len-string-end.lua",
            "bad argument #3 to 'len' (number expected)",
        )
        assertNonNumericIntegerError(
            """return utf8.offset("abc", "bad")""",
            "utf8-offset-string-count.lua",
            "bad argument #2 to 'offset' (number expected)",
        )
        assertNonNumericIntegerError(
            """return utf8.offset("abc", 1, "bad")""",
            "utf8-offset-string-position.lua",
            "bad argument #3 to 'offset' (number expected)",
        )
    }

    @Test
    fun `utf8 codepoint reports position argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okStartString, startStringMessage = pcall(utf8.codepoint, "abc", "bad")
                local okStartFraction, startFractionMessage = pcall(utf8.codepoint, "abc", 1.5)
                local okEndFraction, endFractionMessage = pcall(utf8.codepoint, "abc", 1, "1.5")
                return okStartString, startStringMessage,
                    okStartFraction, startFractionMessage,
                    okEndFraction, endFractionMessage
                """.trimIndent(),
                "utf8-codepoint-position-argument-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'codepoint' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'codepoint' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'codepoint' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `utf8 codes returns codepoint iterator`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator, stateValue, control = utf8.codes("A" .. utf8.char(128512) .. "Z")
                local firstIndex, firstCode = iterator(stateValue, control)
                local firstAgainIndex, firstAgainCode = iterator(stateValue, control)
                local secondIndex, secondCode = iterator(stateValue, firstIndex)
                local thirdIndex, thirdCode = iterator(stateValue, secondIndex)
                local done = iterator(stateValue, thirdIndex)
                local doneCount = select("#", iterator(stateValue, thirdIndex))
                local nilControlIndex, nilControlCode = iterator(stateValue, nil)
                local badControlIndex, badControlCode = iterator(stateValue, "bad")
                local fractionalIndex, fractionalCode = iterator(stateValue, 1.5)
                local fractionalStringIndex, fractionalStringCode = iterator(stateValue, "1.5")
                local negativeFractionalIndex, negativeFractionalCode = iterator(stateValue, -0.5)
                local negativeFractionalCount = select("#", iterator(stateValue, -0.5))
                return firstIndex, firstCode, firstAgainIndex, firstAgainCode,
                    secondIndex, secondCode, thirdIndex, thirdCode, done, doneCount,
                    nilControlIndex, nilControlCode, badControlIndex, badControlCode,
                    fractionalIndex, fractionalCode, fractionalStringIndex, fractionalStringCode,
                    negativeFractionalIndex, negativeFractionalCode, negativeFractionalCount
                """.trimIndent(),
                "utf8-codes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(65L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(65L, state.toInteger(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals(128512L, state.toInteger(6))
        assertEquals(6L, state.toInteger(7))
        assertEquals(90L, state.toInteger(8))
        assertTrue(state.isNil(9))
        assertEquals(0L, state.toInteger(10))
        assertEquals(1L, state.toInteger(11))
        assertEquals(65L, state.toInteger(12))
        assertEquals(1L, state.toInteger(13))
        assertEquals(65L, state.toInteger(14))
        assertEquals(1L, state.toInteger(15))
        assertEquals(65L, state.toInteger(16))
        assertEquals(1L, state.toInteger(17))
        assertEquals(65L, state.toInteger(18))
        assertEquals(1L, state.toInteger(19))
        assertEquals(65L, state.toInteger(20))
        assertEquals(2L, state.toInteger(21))
    }

    @Test
    fun `utf8 codes returns generic for iterator triple`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local positions = {}
                local codepoints = {}
                for position, codepoint in utf8.codes("A" .. utf8.char(128512) .. "Z") do
                    positions[#positions + 1] = position
                    codepoints[#codepoints + 1] = codepoint
                end
                return positions[1], codepoints[1],
                    positions[2], codepoints[2],
                    positions[3], codepoints[3],
                    positions[4]
                """.trimIndent(),
                "utf8-codes-for.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(65L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals(128512L, state.toInteger(4))
        assertEquals(6L, state.toInteger(5))
        assertEquals(90L, state.toInteger(6))
        assertTrue(state.isNil(7))
    }

    @Test
    fun `utf8 codes iterator state is coerced string argument`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator, stateValue, control = utf8.codes(123)
                local position, codepoint = iterator(stateValue, control)
                return stateValue, control, position, codepoint
                """.trimIndent(),
                "utf8-codes-coerced-state.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("123", state.toString(1))
        assertEquals(0L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(49L, state.toInteger(4))
    }

    @Test
    fun `utf8 codes honors lax invalid code points`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushString("\uD800")
        state.setGlobal("surrogate")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local strictIterator, strictState, strictControl = utf8.codes(surrogate)
                local strictOk, strictMessage = pcall(strictIterator, strictState, strictControl)
                local laxIterator, laxState, laxControl = utf8.codes(surrogate, true)
                local laxPosition, laxCodepoint = laxIterator(laxState, laxControl)
                return strictOk, strictMessage, laxPosition, laxCodepoint
                """.trimIndent(),
                "utf8-codes-lax.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(false, state.toBoolean(1))
        assertEquals("invalid UTF-8 code", state.toString(2))
        assertEquals(1L, state.toInteger(3))
        assertEquals(0xD800L, state.toInteger(4))
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
                local b1, b2, b3, b4 = string.byte(utf8.charpattern, 1, 4)
                return b1, b2, b3, b4,
                    iterator(), iterator(), iterator(), iterator()
                """.trimIndent(),
                "utf8-charpattern.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals('['.code.toLong(), state.toInteger(1))
        assertEquals(0L, state.toInteger(2))
        assertEquals('-'.code.toLong(), state.toInteger(3))
        assertEquals(0x7FL, state.toInteger(4))
        assertEquals("A", state.toString(5))
        assertEquals("é", state.toString(6))
        assertEquals("Z", state.toString(7))
        assertTrue(state.isNil(8))
    }

    @Test
    fun `utf8 codes reports string argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(LuaStatus.OK, state.load("""return utf8.codes({})""", "utf8-codes-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'codes' (string expected)", state.toString(-1))
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
    fun `utf8 len returns zero for empty ranges`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return utf8.len("abc", 4), utf8.len("abc", 3, 2), utf8.len("", 1, 0), utf8.len("abc", 1, -5)""",
                "utf8-len-empty-ranges.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0L, state.toInteger(1))
        assertEquals(0L, state.toInteger(2))
        assertEquals(0L, state.toInteger(3))
        assertEquals(0L, state.toInteger(4))
    }

    @Test
    fun `utf8 len counts byte ranges`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local text = "A" .. utf8.char(128512) .. "Z"
                return utf8.len(text, 1, 6),
                    utf8.len(text, 2, 5),
                    utf8.len(text, 2, 6),
                    utf8.len(text, 6, 6),
                    utf8.len(text, 3, 2),
                    utf8.len(text, 1, 4),
                    utf8.len(text, 2, 4)
                """.trimIndent(),
                "utf8-len-byte-ranges.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3L, state.toInteger(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals(0L, state.toInteger(5))
        assertEquals(2L, state.toInteger(6))
        assertEquals(1L, state.toInteger(7))
    }

    @Test
    fun `utf8 len counts codepoints that start in byte range`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local text = "A" .. utf8.char(128512) .. "Z"
                local continuationLength, continuationPosition = utf8.len(text, 4, 6)
                local partialFromStart = utf8.len(text, 1, 4)
                local partialCodepoint = utf8.len(text, 2, 3)
                return continuationLength, continuationPosition, partialFromStart, partialCodepoint
                """.trimIndent(),
                "utf8-len-starting-byte-ranges.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertEquals(4L, state.toInteger(2))
        assertEquals(2L, state.toInteger(3))
        assertEquals(1L, state.toInteger(4))
    }

    @Test
    fun `utf8 len honors lax invalid code points`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)
        state.pushString("\uD800")
        state.setGlobal("surrogate")

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local strictLength, strictPosition = utf8.len(surrogate)
                local laxLength = utf8.len(surrogate, 1, -1, true)
                return strictLength, strictPosition, laxLength
                """.trimIndent(),
                "utf8-len-lax.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
    }

    @Test
    fun `utf8 len reports range position errors`() {
        val startState = LuaState.create()
        LuaStdlib.openUtf8(startState)

        assertEquals(LuaStatus.OK, startState.load("""return utf8.len("", 2)""", "utf8-len-start-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, startState.pcall(0, -1))

        assertIs<LuaRuntimeException>(startState.getLastError())
        assertEquals("bad argument #2 to 'len' (initial position out of bounds)", startState.toString(-1))

        val endState = LuaState.create()
        LuaStdlib.openUtf8(endState)

        assertEquals(LuaStatus.OK, endState.load("""return utf8.len("abc", 1, 4)""", "utf8-len-end-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, endState.pcall(0, -1))

        assertIs<LuaRuntimeException>(endState.getLastError())
        assertEquals("bad argument #3 to 'len' (final position out of bounds)", endState.toString(-1))
    }

    @Test
    fun `utf8 len reports position argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okStartString, startStringMessage = pcall(utf8.len, "abc", "bad")
                local okStartFraction, startFractionMessage = pcall(utf8.len, "abc", 1.5)
                local okEndFraction, endFractionMessage = pcall(utf8.len, "abc", 1, "1.5")
                return okStartString, startStringMessage,
                    okStartFraction, startFractionMessage,
                    okEndFraction, endFractionMessage
                """.trimIndent(),
                "utf8-len-position-argument-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'len' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'len' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'len' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `utf8 offset returns byte positions`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local text = "A" .. utf8.char(128512) .. "Z"
                local secondStart, secondEnd = utf8.offset(text, 2)
                local lastStart, lastEnd = utf8.offset(text, -1)
                local currentStart, currentEnd = utf8.offset(text, 1, 2)
                local containingStart, containingEnd = utf8.offset(text, 0, 4)
                local nextStart, nextEnd = utf8.offset(text, 2, 2)
                local afterStart, afterEnd = utf8.offset(text, 3, 2)
                local missing = utf8.offset(text, 4, 2)
                local trailingStart, trailingEnd = utf8.offset(text, 0, 7)
                return secondStart, secondEnd,
                    lastStart, lastEnd,
                    currentStart, currentEnd,
                    containingStart, containingEnd,
                    nextStart, nextEnd,
                    afterStart, afterEnd,
                    missing,
                    trailingStart, trailingEnd
                """.trimIndent(),
                "utf8-offset.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(2L, state.toInteger(1))
        assertEquals(5L, state.toInteger(2))
        assertEquals(6L, state.toInteger(3))
        assertEquals(6L, state.toInteger(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals(5L, state.toInteger(6))
        assertEquals(2L, state.toInteger(7))
        assertEquals(5L, state.toInteger(8))
        assertEquals(6L, state.toInteger(9))
        assertEquals(6L, state.toInteger(10))
        assertEquals(7L, state.toInteger(11))
        assertEquals(7L, state.toInteger(12))
        assertTrue(state.isNil(13))
        assertEquals(7L, state.toInteger(14))
        assertEquals(7L, state.toInteger(15))
    }

    @Test
    fun `utf8 offset rejects nonzero offsets from continuation bytes`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local text = "A" .. utf8.char(128512) .. "Z"
                return utf8.offset(text, 1, 4)
                """.trimIndent(),
                "utf8-offset-continuation-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("initial position is a continuation byte", state.toString(-1))
    }

    @Test
    fun `utf8 offset reports position errors`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(LuaStatus.OK, state.load("""return utf8.offset("abc", 1, 5)""", "utf8-offset-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #3 to 'offset' (position out of bounds)", state.toString(-1))
    }

    @Test
    fun `utf8 offset reports integer argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okOffsetString, offsetStringMessage = pcall(utf8.offset, "abc", "bad")
                local okOffsetFraction, offsetFractionMessage = pcall(utf8.offset, "abc", 1.5)
                local okPositionFraction, positionFractionMessage = pcall(utf8.offset, "abc", 1, "1.5")
                return okOffsetString, offsetStringMessage,
                    okOffsetFraction, offsetFractionMessage,
                    okPositionFraction, positionFractionMessage
                """.trimIndent(),
                "utf8-offset-integer-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'offset' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'offset' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'offset' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `utf8 char allows surrogate code points and strict decoders reject them`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local surrogate = utf8.char(55296)
                local strictLength, strictPosition = utf8.len(surrogate)
                local laxLength = utf8.len(surrogate, 1, -1, true)
                local strictCodeOk, strictCodeMessage = pcall(utf8.codepoint, surrogate)
                local laxCode = utf8.codepoint(surrogate, 1, -1, true)
                local strictIterator = utf8.codes(surrogate)
                local strictCodesOk, strictCodesMessage = pcall(strictIterator, surrogate, 0)
                local laxIterator, laxState, laxControl = utf8.codes(surrogate, true)
                local laxPosition, laxIteratorCode = laxIterator(laxState, laxControl)
                local offsetStart, offsetEnd = utf8.offset(surrogate, 1)
                return strictLength, strictPosition, laxLength,
                    strictCodeOk, strictCodeMessage, laxCode,
                    strictCodesOk, strictCodesMessage, laxPosition, laxIteratorCode,
                    offsetStart, offsetEnd
                """.trimIndent(),
                "utf8-surrogate.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertFalse(state.toBoolean(4))
        assertEquals("invalid UTF-8 code", state.toString(5))
        assertEquals(55296L, state.toInteger(6))
        assertFalse(state.toBoolean(7))
        assertEquals("invalid UTF-8 code", state.toString(8))
        assertEquals(1L, state.toInteger(9))
        assertEquals(55296L, state.toInteger(10))
        assertEquals(1L, state.toInteger(11))
        assertEquals(3L, state.toInteger(12))
    }

    @Test
    fun `utf8 char accepts max utf code point as lax utf8`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local max = utf8.char(0x7fffffff)
                local b1, b2, b3, b4, b5, b6 = string.byte(max, 1, -1)
                local strictLength, strictPosition = utf8.len(max)
                local laxLength = utf8.len(max, 1, -1, true)
                local strictOk, strictMessage = pcall(utf8.codepoint, max)
                local laxCodepoint = utf8.codepoint(max, 1, -1, true)
                local offsetStart, offsetEnd = utf8.offset(max, 1)
                return rawlen(max), b1, b2, b3, b4, b5, b6,
                    strictLength, strictPosition, laxLength,
                    strictOk, strictMessage, laxCodepoint,
                    offsetStart, offsetEnd
                """.trimIndent(),
                "utf8-char-maxutf.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(6L, state.toInteger(1))
        assertEquals(0xFDL, state.toInteger(2))
        assertEquals(0xBFL, state.toInteger(3))
        assertEquals(0xBFL, state.toInteger(4))
        assertEquals(0xBFL, state.toInteger(5))
        assertEquals(0xBFL, state.toInteger(6))
        assertEquals(0xBFL, state.toInteger(7))
        assertTrue(state.isNil(8))
        assertEquals(1L, state.toInteger(9))
        assertEquals(1L, state.toInteger(10))
        assertFalse(state.toBoolean(11))
        assertEquals("invalid UTF-8 code", state.toString(12))
        assertEquals(0x7fffffffL, state.toInteger(13))
        assertEquals(1L, state.toInteger(14))
        assertEquals(6L, state.toInteger(15))
    }

    @Test
    fun `utf8 char rejects values above max utf`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(LuaStatus.OK, state.load("""return utf8.char(0x80000000)""", "utf8-char-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'char' (value out of range)", state.toString(-1))
    }

    @Test
    fun `utf8 char reports code point argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(utf8.char, "bad")
                local okFraction, fractionMessage = pcall(utf8.char, 65.5)
                local okStringNumber, stringNumberMessage = pcall(utf8.char, "65.5")
                return okString, stringMessage,
                    okFraction, fractionMessage,
                    okStringNumber, stringNumberMessage
                """.trimIndent(),
                "utf8-char-codepoint-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'char' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'char' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #1 to 'char' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `utf8 char accepts surrogate code points as invalid strict utf8`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local surrogate = utf8.char(55296)
                local strictLength, strictPosition = utf8.len(surrogate)
                local laxLength = utf8.len(surrogate, 1, -1, true)
                local strictOk, strictMessage = pcall(utf8.codepoint, surrogate)
                local laxCodepoint = utf8.codepoint(surrogate, 1, -1, true)
                return strictLength, strictPosition, laxLength,
                    strictOk, strictMessage, laxCodepoint
                """.trimIndent(),
                "utf8-char-surrogate.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertEquals(1L, state.toInteger(2))
        assertEquals(1L, state.toInteger(3))
        assertFalse(state.toBoolean(4))
        assertEquals("invalid UTF-8 code", state.toString(5))
        assertEquals(0xD800L, state.toInteger(6))
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
    fun `string match treats closing bracket outside classes as literal`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return string.match("abc", "a]"), string.match("a]b", "a]")""", "string-match-closing-bracket.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertEquals("a]", state.toString(2))
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
    fun `string byte reports range argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okStartString, startStringMessage = pcall(string.byte, "ABC", "x", 1)
                local okStartFraction, startFractionMessage = pcall(string.byte, "ABC", 1.5, 1)
                local okEndFraction, endFractionMessage = pcall(string.byte, "ABC", 1, "1.5")
                return okStartString, startStringMessage,
                    okStartFraction, startFractionMessage,
                    okEndFraction, endFractionMessage
                """.trimIndent(),
                "string-byte-range-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'byte' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'byte' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'byte' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `string comparisons order raw bytes`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local raw = string.char(128)
                local utf8 = "é"
                local extended = raw .. "x"
                return raw < utf8,
                    utf8 < raw,
                    raw <= utf8,
                    raw < extended
                """.trimIndent(),
                "string-raw-byte-comparison.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertFalse(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
    }

    @Test
    fun `string functions report argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(string.rep, "x", "bad")
                local okFraction, fractionMessage = pcall(string.rep, "x", 1.5)
                return okString, stringMessage, okFraction, fractionMessage
                """.trimIndent(),
                "string-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'rep' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'rep' (number has no integer representation)", state.toString(4))
    }

    @Test
    fun `string integer arguments report non numeric value errors`() {
        fun assertNonNumericIntegerError(source: String, chunkName: String, expected: String) {
            val state = LuaState.create()
            LuaStdlib.openString(state)

            assertEquals(LuaStatus.OK, state.load(source, chunkName))
            assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

            assertIs<LuaRuntimeException>(state.getLastError())
            assertEquals(expected, state.toString(-1))
        }

        assertNonNumericIntegerError(
            """return string.sub("abc", "bad")""",
            "string-sub-string-start.lua",
            "bad argument #2 to 'sub' (number expected)",
        )
        assertNonNumericIntegerError(
            """return string.byte("abc", "bad")""",
            "string-byte-string-start.lua",
            "bad argument #2 to 'byte' (number expected)",
        )
        assertNonNumericIntegerError(
            """return string.char("bad")""",
            "string-char-string.lua",
            "bad argument #1 to 'char' (number expected)",
        )
        assertNonNumericIntegerError(
            """return string.find("abc", "a", "bad", true)""",
            "string-find-string-start.lua",
            "bad argument #3 to 'find' (number expected)",
        )
        assertNonNumericIntegerError(
            """return string.match("abc", "a", "bad")""",
            "string-match-string-start.lua",
            "bad argument #3 to 'match' (number expected)",
        )
        assertNonNumericIntegerError(
            """return string.gmatch("abc", "a", "bad")""",
            "string-gmatch-string-start.lua",
            "bad argument #3 to 'gmatch' (number expected)",
        )
        assertNonNumericIntegerError(
            """return string.gsub("abc", "a", "x", "bad")""",
            "string-gsub-string-limit.lua",
            "bad argument #4 to 'gsub' (number expected)",
        )
    }

    @Test
    fun `string integer arguments report fractional number errors`() {
        fun assertFractionalIntegerError(source: String, chunkName: String, expected: String) {
            val state = LuaState.create()
            LuaStdlib.openString(state)

            assertEquals(LuaStatus.OK, state.load(source, chunkName))
            assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

            assertIs<LuaRuntimeException>(state.getLastError())
            assertEquals(expected, state.toString(-1))
        }

        assertFractionalIntegerError(
            """return string.rep("x", 1.5)""",
            "string-rep-fractional-count.lua",
            "bad argument #2 to 'rep' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return string.sub("abc", 1.5)""",
            "string-sub-fractional-start.lua",
            "bad argument #2 to 'sub' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return string.sub("abc", 1, 2.5)""",
            "string-sub-fractional-end.lua",
            "bad argument #3 to 'sub' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return string.byte("abc", 1.5)""",
            "string-byte-fractional-start.lua",
            "bad argument #2 to 'byte' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return string.byte("abc", 1, 2.5)""",
            "string-byte-fractional-end.lua",
            "bad argument #3 to 'byte' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return string.char(65.5)""",
            "string-char-fractional.lua",
            "bad argument #1 to 'char' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return string.find("abc", "a", 1.5, true)""",
            "string-find-fractional-start.lua",
            "bad argument #3 to 'find' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return string.match("abc", "a", 1.5)""",
            "string-match-fractional-start.lua",
            "bad argument #3 to 'match' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return string.gmatch("abc", "a", 1.5)""",
            "string-gmatch-fractional-start.lua",
            "bad argument #3 to 'gmatch' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return string.gsub("abc", "a", "x", 1.5)""",
            "string-gsub-fractional-limit.lua",
            "bad argument #4 to 'gsub' (number has no integer representation)",
        )
    }

    @Test
    fun `string char reports byte range errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.char(256)""", "string-char-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'char' (value out of range)", state.toString(-1))
    }

    @Test
    fun `string char reports integer argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(string.char, "x")
                local okFraction, fractionMessage = pcall(string.char, 65.5)
                local okStringNumber, stringNumberMessage = pcall(string.char, "65.5")
                return okString, stringMessage,
                    okFraction, fractionMessage,
                    okStringNumber, stringNumberMessage
                """.trimIndent(),
                "string-char-integer-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'char' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'char' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #1 to 'char' (number has no integer representation)", state.toString(6))
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
    fun `table concat formats numbers like lua`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                return table.concat({1, 1.0, 1.25, 1e14, 1e15, 1e-5, 0/0, 1/0, -1/0}, "|")
                """.trimIndent(),
                "table-concat-number-format.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("1|1.0|1.25|100000000000000.0|1e+15|1e-05|nan|inf|-inf", state.toString(1))
    }

    @Test
    fun `table concat default range respects raw sequence length`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local fallback = setmetatable({}, {
                    __len = function()
                        return 2
                    end,
                    __index = function(_, index)
                        return ({ "meta-a", "meta-b" })[index]
                    end,
                })
                return table.concat({"a", nil, "c"}), table.concat(fallback, ",")
                """.trimIndent(),
                "table-concat-default-length.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a", state.toString(1))
        assertEquals("meta-a,meta-b", state.toString(2))
    }

    @Test
    fun `table concat reports non integer length metamethod results`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = setmetatable({}, {
                    __len = function()
                        return 1.5
                    end,
                })
                return table.concat(values)
                """.trimIndent(),
                "table-concat-length-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("object length is not an integer", state.toString(-1))

        val explicitRangeState = LuaState.create()
        LuaStdlib.openBase(explicitRangeState)
        LuaStdlib.openTable(explicitRangeState)

        assertEquals(
            LuaStatus.OK,
            explicitRangeState.load(
                """
                local values = setmetatable({}, {
                    __len = function()
                        return 1.5
                    end,
                })
                return table.concat(values, "", 2, 1)
                """.trimIndent(),
                "table-concat-explicit-range-length-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, explicitRangeState.pcall(0, -1))

        assertIs<LuaRuntimeException>(explicitRangeState.getLastError())
        assertEquals("object length is not an integer", explicitRangeState.toString(-1))

        val outOfRangeState = LuaState.create()
        LuaStdlib.openBase(outOfRangeState)
        LuaStdlib.openTable(outOfRangeState)

        assertEquals(
            LuaStatus.OK,
            outOfRangeState.load(
                """
                local values = setmetatable({}, {
                    __len = function()
                        return "0x1p63"
                    end,
                })
                return table.concat(values)
                """.trimIndent(),
                "table-concat-out-of-range-length-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, outOfRangeState.pcall(0, -1))

        assertIs<LuaRuntimeException>(outOfRangeState.getLastError())
        assertEquals("object length is not an integer", outOfRangeState.toString(-1))
    }

    @Test
    fun `table concat length metamethod trims only lua ascii whitespace`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ascii = setmetatable({}, {
                    __len = function()
                        return " \t2\n"
                    end,
                    __index = function(_, index)
                        return ({ "a", "b" })[index]
                    end,
                })
                local emSpace = string.char(226, 128, 131)
                local unicode = setmetatable({}, {
                    __len = function()
                        return emSpace .. "2"
                    end,
                    __index = function(_, index)
                        return ({ "x", "y" })[index]
                    end,
                })
                local hex = setmetatable({}, {
                    __len = function()
                        return "0x2"
                    end,
                    __index = function(_, index)
                        return ({ "hex-a", "hex-b" })[index]
                    end,
                })
                local wrapped = setmetatable({}, {
                    __len = function()
                        return "0x10000000000000000"
                    end,
                    __index = function(_, index)
                        return ({ "wrapped-a", "wrapped-b" })[index]
                    end,
                })
                local first, second = table.unpack(hex)
                local ok, message = pcall(table.concat, unicode, ",")
                return table.concat(ascii, ","), table.concat(hex, ","), table.concat(wrapped, ","), ok, message,
                    select("#", table.unpack(hex)), first, second
                """.trimIndent(),
                "table-concat-length-ascii-whitespace.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a,b", state.toString(1))
        assertEquals("hex-a,hex-b", state.toString(2))
        assertEquals("", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("object length is not an integer", state.toString(5))
        assertEquals(2L, state.toInteger(6))
        assertEquals("hex-a", state.toString(7))
        assertEquals("hex-b", state.toString(8))
    }

    @Test
    fun `table concat uses index metamethod values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = setmetatable({}, {
                    __index = function(_, index)
                        return ({ "a", "b" })[index]
                    end,
                })
                local nested = setmetatable({}, {
                    __index = setmetatable({}, {
                        __index = {
                            [1] = "x",
                            [2] = "y",
                        },
                    }),
                })
                return table.concat(values, ",", 1, 2), table.concat(nested, "-", 1, 2)
                """.trimIndent(),
                "table-concat-index-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a,b", state.toString(1))
        assertEquals("x-y", state.toString(2))
    }

    @Test
    fun `table concat accepts primitive values with table-like metatables`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local original = debug.getmetatable("")
                debug.setmetatable("", {
                    __len = function()
                        return 2
                    end,
                    __index = function(value, index)
                        return value .. ":" .. index
                    end,
                })
                local explicit = table.concat("s", "|", 1, 2)
                local defaultRange = table.concat("abc", "|")
                debug.setmetatable("", original)
                return explicit, defaultRange, ("abc"):len()
                """.trimIndent(),
                "table-concat-primitive-table-like.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("s:1|s:2", state.toString(1))
        assertEquals("abc:1|abc:2|abc:3", state.toString(2))
        assertEquals(3L, state.toInteger(3))
    }

    @Test
    fun `table concat rejects primitive values missing table-like fields`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local original = debug.getmetatable("")
                debug.setmetatable("", {
                    __len = function()
                        return 1
                    end,
                })
                local lenOnlyOk, lenOnlyMessage = pcall(table.concat, "abc", "", 1, 1)
                debug.setmetatable("", {
                    __index = function()
                        return "x"
                    end,
                })
                local indexOnlyOk, indexOnlyMessage = pcall(table.concat, "abc", "", 1, 1)
                debug.setmetatable("", original)
                return lenOnlyOk, lenOnlyMessage, indexOnlyOk, indexOnlyMessage
                """.trimIndent(),
                "table-concat-primitive-table-like-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'concat' (table expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #1 to 'concat' (table expected)", state.toString(4))
    }

    @Test
    fun `table concat follows nested index metamethod tables`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = setmetatable({}, {
                    __index = setmetatable({}, {
                        __index = {
                            [1] = "a",
                            [2] = "b",
                        },
                    }),
                })
                return table.concat(values, ",", 1, 2)
                """.trimIndent(),
                "table-concat-nested-index-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a,b", state.toString(1))
    }

    @Test
    fun `table concat reports bad index metamethod chains`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numberValues = setmetatable({}, { __index = 1 })
                local numberOk, numberMessage = pcall(table.concat, numberValues, ",", 1, 1)

                local booleanValues = setmetatable({}, { __index = true })
                local booleanOk, booleanMessage = pcall(table.concat, booleanValues, ",", 1, 1)

                local loopValues = {}
                setmetatable(loopValues, { __index = loopValues })
                local loopOk, loopMessage = pcall(table.concat, loopValues, ",", 1, 1)

                local stringValues = setmetatable({}, { __index = "x" })
                local stringOk, stringMessage = pcall(table.concat, stringValues, ",", 1, 1)

                return numberOk, numberMessage, booleanOk, booleanMessage,
                    loopOk, loopMessage, stringOk, stringMessage
                """.trimIndent(),
                "table-concat-bad-index-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to index a number value", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("attempt to index a boolean value", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("'__index' chain too long; possible loop", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("invalid value (nil) at index 1 in table for 'concat'", state.toString(8))
    }

    @Test
    fun `table concat reports argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.concat("not-table")""", "table-concat-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'concat' (table expected)", state.toString(-1))

        val separatorState = LuaState.create()
        LuaStdlib.openTable(separatorState)

        assertEquals(
            LuaStatus.OK,
            separatorState.load("""return table.concat({"a"}, true)""", "table-concat-separator-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, separatorState.pcall(0, -1))

        assertIs<LuaRuntimeException>(separatorState.getLastError())
        assertEquals("bad argument #2 to 'concat' (string expected)", separatorState.toString(-1))
    }

    @Test
    fun `table concat reports range argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okStartString, startStringMessage = pcall(table.concat, {"a"}, "", "x", 1)
                local okStartFraction, startFractionMessage = pcall(table.concat, {"a"}, "", 1.5, 1)
                local okEndFraction, endFractionMessage = pcall(table.concat, {"a"}, "", 1, "1.5")
                return okStartString, startStringMessage,
                    okStartFraction, startFractionMessage,
                    okEndFraction, endFractionMessage
                """.trimIndent(),
                "table-concat-range-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #3 to 'concat' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #3 to 'concat' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #4 to 'concat' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `table concat reports invalid value types`() {
        val nilState = LuaState.create()
        LuaStdlib.openTable(nilState)

        assertEquals(
            LuaStatus.OK,
            nilState.load("""return table.concat({"a", nil, "c"}, "", 1, 3)""", "table-concat-nil-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, nilState.pcall(0, -1))

        assertIs<LuaRuntimeException>(nilState.getLastError())
        assertEquals("invalid value (nil) at index 2 in table for 'concat'", nilState.toString(-1))

        val booleanState = LuaState.create()
        LuaStdlib.openTable(booleanState)

        assertEquals(
            LuaStatus.OK,
            booleanState.load("""return table.concat({"a", true}, "", 1, 2)""", "table-concat-boolean-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, booleanState.pcall(0, -1))

        assertIs<LuaRuntimeException>(booleanState.getLastError())
        assertEquals("invalid value (boolean) at index 2 in table for 'concat'", booleanState.toString(-1))

        val functionState = LuaState.create()
        LuaStdlib.openTable(functionState)
        assertEquals(
            LuaStatus.OK,
            functionState.load("""return table.concat({function() end}, "", 1, 1)""", "table-concat-function-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, functionState.pcall(0, -1))

        assertIs<LuaRuntimeException>(functionState.getLastError())
        assertEquals("invalid value (function) at index 1 in table for 'concat'", functionState.toString(-1))
    }

    @Test
    fun `table create returns an empty table`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local created = table.create(3)
                local firstKey = next(created)
                return type(created), #created, firstKey == nil
                """.trimIndent(),
                "table-create.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("table", state.toString(1))
        assertEquals(0L, state.toInteger(2))
        assertTrue(state.toBoolean(3))
    }

    @Test
    fun `table create accepts record hints and creates independent tables`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local left = table.create(2, 4)
                local right = table.create(2)
                left[1] = "left"
                right[1] = "right"
                left.name = "record"
                return left[1], right[1], left.name, #left, #right
                """.trimIndent(),
                "table-create-hints.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("left", state.toString(1))
        assertEquals("right", state.toString(2))
        assertEquals("record", state.toString(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals(1L, state.toInteger(5))
    }

    @Test
    fun `table create reports argument errors`() {
        val typeState = LuaState.create()
        LuaStdlib.openTable(typeState)

        assertEquals(LuaStatus.OK, typeState.load("""return table.create("not-integer")""", "table-create-type-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, typeState.pcall(0, -1))

        assertIs<LuaRuntimeException>(typeState.getLastError())
        assertEquals("bad argument #1 to 'create' (number expected)", typeState.toString(-1))

        val fractionalState = LuaState.create()
        LuaStdlib.openBase(fractionalState)
        LuaStdlib.openTable(fractionalState)

        assertEquals(
            LuaStatus.OK,
            fractionalState.load(
                """
                local okSeq, seqMessage = pcall(table.create, 1.5)
                local okRec, recMessage = pcall(table.create, 0, "1.5")
                return okSeq, seqMessage, okRec, recMessage
                """.trimIndent(),
                "table-create-fractional-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, fractionalState.pcall(0, -1), fractionalState.toString(-1))

        assertFalse(fractionalState.toBoolean(1))
        assertEquals("bad argument #1 to 'create' (number has no integer representation)", fractionalState.toString(2))
        assertFalse(fractionalState.toBoolean(3))
        assertEquals("bad argument #2 to 'create' (number has no integer representation)", fractionalState.toString(4))

        val sequenceState = LuaState.create()
        LuaStdlib.openTable(sequenceState)

        assertEquals(LuaStatus.OK, sequenceState.load("""return table.create(-1)""", "table-create-seq-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, sequenceState.pcall(0, -1))

        assertIs<LuaRuntimeException>(sequenceState.getLastError())
        assertEquals(
            "bad argument #1 to 'create' (out of range)",
            sequenceState.toString(-1),
        )

        val recordState = LuaState.create()
        LuaStdlib.openTable(recordState)

        assertEquals(LuaStatus.OK, recordState.load("""return table.create(1, -1)""", "table-create-rec-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, recordState.pcall(0, -1))

        assertIs<LuaRuntimeException>(recordState.getLastError())
        assertEquals(
            "bad argument #2 to 'create' (out of range)",
            recordState.toString(-1),
        )

        val invalidRecordBeforeSequenceRangeState = LuaState.create()
        LuaStdlib.openTable(invalidRecordBeforeSequenceRangeState)

        assertEquals(
            LuaStatus.OK,
            invalidRecordBeforeSequenceRangeState.load(
                """return table.create(-1, "bad")""",
                "table-create-record-type-before-seq-range-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, invalidRecordBeforeSequenceRangeState.pcall(0, -1))

        assertIs<LuaRuntimeException>(invalidRecordBeforeSequenceRangeState.getLastError())
        assertEquals(
            "bad argument #2 to 'create' (number expected)",
            invalidRecordBeforeSequenceRangeState.toString(-1),
        )

        val largeSequenceState = LuaState.create()
        LuaStdlib.openTable(largeSequenceState)

        assertEquals(
            LuaStatus.OK,
            largeSequenceState.load("""return table.create(2147483648)""", "table-create-large-seq-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, largeSequenceState.pcall(0, -1))

        assertIs<LuaRuntimeException>(largeSequenceState.getLastError())
        assertEquals(
            "bad argument #1 to 'create' (out of range)",
            largeSequenceState.toString(-1),
        )

        val largeRecordState = LuaState.create()
        LuaStdlib.openTable(largeRecordState)

        assertEquals(
            LuaStatus.OK,
            largeRecordState.load("""return table.create(0, 2147483648)""", "table-create-large-rec-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, largeRecordState.pcall(0, -1))

        assertIs<LuaRuntimeException>(largeRecordState.getLastError())
        assertEquals(
            "bad argument #2 to 'create' (out of range)",
            largeRecordState.toString(-1),
        )
    }

    @Test
    fun `table integer arguments report non numeric value errors`() {
        fun assertNonNumericIntegerError(source: String, chunkName: String, expected: String) {
            val state = LuaState.create()
            LuaStdlib.openString(state)
            LuaStdlib.openTable(state)

            assertEquals(LuaStatus.OK, state.load(source, chunkName))
            assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

            assertIs<LuaRuntimeException>(state.getLastError())
            assertEquals(expected, state.toString(-1))
        }

        assertNonNumericIntegerError(
            """return table.concat({"a"}, "", "bad")""",
            "table-concat-string-start.lua",
            "bad argument #3 to 'concat' (number expected)",
        )
        assertNonNumericIntegerError(
            """return table.concat({"a"}, "", string.char(226, 128, 131) .. "1")""",
            "table-concat-unicode-space-start.lua",
            "bad argument #3 to 'concat' (number expected)",
        )
        assertNonNumericIntegerError(
            """return table.concat({"a"}, "", 1, "bad")""",
            "table-concat-string-end.lua",
            "bad argument #4 to 'concat' (number expected)",
        )
        assertNonNumericIntegerError(
            """return table.create(1, "bad")""",
            "table-create-string-record.lua",
            "bad argument #2 to 'create' (number expected)",
        )
        assertNonNumericIntegerError(
            """return table.insert({}, "bad", "x")""",
            "table-insert-string-position.lua",
            "bad argument #2 to 'insert' (number expected)",
        )
        assertNonNumericIntegerError(
            """return table.move({}, "bad", 1, 1)""",
            "table-move-string-first.lua",
            "bad argument #2 to 'move' (number expected)",
        )
        assertNonNumericIntegerError(
            """return table.move({}, 1, "bad", 1)""",
            "table-move-string-last.lua",
            "bad argument #3 to 'move' (number expected)",
        )
        assertNonNumericIntegerError(
            """return table.move({}, 1, 1, "bad")""",
            "table-move-string-target.lua",
            "bad argument #4 to 'move' (number expected)",
        )
        assertNonNumericIntegerError(
            """return table.remove({}, "bad")""",
            "table-remove-string-position.lua",
            "bad argument #2 to 'remove' (number expected)",
        )
        assertNonNumericIntegerError(
            """return table.unpack({}, "bad")""",
            "table-unpack-string-start.lua",
            "bad argument #2 to 'unpack' (number expected)",
        )
        assertNonNumericIntegerError(
            """return table.unpack({}, 1, "bad")""",
            "table-unpack-string-end.lua",
            "bad argument #3 to 'unpack' (number expected)",
        )
    }

    @Test
    fun `table integer arguments report fractional number errors`() {
        fun assertFractionalIntegerError(source: String, chunkName: String, expected: String) {
            val state = LuaState.create()
            LuaStdlib.openTable(state)

            assertEquals(LuaStatus.OK, state.load(source, chunkName))
            assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

            assertIs<LuaRuntimeException>(state.getLastError())
            assertEquals(expected, state.toString(-1))
        }

        assertFractionalIntegerError(
            """return table.concat({"a"}, "", 1.5)""",
            "table-concat-fractional-start.lua",
            "bad argument #3 to 'concat' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return table.concat({"a"}, "", 1, 1.5)""",
            "table-concat-fractional-end.lua",
            "bad argument #4 to 'concat' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return table.create(1.5)""",
            "table-create-fractional-sequence.lua",
            "bad argument #1 to 'create' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return table.create(1, 1.5)""",
            "table-create-fractional-record.lua",
            "bad argument #2 to 'create' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return table.insert({}, 1.5, "x")""",
            "table-insert-fractional-position.lua",
            "bad argument #2 to 'insert' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return table.move({}, 1.5, 1, 1)""",
            "table-move-fractional-first.lua",
            "bad argument #2 to 'move' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return table.move({}, 1, 1.5, 1)""",
            "table-move-fractional-last.lua",
            "bad argument #3 to 'move' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return table.move({}, 1, 1, 1.5)""",
            "table-move-fractional-target.lua",
            "bad argument #4 to 'move' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return table.remove({}, 1.5)""",
            "table-remove-fractional-position.lua",
            "bad argument #2 to 'remove' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return table.unpack({}, 1.5)""",
            "table-unpack-fractional-start.lua",
            "bad argument #2 to 'unpack' (number has no integer representation)",
        )
        assertFractionalIntegerError(
            """return table.unpack({}, 1, 1.5)""",
            "table-unpack-fractional-end.lua",
            "bad argument #3 to 'unpack' (number has no integer representation)",
        )
    }

    @Test
    fun `table insert mutates list values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {"a", "c"}
                table.insert(values, "d")
                table.insert(values, 2, "b")
                local fallback = setmetatable({}, {
                    __len = function() return 2 end,
                    __index = function(_, index)
                        return ({ "meta-a", "meta-b" })[index]
                    end,
                })
                table.insert(fallback, 2, "inserted")
                return values[1], values[2], values[3], values[4], #values,
                    rawget(fallback, 1), rawget(fallback, 2), rawget(fallback, 3)
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
        assertTrue(state.isNil(6))
        assertEquals("inserted", state.toString(7))
        assertEquals("meta-b", state.toString(8))
    }

    @Test
    fun `table insert preserves table values`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local first = {name = "first"}
                local second = {name = "second"}
                local values = {}
                table.insert(values, first)
                table.insert(values, 1, second)
                return values[1] == second, values[2] == first, values[1].name, values[2].name
                """.trimIndent(),
                "table-insert-table-values.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertEquals("second", state.toString(3))
        assertEquals("first", state.toString(4))
    }

    @Test
    fun `table insert wraps first empty slot like lua integer arithmetic`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openMath(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local appendWrites = {}
                local appendTarget = setmetatable({}, {
                    __len = function()
                        return math.maxinteger
                    end,
                    __newindex = function(_, key, value)
                        appendWrites[#appendWrites + 1] = tostring(key) .. ":" .. value
                    end,
                })
                table.insert(appendTarget, "tail")

                local positionedWrites = {}
                local positionedTarget = setmetatable({}, {
                    __len = function()
                        return math.maxinteger
                    end,
                    __newindex = function(_, key, value)
                        positionedWrites[#positionedWrites + 1] = tostring(key) .. ":" .. value
                    end,
                })
                table.insert(positionedTarget, 1, "head")

                return appendWrites[1], positionedWrites[1]
                """.trimIndent(),
                "table-insert-wrapped-first-empty.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("${Long.MIN_VALUE}:tail", state.toString(1))
        assertEquals("1:head", state.toString(2))
    }

    @Test
    fun `table insert writes missing slots through newindex metamethod`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local writes = {}
                local values = setmetatable({}, {
                    __len = function()
                        return 2
                    end,
                    __index = function(_, index)
                        return ({ "a", "b" })[index]
                    end,
                    __newindex = function(_, key, value)
                        writes[#writes + 1] = key .. ":" .. tostring(value)
                    end,
                })
                table.insert(values, 2, "inserted")
                return #writes, writes[1], writes[2], rawget(values, 2), rawget(values, 3)
                """.trimIndent(),
                "table-insert-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(2L, state.toInteger(1))
        assertEquals("3:b", state.toString(2))
        assertEquals("2:inserted", state.toString(3))
        assertTrue(state.isNil(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `table insert uses wrapped first empty position like lua`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openMath(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local writes = {}
                local values = setmetatable({}, {
                    __len = function()
                        return math.maxinteger
                    end,
                    __newindex = function(_, key, value)
                        writes[#writes + 1] = tostring(key) .. ":" .. tostring(value)
                    end,
                })
                table.insert(values, 1, "first")
                table.insert(values, math.mininteger, "wrapped")
                table.insert(values, math.maxinteger, "last")
                return writes[1], writes[2], writes[3]
                """.trimIndent(),
                "table-insert-wrapped-first-empty.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("1:first", state.toString(1))
        assertEquals("-9223372036854775808:wrapped", state.toString(2))
        assertEquals("9223372036854775807:last", state.toString(3))
    }

    @Test
    fun `table insert follows table newindex metamethod chains`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local target = {}
                local proxy = setmetatable({}, {
                    __len = function()
                        return 0
                    end,
                    __newindex = setmetatable({}, {
                        __newindex = target,
                    }),
                })
                table.insert(proxy, "value")
                return target[1], rawget(proxy, 1)
                """.trimIndent(),
                "table-insert-table-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("value", state.toString(1))
        assertTrue(state.isNil(2))
    }

    @Test
    fun `table insert accepts primitive values with table-like metatables`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local original = debug.getmetatable(0)
                local values = { "a", "b" }
                local len = 2
                debug.setmetatable(0, {
                    __len = function()
                        return len
                    end,
                    __index = function(_, index)
                        return values[index]
                    end,
                    __newindex = function(_, index, value)
                        values[index] = value
                        if value ~= nil and index > len then
                            len = index
                        end
                    end,
                })
                table.insert(7, "c")
                table.insert(7, 2, "x")
                debug.setmetatable(0, original)
                return values[1], values[2], values[3], values[4], len
                """.trimIndent(),
                "table-insert-primitive-table-like.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a", state.toString(1))
        assertEquals("x", state.toString(2))
        assertEquals("b", state.toString(3))
        assertEquals("c", state.toString(4))
        assertEquals(4L, state.toInteger(5))
    }

    @Test
    fun `table insert reports argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.insert("not-table", "x")""", "table-insert-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'insert' (table expected)", state.toString(-1))

        val arityState = LuaState.create()
        LuaStdlib.openBase(arityState)
        LuaStdlib.openTable(arityState)

        assertEquals(
            LuaStatus.OK,
            arityState.load(
                """
                local missingOk, missingMessage = pcall(table.insert, {})
                local extraOk, extraMessage = pcall(table.insert, {}, 1, "x", "extra")
                local boundsOk, boundsMessage = pcall(table.insert, {}, 2, "x")
                local stringOk, stringMessage = pcall(table.insert, {}, "not-index", "x")
                local fractionOk, fractionMessage = pcall(table.insert, {}, 1.5, "x")
                return missingOk, missingMessage,
                    extraOk, extraMessage,
                    boundsOk, boundsMessage,
                    stringOk, stringMessage,
                    fractionOk, fractionMessage
                """.trimIndent(),
                "table-insert-arity-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, arityState.pcall(0, -1), arityState.toString(-1))

        assertFalse(arityState.toBoolean(1))
        assertEquals("wrong number of arguments to 'insert'", arityState.toString(2))
        assertFalse(arityState.toBoolean(3))
        assertEquals("wrong number of arguments to 'insert'", arityState.toString(4))
        assertFalse(arityState.toBoolean(5))
        assertEquals("bad argument #2 to 'insert' (position out of bounds)", arityState.toString(6))
        assertFalse(arityState.toBoolean(7))
        assertEquals("bad argument #2 to 'insert' (number expected)", arityState.toString(8))
        assertFalse(arityState.toBoolean(9))
        assertEquals("bad argument #2 to 'insert' (number has no integer representation)", arityState.toString(10))
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
                local nextKey, nextValue = next(packed, 1)
                local seenNilSlot = false
                for key in pairs(packed) do
                    if key == 2 then
                        seenNilSlot = true
                    end
                end
                return packed[1], packed[2], packed[3], packed.n, count,
                    nextKey, nextValue, seenNilSlot
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
        assertEquals(3L, state.toInteger(6))
        assertEquals("c", state.toString(7))
        assertFalse(state.toBoolean(8))
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
    fun `table unpack accepts primitive values with table-like metatables`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local original = debug.getmetatable(0)
                debug.setmetatable(0, {
                    __index = function(_, index)
                        return ({ "a", "b" })[index]
                    end,
                })
                local explicitA, explicitB = table.unpack(7, 1, 2)
                debug.setmetatable(0, {
                    __len = function()
                        return 2
                    end,
                    __index = function(_, index)
                        return ({ "x", "y" })[index]
                    end,
                })
                local defaultA, defaultB = table.unpack(7)
                debug.setmetatable(0, original)
                return explicitA, explicitB, defaultA, defaultB
                """.trimIndent(),
                "table-unpack-primitive-table-like.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a", state.toString(1))
        assertEquals("b", state.toString(2))
        assertEquals("x", state.toString(3))
        assertEquals("y", state.toString(4))
    }

    @Test
    fun `table move copies ranges and returns destination table`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local source = {"a", "b", "c"}
                local destination = {"x"}
                local returned = table.move(source, 1, 2, 2, destination)
                returned[4] = "tail"
                local fallback = setmetatable({}, {
                    __index = function(_, index)
                        return ({ "meta-a", "meta-b" })[index]
                    end,
                })
                local fallbackDestination = {}
                local fallbackReturned = table.move(fallback, 1, 2, 1, fallbackDestination)
                return destination[1], destination[2], destination[3], destination[4], returned == destination,
                    fallbackDestination[1], fallbackDestination[2], fallbackReturned == fallbackDestination
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
        assertEquals("meta-a", state.toString(6))
        assertEquals("meta-b", state.toString(7))
        assertTrue(state.toBoolean(8))
    }

    @Test
    fun `table move accepts primitive values with table-like metatables`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local original = debug.getmetatable(0)
                debug.setmetatable(0, {
                    __index = function(_, index)
                        return ({ "a", "b" })[index]
                    end,
                })
                local destination = {}
                local destinationReturned = table.move(7, 1, 2, 1, destination)

                local writes = {}
                debug.setmetatable(0, {
                    __newindex = function(_, index, value)
                        writes[#writes + 1] = index .. ":" .. tostring(value)
                    end,
                })
                local primitiveDestinationReturned = table.move({ "x", "y" }, 1, 2, 1, 7)

                local values = { "m", "n", "o" }
                debug.setmetatable(0, {
                    __index = function(_, index)
                        return values[index]
                    end,
                    __newindex = function(_, index, value)
                        values[index] = value
                    end,
                })
                local selfReturned = table.move(7, 1, 3, 2)
                debug.setmetatable(0, original)
                return destinationReturned == destination, destination[1], destination[2],
                    primitiveDestinationReturned,
                    writes[1], writes[2],
                    selfReturned, values[1], values[2], values[3], values[4]
                """.trimIndent(),
                "table-move-primitive-table-like.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("a", state.toString(2))
        assertEquals("b", state.toString(3))
        assertEquals(7L, state.toInteger(4))
        assertEquals("1:x", state.toString(5))
        assertEquals("2:y", state.toString(6))
        assertEquals(7L, state.toInteger(7))
        assertEquals("m", state.toString(8))
        assertEquals("m", state.toString(9))
        assertEquals("n", state.toString(10))
        assertEquals("o", state.toString(11))
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
    fun `table move writes missing destination slots through newindex metamethod`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local writes = {}
                local destination = setmetatable({}, {
                    __newindex = function(_, key, value)
                        writes[#writes + 1] = key .. ":" .. tostring(value)
                    end,
                })
                local returned = table.move({"a", nil, "c"}, 1, 3, 1, destination)
                return returned == destination, #writes, writes[1], writes[2], writes[3],
                    rawget(destination, 1), rawget(destination, 2), rawget(destination, 3)
                """.trimIndent(),
                "table-move-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals(3L, state.toInteger(2))
        assertEquals("1:a", state.toString(3))
        assertEquals("2:nil", state.toString(4))
        assertEquals("3:c", state.toString(5))
        assertTrue(state.isNil(6))
        assertTrue(state.isNil(7))
        assertTrue(state.isNil(8))
    }

    @Test
    fun `table move uses equality metamethod for overlap direction`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local storage = {"a", "b", "c"}
                local metatable = {
                    __len = function()
                        return 3
                    end,
                    __index = function(_, key)
                        return storage[key]
                    end,
                    __newindex = function(_, key, value)
                        storage[key] = value
                    end,
                    __eq = function()
                        return true
                    end,
                }
                local source = setmetatable({}, metatable)
                local destination = setmetatable({}, metatable)
                table.move(source, 1, 3, 2, destination)
                return storage[1], storage[2], storage[3], storage[4]
                """.trimIndent(),
                "table-move-eq-overlap.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a", state.toString(1))
        assertEquals("a", state.toString(2))
        assertEquals("b", state.toString(3))
        assertEquals("c", state.toString(4))
    }

    @Test
    fun `table move follows table newindex metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local target = {}
                local destination = setmetatable({}, {
                    __newindex = target,
                })
                table.move({"a", "b"}, 1, 2, 1, destination)
                return target[1], target[2], rawget(destination, 1), rawget(destination, 2)
                """.trimIndent(),
                "table-move-table-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a", state.toString(1))
        assertEquals("b", state.toString(2))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
    }

    @Test
    fun `table move accepts table-like non-table sources and destinations`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local writes = {}
                debug.setmetatable("source", {
                    __index = function(value, index)
                        return ({ "a", "b", "c" })[index]
                    end,
                })
                debug.setmetatable(42, {
                    __newindex = function(value, key, moved)
                        writes[#writes + 1] = value .. ":" .. key .. ":" .. tostring(moved)
                    end,
                })

                local destination = {}
                local returnedTable = table.move("source", 1, 2, 2, destination)
                local returnedValue = table.move("source", 2, 3, 5, 42)

                return returnedTable == destination,
                    destination[1], destination[2], destination[3],
                    returnedValue,
                    #writes, writes[1], writes[2]
                """.trimIndent(),
                "table-move-table-like.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.isNil(2))
        assertEquals("a", state.toString(3))
        assertEquals("b", state.toString(4))
        assertEquals(42L, state.toInteger(5))
        assertEquals(2L, state.toInteger(6))
        assertEquals("42:5:b", state.toString(7))
        assertEquals("42:6:c", state.toString(8))
    }

    @Test
    fun `table move rejects table-like values missing required methods`() {
        val missingSourceState = LuaState.create()
        LuaStdlib.openLibs(missingSourceState)

        assertEquals(
            LuaStatus.OK,
            missingSourceState.load(
                """
                debug.setmetatable("source", {
                    __newindex = function() end,
                })
                return table.move("source", 1, 1, 1, {})
                """.trimIndent(),
                "table-move-table-like-source-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, missingSourceState.pcall(0, -1))

        assertIs<LuaRuntimeException>(missingSourceState.getLastError())
        assertEquals("bad argument #1 to 'move' (table expected)", missingSourceState.toString(-1))

        val missingDestinationState = LuaState.create()
        LuaStdlib.openLibs(missingDestinationState)

        assertEquals(
            LuaStatus.OK,
            missingDestinationState.load(
                """
                debug.setmetatable(42, {
                    __index = {
                        [1] = "x",
                    },
                })
                return table.move({ "x" }, 1, 1, 1, 42)
                """.trimIndent(),
                "table-move-table-like-destination-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, missingDestinationState.pcall(0, -1))

        assertIs<LuaRuntimeException>(missingDestinationState.getLastError())
        assertEquals("bad argument #5 to 'move' (table expected)", missingDestinationState.toString(-1))
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
    fun `table move uses lua equality for overlap direction`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local log = {}
                local metatable = {}
                metatable.__eq = function()
                    return true
                end
                local sourceValues = {
                    [1] = "a",
                    [2] = "b",
                    [3] = "c",
                }
                metatable.__index = function(_, key)
                    log[#log + 1] = "read:" .. tostring(key)
                    return sourceValues[key]
                end
                metatable.__newindex = function(_, key, value)
                    log[#log + 1] = "write:" .. tostring(key) .. ":" .. tostring(value)
                end
                local source = setmetatable({}, metatable)
                local destination = setmetatable({}, metatable)
                table.move(source, 1, 3, 2, destination)
                return table.concat(log, "|")
                """.trimIndent(),
                "table-move-eq-overlap.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("read:3|write:4:c|read:2|write:3:b|read:1|write:2:a", state.toString(1))
    }

    @Test
    fun `table move preserves nil holes`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local source = {"a", nil, "c"}
                local destination = {"x", "y", "z"}
                table.move(source, 1, 3, 1, destination)
                return destination[1], destination[2], destination[3]
                """.trimIndent(),
                "table-move-nil-holes.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("a", state.toString(1))
        assertTrue(state.isNil(2))
        assertEquals("c", state.toString(3))
    }

    @Test
    fun `table move accepts string sources with explicit destination tables`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openDebug(state)
        LuaStdlib.openString(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local original = debug.getmetatable("")
                debug.setmetatable("", { __index = string })
                local destination = {"x", "y", "z"}
                local returned = table.move("abc", 1, 2, 1, destination)
                local emptyRangeDestination = {"kept"}
                local emptyReturned = table.move("abc", 2, 1, 1, emptyRangeDestination)
                debug.setmetatable("", original)
                return returned == destination,
                    destination[1], destination[2], destination[3],
                    emptyReturned == emptyRangeDestination, emptyRangeDestination[1]
                """.trimIndent(),
                "table-move-string-source.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.isNil(2))
        assertTrue(state.isNil(3))
        assertEquals("z", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertEquals("kept", state.toString(6))
    }

    @Test
    fun `table move reports argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local sourceOk, sourceMessage = pcall(table.move, "not-table", 1, 1, 1)
                local destinationOk, destinationMessage = pcall(table.move, {}, 1, 1, 1, "not-table")
                return sourceOk, sourceMessage, destinationOk, destinationMessage
                """.trimIndent(),
                "table-move-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'move' (table expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #5 to 'move' (table expected)", state.toString(4))

        val orderState = LuaState.create()
        LuaStdlib.openBase(orderState)
        LuaStdlib.openTable(orderState)

        assertEquals(
            LuaStatus.OK,
            orderState.load(
                """
                local okString, stringMessage = pcall(table.move, "not-table", "not-integer", 1, 1)
                local okFirst, firstMessage = pcall(table.move, {}, 1.5, 1, 1)
                local okLast, lastMessage = pcall(table.move, {}, 1, "1.5", 1)
                local okTarget, targetMessage = pcall(table.move, {}, 1, 1, 1.5)
                return okString, stringMessage,
                    okFirst, firstMessage,
                    okLast, lastMessage,
                    okTarget, targetMessage
                """.trimIndent(),
                "table-move-validation-order.lua",
            ),
        )
        assertEquals(LuaStatus.OK, orderState.pcall(0, -1), orderState.toString(-1))

        assertFalse(orderState.toBoolean(1))
        assertEquals("bad argument #2 to 'move' (number expected)", orderState.toString(2))
        assertFalse(orderState.toBoolean(3))
        assertEquals("bad argument #2 to 'move' (number has no integer representation)", orderState.toString(4))
        assertFalse(orderState.toBoolean(5))
        assertEquals("bad argument #3 to 'move' (number has no integer representation)", orderState.toString(6))
        assertFalse(orderState.toBoolean(7))
        assertEquals("bad argument #4 to 'move' (number has no integer representation)", orderState.toString(8))
    }

    @Test
    fun `table move reports range overflow errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return table.move({}, math.mininteger, math.maxinteger, 1)""", "table-move-range-overflow.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #3 to 'move' (too many elements to move)", state.toString(-1))
    }

    @Test
    fun `table move reports destination wrap errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return table.move({}, 1, 3, math.maxinteger)""", "table-move-destination-wrap.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #4 to 'move' (destination wrap around)", state.toString(-1))
    }

    @Test
    fun `table move empty ranges skip overflow validation`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local destination = {}
                local returned = table.move({}, 2, 1, math.maxinteger, destination)
                return returned == destination
                """.trimIndent(),
                "table-move-empty-extreme-target.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
    }

    @Test
    fun `table remove mutates list values and returns removed values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
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
                local defaultEmptyRemoved = table.remove({})
                local fallback = setmetatable({}, {
                    __len = function() return 2 end,
                    __index = function(_, index)
                        return ({ "meta-a", "meta-b" })[index]
                    end,
                })
                local fallbackRemoved = table.remove(fallback)
                return middle, last, trailing, emptyRemoved, defaultEmptyRemoved,
                    values[1], values[2], values[3], #values,
                    fallbackRemoved, rawget(fallback, 1), rawget(fallback, 2)
                """.trimIndent(),
                "table-remove.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("b", state.toString(1))
        assertEquals("d", state.toString(2))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
        assertTrue(state.isNil(5))
        assertEquals("a", state.toString(6))
        assertEquals("c", state.toString(7))
        assertTrue(state.isNil(8))
        assertEquals(2L, state.toInteger(9))
        assertEquals("meta-b", state.toString(10))
        assertTrue(state.isNil(11))
        assertTrue(state.isNil(12))
    }

    @Test
    fun `table remove writes missing slots through newindex metamethod`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local writes = {}
                local values = setmetatable({}, {
                    __len = function()
                        return 2
                    end,
                    __index = function(_, index)
                        return ({ "a", "b" })[index]
                    end,
                    __newindex = function(_, key, value)
                        writes[#writes + 1] = key .. ":" .. tostring(value)
                    end,
                })
                local removed = table.remove(values, 1)
                local trailing = table.remove(values, 3)
                return removed, #writes, writes[1], writes[2], writes[3],
                    trailing, rawget(values, 1), rawget(values, 2), rawget(values, 3)
                """.trimIndent(),
                "table-remove-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a", state.toString(1))
        assertEquals(3L, state.toInteger(2))
        assertEquals("1:b", state.toString(3))
        assertEquals("2:nil", state.toString(4))
        assertEquals("3:nil", state.toString(5))
        assertTrue(state.isNil(6))
        assertTrue(state.isNil(7))
        assertTrue(state.isNil(8))
        assertTrue(state.isNil(9))
    }

    @Test
    fun `table remove accepts primitive values with table-like metatables`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local original = debug.getmetatable(0)
                local values = { "a", "b", "c" }
                local len = 3
                debug.setmetatable(0, {
                    __len = function()
                        return len
                    end,
                    __index = function(_, index)
                        return values[index]
                    end,
                    __newindex = function(_, index, value)
                        values[index] = value
                        if value == nil and index == len then
                            len = len - 1
                        end
                    end,
                })
                local removed = table.remove(7, 2)
                debug.setmetatable(0, original)
                return removed, values[1], values[2], values[3], len
                """.trimIndent(),
                "table-remove-primitive-table-like.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("b", state.toString(1))
        assertEquals("a", state.toString(2))
        assertEquals("c", state.toString(3))
        assertTrue(state.isNil(4))
        assertEquals(2L, state.toInteger(5))
    }

    @Test
    fun `table remove follows table newindex metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local target = {}
                local values = setmetatable({}, {
                    __len = function()
                        return 2
                    end,
                    __index = function(_, index)
                        return ({ "a", "b" })[index]
                    end,
                    __newindex = target,
                })
                local removed = table.remove(values, 1)
                return removed, target[1], target[2], rawget(values, 1), rawget(values, 2)
                """.trimIndent(),
                "table-remove-table-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a", state.toString(1))
        assertEquals("b", state.toString(2))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
        assertTrue(state.isNil(5))
    }

    @Test
    fun `table remove writes nil at zero for empty table removals`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local writes = {}
                local values = setmetatable({}, {
                    __newindex = function(_, key, value)
                        writes[#writes + 1] = key .. ":" .. tostring(value)
                    end,
                })
                local defaultRemoved = table.remove(values)
                local zeroRemoved = table.remove(values, 0)
                local afterEndRemoved = table.remove(values, 1)
                return defaultRemoved, zeroRemoved, afterEndRemoved, #writes, writes[1], writes[2], writes[3]
                """.trimIndent(),
                "table-remove-empty-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertTrue(state.isNil(2))
        assertTrue(state.isNil(3))
        assertEquals(3L, state.toInteger(4))
        assertEquals("0:nil", state.toString(5))
        assertEquals("0:nil", state.toString(6))
        assertEquals("1:nil", state.toString(7))
    }

    @Test
    fun `table remove reads after end and negative length positions like lua`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local afterEndWrites = {}
                local afterEnd = setmetatable({}, {
                    __len = function()
                        return 1
                    end,
                    __index = function(_, key)
                        return "value:" .. tostring(key)
                    end,
                    __newindex = function(_, key, value)
                        afterEndWrites[#afterEndWrites + 1] = tostring(key) .. ":" .. tostring(value)
                    end,
                })
                local afterEndRemoved = table.remove(afterEnd, 2)

                local negativeWrites = {}
                local negative = setmetatable({}, {
                    __len = function()
                        return -1
                    end,
                    __index = function(_, key)
                        return "value:" .. tostring(key)
                    end,
                    __newindex = function(_, key, value)
                        negativeWrites[#negativeWrites + 1] = tostring(key) .. ":" .. tostring(value)
                    end,
                })
                local negativeRemoved = table.remove(negative, -2)

                return afterEndRemoved, afterEndWrites[1],
                    negativeRemoved, negativeWrites[1], negativeWrites[2]
                """.trimIndent(),
                "table-remove-after-end-index.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("value:2", state.toString(1))
        assertEquals("2:nil", state.toString(2))
        assertEquals("value:-2", state.toString(3))
        assertEquals("-2:value:-1", state.toString(4))
        assertEquals("-1:nil", state.toString(5))
    }

    @Test
    fun `table remove reports table argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.remove("not-table")""", "table-remove-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'remove' (table expected)", state.toString(-1))
    }

    @Test
    fun `table remove reports position argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okString, stringMessage = pcall(table.remove, {"a"}, "not-index")
                local okFraction, fractionMessage = pcall(table.remove, {"a"}, 1.5)
                local okBounds, boundsMessage = pcall(table.remove, {"a"}, 3)
                return okString, stringMessage, okFraction, fractionMessage, okBounds, boundsMessage
                """.trimIndent(),
                "table-remove-position-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'remove' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'remove' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #2 to 'remove' (position out of bounds)", state.toString(6))
    }

    @Test
    fun `table sort orders numeric and string lists`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numbers = {3, 1, 2}
                local names = {"beta", "alpha", "gamma"}
                local raw = string.char(128)
                local utf8 = "é"
                local rawNames = {utf8, raw}
                local fallback = setmetatable({}, {
                    __len = function() return 3 end,
                    __index = function(_, index)
                        return ({ 3, 1, 2 })[index]
                    end,
                })
                table.sort(numbers)
                table.sort(names)
                table.sort(rawNames)
                table.sort(fallback)
                return numbers[1], numbers[2], numbers[3], names[1], names[2], names[3],
                    rawget(fallback, 1), rawget(fallback, 2), rawget(fallback, 3),
                    string.byte(rawNames[1]), rawNames[2] == utf8
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
        assertEquals(1L, state.toInteger(7))
        assertEquals(2L, state.toInteger(8))
        assertEquals(3L, state.toInteger(9))
        assertEquals(128L, state.toInteger(10))
        assertTrue(state.toBoolean(11))
    }

    @Test
    fun `table sort orders adjacent large integers exactly`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {
                    math.maxinteger,
                    math.mininteger + 1,
                    math.maxinteger - 1,
                    math.mininteger,
                }
                table.sort(values)
                return values[1], values[2], values[3], values[4]
                """.trimIndent(),
                "table-sort-large-integers.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(Long.MIN_VALUE, state.toInteger(1))
        assertEquals(Long.MIN_VALUE + 1L, state.toInteger(2))
        assertEquals(Long.MAX_VALUE - 1L, state.toInteger(3))
        assertEquals(Long.MAX_VALUE, state.toInteger(4))
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
    fun `table sort default comparison uses less than metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local metatable = {
                    __lt = function(left, right)
                        return left.rank < right.rank
                    end,
                }
                local low = setmetatable({ rank = 1, name = "low" }, metatable)
                local middle = setmetatable({ rank = 3, name = "middle" }, metatable)
                local high = setmetatable({ rank = 5, name = "high" }, metatable)
                local values = { high, low, middle }
                table.sort(values)
                return values[1] == low, values[2] == middle, values[3] == high,
                    values[1].name, values[2].name, values[3].name
                """.trimIndent(),
                "table-sort-lt-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertEquals("low", state.toString(4))
        assertEquals("middle", state.toString(5))
        assertEquals("high", state.toString(6))
    }

    @Test
    fun `table sort does not add reverse comparator calls`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {3, 1, 2}
                local trueComparisons = {}
                local sawReverseCall = false
                table.sort(values, function(a, b)
                    if trueComparisons[b .. ":" .. a] then
                        sawReverseCall = true
                    end
                    local result = a < b
                    if result then
                        trueComparisons[a .. ":" .. b] = true
                    end
                    return result
                end)
                return values[1], values[2], values[3], sawReverseCall
                """.trimIndent(),
                "table-sort-no-reverse-comparator-call.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(3L, state.toInteger(3))
        assertFalse(state.toBoolean(4))
    }

    @Test
    fun `table sort writes missing slots through newindex metamethod`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local writes = {}
                local values = setmetatable({}, {
                    __len = function()
                        return 3
                    end,
                    __index = function(_, index)
                        return ({ 3, 1, 2 })[index]
                    end,
                    __newindex = function(_, key, value)
                        writes[#writes + 1] = key .. ":" .. tostring(value)
                    end,
                })
                table.sort(values)
                return #writes, writes[1], writes[2], writes[3], writes[4], rawget(values, 1), rawget(values, 2), rawget(values, 3)
                """.trimIndent(),
                "table-sort-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(4L, state.toInteger(1))
        assertEquals("1:2", state.toString(2))
        assertEquals("3:3", state.toString(3))
        assertEquals("2:3", state.toString(4))
        assertEquals("1:1", state.toString(5))
        assertTrue(state.isNil(6))
        assertTrue(state.isNil(7))
        assertTrue(state.isNil(8))
    }

    @Test
    fun `table sort accepts primitive values with table-like metatables`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local original = debug.getmetatable(0)
                local values = { 3, 1, 2 }
                debug.setmetatable(0, {
                    __len = function()
                        return 3
                    end,
                    __index = function(_, index)
                        return values[index]
                    end,
                    __newindex = function(_, index, value)
                        values[index] = value
                    end,
                })
                table.sort(7)
                debug.setmetatable(0, original)
                return values[1], values[2], values[3]
                """.trimIndent(),
                "table-sort-primitive-table-like.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(3L, state.toInteger(3))
    }

    @Test
    fun `table sort follows table newindex metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local target = {}
                local values = setmetatable({}, {
                    __len = function()
                        return 3
                    end,
                    __index = function(_, index)
                        return ({ 3, 1, 2 })[index]
                    end,
                    __newindex = target,
                })
                table.sort(values)
                return target[1], target[2], target[3], rawget(values, 1), rawget(values, 2), rawget(values, 3)
                """.trimIndent(),
                "table-sort-table-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(3L, state.toInteger(2))
        assertEquals(3L, state.toInteger(3))
        assertTrue(state.isNil(4))
        assertTrue(state.isNil(5))
        assertTrue(state.isNil(6))
    }

    @Test
    fun `table sort accepts table-like non-table values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)
        LuaStdlib.openDebug(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = { 3, 1, 2 }
                debug.setmetatable("proxy", {
                    __len = function(value)
                        return value == "proxy" and 3 or 0
                    end,
                    __index = function(value, index)
                        return value == "proxy" and values[index] or nil
                    end,
                    __newindex = function(value, key, sorted)
                        if value == "proxy" then
                            values[key] = sorted
                        end
                    end,
                })
                table.sort("proxy")
                return values[1], values[2], values[3]
                """.trimIndent(),
                "table-sort-table-like.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(1L, state.toInteger(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals(3L, state.toInteger(3))
    }

    @Test
    fun `table sort rejects table-like values missing required methods`() {
        val indexState = LuaState.create()
        LuaStdlib.openBase(indexState)
        LuaStdlib.openTable(indexState)
        LuaStdlib.openDebug(indexState)

        assertEquals(
            LuaStatus.OK,
            indexState.load(
                """
                debug.setmetatable("proxy", {
                    __len = function() return 2 end,
                    __newindex = function() end,
                })
                return table.sort("proxy")
                """.trimIndent(),
                "table-sort-table-like-index-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, indexState.pcall(0, -1))

        assertIs<LuaRuntimeException>(indexState.getLastError())
        assertEquals("bad argument #1 to 'sort' (table expected)", indexState.toString(-1))

        val newIndexState = LuaState.create()
        LuaStdlib.openBase(newIndexState)
        LuaStdlib.openTable(newIndexState)
        LuaStdlib.openDebug(newIndexState)

        assertEquals(
            LuaStatus.OK,
            newIndexState.load(
                """
                debug.setmetatable(42, {
                    __len = function() return 2 end,
                    __index = { 2, 1 },
                })
                return table.sort(42)
                """.trimIndent(),
                "table-sort-table-like-newindex-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, newIndexState.pcall(0, -1))

        assertIs<LuaRuntimeException>(newIndexState.getLastError())
        assertEquals("bad argument #1 to 'sort' (table expected)", newIndexState.toString(-1))
    }

    @Test
    fun `table insert reports cyclic table newindex chains`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local proxy = {}
                setmetatable(proxy, {
                    __len = function()
                        return 0
                    end,
                    __newindex = proxy,
                })
                return table.insert(proxy, "value")
                """.trimIndent(),
                "table-insert-newindex-cycle.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("'__newindex' chain too long; possible loop", state.toString(-1))
    }

    @Test
    fun `table insert reports nonindexable newindex values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numberValues = setmetatable({}, { __newindex = 1 })
                local numberOk, numberMessage = pcall(table.insert, numberValues, "value")

                local booleanValues = setmetatable({}, { __newindex = true })
                local booleanOk, booleanMessage = pcall(table.insert, booleanValues, "value")

                local stringValues = setmetatable({}, { __newindex = "x" })
                local stringOk, stringMessage = pcall(table.insert, stringValues, "value")

                return numberOk, numberMessage, booleanOk, booleanMessage, stringOk, stringMessage
                """.trimIndent(),
                "table-insert-newindex-nonindexable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to index a number value", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("attempt to index a boolean value", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("attempt to index a string value", state.toString(6))
    }

    @Test
    fun `table sort reports argument errors`() {
        val tableState = LuaState.create()
        LuaStdlib.openTable(tableState)

        assertEquals(LuaStatus.OK, tableState.load("""return table.sort("not-table")""", "table-sort-table-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, tableState.pcall(0, -1))

        assertIs<LuaRuntimeException>(tableState.getLastError())
        assertEquals("bad argument #1 to 'sort' (table expected)", tableState.toString(-1))

        val comparatorState = LuaState.create()
        LuaStdlib.openTable(comparatorState)

        assertEquals(
            LuaStatus.OK,
            comparatorState.load("""return table.sort({2, 1}, true)""", "table-sort-comparator-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, comparatorState.pcall(0, -1))

        assertIs<LuaRuntimeException>(comparatorState.getLastError())
        assertEquals("bad argument #2 to 'sort' (function expected)", comparatorState.toString(-1))
    }

    @Test
    fun `table sort reports function comparison errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return table.sort({function() end, 1})""", "table-sort-function-compare-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("attempt to compare number with function", state.toString(-1))
    }

    @Test
    fun `table sort reports same type comparison errors`() {
        val functionState = LuaState.create()
        LuaStdlib.openTable(functionState)

        assertEquals(
            LuaStatus.OK,
            functionState.load(
                """return table.sort({function() end, function() end})""",
                "table-sort-two-functions-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, functionState.pcall(0, -1))

        assertIs<LuaRuntimeException>(functionState.getLastError())
        assertEquals("attempt to compare two function values", functionState.toString(-1))

        val tableState = LuaState.create()
        LuaStdlib.openTable(tableState)

        assertEquals(
            LuaStatus.OK,
            tableState.load("""return table.sort({{}, {}})""", "table-sort-two-tables-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, tableState.pcall(0, -1))

        assertIs<LuaRuntimeException>(tableState.getLastError())
        assertEquals("attempt to compare two table values", tableState.toString(-1))
    }

    @Test
    fun `table sort reports nil and boolean comparison errors`() {
        val nilState = LuaState.create()
        LuaStdlib.openBase(nilState)
        LuaStdlib.openTable(nilState)

        assertEquals(
            LuaStatus.OK,
            nilState.load(
                """
                local values = setmetatable({}, {
                    __len = function()
                        return 2
                    end,
                    __index = "x",
                })
                return table.sort(values)
                """.trimIndent(),
                "table-sort-two-nils-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, nilState.pcall(0, -1))

        assertIs<LuaRuntimeException>(nilState.getLastError())
        assertEquals("attempt to compare two nil values", nilState.toString(-1))

        val booleanState = LuaState.create()
        LuaStdlib.openTable(booleanState)

        assertEquals(
            LuaStatus.OK,
            booleanState.load("""return table.sort({true, false})""", "table-sort-two-booleans-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, booleanState.pcall(0, -1))

        assertIs<LuaRuntimeException>(booleanState.getLastError())
        assertEquals("attempt to compare two boolean values", booleanState.toString(-1))
    }

    @Test
    fun `table sort skips comparator validation for short lists`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local empty = {}
                local single = {1}
                table.sort(empty, true)
                table.sort(single, true)
                return #empty, single[1]
                """.trimIndent(),
                "table-sort-short-comparator.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0L, state.toInteger(1))
        assertEquals(1L, state.toInteger(2))
    }

    @Test
    fun `table sort reports array size errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = setmetatable({}, {
                    __len = function() return 2147483647 end,
                })
                return table.sort(values)
                """.trimIndent(),
                "table-sort-array-size-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'sort' (array too big)", state.toString(-1))
    }

    @Test
    fun `table sort does not reject short invalid comparator order`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {1, 2}
                table.sort(values, function(a, b) return true end)
                return values[1], values[2]
                """.trimIndent(),
                "table-sort-short-invalid-order.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(2L, state.toInteger(1))
        assertEquals(1L, state.toInteger(2))
    }

    @Test
    fun `table sort reports invalid comparator order for partitioned ranges`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {1, 2, 3, 4}
                return pcall(table.sort, values, function() return true end)
                """.trimIndent(),
                "table-sort-invalid-order.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("invalid order function for sorting", state.toString(2))
    }

    @Test
    fun `table sort reports invalid comparator order for table-like values`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = {1, 2, 3, 4}
                debug.setmetatable("proxy", {
                    __len = function(value)
                        return value == "proxy" and #values or 0
                    end,
                    __index = function(value, index)
                        return value == "proxy" and values[index] or nil
                    end,
                    __newindex = function(value, index, sorted)
                        if value == "proxy" then
                            values[index] = sorted
                        end
                    end,
                })
                return pcall(table.sort, "proxy", function() return true end)
                """.trimIndent(),
                "table-sort-table-like-invalid-order.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("invalid order function for sorting", state.toString(2))
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
                local fallback = setmetatable({}, {
                    __len = function()
                        return 3
                    end,
                    __index = function(_, index)
                        return ({ "meta-first", nil, "meta-third" })[index]
                    end,
                })
                local metaCount = select("#", table.unpack(fallback, 1, 3))
                local metaFirst, metaSecond, metaThird = table.unpack(fallback, 1, 3)
                local defaultMetaCount = select("#", table.unpack(fallback))
                local nested = setmetatable({}, {
                    __index = setmetatable({}, {
                        __index = {
                            [1] = "nested-first",
                            [3] = "nested-third",
                        },
                    }),
                })
                local nestedCount = select("#", table.unpack(nested, 1, 3))
                local nestedFirst, nestedSecond, nestedThird = table.unpack(nested, 1, 3)
                return a, b, c, d, e, count, first, second, third,
                    metaCount, metaFirst, metaSecond, metaThird, defaultMetaCount,
                    nestedCount, nestedFirst, nestedSecond, nestedThird
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
        assertEquals(3L, state.toInteger(10))
        assertEquals("meta-first", state.toString(11))
        assertTrue(state.isNil(12))
        assertEquals("meta-third", state.toString(13))
        assertEquals(3L, state.toInteger(14))
        assertEquals(3L, state.toInteger(15))
        assertEquals("nested-first", state.toString(16))
        assertTrue(state.isNil(17))
        assertEquals("nested-third", state.toString(18))
    }

    @Test
    fun `table unpack accepts table-like non-table values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)
        LuaStdlib.openDebug(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable("proxy", {
                    __len = function(value)
                        return value == "proxy" and 3 or 0
                    end,
                    __index = function(value, index)
                        return value == "proxy" and ({ "a", nil, "c" })[index] or nil
                    end,
                })
                local defaultCount = select("#", table.unpack("proxy"))
                local first, second, third = table.unpack("proxy")

                debug.setmetatable(42, {
                    __index = function(_, index)
                        return ({ "explicit-a", "explicit-b" })[index]
                    end,
                })
                local explicitCount = select("#", table.unpack(42, 1, 2))
                local explicitFirst, explicitSecond = table.unpack(42, 1, 2)
                return defaultCount, first, second, third, explicitCount, explicitFirst, explicitSecond
                """.trimIndent(),
                "table-unpack-table-like.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(3L, state.toInteger(1))
        assertEquals("a", state.toString(2))
        assertTrue(state.isNil(3))
        assertEquals("c", state.toString(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals("explicit-a", state.toString(6))
        assertEquals("explicit-b", state.toString(7))
    }

    @Test
    fun `table unpack indexes strings through string metatable`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)
        LuaStdlib.openTable(state)
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local raw = string.char(255, 65)
                local wide = utf8.char(128512)
                local count = select("#", table.unpack("abc"))
                local first, second, third = table.unpack("abc")
                local rawCount = select("#", table.unpack(raw))
                local wideCount = select("#", table.unpack(wide))
                return count, first, second, third, rawCount, wideCount
                """.trimIndent(),
                "table-unpack-string-metatable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(3L, state.toInteger(1))
        assertTrue(state.isNil(2))
        assertTrue(state.isNil(3))
        assertTrue(state.isNil(4))
        assertEquals(2L, state.toInteger(5))
        assertEquals(4L, state.toInteger(6))
    }

    @Test
    fun `table unpack reports non integer length metamethod results`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = setmetatable({}, {
                    __len = function()
                        return 1.5
                    end,
                })
                return table.unpack(values)
                """.trimIndent(),
                "table-unpack-length-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("object length is not an integer", state.toString(-1))
    }

    @Test
    fun `table unpack reports too many result errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = setmetatable({}, {
                    __len = function()
                        return 2147483647
                    end,
                })
                return table.unpack(values)
                """.trimIndent(),
                "table-unpack-too-many-results.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("too many results to unpack", state.toString(-1))
    }

    @Test
    fun `table unpack returns no values for empty ranges`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local tableCount = select("#", table.unpack({"a", "b"}, 3, 2))
                local numberCount = select("#", table.unpack(42, 3, 2))
                return tableCount, numberCount
                """.trimIndent(),
                "table-unpack-empty.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0L, state.toInteger(1))
        assertEquals(0L, state.toInteger(2))
    }

    @Test
    fun `table unpack uses primitive length metamethod before indexing`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)
        LuaStdlib.openDebug(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                debug.setmetatable(0, {
                    __len = function()
                        return 0
                    end,
                })
                local emptyOk, emptyCount = pcall(function()
                    return select("#", table.unpack(42))
                end)

                debug.setmetatable(0, {
                    __len = function()
                        return 1
                    end,
                })
                local indexOk, indexMessage = pcall(table.unpack, 42)
                debug.setmetatable(0, nil)
                return emptyOk, emptyCount, indexOk, indexMessage
                """.trimIndent(),
                "table-unpack-primitive-length.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals(0L, state.toInteger(2))
        assertFalse(state.toBoolean(3))
        assertEquals("attempt to index a number value", state.toString(4))
    }

    @Test
    fun `table unpack follows nested index metamethod tables`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local values = setmetatable({}, {
                    __len = function()
                        return 2
                    end,
                    __index = setmetatable({}, {
                        __index = {
                            [1] = "a",
                            [2] = "b",
                        },
                    }),
                })
                return table.unpack(values)
                """.trimIndent(),
                "table-unpack-nested-index-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a", state.toString(1))
        assertEquals("b", state.toString(2))
    }

    @Test
    fun `table unpack reports bad index metamethod chains`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numberValues = setmetatable({}, {
                    __len = function() return 1 end,
                    __index = 1,
                })
                local numberOk, numberMessage = pcall(table.unpack, numberValues)

                local booleanValues = setmetatable({}, {
                    __len = function() return 1 end,
                    __index = true,
                })
                local booleanOk, booleanMessage = pcall(table.unpack, booleanValues)

                local loopValues = {}
                setmetatable(loopValues, {
                    __len = function() return 1 end,
                    __index = loopValues,
                })
                local loopOk, loopMessage = pcall(table.unpack, loopValues)

                local stringValues = setmetatable({}, {
                    __len = function() return 1 end,
                    __index = "x",
                })
                local stringOk, stringValue = pcall(table.unpack, stringValues)

                return numberOk, numberMessage, booleanOk, booleanMessage,
                    loopOk, loopMessage, stringOk, stringValue
                """.trimIndent(),
                "table-unpack-bad-index-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("attempt to index a number value", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("attempt to index a boolean value", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("'__index' chain too long; possible loop", state.toString(6))
        assertTrue(state.toBoolean(7))
        assertTrue(state.isNil(8))
    }

    @Test
    fun `table unpack reports range argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local okStartString, startStringMessage = pcall(table.unpack, {"a"}, "x", 1)
                local okStartFraction, startFractionMessage = pcall(table.unpack, {"a"}, 1.5, 1)
                local okEndFraction, endFractionMessage = pcall(table.unpack, {"a"}, 1, "1.5")
                return okStartString, startStringMessage,
                    okStartFraction, startFractionMessage,
                    okEndFraction, endFractionMessage
                """.trimIndent(),
                "table-unpack-range-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'unpack' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'unpack' (number has no integer representation)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #3 to 'unpack' (number has no integer representation)", state.toString(6))
    }

    @Test
    fun `table unpack reports table argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.unpack(42)""", "table-unpack-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("attempt to get length of a number value", state.toString(-1))

        val indexState = LuaState.create()
        LuaStdlib.openTable(indexState)

        assertEquals(
            LuaStatus.OK,
            indexState.load("""return table.unpack(42, 1, 1)""", "table-unpack-index-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, indexState.pcall(0, -1))

        assertIs<LuaRuntimeException>(indexState.getLastError())
        assertEquals("attempt to index a number value", indexState.toString(-1))

        val stringState = LuaState.create()
        LuaStdlib.openTable(stringState)

        assertEquals(
            LuaStatus.OK,
            stringState.load("""return table.unpack("abc")""", "table-unpack-string-index-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, stringState.pcall(0, -1))

        assertIs<LuaRuntimeException>(stringState.getLastError())
        assertEquals("attempt to index a string value", stringState.toString(-1))
    }
}

private data class DebugHostObject(
    val name: String,
)

private fun Path.luaPath(): String = toString().replace("\\", "\\\\")

private fun withStandardInput(source: String, block: () -> Unit) {
    synchronized(System::class.java) {
        val previous = System.`in`
        try {
            System.setIn(ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)))
            block()
        } finally {
            System.setIn(previous)
        }
    }
}
