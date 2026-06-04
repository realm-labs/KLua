package io.github.realmlabs.klua.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KLuaCoreRuntimeErrorTest {
    @Test
    fun `runtime errors expose source line metadata`() {
        val result = KLuaCoreRuntime.execute(
            """
            local x = 1
            return "x" + x
            """.trimIndent(),
            "core-runtime-line.lua",
        )

        val error = assertIs<KLuaCoreExecution.RuntimeError>(result)
        assertEquals("attempt to perform arithmetic on string", error.message)
        assertEquals("core-runtime-line.lua", error.sourceName)
        assertEquals(2, error.line)
    }
}
