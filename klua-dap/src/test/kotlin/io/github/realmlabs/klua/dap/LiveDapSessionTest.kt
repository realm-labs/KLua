package io.github.realmlabs.klua.dap

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.api.LuaConfig
import io.github.realmlabs.klua.api.LuaCoroutineFunction
import io.github.realmlabs.klua.debug.LiveDebugResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveDapSessionTest {
    @Test
    fun `wire client controls a live stopped coroutine and reads its frame state`() {
        val lua = Lua.create()
        lua.globals().set("globalValue", 20L)
        val liveSession = LiveDapSession()
        val mainThread = liveSession.register(
            lua.load(
                """
                local captured = 10
                return function()
                    local value = 1
                    value = value + captured
                    return value + globalValue
                end
                """.trimIndent(),
                "dap-live.lua",
            ).eval().get(1) as LuaCoroutineFunction,
            "main coroutine",
        )
        val workerThread = liveSession.register(returningFunction("dap-worker.lua"), "worker coroutine")
        val wireSession = DapWireSession(liveSession)

        val setBreakpoints = response(
            wireSession.handleJson(
                request(
                    seq = 1,
                    command = "setBreakpoints",
                    arguments = """{"source":{"path":"dap-live.lua"},"breakpoints":[{"line":4}]}""",
                ),
            ),
        )
        assertTrue(setBreakpoints.success)

        val stopped = assertIs<LiveDebugResult.Stopped>(liveSession.start(mainThread.id))
        assertEquals(4, stopped.stop.line)
        val initialEvents = wireSession.drainEvents()
        assertEquals(listOf("thread", "thread", "stopped"), initialEvents.map { event -> event.event })
        assertEquals(
            DapJsonObject(
                linkedMapOf(
                    "reason" to DapJsonString("breakpoint"),
                    "threadId" to DapJsonNumber(mainThread.id.toDouble()),
                    "allThreadsStopped" to DapJsonBoolean(false),
                ),
            ),
            initialEvents.last().body,
        )

        val threads = response(wireSession.handleJson(request(2, "threads"))).body.objectProperty("threads").arrayValues()
        assertEquals(2, threads.size)
        assertEquals(
            listOf(mainThread.id, workerThread.id),
            threads.map { thread -> thread.objectProperty("id").intValue() },
        )

        val stackResponse = response(
            wireSession.handleJson(
                request(3, "stackTrace", """{"threadId":${mainThread.id}}"""),
            ),
        )
        val frame = stackResponse.body.objectProperty("stackFrames").arrayValues().single()
        val frameId = frame.objectProperty("id").intValue()
        assertEquals(4, frame.objectProperty("line").intValue())

        val scopes = response(
            wireSession.handleJson(request(4, "scopes", """{"frameId":$frameId}""")),
        ).body.objectProperty("scopes").arrayValues()
        assertEquals(listOf("Locals", "Upvalues", "Globals"), scopes.map { scope -> scope.objectProperty("name").stringValue() })
        val localsReference = scopes.first().objectProperty("variablesReference").intValue()
        val locals = response(
            wireSession.handleJson(request(5, "variables", """{"variablesReference":$localsReference}""")),
        ).body.objectProperty("variables").arrayValues()
        assertTrue(
            locals.any { variable ->
                variable.objectProperty("name").stringValue() == "value" &&
                    variable.objectProperty("value").stringValue() == "1"
            },
        )

        val evaluation = response(
            wireSession.handleJson(
                request(6, "evaluate", """{"expression":"value + captured + globalValue","frameId":$frameId}"""),
            ),
        )
        assertEquals("31", evaluation.body.objectProperty("result").stringValue())
        assertEquals("number", evaluation.body.objectProperty("type").stringValue())

        val nextExchange = wireSession.handleJsonExchange(
            request(7, "next", """{"threadId":${mainThread.id}}"""),
        ).map(DapProtocolCodec::parse)
        assertTrue(assertIs<DapResponseMessage>(nextExchange.first()).success)
        val stepEvent = assertIs<DapEventMessage>(nextExchange.single { message -> message is DapEventMessage })
        assertEquals("stopped", stepEvent.event)
        assertEquals("step", stepEvent.body.objectProperty("reason").stringValue())
        assertEquals(5, assertIs<LiveDebugResult.Stopped>(liveSession.lastResult(mainThread.id)).stop.line)

        val continueExchange = wireSession.handleJsonExchange(
            request(8, "continue", """{"threadId":${mainThread.id}}"""),
        ).map(DapProtocolCodec::parse)
        assertTrue(assertIs<DapResponseMessage>(continueExchange.first()).success)
        assertEquals(
            listOf(31L),
            assertIs<LiveDebugResult.Returned>(liveSession.lastResult(mainThread.id)).values,
        )
        assertEquals("thread", assertIs<DapEventMessage>(continueExchange.last()).event)
    }

    @Test
    fun `live DAP registration rejects a production runtime with debugging disabled`() {
        val function = Lua.create(LuaConfig(debugEnabled = false)).load(
            "return function() return 1 end",
            "disabled.lua",
        ).eval().get(1) as LuaCoroutineFunction

        val error = assertFailsWith<IllegalStateException> {
            LiveDapSession().register(function)
        }

        assertEquals("debugging is disabled for this Lua runtime", error.message)
    }

    private fun returningFunction(sourceName: String): LuaCoroutineFunction {
        return Lua.create().load(
            "return function() return 2 end",
            sourceName,
        ).eval().get(1) as LuaCoroutineFunction
    }

    private fun request(seq: Int, command: String, arguments: String? = null): String {
        val argumentsProperty = arguments?.let { ",\"arguments\":$it" }.orEmpty()
        return """{"seq":$seq,"type":"request","command":"$command"$argumentsProperty}"""
    }

    private fun response(json: String): DapResponseMessage = assertIs(DapProtocolCodec.parse(json))

    private fun DapJsonValue?.objectProperty(name: String): DapJsonValue {
        return assertIs<DapJsonObject>(this).properties.getValue(name)
    }

    private fun DapJsonValue.arrayValues(): List<DapJsonValue> = assertIs<DapJsonArray>(this).values

    private fun DapJsonValue.intValue(): Int = assertIs<DapJsonNumber>(this).value.toInt()

    private fun DapJsonValue.stringValue(): String = assertIs<DapJsonString>(this).value
}
