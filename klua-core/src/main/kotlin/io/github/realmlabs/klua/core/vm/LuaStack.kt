package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue
import io.github.realmlabs.klua.core.value.LuaValueSlots
import io.github.realmlabs.klua.core.value.LuaValueTag

internal open class LuaStack(size: Int, reservedSlots: Int = 0) :
    LuaValueSlots(size.coerceAtLeast(1) + reservedSlots) {
    private var captures: MutableMap<Int, LuaUpvalue>? = null

    protected open val logicalCapacity: Int
        get() = slotCapacity

    val capacity: Int
        get() = logicalCapacity

    fun get(index: Int): LuaValue {
        return captures?.get(index)?.value ?: rawValue(index)
    }

    fun set(index: Int, value: LuaValue) {
        ensureIndex(index)
        rawSet(index, value)
        captures?.get(index)?.value = value
    }

    fun setNil(index: Int) {
        ensureIndex(index)
        rawSetNil(index)
        captures?.get(index)?.setNil()
    }

    fun setBoolean(index: Int, value: Boolean) {
        ensureIndex(index)
        rawSetBoolean(index, value)
        captures?.get(index)?.setBoolean(value)
    }

    fun setInteger(index: Int, value: Long) {
        ensureIndex(index)
        rawSetInteger(index, value)
        captures?.get(index)?.setInteger(value)
    }

    fun setFloat(index: Int, value: Double) {
        ensureIndex(index)
        rawSetFloat(index, value)
        captures?.get(index)?.setFloat(value)
    }

    fun tagAt(index: Int): LuaValueTag {
        return captures?.get(index)?.tag ?: rawTag(index)
    }

    fun tagCode(index: Int): Int {
        return captures?.get(index)?.tag?.ordinal ?: rawTagCode(index)
    }

    fun integerValue(index: Int): Long {
        return captures?.get(index)?.integerValue() ?: integerAt(index)
    }

    fun floatValue(index: Int): Double {
        return captures?.get(index)?.floatValue() ?: floatAt(index)
    }

    fun numberValue(index: Int): Double {
        return when (tagCode(index)) {
            LuaValueTag.INTEGER.ordinal -> integerValue(index).toDouble()
            LuaValueTag.FLOAT.ordinal -> floatValue(index)
            else -> throw IllegalArgumentException("stack slot does not contain a number: $index")
        }
    }

    fun isTruthy(index: Int): Boolean {
        return captures?.get(index)?.isTruthy() ?: rawTruthy(index)
    }

    fun copy(from: Int, to: Int) {
        checkIndex(from)
        ensureIndex(to)
        val sourceCapture = captures?.get(from)
        if (sourceCapture != null) {
            sourceCapture.copyTo(this, to)
        } else {
            rawCopy(from, to)
        }
        captures?.get(to)?.copyFrom(this, to)
    }

    fun copyFrom(source: LuaStack, from: Int, to: Int) {
        ensureIndex(to)
        source.copyToSlots(from, this, to)
        captures?.get(to)?.copyFrom(this, to)
    }

    fun copyFrom(upvalue: LuaUpvalue, to: Int) {
        ensureIndex(to)
        upvalue.copyTo(this, to)
        captures?.get(to)?.copyFrom(this, to)
    }

    fun copyTo(from: Int, upvalue: LuaUpvalue) {
        checkIndex(from)
        val capture = captures?.get(from)
        if (capture != null) {
            capture.copyTo(upvalue)
        } else {
            upvalue.copyFrom(this, from)
        }
    }

    internal fun copyToSlots(from: Int, target: LuaValueSlots, to: Int) {
        checkIndex(from)
        val capture = captures?.get(from)
        if (capture != null) {
            capture.copyTo(target, to)
        } else {
            rawCopyTo(from, target, to)
        }
    }

    internal fun copyFromSlots(source: LuaValueSlots, from: Int, to: Int) {
        ensureIndex(to)
        source.rawCopyTo(from, this, to)
        captures?.get(to)?.copyFrom(this, to)
    }

    internal fun ensureStackIndex(index: Int) {
        ensureIndex(index)
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

    fun forEachHeapValue(action: (LuaValue) -> Unit) {
        for (index in 0 until capacity) {
            val capture = captures?.get(index)
            if (capture != null) {
                capture.forEachHeapValue(action)
            } else {
                heapValueOrNull(index)?.let(action)
            }
        }
    }

    fun capture(index: Int): LuaUpvalue {
        checkIndex(index)
        val openCaptures = captures ?: mutableMapOf<Int, LuaUpvalue>().also { created -> captures = created }
        return openCaptures.getOrPut(index) {
            LuaUpvalue(LuaNil).also { upvalue -> upvalue.copyFrom(this, index) }
        }
    }

    fun closeCapturesFrom(index: Int) {
        val openCaptures = captures ?: return
        openCaptures.forEach { (slotIndex, upvalue) ->
            if (slotIndex >= index) {
                upvalue.copyTo(this, slotIndex)
            }
        }
        openCaptures.keys.removeIf { it >= index }
        if (openCaptures.isEmpty()) {
            captures = null
        }
    }

    private fun checkIndex(index: Int) {
        require(index in 0 until capacity) { "stack index out of range: $index" }
    }

    private fun ensureIndex(index: Int) {
        require(index >= 0) { "stack index out of range: $index" }
        if (index >= capacity) {
            growStack(index)
        }
    }

    protected open fun growStack(index: Int) {
        val grownSize = grownStackCapacity(index)
        ensureSlot(grownSize - 1)
    }

    protected fun grownStackCapacity(index: Int): Int =
        maxOf(index + 1, capacity + (capacity shr 1))
}
