package io.github.realmlabs.klua.core.bytecode

import io.github.realmlabs.klua.core.value.LuaString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BytecodePackageTest {
    @Test
    fun `accepts current bytecode package header`() {
        assertEquals(
            BytecodePackageValidation.Valid,
            BytecodePackageValidator.validate(BytecodePackageHeader()),
        )
    }

    @Test
    fun `accepts bytecode package header without debug info flag`() {
        assertEquals(
            BytecodePackageValidation.Valid,
            BytecodePackageValidator.validate(BytecodePackageHeader(flags = 0)),
        )
    }

    @Test
    fun `rejects unsupported bytecode magic`() {
        assertEquals(
            BytecodePackageValidation.Invalid("unsupported KLua bytecode magic 'Lua'"),
            BytecodePackageValidator.validate(BytecodePackageHeader(magic = "Lua")),
        )
    }

    @Test
    fun `rejects unsupported bytecode format versions`() {
        assertEquals(
            BytecodePackageValidation.Invalid("unsupported KLua bytecode format version 3"),
            BytecodePackageValidator.validate(BytecodePackageHeader(formatVersion = 3)),
        )
    }

    @Test
    fun `rejects unsupported source language markers`() {
        assertEquals(
            BytecodePackageValidation.Invalid("unsupported KLua source language marker 'Lua54'"),
            BytecodePackageValidator.validate(BytecodePackageHeader(sourceLanguage = "Lua54")),
        )
    }

    @Test
    fun `rejects unsupported bytecode flags`() {
        assertEquals(
            BytecodePackageValidation.Invalid("unsupported KLua bytecode flags 0x2"),
            BytecodePackageValidator.validate(BytecodePackageHeader(flags = 2)),
        )
    }

    @Test
    fun `rejects invalid payload metadata`() {
        assertEquals(
            BytecodePackageValidation.Invalid("invalid KLua bytecode payload size -1"),
            BytecodePackageValidator.validate(BytecodePackageHeader(payloadSize = -1)),
        )
        assertEquals(
            BytecodePackageValidation.Invalid("invalid KLua bytecode payload checksum 0x100000000"),
            BytecodePackageValidator.validate(BytecodePackageHeader(payloadChecksum = 0x1_0000_0000L)),
        )
    }

    @Test
    fun `builds bytecode package header for payload checksums`() {
        assertEquals(
            BytecodePackageHeader(
                flags = 0,
                payloadSize = 9,
                payloadChecksum = 0xcbf4_3926L,
            ),
            BytecodePackageHeader.forPayload("123456789".encodeToByteArray(), flags = 0),
        )
    }

    @Test
    fun `validates bytecode package payload checksums`() {
        val payload = "123456789".encodeToByteArray()
        val header = BytecodePackageHeader.forPayload(payload)

        assertEquals(
            BytecodePackageValidation.Valid,
            BytecodePackagePayloadValidator.validate(header, payload, offset = 0),
        )
    }

    @Test
    fun `rejects truncated bytecode package payloads`() {
        assertEquals(
            BytecodePackageValidation.Invalid("truncated KLua bytecode payload"),
            BytecodePackagePayloadValidator.validate(
                BytecodePackageHeader(payloadSize = 2, payloadChecksum = 0),
                byteArrayOf(0x01),
                offset = 0,
            ),
        )
    }

    @Test
    fun `payload validator rejects invalid payload metadata`() {
        assertEquals(
            BytecodePackageValidation.Invalid("invalid KLua bytecode payload size -1"),
            BytecodePackagePayloadValidator.validate(
                BytecodePackageHeader(payloadSize = -1),
                byteArrayOf(),
                offset = 0,
            ),
        )
    }

    @Test
    fun `rejects mismatched bytecode package payload checksums`() {
        assertEquals(
            BytecodePackageValidation.Invalid(
                "KLua bytecode payload checksum mismatch: expected 0x0, got 0xa505df1b",
            ),
            BytecodePackagePayloadValidator.validate(
                BytecodePackageHeader(payloadSize = 1, payloadChecksum = 0),
                byteArrayOf(0x01),
                offset = 0,
            ),
        )
    }

    @Test
    fun `encodes current bytecode package header deterministically`() {
        assertContentEquals(
            byteArrayOf(
                0x4b,
                0x4c,
                0x75,
                0x61,
                0x00,
                0x00,
                0x00,
                0x02,
                0x00,
                0x00,
                0x00,
                0x05,
                0x4c,
                0x75,
                0x61,
                0x35,
                0x35,
                0x00,
                0x00,
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            ),
            BytecodePackageHeaderCodec.encode(),
        )
    }

    @Test
    fun `decodes bytecode package header and returns payload offset`() {
        val encoded = BytecodePackageHeaderCodec.encode(BytecodePackageHeader(flags = 0))
        val bytes = encoded + byteArrayOf(0x7f)

        val decoded = assertIs<BytecodePackageHeaderDecode.Decoded>(
            BytecodePackageHeaderCodec.decode(bytes),
        )

        assertEquals(BytecodePackageHeader(flags = 0), decoded.header)
        assertEquals(encoded.size, decoded.nextOffset)
    }

    @Test
    fun `decodes bytecode package header from nonzero offset`() {
        val encoded = BytecodePackageHeaderCodec.encode()
        val prefix = byteArrayOf(0x00, 0x01)

        val decoded = assertIs<BytecodePackageHeaderDecode.Decoded>(
            BytecodePackageHeaderCodec.decode(prefix + encoded, offset = prefix.size),
        )

        assertEquals(BytecodePackageHeader(), decoded.header)
        assertEquals(prefix.size + encoded.size, decoded.nextOffset)
    }

    @Test
    fun `decode rejects unsupported encoded header values`() {
        val encoded = BytecodePackageHeaderCodec.encode()
        val bytes = encoded.copyOf()
        bytes[7] = 3

        assertEquals(
            BytecodePackageHeaderDecode.Invalid("unsupported KLua bytecode format version 3"),
            BytecodePackageHeaderCodec.decode(bytes),
        )
    }

    @Test
    fun `decode rejects truncated headers`() {
        assertEquals(
            BytecodePackageHeaderDecode.Invalid("truncated KLua bytecode header"),
            BytecodePackageHeaderCodec.decode(byteArrayOf(0x4b, 0x4c, 0x75)),
        )
    }

    @Test
    fun `encode rejects unsupported header values`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            BytecodePackageHeaderCodec.encode(BytecodePackageHeader(formatVersion = 3))
        }

        assertEquals("unsupported KLua bytecode format version 3", exception.message)
    }

    @Test
    fun `encodes and decodes bytecode packages with prototype payloads`() {
        val prototype = Prototype(
            sourceName = "package.lua",
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, a = 0, b = 0),
                Instruction.abc(Opcode.RETURN, a = 0, b = 1),
            ),
            constants = arrayOf(LuaString("ok")),
            lineInfo = intArrayOf(1, 1),
            maxStackSize = 1,
        )

        val bytes = BytecodePackageCodec.encode(prototype, flags = 0)
        val decoded = assertIs<BytecodePackageDecode.Decoded>(BytecodePackageCodec.decode(bytes))

        assertEquals(prototype, decoded.prototype)
        assertEquals(0, decoded.header.flags)
        assertEquals(bytes.size - BytecodePackageHeaderCodec.encode(decoded.header).size, decoded.header.payloadSize)
    }

    @Test
    fun `package decode rejects checksum mismatches`() {
        val bytes = BytecodePackageCodec.encode(
            Prototype(
                sourceName = "checksum.lua",
                code = intArrayOf(Instruction.abc(Opcode.RETURN, a = 0, b = 0)),
                constants = emptyArray(),
                maxStackSize = 1,
            ),
        )
        val corrupted = bytes.copyOf()
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()

        assertIs<BytecodePackageDecode.Invalid>(BytecodePackageCodec.decode(corrupted)).also { invalid ->
            assertTrue(invalid.reason.startsWith("KLua bytecode payload checksum mismatch"))
        }
    }

    @Test
    fun `package decode rejects trailing bytes`() {
        val bytes = BytecodePackageCodec.encode(
            Prototype(
                sourceName = "trailing.lua",
                code = intArrayOf(Instruction.abc(Opcode.RETURN, a = 0, b = 0)),
                constants = emptyArray(),
                maxStackSize = 1,
            ),
        )

        assertEquals(
            BytecodePackageDecode.Invalid("trailing KLua bytecode package bytes"),
            BytecodePackageCodec.decode(bytes + byteArrayOf(0x00)),
        )
    }
}
