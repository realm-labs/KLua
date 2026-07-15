package io.github.realmlabs.klua.debug

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.api.LuaCoroutineFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LiveDebugSessionTest {
    @Test
    fun `breakpoint suspends live execution with locals and upvalues`() {
        val function = debugFunction(
            """
            local captured = 10
            return function()
                local value = 1
                value = value + captured
                return value
            end
            """.trimIndent(),
            "live-breakpoint.lua",
        )
        val session = LiveDebugSession(function)
        session.controller.setBreakpoint("live-breakpoint.lua", 5)

        val stopped = assertIs<LiveDebugResult.Stopped>(session.run())

        assertEquals(DebugStopReason.BREAKPOINT, stopped.stop.reason)
        assertEquals("live-breakpoint.lua", stopped.stop.sourceId)
        assertEquals(5, stopped.stop.line)
        val frame = stopped.frames.single()
        assertEquals(5, frame.line)
        assertEquals(11L, frame.locals.single { variable -> variable.name == "value" }.value)
        assertEquals(10L, frame.upvalues.single { variable -> variable.name == "captured" }.value)
        assertEquals(listOf(11L), assertIs<LiveDebugResult.Returned>(session.continueExecution()).values)
    }

    @Test
    fun `live session steps into and out of nested Lua calls`() {
        val session = steppingSession()
        val initial = assertIs<LiveDebugResult.Stopped>(session.run())
        assertEquals(6, initial.stop.line)

        val inside = assertIs<LiveDebugResult.Stopped>(session.stepInto())
        assertEquals(DebugStopReason.STEP, inside.stop.reason)
        assertEquals(4, inside.stop.line)
        assertEquals(2, inside.frames.size)

        val caller = assertIs<LiveDebugResult.Stopped>(session.stepOut())
        assertEquals(7, caller.stop.line)
        assertEquals(1, caller.frames.size)
        assertEquals(listOf(3L), assertIs<LiveDebugResult.Returned>(session.continueExecution()).values)
    }

    @Test
    fun `live session steps over nested Lua calls`() {
        val session = steppingSession()
        assertIs<LiveDebugResult.Stopped>(session.run())

        val caller = assertIs<LiveDebugResult.Stopped>(session.stepOver())

        assertEquals(DebugStopReason.STEP, caller.stop.reason)
        assertEquals(7, caller.stop.line)
        assertEquals(1, caller.frames.size)
        assertEquals(listOf(3L), assertIs<LiveDebugResult.Returned>(session.continueExecution()).values)
    }

    private fun steppingSession(): LiveDebugSession {
        val function = debugFunction(
            """
            return function()
                local value = 1
                local function add()
                    value = value + 1
                end
                add()
                value = value + 1
                return value
            end
            """.trimIndent(),
            "live-stepping.lua",
        )
        return LiveDebugSession(function).also { session ->
            session.controller.setBreakpoint("live-stepping.lua", 6)
        }
    }

    private fun debugFunction(source: String, chunkName: String): LuaCoroutineFunction {
        return Lua.create().load(source, chunkName).eval().get(1) as LuaCoroutineFunction
    }
}
