package io.github.realmlabs.klua.tools

import io.github.realmlabs.klua.dap.DapEventMessage
import io.github.realmlabs.klua.dap.DapJsonBoolean
import io.github.realmlabs.klua.dap.DapJsonObject
import io.github.realmlabs.klua.dap.DapJsonString
import io.github.realmlabs.klua.dap.DapMessageStream
import io.github.realmlabs.klua.dap.DapMessageTransport
import io.github.realmlabs.klua.dap.DapProtocolCodec
import io.github.realmlabs.klua.dap.DapRequestMessage
import io.github.realmlabs.klua.dap.DapResponseMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DapStdioHostTest {
    @Test
    fun `stdio host launches and stops a Lua program before disconnect`() {
        val requests = listOf(
            request(1, "initialize", DapJsonObject(mapOf("adapterID" to DapJsonString("klua")))),
            request(
                2,
                "launch",
                DapJsonObject(
                    mapOf(
                        "program" to DapJsonString("main.lua"),
                        "args" to io.github.realmlabs.klua.dap.DapJsonArray(listOf(DapJsonString("left"))),
                        "stopOnEntry" to DapJsonBoolean(true),
                    ),
                ),
            ),
            request(3, "configurationDone", DapJsonObject(emptyMap())),
            request(4, "threads", null),
            request(5, "disconnect", DapJsonObject(emptyMap())),
        )
        val input = requests
            .map(DapProtocolCodec::stringify)
            .map(DapMessageTransport::frame)
            .fold(ByteArray(0), ByteArray::plus)
        val output = ByteArrayOutputStream()
        val errors = mutableListOf<String>()
        val session = DapLaunchSession(readSource = { "return ..." })

        val exitCode = DapStdioHost(session).run(ByteArrayInputStream(input), output, errors::add)

        assertEquals(0, exitCode)
        assertEquals(emptyList(), errors)
        val messages = DapMessageStream().feed(output.toByteArray()).map(DapProtocolCodec::parse)
        val responses = messages.filterIsInstance<DapResponseMessage>()
        assertEquals(listOf("initialize", "launch", "configurationDone", "threads", "disconnect"), responses.map { it.command })
        assertTrue(responses.all { it.success })
        val events = messages.filterIsInstance<DapEventMessage>()
        assertEquals(listOf("initialized", "thread", "stopped"), events.map { it.event })
    }

    @Test
    fun `launch failure is returned as a DAP error response`() {
        val requests = listOf(
            request(1, "launch", DapJsonObject(mapOf("program" to DapJsonString("missing.lua")))),
            request(2, "disconnect", DapJsonObject(emptyMap())),
        )
        val input = requests
            .map(DapProtocolCodec::stringify)
            .map(DapMessageTransport::frame)
            .fold(ByteArray(0), ByteArray::plus)
        val output = ByteArrayOutputStream()
        val session = DapLaunchSession(readSource = { throw java.nio.file.NoSuchFileException(it.toString()) })

        assertEquals(0, DapStdioHost(session).run(ByteArrayInputStream(input), output) { error(it) })

        val responses = DapMessageStream().feed(output.toByteArray())
            .map(DapProtocolCodec::parse)
            .filterIsInstance<DapResponseMessage>()
        assertEquals(false, responses.first().success)
        assertTrue(responses.first().message.orEmpty().contains("cannot launch missing.lua"))
        assertEquals(true, responses.last().success)
    }

    private fun request(seq: Int, command: String, arguments: DapJsonObject?): DapRequestMessage {
        return DapRequestMessage(
            seq = seq,
            command = command,
            arguments = arguments,
        )
    }
}
