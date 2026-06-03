package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState

internal object LuaUtf8Library {
    private const val MAX_CODE_POINT = 0x10FFFFL
    private const val HIGH_SURROGATE_START = 0xD800L
    private const val LOW_SURROGATE_END = 0xDFFFL
    private const val CHAR_PATTERN = "[%z\u0001-\u007F\u00C2-\u00FD][\u0080-\u00BF]*"

    fun open(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "char", ::utf8Char)
        setFunctionField(state, "codepoint", ::utf8Codepoint)
        setFunctionField(state, "len", ::utf8Len)
        state.pushString(CHAR_PATTERN)
        state.setField(-2, "charpattern")
        state.setGlobal("utf8")
        return state
    }

    private fun utf8Char(context: LuaCallContext): LuaReturn {
        val builder = StringBuilder()
        for (index in 1..context.argumentCount) {
            val codePoint = requiredCodePoint(context, index, "utf8.char")
            builder.appendCodePoint(codePoint.toInt())
        }
        return LuaReturn.of(builder.toString())
    }

    private fun utf8Codepoint(context: LuaCallContext): LuaReturn {
        val codePoints = requiredString(context, 1, "utf8.codepoint").codePoints().toArray()
        val start = normalizedPosition(context, 2, 1L, codePoints.size, "utf8.codepoint")
        val end = normalizedPosition(context, 3, start.toLong(), codePoints.size, "utf8.codepoint")
        if (start > end) {
            return LuaReturn.none()
        }
        return LuaReturn.ofValues((start..end).map { index -> codePoints[index - 1].toLong() })
    }

    private fun utf8Len(context: LuaCallContext): LuaReturn {
        val codePoints = requiredString(context, 1, "utf8.len").codePoints().toArray()
        val start = normalizedPosition(context, 2, 1L, codePoints.size, "utf8.len")
        val end = normalizedPosition(context, 3, -1L, codePoints.size, "utf8.len")
        if (start > end) {
            return LuaReturn.of(0L)
        }
        return LuaReturn.of((end - start + 1).toLong())
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function)
        state.setField(-2, name)
    }

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return context.toString(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
    }

    private fun requiredInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
    }

    private fun requiredCodePoint(context: LuaCallContext, index: Int, functionName: String): Long {
        val codePoint = requiredInteger(context, index, functionName)
        if (codePoint !in 0L..MAX_CODE_POINT || codePoint in HIGH_SURROGATE_START..LOW_SURROGATE_END) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (value out of range)")
        }
        return codePoint
    }

    private fun normalizedPosition(
        context: LuaCallContext,
        index: Int,
        defaultValue: Long,
        length: Int,
        functionName: String,
    ): Int {
        val position = if (context.isNone(index) || context.isNil(index)) {
            defaultValue
        } else {
            requiredInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 1L || normalized > length.toLong()) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (position out of range)")
        }
        return normalized.toInt()
    }
}
