package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunctionDebugInfo
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaStackFrame
import io.github.realmlabs.klua.api.LuaState

internal object LuaDebugLibrary {
    fun open(state: LuaState): LuaState {
        state.register("klua_debug_traceback") { context -> traceback(context) }
        state.register("klua_debug_getinfo") { context -> getInfo(context) }
        state.register("klua_debug_getlocal") { context -> getLocal(context) }
        state.register("klua_debug_setlocal") { context -> setLocal(context) }
        state.register("klua_debug_getupvalue") { context -> getUpvalue(context) }
        state.register("klua_debug_setupvalue") { context -> setupUpvalue(context) }
        state.register("klua_debug_upvalueid") { context -> upvalueId(context) }
        state.register("klua_debug_upvaluejoin") { context -> upvalueJoin(context) }
        state.register("klua_debug_getuservalue") { context -> getUserValue(context) }
        state.register("klua_debug_setuservalue") { context -> setUserValue(context) }
        state.register("klua_debug_getmetatable") { context -> getMetatable(context) }
        state.register("klua_debug_setmetatable") { context -> setMetatable(context) }
        state.register("klua_debug_sethook") { context -> setHook(context) }
        state.register("klua_debug_gethook") { context -> context.getDebugHook() }
        installLuaSource(state, DEBUG_SOURCE, "stdlib-debug.lua")
        return state
    }

    private fun traceback(context: LuaCallContext): LuaReturn {
        val message = when {
            context.isNil(1) || context.isNone(1) -> null
            else -> context.toString(1) ?: return LuaReturn.of(context.getLuaValue(1))
        }
        val level = optionalStackLevel(context, 2, 1, "traceback")
        val frames = if (level < 0) {
            emptyList()
        } else {
            context.luaFrames.drop(level)
        }
        return LuaReturn.of(formatTraceback(message, frames))
    }

    private fun formatTraceback(message: String?, frames: List<LuaStackFrame>): String {
        return buildString {
            if (message != null) {
                append(message)
                append('\n')
            }
            append("stack traceback:")
            if (frames.size > TRACEBACK_HEAD_LEVELS + TRACEBACK_TAIL_LEVELS) {
                for (frame in frames.take(TRACEBACK_HEAD_LEVELS)) {
                    appendTracebackFrame(frame)
                }
                append("\n\t...\t(skipping ")
                append(frames.size - TRACEBACK_HEAD_LEVELS - TRACEBACK_TAIL_LEVELS)
                append(" levels)")
                for (frame in frames.takeLast(TRACEBACK_TAIL_LEVELS)) {
                    appendTracebackFrame(frame)
                }
            } else {
                for (frame in frames) {
                    appendTracebackFrame(frame)
                }
            }
        }
    }

    private fun StringBuilder.appendTracebackFrame(frame: LuaStackFrame) {
        append("\n\t")
        append(luaShortSourceName(frame.sourceName))
        if (frame.line > 0) {
            append(':')
            append(frame.line)
        }
    }

    private fun getInfo(context: LuaCallContext): LuaReturn {
        val what = optionalString(context, 2, DEFAULT_GETINFO_OPTIONS, "getinfo")
        rejectPrivateGetInfoOption(what)
        if (context.typeName(1) == "function") {
            val info = context.getFunctionDebugInfo(1)
            return LuaReturn.of(functionInfoTable(info, what, context.getLuaValue(1)))
        }
        val level = requiredStackLevel(context, "getinfo")
        if (level < 0) {
            return LuaReturn.of(null)
        }
        val frame = context.luaFrames.drop(level).firstOrNull() ?: return LuaReturn.of(null)
        return LuaReturn.of(frameInfoTable(frame, what))
    }

    private fun frameInfoTable(frame: LuaStackFrame, what: String): Map<String, Any?> {
        return debugInfoTable(what) { option, table ->
            when (option) {
                'S' -> {
                    table["what"] = luaFunctionWhat(frame.lineDefined)
                    table["source"] = frame.sourceName
                    table["short_src"] = luaShortSourceName(frame.sourceName)
                    table["linedefined"] = frame.lineDefined.toLong()
                    table["lastlinedefined"] = frame.lastLineDefined.toLong()
                }
                'l' -> table["currentline"] = frame.line.toLong()
                'n' -> table["namewhat"] = ""
                'u' -> {
                    table["nups"] = frame.upvalueCount.toLong()
                    table["nparams"] = frame.parameterCount.toLong()
                    table["isvararg"] = frame.isVararg
                }
                'f' -> table["func"] = frame.function
                'r' -> addTransferInfo(table)
                't' -> addTailCallInfo(table)
                'L' -> table["activelines"] = activeLinesTable(frame.activeLines)
            }
        }
    }

