package io.github.realmlabs.klua.core.bytecode

import java.io.ByteArrayOutputStream

private const val BYTECODE_INSTRUCTION_INT_SIZE: Int = 4

internal sealed interface BytecodeInstructionStreamDecode {
    data class Decoded(
        val code: IntArray,
        val nextOffset: Int,
    ) : BytecodeInstructionStreamDecode {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return code.contentEquals(other.code) && nextOffset == other.nextOffset
        }

        override fun hashCode(): Int {
            var result = code.contentHashCode()
            result = 31 * result + nextOffset
            return result
        }
    }

    data class Invalid(val reason: String) : BytecodeInstructionStreamDecode
}

internal object BytecodeInstructionStreamCodec {
    fun encode(code: IntArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeInt(code.size)
        code.forEach { instruction -> output.writeInt(instruction) }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, offset: Int = 0): BytecodeInstructionStreamDecode {
        if (offset < 0 || offset > bytes.size) {
            return BytecodeInstructionStreamDecode.Invalid("invalid KLua instruction stream offset $offset")
        }

        var position = offset
        val count = readIntOrInvalid(bytes, position) ?: return BytecodeInstructionStreamDecode.Invalid(
            "truncated KLua instruction stream",
        )
        position += BYTECODE_INSTRUCTION_INT_SIZE
        if (count < 0) {
            return BytecodeInstructionStreamDecode.Invalid("invalid KLua instruction stream size $count")
        }

        val code = IntArray(count)
        repeat(count) { index ->
            val instruction = readIntOrInvalid(bytes, position)
                ?: return BytecodeInstructionStreamDecode.Invalid("truncated KLua instruction stream")
            val opcode = instruction and 0xff
            if (opcode >= Opcode.entries.size) {
                return BytecodeInstructionStreamDecode.Invalid("unsupported KLua opcode $opcode at pc $index")
            }
            code[index] = instruction
            position += BYTECODE_INSTRUCTION_INT_SIZE
        }

        return BytecodeInstructionStreamDecode.Decoded(code, position)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }

    private fun readIntOrInvalid(bytes: ByteArray, offset: Int): Int? {
        if (!hasBytes(bytes, offset, BYTECODE_INSTRUCTION_INT_SIZE)) return null
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private fun hasBytes(bytes: ByteArray, offset: Int, byteCount: Int): Boolean {
        return byteCount >= 0 && offset <= bytes.size && byteCount <= bytes.size - offset
    }
}
