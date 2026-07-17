package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue
import io.github.realmlabs.klua.core.value.LuaValueTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun `slices are immutable snapshots for common result counts`() {
        val stack = LuaStack(2)
        stack.set(0, LuaInteger(10))
        stack.set(1, LuaInteger(20))

        val empty = stack.snapshotResults(0, 0)
        val single = stack.snapshotResults(0, 1)
        val multiple = stack.snapshotResults(0, 2)
        stack.set(0, LuaInteger(30))

        assertSame(empty, stack.snapshotResults(0, 0))
        assertEquals(listOf(LuaInteger(10)), single)
        assertEquals(listOf(LuaInteger(10), LuaInteger(20)), multiple)
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

    @Test
    fun `stores primitive tags and payloads through copies and captures`() {
        val source = LuaStack(2)
        val target = LuaStack(1)
        source.setInteger(0, Long.MIN_VALUE)
        source.setFloat(1, -0.0)

        target.copyFrom(source, 0, 0)
        target.copyFrom(source, 1, 1)

        assertEquals(LuaValueTag.INTEGER, target.tagAt(0))
        assertEquals(Long.MIN_VALUE, target.integerValue(0))
        assertEquals(LuaValueTag.FLOAT, target.tagAt(1))
        assertEquals((-0.0).toRawBits(), target.floatValue(1).toRawBits())

        val captured = target.capture(0)
        target.setFloat(0, Double.fromBits(0x7ff8000000000001L))
        assertEquals(LuaValueTag.FLOAT, captured.tag)
        assertEquals(0x7ff8000000000001L, captured.floatValue().toRawBits())

        captured.setBoolean(false)
        assertEquals(LuaValueTag.FALSE, target.tagAt(0))
        assertFalse(target.isTruthy(0))

        val closed = LuaUpvalue(LuaNil)
        target.copyTo(0, closed)
        assertEquals(LuaValueTag.FALSE, closed.tag)
        target.closeCapturesFrom(0)
        assertEquals(LuaValueTag.FALSE, target.tagAt(0))
        target.setInteger(0, 42)
        assertEquals(LuaValueTag.FALSE, captured.tag)
        assertEquals(LuaValueTag.INTEGER, target.tagAt(0))
    }

    @Test
    fun `primitive overwrite removes stale heap roots`() {
        val stack = LuaStack(1)
        val table = LuaTable()
        stack.set(0, table)

        val before = mutableListOf<LuaValue>()
        stack.forEachHeapValue(before::add)
        assertEquals(listOf<LuaValue>(table), before)

        stack.setInteger(0, 10)
        val after = mutableListOf<LuaValue>()
        stack.forEachHeapValue(after::add)
        assertTrue(after.isEmpty())
    }
}
