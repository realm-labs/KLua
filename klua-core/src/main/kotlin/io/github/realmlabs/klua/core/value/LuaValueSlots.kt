package io.github.realmlabs.klua.core.value

internal enum class LuaValueTag {
    NIL,
    FALSE,
    TRUE,
    INTEGER,
    FLOAT,
    REFERENCE,
}

/** Internal TValue-like storage. Primitive payloads remain unboxed until a generic boundary reads them. */
internal open class LuaValueSlots(size: Int) {
    private var tags = ByteArray(size.coerceAtLeast(1))
    private var payloads = LongArray(tags.size)
    private var references: Array<LuaValue?>? = null

    val slotCapacity: Int
        get() = tags.size

    fun rawValue(index: Int): LuaValue {
        checkIndex(index)
        return when (tags[index].toInt()) {
            LuaValueTag.NIL.ordinal -> LuaNil
            LuaValueTag.FALSE.ordinal -> LuaBoolean(false)
            LuaValueTag.TRUE.ordinal -> LuaBoolean(true)
            LuaValueTag.INTEGER.ordinal -> LuaInteger(payloads[index])
            LuaValueTag.FLOAT.ordinal -> LuaFloat(Double.fromBits(payloads[index]))
            LuaValueTag.REFERENCE.ordinal -> checkNotNull(references?.get(index))
            else -> error("invalid slot tag")
        }
    }

    fun rawSet(index: Int, value: LuaValue) {
        ensureSlot(index)
        when (value) {
            LuaNil -> rawSetNil(index)
            is LuaBoolean -> rawSetBoolean(index, value.value)
            is LuaInteger -> rawSetInteger(index, value.value)
            is LuaFloat -> rawSetFloat(index, value.value)
            else -> rawSetReference(index, value)
        }
    }

    fun rawSetNil(index: Int) {
        preparePrimitive(index, LuaValueTag.NIL)
    }

    fun rawSetBoolean(index: Int, value: Boolean) {
        preparePrimitive(index, if (value) LuaValueTag.TRUE else LuaValueTag.FALSE)
    }

    fun rawSetInteger(index: Int, value: Long) {
        preparePrimitive(index, LuaValueTag.INTEGER)
        payloads[index] = value
    }

    fun rawSetFloat(index: Int, value: Double) {
        preparePrimitive(index, LuaValueTag.FLOAT)
        payloads[index] = value.toRawBits()
    }

    fun rawSetReference(index: Int, value: LuaValue) {
        ensureSlot(index)
        setRawTag(index, LuaValueTag.REFERENCE)
        payloads[index] = 0L
        val activeReferences = references
            ?: arrayOfNulls<LuaValue>(slotCapacity).also { created -> references = created }
        activeReferences[index] = value
    }

    fun rawCopy(from: Int, to: Int) {
        checkIndex(from)
        ensureSlot(to)
        tags[to] = tags[from]
        payloads[to] = payloads[from]
        copyReference(from, this, to)
    }

    fun rawCopyTo(from: Int, target: LuaValueSlots, to: Int = 0) {
        checkIndex(from)
        target.ensureSlot(to)
        target.tags[to] = tags[from]
        target.payloads[to] = payloads[from]
        copyReference(from, target, to)
    }

    fun rawTag(index: Int): LuaValueTag {
        return LuaValueTag.entries[rawTagCode(index)]
    }

    fun rawTagCode(index: Int): Int {
        checkIndex(index)
        return tags[index].toInt()
    }

    fun integerAt(index: Int): Long {
        checkIndex(index)
        return payloads[index]
    }

    fun floatAt(index: Int): Double {
        checkIndex(index)
        return Double.fromBits(payloads[index])
    }

    fun rawTruthy(index: Int): Boolean {
        val tag = rawTagCode(index)
        return tag != LuaValueTag.NIL.ordinal && tag != LuaValueTag.FALSE.ordinal
    }

    fun heapValueOrNull(index: Int): LuaValue? {
        return if (rawTag(index) == LuaValueTag.REFERENCE) checkNotNull(references?.get(index)) else null
    }

    fun forEachReference(action: (LuaValue) -> Unit) {
        for (index in 0 until slotCapacity) {
            if (tags[index].toInt() == LuaValueTag.REFERENCE.ordinal) {
                action(checkNotNull(references?.get(index)))
            }
        }
    }

    fun ensureSlot(index: Int) {
        require(index >= 0) { "slot index out of range: $index" }
        if (index < slotCapacity) {
            return
        }
        val minimumSize = index + 1
        val grownSize = maxOf(minimumSize, slotCapacity + (slotCapacity shr 1))
        tags = tags.copyOf(grownSize)
        payloads = payloads.copyOf(grownSize)
        references = references?.copyOf(grownSize)
    }

    protected fun ensureSlotCapacity(requiredCapacity: Int) {
        if (requiredCapacity <= slotCapacity) {
            return
        }
        tags = tags.copyOf(requiredCapacity)
        payloads = payloads.copyOf(requiredCapacity)
        references = references?.copyOf(requiredCapacity)
    }

    private fun preparePrimitive(index: Int, tag: LuaValueTag) {
        ensureSlot(index)
        setRawTag(index, tag)
        payloads[index] = 0L
        references?.set(index, null)
    }

    private fun copyReference(from: Int, target: LuaValueSlots, to: Int) {
        if (tags[from].toInt() == LuaValueTag.REFERENCE.ordinal) {
            val targetReferences = target.references
                ?: arrayOfNulls<LuaValue>(target.slotCapacity).also { created -> target.references = created }
            targetReferences[to] = checkNotNull(references?.get(from))
        } else {
            target.references?.set(to, null)
        }
    }

    private fun checkIndex(index: Int) {
        require(index in 0 until slotCapacity) { "slot index out of range: $index" }
    }

    private fun setRawTag(index: Int, tag: LuaValueTag) {
        tags[index] = tag.ordinal.toByte()
    }
}
