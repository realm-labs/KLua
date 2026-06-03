package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.compiler.Compiler
import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
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

    @Test
    fun `executes list table constructors and bracket indexes`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = {10, 20}
                return table[1], table[2]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(10), LuaInteger(20)), result)
    }

    @Test
    fun `returns nil for missing table keys`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = {10}
                return table[2]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaNil), result)
    }

    @Test
    fun `rejects indexing non table values`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(Compiler.compile("return 1[1]"))
        }

        assertEquals("attempt to index number", error.message)
    }
}
