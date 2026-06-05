package io.github.realmlabs.klua.dap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
