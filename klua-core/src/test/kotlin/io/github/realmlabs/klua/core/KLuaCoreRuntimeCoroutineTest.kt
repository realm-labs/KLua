package io.github.realmlabs.klua.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class KLuaCoreRuntimeCoroutineTest {
    @Test
    fun `ordinary host functions cannot yield across native boundary`() {
        val globals = KLuaCoreGlobals.create()
        globals.setFunction("yield") { arguments ->
            KLuaCoreCallResult.Yielded(arguments)
        }
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile("return yield(42)", "core-yield.lua"),
        ).chunk

        val result = KLuaCoreRuntime.execute(chunk, emptyList(), globals)

        val error = assertIs<KLuaCoreExecution.RuntimeError>(result)
        assertEquals("attempt to yield across a non-yieldable boundary", error.message)
        assertEquals("core-yield.lua", error.sourceName)
        assertEquals(1, error.line)
    }

    @Test
    fun `lua function values retain source function metadata`() {
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile(
                """
                return function()
                    return 42
                end
                """.trimIndent(),
                "core-function-source.lua",
            ),
        ).chunk

        val result = assertIs<KLuaCoreExecution.Success>(
            KLuaCoreRuntime.execute(chunk),
        )
        val function = assertIs<KLuaCoreValue.FunctionValue>(result.values.single())

        assertNotNull(function.sourceFunction)
    }

    @Test
    fun `core coroutine runner resumes yielded lua functions`() {
        val globals = KLuaCoreGlobals.create()
        globals.set(
            "yield",
            KLuaCoreRuntime.createFunctionValue(
                function = { arguments -> KLuaCoreCallResult.Yielded(arguments) },
                yieldable = true,
            ),
        )
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile(
                """
                return function(value)
                    local resumed = yield(value)
                    return resumed
                end
                """.trimIndent(),
                "core-coroutine-runner.lua",
            ),
        ).chunk

        val result = assertIs<KLuaCoreExecution.Success>(
            KLuaCoreRuntime.execute(chunk, emptyList(), globals),
        )
        val function = assertIs<KLuaCoreValue.FunctionValue>(result.values.single())
        val coroutine = assertNotNull(KLuaCoreRuntime.createCoroutine(function, globals))

        assertEquals(
            KLuaCoreCoroutineExecution.Yielded(listOf(KLuaCoreValue.IntegerValue(42))),
            coroutine.resume(listOf(KLuaCoreValue.IntegerValue(42))),
        )
        assertEquals(
            KLuaCoreCoroutineExecution.Returned(listOf(KLuaCoreValue.StringValue("done"))),
            coroutine.resume(listOf(KLuaCoreValue.StringValue("done"))),
        )
        assertEquals(
            KLuaCoreCoroutineExecution.RuntimeError("cannot resume dead coroutine"),
            coroutine.resume(emptyList()),
        )
    }

    @Test
    fun `core coroutine runtime errors expose source line metadata`() {
        val globals = KLuaCoreGlobals.create()
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile(
                """
                return function()
                    local x = 1
                    return "x" + x
                end
                """.trimIndent(),
                "core-coroutine-error-line.lua",
            ),
        ).chunk
        val function = assertIs<KLuaCoreValue.FunctionValue>(
            assertIs<KLuaCoreExecution.Success>(
                KLuaCoreRuntime.execute(chunk, emptyList(), globals),
            ).values.single(),
        )
        val coroutine = assertNotNull(KLuaCoreRuntime.createCoroutine(function, globals))

        val error = assertIs<KLuaCoreCoroutineExecution.RuntimeError>(coroutine.resume(emptyList()))
        assertEquals("attempt to perform arithmetic on string", error.message)
        assertEquals("core-coroutine-error-line.lua", error.sourceName)
        assertEquals(3, error.line)
        assertEquals(listOf("core-coroutine-error-line.lua" to 3), error.luaFrames.map { frame -> frame.sourceName to frame.line })
        assertEquals(
            "attempt to perform arithmetic on string\n" +
                "stack traceback:\n" +
                "\t[string \"core-coroutine-error-line.lua\"]:3",
            error.traceback,
        )
    }

    @Test
    fun `core coroutine runner resumes top level yieldable native functions`() {
        val globals = KLuaCoreGlobals.create()
        val function = KLuaCoreRuntime.createFunctionValue(
            function = { arguments -> KLuaCoreCallResult.Yielded(arguments) },
            yieldable = true,
        )
        globals.set("host", function)
        val coroutineFunction = assertIs<KLuaCoreValue.FunctionValue>(globals.get("host"))
        val coroutine = assertNotNull(KLuaCoreRuntime.createCoroutine(coroutineFunction, globals))

        assertEquals(
            KLuaCoreCoroutineExecution.Yielded(listOf(KLuaCoreValue.StringValue("host"))),
            coroutine.resume(listOf(KLuaCoreValue.StringValue("host"))),
        )
        assertEquals(
            KLuaCoreCoroutineExecution.Returned(listOf(KLuaCoreValue.StringValue("done"))),
            coroutine.resume(listOf(KLuaCoreValue.StringValue("done"))),
        )
        assertEquals(
            KLuaCoreCoroutineExecution.RuntimeError("cannot resume dead coroutine"),
            coroutine.resume(emptyList()),
        )
    }

    @Test
    fun `core coroutine runner enforces instruction limits`() {
        val globals = KLuaCoreGlobals.create()
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile("return function() while true do end end", "core-coroutine-budget.lua"),
        ).chunk
        val function = assertIs<KLuaCoreValue.FunctionValue>(
            assertIs<KLuaCoreExecution.Success>(
                KLuaCoreRuntime.execute(chunk, emptyList(), globals),
            ).values.single(),
        )
        val coroutine = assertNotNull(
            KLuaCoreRuntime.createCoroutine(
                function,
                globals,
                KLuaCoreExecutionLimits(instructionLimit = 10),
            ),
        )

        val error = assertIs<KLuaCoreCoroutineExecution.RuntimeError>(coroutine.resume(emptyList()))

        assertEquals("instruction limit exceeded", error.message)
        assertEquals("core-coroutine-budget.lua", error.sourceName)
        assertEquals(1, error.line)
    }
}
