package io.github.realmlabs.klua.api

interface LuaDebuggableCoroutineHandle : LuaCoroutineHandle {
    val luaFrames: List<LuaStackFrame>
        get() = emptyList()

    fun setLocal(level: Int, index: Int, value: Any?): String? = null
}
