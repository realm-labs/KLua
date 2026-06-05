package io.github.realmlabs.klua.dap

import io.github.realmlabs.klua.debug.StepMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DapSessionTest {
    @Test
    fun `initialize records client metadata and returns default capabilities`() {
        val session = DapSession()

        val response = session.initialize(
            DapInitializeRequest(
                clientId = "vscode",
                clientName = "Visual Studio Code",
            ),
        )

        assertTrue(session.isInitialized)
        assertEquals("vscode", session.clientId)
        assertEquals(
            DapCapabilities(
                supportsConfigurationDoneRequest = true,
                supportsConditionalBreakpoints = true,
                supportsHitConditionalBreakpoints = false,
                supportsEvaluateForHovers = false,
                supportsStepBack = false,
                supportsSetVariable = false,
            ),
            response.capabilities,
        )
    }

    @Test
    fun `session starts uninitialized`() {
        val session = DapSession()

        assertFalse(session.isInitialized)
        assertEquals(null, session.clientId)
    }

    @Test
    fun `initialize returns configured capabilities`() {
        val capabilities = DapCapabilities(
            supportsEvaluateForHovers = true,
            supportsSetVariable = true,
        )
        val session = DapSession(capabilities)

        val response = session.initialize(DapInitializeRequest())

        assertEquals(capabilities, response.capabilities)
    }

    @Test
    fun `setBreakpoints replaces source breakpoints and returns verified DAP breakpoints`() {
        val session = DapSession()
        val source = DapSource(path = "main.lua", name = "main.lua")

        val first = session.setBreakpoints(
            DapSetBreakpointsRequest(
                source = source,
                breakpoints = listOf(
                    DapSourceBreakpoint(line = 2),
                    DapSourceBreakpoint(line = 4, condition = "ready"),
                ),
            ),
        )

        assertEquals(
            DapSetBreakpointsResponse(
                listOf(
                    DapBreakpoint(verified = true, source = source, line = 2),
                    DapBreakpoint(verified = true, source = source, line = 4, condition = "ready"),
                ),
            ),
            first,
        )
        assertEquals("ready", session.breakpointAt("main.lua", 4)?.condition)

        val replacement = session.setBreakpoints(
            DapSetBreakpointsRequest(
                source = source,
                breakpoints = listOf(DapSourceBreakpoint(line = 6)),
            ),
        )

        assertEquals(
            DapSetBreakpointsResponse(listOf(DapBreakpoint(verified = true, source = source, line = 6))),
            replacement,
        )
        assertEquals(null, session.breakpointAt("main.lua", 2))
        assertEquals(null, session.breakpointAt("main.lua", 4))
        assertEquals(6, session.breakpointAt("main.lua", 6)?.line)
    }

    @Test
    fun `configurationDone marks session configured`() {
        val session = DapSession()

        val response = session.configurationDone()

        assertTrue(response.configured)
        assertTrue(session.isConfigured)
    }

    @Test
    fun `pause and continue map to debug controller state`() {
        val session = DapSession()

        val pause = session.pause()

        assertTrue(pause.paused)
        assertEquals(StepMode.None, session.currentStepMode())

        val next = session.next(callDepth = 2)
        assertIs<StepMode.Over>(next.stepMode)
        assertEquals(StepMode.Over(2), session.currentStepMode())

        val continued = session.continueExecution()
        assertTrue(continued.allThreadsContinued)
        assertEquals(StepMode.None, session.currentStepMode())
    }

    @Test
    fun `stepIn and stepOut map to debug controller step modes`() {
        val session = DapSession()

        val stepIn = session.stepIn()
        val stepOut = session.stepOut(callDepth = 3)

        assertEquals(StepMode.Into, stepIn.stepMode)
        assertEquals(StepMode.Out(targetDepth = 2), stepOut.stepMode)
        assertEquals(StepMode.Out(targetDepth = 2), session.currentStepMode())
    }
}
