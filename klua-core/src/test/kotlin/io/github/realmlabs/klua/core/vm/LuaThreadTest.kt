package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue
import io.github.realmlabs.klua.core.value.LuaValueTag
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
    fun `stack range calls preserve tagged parameter and vararg payloads`() {
        val thread = LuaThread()
        val sourceStack = LuaStack(3)
        sourceStack.setInteger(0, Long.MAX_VALUE)
        sourceStack.setFloat(1, -0.0)
        sourceStack.setBoolean(2, false)

        val frame = thread.pushCallFromStack(
            sourceStack,
            argumentStart = 0,
            argumentCount = 3,
            environment = LuaUpvalue(LuaTable()),
            function = LuaClosure(prototype("chunk", numParams = 1, isVararg = true)),
        )
        val loadedVarargs = LuaStack(2)
        assertTrue(frame.copyVarargTo(0, loadedVarargs, 0))
        assertTrue(frame.copyVarargTo(1, loadedVarargs, 1))

        assertEquals(LuaValueTag.INTEGER, frame.tagAt(0))
        assertEquals(Long.MAX_VALUE, frame.integerValue(0))
        assertEquals(LuaValueTag.FLOAT, loadedVarargs.tagAt(0))
        assertEquals((-0.0).toRawBits(), loadedVarargs.floatValue(0).toRawBits())
        assertEquals(LuaValueTag.FALSE, loadedVarargs.tagAt(1))
        assertFalse(loadedVarargs.isTruthy(1))
    }

    @Test
    fun `stack range call snapshots every trailing tagged argument after source growth`() {
        val source = LuaStack(1)
        val callee = LuaClosure(prototype("callee", maxStackSize = 3, numParams = 1, isVararg = true))
        source.set(1, callee)
        source.setInteger(2, 10)
        source.setInteger(3, 20)
        source.setInteger(4, 12)

        val frame = LuaThread().pushCallFromStack(
            source,
            argumentStart = 1,
            argumentCount = 4,
            environment = LuaUpvalue(LuaTable()),
            function = callee,
        )

        assertEquals(listOf<LuaValue>(LuaInteger(10), LuaInteger(20), LuaInteger(12)), frame.varargs)
        for (index in 0 until frame.varargCount) {
            assertTrue(frame.copyVarargTo(index, frame, index + 1))
        }
        assertEquals(listOf(LuaInteger(10), LuaInteger(20), LuaInteger(12)), frame.slice(1, 3))
    }

    @Test
    fun `lifecycle roots visit vararg references without boxing primitives`() {
        val thread = LuaThread()
        val table = LuaTable()
        val frame = thread.pushCall(
            prototype("roots", isVararg = true),
            listOf(LuaInteger(10), table),
        )

        assertTrue(table in thread.lifecycleRoots())
        assertFalse(LuaInteger(10) in thread.lifecycleRoots())

        assertTrue(frame.setVararg(1, LuaInteger(20)))
        assertFalse(table in thread.lifecycleRoots())
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
