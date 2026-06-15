package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaStackFrame

internal interface LuaDebugThread {
    val luaFrames: List<LuaStackFrame>

    fun setLocal(level: Int, index: Int, value: Any?): String?
}
