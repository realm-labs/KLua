package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState

internal object LuaUtf8Library {
    private const val MAX_CODE_POINT = 0x10FFFFL
    private const val HIGH_SURROGATE_START = 0xD800L
    private const val LOW_SURROGATE_END = 0xDFFFL
    private const val CHAR_PATTERN = "[\u0000-\u007F\u00C2-\u00FD][\u0080-\u00BF]*"

    fun open(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "char", ::utf8Char)
        setFunctionField(state, "codepoint", ::utf8Codepoint)
        setFunctionField(state, "codes", ::utf8Codes)
        setFunctionField(state, "len", ::utf8Len)
        setFunctionField(state, "offset", ::utf8Offset)
        state.pushString(CHAR_PATTERN)
        state.setField(-2, "charpattern")
        state.setGlobal("utf8")
        return state
    }

    private fun utf8Char(context: LuaCallContext): LuaReturn {
        val builder = StringBuilder()
        for (index in 1..context.argumentCount) {
            val codePoint = requiredCodePoint(context, index, "char")
            if (codePoint in HIGH_SURROGATE_START..LOW_SURROGATE_END) {
                builder.append(codePoint.toInt().toChar())
            } else {
                builder.appendCodePoint(codePoint.toInt())
            }
        }
        return LuaReturn.of(builder.toString())
    }

    private fun utf8Codepoint(context: LuaCallContext): LuaReturn {
        val codePoints = requiredString(context, 1, "codepoint").codePoints().toArray()
        val byteOffsets = utf8ByteOffsets(codePoints)
        val byteLength = utf8ByteLength(codePoints)
        val start = normalizedCodepointStart(context, 2, 1L, byteLength, "codepoint")
        val end = normalizedCodepointEnd(context, 3, start, byteLength, "codepoint")
        val strict = !optionalBoolean(context, 4)
        if (start > end) {
            return LuaReturn.none()
        }
        if (!isUtf8StartBytePosition(byteOffsets, start)) {
            throw LuaRuntimeException("invalid UTF-8 code")
        }
        return LuaReturn.ofValues(
            byteOffsets.mapIndexedNotNull { index, byteOffset ->
                if (byteOffset !in start..end) {
                    null
                } else {
                    val codePoint = codePoints[index]
                    if (strict && !isValidStrictUtf8CodePoint(codePoint)) {
                        throw LuaRuntimeException("invalid UTF-8 code")
                    }
                    codePoint.toLong()
                }
            },
        )
    }

    private fun utf8Codes(context: LuaCallContext): LuaReturn {
        val text = requiredString(context, 1, "codes")
        val strict = !optionalBoolean(context, 2)
        return LuaReturn.ofValues(listOf(LuaFunction { iteratorContext -> utf8CodesIterator(iteratorContext, strict) }, text, 0L))
    }

    private fun utf8CodesIterator(context: LuaCallContext, strict: Boolean): LuaReturn {
        val text = requiredString(context, 1, "codes iterator")
        val codePoints = text.codePoints().toArray()
        val byteOffsets = utf8ByteOffsets(codePoints)
        val control = context.toInteger(2) ?: 0L
        val nextIndex = nextCodePointIndex(byteOffsets, control) ?: return LuaReturn.none()
        if (strict && !isValidStrictUtf8CodePoint(codePoints[nextIndex])) {
            throw LuaRuntimeException("invalid UTF-8 code")
        }
        return LuaReturn.of(byteOffsets[nextIndex], codePoints[nextIndex].toLong())
    }

    private fun utf8Len(context: LuaCallContext): LuaReturn {
        val codePoints = requiredString(context, 1, "len").codePoints().toArray()
        val byteOffsets = utf8ByteOffsets(codePoints)
        val byteLength = utf8ByteLength(codePoints)
        val start = normalizedLenStart(context, 2, 1L, byteLength, "len")
        val end = normalizedLenEnd(context, 3, -1L, byteLength, "len")
        val strict = !optionalBoolean(context, 4)
        if (start > end) {
            return LuaReturn.of(0L)
        }
        if (!isUtf8StartBytePosition(byteOffsets, start)) {
            return LuaReturn.of(null, start)
        }
        var count = 0L
        for (index in byteOffsets.indices) {
            val byteOffset = byteOffsets[index]
            if (byteOffset in start..end) {
                if (strict && !isValidStrictUtf8CodePoint(codePoints[index])) {
                    return LuaReturn.of(null, byteOffset)
                }
                count++
            }
        }
        return LuaReturn.of(count)
    }

    private fun utf8Offset(context: LuaCallContext): LuaReturn {
        val codePoints = requiredString(context, 1, "offset").codePoints().toArray()
        val byteOffsets = utf8ByteOffsets(codePoints)
        val byteLength = utf8ByteLength(codePoints)
        val offset = requiredInteger(context, 2, "offset")
        val defaultPosition = if (offset < 0L) {
            byteLength + 1L
        } else {
            1L
        }
        val position = normalizedOffsetPosition(context, 3, defaultPosition, byteLength, "offset")
        val codePointIndex = codePointIndexAtOrContaining(byteOffsets, codePoints, position, byteLength)
            ?: return LuaReturn.of(null)
        if (offset == 0L) {
            return utf8OffsetResult(byteOffsets, codePoints, codePointIndex, byteLength)
        }
        if (codePointIndex < codePoints.size && position != byteOffsets[codePointIndex]) {
            throw LuaRuntimeException("bad argument #3 to 'offset' (initial position is a continuation byte)")
        }
        val targetIndex = when {
            offset > 0L -> codePointIndex + offset - 1L
            else -> codePointIndex + offset
        }
        if (targetIndex < 0L || targetIndex > codePoints.size.toLong()) {
            return LuaReturn.of(null)
        }
        return utf8OffsetResult(byteOffsets, codePoints, targetIndex.toInt(), byteLength)
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
            ?: if (context.toNumber(index) != null || context.typeName(index) == "number") {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number has no integer representation)")
            } else {
                throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
            }
    }

    private fun requiredNumberInteger(context: LuaCallContext, index: Int, functionName: String): Long {
        return context.toInteger(index) ?: throw LuaRuntimeException(
            if (context.toNumber(index) != null) {
                "bad argument #$index to '$functionName' (number has no integer representation)"
            } else {
                "bad argument #$index to '$functionName' (number expected)"
            },
        )
    }

    private fun requiredCodePoint(context: LuaCallContext, index: Int, functionName: String): Long {
        val codePoint = requiredNumberInteger(context, index, functionName)
        if (codePoint !in 0L..MAX_CODE_POINT) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (value out of range)")
        }
        return codePoint
    }

    private fun optionalBoolean(context: LuaCallContext, index: Int): Boolean {
        if (context.isNone(index) || context.isNil(index)) {
            return false
        }
        return context.get(index) != false
    }

    private fun normalizedCodepointStart(
        context: LuaCallContext,
        index: Int,
        defaultValue: Long,
        length: Long,
        functionName: String,
    ): Long {
        val position = if (context.isNone(index) || context.isNil(index)) {
            defaultValue
        } else {
            requiredNumberInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 1L) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (out of bounds)")
        }
        return normalized
    }

    private fun normalizedCodepointEnd(
        context: LuaCallContext,
        index: Int,
        defaultValue: Long,
        length: Long,
        functionName: String,
    ): Long {
        val position = if (context.isNone(index) || context.isNil(index)) {
            defaultValue
        } else {
            requiredNumberInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) (length + position + 1L).coerceAtLeast(0L) else position
        if (normalized < 0L || normalized > length) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (out of bounds)")
        }
        return normalized
    }

    private fun normalizedLenStart(
        context: LuaCallContext,
        index: Int,
        defaultValue: Long,
        length: Long,
        functionName: String,
    ): Long {
        val position = if (context.isNone(index) || context.isNil(index)) {
            defaultValue
        } else {
            requiredNumberInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 1L || normalized > length + 1L) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (initial position out of bounds)")
        }
        return normalized
    }

    private fun normalizedLenEnd(
        context: LuaCallContext,
        index: Int,
        defaultValue: Long,
        length: Long,
        functionName: String,
    ): Long {
        val position = if (context.isNone(index) || context.isNil(index)) {
            defaultValue
        } else {
            requiredNumberInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) (length + position + 1L).coerceAtLeast(0L) else position
        if (normalized < 0L || normalized > length) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (final position out of bounds)")
        }
        return normalized
    }

    private fun utf8ByteOffsets(codePoints: IntArray): List<Long> {
        val offsets = mutableListOf<Long>()
        var nextOffset = 1L
        for (codePoint in codePoints) {
            offsets += nextOffset
            nextOffset += utf8ByteLength(codePoint).toLong()
        }
        return offsets
    }

    private fun utf8ByteLength(codePoint: Int): Int {
        return when {
            codePoint <= 0x7F -> 1
            codePoint <= 0x7FF -> 2
            codePoint <= 0xFFFF -> 3
            else -> 4
        }
    }

    private fun isValidStrictUtf8CodePoint(codePoint: Int): Boolean {
        return codePoint.toLong() in 0L..MAX_CODE_POINT &&
            codePoint.toLong() !in HIGH_SURROGATE_START..LOW_SURROGATE_END
    }

    private fun utf8ByteLength(codePoints: IntArray): Long {
        var length = 0L
        for (codePoint in codePoints) {
            length += utf8ByteLength(codePoint).toLong()
        }
        return length
    }

    private fun codePointIndexAtOrContaining(
        byteOffsets: List<Long>,
        codePoints: IntArray,
        bytePosition: Long,
        byteLength: Long,
    ): Int? {
        if (bytePosition == byteLength + 1L) {
            return codePoints.size
        }
        for (index in byteOffsets.indices) {
            val start = byteOffsets[index]
            val end = start + utf8ByteLength(codePoints[index]).toLong()
            if (bytePosition in start until end) {
                return index
            }
        }
        return null
    }

    private fun isUtf8StartBytePosition(byteOffsets: List<Long>, bytePosition: Long): Boolean {
        return byteOffsets.any { byteOffset -> byteOffset == bytePosition }
    }

    private fun nextCodePointIndex(byteOffsets: List<Long>, control: Long): Int? {
        if (control < 0L) {
            return null
        }
        for (index in byteOffsets.indices) {
            if (byteOffsets[index] > control) {
                return index
            }
        }
        return null
    }

    private fun utf8OffsetResult(
        byteOffsets: List<Long>,
        codePoints: IntArray,
        codePointIndex: Int,
        byteLength: Long,
    ): LuaReturn {
        if (codePointIndex == codePoints.size) {
            return LuaReturn.of(byteLength + 1L, byteLength + 1L)
        }
        val start = byteOffsets[codePointIndex]
        val end = start + utf8ByteLength(codePoints[codePointIndex]).toLong() - 1L
        return LuaReturn.of(start, end)
    }

    private fun normalizedOffsetPosition(
        context: LuaCallContext,
        index: Int,
        defaultValue: Long,
        length: Long,
        functionName: String,
    ): Long {
        val position = if (context.isNone(index) || context.isNil(index)) {
            defaultValue
        } else {
            requiredNumberInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 1L || normalized > length + 1L) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (position out of bounds)")
        }
        return normalized
    }
}
