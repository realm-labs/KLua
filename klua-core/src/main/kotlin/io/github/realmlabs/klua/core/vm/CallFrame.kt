package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.value.LuaValue

internal data class CallFrame(
    val prototype: Prototype,
    val varargs: List<LuaValue> = emptyList(),
    var pc: Int = 0,
    var openResultBase: Int = 0,
    var openResultCount: Int = 0,
    val base: Int = 0,
    val returnBase: Int = 0,
    val expectedResults: Int = -1,
)
