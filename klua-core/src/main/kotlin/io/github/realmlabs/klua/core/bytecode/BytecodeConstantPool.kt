package io.github.realmlabs.klua.core.bytecode

import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaValue
import io.github.realmlabs.klua.core.value.luaRawBytes
import io.github.realmlabs.klua.core.value.toLuaByteString
import java.io.ByteArrayOutputStream

private const val CONSTANT_TAG_NIL: Int = 0
private const val CONSTANT_TAG_BOOLEAN: Int = 1
private const val CONSTANT_TAG_INTEGER: Int = 2
private const val CONSTANT_TAG_FLOAT: Int = 3
private const val CONSTANT_TAG_STRING: Int = 4
private const val BYTECODE_INT_SIZE: Int = 4
private const val BYTECODE_LONG_SIZE: Int = 8

internal sealed interface BytecodeConstantPoolDecode {
    data class Decoded(
        val constants: Array<LuaValue>,
        val nextOffset: Int,
    ) : BytecodeConstantPoolDecode

    data class Invalid(val reason: String) : BytecodeConstantPoolDecode
}

internal object BytecodeConstantPoolCodec {
    fun encode(constants: Array<LuaValue>): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeInt(constants.size)
        constants.forEach { constant -> output.writeConstant(constant) }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, offset: Int = 0): BytecodeConstantPoolDecode {
        if (offset < 0 || offset > bytes.size) {
            return BytecodeConstantPoolDecode.Invalid("invalid KLua constant pool offset $offset")
        }

        var position = offset
        val count = readIntOrInvalid(bytes, position) ?: return BytecodeConstantPoolDecode.Invalid(
            "truncated KLua constant pool",
        )
        position += BYTECODE_INT_SIZE
        if (count < 0) {
            return BytecodeConstantPoolDecode.Invalid("invalid KLua constant pool size $count")
        }

        val constants = ArrayList<LuaValue>(count)
        repeat(count) { index ->
            if (!hasBytes(bytes, position, 1)) {
                return BytecodeConstantPoolDecode.Invalid("truncated KLua constant pool")
            }

            val tag = bytes[position].toInt() and 0xff
            position += 1
            when (tag) {
                CONSTANT_TAG_NIL -> constants += LuaNil
                CONSTANT_TAG_BOOLEAN -> {
                    if (!hasBytes(bytes, position, 1)) {
                        return BytecodeConstantPoolDecode.Invalid("truncated KLua constant pool")
                    }
                    val value = when (val raw = bytes[position].toInt() and 0xff) {
                        0 -> false
                        1 -> true
                        else -> return BytecodeConstantPoolDecode.Invalid(
                            "invalid KLua boolean constant value $raw at index $index",
                        )
                    }
                    position += 1
                    constants += LuaBoolean(value)
                }
                CONSTANT_TAG_INTEGER -> {
                    val value = readLongOrInvalid(bytes, position)
                        ?: return BytecodeConstantPoolDecode.Invalid("truncated KLua constant pool")
                    position += BYTECODE_LONG_SIZE
                    constants += LuaInteger(value)
                }
                CONSTANT_TAG_FLOAT -> {
                    val bits = readLongOrInvalid(bytes, position)
                        ?: return BytecodeConstantPoolDecode.Invalid("truncated KLua constant pool")
                    position += BYTECODE_LONG_SIZE
                    constants += LuaFloat(Double.fromBits(bits))
                }
                CONSTANT_TAG_STRING -> {
                    val length = readIntOrInvalid(bytes, position)
                        ?: return BytecodeConstantPoolDecode.Invalid("truncated KLua constant pool")
                    position += BYTECODE_INT_SIZE
                    if (length < 0) {
                        return BytecodeConstantPoolDecode.Invalid(
                            "invalid KLua string constant length $length at index $index",
                        )
                    }
                    if (!hasBytes(bytes, position, length)) {
                        return BytecodeConstantPoolDecode.Invalid("truncated KLua constant pool")
                    }
                    constants += LuaString(bytes.copyOfRange(position, position + length).toLuaByteString())
                    position += length
                }
                else -> return BytecodeConstantPoolDecode.Invalid(
                    "unsupported KLua constant tag $tag at index $index",
                )
            }
        }

        return BytecodeConstantPoolDecode.Decoded(constants.toTypedArray(), position)
    }

    private fun ByteArrayOutputStream.writeConstant(value: LuaValue) {
        when (value) {
            LuaNil -> write(CONSTANT_TAG_NIL)
            is LuaBoolean -> {
                write(CONSTANT_TAG_BOOLEAN)
                write(if (value.value) 1 else 0)
            }
            is LuaInteger -> {
                write(CONSTANT_TAG_INTEGER)
                writeLong(value.value)
            }
            is LuaFloat -> {
                write(CONSTANT_TAG_FLOAT)
                writeLong(value.value.toRawBits())
            }
            is LuaString -> {
                val bytes = value.value.luaRawBytes()
                write(CONSTANT_TAG_STRING)
                writeInt(bytes.size)
                write(bytes)
            }
            else -> throw IllegalArgumentException("cannot serialize KLua constant ${value::class.simpleName}")
        }
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }

    private fun ByteArrayOutputStream.writeLong(value: Long) {
        write((value ushr 56).toInt())
        write((value ushr 48).toInt())
        write((value ushr 40).toInt())
        write((value ushr 32).toInt())
        write((value ushr 24).toInt())
        write((value ushr 16).toInt())
        write((value ushr 8).toInt())
        write(value.toInt())
    }

    private fun readIntOrInvalid(bytes: ByteArray, offset: Int): Int? {
        if (!hasBytes(bytes, offset, BYTECODE_INT_SIZE)) return null
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private fun readLongOrInvalid(bytes: ByteArray, offset: Int): Long? {
        if (!hasBytes(bytes, offset, BYTECODE_LONG_SIZE)) return null
        var value = 0L
        repeat(BYTECODE_LONG_SIZE) { index ->
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
        }
        return value
    }

    private fun hasBytes(bytes: ByteArray, offset: Int, byteCount: Int): Boolean {
        return byteCount >= 0 && offset <= bytes.size && byteCount <= bytes.size - offset
    }
}
