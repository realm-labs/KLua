package io.github.realmlabs.klua.dap

import io.github.realmlabs.klua.debug.DebugVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DapWireSessionTest {
    @Test
    fun `handle returns initialize response envelope with capabilities body`() {
        val wireSession = DapWireSession()

        val response = wireSession.handle(
            DapRequestMessage(
                seq = 7,
                command = "initialize",
                arguments = DapJsonObject(
                    linkedMapOf(
                        "clientID" to DapJsonString("vscode"),
                        "adapterID" to DapJsonString("klua"),
                    ),
                ),
            ),
        )

        assertEquals(1, response.seq)
        assertEquals(7, response.requestSeq)
        assertEquals("initialize", response.command)
        assertTrue(response.success)
        assertEquals(
            DapJsonObject(
                linkedMapOf(
                    "capabilities" to DapJsonObject(
                        linkedMapOf(
                            "supportsConfigurationDoneRequest" to DapJsonBoolean(true),
                            "supportsConditionalBreakpoints" to DapJsonBoolean(true),
                            "supportsHitConditionalBreakpoints" to DapJsonBoolean(false),
                            "supportsEvaluateForHovers" to DapJsonBoolean(false),
                            "supportsStepBack" to DapJsonBoolean(false),
                            "supportsSetVariable" to DapJsonBoolean(false),
                        ),
                    ),
                ),
            ),
            response.body,
        )
    }

    @Test
    fun `handle routes JSON setBreakpoints arguments to session`() {
        val session = DapSession()
        val wireSession = DapWireSession(session)

        val response = wireSession.handle(
            DapRequestMessage(
                seq = 3,
                command = "setBreakpoints",
                arguments = DapJsonObject(
                    linkedMapOf(
                        "source" to DapJsonObject(
                            linkedMapOf(
                                "path" to DapJsonString("main.lua"),
                                "name" to DapJsonString("main.lua"),
                            ),
                        ),
                        "breakpoints" to DapJsonArray(
                            listOf(
                                DapJsonObject(
                                    linkedMapOf(
                                        "line" to DapJsonNumber(4.0),
                                        "condition" to DapJsonString("ready"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(response.success)
        assertEquals("ready", session.breakpointAt("main.lua", 4)?.condition)
        assertEquals(
            DapJsonObject(
                linkedMapOf(
                    "breakpoints" to DapJsonArray(
                        listOf(
                            DapJsonObject(
                                linkedMapOf(
                                    "verified" to DapJsonBoolean(true),
                                    "source" to DapJsonObject(
                                        linkedMapOf(
                                            "path" to DapJsonString("main.lua"),
                                            "name" to DapJsonString("main.lua"),
                                        ),
                                    ),
                                    "line" to DapJsonNumber(4.0),
                                    "condition" to DapJsonString("ready"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            response.body,
        )
    }

    @Test
    fun `handleJson parses request and serializes response`() {
        val wireSession = DapWireSession(
            DapSession(
                expressionEvaluator = DapExpressionEvaluator { _, _ ->
                    DebugVariable("result", 42L, "number", "42")
                },
            ),
        )

        val json = wireSession.handleJson(
            """{"seq":9,"type":"request","command":"evaluate","arguments":{"expression":"answer","context":"watch"}}""",
        )

        assertEquals(
            """{"seq":1,"type":"response","request_seq":9,"success":true,"command":"evaluate","body":{"result":"42","type":"number","variablesReference":0}}""",
            json,
        )
    }

    @Test
    fun `handle returns failed response envelope for invalid requests`() {
        val response = DapWireSession().handle(
            DapRequestMessage(
                seq = 11,
                command = "initialize",
                arguments = DapJsonString("invalid"),
            ),
        )

        assertFalse(response.success)
        assertEquals(11, response.requestSeq)
        assertEquals("initialize", response.command)
        assertEquals("command initialize requires JSON object arguments", response.message)
    }
}
