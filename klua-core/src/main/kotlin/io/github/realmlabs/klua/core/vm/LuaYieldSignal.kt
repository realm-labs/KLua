package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaValue

internal class LuaYieldSignal(
    val values: List<LuaValue>,
    val continuation: LuaYieldSignalContinuation? = null,
) : RuntimeException(null, null, false, false)

internal fun interface LuaYieldSignalContinuation {
    fun resume(arguments: List<LuaValue>): List<LuaValue>
}
