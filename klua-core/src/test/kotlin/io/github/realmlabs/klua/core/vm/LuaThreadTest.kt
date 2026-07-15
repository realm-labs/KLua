package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUpvalue
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
        assertEquals(listOf<LuaValue>(LuaInteger(30)), frame.varargs)
    }

    @Test
    fun `push call snapshots mutable vararg arguments`() {
        val thread = LuaThread()
        val arguments = mutableListOf<LuaValue>(LuaInteger(10), LuaInteger(20), LuaNil, LuaInteger(40))

        val frame = thread.pushCall(
            prototype("chunk", maxStackSize = 2, numParams = 2, isVararg = true),
            arguments,
        )
        arguments[2] = LuaInteger(30)

        assertEquals(listOf<LuaValue>(LuaNil, LuaInteger(40)), frame.varargs)

        assertTrue(frame.setVararg(1, LuaInteger(50)))

        assertEquals(listOf<LuaValue>(LuaInteger(10), LuaInteger(20), LuaInteger(30), LuaInteger(40)), arguments)
    }

    @Test
    fun `push call copies fixed parameters and varargs from a stack range`() {
        val thread = LuaThread()
        val sourceStack = LuaStack(5)
        sourceStack.set(0, LuaInteger(5))
        sourceStack.set(1, LuaInteger(10))
        sourceStack.set(2, LuaInteger(20))
        sourceStack.set(3, LuaNil)
        sourceStack.set(4, LuaInteger(40))

        val frame = thread.pushCallFromStack(
            sourceStack,
            argumentStart = 1,
            argumentCount = 4,
            environment = LuaUpvalue(LuaTable()),
            function = LuaClosure(prototype("chunk", maxStackSize = 2, numParams = 2, isVararg = true)),
        )
        sourceStack.set(3, LuaInteger(30))

        assertEquals(LuaInteger(10), frame.stack.get(0))
        assertEquals(LuaInteger(20), frame.stack.get(1))
        assertEquals(listOf<LuaValue>(LuaNil, LuaInteger(40)), frame.varargs)
    }

    @Test
    fun `push fixed call places two or three arguments without a source list`() {
        val thread = LuaThread()
        val environment = LuaUpvalue(LuaTable())
        val fixed = thread.pushFixedCall(
            function = LuaClosure(prototype("fixed", numParams = 3)),
            argumentCount = 2,
            firstArgument = LuaInteger(10),
            secondArgument = LuaInteger(20),
            environment = environment,
        )

        assertEquals(LuaInteger(10), fixed.get(0))
        assertEquals(LuaInteger(20), fixed.get(1))
        assertEquals(LuaNil, fixed.get(2))
        assertEquals(emptyList(), fixed.varargs)
        thread.popFrame(fixed)

        val vararg = thread.pushFixedCall(
            function = LuaClosure(prototype("vararg", numParams = 1, isVararg = true)),
            argumentCount = 3,
            firstArgument = LuaInteger(30),
            secondArgument = LuaNil,
            thirdArgument = LuaInteger(50),
            environment = environment,
        )

        assertEquals(LuaInteger(30), vararg.get(0))
        assertEquals(listOf<LuaValue>(LuaNil, LuaInteger(50)), vararg.varargs)
    }

    @Test
    fun `push call fills missing fixed parameters with nil`() {
        val thread = LuaThread()

        val first = thread.pushCall(
            prototype("chunk", numParams = 2),
            listOf(LuaInteger(10)),
        )
        val second = thread.pushCall(prototype("chunk"), emptyList())

        assertEquals(LuaInteger(10), first.stack.get(0))
        assertEquals(LuaNil, first.stack.get(1))
        assertEquals(emptyList<LuaValue>(), first.varargs)
        assertSame(first.varargs, second.varargs)
        assertFalse(first.setVararg(0, LuaInteger(20)))
    }

    @Test
    fun `call frame owns its stack storage and captures`() {
        val prototype = prototype("chunk", maxStackSize = 1)
        val environment = LuaUpvalue(LuaTable())
        val closure = LuaClosure(prototype, mutableListOf(LuaUpvalue(LuaInteger(10))), environment = environment)
        val frame = LuaThread().pushCall(
            prototype,
            emptyList(),
            environment = environment,
            function = closure,
        )

        assertSame(frame, frame.stack)
        assertSame(closure, frame.function)
        assertSame(prototype, frame.prototype)
        assertSame(closure.upvalues, frame.upvalues)
        frame.set(4, LuaInteger(40))
        val captured = frame.capture(4)
        frame.stack.set(4, LuaInteger(50))

        assertEquals(LuaInteger(50), frame.get(4))
        assertEquals(LuaInteger(50), captured.value)
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
    fun `tracks non yieldable call boundaries`() {
        val thread = LuaThread()

        assertTrue(thread.isYieldable)

        thread.runNonYieldableCall {
            assertFalse(thread.isYieldable)
            thread.runNonYieldableCall {
                assertFalse(thread.isYieldable)
            }
            assertFalse(thread.isYieldable)
        }

        assertTrue(thread.isYieldable)
    }

    @Test
    fun `unwinds non yieldable call boundary after failure`() {
        val thread = LuaThread()

        assertFailsWith<IllegalStateException> {
            thread.runNonYieldableCall {
                throw IllegalStateException("boom")
            }
        }

        assertTrue(thread.isYieldable)
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
