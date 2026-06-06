package io.github.realmlabs.klua.api

data class LuaFunctionDebugInfo(
    val sourceName: String,
    val lineDefined: Int,
    val lastLineDefined: Int,
    val upvalueCount: Int,
    val parameterCount: Int,
    val isVararg: Boolean,
    val activeLines: List<Int>,
)
