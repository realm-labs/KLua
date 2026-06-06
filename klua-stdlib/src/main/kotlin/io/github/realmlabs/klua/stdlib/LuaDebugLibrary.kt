package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunctionDebugInfo
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaStackFrame
import io.github.realmlabs.klua.api.LuaState

internal object LuaDebugLibrary {
    fun open(state: LuaState): LuaState {
        state.register("klua_debug_traceback") { context -> LuaReturn.of(traceback(context)) }
        state.register("klua_debug_getinfo") { context -> getInfo(context) }
        state.register("klua_debug_getlocal") { context -> getLocal(context) }
        state.register("klua_debug_setlocal") { context -> setLocal(context) }
        state.register("klua_debug_getupvalue") { context -> getUpvalue(context) }
        state.register("klua_debug_setupvalue") { context -> setupUpvalue(context) }
        state.register("klua_debug_sethook") { context -> setHook(context) }
        state.register("klua_debug_gethook") { context -> context.getDebugHook() }
        installLuaSource(state, DEBUG_SOURCE, "stdlib-debug.lua")
        return state
    }

    private fun traceback(context: LuaCallContext): String {
        val message = if (context.isNil(1) || context.isNone(1)) {
            null
        } else {
            context.toString(1)
        }
        val level = context.toInteger(2)?.toInt()?.coerceAtLeast(0) ?: 1
        return formatTraceback(message, context.luaFrames.drop(level))
    }

    private fun formatTraceback(message: String?, frames: List<LuaStackFrame>): String {
        return buildString {
            if (message != null) {
                append(message)
                append('\n')
            }
            append("stack traceback:")
            for (frame in frames) {
                append("\n\t")
                append(frame.sourceName)
                if (frame.line > 0) {
                    append(':')
                    append(frame.line)
                }
            }
        }
    }

    private fun getInfo(context: LuaCallContext): LuaReturn {
        if (context.typeName(1) == "function") {
            val info = context.getFunctionDebugInfo(1)
            val what = context.toString(2) ?: DEFAULT_GETINFO_OPTIONS
            return LuaReturn.of(functionInfoTable(info, what, context.getLuaValue(1)))
        }
        val what = context.toString(2) ?: DEFAULT_GETINFO_OPTIONS
        val level = context.toInteger(1)?.toInt()?.coerceAtLeast(0) ?: 1
        val frame = context.luaFrames.drop(level).firstOrNull() ?: return LuaReturn.of(null)
        return LuaReturn.of(frameInfoTable(frame, what))
    }

    private fun frameInfoTable(frame: LuaStackFrame, what: String): Map<String, Any?> {
        return debugInfoTable(what) { option, table ->
            when (option) {
                'S' -> {
                    table["what"] = "Lua"
                    table["source"] = frame.sourceName
                    table["short_src"] = frame.sourceName
                    table["linedefined"] = frame.lineDefined.toLong()
                    table["lastlinedefined"] = frame.lastLineDefined.toLong()
                }
                'l' -> table["currentline"] = frame.line.toLong()
                'n' -> table["namewhat"] = ""
            }
        }
    }

    private fun functionInfoTable(info: LuaFunctionDebugInfo?, what: String, function: Any?): Map<String, Any?> {
        if (info == null) {
            return debugInfoTable(what) { option, table ->
                when (option) {
                    'S' -> {
                        table["what"] = "Java"
                        table["source"] = "[Java]"
                        table["short_src"] = "[Java]"
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
                }
            }
        }
        return debugInfoTable(what) { option, table ->
            when (option) {
                'S' -> {
                    table["what"] = "Lua"
                    table["source"] = info.sourceName
                    table["short_src"] = info.sourceName
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
            }
        }
    }

    private fun debugInfoTable(
        what: String,
        addFields: (option: Char, table: MutableMap<String, Any?>) -> Unit,
    ): Map<String, Any?> {
        val table = linkedMapOf<String, Any?>()
        for (option in what) {
            addFields(option, table)
        }
        return table
    }

    private fun getLocal(context: LuaCallContext): LuaReturn {
        val level = context.toInteger(1)?.toInt()?.coerceAtLeast(0) ?: 1
        val index = context.toInteger(2)?.toInt() ?: return LuaReturn.of(null)
        if (index <= 0) {
            return LuaReturn.of(null)
        }
        val frame = context.luaFrames.drop(level).firstOrNull() ?: return LuaReturn.of(null)
        val local = frame.locals.getOrNull(index - 1) ?: return LuaReturn.of(null)
        return LuaReturn.of(local.name, local.value)
    }

    private fun setLocal(context: LuaCallContext): LuaReturn {
        val level = context.toInteger(1)?.toInt()?.coerceAtLeast(0) ?: 1
        val index = context.toInteger(2)?.toInt() ?: return LuaReturn.of(null)
        if (index <= 0) {
            return LuaReturn.of(null)
        }
        return LuaReturn.of(context.setLocal(level, index, context.get(3)))
    }

    private fun getUpvalue(context: LuaCallContext): LuaReturn {
        val index = context.toInteger(2)?.toInt() ?: return LuaReturn.of(null)
        if (index <= 0) {
            return LuaReturn.of(null)
        }
        return context.getUpvalue(1, index) ?: LuaReturn.of(null)
    }

    private fun setupUpvalue(context: LuaCallContext): LuaReturn {
        val index = context.toInteger(2)?.toInt() ?: return LuaReturn.of(null)
        if (index <= 0) {
            return LuaReturn.of(null)
        }
        return LuaReturn.of(context.setUpvalue(1, index, context.get(3)))
    }

    private fun setHook(context: LuaCallContext): LuaReturn {
        val mask = context.toString(2) ?: ""
        if (mask.any { event -> event !in "crl" }) {
            throw LuaRuntimeException("bad argument #2 to 'debug.sethook' (invalid hook mask)")
        }
        val count = context.toInteger(3)?.toInt() ?: 0
        context.setDebugHook(1, mask, count)
        return LuaReturn.of()
    }

    private const val DEBUG_SOURCE: String = """
        debug = debug or {}

        local function klua_debug_tostring(value)
            if _G ~= nil and _G.tostring ~= nil then
                return _G.tostring(value)
            end
            return value
        end

        function debug.traceback(message, level)
            if message == nil then
                return klua_debug_traceback(nil, level)
            end
            return klua_debug_traceback(klua_debug_tostring(message), level)
        end

        function debug.getinfo(threadOrLevel, what)
            return klua_debug_getinfo(threadOrLevel, what)
        end

        function debug.getlocal(threadOrLevel, index)
            return klua_debug_getlocal(threadOrLevel, index)
        end

        function debug.setlocal(threadOrLevel, index, value)
            return klua_debug_setlocal(threadOrLevel, index, value)
        end

        function debug.getupvalue(func, index)
            return klua_debug_getupvalue(func, index)
        end

        function debug.setupvalue(func, index, value)
            return klua_debug_setupvalue(func, index, value)
        end

        function debug.sethook(hook, mask, count)
            return klua_debug_sethook(hook, mask, count)
        end

        function debug.gethook()
            return klua_debug_gethook()
        end
    """

    private const val DEFAULT_GETINFO_OPTIONS = "nSl"
}
