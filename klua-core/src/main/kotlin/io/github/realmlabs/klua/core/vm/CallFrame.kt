package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Prototype

internal data class CallFrame(
    val prototype: Prototype,
    var pc: Int = 0,
    val base: Int = 0,
    val returnBase: Int = 0,
    val expectedResults: Int = -1,
)
