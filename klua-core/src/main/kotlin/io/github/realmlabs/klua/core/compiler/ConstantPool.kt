package io.github.realmlabs.klua.core.compiler

import io.github.realmlabs.klua.core.value.LuaValue

internal class ConstantPool {
    private val constants = mutableListOf<LuaValue>()

    fun add(value: LuaValue): Int {
        val existing = constants.indexOf(value)
        if (existing >= 0) {
            return existing
        }

        constants += value
        return constants.lastIndex
    }

    fun toArray(): Array<LuaValue> = constants.toTypedArray()
}
