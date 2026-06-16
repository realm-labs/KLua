package io.github.realmlabs.klua.tests

import io.github.realmlabs.klua.api.Lua
import kotlin.test.Test
import kotlin.test.assertEquals

class BytecodePackagingIntegrationTest {
    @Test
    fun `source and bytecode chunks produce equivalent public api results`() {
        val source = """
            local total = 0
            local function add(value)
                total = total + value
                return total
            end
            local left, right, label = ...
            local first = add(left)
            local second = add(right)
            local values = { label = label, [first] = second }
            return first, second, values.label, values[first], first < second
        """.trimIndent()
        val sourceLua = Lua.create()
        val bytecodeLua = Lua.create()
        val bytecode = bytecodeLua.compileBytecode(source, "m17-equivalence.lua")

        val sourceResult = sourceLua.load(source, "m17-equivalence.lua").call(20L, 22L, "sum")
        val bytecodeResult = bytecodeLua.loadBytecode(bytecode).call(20L, 22L, "sum")

        assertEquals(sourceResult.values, bytecodeResult.values)
        assertEquals(listOf(20L, 42L, "sum", 42L, true), bytecodeResult.values)
    }
}
