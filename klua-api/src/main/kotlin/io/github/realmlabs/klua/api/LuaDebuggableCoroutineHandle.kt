package io.github.realmlabs.klua.api

interface LuaDebuggableCoroutineHandle : LuaCoroutineHandle {
    val luaFrames: List<LuaStackFrame>
        get() = emptyList()

    fun setLocal(level: Int, index: Int, value: Any?): String? = null

    fun setDebugHook(function: Any?, mask: String, count: Int): Boolean = false

    fun getDebugHook(): LuaReturn = LuaReturn.of(null)

    fun setDebugObserver(observer: LuaDebugObserver?): Boolean = false
}

enum class LuaDebugEvent {
    LINE,
}

fun interface LuaDebugObserver {
    fun shouldSuspend(event: LuaDebugEvent, sourceId: String, line: Int, callDepth: Int): Boolean
}
