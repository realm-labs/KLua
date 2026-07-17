package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaValue
import io.github.realmlabs.klua.core.value.LuaValueTag
import java.util.RandomAccess

internal sealed interface LuaEphemeralArguments {
    fun snapshot(): List<LuaValue>
}

internal interface LuaTaggedArguments : List<LuaValue> {
    fun tagCode(index: Int): Int = valueTagCode(get(index))

    fun integerValue(index: Int): Long = (get(index) as LuaInteger).value

    fun floatValue(index: Int): Double = (get(index) as LuaFloat).value

    fun referenceValue(index: Int): LuaValue = get(index)
}

internal class LuaStackArguments(
    private val stack: LuaStack,
    private val start: Int,
    override val size: Int,
) : AbstractList<LuaValue>(), RandomAccess, LuaEphemeralArguments, LuaTaggedArguments {
    init {
        require(size >= 0) { "argument count must be non-negative" }
        require(start >= 0 && start <= stack.capacity && size <= stack.capacity - start) {
            "argument range exceeds the stack"
        }
    }

    override fun get(index: Int): LuaValue {
        checkElementIndex(index, size)
        return stack.get(start + index)
    }

    override fun snapshot(): List<LuaValue> = stack.snapshotResults(start, size)

    override fun tagCode(index: Int): Int {
        checkElementIndex(index, size)
        return stack.tagCode(start + index)
    }

    override fun integerValue(index: Int): Long {
        checkElementIndex(index, size)
        return stack.integerValue(start + index)
    }

    override fun floatValue(index: Int): Double {
        checkElementIndex(index, size)
        return stack.floatValue(start + index)
    }

    override fun referenceValue(index: Int): LuaValue {
        checkElementIndex(index, size)
        check(tagCode(index) == LuaValueTag.REFERENCE.ordinal) { "argument is not a reference value" }
        return stack.get(start + index)
    }
}

internal class LuaPrependedArguments(
    private val first: LuaValue,
    private val remaining: List<LuaValue>,
) : AbstractList<LuaValue>(), RandomAccess, LuaEphemeralArguments, LuaTaggedArguments {
    override val size: Int
        get() = remaining.size + 1

    override fun get(index: Int): LuaValue {
        checkElementIndex(index, size)
        return if (index == 0) first else remaining[index - 1]
    }

    override fun tagCode(index: Int): Int {
        checkElementIndex(index, size)
        return if (index == 0) valueTagCode(first) else taggedRemaining()?.tagCode(index - 1) ?: valueTagCode(get(index))
    }

    override fun integerValue(index: Int): Long {
        checkElementIndex(index, size)
        return if (index == 0) (first as LuaInteger).value else taggedRemaining()?.integerValue(index - 1)
            ?: (get(index) as LuaInteger).value
    }

    override fun floatValue(index: Int): Double {
        checkElementIndex(index, size)
        return if (index == 0) (first as LuaFloat).value else taggedRemaining()?.floatValue(index - 1)
            ?: (get(index) as LuaFloat).value
    }

    override fun referenceValue(index: Int): LuaValue {
        checkElementIndex(index, size)
        return if (index == 0) first else taggedRemaining()?.referenceValue(index - 1) ?: get(index)
    }

    override fun snapshot(): List<LuaValue> = List(size, ::get)

    private fun taggedRemaining(): LuaTaggedArguments? = remaining as? LuaTaggedArguments
}

internal class LuaFixedArguments private constructor(
    override val size: Int,
    private val first: LuaValue,
    private val second: LuaValue?,
    private val third: LuaValue?,
) : AbstractList<LuaValue>(), RandomAccess, LuaTaggedArguments {
    override fun get(index: Int): LuaValue {
        checkElementIndex(index, size)
        return when (index) {
            0 -> first
            1 -> checkNotNull(second)
            else -> checkNotNull(third)
        }
    }

    companion object {
        fun one(first: LuaValue): LuaFixedArguments = LuaFixedArguments(1, first, null, null)

        fun two(first: LuaValue, second: LuaValue): LuaFixedArguments {
            return LuaFixedArguments(2, first, second, null)
        }

        fun three(first: LuaValue, second: LuaValue, third: LuaValue): LuaFixedArguments {
            return LuaFixedArguments(3, first, second, third)
        }
    }
}

private fun checkElementIndex(index: Int, size: Int) {
    if (index !in 0 until size) {
        throw IndexOutOfBoundsException("index: $index, size: $size")
    }
}

private fun valueTagCode(value: LuaValue): Int {
    return when (value) {
        LuaNil -> LuaValueTag.NIL.ordinal
        is LuaBoolean -> if (value.value) LuaValueTag.TRUE.ordinal else LuaValueTag.FALSE.ordinal
        is LuaInteger -> LuaValueTag.INTEGER.ordinal
        is LuaFloat -> LuaValueTag.FLOAT.ordinal
        else -> LuaValueTag.REFERENCE.ordinal
    }
}
