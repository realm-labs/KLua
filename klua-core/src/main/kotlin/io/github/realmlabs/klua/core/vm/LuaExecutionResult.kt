package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaValue

internal sealed interface LuaExecutionResult {
    data class Returned(
        val values: List<LuaValue>,
    ) : LuaExecutionResult

    data class Yielded(
        val values: List<LuaValue>,
        val continuation: LuaYieldContinuation? = null,
    ) : LuaExecutionResult
}

internal fun interface LuaYieldContinuation {
    fun resume(arguments: List<LuaValue>): LuaExecutionResult
}
