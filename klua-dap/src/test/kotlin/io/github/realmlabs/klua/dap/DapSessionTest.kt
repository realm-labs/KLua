package io.github.realmlabs.klua.dap

import io.github.realmlabs.klua.debug.DebugFrameView
import io.github.realmlabs.klua.debug.DebugVariable
import io.github.realmlabs.klua.debug.StepMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
                supportsEvaluateForHovers = true,
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
    fun `launch records launch mode and target program`() {
        val session = DapSession()

        val response = session.launch(
            DapLaunchRequest(
                program = "scripts/main.lua",
                cwd = "/workspace",
                args = listOf("one", "two"),
                stopOnEntry = true,
            ),
        )

        assertEquals(DapLaunchResponse(launched = true, program = "scripts/main.lua"), response)
        assertEquals(DapDebugMode.Launch, session.mode)
        assertEquals("scripts/main.lua", session.launchedProgram)
        assertEquals(null, session.attachTarget)
    }

    @Test
    fun `attach records attach mode and target`() {
        val session = DapSession()

        val response = session.attach(DapAttachRequest(host = "127.0.0.1", port = 8172))

        assertEquals(DapAttachResponse(attached = true, target = "127.0.0.1:8172"), response)
        assertEquals(DapDebugMode.Attach, session.mode)
        assertEquals("127.0.0.1:8172", session.attachTarget)
        assertEquals(null, session.launchedProgram)
    }

    @Test
    fun `disconnect marks session disconnected and clears configured state`() {
        val session = DapSession()
        session.configurationDone()

        val response = session.disconnect(DapDisconnectRequest(restart = true, terminateDebuggee = true))

        assertEquals(
            DapDisconnectResponse(disconnected = true, restart = true, terminateDebuggee = true),
            response,
        )
        assertTrue(session.isDisconnected)
        assertFalse(session.isConfigured)
        assertEquals(StepMode.None, session.currentStepMode())
    }

    @Test
    fun `launch and attach validate required targets`() {
        val session = DapSession()

        assertFailsWith<IllegalArgumentException> {
            session.launch(DapLaunchRequest(program = " "))
        }
        assertFailsWith<IllegalArgumentException> {
            session.attach(DapAttachRequest())
        }
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

    @Test
    fun `stackTrace maps debug frames to DAP frames and scopes`() {
        val session = DapSession()
        val frames = listOf(
            DebugFrameView(
                level = 0,
                sourceName = "scripts/main.lua",
                line = 12,
                locals = listOf(DebugVariable("answer", 42L, "number", "42")),
            ),
            DebugFrameView(level = 1, sourceName = "scripts/lib.lua", line = 4),
            DebugFrameView(level = 2, sourceName = "C:\\workspace\\main.lua", line = 7),
        )

        val stackTrace = session.stackTrace(frames)
        val scopes = session.scopes(stackTrace.stackFrames[0].id)

        assertEquals(3, stackTrace.totalFrames)
        assertEquals(
            DapStackFrame(
                id = stackTrace.stackFrames[0].id,
                name = "scripts/main.lua",
                source = DapSource(path = "scripts/main.lua", name = "main.lua"),
                line = 12,
            ),
            stackTrace.stackFrames[0],
        )
        assertNotEquals(stackTrace.stackFrames[0].id, stackTrace.stackFrames[1].id)
        assertEquals(
            DapSource(path = "C:\\workspace\\main.lua", name = "main.lua"),
            stackTrace.stackFrames[2].source,
        )
        assertEquals(1, scopes.scopes.size)
        assertEquals("Locals", scopes.scopes[0].name)
        assertTrue(scopes.scopes[0].variablesReference > 0)
    }

    @Test
    fun `scopes exposes supplied local upvalue and global variables`() {
        val session = DapSession()
        val stackTrace = session.stackTrace(
            listOf(
                DebugFrameView(
                    level = 0,
                    sourceName = "main.lua",
                    line = 3,
                    locals = listOf(DebugVariable("localValue", 1L, "number", "1")),
                    upvalues = listOf(DebugVariable("upvalueValue", "closed", "string", "closed")),
                    globals = listOf(DebugVariable("_VERSION", "KLua", "string", "KLua")),
                ),
            ),
        )

        val scopes = session.scopes(stackTrace.stackFrames[0].id)
        val variables = scopes.scopes.map { scope -> session.variables(scope.variablesReference).variables }

        assertEquals(listOf("Locals", "Upvalues", "Globals"), scopes.scopes.map { scope -> scope.name })
        assertTrue(scopes.scopes.all { scope -> scope.variablesReference > 0 })
        assertEquals(
            listOf(
                listOf(DapVariable("localValue", "1", "number")),
                listOf(DapVariable("upvalueValue", "closed", "string")),
                listOf(DapVariable("_VERSION", "KLua", "string")),
            ),
            variables,
        )
    }

    @Test
    fun `stackTrace pages frames and reports total frame count`() {
        val session = DapSession()
        val frames = listOf(
            DebugFrameView(level = 0, sourceName = "main.lua", line = 1),
            DebugFrameView(level = 1, sourceName = "lib.lua", line = 2),
            DebugFrameView(level = 2, sourceName = "deep.lua", line = 3),
        )

        val stackTrace = session.stackTrace(frames, startFrame = 1, levels = 1)

        assertEquals(3, stackTrace.totalFrames)
        assertEquals(1, stackTrace.stackFrames.size)
        assertEquals(DapSource(path = "lib.lua", name = "lib.lua"), stackTrace.stackFrames.single().source)
        assertEquals(2, stackTrace.stackFrames.single().line)
        assertEquals(DapScopesResponse(emptyList()), session.scopes(frameId = 999))
    }

    @Test
    fun `stackTrace validates paging arguments`() {
        val session = DapSession()

        assertFailsWith<IllegalArgumentException> {
            session.stackTrace(emptyList(), startFrame = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            session.stackTrace(emptyList(), levels = -1)
        }
    }

    @Test
    fun `variables returns scope variables and paged table children`() {
        val session = DapSession()
        val table = linkedMapOf<Any?, Any?>(
            "name" to "KLua",
            2L to 99L,
        )
        val stackTrace = session.stackTrace(
            listOf(
                DebugFrameView(
                    level = 0,
                    sourceName = "main.lua",
                    line = 1,
                    locals = listOf(
                        DebugVariable("tableValue", table, "table", "table(2)"),
                        DebugVariable("scalar", true, "boolean", "true"),
                    ),
                ),
            ),
        )
        val localsReference = session.scopes(stackTrace.stackFrames[0].id).scopes[0].variablesReference

        val locals = session.variables(localsReference, start = 0, count = 2)
        val tableReference = locals.variables[0].variablesReference
        val children = session.variables(tableReference, start = 1, count = 1)

        assertEquals(
            listOf(
                DapVariable("tableValue", "table(2)", "table", variablesReference = tableReference),
                DapVariable("scalar", "true", "boolean", variablesReference = 0),
            ),
            locals.variables,
        )
        assertTrue(tableReference > 0)
        assertEquals(listOf(DapVariable("[2]", "99", "number")), children.variables)
    }

    @Test
    fun `scopes and variables return empty responses for unknown references`() {
        val session = DapSession()

        assertEquals(DapScopesResponse(emptyList()), session.scopes(frameId = 99))
        assertEquals(DapVariablesResponse(emptyList()), session.variables(variablesReference = 99))
    }

    @Test
    fun `evaluate delegates expression and frame to evaluator`() {
        val evaluated = mutableListOf<Pair<String, Int?>>()
        val session = DapSession(
            expressionEvaluator = DapExpressionEvaluator { expression, frame ->
                evaluated += expression to frame?.level
                DebugVariable("result", 123L, "number", "123")
            },
        )
        val stackTrace = session.stackTrace(listOf(DebugFrameView(level = 2, sourceName = "main.lua", line = 8)))

        val response = session.evaluate(
            DapEvaluateRequest(expression = "answer", frameId = stackTrace.stackFrames[0].id, context = "watch"),
        )

        assertEquals(listOf(Pair<String, Int?>("answer", 2)), evaluated)
        assertEquals(DapEvaluateResponse(result = "123", type = "number"), response)
    }

    @Test
    fun `evaluate returns variables reference for table results`() {
        val session = DapSession(
            expressionEvaluator = DapExpressionEvaluator { _, _ ->
                DebugVariable("result", linkedMapOf("name" to "KLua"), "table", "table(1)")
            },
        )

        val response = session.evaluate(DapEvaluateRequest(expression = "state"))
        val children = session.variables(response.variablesReference)

        assertEquals("table(1)", response.result)
        assertEquals("table", response.type)
        assertTrue(response.variablesReference > 0)
        assertEquals(listOf(DapVariable("name", "KLua", "string")), children.variables)
    }

    @Test
    fun `evaluate validates expressions`() {
        val session = DapSession()

        assertFailsWith<IllegalArgumentException> {
            session.evaluate(DapEvaluateRequest(expression = " "))
        }
    }

    @Test
    fun `threads returns default main thread`() {
        val session = DapSession()

        assertEquals(DapThreadsResponse(listOf(DapThread(id = 1, name = "main"))), session.threads())
    }

    @Test
    fun `threads delegates to configured thread provider`() {
        val session = DapSession(
            threadProvider = DapThreadProvider {
                listOf(
                    DapThread(id = 1, name = "main"),
                    DapThread(id = 2, name = "coroutine 2"),
                )
            },
        )

        assertEquals(
            DapThreadsResponse(
                listOf(
                    DapThread(id = 1, name = "main"),
                    DapThread(id = 2, name = "coroutine 2"),
                ),
            ),
            session.threads(),
        )
    }

    @Test
    fun `handle routes initialize command`() {
        val session = DapSession()

        val response = session.handle(
            DapCommandRequest(
                command = "initialize",
                arguments = DapInitializeRequest(clientId = "vscode"),
            ),
        )

        assertEquals("initialize", response.command)
        assertEquals(DapInitializeResponse(DapCapabilities()), response.body)
        assertTrue(session.isInitialized)
        assertEquals("vscode", session.clientId)
    }

    @Test
    fun `handle routes control and thread commands`() {
        val session = DapSession()

        val next = session.handle(DapCommandRequest(command = "next", arguments = DapStepRequest(callDepth = 2)))
        val disconnect = session.handle(
            DapCommandRequest(
                command = "disconnect",
                arguments = DapDisconnectRequest(terminateDebuggee = true),
            ),
        )
        val threads = session.handle(DapCommandRequest(command = "threads"))

        assertEquals(DapCommandResponse("next", DapStepResponse(StepMode.Over(2))), next)
        assertEquals(
            DapCommandResponse(
                "disconnect",
                DapDisconnectResponse(disconnected = true, terminateDebuggee = true),
            ),
            disconnect,
        )
        assertEquals(
            DapCommandResponse("threads", DapThreadsResponse(listOf(DapThread(id = 1, name = "main")))),
            threads,
        )
    }

    @Test
    fun `handle routes stack scope variable and evaluate commands`() {
        val session = DapSession(
            expressionEvaluator = DapExpressionEvaluator { _, _ ->
                DebugVariable("result", linkedMapOf("answer" to 42L), "table", "table(1)")
            },
        )
        val frame = DebugFrameView(
            level = 0,
            sourceName = "main.lua",
            line = 7,
            locals = listOf(DebugVariable("localValue", "KLua", "string", "KLua")),
        )

        val stackTrace = session.handle(
            DapCommandRequest(command = "stackTrace", arguments = DapStackTraceRequest(listOf(frame))),
        ).body as DapStackTraceResponse
        val scopes = session.handle(
            DapCommandRequest(
                command = "scopes",
                arguments = DapScopesRequest(stackTrace.stackFrames[0].id),
            ),
        ).body as DapScopesResponse
        val variables = session.handle(
            DapCommandRequest(
                command = "variables",
                arguments = DapVariablesRequest(scopes.scopes[0].variablesReference),
            ),
        ).body as DapVariablesResponse
        val evaluated = session.handle(
            DapCommandRequest(command = "evaluate", arguments = DapEvaluateRequest("state")),
        ).body as DapEvaluateResponse

        assertEquals(listOf(DapVariable("localValue", "KLua", "string")), variables.variables)
        assertEquals(DapEvaluateResponse("table(1)", "table", evaluated.variablesReference), evaluated)
        assertTrue(evaluated.variablesReference > 0)
    }

    @Test
    fun `handle rejects unsupported commands and missing arguments`() {
        val session = DapSession()

        assertFailsWith<IllegalArgumentException> {
            session.handle(DapCommandRequest(command = "restart"))
        }
        assertFailsWith<IllegalArgumentException> {
            session.handle(DapCommandRequest(command = "initialize"))
        }
    }
}
