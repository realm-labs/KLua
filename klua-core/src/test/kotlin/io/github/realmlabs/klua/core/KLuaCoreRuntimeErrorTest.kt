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

    @Test
    fun `runtime errors expose lua call frames`() {
        val result = KLuaCoreRuntime.execute(
            """
            local function inner()
                return "x" + 1
            end
            local function outer()
                return inner()
            end
            return outer()
            """.trimIndent(),
            "core-trace.lua",
        )

        val error = assertIs<KLuaCoreExecution.RuntimeError>(result)
        assertEquals("attempt to perform arithmetic on string", error.message)
        assertEquals(
            listOf(
                KLuaCoreStackFrame("core-trace.lua", 2),
                KLuaCoreStackFrame("core-trace.lua", 5),
                KLuaCoreStackFrame("core-trace.lua", 7),
            ),
            error.luaFrames,
        )
        assertEquals(
            "attempt to perform arithmetic on string\n" +
                "stack traceback:\n" +
                "\t[string \"core-trace.lua\"]:2\n" +
                "\t[string \"core-trace.lua\"]:5\n" +
                "\t[string \"core-trace.lua\"]:7",
            error.traceback,
        )
    }

    @Test
    fun `runtime tracebacks format file chunk source names`() {
        val result = KLuaCoreRuntime.execute(
            """
            local function inner()
                return "x" + 1
            end
            return inner()
            """.trimIndent(),
            "@/tmp/core-file-trace.lua",
        )

        val error = assertIs<KLuaCoreExecution.RuntimeError>(result)
        assertEquals(
            "attempt to perform arithmetic on string\n" +
                "stack traceback:\n" +
                "\t/tmp/core-file-trace.lua:2\n" +
                "\t/tmp/core-file-trace.lua:4",
            error.traceback,
        )
    }
}