    private fun functionInfoTable(info: LuaFunctionDebugInfo?, what: String, function: Any?): Map<String, Any?> {
        if (info == null) {
            return debugInfoTable(what) { option, table ->
                when (option) {
                    'S' -> {
                        table["what"] = "C"
                        table["source"] = "=[C]"
                        table["short_src"] = "[C]"
                        table["linedefined"] = -1L
                        table["lastlinedefined"] = -1L
                    }
                    'l' -> table["currentline"] = -1L
                    'n' -> table["namewhat"] = ""
                    'f' -> table["func"] = function
                    'u' -> {
                        table["nups"] = 0L
                        table["nparams"] = 0L
                        table["isvararg"] = true
                    }
                    'r' -> addTransferInfo(table)
                    't' -> addTailCallInfo(table)
                    'L' -> Unit
                }
            }
        }
        return debugInfoTable(what) { option, table ->
            when (option) {
                'S' -> {
                    table["what"] = luaFunctionWhat(info.lineDefined)
                    table["source"] = info.sourceName
                    table["short_src"] = luaShortSourceName(info.sourceName)
                    table["linedefined"] = info.lineDefined.toLong()
                    table["lastlinedefined"] = info.lastLineDefined.toLong()
                }
                'l' -> table["currentline"] = -1L
                'n' -> table["namewhat"] = ""
                'f' -> table["func"] = function
                'u' -> {
                    table["nups"] = info.upvalueCount.toLong()
                    table["nparams"] = info.parameterCount.toLong()
                    table["isvararg"] = info.isVararg
                }
                'r' -> addTransferInfo(table)
                't' -> addTailCallInfo(table)
                'L' -> table["activelines"] = activeLinesTable(info.activeLines)
            }
        }
    }

    private fun activeLinesTable(lines: List<Int>): Map<Long, Boolean> {
        return lines.associate { line -> line.toLong() to true }
    }

    private fun debugInfoTable(
        what: String,
        addFields: (option: Char, table: MutableMap<String, Any?>) -> Unit,
    ): Map<String, Any?> {
        val table = linkedMapOf<String, Any?>()
        for (option in what) {
            if (option !in GETINFO_OPTIONS) {
                throw LuaRuntimeException("bad argument #2 to 'getinfo' (invalid option)")
            }
            addFields(option, table)
        }
        return table
    }

    private fun rejectPrivateGetInfoOption(what: String) {
        if (what.startsWith(">")) {
            throw LuaRuntimeException("bad argument #2 to 'getinfo' (invalid option '>')")
        }
    }

    private fun addTransferInfo(table: MutableMap<String, Any?>) {
        table["ftransfer"] = 0L
        table["ntransfer"] = 0L
    }

    private fun addTailCallInfo(table: MutableMap<String, Any?>) {
        table["istailcall"] = false
        table["extraargs"] = 0L
    }

    private fun luaFunctionWhat(lineDefined: Int): String = if (lineDefined == 0) "main" else "Lua"

    private fun getLocal(context: LuaCallContext): LuaReturn {
        val index = requiredLocalIndex(context, 2, "getlocal")
        if (context.typeName(1) == "function") {
            if (index <= 0) {
                return LuaReturn.of(null)
            }
            val info = context.getFunctionDebugInfo(1) ?: return LuaReturn.of(null)
            return LuaReturn.of(info.parameterNames.getOrNull(index - 1))
        }
        val level = requiredStackLevel(context, "getlocal")
        if (level < 0) {
            throw LuaRuntimeException("bad argument #1 to 'getlocal' (level out of range)")
        }
        val frame = context.luaFrames.drop(level).firstOrNull()
            ?: throw LuaRuntimeException("bad argument #1 to 'getlocal' (level out of range)")
        if (index < 0) {
            val varargIndex = -index - 1
            if (varargIndex !in frame.varargs.indices) {
                return LuaReturn.of(null)
            }
            val vararg = frame.varargs[varargIndex]
            return LuaReturn.of(VARARG_LOCAL_NAME, vararg)
        }
        if (index == 0) {
            return LuaReturn.of(null)
        }
        val local = frame.locals.getOrNull(index - 1) ?: return LuaReturn.of(null)
        return LuaReturn.of(local.name, local.value)
    }

