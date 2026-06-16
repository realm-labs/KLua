package io.github.realmlabs.klua.core.bytecode

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BytecodeInstructionStreamTest {
    @Test
    fun `encodes instruction stream deterministically`() {
        val code = intArrayOf(
            Instruction.abc(Opcode.LOAD_INT, a = 1, b = 254),
            Instruction.abc(Opcode.RETURN, a = 1, b = 1, c = 255),
        )

        assertContentEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x02,
                0x00, 0xfe.toByte(), 0x01, 0x02,
                0xff.toByte(), 0x01, 0x01, 0x2b,
            ),
            BytecodeInstructionStreamCodec.encode(code),
        )
    }

    @Test
    fun `decodes instruction stream and returns payload offset`() {
        val encoded = BytecodeInstructionStreamCodec.encode(
            intArrayOf(
                Instruction.abc(Opcode.LOAD_NIL, a = 0),
                Instruction.abc(Opcode.RETURN, a = 0, b = 1),
            ),
        )
        val bytes = byteArrayOf(0x7f) + encoded + byteArrayOf(0x01)

        val decoded = assertIs<BytecodeInstructionStreamDecode.Decoded>(
            BytecodeInstructionStreamCodec.decode(bytes, offset = 1),
        )

        assertContentEquals(
            intArrayOf(
                Instruction.abc(Opcode.LOAD_NIL, a = 0),
                Instruction.abc(Opcode.RETURN, a = 0, b = 1),
            ),
            decoded.code,
        )
        assertEquals(1 + encoded.size, decoded.nextOffset)
    }

    @Test
    fun `rejects invalid instruction stream offsets`() {
        assertEquals(
            BytecodeInstructionStreamDecode.Invalid("invalid KLua instruction stream offset -1"),
            BytecodeInstructionStreamCodec.decode(byteArrayOf(), offset = -1),
        )
    }

    @Test
    fun `rejects truncated instruction streams`() {
        assertEquals(
            BytecodeInstructionStreamDecode.Invalid("truncated KLua instruction stream"),
            BytecodeInstructionStreamCodec.decode(byteArrayOf(0x00, 0x00, 0x00)),
        )
    }

    @Test
    fun `rejects unsupported opcodes`() {
        assertEquals(
            BytecodeInstructionStreamDecode.Invalid("unsupported KLua opcode 99 at pc 0"),
            BytecodeInstructionStreamCodec.decode(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 99)),
        )
    }
}
