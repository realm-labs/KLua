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
        state.register("klua_debug_gethook") { context -> getHook(context) }
        installLuaSource(state, DEBUG_SOURCE, "stdlib-debug.lua")
        return state
    }

    private fun traceback(context: LuaCallContext): LuaReturn {
        val target = threadTarget(context)
        val message = when {
            context.isNil(target.argumentOffset + 1) || context.isNone(target.argumentOffset + 1) -> null
            else -> context.toString(target.argumentOffset + 1)
                ?: return LuaReturn.of(context.getLuaValue(target.argumentOffset + 1))
        }
        val defaultLevel = if (target.isCurrentThread) 1 else 0
        val level = optionalStackLevel(context, target.argumentOffset + 2, defaultLevel, "traceback")
        val frames = if (level < 0) {
            emptyList()
        } else {
            target.frames.drop(level)
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
        val target = threadTarget(context)
        val optionIndex = target.argumentOffset + 2
        val what = optionalString(context, optionIndex, DEFAULT_GETINFO_OPTIONS, "getinfo")
        rejectPrivateGetInfoOption(what, optionIndex)
        val subjectIndex = target.argumentOffset + 1
        if (context.typeName(subjectIndex) == "function") {
            val info = context.getFunctionDebugInfo(subjectIndex)
            return LuaReturn.of(functionInfoTable(info, what, optionIndex, context.getLuaValue(subjectIndex)))
        }
        val level = requiredStackLevel(context, subjectIndex, "getinfo")
        if (level < 0) {
            return LuaReturn.of(null)
        }
        val frame = target.frames.drop(level).firstOrNull() ?: return LuaReturn.of(null)
        return LuaReturn.of(frameInfoTable(frame, what, optionIndex))
    }

    private fun frameInfoTable(frame: LuaStackFrame, what: String, optionIndex: Int): Map<String, Any?> {
        return debugInfoTable(what, optionIndex) { option, table ->
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

    private fun functionInfoTable(
        info: LuaFunctionDebugInfo?,
        what: String,
        optionIndex: Int,
        function: Any?,
    ): Map<String, Any?> {
        if (info == null) {
            return debugInfoTable(what, optionIndex) { option, table ->
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
        return debugInfoTable(what, optionIndex) { option, table ->
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
        optionIndex: Int,
        addFields: (option: Char, table: MutableMap<String, Any?>) -> Unit,
    ): Map<String, Any?> {
        val table = linkedMapOf<String, Any?>()
        for (option in what) {
            if (option !in GETINFO_OPTIONS) {
                throw LuaRuntimeException("bad argument #$optionIndex to 'getinfo' (invalid option)")
            }
            addFields(option, table)
        }
        return table
    }

    private fun rejectPrivateGetInfoOption(what: String, optionIndex: Int) {
        if (what.startsWith(">")) {
            throw LuaRuntimeException("bad argument #$optionIndex to 'getinfo' (invalid option '>')")
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
        val target = threadTarget(context)
        val index = requiredLocalIndex(context, target.argumentOffset + 2, "getlocal")
        val subjectIndex = target.argumentOffset + 1
        if (context.typeName(subjectIndex) == "function") {
            if (index <= 0) {
                return LuaReturn.of(null)
            }
            val info = context.getFunctionDebugInfo(subjectIndex) ?: return LuaReturn.of(null)
            return LuaReturn.of(info.parameterNames.getOrNull(index - 1))
        }
        val level = requiredStackLevel(context, subjectIndex, "getlocal")
        if (index == 0) {
            return LuaReturn.of(null)
        }
        if (level < 0) {
            throw levelOutOfRange(target.argumentOffset + 1, "getlocal")
        }
        val frame = target.frames.drop(level).firstOrNull()
            ?: throw levelOutOfRange(target.argumentOffset + 1, "getlocal")
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
        val target = threadTarget(context)
        val levelIndex = target.argumentOffset + 1
        val level = requiredStackLevel(context, levelIndex, "setlocal")
        val index = requiredLocalIndex(context, target.argumentOffset + 2, "setlocal")
        if (level < 0) {
            throw levelOutOfRange(levelIndex, "setlocal")
        }
        target.frames.drop(level).firstOrNull()
            ?: throw levelOutOfRange(levelIndex, "setlocal")
        requireValueArgument(context, target.argumentOffset + 3, "setlocal")
        if (index == 0) {
            return LuaReturn.of(null)
        }
        return LuaReturn.of(target.setLocal(context, level, index, context.getLuaValue(target.argumentOffset + 3)))
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
        val name = context.setUpvalue(1, index, context.getLuaValue(3)) ?: return LuaReturn.none()
        return LuaReturn.of(name)
    }

    private fun upvalueId(context: LuaCallContext): LuaReturn {
        val index = requiredUpvalueLookupIndex(context, 2, "upvalueid")
        requireFunction(context, 1, "upvalueid")
        if (index <= 0) {
            return LuaReturn.none()
        }
        val id = context.getUpvalueId(1, index)
            ?: return LuaReturn.none()
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

    private fun requiredStackLevel(context: LuaCallContext, index: Int, functionName: String): Int {
        return requiredNumberInteger(context, index, functionName).toInt()
    }

    private fun threadTarget(context: LuaCallContext): DebugThreadTarget {
        val coroutine = context.toUserData(1, LuaDebugThread::class.java)
            ?: return DebugThreadTarget.Current(context.luaFrames)
        return DebugThreadTarget.Coroutine(coroutine)
    }

    private fun levelOutOfRange(index: Int, functionName: String): LuaRuntimeException {
        return LuaRuntimeException("bad argument #$index to '$functionName' (level out of range)")
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

    private fun requiredNumberInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index) ?: throw LuaRuntimeException(
            if (context.toNumber(index) != null) {
                "bad argument #$index to '$functionName' (number has no integer representation)"
            } else {
                "bad argument #$index to '$functionName' (number expected)"
            },
        )
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
        val target = threadTarget(context)
        val hookIndex = target.argumentOffset + 1
        if (context.isNone(hookIndex) || context.isNil(hookIndex)) {
            target.setDebugHook(context, hookIndex, "", 0)
            return LuaReturn.of()
        }
        val mask = requiredString(context, target.argumentOffset + 2, "sethook")
        requireFunction(context, hookIndex, "sethook")
        val count = optionalInteger(context, target.argumentOffset + 3, 0, "sethook").toInt()
        target.setDebugHook(context, hookIndex, mask, count)
        return LuaReturn.of()
    }

    private fun getHook(context: LuaCallContext): LuaReturn {
        return threadTarget(context).getDebugHook(context)
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

        function debug.debug()
        end

        function debug.traceback(messageOrThread, messageOrLevel, level)
            if type(messageOrThread) == "thread" then
                return klua_debug_traceback(messageOrThread, messageOrLevel, level)
            end
            return klua_debug_traceback(messageOrThread, messageOrLevel)
        end

        function debug.getregistry()
            return klua_debug_registry
        end

        function debug.getinfo(threadOrLevel, levelOrWhat, what)
            if type(threadOrLevel) == "thread" then
                return klua_debug_getinfo(threadOrLevel, levelOrWhat, what)
            end
            return klua_debug_getinfo(threadOrLevel, levelOrWhat)
        end

        function debug.getlocal(threadOrLevel, levelOrIndex, index)
            if type(threadOrLevel) == "thread" then
                return klua_debug_getlocal(threadOrLevel, levelOrIndex, index)
            end
            return klua_debug_getlocal(threadOrLevel, levelOrIndex)
        end

        function debug.setlocal(threadOrLevel, levelOrIndex, ...)
            if type(threadOrLevel) == "thread" then
                return klua_debug_setlocal(threadOrLevel, levelOrIndex, ...)
            end
            return klua_debug_setlocal(threadOrLevel, levelOrIndex, ...)
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

        function debug.sethook(threadOrHook, hookOrMask, maskOrCount, count)
            if type(threadOrHook) == "thread" then
                return klua_debug_sethook(threadOrHook, hookOrMask, maskOrCount, count)
            end
            return klua_debug_sethook(threadOrHook, hookOrMask, maskOrCount)
        end

        function debug.gethook(thread)
            return klua_debug_gethook(thread)
        end
    """

    private const val GETINFO_OPTIONS = "flnSrtuL"
    private const val DEFAULT_GETINFO_OPTIONS = "flnSrtu"
    private const val VARARG_LOCAL_NAME = "(vararg)"
    private const val TRACEBACK_HEAD_LEVELS = 10
    private const val TRACEBACK_TAIL_LEVELS = 11

    private sealed class DebugThreadTarget(
        val argumentOffset: Int,
        val frames: List<LuaStackFrame>,
    ) {
        val isCurrentThread: Boolean
            get() = this is Current

        abstract fun setLocal(context: LuaCallContext, level: Int, index: Int, value: Any?): String?

        abstract fun setDebugHook(context: LuaCallContext, index: Int, mask: String, count: Int): Boolean

        abstract fun getDebugHook(context: LuaCallContext): LuaReturn

        class Current(frames: List<LuaStackFrame>) : DebugThreadTarget(0, frames) {
            override fun setLocal(context: LuaCallContext, level: Int, index: Int, value: Any?): String? {
                return context.setLocal(level, index, value)
            }

            override fun setDebugHook(context: LuaCallContext, index: Int, mask: String, count: Int): Boolean {
                return context.setDebugHook(index, mask, count)
            }

            override fun getDebugHook(context: LuaCallContext): LuaReturn {
                return context.getDebugHook()
            }
        }

        class Coroutine(private val coroutine: LuaDebugThread) : DebugThreadTarget(1, coroutine.luaFrames) {
            override fun setLocal(context: LuaCallContext, level: Int, index: Int, value: Any?): String? {
                return coroutine.setLocal(level, index, value)
            }

            override fun setDebugHook(context: LuaCallContext, index: Int, mask: String, count: Int): Boolean {
                return coroutine.setDebugHook(context.getLuaValue(index), mask, count)
            }

            override fun getDebugHook(context: LuaCallContext): LuaReturn {
                return coroutine.getDebugHook()
            }
        }
    }
}
