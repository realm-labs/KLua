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
                    type(package.loaded), type(package.preload), type(package.searchers), type(require)
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
                    type(debug.upvalueid), type(debug.upvaluejoin), type(debug.getmetatable),
                    type(debug.setmetatable), type(debug.getregistry),
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
        assertTrue(state.toString(15)?.contains("boom\nstack traceback:") == true)
        assertEquals("table", state.toString(16))
        assertEquals("Lua", state.toString(17))
        assertEquals("debug-openlibs.lua", state.toString(18))
        assertEquals(1L, state.toInteger(19))
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
        assertEquals("bad argument #2 to 'debug.traceback' (number expected)", state.toString(2))
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
    fun `debug traceback returns non string messages unchanged`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local marker = {name = "marker"}
                local returnedTable = debug.traceback(marker)
                local returnedNumber = debug.traceback(42)
                return returnedTable == marker,
                    returnedTable.name,
                    returnedNumber,
                    type(returnedNumber)
                """.trimIndent(),
                "debug-traceback-non-string.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals("marker", state.toString(2))
        assertEquals(42L, state.toInteger(3))
        assertEquals("number", state.toString(4))
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
        assertEquals("bad argument #2 to 'debug.getinfo' (invalid option)", state.toString(2))
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
        assertEquals("bad argument #2 to 'debug.getinfo' (string expected)", state.toString(2))
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
        assertEquals("bad argument #1 to 'debug.getinfo' (number expected)", state.toString(2))
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
                    return missing, ok, message
                end

                return leaf()
                """.trimIndent(),
                "debug-getlocal-level-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertFalse(state.toBoolean(2))
        assertEquals("bad argument #1 to 'debug.getlocal' (level out of range)", state.toString(3))
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
        assertEquals("bad argument #1 to 'debug.getlocal' (number expected)", state.toString(2))
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
                    local okFunction, functionMessage = pcall(debug.getlocal, target, "not-index")
                    return okStack, stackMessage, okFunction, functionMessage
                end

                return probe()
                """.trimIndent(),
                "debug-getlocal-index-type-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'debug.getlocal' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'debug.getlocal' (number expected)", state.toString(4))
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
        assertEquals("bad argument #1 to 'debug.setlocal' (level out of range)", state.toString(3))
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
        assertEquals("bad argument #1 to 'debug.setlocal' (number expected)", state.toString(2))
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
        assertEquals("bad argument #2 to 'debug.setlocal' (number expected)", state.toString(2))
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
        assertEquals("bad argument #4 to 'debug.upvaluejoin' (invalid upvalue index)", state.toString(5))
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
                local okIdIndex, idIndexMessage = pcall(debug.upvalueid, left, 99)
                local okJoinFunction, joinFunctionMessage = pcall(debug.upvaluejoin, "not-function", 1, leftAgain, 1)
                local okJoinOtherFunction, joinOtherFunctionMessage = pcall(debug.upvaluejoin, left, 1, "not-function", 1)
                local okJoinTarget, joinTargetMessage = pcall(debug.upvaluejoin, left, 99, leftAgain, 1)
                return okGetFunction, getFunctionMessage,
                    okGetIndex, getIndexMessage,
                    okSetupFunction, setupFunctionMessage,
                    okSetupIndex, setupIndexMessage,
                    okFunction, functionMessage,
                    okIdIndex, idIndexMessage,
                    okJoinFunction, joinFunctionMessage,
                    okJoinOtherFunction, joinOtherFunctionMessage,
                    okJoinTarget, joinTargetMessage
                """.trimIndent(),
                "debug-upvalue-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #1 to 'debug.getupvalue' (function expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'debug.getupvalue' (number expected)", state.toString(4))
        assertFalse(state.toBoolean(5))
        assertEquals("bad argument #1 to 'debug.setupvalue' (function expected)", state.toString(6))
        assertFalse(state.toBoolean(7))
        assertEquals("bad argument #2 to 'debug.setupvalue' (number expected)", state.toString(8))
        assertFalse(state.toBoolean(9))
        assertEquals("bad argument #1 to 'debug.upvalueid' (function expected)", state.toString(10))
        assertFalse(state.toBoolean(11))
        assertEquals("bad argument #2 to 'debug.upvalueid' (invalid upvalue index)", state.toString(12))
        assertFalse(state.toBoolean(13))
        assertEquals("bad argument #1 to 'debug.upvaluejoin' (function expected)", state.toString(14))
        assertFalse(state.toBoolean(15))
        assertEquals("bad argument #3 to 'debug.upvaluejoin' (function expected)", state.toString(16))
        assertFalse(state.toBoolean(17))
        assertEquals("bad argument #2 to 'debug.upvaluejoin' (invalid upvalue index)", state.toString(18))
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
    fun `debug sethook rejects invalid hook masks`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return debug.sethook(function() end, "x", 0)""", "debug-sethook-mask-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #2 to 'debug.sethook' (invalid hook mask)", state.toString(-1))
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
        assertEquals("bad argument #2 to 'debug.sethook' (string expected)", state.toString(-1))
    }

    @Test
    fun `debug sethook reports hook function errors before mask errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return debug.sethook("not-function", "x", 0)""", "debug-sethook-function-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #1 to 'debug.sethook' (function expected)", state.toString(-1))
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
        assertEquals("bad argument #3 to 'debug.sethook' (number expected)", state.toString(-1))
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
        assertTrue(message.contains("no file '${root}/alpha/beta.lua'"))
        assertTrue(message.contains("no file '${root}/alpha/beta/init.lua'"))
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

                local first = require("demo")
                local second = require("demo")
                return first.name, first.calls, first == second, package.loaded.demo == first
                """.trimIndent(),
                "require-preload-cache.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("demo", state.toString(1))
        assertEquals(1L, state.toInteger(2))
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
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
                return require("empty"), package.loaded.empty
                """.trimIndent(),
                "require-preload-nil.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertTrue(state.toBoolean(2))
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
                local first = require("alpha.beta")
                local second = require("alpha.beta")
                return first.name, first.filename,
                    first == second, package.loaded["alpha.beta"] == first
                """.trimIndent(),
                "require-file.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("alpha.beta", state.toString(1))
        assertTrue(state.toString(2)?.endsWith("beta.lua") == true)
        assertTrue(state.toBoolean(3))
        assertTrue(state.toBoolean(4))
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
                    tonumber("0x"),
                    tonumber("0x10000000000000000")
                """.trimIndent(),
                "tonumber-hex.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(16L, state.toInteger(1))
        assertEquals(255L, state.toInteger(2))
        assertEquals(-16L, state.toInteger(3))
        assertEquals(Long.MIN_VALUE, state.toInteger(4))
        assertTrue(state.isNil(5))
        assertTrue(state.isNil(6))
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
        assertEquals("boom", state.toString(6))
        assertEquals("dead", state.toString(7))
    }

    @Test
    fun `pcall protects non callable arguments`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return pcall(42)""", "pcall-non-callable.lua"))
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("string", state.typeName(2))
        assertTrue((state.toString(2) ?: "").isNotEmpty())
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
        assertEquals("handled:boom", state.toString(4))
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
        assertEquals("handled:boom", state.toString(6))
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
        assertEquals("handling:boom", state.toString(2))
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
                    return "handled:" .. type(message)
                end)
                """.trimIndent(),
                "xpcall-non-callable.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("handled:string", state.toString(2))
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
        assertEquals("argument 1 is nil", state.toString(2))
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
    fun `warn reports non string argument errors`() {
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
                collectgarbage("stop")
                local stopped = collectgarbage("isrunning")
                collectgarbage("restart")
                local restarted = collectgarbage("isrunning")
                local countType = type(collectgarbage("count"))
                local stepDone = collectgarbage("step")
                local previousMode = collectgarbage("generational")
                local restoredMode = collectgarbage("incremental")
                return running, stopped, restarted, countType, stepDone, previousMode, restoredMode
                """.trimIndent(),
                "collectgarbage.lua",
            ),
        )
        val status = state.pcall(0, -1)
        assertEquals(LuaStatus.OK, status, state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertFalse(state.toBoolean(2))
        assertTrue(state.toBoolean(3))
        assertEquals("number", state.toString(4))
        assertTrue(state.toBoolean(5))
        assertEquals("incremental", state.toString(6))
        assertEquals("generational", state.toString(7))
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
    fun `load rejects non string reader function results`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local chunk, message = load(function()
                    return 42
                end, "reader-number.lua")
                return chunk, message
                """.trimIndent(),
                "load-reader-number.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.isNil(1))
        assertEquals("reader function must return a string", state.toString(2))
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
                return textChunk(), defaultChunk(), binaryChunk, message
                """.trimIndent(),
                "load-mode.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals(42L, state.toInteger(1))
        assertEquals(24L, state.toInteger(2))
        assertTrue(state.isNil(3))
        assertEquals("attempt to load a text chunk (mode is 'b')", state.toString(4))
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
                    return textChunk(), binaryChunk, message
                    """.trimIndent(),
                    "loadfile-mode.lua",
                ),
            )
            assertEquals(LuaStatus.OK, state.pcall(0, -1))

            assertEquals(42L, state.toInteger(1))
            assertTrue(state.isNil(2))
            assertEquals("attempt to load a text chunk (mode is 'b')", state.toString(3))
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
                return seenKey, seenValue
                """.trimIndent(),
                "pairs-metamethod.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("custom", state.toString(1))
        assertEquals("value", state.toString(2))
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
    fun `pairs and ipairs defer non table errors to iterators`() {
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
                return type(pairIterator), pairState, pairKey, pairOk, pairMessage,
                    type(ipairsIterator), ipairsState, ipairsIndex, ipairsOk, ipairsMessage
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
        assertFalse(state.toBoolean(9))
        assertEquals("bad argument #1 to 'ipairs iterator' (table expected)", state.toString(10))
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
                return okNil, nilMessage, okString, stringMessage
                """.trimIndent(),
                "ipairs-iterator-index-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertFalse(state.toBoolean(1))
        assertEquals("bad argument #2 to 'ipairs iterator' (number expected)", state.toString(2))
        assertFalse(state.toBoolean(3))
        assertEquals("bad argument #2 to 'ipairs iterator' (number expected)", state.toString(4))
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
    fun `rawget rejects nan keys`() {
        val state = LuaState.create()
        LuaStdlib.openBase(state)

        assertEquals(LuaStatus.OK, state.load("""return rawget({}, 0 / 0)""", "rawget-nan-key-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("table index is NaN", state.toString(-1))
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
    fun `debug metatable functions report unsupported value errors`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local missing = debug.getmetatable("text")
                local okTable, tableMessage = pcall(debug.setmetatable, "text", {})
                local okMeta, metaMessage = pcall(debug.setmetatable, {}, "not-table")
                return missing, okTable, tableMessage, okMeta, metaMessage
                """.trimIndent(),
                "debug-metatable-errors.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.isNil(1))
        assertFalse(state.toBoolean(2))
        assertEquals("bad argument #1 to 'debug.setmetatable' (table expected)", state.toString(3))
        assertFalse(state.toBoolean(4))
        assertEquals("bad argument #2 to 'debug.setmetatable' (nil or table expected)", state.toString(5))
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
                return ok, message, coroutine.status(co)
                """.trimIndent(),
                "coroutine-error.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertFalse(state.toBoolean(1))
        assertEquals("boom", state.toString(2))
        assertEquals("dead", state.toString(3))
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
                return sum, samePayload, payloadName, resumeValue,
                    deadOk, deadMessage, failOk, failMessage
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
        assertEquals("boom", state.toString(8))
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
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local mainThread = coroutine.running()
                local beforeStatus = coroutine.status(mainThread)
                local beforeCloseOk, beforeCloseMessage = coroutine.close(mainThread)
                local co = coroutine.create(function()
                    local running, isMain = coroutine.running()
                    local mainStatus = coroutine.status(mainThread)
                    local closeOk, closeMessage = coroutine.close(mainThread)
                    return running ~= mainThread, isMain, mainStatus, closeOk, closeMessage
                end)
                local ok, differentThread, coroutineIsMain, duringStatus, duringCloseOk, duringCloseMessage =
                    coroutine.resume(co)
                return beforeStatus, beforeCloseOk, beforeCloseMessage,
                    ok, differentThread, coroutineIsMain, duringStatus, duringCloseOk, duringCloseMessage,
                    coroutine.status(mainThread)
                """.trimIndent(),
                "coroutine-main-status-normal.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("running", state.toString(1))
        assertFalse(state.toBoolean(2))
        assertEquals("cannot close running coroutine", state.toString(3))
        assertTrue(state.toBoolean(4))
        assertTrue(state.toBoolean(5))
        assertFalse(state.toBoolean(6))
        assertEquals("normal", state.toString(7))
        assertFalse(state.toBoolean(8))
        assertEquals("cannot close normal coroutine", state.toString(9))
        assertEquals("running", state.toString(10))
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
    fun `coroutine close reports running coroutine`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local co
                co = coroutine.create(function()
                    return coroutine.close(co)
                end)
                local ok, closeOk, message = coroutine.resume(co)
                return ok, closeOk, message, coroutine.status(co)
                """.trimIndent(),
                "coroutine-close-running.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertTrue(state.toBoolean(1))
        assertFalse(state.toBoolean(2))
        assertEquals("cannot close running coroutine", state.toString(3))
        assertEquals("dead", state.toString(4))
    }

    @Test
    fun `coroutine close reports normal coroutine`() {
        val state = LuaState.create()
        LuaStdlib.openCoroutine(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local outer
                outer = coroutine.create(function()
                    local inner = coroutine.create(function()
                        local closeOk, message = coroutine.close(outer)
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
        assertEquals("cannot close normal coroutine", state.toString(4))
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
        assertEquals("bad argument #1 to 'coroutine.create' (function expected)", createState.toString(-1))

        val resumeState = LuaState.create()
        LuaStdlib.openCoroutine(resumeState)

        assertEquals(
            LuaStatus.OK,
            resumeState.load("""return coroutine.resume("not-thread")""", "coroutine-resume-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, resumeState.pcall(0, -1))
        assertIs<LuaRuntimeException>(resumeState.getLastError())
        assertEquals("bad argument #1 to 'coroutine.resume' (thread expected)", resumeState.toString(-1))

        val statusState = LuaState.create()
        LuaStdlib.openCoroutine(statusState)

        assertEquals(
            LuaStatus.OK,
            statusState.load("""return coroutine.status("not-thread")""", "coroutine-status-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, statusState.pcall(0, -1))
        assertIs<LuaRuntimeException>(statusState.getLastError())
        assertEquals("bad argument #1 to 'coroutine.status' (thread expected)", statusState.toString(-1))

        val closeState = LuaState.create()
        LuaStdlib.openCoroutine(closeState)

        assertEquals(
            LuaStatus.OK,
            closeState.load("""return coroutine.close("not-thread")""", "coroutine-close-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, closeState.pcall(0, -1))
        assertIs<LuaRuntimeException>(closeState.getLastError())
        assertEquals("bad argument #1 to 'coroutine.close' (thread expected)", closeState.toString(-1))

        val isYieldableState = LuaState.create()
        LuaStdlib.openCoroutine(isYieldableState)

        assertEquals(
            LuaStatus.OK,
            isYieldableState.load("""return coroutine.isyieldable("not-thread")""", "coroutine-isyieldable-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, isYieldableState.pcall(0, -1))
        assertIs<LuaRuntimeException>(isYieldableState.getLastError())
        assertEquals(
            "bad argument #1 to 'coroutine.isyieldable' (thread expected)",
            isYieldableState.toString(-1),
        )

        val wrapState = LuaState.create()
        LuaStdlib.openCoroutine(wrapState)

        assertEquals(LuaStatus.OK, wrapState.load("""return coroutine.wrap(nil)""", "coroutine-wrap-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, wrapState.pcall(0, -1))
        assertIs<LuaRuntimeException>(wrapState.getLastError())
        assertEquals("bad argument #1 to 'coroutine.create' (function expected)", wrapState.toString(-1))
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
    fun `math frexp and ldexp report argument errors`() {
        val frexpState = LuaState.create()
        LuaStdlib.openMath(frexpState)

        assertEquals(LuaStatus.OK, frexpState.load("""return math.frexp("x")""", "math-frexp-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, frexpState.pcall(0, -1))

        assertIs<LuaRuntimeException>(frexpState.getLastError())
        assertEquals("bad argument #1 to 'math.frexp' (number expected)", frexpState.toString(-1))

        val ldexpState = LuaState.create()
        LuaStdlib.openMath(ldexpState)

        assertEquals(LuaStatus.OK, ldexpState.load("""return math.ldexp(1, "x")""", "math-ldexp-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, ldexpState.pcall(0, -1))

        assertIs<LuaRuntimeException>(ldexpState.getLastError())
        assertEquals("bad argument #2 to 'math.ldexp' (integer expected)", ldexpState.toString(-1))
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
    fun `math min and max report missing argument errors`() {
        val minState = LuaState.create()
        LuaStdlib.openMath(minState)

        assertEquals(LuaStatus.OK, minState.load("""return math.min()""", "math-min-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, minState.pcall(0, -1))

        assertIs<LuaRuntimeException>(minState.getLastError())
        assertEquals("bad argument #1 to 'math.min' (value expected)", minState.toString(-1))

        val maxState = LuaState.create()
        LuaStdlib.openMath(maxState)

        assertEquals(LuaStatus.OK, maxState.load("""return math.max()""", "math-max-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, maxState.pcall(0, -1))

        assertIs<LuaRuntimeException>(maxState.getLastError())
        assertEquals("bad argument #1 to 'math.max' (value expected)", maxState.toString(-1))
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
                local allBits = math.random(0)
                math.randomseed(123)
                return first == math.random(),
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

        for (index in 1..8) {
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
                local literalCaretStart, literalCaretEnd = string.find("a^b", "a^")
                local literalDollarStart, literalDollarEnd = string.find("a${'$'}b", "${'$'}b")
                return firstStart, firstEnd, secondStart, secondEnd, lastStart, lastEnd,
                    dotStart, dotEnd, patternStart, patternEnd, digitStart, digitEnd,
                    literalDotStart, literalDotEnd, anchorStart, anchorEnd, endStart, endEnd,
                    vowelStart, vowelEnd, rangeStart, rangeEnd, negatedStart, negatedEnd,
                    literalCaretStart, literalCaretEnd, literalDollarStart, literalDollarEnd,
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
        assertTrue(state.isNil(29))
        assertTrue(state.isNil(30))
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
    fun `string find reports unsupported patterns`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.find("abc", "a]")""", "string-find-pattern.lua"))
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
                return first, last, before, after, matchBefore, matchAfter,
                    firstWord, secondWord, replaced, count, expanded, expandedCount
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
    fun `string format renders hexadecimal float conversions`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return string.format("%a|%A|%.2a|%12a", 1.5, 1.5, 1.5, 1.5)""",
                "string-format-hex-float.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("0x1.8p0|0X1.8P0|0x1.80p0|     0x1.8p0", state.toString(1))
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
        assertEquals("invalid option '%#d' to 'string.format'", hashState.toString(-1))

        val plusState = LuaState.create()
        LuaStdlib.openString(plusState)

        assertEquals(LuaStatus.OK, plusState.load("""return string.format("%+u", 1)""", "string-format-unsigned-plus-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, plusState.pcall(0, -1))

        assertIs<LuaRuntimeException>(plusState.getLastError())
        assertEquals("invalid option '%+u' to 'string.format'", plusState.toString(-1))

        val spaceState = LuaState.create()
        LuaStdlib.openString(spaceState)

        assertEquals(LuaStatus.OK, spaceState.load("""return string.format("% x", 1)""", "string-format-hex-space-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, spaceState.pcall(0, -1))

        assertIs<LuaRuntimeException>(spaceState.getLastError())
        assertEquals("invalid option '% x' to 'string.format'", spaceState.toString(-1))
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
    fun `string format reports missing argument errors`() {
        val firstMissingState = LuaState.create()
        LuaStdlib.openString(firstMissingState)

        assertEquals(LuaStatus.OK, firstMissingState.load("""return string.format("%s")""", "string-format-missing-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, firstMissingState.pcall(0, -1))

        assertIs<LuaRuntimeException>(firstMissingState.getLastError())
        assertEquals("bad argument #2 to 'string.format' (no value)", firstMissingState.toString(-1))

        val laterMissingState = LuaState.create()
        LuaStdlib.openString(laterMissingState)

        assertEquals(
            LuaStatus.OK,
            laterMissingState.load("""return string.format("%% %s %d", "x")""", "string-format-later-missing-error.lua"),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, laterMissingState.pcall(0, -1))

        assertIs<LuaRuntimeException>(laterMissingState.getLastError())
        assertEquals("bad argument #3 to 'string.format' (no value)", laterMissingState.toString(-1))
    }

    @Test
    fun `string format rejects invalid string modifiers`() {
        val plusState = LuaState.create()
        LuaStdlib.openString(plusState)

        assertEquals(LuaStatus.OK, plusState.load("""return string.format("%+s", "x")""", "string-format-string-plus-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, plusState.pcall(0, -1))

        assertIs<LuaRuntimeException>(plusState.getLastError())
        assertEquals("invalid option '%+s' to 'string.format'", plusState.toString(-1))

        val zeroState = LuaState.create()
        LuaStdlib.openString(zeroState)

        assertEquals(LuaStatus.OK, zeroState.load("""return string.format("%05s", "x")""", "string-format-string-zero-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, zeroState.pcall(0, -1))

        assertIs<LuaRuntimeException>(zeroState.getLastError())
        assertEquals("invalid option '%05s' to 'string.format'", zeroState.toString(-1))
    }

    @Test
    fun `string format rejects oversized width and precision`() {
        val integerState = LuaState.create()
        LuaStdlib.openString(integerState)

        assertEquals(LuaStatus.OK, integerState.load("""return string.format("%100d", 1)""", "string-format-wide-integer-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, integerState.pcall(0, -1))

        assertIs<LuaRuntimeException>(integerState.getLastError())
        assertEquals("invalid option '%100d' to 'string.format'", integerState.toString(-1))

        val floatState = LuaState.create()
        LuaStdlib.openString(floatState)

        assertEquals(LuaStatus.OK, floatState.load("""return string.format("%.100f", 1)""", "string-format-precise-float-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, floatState.pcall(0, -1))

        assertIs<LuaRuntimeException>(floatState.getLastError())
        assertEquals("invalid option '%.100f' to 'string.format'", floatState.toString(-1))

        val stringState = LuaState.create()
        LuaStdlib.openString(stringState)

        assertEquals(LuaStatus.OK, stringState.load("""return string.format("%100s", "x")""", "string-format-wide-string-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, stringState.pcall(0, -1))

        assertIs<LuaRuntimeException>(stringState.getLastError())
        assertEquals("invalid option '%100s' to 'string.format'", stringState.toString(-1))
    }

    @Test
    fun `string format rejects length modifiers`() {
        val stringState = LuaState.create()
        LuaStdlib.openString(stringState)

        assertEquals(LuaStatus.OK, stringState.load("""return string.format("%ls", "x")""", "string-format-string-length-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, stringState.pcall(0, -1))

        assertIs<LuaRuntimeException>(stringState.getLastError())
        assertEquals("invalid option '%ls' to 'string.format'", stringState.toString(-1))

        val integerState = LuaState.create()
        LuaStdlib.openString(integerState)

        assertEquals(LuaStatus.OK, integerState.load("""return string.format("%Ld", 1)""", "string-format-integer-length-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, integerState.pcall(0, -1))

        assertIs<LuaRuntimeException>(integerState.getLastError())
        assertEquals("invalid option '%Ld' to 'string.format'", integerState.toString(-1))

        val hexState = LuaState.create()
        LuaStdlib.openString(hexState)

        assertEquals(LuaStatus.OK, hexState.load("""return string.format("%hhx", 1)""", "string-format-hex-length-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, hexState.pcall(0, -1))

        assertIs<LuaRuntimeException>(hexState.getLastError())
        assertEquals("invalid option '%hhx' to 'string.format'", hexState.toString(-1))
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
    fun `string format rejects invalid character modifiers`() {
        val plusState = LuaState.create()
        LuaStdlib.openString(plusState)

        assertEquals(LuaStatus.OK, plusState.load("""return string.format("%+c", 65)""", "string-format-char-plus-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, plusState.pcall(0, -1))

        assertIs<LuaRuntimeException>(plusState.getLastError())
        assertEquals("invalid option '%+c' to 'string.format'", plusState.toString(-1))

        val zeroState = LuaState.create()
        LuaStdlib.openString(zeroState)

        assertEquals(LuaStatus.OK, zeroState.load("""return string.format("%05c", 65)""", "string-format-char-zero-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, zeroState.pcall(0, -1))

        assertIs<LuaRuntimeException>(zeroState.getLastError())
        assertEquals("invalid option '%05c' to 'string.format'", zeroState.toString(-1))

        val precisionState = LuaState.create()
        LuaStdlib.openString(precisionState)

        assertEquals(LuaStatus.OK, precisionState.load("""return string.format("%.1c", 65)""", "string-format-char-precision-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, precisionState.pcall(0, -1))

        assertIs<LuaRuntimeException>(precisionState.getLastError())
        assertEquals("invalid option '%.1c' to 'string.format'", precisionState.toString(-1))
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
        assertEquals("invalid option '%+p' to 'string.format'", plusState.toString(-1))

        val zeroState = LuaState.create()
        LuaStdlib.openString(zeroState)

        assertEquals(LuaStatus.OK, zeroState.load("""return string.format("%05p", {})""", "string-format-pointer-zero-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, zeroState.pcall(0, -1))

        assertIs<LuaRuntimeException>(zeroState.getLastError())
        assertEquals("invalid option '%05p' to 'string.format'", zeroState.toString(-1))

        val precisionState = LuaState.create()
        LuaStdlib.openString(precisionState)

        assertEquals(LuaStatus.OK, precisionState.load("""return string.format("%.1p", {})""", "string-format-pointer-precision-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, precisionState.pcall(0, -1))

        assertIs<LuaRuntimeException>(precisionState.getLastError())
        assertEquals("invalid option '%.1p' to 'string.format'", precisionState.toString(-1))
    }

    @Test
    fun `string format rejects quote modifiers`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(LuaStatus.OK, state.load("""return string.format("%5q", "x")""", "string-format-quote-modifier-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("invalid option '%5q' to 'string.format'", state.toString(-1))
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
                return integerReplacement, integerCount, floatReplacement, floatCount
                """.trimIndent(),
                "string-gsub-number-replacement.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1))

        assertEquals("a7b7", state.toString(1))
        assertEquals(2L, state.toInteger(2))
        assertEquals("a1.5b", state.toString(3))
        assertEquals(1L, state.toInteger(4))
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

        assertEquals(LuaStatus.OK, state.load("""return string.gsub("abc", "a]", "x")""", "string-gsub-pattern.lua"))
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
                return positive(), positive(), positive(),
                    negative(), negative(),
                    clamped(),
                    pastEnd()
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
    fun `string gmatch reports unsupported patterns`() {
        val state = LuaState.create()
        LuaStdlib.openString(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator = string.gmatch("abc", "a]")
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
    fun `utf8 codes returns codepoint iterator`() {
        val state = LuaState.create()
        LuaStdlib.openUtf8(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local iterator = utf8.codes("A" .. utf8.char(128512) .. "Z")
                local firstIndex, firstCode = iterator()
                local secondIndex, secondCode = iterator()
                local thirdIndex, thirdCode = iterator()
                local done = iterator()
                return firstIndex, firstCode, secondIndex, secondCode, thirdIndex, thirdCode, done
                """.trimIndent(),
                "utf8-codes.lua",
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
        assertEquals("bad argument #2 to 'utf8.len' (position out of range)", startState.toString(-1))

        val endState = LuaState.create()
        LuaStdlib.openUtf8(endState)

        assertEquals(LuaStatus.OK, endState.load("""return utf8.len("abc", 1, 4)""", "utf8-len-end-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, endState.pcall(0, -1))

        assertIs<LuaRuntimeException>(endState.getLastError())
        assertEquals("bad argument #3 to 'utf8.len' (position out of range)", endState.toString(-1))
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
        assertEquals("bad argument #3 to 'utf8.offset' (initial position is a continuation byte)", state.toString(-1))
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

        assertEquals(LuaStatus.OK, state.load("""return string.match("abc", "a]")""", "string-match-pattern.lua"))
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
    fun `table concat default range respects raw sequence length`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load("""return table.concat({"a", nil, "c"})""", "table-concat-default-length.lua"),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertEquals("a", state.toString(1))
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
        assertEquals("bad argument #1 to 'table.create' (integer expected)", typeState.toString(-1))

        val sequenceState = LuaState.create()
        LuaStdlib.openTable(sequenceState)

        assertEquals(LuaStatus.OK, sequenceState.load("""return table.create(-1)""", "table-create-seq-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, sequenceState.pcall(0, -1))

        assertIs<LuaRuntimeException>(sequenceState.getLastError())
        assertEquals(
            "bad argument #1 to 'table.create' (non-negative integer expected)",
            sequenceState.toString(-1),
        )

        val recordState = LuaState.create()
        LuaStdlib.openTable(recordState)

        assertEquals(LuaStatus.OK, recordState.load("""return table.create(1, -1)""", "table-create-rec-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, recordState.pcall(0, -1))

        assertIs<LuaRuntimeException>(recordState.getLastError())
        assertEquals(
            "bad argument #2 to 'table.create' (non-negative integer expected)",
            recordState.toString(-1),
        )
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
    fun `table move reports argument errors`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(LuaStatus.OK, state.load("""return table.move({}, 1, 1, 1, "not-table")""", "table-move-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("bad argument #5 to 'table.move' (table expected)", state.toString(-1))
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
        assertEquals("bad argument #3 to 'table.move' (too many elements to move)", state.toString(-1))
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
        assertEquals("bad argument #4 to 'table.move' (destination wrap around)", state.toString(-1))
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
    fun `table sort reports argument errors`() {
        val tableState = LuaState.create()
        LuaStdlib.openTable(tableState)

        assertEquals(LuaStatus.OK, tableState.load("""return table.sort("not-table")""", "table-sort-table-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, tableState.pcall(0, -1))

        assertIs<LuaRuntimeException>(tableState.getLastError())
        assertEquals("bad argument #1 to 'table.sort' (table expected)", tableState.toString(-1))

        val comparatorState = LuaState.create()
        LuaStdlib.openTable(comparatorState)

        assertEquals(LuaStatus.OK, comparatorState.load("""return table.sort({1}, true)""", "table-sort-comparator-error.lua"))
        assertEquals(LuaStatus.RUNTIME_ERROR, comparatorState.pcall(0, -1))

        assertIs<LuaRuntimeException>(comparatorState.getLastError())
        assertEquals("bad argument #2 to 'table.sort' (function expected)", comparatorState.toString(-1))
    }

    @Test
    fun `table sort rejects invalid comparator order`() {
        val state = LuaState.create()
        LuaStdlib.openTable(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """return table.sort({1, 2}, function(a, b) return true end)""",
                "table-sort-invalid-order-error.lua",
            ),
        )
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1))

        assertIs<LuaRuntimeException>(state.getLastError())
        assertEquals("invalid order function for sorting", state.toString(-1))
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

private fun Path.luaPath(): String = toString().replace("\\", "\\\\")
