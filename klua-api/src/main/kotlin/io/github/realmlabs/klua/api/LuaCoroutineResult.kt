package io.github.realmlabs.klua.api

sealed interface LuaCoroutineResult {
    data class Returned(
        val values: List<Any?>,
    ) : LuaCoroutineResult

    data class Yielded(
        val values: List<Any?>,
    ) : LuaCoroutineResult

    data class RuntimeError(
        val message: String,
        val sourceName: String? = null,
        val line: Int? = null,
        val cause: Throwable? = null,
        val luaFrames: List<LuaStackFrame> = emptyList(),
    ) : LuaCoroutineResult {
        val traceback: String = formatLuaTraceback(message, luaFrames)
    }
}
