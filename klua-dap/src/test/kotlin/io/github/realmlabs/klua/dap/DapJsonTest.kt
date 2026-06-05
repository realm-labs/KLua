package io.github.realmlabs.klua.dap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DapJsonTest {
    @Test
    fun `parse reads nested JSON values`() {
        val value = DapJson.parse(
            """
            {
              "seq": 1,
              "type": "request",
              "success": true,
              "missing": null,
              "arguments": {
                "source": "main.lua",
                "lines": [2, 4]
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            DapJsonObject(
                linkedMapOf(
                    "seq" to DapJsonNumber(1.0),
                    "type" to DapJsonString("request"),
                    "success" to DapJsonBoolean(true),
                    "missing" to DapJsonNull,
                    "arguments" to DapJsonObject(
                        linkedMapOf(
                            "source" to DapJsonString("main.lua"),
                            "lines" to DapJsonArray(listOf(DapJsonNumber(2.0), DapJsonNumber(4.0))),
                        ),
                    ),
                ),
            ),
            value,
        )
    }

    @Test
    fun `parse reads string escapes and exponent numbers`() {
        val value = DapJson.parse("""{"text":"line\n\u0041","number":-1.25e2}""")

        assertEquals(
            DapJsonObject(
                linkedMapOf(
                    "text" to DapJsonString("line\nA"),
                    "number" to DapJsonNumber(-125.0),
                ),
            ),
            value,
        )
    }

    @Test
    fun `stringify writes compact JSON with escaped strings`() {
        val value = DapJsonObject(
            linkedMapOf(
                "command" to DapJsonString("initialize"),
                "line" to DapJsonNumber(12.0),
                "message" to DapJsonString("quoted \"text\"\nnext"),
                "items" to DapJsonArray(listOf(DapJsonBoolean(false), DapJsonNull)),
            ),
        )

        assertEquals(
            """{"command":"initialize","line":12,"message":"quoted \"text\"\nnext","items":[false,null]}""",
            DapJson.stringify(value),
        )
    }

    @Test
    fun `parse rejects trailing input and invalid numbers`() {
        assertFailsWith<IllegalArgumentException> {
            DapJson.parse("""{"seq":1} true""")
        }
        assertFailsWith<IllegalArgumentException> {
            DapJson.parse("""{"seq":01}""")
        }
    }
}
