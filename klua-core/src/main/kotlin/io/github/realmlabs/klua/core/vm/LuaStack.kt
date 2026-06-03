package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaValue

internal class LuaStack(size: Int) {
    private val values = Array<LuaValue>(size.coerceAtLeast(1)) { LuaNil }

    fun get(index: Int): LuaValue {
        checkIndex(index)
        return values[index]
    }

    fun set(index: Int, value: LuaValue) {
        checkIndex(index)
        values[index] = value
    }

    fun copy(from: Int, to: Int) {
        set(to, get(from))
    }

    fun slice(start: Int, count: Int): List<LuaValue> {
        require(count >= 0) { "count must be non-negative" }
        return List(count) { offset -> get(start + offset) }
    }

    private fun checkIndex(index: Int) {
        require(index in values.indices) { "stack index out of range: $index" }
    }
}
