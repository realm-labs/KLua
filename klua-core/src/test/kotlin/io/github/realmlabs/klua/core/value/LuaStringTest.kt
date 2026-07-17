package io.github.realmlabs.klua.core.value

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class LuaStringTest {
    @Test
    fun `owns exact bytes and exposes only defensive copies`() {
        val source = byteArrayOf(0, 0x80.toByte(), 0xC3.toByte(), 0xA9.toByte(), 0xFF.toByte())
        val value = LuaString(source)
        source.fill(1)

        val first = value.copyRawBytes()
        val second = value.copyRawBytes()

        assertContentEquals(byteArrayOf(0, 0x80.toByte(), 0xC3.toByte(), 0xA9.toByte(), 0xFF.toByte()), first)
        assertNotSame(first, second)
        first.fill(2)
        assertContentEquals(byteArrayOf(0, 0x80.toByte(), 0xC3.toByte(), 0xA9.toByte(), 0xFF.toByte()), second)
        assertEquals(5, value.byteLength)
    }

    @Test
    fun `equality hashing ordering and concatenation use unsigned bytes`() {
        val prefix = LuaString(byteArrayOf(0, 0x80.toByte()))
        val same = LuaString(prefix.value)
        val suffix = LuaString(byteArrayOf(0xFF.toByte()))

        assertEquals(prefix, same)
        assertEquals(prefix.hashCode(), same.hashCode())
        assertTrue(prefix.byteCompareTo(LuaString(byteArrayOf(0, 0x7F))) > 0)
        assertContentEquals(
            byteArrayOf(0, 0x80.toByte(), 0xFF.toByte()),
            prefix.concatenatedWith(suffix).copyRawBytes(),
        )
    }
}
