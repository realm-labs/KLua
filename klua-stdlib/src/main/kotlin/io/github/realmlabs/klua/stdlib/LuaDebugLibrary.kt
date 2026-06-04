package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaState

internal object LuaDebugLibrary {
    fun open(state: LuaState): LuaState {
        installLuaSource(state, DEBUG_SOURCE, "stdlib-debug.lua")
        return state
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
                return "stack traceback:"
            end
            return klua_debug_tostring(message) .. "\nstack traceback:"
        end

        function debug.getinfo(threadOrLevel, what)
            return {
                what = "Lua",
                source = "=[KLua]",
                short_src = "=[KLua]",
                currentline = -1,
                linedefined = -1,
                lastlinedefined = -1,
                namewhat = "",
            }
        end
    """
}
