package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.runtime.LuaSourceVersion
import io.github.realmlabs.klua.core.value.LuaValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class LuaThreadTest {
    @Test
    fun `tracks active call frames in stack order`() {
        val thread = LuaThread()
        val outer = callFrame("outer")
        val inner = callFrame("inner")

        assertNull(thread.currentFrame)
        assertEquals(0, thread.callDepth)

        thread.pushFrame(outer)
        assertSame(outer, thread.currentFrame)
        assertEquals(1, thread.callDepth)

        thread.pushFrame(inner)
        assertSame(inner, thread.currentFrame)
        assertEquals(2, thread.callDepth)

        thread.popFrame(inner)
        assertSame(outer, thread.currentFrame)
        assertEquals(1, thread.callDepth)

        thread.popFrame(outer)
        assertNull(thread.currentFrame)
        assertEquals(0, thread.callDepth)
    }

    @Test
    fun `rejects out of order frame pops`() {
        val thread = LuaThread()
        val outer = callFrame("outer")
        val inner = callFrame("inner")

        thread.pushFrame(outer)
        thread.pushFrame(inner)

        assertFailsWith<IllegalArgumentException> {
            thread.popFrame(outer)
        }
    }

    private fun callFrame(sourceName: String): CallFrame {
        return CallFrame(
            prototype = Prototype(
                sourceName = sourceName,
                version = LuaSourceVersion.LUA_54,
                code = IntArray(0),
                constants = emptyArray<LuaValue>(),
                maxStackSize = 1,
            ),
            stack = LuaStack(1),
        )
    }
}
