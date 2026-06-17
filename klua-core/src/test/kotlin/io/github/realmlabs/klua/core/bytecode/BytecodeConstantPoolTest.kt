package io.github.realmlabs.klua.core.bytecode

import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNativeFunction
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.toLuaByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BytecodeConstantPoolTest {
    @Test
    fun `encodes constant pool deterministically`() {
        val constants = arrayOf(
            LuaNil,
            LuaBoolean(false),
            LuaBoolean(true),
            LuaInteger(-2),
            LuaFloat(1.5),
            LuaString("ok"),
        )

        assertContentEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x06,
                0x00,
                0x01, 0x00,
                0x01, 0x01,
                0x02,
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xfe.toByte(),
                0x03,
                0x3f, 0xf8.toByte(), 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x04,
                0x00, 0x00, 0x00, 0x02,
                0x6f, 0x6b,
            ),
            BytecodeConstantPoolCodec.encode(constants),
        )
    }

    @Test
    fun `decodes constant pool and returns payload offset`() {
        val encoded = BytecodeConstantPoolCodec.encode(
            arrayOf(
                LuaString("hello"),
                LuaInteger(42),
                LuaFloat(-0.0),
            ),
        )
        val bytes = byteArrayOf(0x7f) + encoded + byteArrayOf(0x01)

        val decoded = assertIs<BytecodeConstantPoolDecode.Decoded>(
            BytecodeConstantPoolCodec.decode(bytes, offset = 1),
        )

        assertContentEquals(
            arrayOf(LuaString("hello"), LuaInteger(42), LuaFloat(-0.0)),
            decoded.constants,
        )
        assertEquals(1 + encoded.size, decoded.nextOffset)
    }

    @Test
    fun `encodes string constants as lua raw bytes`() {
        val rawString = byteArrayOf(0, 255.toByte(), 195.toByte(), 169.toByte()).toLuaByteString()
        val encoded = BytecodeConstantPoolCodec.encode(arrayOf(LuaString(rawString)))

        val decoded = assertIs<BytecodeConstantPoolDecode.Decoded>(
            BytecodeConstantPoolCodec.decode(encoded),
        )

        assertContentEquals(arrayOf(LuaString(rawString)), decoded.constants)
    }

    @Test
    fun `rejects invalid constant pool offsets`() {
        assertEquals(
            BytecodeConstantPoolDecode.Invalid("invalid KLua constant pool offset -1"),
            BytecodeConstantPoolCodec.decode(byteArrayOf(), offset = -1),
        )
    }

    @Test
    fun `rejects truncated constant pools`() {
        assertEquals(
            BytecodeConstantPoolDecode.Invalid("truncated KLua constant pool"),
            BytecodeConstantPoolCodec.decode(byteArrayOf(0x00, 0x00, 0x00)),
        )
    }

    @Test
    fun `rejects unsupported constant tags`() {
        assertEquals(
            BytecodeConstantPoolDecode.Invalid("unsupported KLua constant tag 99 at index 0"),
            BytecodeConstantPoolCodec.decode(byteArrayOf(0x00, 0x00, 0x00, 0x01, 99)),
        )
    }

    @Test
    fun `rejects unsupported runtime values during encoding`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            BytecodeConstantPoolCodec.encode(arrayOf(LuaNativeFunction { emptyList() }))
        }

        assertEquals("cannot serialize KLua constant LuaNativeFunction", exception.message)
    }
}
