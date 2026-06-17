package io.github.realmlabs.klua.dap

import io.github.realmlabs.klua.debug.DebugFrameView
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
                            "supportsEvaluateForHovers" to DapJsonBoolean(true),
                            "supportsDelayedStackTraceLoading" to DapJsonBoolean(true),
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
    fun `handleExchange emits initialized event after initialize response`() {
        val wireSession = DapWireSession()

        val exchange = wireSession.handleExchange(
            DapRequestMessage(
                seq = 8,
                command = "initialize",
                arguments = DapJsonObject(linkedMapOf("adapterID" to DapJsonString("klua"))),
            ),
        )

        assertEquals(1, exchange.response.seq)
        assertEquals(8, exchange.response.requestSeq)
        assertEquals("initialize", exchange.response.command)
        assertTrue(exchange.response.success)
        assertEquals(listOf(DapEventMessage(seq = 2, event = "initialized")), exchange.events)
    }

    @Test
    fun `handleJsonExchange serializes response and events`() {
        val messages = DapWireSession().handleJsonExchange(
            """{"seq":9,"type":"request","command":"initialize","arguments":{"adapterID":"klua"}}""",
        )

        assertEquals(
            listOf(
                """{"seq":1,"type":"response","request_seq":9,"success":true,"command":"initialize","body":{"capabilities":{"supportsConfigurationDoneRequest":true,"supportsConditionalBreakpoints":true,"supportsHitConditionalBreakpoints":false,"supportsEvaluateForHovers":true,"supportsDelayedStackTraceLoading":true,"supportsStepBack":false,"supportsSetVariable":false}}}""",
                """{"seq":2,"type":"event","event":"initialized"}""",
            ),
            messages,
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
    fun `handle routes JSON launch arguments to session`() {
        val session = DapSession()
        val wireSession = DapWireSession(session)

        val response = wireSession.handle(
            DapRequestMessage(
                seq = 4,
                command = "launch",
                arguments = DapJsonObject(
                    linkedMapOf(
                        "program" to DapJsonString("scripts/main.lua"),
                        "cwd" to DapJsonString("/workspace"),
                        "args" to DapJsonArray(listOf(DapJsonString("one"), DapJsonString("two"))),
                        "stopOnEntry" to DapJsonBoolean(true),
                    ),
                ),
            ),
        )

        assertTrue(response.success)
        assertEquals(DapDebugMode.Launch, session.mode)
        assertEquals("scripts/main.lua", session.launchedProgram)
        assertEquals(
            DapJsonObject(
                linkedMapOf(
                    "launched" to DapJsonBoolean(true),
                    "program" to DapJsonString("scripts/main.lua"),
                ),
            ),
            response.body,
        )
    }

    @Test
    fun `handle routes JSON attach arguments to session`() {
        val session = DapSession()
        val wireSession = DapWireSession(session)

        val response = wireSession.handle(
            DapRequestMessage(
                seq = 5,
                command = "attach",
                arguments = DapJsonObject(
                    linkedMapOf(
                        "processId" to DapJsonNumber(1201.0),
                    ),
                ),
            ),
        )

        assertTrue(response.success)
        assertEquals(DapDebugMode.Attach, session.mode)
        assertEquals("process:1201", session.attachTarget)
        assertEquals(
            DapJsonObject(
                linkedMapOf(
                    "attached" to DapJsonBoolean(true),
                    "target" to DapJsonString("process:1201"),
                ),
            ),
            response.body,
        )
    }

    @Test
    fun `handle routes JSON disconnect arguments to session`() {
        val session = DapSession()
        val response = DapWireSession(session).handle(
            DapRequestMessage(
                seq = 8,
                command = "disconnect",
                arguments = DapJsonObject(
                    linkedMapOf(
                        "restart" to DapJsonBoolean(true),
                        "terminateDebuggee" to DapJsonBoolean(true),
                    ),
                ),
            ),
        )

        assertTrue(session.isDisconnected)
        assertEquals(
            DapResponseMessage(
                seq = 1,
                requestSeq = 8,
                command = "disconnect",
                success = true,
                body = DapJsonObject(
                    linkedMapOf(
                        "disconnected" to DapJsonBoolean(true),
                        "restart" to DapJsonBoolean(true),
                        "terminateDebuggee" to DapJsonBoolean(true),
                    ),
                ),
            ),
            response,
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
    fun `handleJson routes stackTrace thread and paging arguments`() {
        val requestedThreadIds = mutableListOf<Int>()
        val wireSession = DapWireSession(
            stackTraceFrameProvider = DapStackTraceFrameProvider { threadId ->
                requestedThreadIds += threadId
                listOf(
                    DebugFrameView(level = 0, sourceName = "main.lua", line = 1),
                    DebugFrameView(level = 1, sourceName = "lib.lua", line = 2),
                    DebugFrameView(level = 2, sourceName = "deep.lua", line = 3),
                )
            },
        )

        val json = wireSession.handleJson(
            """{"seq":10,"type":"request","command":"stackTrace","arguments":{"threadId":1,"startFrame":1,"levels":1}}""",
        )

        assertEquals(listOf(1), requestedThreadIds)
        assertEquals(
            """{"seq":1,"type":"response","request_seq":10,"success":true,"command":"stackTrace","body":{"stackFrames":[{"id":1,"name":"lib.lua","source":{"path":"lib.lua","name":"lib.lua"},"line":2,"column":1}],"totalFrames":3}}""",
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

    @Test
    fun `handle returns failed response envelope for invalid attach target`() {
        val response = DapWireSession().handle(
            DapRequestMessage(
                seq = 12,
                command = "attach",
                arguments = DapJsonObject(emptyMap()),
            ),
        )

        assertFalse(response.success)
        assertEquals("attach", response.command)
        assertEquals("attach requires processId or host and port", response.message)
    }
}
