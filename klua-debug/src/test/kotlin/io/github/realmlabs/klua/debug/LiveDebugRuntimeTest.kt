package io.github.realmlabs.klua.debug

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.api.LuaCoroutineFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LiveDebugRuntimeTest {
    @Test
    fun `runtime assigns stable debugger thread ids to independent Lua coroutines`() {
        val runtime = LiveDebugRuntime()
        val first = runtime.register(debugFunction("thread-one.lua", 1), "worker one")
        val second = runtime.register(debugFunction("thread-two.lua", 2), "worker two")
        first.session.controller.setBreakpoint("thread-one.lua", 2)
        second.session.controller.setBreakpoint("thread-two.lua", 2)

        val firstStop = assertIs<LiveDebugResult.Stopped>(first.session.run())
        val secondStop = assertIs<LiveDebugResult.Stopped>(second.session.run())

        assertEquals(listOf(1, 2), runtime.threads().map { thread -> thread.id })
        assertEquals("thread-one.lua", firstStop.stop.sourceId)
        assertEquals("thread-two.lua", secondStop.stop.sourceId)
        assertEquals(first, runtime.thread(1))
        assertEquals(second, runtime.thread(2))
        assertEquals(true, runtime.remove(1))
        assertNull(runtime.thread(1))
        assertEquals(listOf(2), runtime.threads().map { thread -> thread.id })
    }

    private fun debugFunction(sourceName: String, value: Long): LuaCoroutineFunction {
        return Lua.create().load(
            """
            return function()
                return $value
            end
            """.trimIndent(),
            sourceName,
        ).eval().get(1) as LuaCoroutineFunction
    }
}
