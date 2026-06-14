package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaConfig
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaStatus
import io.github.realmlabs.klua.api.LuaYieldException
import io.github.realmlabs.klua.api.LuaYieldableFunction
import io.github.realmlabs.klua.api.withContinuation
import java.nio.file.Files
import java.nio.file.Path
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
                    type(info), info.what, info.source, info.currentline
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
        assertEquals("Lua", state.toString(19))
        assertEquals("debug-openlibs.lua", state.toString(20))
        assertEquals(1L, state.toInteger(21))
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
                "\tdebug-traceback.lua:2\n" +
                "\tdebug-traceback.lua:6\n" +
                "\tdebug-traceback.lua:9",
            state.toString(1),
        )
    }

    @Test
    fun `debug traceback reports non numeric level errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(debug.traceback, "boom", "not-level")
                return ok, message
                """.trimIndent(),
                "debug-traceback-level-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'traceback' (number expected)", state.toString(2))
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
    fun `debug traceback converts number messages and preserves other non strings`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local marker = {name = "marker"}
                local returnedTable = debug.traceback(marker)
                local returnedNumber = debug.traceback(42)
                local returnedBoolean = debug.traceback(false)
                return returnedTable == marker,
                    returnedTable.name,
                    returnedNumber,
                    type(returnedNumber),
                    returnedBoolean,
                    type(returnedBoolean)
                """.trimIndent(),
                "debug-traceback-non-string.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("marker", state.toString(2))
        assertEquals(
            "42\n" +
                "stack traceback:\n" +
                "\tdebug-traceback-non-string.lua:3",
            state.toString(3),
        )
        assertEquals("string", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("boolean", state.toString(6))
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
        assertEquals("debug-getinfo.lua", state.toString(2))
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
        assertEquals("debug-getinfo-function.lua", state.toString(2))
        assertEquals(-1L, state.toInteger(3))
        assertEquals("Lua", state.toString(4))
        assertEquals(1L, state.toInteger(5))
        assertEquals(4L, state.toInteger(6))
        assertEquals("", state.toString(7))
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

        assertEquals("[Java]", state.toString(1))
        assertEquals("[Java]", state.toString(2))
        assertEquals(-1L, state.toInteger(3))
        assertEquals("Java", state.toString(4))
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
                    return frameLine.currentline, frameLine.source,
                        frameSource.currentline, frameSource.source
                end

                local functionInfo = debug.getinfo(probe, "S")
                local functionLine = debug.getinfo(probe, "l")
                local functionValue = debug.getinfo(probe, "f")
                local hostFunction = debug.getinfo(print, "fS")
                local frameCurrentline, frameLineSource, frameSourceCurrentline, frameSource = probe()
                return functionInfo.source, functionInfo.currentline,
                    functionLine.currentline, functionLine.source,
                    functionValue.func == probe, functionValue.source,
                    hostFunction.func == print, hostFunction.what, hostFunction.currentline,
                    frameCurrentline, frameLineSource, frameSourceCurrentline, frameSource
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
        assertEquals("Java", state.toString(8))
        assertTrue(state.isNil(9))
        assertEquals(2L, state.toInteger(10))
        assertTrue(state.isNil(11))
        assertTrue(state.isNil(12))
        assertEquals("debug-getinfo-what.lua", state.toString(13))
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
                local ok, message = pcall(debug.getinfo, "not-level")
                return ok, message
                """.trimIndent(),
                "debug-getinfo-level-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'getinfo' (number expected)", state.toString(2))
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
                local ok, message = pcall(debug.getlocal, "not-level", 1)
                return ok, message
                """.trimIndent(),
                "debug-getlocal-level-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'getlocal' (number expected)", state.toString(2))
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
                    return okStack, stackMessage, okLevel, levelMessage, okFunction, functionMessage
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

                local fixedFirst = debug.getlocal(fixed, 1)
                local fixedSecond = debug.getlocal(fixed, 2)
                local fixedMissing = debug.getlocal(fixed, 3)
                local fixedInvalid = debug.getlocal(fixed, 0)
                local varargFirst = debug.getlocal(vararg, 1)
                local varargSecond = debug.getlocal(vararg, 2)
                local hostMissing = debug.getlocal(print, 1)
                return fixedFirst, fixedSecond, fixedMissing, fixedInvalid,
                    varargFirst, varargSecond, hostMissing
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
        assertTrue(state.isNil(7))
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
    fun `debug setlocal reports non numeric stack level errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local ok, message = pcall(debug.setlocal, "not-level", 1, "ignored")
                return ok, message
                """.trimIndent(),
                "debug-setlocal-level-type-error.lua",
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
                    local ok, message = pcall(debug.setlocal, 1, "not-index", "ignored")
                    return ok, message
                end

                return probe()
                """.trimIndent(),
                "debug-setlocal-index-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'setlocal' (number expected)", state.toString(2))
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
                local missing = debug.getupvalue(capture, 99)
                return n1, v1, n2, v2, missing
                """.trimIndent(),
                "debug-getupvalue.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("secret", state.toString(1))
        assertEquals("ok", state.toString(2))
        assertEquals("count", state.toString(3))
        assertEquals(42L, state.toInteger(4))
        assertTrue(state.isNil(5))
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
                local okJoinOtherFunction, joinOtherFunctionMessage = pcall(debug.upvaluejoin, left, 1, "not-function", 1)
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
                local missing = debug.setupvalue(capture, 99, "ignored")
                local v1, v2 = capture()
                return n1, n2, missing, v1, v2
                """.trimIndent(),
                "debug-setupvalue.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("secret", state.toString(1))
        assertEquals("count", state.toString(2))
        assertTrue(state.isNil(3))
        assertEquals("new", state.toString(4))
        assertEquals(99L, state.toInteger(5))
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
                return okMissing, missingMessage,
                    okMissingType, missingTypeMessage,
                    okInvalidIndex, invalidIndexResult,
                    capture()
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
        assertEquals("old", state.toString(7))
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
                debug.sethook()
                return #none,
                    type(countHook), countMask, countInterval,
                    type(eventHook), eventMask, eventInterval
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
            state.load("""return debug.sethook(function() end, "", "not-count")""", "debug-sethook-count-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #3 to 'sethook' (number expected)", state.toString(-1))
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

                local first, firstExtra = require("demo")
                local second, secondExtra = require("demo")
                return first.name, first.calls, firstExtra, first == second,
                    package.loaded.demo == first, secondExtra == nil
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
        assertTrue(state.toBoolean(6))
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
        assertTrue(message.contains("module 'missing' not found"))
        assertTrue(message.contains("no field package.preload['missing']"))
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
                        return "\n\tkept first"
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
        assertTrue(message.contains("kept first"))
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
    fun `require reports C searcher diagnostics and cpath type errors`() {
        val root = Files.createTempDirectory("klua-require-cpath")
        val nativeModule = root.resolve("native.so")
        Files.writeString(nativeModule, "")

        val state = LuaState.create()
        LuaStdlib.openBase(state)
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
        assertTrue(rootMessage.contains("module 'native.child' not found"), rootMessage)
        assertTrue(rootMessage.contains("no file '${root}/native/child.so'"), rootMessage)
        assertTrue(rootMessage.contains("no module 'native.child' in file '${root}/native.so'"), rootMessage)
        assertFalse(state.toBoolean(5))
        assertEquals(
            "error loading module 'native' from file '${root}/native.so':\n\t" +
                "dynamic libraries not enabled; check your Lua installation",
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
                local first, firstExtra = require("alpha.beta")
                local second, secondExtra = require("alpha.beta")
                return first.name, first.filename, firstExtra,
                    first == second, package.loaded["alpha.beta"] == first, secondExtra == nil
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
        assertTrue(state.toBoolean(6))
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
        assertEquals("assert-false.lua:1: nope", state.toString(-1))
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
        assertEquals("<no error object>", state.toString(9))
    }

    @Test
    fun `error raises runtime error with message`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return error("boom", nil)""", "error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("error.lua:1: boom", state.toString(-1))
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
        assertEquals("error-levels.lua:8: boom", state.toString(4))
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
        assertEquals("<no error object>", state.toString(5))
        assertFalse(state.toBoolean(6))
        assertEquals("<no error object>", state.toString(7))
        assertFalse(state.toBoolean(8))
        assertTrue(state.toBoolean(9))
    }

    @Test
    fun `error reports non numeric level errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return error("boom", false)""", "error-level-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'error' (number expected)", state.toString(-1))

        val fractionalState = LuaState.create()
        LuaStdlib.openBase(fractionalState)

        assertEquals(
            LuaStatus.OK,
            fractionalState.load("""return error("boom", 1.5)""", "error-level-fractional-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, fractionalState.pcall(0, -1))

        assertIs<LuaRuntimeException>(fractionalState.getLastError())
        assertEquals(
            "bad argument #2 to 'error' (number has no integer representation)",
            fractionalState.toString(-1),
        )
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
        assertEquals("pcall.lua:5: boom", state.toString(5))
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
        assertEquals("pcall-yield-error.lua:4: boom", state.toString(6))
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
        assertEquals("handled:xpcall.lua:7: boom", state.toString(4))
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
        assertEquals("handled:xpcall-yield-error.lua:4: boom", state.toString(6))
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
        assertEquals("handling:xpcall-handler-yield.lua:3: boom", state.toString(2))
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
                local prefixedCount = select("#extra", "a", "b", "c")
                local second, third = select(2, "a", "b", "c")
                local last = select(-1, "a", "b", "c")
                return count, prefixedCount, second, third, last
                """.trimIndent(),
                "select.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(3L, state.toInteger(1))
        assertEquals(3L, state.toInteger(2))
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

        assertEquals(LuaStatus.OK, state.load("""return select("", "a")""", "select-string-index.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'select' (number expected)", state.toString(-1))
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
    fun `select reports fractional number index errors`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return select(1.5, "a")""", "select-fractional-index.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'select' (number has no integer representation)", state.toString(-1))
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
                local integerOk, integerMessage = pcall(collectgarbage, "step", 1.5)
                return nilStep, sizedStep, countType, ok, message, integerOk, integerMessage
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
                local pause = collectgarbage("param", "pause")
                local previous = collectgarbage("param", "pause", 300)
                local updated = collectgarbage("param", "pause")
                local query = collectgarbage("param", "pause", nil)
                local unchanged = collectgarbage("param", "pause", -1)
                return pause, previous, updated, query, unchanged, collectgarbage("param", "stepmul")
                """.trimIndent(),
                "collectgarbage-param.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(250L, state.toInteger(1))
        assertEquals(250L, state.toInteger(2))
        assertEquals(300L, state.toInteger(3))
        assertEquals(300L, state.toInteger(4))
        assertEquals(300L, state.toInteger(5))
        assertEquals(200L, state.toInteger(6))
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
            valueState.load("""return collectgarbage("param", "pause", false)""", "collectgarbage-param-value.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, valueState.pcall(0, -1))

        assertIs<LuaRuntimeException>(valueState.getLastError())
        assertEquals("bad argument #3 to 'collectgarbage' (number expected)", valueState.toString(-1))

        val integerState = LuaState.create()
        LuaStdlib.openBase(integerState)

        assertEquals(
            LuaStatus.OK,
            integerState.load("""return collectgarbage("param", "pause", 1.5)""", "collectgarbage-param-integer.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, integerState.pcall(0, -1))

        assertIs<LuaRuntimeException>(integerState.getLastError())
        assertEquals(
            "bad argument #3 to 'collectgarbage' (number has no integer representation)",
            integerState.toString(-1),
        )
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
                value = 5
                local env = {value = 40}
                local chunk = load("local add = ...; value = value + add; return value", "load-env.lua", "t", env)
                return chunk(2), env.value, value
                """.trimIndent(),
                "load-env-driver.lua",
            ),
        )
        val status = state.pcall(0, -1)
        assertEquals(LuaStatus.OK, status, state.toString(-1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(42L, state.toInteger(2))
        assertEquals(5L, state.toInteger(3))
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
            Files.writeString(file, "local add = ...; value = value + add; return value")
            val state = LuaState.create()
            LuaStdlib.openBase(state)

            assertEquals(
                LuaStatus.OK,
                state.load(
                    """
                    value = 5
                    local env = {value = 40}
                    local chunk = loadfile("${file.luaPath()}", "t", env)
                    return chunk(2), env.value, value
                    """.trimIndent(),
                    "loadfile-env.lua",
                ),
            )
            val status = state.pcall(0, -1)
            assertEquals(LuaStatus.OK, status, state.toString(-1))

            assertEquals(42L, state.toInteger(1))
            assertEquals(42L, state.toInteger(2))
            assertEquals(5L, state.toInteger(3))
        } finally {
            Files.deleteIfExists(file)
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
                return okNil, nilMessage, okString, stringMessage, okFractional, fractionalMessage
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
                return rawequal(nil, nil),
                    rawequal(1, 1.0),
                    rawequal("x", "x"),
                    rawequal(false, false),
                    rawequal(1, "1"),
                    rawequal(tableValue, tableValue),
                    rawequal({}, {}),
                    rawequal(functionValue, functionValue),
                    rawequal(functionValue, otherFunction),
                    rawequal(threadValue, threadValue),
                    rawequal(threadValue, otherThread),
                    rawequal(equalByMetamethod, {})
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
        assertTrue(state.toBoolean(8))
        assertFalse(state.toBoolean(9))
        assertTrue(state.toBoolean(10))
        assertFalse(state.toBoolean(11))
        assertFalse(state.toBoolean(12))
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
                local okMeta, metaMessage = pcall(debug.setmetatable, {}, "not-table")
                return type(metatable),
                    metatable.__index == string,
                    okTable,
                    returned,
                    raw == replacement,
                    custom,
                    len,
                    okMeta,
                    metaMessage
                """.trimIndent(),
                "debug-metatable-errors.lua",
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
        assertFalse(state.toBoolean(8))
        assertEquals("bad argument #2 to 'setmetatable' (nil or table expected)", state.toString(9))
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
                    debug.setuservalue(host, "ignored", 2)
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
        assertTrue(state.isNil(8))
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
        assertEquals("coroutine-error.lua:2: boom", state.toString(2))
        assertEquals("dead", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("coroutine-error.lua:2: boom", state.toString(5))
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

                local failing = coroutine.wrap(function()
                    error("boom")
                end)
                local failOk, failMessage = pcall(failing)
                local marker = {name = "marker"}
                local failingObject = coroutine.wrap(function()
                    error(marker)
                end)
                local objectFailOk, objectError = pcall(failingObject)
                return sum, samePayload, payloadName, resumeValue,
                    deadOk, deadMessage, failOk, failMessage,
                    objectFailOk, objectError == marker, objectError.name
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
        assertEquals("cannot resume dead coroutine", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("coroutine-wrap.lua:11: boom", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertTrue(state.toBoolean(10))
        assertEquals("marker", state.toString(11))
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
        assertEquals("<no error object>", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("<no error object>", state.toString(4))
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
        assertEquals("attempt to yield across a non-yieldable boundary", state.toString(2))
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

                return firstOk, yielded, beforeClose, closeOk, afterClose,
                    resumeOk, resumeMessage, deadResumeOk, deadBeforeClose,
                    deadCloseOk, deadAfterClose
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
        assertTrue(state.toBoolean(7))
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
        assertTrue(state.toBoolean(4))
        assertEquals("dead", state.toString(5))
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
                    math.deg(math.pi), math.rad(180)
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

        assertEquals(LuaStatus.OK, ldexpState.load("""return math.ldexp(1, "x")""", "math-ldexp-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, ldexpState.pcall(0, -1))

        assertIs<LuaRuntimeException>(ldexpState.getLastError())
        assertEquals("bad argument #2 to 'ldexp' (number expected)", ldexpState.toString(-1))

        val fractionalState = LuaState.create()
        LuaStdlib.openMath(fractionalState)

        assertEquals(LuaStatus.OK, fractionalState.load("""return math.ldexp(1, 1.5)""", "math-ldexp-fractional-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, fractionalState.pcall(0, -1))

        assertIs<LuaRuntimeException>(fractionalState.getLastError())
        assertEquals(
            "bad argument #2 to 'ldexp' (number has no integer representation)",
            fractionalState.toString(-1),
        )
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
        assertTrue(state.toBoolean(8))
        assertFalse(state.toBoolean(9))
        assertFalse(state.toBoolean(10))
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
    fun `math min and max use lua comparison and return selected value`() {
        val state = LuaState.create()
        LuaStdlib.openMath(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local tableValue = {}
                local minTable = math.min(tableValue)
                local maxBoolean = math.max(true)
                local minString = math.min("3", "4")
                local maxString = math.max("3", "4")
                return minTable == tableValue,
                    maxBoolean,
                    minString, math.type(minString),
                    maxString, math.type(maxString)
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
                return firstSeed, secondSeed, first, first == repeated,
                    generatedFirstSeed ~= nil, generatedSecondSeed ~= 0, generated >= 1 and generated <= 100,
                    singleFirstSeed, singleSecondSeed
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
                return upperOnly, wideRange, fullRange,
                    upperOnly >= 1 and upperOnly <= 3000000000,
                    wideRange >= -3000000000 and wideRange <= 3000000000,
                    fullRange ~= nil,
                    upperOnly == repeated
                """.trimIndent(),
                "math-random-wide.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(676063042L, state.toInteger(1))
        assertEquals(2846524133L, state.toInteger(2))
        assertEquals(5533629760186076056L, state.toInteger(3))
        for (index in 4..7) {
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
                return string.len("hello"), string.len("é"), string.lower("HeLLo"), string.upper("HeLLo"),
                    string.reverse("abc"), string.rep("ha", 3), string.sub("abcdef", 2, 4),
                    string.sub("abcdef", -3, -1), string.sub("abcdef", 4, 2),
                    string.sub("éx", 1, 2), string.sub("éx", 3, 3)
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
    fun `string rep reports large result errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.rep("x", 2147483648)""", "string-rep-large-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("resulting string too large", state.toString(-1))
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
                local utf8First, utf8Second = string.byte("é", 1, -1)
                local utf8Last = string.byte("é", -1)
                local utf8Char = string.char(195, 169)
                local charFirst, charSecond = string.byte(utf8Char, 1, -1)
                local empty = string.char()
                return first, second, third, last, utf8First, utf8Second, utf8Last,
                    string.char(65, 66, 67), utf8Char, charFirst, charSecond, empty
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
                return plainStart, plainEnd, patternStart, patternEnd, matched, iterator()
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
    fun `string pattern classes use lua ascii locale semantics`() {
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
        assertEquals("é", state.toString(9))
        assertEquals("é", state.toString(10))
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
                local utf8Replaced = string.gsub("éx", "()x", function(position)
                    return "[" .. position .. "]"
                end)
                local utf8Expanded = string.gsub("éx", "()x", "%1")
                return first, last, before, after, matchBefore, matchAfter,
                    firstWord, secondWord, replaced, count, expanded, expandedCount,
                    utf8Before, fromSecondByte, utf8Replaced, utf8Expanded
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
        assertEquals("é[3]", state.toString(15))
        assertEquals("é3", state.toString(16))
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
                """return string.format("%5s|%-5s|%.2s|%5.2s", "ab", "ab", "abcd", "abcd")""",
                "string-format-string-modifiers.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("   ab|ab   |ab|   ab", state.toString(1))
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
    fun `string format string conversion uses table tostring metamethods`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local value = setmetatable({name = "formatted"}, {
                    __tostring = function(target)
                        return "table:" .. target.name
                    end,
                })
                local formatted = string.format("%s", value)
                local ok, message = pcall(string.format, "%q", value)
                return formatted, ok, message
                """.trimIndent(),
                "string-format-tostring-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("table:formatted", state.toString(1))
        assertFalse(state.toBoolean(2))
        assertEquals("bad argument #2 to 'format' (value has no literal form)", state.toString(3))
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
        assertEquals(
            "0x1.8p+0|0x1p-1|0x0p+0|-0x0p+0|1e9999|-1e9999|(0/0)|0x8000000000000000",
            state.toString(6),
        )
    }

    @Test
    fun `string format applies width to character conversions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.format("%3c|%-3c|%c|%c", 65, 66, 256, -1)""",
                "string-format-char-width.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("  A|B  |\u0000|\u00FF", state.toString(1))
    }

    @Test
    fun `string format renders hexadecimal float conversions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.format("%a|%A|%.2a|%12a|%012a|%+12a|%.0a|%#.0a", 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5)""",
                "string-format-hex-float.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("0x1.8p+0|0X1.8P+0|0x1.80p+0|    0x1.8p+0|0x00001.8p+0|   +0x1.8p+0|0x1p+0|0x1.p+0", state.toString(1))
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
    fun `string format reports argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.format("%d", "bad")""", "string-format-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'format' (number expected)", state.toString(-1))

        val fractionalState = LuaState.create()
        LuaStdlib.openString(fractionalState)

        assertEquals(
            LuaStatus.OK,
            fractionalState.load("""return string.format("%d", 1.5)""", "string-format-integer-fractional-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, fractionalState.pcall(0, -1))

        assertIs<LuaRuntimeException>(fractionalState.getLastError())
        assertEquals(
            "bad argument #2 to 'format' (number has no integer representation)",
            fractionalState.toString(-1),
        )
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
    }

    @Test
    fun `string format reports char argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.format("%c", "bad")""", "string-format-char-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'format' (number expected)", state.toString(-1))

        val fractionalState = LuaState.create()
        LuaStdlib.openString(fractionalState)

        assertEquals(LuaStatus.OK, fractionalState.load("""return string.format("%c", 1.5)""", "string-format-char-fractional-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, fractionalState.pcall(0, -1))

        assertIs<LuaRuntimeException>(fractionalState.getLastError())
        assertEquals(
            "bad argument #2 to 'format' (number has no integer representation)",
            fractionalState.toString(-1),
        )
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
        assertEquals("bad argument #1 to 'string.packsize' (variable-length format)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("integral size (0) out of limits [1,16]", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("integral size (17) out of limits [1,16]", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals(
            "bad argument #1 to 'string.packsize' (format asks for alignment not power of 2)",
            state.toString(8),
        )
        assertFalse(state.toBoolean(9))
        assertEquals("invalid format option '?'", state.toString(10))
        assertFalse(state.toBoolean(11))
        assertEquals("missing size for format option 'c'", state.toString(12))
        assertFalse(state.toBoolean(13))
        assertEquals(
            "bad argument #1 to 'string.packsize' (invalid next option for option 'X')",
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
    fun `string gsub supports empty patterns`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local replaced, count = string.gsub("abc", "", "-")
                local empty, emptyCount = string.gsub("", "", "-")
                local limited, limitedCount = string.gsub("abc", "", "-", 2)
                return replaced, count, empty, emptyCount, limited, limitedCount
                """.trimIndent(),
                "string-gsub-empty-pattern.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("-a-b-c-", state.toString(1))
        assertEquals(4L, state.toInteger(2))
        assertEquals("-", state.toString(3))
        assertEquals(1L, state.toInteger(4))
        assertEquals("-a-bc", state.toString(5))
        assertEquals(2L, state.toInteger(6))
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
    fun `string gsub reports invalid function and table replacement values`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local function functionReplacement(value)
                    local ok, message = pcall(function()
                        return string.gsub("a", "a", function()
                            return value
                        end)
                    end)
                    return ok, message
                end
                local function tableReplacement(value)
                    local ok, message = pcall(function()
                        return string.gsub("a", "a", { a = value })
                    end)
                    return ok, message
                end
                local okTableFunction, tableFunction = functionReplacement({})
                local okNestedFunction, nestedFunction = functionReplacement(function() end)
                local okBooleanFunction, booleanFunction = functionReplacement(true)
                local okTableLookup, tableLookup = tableReplacement({})
                return okTableFunction, tableFunction, okNestedFunction, nestedFunction,
                    okBooleanFunction, booleanFunction, okTableLookup, tableLookup
                """.trimIndent(),
                "string-gsub-invalid-replacement-values.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("invalid replacement value (a table)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("invalid replacement value (a function)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("invalid replacement value (a boolean)", state.toString(6))
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

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator = string.gmatch("ab", "")
                local empty = string.gmatch("", "")
                return iterator(), iterator(), iterator(), iterator(),
                    empty(), empty()
                """.trimIndent(),
                "string-gmatch-empty-pattern.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("", state.toString(1))
        assertEquals("", state.toString(2))
        assertEquals("", state.toString(3))
        assertTrue(state.isNil(4))
        assertEquals("", state.toString(5))
        assertTrue(state.isNil(6))
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
        LuaStdlib.openUtf8(state)

        assertEquals(LuaStatus.OK, state.load("""return utf8.codepoint("abc", 4, 3)""", "utf8-codepoint-empty.lua"))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0, state.getTop())
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
    fun `utf8 codes returns codepoint iterator`() {
        val state = LuaState.create()
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
                local nilControlIndex, nilControlCode = iterator(stateValue, nil)
                local badControlIndex, badControlCode = iterator(stateValue, "bad")
                return firstIndex, firstCode, firstAgainIndex, firstAgainCode,
                    secondIndex, secondCode, thirdIndex, thirdCode, done,
                    nilControlIndex, nilControlCode, badControlIndex, badControlCode
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
        assertEquals(1L, state.toInteger(10))
        assertEquals(65L, state.toInteger(11))
        assertEquals(1L, state.toInteger(12))
        assertEquals(65L, state.toInteger(13))
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
                """return utf8.len("abc", 4), utf8.len("abc", 3, 2), utf8.len("", 1, 0)""",
                "utf8-len-empty-ranges.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0L, state.toInteger(1))
        assertEquals(0L, state.toInteger(2))
        assertEquals(0L, state.toInteger(3))
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
                    utf8.len(text, 3, 2)
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
    }

    @Test
    fun `utf8 len reports invalid byte ranges`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local text = "A" .. utf8.char(128512) .. "Z"
                local continuationLength, continuationPosition = utf8.len(text, 4, 6)
                local truncatedLength, truncatedPosition = utf8.len(text, 1, 4)
                return continuationLength, continuationPosition, truncatedLength, truncatedPosition
                """.trimIndent(),
                "utf8-len-invalid-ranges.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertEquals(4L, state.toInteger(2))
        assertTrue(state.isNil(3))
        assertEquals(2L, state.toInteger(4))
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
        assertEquals("bad argument #3 to 'offset' (initial position is a continuation byte)", state.toString(-1))
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
    fun `utf8 char rejects invalid code points`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(LuaStatus.OK, state.load("""return utf8.char(55296)""", "utf8-char-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'char' (value out of range)", state.toString(-1))
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
    fun `string functions report argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.rep("x", "bad")""", "string-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'rep' (number expected)", state.toString(-1))
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
                local ok, message = pcall(table.concat, unicode, ",")
                return table.concat(ascii, ","), table.concat(hex, ","), table.concat(wrapped, ","), ok, message
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
                return table.concat(values, ",", 1, 2)
                """.trimIndent(),
                "table-concat-index-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a,b", state.toString(1))
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
                return missingOk, missingMessage, extraOk, extraMessage, boundsOk, boundsMessage
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
                local fallback = setmetatable({}, {
                    __len = function() return 2 end,
                    __index = function(_, index)
                        return ({ "meta-a", "meta-b" })[index]
                    end,
                })
                local fallbackRemoved = table.remove(fallback)
                return middle, last, trailing, emptyRemoved, values[1], values[2], values[3], #values,
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
        assertEquals("a", state.toString(5))
        assertEquals("c", state.toString(6))
        assertTrue(state.isNil(7))
        assertEquals(2L, state.toInteger(8))
        assertEquals("meta-b", state.toString(9))
        assertTrue(state.isNil(10))
        assertTrue(state.isNil(11))
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
                return removed, #writes, writes[1], writes[2], rawget(values, 1), rawget(values, 2)
                """.trimIndent(),
                "table-remove-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a", state.toString(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals("1:b", state.toString(3))
        assertEquals("2:nil", state.toString(4))
        assertTrue(state.isNil(5))
        assertTrue(state.isNil(6))
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
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.remove({"a"}, 3)""", "table-remove-position-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'remove' (position out of bounds)", state.toString(-1))
    }

    @Test
    fun `table sort orders numeric and string lists`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local numbers = {3, 1, 2}
                local names = {"beta", "alpha", "gamma"}
                local fallback = setmetatable({}, {
                    __len = function() return 3 end,
                    __index = function(_, index)
                        return ({ 3, 1, 2 })[index]
                    end,
                })
                table.sort(numbers)
                table.sort(names)
                table.sort(fallback)
                return numbers[1], numbers[2], numbers[3], names[1], names[2], names[3],
                    rawget(fallback, 1), rawget(fallback, 2), rawget(fallback, 3)
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
                return #writes, writes[1], writes[2], writes[3], rawget(values, 1), rawget(values, 2), rawget(values, 3)
                """.trimIndent(),
                "table-sort-newindex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals(3L, state.toInteger(1))
        assertEquals("1:1", state.toString(2))
        assertEquals("2:2", state.toString(3))
        assertEquals("3:3", state.toString(4))
        assertTrue(state.isNil(5))
        assertTrue(state.isNil(6))
        assertTrue(state.isNil(7))
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
        assertEquals(2L, state.toInteger(2))
        assertEquals(3L, state.toInteger(3))
        assertTrue(state.isNil(4))
        assertTrue(state.isNil(5))
        assertTrue(state.isNil(6))
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
                local stringCount = select("#", table.unpack("abc"))
                local stringFirst, stringSecond, stringThird = table.unpack("abc")
                local stringExplicitCount = select("#", table.unpack("abc", 2, 4))
                return a, b, c, d, e, count, first, second, third,
                    metaCount, metaFirst, metaSecond, metaThird, defaultMetaCount,
                    stringCount, stringFirst, stringSecond, stringThird, stringExplicitCount
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
        assertTrue(state.isNil(16))
        assertTrue(state.isNil(17))
        assertTrue(state.isNil(18))
        assertEquals(3L, state.toInteger(19))
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
            state.load("""return select("#", table.unpack({"a", "b"}, 3, 2))""", "table-unpack-empty.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(0L, state.toInteger(1))
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
    }
}

private data class DebugHostObject(
    val name: String,
)

private fun Path.luaPath(): String = toString().replace("\\", "\\\\")
