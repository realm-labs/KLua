package io.github.realmlabs.klua.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class KLuaCoreRuntimeCoroutineTest {
    @Test
    fun `host functions can signal yielded values to the VM`() {
        val globals = KLuaCoreGlobals.create()
        globals.setFunction("yield") { arguments ->
            KLuaCoreCallResult.Yielded(arguments)
        }
        val chunk = assertIs<KLuaCoreLoad.Success>(
            KLuaCoreRuntime.compile("return yield(42)", "core-yield.lua"),
        ).chunk

        val result = KLuaCoreRuntime.execute(chunk, emptyList(), globals)

        assertEquals(
            KLuaCoreExecution.RuntimeError("attempt to yield from outside a coroutine"),
            result,
        )
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
}
