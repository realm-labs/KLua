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
    private const val CHAR_PATTERN = "[%z\u0001-\u007F\u00C2-\u00FD][\u0080-\u00BF]*"

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
            val codePoint = requiredCodePoint(context, index, "utf8.char")
            builder.appendCodePoint(codePoint.toInt())
        }
        return LuaReturn.of(builder.toString())
    }

    private fun utf8Codepoint(context: LuaCallContext): LuaReturn {
        val codePoints = requiredString(context, 1, "utf8.codepoint").codePoints().toArray()
        val byteOffsets = utf8ByteOffsets(codePoints)
        val byteLength = utf8ByteLength(codePoints)
        val start = normalizedCodepointStart(context, 2, 1L, byteLength, "utf8.codepoint")
        val end = normalizedCodepointEnd(context, 3, start, byteLength, "utf8.codepoint")
        if (start > end) {
            return LuaReturn.none()
        }
        if (!isUtf8StartBytePosition(byteOffsets, start)) {
            throw LuaRuntimeException("invalid UTF-8 code")
        }
        return LuaReturn.ofValues(
            byteOffsets.mapIndexedNotNull { index, byteOffset ->
                if (byteOffset in start..end) codePoints[index].toLong() else null
            },
        )
    }

    private fun utf8Codes(context: LuaCallContext): LuaReturn {
        val codePoints = requiredString(context, 1, "utf8.codes").codePoints().toArray()
        val byteOffsets = utf8ByteOffsets(codePoints)
        var index = 0
        val iterator = LuaFunction {
            if (index >= codePoints.size) {
                LuaReturn.of(null)
            } else {
                index++
                LuaReturn.of(byteOffsets[index - 1], codePoints[index - 1].toLong())
            }
        }
        return LuaReturn.of(iterator)
    }

    private fun utf8Len(context: LuaCallContext): LuaReturn {
        val codePoints = requiredString(context, 1, "utf8.len").codePoints().toArray()
        val byteOffsets = utf8ByteOffsets(codePoints)
        val byteLength = utf8ByteLength(codePoints)
        val start = normalizedLenStart(context, 2, 1L, byteLength, "utf8.len")
        val end = normalizedLenEnd(context, 3, -1L, byteLength, "utf8.len")
        if (start > end) {
            return LuaReturn.of(0L)
        }
        if (!isUtf8StartBytePosition(byteOffsets, start)) {
            return LuaReturn.of(null, start)
        }
        val truncatedStart = truncatedCodePointStart(codePoints, byteOffsets, start, end)
        if (truncatedStart != null) {
            return LuaReturn.of(null, truncatedStart)
        }
        return LuaReturn.of(byteOffsets.count { byteOffset -> byteOffset in start..end }.toLong())
    }

    private fun utf8Offset(context: LuaCallContext): LuaReturn {
        val codePoints = requiredString(context, 1, "utf8.offset").codePoints().toArray()
        val byteOffsets = utf8ByteOffsets(codePoints)
        val byteLength = utf8ByteLength(codePoints)
        val offset = requiredInteger(context, 2, "utf8.offset")
        val defaultPosition = if (offset < 0L) {
            byteLength + 1L
        } else {
            1L
        }
        val position = normalizedOffsetPosition(context, 3, defaultPosition, byteLength, "utf8.offset")
        val codePointIndex = codePointIndexAtOrContaining(byteOffsets, codePoints, position, byteLength)
            ?: return LuaReturn.of(null)
        if (offset == 0L) {
            return utf8OffsetResult(byteOffsets, codePoints, codePointIndex, byteLength)
        }
        if (codePointIndex < codePoints.size && position != byteOffsets[codePointIndex]) {
            throw LuaRuntimeException("bad argument #3 to 'utf8.offset' (initial position is a continuation byte)")
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
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (number expected)")
    }

    private fun requiredCodePoint(context: LuaCallContext, index: Int, functionName: String): Long {
        val codePoint = requiredInteger(context, index, functionName)
        if (codePoint !in 0L..MAX_CODE_POINT || codePoint in HIGH_SURROGATE_START..LOW_SURROGATE_END) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (value out of range)")
        }
        return codePoint
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
            requiredInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 1L) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (position out of range)")
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
            requiredInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 0L || normalized > length) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (position out of range)")
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
            requiredInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 1L || normalized > length + 1L) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (position out of range)")
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
            requiredInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 0L || normalized > length) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (position out of range)")
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

    private fun truncatedCodePointStart(
        codePoints: IntArray,
        byteOffsets: List<Long>,
        start: Long,
        end: Long,
    ): Long? {
        for (index in codePoints.indices) {
            val byteOffset = byteOffsets[index]
            if (byteOffset !in start..end) {
                continue
            }
            val byteEnd = byteOffset + utf8ByteLength(codePoints[index]).toLong() - 1L
            if (byteEnd > end) {
                return byteOffset
            }
        }
        return null
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
            requiredInteger(context, index, functionName)
        }
        val normalized = if (position < 0L) length + position + 1L else position
        if (normalized < 1L || normalized > length + 1L) {
            throw LuaRuntimeException("bad argument #$index to '$functionName' (position out of range)")
        }
        return normalized
    }
}
