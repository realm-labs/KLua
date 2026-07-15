package io.github.realmlabs.klua.api

interface LuaDebugThread {
    val luaFrames: List<LuaStackFrame>

    val isCurrentDebugThread: Boolean
        get() = false

    fun setLocal(level: Int, index: Int, value: Any?): String?

    fun setDebugHook(function: Any?, mask: String, count: Int): Boolean

    fun getDebugHook(): LuaReturn
}

class LuaMainThread internal constructor() : LuaTypedValue, LuaDebugThread {
    override val luaTypeName: String = "thread"

    override val luaFrames: List<LuaStackFrame> = emptyList()

    @Volatile
    override var isCurrentDebugThread: Boolean = true
        internal set

    override fun setLocal(level: Int, index: Int, value: Any?): String? = null

    override fun setDebugHook(function: Any?, mask: String, count: Int): Boolean = false

    override fun getDebugHook(): LuaReturn = LuaReturn.of(null)
}
