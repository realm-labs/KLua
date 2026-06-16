package io.github.realmlabs.klua.core.bytecode

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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
            BytecodePackageValidation.Invalid("unsupported KLua bytecode format version 2"),
            BytecodePackageValidator.validate(BytecodePackageHeader(formatVersion = 2)),
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
                0x01,
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
        bytes[7] = 2

        assertEquals(
            BytecodePackageHeaderDecode.Invalid("unsupported KLua bytecode format version 2"),
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
            BytecodePackageHeaderCodec.encode(BytecodePackageHeader(formatVersion = 2))
        }

        assertEquals("unsupported KLua bytecode format version 2", exception.message)
    }
}
