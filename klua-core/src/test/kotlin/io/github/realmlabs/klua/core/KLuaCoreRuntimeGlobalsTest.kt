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
}
