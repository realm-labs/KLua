package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaValue

internal class LuaYieldSignal(
    val values: List<LuaValue>,
) : RuntimeException(null, null, false, false)
