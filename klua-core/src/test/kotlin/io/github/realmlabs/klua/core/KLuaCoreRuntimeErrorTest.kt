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
                "core-trace.lua" to 2,
                "core-trace.lua" to 5,
                "core-trace.lua" to 7,
            ),
            error.luaFrames.map { frame -> frame.sourceName to frame.line },
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

    @Test
    fun `runtime error frames preserve function debug metadata`() {
        val result = KLuaCoreRuntime.execute(
            """
            local captured = 1
            local function inner(first, ...)
                local _ = captured
                return "x" + first
            end
            local function outer()
                return inner(1, 2, 3)
            end
            return outer()
            """.trimIndent(),
            "core-frame-metadata.lua",
        )

        val error = assertIs<KLuaCoreExecution.RuntimeError>(result)
        val inner = error.luaFrames[0]
        assertEquals("core-frame-metadata.lua", inner.sourceName)
        assertEquals(4, inner.line)
        assertEquals(2, inner.lineDefined)
        assertEquals(5, inner.lastLineDefined)
        assertEquals(1, inner.upvalueCount)
        assertEquals(1, inner.parameterCount)
        assertEquals(true, inner.isVararg)
        assertEquals(listOf(3, 4), inner.activeLines)
    }

    @Test
    fun `runtime errors when instruction limit is exceeded`() {
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile("while true do end", "core-budget.lua"),
        ).chunk

        val result = KLuaCoreRuntime.execute(
            chunk,
            emptyList(),
            KLuaCoreGlobals.create(),
            KLuaCoreExecutionLimits(instructionLimit = 10),
        )

        val error = assertIs<KLuaCoreExecution.RuntimeError>(result)
        assertEquals("instruction limit exceeded", error.message)
        assertEquals("core-budget.lua", error.sourceName)
        assertEquals(1, error.line)
    }

    @Test
    fun `runtime errors when function call instruction limit is exceeded`() {
        val globals = KLuaCoreGlobals.create()
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile("return function() while true do end end", "core-function-budget.lua"),
        ).chunk
        val function = assertIs<KLuaCoreValue.FunctionValue>(
            assertIs<KLuaCoreExecution.Success>(
                KLuaCoreRuntime.execute(chunk, emptyList(), globals),
            ).values.single(),
        )

        val result = KLuaCoreRuntime.callFunction(
            function,
            emptyList(),
            globals,
            limits = KLuaCoreExecutionLimits(instructionLimit = 10),
        )

        val error = assertIs<KLuaCoreCallResult.RuntimeError>(result)
        assertEquals("instruction limit exceeded", error.message)
    }
}
