package io.github.realmlabs.klua.core.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LuaTableTest {
    @Test
    fun `stores and retrieves raw values`() {
        val table = LuaTable()

        table.rawSet(LuaString("answer"), LuaInteger(42))

        assertEquals(LuaInteger(42), table.rawGet(LuaString("answer")))
    }

    @Test
    fun `setting raw nil removes values`() {
        val table = LuaTable()

        table.rawSet(LuaString("answer"), LuaInteger(42))
        table.rawSet(LuaString("answer"), LuaNil)

        assertEquals(LuaNil, table.rawGet(LuaString("answer")))
    }

    @Test
    fun `canonicalizes integral float keys for raw access`() {
        val table = LuaTable()

        table.rawSet(LuaFloat(1.0), LuaString("one"))

        assertEquals(LuaString("one"), table.rawGet(LuaInteger(1)))
    }

    @Test
    fun `rejects invalid raw keys`() {
        val table = LuaTable()

        assertFailsWith<LuaTableKeyException> {
            table.rawGet(LuaNil)
        }
        assertFailsWith<LuaTableKeyException> {
            table.rawSet(LuaFloat(Double.NaN), LuaInteger(1))
        }
    }

    @Test
    fun `stores metatable identity independently from raw entries`() {
        val table = LuaTable()
        val metatable = LuaTable()

        table.rawSet(LuaString("__index"), LuaString("ordinary field"))
        table.metatable = metatable

        assertSame(metatable, table.metatable)
        assertEquals(LuaString("ordinary field"), table.rawGet(LuaString("__index")))
    }

    @Test
    fun `raw length ignores metatable fields`() {
        val table = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaInteger(1), LuaString("from metatable"))
        table.metatable = metatable

        assertEquals(0, table.rawLength())
    }
}
