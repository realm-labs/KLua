package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LuaThreadTest {
    @Test
    fun `tracks active call frames in stack order`() {
        val thread = LuaThread()

        assertNull(thread.currentFrame)
        assertEquals(0, thread.callDepth)

        val outer = thread.pushCall(prototype("outer"), emptyList())
        assertSame(outer, thread.currentFrame)
        assertEquals(1, thread.callDepth)

        val inner = thread.pushCall(prototype("inner"), emptyList())
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
        val outer = thread.pushCall(prototype("outer"), emptyList())
        thread.pushCall(prototype("inner"), emptyList())

        assertFailsWith<IllegalArgumentException> {
            thread.popFrame(outer)
        }
    }

    @Test
    fun `push call initializes parameters and varargs`() {
        val thread = LuaThread()

        val frame = thread.pushCall(
            prototype("chunk", maxStackSize = 1, numParams = 2, isVararg = true),
            listOf(LuaInteger(10), LuaInteger(20), LuaInteger(30)),
        )

        assertEquals(LuaInteger(10), frame.stack.get(0))
        assertEquals(LuaInteger(20), frame.stack.get(1))
        assertEquals(listOf(LuaInteger(30)), frame.varargs)
    }

    @Test
    fun `push call fills missing fixed parameters with nil`() {
        val thread = LuaThread()

        val frame = thread.pushCall(
            prototype("chunk", numParams = 2),
            listOf(LuaInteger(10)),
        )

        assertEquals(LuaInteger(10), frame.stack.get(0))
        assertEquals(LuaNil, frame.stack.get(1))
        assertEquals(emptyList(), frame.varargs)
    }

    @Test
    fun `tracks nested native call boundaries`() {
        val thread = LuaThread()

        assertFalse(thread.inNativeCall)

        thread.runNativeCall {
            assertTrue(thread.inNativeCall)
            thread.runNativeCall {
                assertTrue(thread.inNativeCall)
            }
            assertTrue(thread.inNativeCall)
        }

        assertFalse(thread.inNativeCall)
    }

    @Test
    fun `unwinds native call boundary after failure`() {
        val thread = LuaThread()

        assertFailsWith<IllegalStateException> {
            thread.runNativeCall {
                throw IllegalStateException("boom")
            }
        }

        assertFalse(thread.inNativeCall)
    }

    @Test
    fun `clears active call frames`() {
        val thread = LuaThread()

        thread.pushCall(prototype("outer"), emptyList())
        thread.pushCall(prototype("inner"), emptyList())

        thread.clearFrames()

        assertNull(thread.currentFrame)
        assertEquals(0, thread.callDepth)
    }

    private fun prototype(
        sourceName: String,
        maxStackSize: Int = 1,
        numParams: Int = 0,
        isVararg: Boolean = false,
    ): Prototype {
        return Prototype(
            sourceName = sourceName,
            code = IntArray(0),
            constants = emptyArray<LuaValue>(),
            maxStackSize = maxStackSize,
            numParams = numParams,
            isVararg = isVararg,
        )
    }
}
