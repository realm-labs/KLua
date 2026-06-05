package io.github.realmlabs.klua.dap

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DapMessageTransportTest {
    @Test
    fun `frame prefixes utf8 json body with content length`() {
        val json = """{"seq":1,"command":"initialize"}"""

        val frame = DapMessageTransport.frame(json)
        val header = "Content-Length: ${json.toByteArray(StandardCharsets.UTF_8).size}\r\n\r\n"

        assertTrue(String(frame, StandardCharsets.UTF_8).startsWith(header))
        assertContentEquals(
            json.toByteArray(StandardCharsets.UTF_8),
            frame.copyOfRange(header.toByteArray(StandardCharsets.US_ASCII).size, frame.size),
        )
    }

    @Test
    fun `stream buffers partial frames until body is complete`() {
        val json = """{"seq":1,"type":"request","command":"threads"}"""
        val frame = DapMessageTransport.frame(json)
        val stream = DapMessageStream()

        val first = stream.feed(frame.copyOfRange(0, 12))
        val second = stream.feed(frame.copyOfRange(12, frame.size))

        assertEquals(emptyList(), first)
        assertEquals(listOf(json), second)
    }

    @Test
    fun `stream returns multiple complete frames from one chunk`() {
        val firstJson = """{"seq":1,"value":"hello"}"""
        val secondJson = """{"seq":2,"value":"你好"}"""
        val stream = DapMessageStream()

        val messages = stream.feed(DapMessageTransport.frame(firstJson) + DapMessageTransport.frame(secondJson))

        assertEquals(listOf(firstJson, secondJson), messages)
    }

    @Test
    fun `stream rejects complete frames without valid content length`() {
        val stream = DapMessageStream()

        assertFailsWith<IllegalArgumentException> {
            stream.feed("Content-Type: application/json\r\n\r\n{}".toByteArray(StandardCharsets.US_ASCII))
        }
    }

    @Test
    fun `connection buffers request frames and returns framed responses`() {
        val request = DapMessageTransport.frame(
            """{"seq":5,"type":"request","command":"threads"}""",
        )
        val connection = DapMessageConnection()

        val first = connection.feed(request.copyOfRange(0, 10))
        val second = connection.feed(request.copyOfRange(10, request.size))
        val responseJson = DapMessageStream().feed(second.single()).single()

        assertEquals(emptyList(), first)
        assertEquals(
            """{"seq":1,"type":"response","request_seq":5,"success":true,"command":"threads","body":{"threads":[{"id":1,"name":"main"}]}}""",
            responseJson,
        )
    }

    @Test
    fun `connection returns one framed response per complete request`() {
        val requests = DapMessageTransport.frame(
            """{"seq":1,"type":"request","command":"configurationDone"}""",
        ) + DapMessageTransport.frame(
            """{"seq":2,"type":"request","command":"continue"}""",
        )
        val connection = DapMessageConnection()
        val responseStream = DapMessageStream()

        val responses = connection.feed(requests).flatMap { response -> responseStream.feed(response) }

        assertEquals(
            listOf(
                """{"seq":1,"type":"response","request_seq":1,"success":true,"command":"configurationDone","body":{"configured":true}}""",
                """{"seq":2,"type":"response","request_seq":2,"success":true,"command":"continue","body":{"allThreadsContinued":true}}""",
            ),
            responses,
        )
    }
}
