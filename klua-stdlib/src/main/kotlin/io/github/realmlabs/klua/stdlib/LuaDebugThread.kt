package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaStackFrame

internal interface LuaDebugThread {
    val luaFrames: List<LuaStackFrame>

    fun setLocal(level: Int, index: Int, value: Any?): String?

    fun setDebugHook(function: Any?, mask: String, count: Int): Boolean

    fun getDebugHook(): LuaReturn
}
