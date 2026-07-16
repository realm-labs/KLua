package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue

internal open class LuaStack(size: Int) {
    private var values = Array<LuaValue>(size.coerceAtLeast(1)) { LuaNil }
    private var captures: MutableMap<Int, LuaUpvalue>? = null

    fun get(index: Int): LuaValue {
        checkIndex(index)
        return captures?.get(index)?.value ?: values[index]
    }

    fun set(index: Int, value: LuaValue) {
        ensureIndex(index)
        values[index] = value
        captures?.get(index)?.value = value
    }

    fun copy(from: Int, to: Int) {
        set(to, get(from))
    }

    fun slice(start: Int, count: Int): List<LuaValue> {
        require(count >= 0) { "count must be non-negative" }
        return List(count) { offset -> get(start + offset) }
    }

    fun snapshotResults(start: Int, count: Int): List<LuaValue> {
        require(count >= 0) { "count must be non-negative" }
        return when (count) {
            0 -> emptyList()
            1 -> listOf(get(start))
            else -> List(count) { offset -> get(start + offset) }
        }
    }

    fun snapshotValues(): List<LuaValue> = values.toList()

    fun capture(index: Int): LuaUpvalue {
        checkIndex(index)
        val openCaptures = captures ?: mutableMapOf<Int, LuaUpvalue>().also { created -> captures = created }
        return openCaptures.getOrPut(index) { LuaUpvalue(values[index]) }
    }

    fun closeCapturesFrom(index: Int) {
        val openCaptures = captures ?: return
        openCaptures.keys.removeIf { it >= index }
        if (openCaptures.isEmpty()) {
            captures = null
        }
    }

    private fun checkIndex(index: Int) {
        require(index in values.indices) { "stack index out of range: $index" }
    }

    private fun ensureIndex(index: Int) {
        require(index >= 0) { "stack index out of range: $index" }
        if (index >= values.size) {
            val oldValues = values
            val minimumSize = index + 1
            val grownSize = maxOf(minimumSize, oldValues.size + (oldValues.size shr 1))
            values = Array(grownSize) { position ->
                if (position < oldValues.size) oldValues[position] else LuaNil
            }
        }
    }
}
