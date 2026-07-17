package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaValue
import io.github.realmlabs.klua.core.value.LuaValueTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LuaCallArgumentsTest {
    @Test
    fun `stack arguments expose tagged values without changing list semantics`() {
        val stack = LuaStack(6)
        val reference = LuaString("value")
        stack.setNil(0)
        stack.setBoolean(1, false)
        stack.setBoolean(2, true)
        stack.setInteger(3, 42)
        stack.setFloat(4, 2.5)
        stack.set(5, reference)

        val arguments = LuaStackArguments(stack, 0, 6)

        assertEquals(
            listOf(LuaNil, LuaBoolean(false), LuaBoolean(true), LuaInteger(42), LuaFloat(2.5), reference),
            arguments,
        )
        assertEquals(LuaValueTag.INTEGER.ordinal, arguments.tagCode(3))
        assertEquals(42L, arguments.integerValue(3))
        assertEquals(2.5, arguments.floatValue(4))
        assertEquals(reference, arguments.referenceValue(5))
    }

    @Test
    fun `stack arguments read live captures and snapshots isolate escaping values`() {
        val stack = LuaStack(2)
        stack.setInteger(0, 1)
        stack.setInteger(1, 2)
        val capture = stack.capture(1)
        val arguments = LuaStackArguments(stack, 0, 2)

        capture.setInteger(20)
        val snapshot = arguments.snapshot()
        stack.setInteger(0, 10)
        capture.setInteger(30)

        assertEquals(listOf<LuaValue>(LuaInteger(10), LuaInteger(30)), arguments)
        assertEquals(listOf(LuaInteger(1), LuaInteger(20)), snapshot)
    }

    @Test
    fun `prepended and fixed arguments preserve exact order and bounds`() {
        val stack = LuaStack(2)
        stack.setInteger(0, 2)
        stack.setInteger(1, 3)
        val prepended = LuaPrependedArguments(LuaInteger(1), LuaStackArguments(stack, 0, 2))
        val fixed = LuaFixedArguments.three(LuaInteger(1), LuaInteger(2), LuaInteger(3))

        assertEquals(listOf<LuaValue>(LuaInteger(1), LuaInteger(2), LuaInteger(3)), prepended)
        assertEquals(prepended.toList(), fixed.toList())
        assertEquals(LuaValueTag.INTEGER.ordinal, prepended.tagCode(2))
        assertEquals(3L, prepended.integerValue(2))
        assertFailsWith<IndexOutOfBoundsException> { fixed[3] }
    }
}