    private fun setLocal(context: LuaCallContext): LuaReturn {
        val level = requiredStackLevel(context, "setlocal")
        val index = requiredLocalIndex(context, 2, "setlocal")
        if (level < 0) {
            throw LuaRuntimeException("bad argument #1 to 'setlocal' (level out of range)")
        }
        context.luaFrames.drop(level).firstOrNull()
            ?: throw LuaRuntimeException("bad argument #1 to 'setlocal' (level out of range)")
        requireValueArgument(context, 3, "setlocal")
        if (index == 0) {
            return LuaReturn.of(null)
        }
        return LuaReturn.of(context.setLocal(level, index, context.get(3)))
    }

    private fun getUpvalue(context: LuaCallContext): LuaReturn {
        val index = requiredUpvalueLookupIndex(context, 2, "getupvalue")
        requireFunction(context, 1, "getupvalue")
        if (index <= 0) {
            return LuaReturn.none()
        }
        return context.getUpvalue(1, index) ?: LuaReturn.none()
    }

    private fun setupUpvalue(context: LuaCallContext): LuaReturn {
        requireValueArgument(context, 3, "setupvalue")
        val index = requiredUpvalueLookupIndex(context, 2, "setupvalue")
        requireFunction(context, 1, "setupvalue")
        if (index <= 0) {
            return LuaReturn.none()
        }
        val name = context.setUpvalue(1, index, context.get(3)) ?: return LuaReturn.none()
        return LuaReturn.of(name)
    }

    private fun upvalueId(context: LuaCallContext): LuaReturn {
        val index = requiredUpvalueLookupIndex(context, 2, "upvalueid")
        requireFunction(context, 1, "upvalueid")
        if (index <= 0) {
            return LuaReturn.of(null)
        }
        val id = context.getUpvalueId(1, index)
            ?: return LuaReturn.of(null)
        return LuaReturn.of(id)
    }

    private fun upvalueJoin(context: LuaCallContext): LuaReturn {
        val targetIndex = requiredPositiveUpvalueIndex(context, 2, "upvaluejoin")
        requireFunction(context, 1, "upvaluejoin")
        if (context.getUpvalueId(1, targetIndex) == null) {
            throw LuaRuntimeException("bad argument #2 to 'upvaluejoin' (invalid upvalue index)")
        }
        val sourceIndex = requiredPositiveUpvalueIndex(context, 4, "upvaluejoin")
        requireFunction(context, 3, "upvaluejoin")
        if (context.getUpvalueId(3, sourceIndex) == null) {
            throw LuaRuntimeException("bad argument #4 to 'upvaluejoin' (invalid upvalue index)")
        }
        context.joinUpvalue(1, targetIndex, 3, sourceIndex)
        return LuaReturn.none()
    }

    private fun getUserValue(context: LuaCallContext): LuaReturn {
        val index = optionalInteger(context, 2, 1, "getuservalue").toInt()
        if (context.typeName(1) != "userdata") {
            return LuaReturn.of(null)
        }
        return context.getUserValue(1, index) ?: LuaReturn.of(null)
    }

    private fun setUserValue(context: LuaCallContext): LuaReturn {
        val index = optionalInteger(context, 3, 1, "setuservalue").toInt()
        if (context.typeName(1) != "userdata") {
            throw LuaRuntimeException("bad argument #1 to 'setuservalue' (userdata expected)")
        }
        requireValueArgument(context, 2, "setuservalue")
        return if (context.setUserValue(1, index, context.getLuaValue(2))) {
            LuaReturn.of(context.getLuaValue(1))
        } else {
            LuaReturn.of(null)
        }
    }

    private fun requireFunction(context: LuaCallContext, index: Int, functionName: String) {
        if (context.typeName(index) != "function") {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (function expected)")
        }
    }

