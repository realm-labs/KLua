package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaStackFrame
import io.github.realmlabs.klua.api.LuaState

internal object LuaDebugLibrary {
    fun open(state: LuaState): LuaState {
        state.register("klua_debug_traceback") { context -> LuaReturn.of(traceback(context)) }
        state.register("klua_debug_getinfo") { context -> getInfo(context) }
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
                append(':')
                append(frame.line)
            }
        }
    }

    private fun getInfo(context: LuaCallContext): LuaReturn {
        val level = context.toInteger(1)?.toInt()?.coerceAtLeast(0) ?: 1
        val frame = context.luaFrames.drop(level).firstOrNull() ?: return LuaReturn.of(null)
        return LuaReturn.of(
            mapOf(
                "what" to "Lua",
                "source" to frame.sourceName,
                "short_src" to frame.sourceName,
                "currentline" to frame.line.toLong(),
                "linedefined" to -1L,
                "lastlinedefined" to -1L,
                "namewhat" to "",
            ),
        )
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
    """
}
