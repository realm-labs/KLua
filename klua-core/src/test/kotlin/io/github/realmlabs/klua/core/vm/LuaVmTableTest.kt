package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.compiler.Compiler
import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class LuaVmTableTest {
    @Test
    fun `executes empty table constructors`() {
        val result = LuaVm().execute(Compiler.compile("return {}"))

        assertIs<LuaTable>(result.single())
    }

    @Test
    fun `creates distinct table values`() {
        val result = LuaVm().execute(Compiler.compile("return {}, {}"))

        assertNotSame(
            assertIs<LuaTable>(result[0]),
            assertIs<LuaTable>(result[1]),
        )
    }

    @Test
    fun `compares tables by identity`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = {}
                return table == table, {} == {}
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaBoolean(true), LuaBoolean(false)), result)
    }
}