    private fun requireValueArgument(context: LuaCallContext, index: Int, functionName: String) {
        if (context.isNone(index)) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (value expected)")
        }
    }

    private fun requiredPositiveUpvalueIndex(context: LuaCallContext, index: Int, functionName: String): Int {
        val upvalueIndex = requiredIntegerArgument(context, index, functionName)
        if (upvalueIndex <= 0) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (invalid upvalue index)")
        }
        return upvalueIndex
    }

    private fun requiredUpvalueLookupIndex(context: LuaCallContext, index: Int, functionName: String): Int {
        return requiredIntegerArgument(context, index, functionName)
    }

    private fun requiredLocalIndex(context: LuaCallContext, index: Int, functionName: String): Int {
        return requiredIntegerArgument(context, index, functionName)
    }

    private fun requiredStackLevel(context: LuaCallContext, functionName: String): Int {
        return requiredIntegerArgument(context, 1, functionName)
    }

    private fun optionalStackLevel(
        context: LuaCallContext,
        index: Int,
        default: Int,
        functionName: String,
    ): Int {
        return if (context.isNone(index) || context.isNil(index)) {
            default
        } else {
            requiredIntegerArgument(context, index, functionName)
        }
    }

    private fun getMetatable(context: LuaCallContext): LuaReturn {
        requireValueArgument(context, 1, "getmetatable")
        return LuaReturn.of(context.getRawMetatable(1))
    }

    private fun setMetatable(context: LuaCallContext): LuaReturn {
        if (context.isNone(2) || (!context.isNil(2) && !context.isTable(2))) {
            throw LuaRuntimeException("bad argument #2 to 'setmetatable' (nil or table expected)")
        }
        try {
            context.setRawMetatable(1, context.getTable(2))
        } catch (_: IllegalArgumentException) {
            throw LuaRuntimeException("bad argument #1 to 'setmetatable' (table expected)")
        }
        return LuaReturn.of(context.getLuaValue(1))
    }

    private fun setHook(context: LuaCallContext): LuaReturn {
        if (context.isNone(1) || context.isNil(1)) {
            context.setDebugHook(1, "", 0)
            return LuaReturn.of()
        }
        val mask = requiredString(context, 2, "sethook")
        requireFunction(context, 1, "sethook")
        val count = optionalInteger(context, 3, 0, "sethook").toInt()
        context.setDebugHook(1, mask, count)
        return LuaReturn.of()
    }

    private fun optionalInteger(
        context: LuaCallContext,
        index: Int,
        default: Long,
        functionName: String,
    ): Long {
        return if (context.isNone(index) || context.isNil(index)) {
            default
        } else {
            requiredIntegerArgument(context, index, functionName).toLong()
        }
    }

    private fun requiredIntegerArgument(context: LuaCallContext, index: Int, functionName: String): Int {
        return context.toInteger(index)?.toInt()
            ?: if (context.toNumber(index) != null || context.typeName(index) == "number") {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number has no integer representation)")
            } else {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
            }
    }

    private fun optionalString(
        context: LuaCallContext,
        index: Int,
        default: String,
        functionName: String,
    ): String {
        return if (context.isNone(index) || context.isNil(index)) {
            default
        } else {
            context.toString(index)
                ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
        }
    }

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return if (context.isNone(index) || context.isNil(index)) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
        } else {
            context.toString(index)
                ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
        }
    }

    private const val DEBUG_SOURCE: String = """
        debug = debug or {}
        local klua_debug_registry = {}

        function debug.traceback(message, level)
            return klua_debug_traceback(message, level)
        end

        function debug.getregistry()
            return klua_debug_registry
        end

        function debug.getinfo(threadOrLevel, what)
            return klua_debug_getinfo(threadOrLevel, what)
        end

        function debug.getlocal(threadOrLevel, index)
            return klua_debug_getlocal(threadOrLevel, index)
        end

        function debug.setlocal(threadOrLevel, index, ...)
            return klua_debug_setlocal(threadOrLevel, index, ...)
        end

        function debug.getupvalue(func, index)
            return klua_debug_getupvalue(func, index)
        end

        function debug.setupvalue(func, index, ...)
            return klua_debug_setupvalue(func, index, ...)
        end

        function debug.upvalueid(func, index)
            return klua_debug_upvalueid(func, index)
        end

        function debug.upvaluejoin(func1, index1, func2, index2)
            return klua_debug_upvaluejoin(func1, index1, func2, index2)
        end

        function debug.getuservalue(userdata, index)
            return klua_debug_getuservalue(userdata, index)
        end

        function debug.setuservalue(userdata, ...)
            local count = select("#", ...)
            if count == 0 then
                return klua_debug_setuservalue(userdata)
            end
            local value, index = ...
            return klua_debug_setuservalue(userdata, value, index)
        end

        function debug.getmetatable(...)
            return klua_debug_getmetatable(...)
        end

        function debug.setmetatable(value, ...)
            return klua_debug_setmetatable(value, ...)
        end

        function debug.sethook(hook, mask, count)
            return klua_debug_sethook(hook, mask, count)
        end

        function debug.gethook()
            return klua_debug_gethook()
        end
    """

    private const val GETINFO_OPTIONS = "flnSrtuL"
    private const val DEFAULT_GETINFO_OPTIONS = "flnSrtu"
    private const val VARARG_LOCAL_NAME = "(vararg)"
    private const val TRACEBACK_HEAD_LEVELS = 10
    private const val TRACEBACK_TAIL_LEVELS = 11
}
