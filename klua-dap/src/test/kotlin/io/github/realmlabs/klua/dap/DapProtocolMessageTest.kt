package io.github.realmlabs.klua.dap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DapProtocolMessageTest {
    @Test
    fun `parse reads DAP request message`() {
        val message = DapProtocolCodec.parse(
            """{"seq":1,"type":"request","command":"initialize","arguments":{"adapterID":"klua"}}""",
        )

        assertEquals(
            DapRequestMessage(
                seq = 1,
                command = "initialize",
                arguments = DapJsonObject(linkedMapOf("adapterID" to DapJsonString("klua"))),
            ),
            message,
        )
    }

    @Test
    fun `stringify writes DAP response message`() {
        val message = DapResponseMessage(
            seq = 2,
            requestSeq = 1,
            command = "initialize",
            success = true,
            body = DapJsonObject(
                linkedMapOf("supportsConfigurationDoneRequest" to DapJsonBoolean(true)),
            ),
        )

        assertEquals(
            """{"seq":2,"type":"response","request_seq":1,"success":true,"command":"initialize","body":{"supportsConfigurationDoneRequest":true}}""",
            DapProtocolCodec.stringify(message),
        )
    }

    @Test
    fun `event messages roundtrip through JSON`() {
        val message = DapEventMessage(
            seq = 3,
            event = "stopped",
            body = DapJsonObject(linkedMapOf("reason" to DapJsonString("breakpoint"))),
        )

        val json = DapProtocolCodec.stringify(message)

        assertEquals(message, DapProtocolCodec.parse(json))
    }

    @Test
    fun `parse rejects unsupported message types and malformed seq`() {
        assertFailsWith<IllegalArgumentException> {
            DapProtocolCodec.parse("""{"seq":1,"type":"unknown"}""")
        }
        assertFailsWith<IllegalArgumentException> {
            DapProtocolCodec.parse("""{"seq":1.5,"type":"event","event":"stopped"}""")
        }
    }
}
