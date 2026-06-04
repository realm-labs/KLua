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
    ) : LuaCoroutineResult
}
