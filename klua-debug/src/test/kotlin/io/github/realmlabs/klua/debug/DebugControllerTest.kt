package io.github.realmlabs.klua.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugControllerTest {
    @Test
    fun `shouldStop pauses on enabled source line breakpoints`() {
        val controller = DebugController()
        val breakpoint = controller.setBreakpoint("main.lua", 4)

        assertNull(controller.shouldStop("main.lua", 3, DebugEvent.LINE, callDepth = 1))
        val stop = controller.shouldStop("main.lua", 4, DebugEvent.LINE, callDepth = 1)

        assertEquals(DebugStopReason.BREAKPOINT, stop?.reason)
        assertEquals(breakpoint, stop?.breakpoint)
        assertTrue(controller.isPaused)
    }

    @Test
    fun `shouldStop ignores disabled breakpoints and non-line events`() {
        val controller = DebugController()
        controller.setBreakpoint("main.lua", 4, enabled = false)
        controller.setBreakpoint("main.lua", 5)

        assertNull(controller.shouldStop("main.lua", 4, DebugEvent.LINE, callDepth = 1))
        assertNull(controller.shouldStop("main.lua", 5, DebugEvent.CALL, callDepth = 1))
        assertFalse(controller.isPaused)
    }

    @Test
    fun `shouldStop uses condition evaluator for conditional breakpoints`() {
        val evaluated = mutableListOf<String>()
        val controller = DebugController(
            conditionEvaluator = DebugConditionEvaluator { condition ->
                evaluated += condition
                condition == "ready"
            },
        )
        val falseBreakpoint = controller.setBreakpoint("main.lua", 4, condition = "waiting")
        val trueBreakpoint = controller.setBreakpoint("main.lua", 5, condition = "ready")

        assertNull(controller.shouldStop("main.lua", 4, DebugEvent.LINE, callDepth = 1))
        assertFalse(controller.isPaused)
        val stop = controller.shouldStop("main.lua", 5, DebugEvent.LINE, callDepth = 1)

        assertEquals(listOf("waiting", "ready"), evaluated)
        assertEquals(DebugStopReason.BREAKPOINT, stop?.reason)
        assertEquals(trueBreakpoint, stop?.breakpoint)
        assertEquals("waiting", falseBreakpoint.condition)
        assertTrue(controller.isPaused)
    }

    @Test
    fun `pause and resume control manual stop state`() {
        val controller = DebugController()

        controller.pause()
        val stop = controller.shouldStop("main.lua", 1, DebugEvent.LINE, callDepth = 0)

        assertEquals(DebugStopReason.PAUSE, stop?.reason)
        assertTrue(controller.isPaused)
        controller.resume()
        assertFalse(controller.isPaused)
        assertEquals(StepMode.None, controller.currentStepMode())
        assertNull(controller.shouldStop("main.lua", 1, DebugEvent.LINE, callDepth = 0))
    }

    @Test
    fun `stepInto stops on next source line`() {
        val controller = DebugController()

        controller.stepInto()
        assertEquals(StepMode.Into, controller.currentStepMode())
        assertNull(controller.shouldStop("main.lua", 1, DebugEvent.CALL, callDepth = 1))
        val stop = controller.shouldStop("main.lua", 2, DebugEvent.LINE, callDepth = 2)

        assertEquals(DebugStopReason.STEP, stop?.reason)
        assertEquals(StepMode.None, controller.currentStepMode())
        assertTrue(controller.isPaused)
    }

    @Test
    fun `stepOver stops on line when call depth returns to start depth`() {
        val controller = DebugController()

        controller.stepOver(startDepth = 1)
        assertIs<StepMode.Over>(controller.currentStepMode())
        assertNull(controller.shouldStop("main.lua", 2, DebugEvent.LINE, callDepth = 2))
        val stop = controller.shouldStop("main.lua", 3, DebugEvent.LINE, callDepth = 1)

        assertEquals(DebugStopReason.STEP, stop?.reason)
        assertEquals(StepMode.None, controller.currentStepMode())
        assertTrue(controller.isPaused)
    }

    @Test
    fun `stepOut stops on return when call depth reaches target depth`() {
        val controller = DebugController()

        controller.stepOut(currentDepth = 3)
        assertEquals(StepMode.Out(targetDepth = 2), controller.currentStepMode())
        assertNull(controller.shouldStop("main.lua", 2, DebugEvent.RETURN, callDepth = 3))
        val stop = controller.shouldStop("main.lua", 3, DebugEvent.RETURN, callDepth = 2)

        assertEquals(DebugStopReason.STEP, stop?.reason)
        assertEquals(StepMode.None, controller.currentStepMode())
        assertTrue(controller.isPaused)
    }

    @Test
    fun `shouldStop validates source locations and depth`() {
        val controller = DebugController()

        assertFailsWith<IllegalArgumentException> {
            controller.shouldStop("", 1, DebugEvent.LINE, callDepth = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            controller.shouldStop("main.lua", 0, DebugEvent.LINE, callDepth = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            controller.shouldStop("main.lua", 1, DebugEvent.LINE, callDepth = -1)
        }
    }
}
