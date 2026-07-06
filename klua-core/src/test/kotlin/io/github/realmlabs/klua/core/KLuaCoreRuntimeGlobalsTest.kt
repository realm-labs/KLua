package io.github.realmlabs.klua.core

import io.github.realmlabs.klua.core.value.toLuaByteString
import kotlin.test.Test
import kotlin.test.assertEquals

class KLuaCoreRuntimeGlobalsTest {
    @Test
    fun `matches host globals by raw byte name`() {
        val globals = KLuaCoreGlobals()
        globals.set("é", KLuaCoreValue.IntegerValue(42))

        assertEquals(
            KLuaCoreValue.IntegerValue(42),
            globals.get(byteArrayOf(195.toByte(), 169.toByte()).toLuaByteString()),
        )
    }

    @Test
    fun `raw equal compares numbers without losing integer precision`() {
        assertEquals(
            false,
            KLuaCoreRuntime.rawEqual(
                KLuaCoreValue.IntegerValue(Long.MAX_VALUE),
                KLuaCoreValue.IntegerValue(Long.MAX_VALUE - 1L),
            ),
        )
        assertEquals(
            true,
            KLuaCoreRuntime.rawEqual(KLuaCoreValue.IntegerValue(1L), KLuaCoreValue.NumberValue(1.0)),
        )
        assertEquals(
            false,
            KLuaCoreRuntime.rawEqual(KLuaCoreValue.IntegerValue(1L), KLuaCoreValue.NumberValue(1.5)),
        )
        assertEquals(
            false,
            KLuaCoreRuntime.rawEqual(
                KLuaCoreValue.IntegerValue(Long.MAX_VALUE),
                KLuaCoreValue.NumberValue(Long.MAX_VALUE.toDouble()),
            ),
        )
        assertEquals(
            true,
            KLuaCoreRuntime.rawEqual(
                KLuaCoreValue.IntegerValue(Long.MIN_VALUE),
                KLuaCoreValue.NumberValue(Long.MIN_VALUE.toDouble()),
            ),
        )
    }
}
