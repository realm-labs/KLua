package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
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

    @Test
    fun `grows with nil slots and preserves values`() {
        val stack = LuaStack(1)
        stack.set(0, LuaInteger(10))
        stack.set(4, LuaInteger(50))

        assertEquals(
            listOf(LuaInteger(10), LuaNil, LuaNil, LuaNil, LuaInteger(50)),
            stack.slice(0, 5),
        )
        stack.copy(4, 2)
        assertEquals(LuaInteger(50), stack.get(2))
    }

    @Test
    fun `keeps captures synchronized across growth`() {
        val stack = LuaStack(1)
        stack.set(0, LuaInteger(10))
        val captured = stack.capture(0)

        stack.set(4, LuaInteger(50))
        stack.set(0, LuaInteger(20))

        assertEquals(LuaInteger(20), captured.value)
        assertEquals(LuaInteger(20), stack.get(0))
        assertEquals(LuaInteger(50), stack.get(4))
    }
}
