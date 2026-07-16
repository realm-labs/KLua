package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaDebugThread
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaFunctionDebugInfo
import io.github.realmlabs.klua.api.LuaLocalVariable
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaStackFrame
import io.github.realmlabs.klua.api.LuaState

internal object LuaDebugLibrary {
    private val debugFunction = LuaFunction { LuaReturn.none() }
    private val tracebackFunction = LuaFunction { context -> traceback(context) }
    private val getInfoFunction = LuaFunction { context -> getInfo(context) }
    private val getLocalFunction = LuaFunction { context -> getLocal(context) }
    private val setLocalFunction = LuaFunction { context -> setLocal(context) }
    private val getUpvalueFunction = LuaFunction { context -> getUpvalue(context) }
    private val setupUpvalueFunction = LuaFunction { context -> setupUpvalue(context) }
    private val upvalueIdFunction = LuaFunction { context -> upvalueId(context) }
    private val upvalueJoinFunction = LuaFunction { context -> upvalueJoin(context) }
    private val getUserValueFunction = LuaFunction { context -> getUserValue(context) }
    private val setUserValueFunction = LuaFunction { context -> setUserValue(context) }
    private val getMetatableFunction = LuaFunction { context -> getMetatable(context) }
    private val setMetatableFunction = LuaFunction { context -> setMetatable(context) }
    private val setHookFunction = LuaFunction { context -> setHook(context) }
    private val getHookFunction = LuaFunction { context -> getHook(context) }

