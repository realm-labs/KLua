package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue

internal class LuaStack(size: Int) {
    private val values = MutableList<LuaValue>(size.coerceAtLeast(1)) { LuaNil }
    private val captures = mutableMapOf<Int, LuaUpvalue>()

    fun get(index: Int): LuaValue {
        checkIndex(index)
        return values[index]
    }

    fun set(index: Int, value: LuaValue) {
        ensureIndex(index)
        values[index] = value
        captures[index]?.value = value
    }

    fun copy(from: Int, to: Int) {
        set(to, get(from))
    }

    fun slice(start: Int, count: Int): List<LuaValue> {
        require(count >= 0) { "count must be non-negative" }
        return List(count) { offset -> get(start + offset) }
    }

    fun capture(index: Int): LuaUpvalue {
        checkIndex(index)
        return captures.getOrPut(index) { LuaUpvalue(values[index]) }
    }

    private fun checkIndex(index: Int) {
        require(index in values.indices) { "stack index out of range: $index" }
    }

    private fun ensureIndex(index: Int) {
        require(index >= 0) { "stack index out of range: $index" }
        while (index >= values.size) {
            values += LuaNil
        }
    }
}
