package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class LuaStackTest {
    @Test
    fun `shares open captures and detaches them when closed`() {
        val stack = LuaStack(1)
        stack.set(0, LuaInteger(10))

        val first = stack.capture(0)
        assertSame(first, stack.capture(0))

        stack.set(0, LuaInteger(20))
        assertEquals(LuaInteger(20), first.value)

        stack.closeCapturesFrom(0)
        val reopened = stack.capture(0)
        assertNotSame(first, reopened)
        assertEquals(LuaInteger(20), reopened.value)

        stack.set(0, LuaInteger(30))
        assertEquals(LuaInteger(20), first.value)
        assertEquals(LuaInteger(30), reopened.value)
    }
}