    fun open(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "debug", debugFunction)
        setFunctionField(state, "getuservalue", getUserValueFunction)
        setFunctionField(state, "gethook", getHookFunction)
        setFunctionField(state, "getinfo", getInfoFunction)
        setFunctionField(state, "getlocal", getLocalFunction)
        state.pushRegistryGetterFunction()
        state.setField(-2, "getregistry")
        setFunctionField(state, "getmetatable", getMetatableFunction)
        setFunctionField(state, "getupvalue", getUpvalueFunction)
        setFunctionField(state, "upvaluejoin", upvalueJoinFunction)
        setFunctionField(state, "upvalueid", upvalueIdFunction)
        setFunctionField(state, "setuservalue", setUserValueFunction)
        setFunctionField(state, "sethook", setHookFunction)
        setFunctionField(state, "setlocal", setLocalFunction)
        setFunctionField(state, "setmetatable", setMetatableFunction)
        setFunctionField(state, "setupvalue", setupUpvalueFunction)
        setFunctionField(state, "traceback", tracebackFunction)
        state.setGlobal("debug")
        return state
    }

    private fun setFunctionField(state: LuaState, name: String, function: LuaFunction) {
        state.pushFunction(function)
        state.setField(-2, name)
    }

    private fun traceback(context: LuaCallContext): LuaReturn {
        val target = threadTarget(context, tracebackFunction)
        val message = when {
            context.isNil(target.argumentOffset + 1) || context.isNone(target.argumentOffset + 1) -> null
            else -> context.toString(target.argumentOffset + 1)?.toLuaCString()
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
        val shortSource = luaShortSourceName(frame.sourceName)
        append(shortSource)
        if (frame.line > 0) {
            append(':')
            append(frame.line)
        }
        append(": in ")
        append(tracebackFunctionName(frame, shortSource))
        if (frame.isTailCall) {
            append("\n\t(...tail calls...)")
        }
    }

    private fun tracebackFunctionName(frame: LuaStackFrame, shortSource: String): String {
        if (!frame.isTailCall && frame.callSiteName != null && frame.callSiteNameWhat.isNotEmpty()) {
            return "${frame.callSiteNameWhat} '${frame.callSiteName}'"
        }
        if (frame.lineDefined == 0) {
            return "main chunk"
        }
        val globalName = frame.globalFunctionName
        if (globalName != null) {
            return "function '$globalName'"
        }
        return if (frame.lineDefined > 0) {
            "function <$shortSource:${frame.lineDefined}>"
        } else {
            "?"
        }
    }

    private fun getInfo(context: LuaCallContext): LuaReturn {
        val target = threadTarget(context, getInfoFunction)
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
                'n' -> {
                    if (!frame.isTailCall && frame.callSiteName != null) {
                        table["name"] = frame.callSiteName
                    }
                    table["namewhat"] = if (frame.isTailCall) "" else frame.callSiteNameWhat
                }
                'u' -> {
                    table["nups"] = frame.upvalueCount.toLong()
                    table["nparams"] = frame.parameterCount.toLong()
                    table["isvararg"] = frame.isVararg
                }
                'f' -> table["func"] = frame.function
                'r' -> addTransferInfo(table, frame.transferStart, frame.transferCount)
                't' -> addTailCallInfo(table, frame.isTailCall, frame.extraArgumentCount)
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

    private fun addTransferInfo(table: MutableMap<String, Any?>, transferStart: Int = 0, transferCount: Int = 0) {
        table["ftransfer"] = transferStart.toLong()
        table["ntransfer"] = transferCount.toLong()
    }

    private fun addTailCallInfo(
        table: MutableMap<String, Any?>,
        isTailCall: Boolean = false,
        extraArgumentCount: Int = 0,
    ) {
        table["istailcall"] = isTailCall
        table["extraargs"] = extraArgumentCount.toLong()
    }

    private fun luaFunctionWhat(lineDefined: Int): String = when {
        lineDefined < 0 -> "C"
        lineDefined == 0 -> "main"
        else -> "Lua"
    }

    private fun getLocal(context: LuaCallContext): LuaReturn {
        val target = threadTarget(context, getLocalFunction)
        val index = requiredLocalIndex(context, target.argumentOffset + 2, "getlocal")
        val subjectIndex = target.argumentOffset + 1
        if (context.typeName(subjectIndex) == "function") {
            if (index <= 0) {
                return LuaReturn.of(null)
            }
            val info = context.getFunctionDebugInfo(subjectIndex) ?: return LuaReturn.of(null)
            return LuaReturn.of(info.localNames.getOrNull(index - 1))
        }
        val level = requiredStackLevel(context, subjectIndex, "getlocal")
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
        val target = threadTarget(context, setLocalFunction)
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
            return LuaReturn.of(null)
        }
        val id = context.getUpvalueId(1, index)
            ?: return LuaReturn.of(null)
        return LuaReturn.of(id)
    }

    private fun upvalueJoin(context: LuaCallContext): LuaReturn {
        val targetIndex = requiredPositiveUpvalueIndex(context, 2, "upvaluejoin")
        requireFunction(context, 1, "upvaluejoin")
        val sourceIndex = requiredPositiveUpvalueIndex(context, 4, "upvaluejoin")
        requireFunction(context, 3, "upvaluejoin")
        if (context.getUpvalueId(1, targetIndex) == null) {
            throw LuaRuntimeException("bad argument #2 to 'upvaluejoin' (invalid upvalue index)")
        }
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

    private fun threadTarget(context: LuaCallContext, currentFunction: LuaFunction? = null): DebugThreadTarget {
        val coroutine = context.toUserData(1, LuaDebugThread::class.java)
            ?: return DebugThreadTarget.Current(
                context.luaFrames,
                currentFunction,
                context.callSiteName,
                context.callSiteNameWhat,
                nativeArguments(context, currentFunction, 0),
            )
        if (coroutine.isCurrentDebugThread) {
            return DebugThreadTarget.Current(
                context.luaFrames,
                currentFunction,
                context.callSiteName,
                context.callSiteNameWhat,
                nativeArguments(context, currentFunction, 1),
                argumentOffset = 1,
            )
        }
        return DebugThreadTarget.Coroutine(coroutine)
    }

    private fun nativeArguments(
        context: LuaCallContext,
        currentFunction: LuaFunction?,
        argumentOffset: Int,
    ): List<Any?> {
        val count = if (currentFunction === setLocalFunction) {
            minOf(context.argumentCount, argumentOffset + 3)
        } else {
            context.argumentCount
        }
        return (1..count).map { index -> context.getLuaValue(index) }
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
        (context.getLuaValue(1) as? LuaStdlibStringValue)?.disableLuaToStringFallback()
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
                ?.toLuaCString()
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

    private const val GETINFO_OPTIONS = "flnSrtuL"
    private const val DEFAULT_GETINFO_OPTIONS = "flnSrtu"
    private const val VARARG_LOCAL_NAME = "(vararg)"
    private const val C_TEMPORARY_LOCAL_NAME = "(C temporary)"
    private const val TRACEBACK_HEAD_LEVELS = 10
    private const val TRACEBACK_TAIL_LEVELS = 11

    private fun String.toLuaCString(): String = substringBefore('\u0000')

    private fun selectDebugFrames(frames: List<LuaStackFrame>): DebugFrameSelection {
        if (frames.none { frame -> frame.isTailCall }) {
            return DebugFrameSelection(frames, frames.indices.toList())
        }
        val visibleFrames = ArrayList<LuaStackFrame>(frames.size)
        val rawLevels = ArrayList<Int>(frames.size)
        var index = 0
        while (index < frames.size) {
            val frame = frames[index]
            visibleFrames += frame
            rawLevels += index
            index += 1
            var replacedCaller = frame.isTailCall
            while (replacedCaller && index < frames.size) {
                replacedCaller = frames[index].isTailCall
                index += 1
            }
        }
        return DebugFrameSelection(visibleFrames, rawLevels)
    }

    private fun currentDebugFrames(
        frames: List<LuaStackFrame>,
        currentFunction: LuaFunction?,
        callSiteName: String?,
        callSiteNameWhat: String,
        nativeArguments: List<Any?>,
    ): DebugFrameSelection {
        val selected = selectDebugFrames(frames)
        if (currentFunction == null) {
            return selected
        }
        return DebugFrameSelection(
            listOf(
                nativeDebugFrame(currentFunction, callSiteName, callSiteNameWhat, nativeArguments),
            ) + selected.frames,
            listOf(-1) + selected.rawLevels,
        )
    }

    private data class DebugFrameSelection(
        val frames: List<LuaStackFrame>,
        val rawLevels: List<Int>,
    )

    private sealed class DebugThreadTarget(
        val argumentOffset: Int,
        selection: DebugFrameSelection,
    ) {
        val frames: List<LuaStackFrame> = selection.frames
        private val rawLevels: List<Int> = selection.rawLevels

        val isCurrentThread: Boolean
            get() = this is Current

        protected fun selectedRawLevel(level: Int): Int? = rawLevels.getOrNull(level)

        protected fun rawLevel(level: Int): Int? = rawLevels.getOrNull(level)?.takeIf { it >= 0 }

        abstract fun setLocal(context: LuaCallContext, level: Int, index: Int, value: Any?): String?

        abstract fun setDebugHook(context: LuaCallContext, index: Int, mask: String, count: Int): Boolean

        abstract fun getDebugHook(context: LuaCallContext): LuaReturn

        class Current(
            frames: List<LuaStackFrame>,
            private val currentFunction: LuaFunction? = null,
            callSiteName: String? = null,
            callSiteNameWhat: String = "",
            nativeArguments: List<Any?> = emptyList(),
            argumentOffset: Int = 0,
        ) : DebugThreadTarget(
            argumentOffset,
            currentDebugFrames(frames, currentFunction, callSiteName, callSiteNameWhat, nativeArguments),
        ) {
            override fun setLocal(context: LuaCallContext, level: Int, index: Int, value: Any?): String? {
                if (selectedRawLevel(level) == -1) {
                    return if (index > 0) frames[level].locals.getOrNull(index - 1)?.name else null
                }
                val rawLevel = rawLevel(level) ?: return null
                return context.setLocal(rawLevel, index, value)
            }

            override fun setDebugHook(context: LuaCallContext, index: Int, mask: String, count: Int): Boolean {
                return context.setDebugHook(index, mask, count)
            }

            override fun getDebugHook(context: LuaCallContext): LuaReturn {
                return context.getDebugHook()
            }
        }

        class Coroutine(private val coroutine: LuaDebugThread) : DebugThreadTarget(
            1,
            selectDebugFrames(coroutine.luaFrames),
        ) {
            override fun setLocal(context: LuaCallContext, level: Int, index: Int, value: Any?): String? {
                val rawLevel = rawLevel(level) ?: return null
                return coroutine.setLocal(rawLevel, index, value)
            }

            override fun setDebugHook(context: LuaCallContext, index: Int, mask: String, count: Int): Boolean {
                return coroutine.setDebugHook(context.getLuaValue(index), mask, count)
            }

            override fun getDebugHook(context: LuaCallContext): LuaReturn {
                return coroutine.getDebugHook()
            }
        }
    }

    private fun nativeDebugFrame(
        function: LuaFunction,
        callSiteName: String?,
        callSiteNameWhat: String,
        arguments: List<Any?>,
    ): LuaStackFrame {
        return LuaStackFrame(
            sourceName = "=[C]",
            line = -1,
            lineDefined = -1,
            lastLineDefined = -1,
            isVararg = true,
            function = function,
            locals = arguments.map { value -> LuaLocalVariable(C_TEMPORARY_LOCAL_NAME, value) },
            callSiteName = callSiteName,
            callSiteNameWhat = callSiteNameWhat,
        )
    }
}
