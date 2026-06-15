package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue

internal data class CallFrame(
    val prototype: Prototype,
    val function: LuaValue,
    val stack: LuaStack,
    val varargs: MutableList<LuaValue> = mutableListOf(),
    val upvalues: List<LuaUpvalue> = emptyList(),
    val globals: LuaTable,
    var pc: Int = 0,
    var openResultBase: Int = 0,
    var openResultCount: Int = 0,
    var pendingCallResultBase: Int = -1,
    var pendingCallExpectedResults: Int = -1,
    var pendingCallContinuation: LuaYieldContinuation? = null,
    var lastDebugHookLine: Int = -1,
    val base: Int = 0,
    val returnBase: Int = 0,
    val expectedResults: Int = -1,
)
