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
        assertEquals(LuaInteger(42), table.get(LuaString("answer")))
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

    @Test
    fun `table index metamethod resolves missing fields`() {
        val table = LuaTable()
        val prototype = LuaTable()
        val metatable = LuaTable()

        prototype.rawSet(LuaString("answer"), LuaInteger(42))
        metatable.rawSet(LuaString("__index"), prototype)
        table.metatable = metatable

        assertEquals(LuaInteger(42), table.get(LuaString("answer")))
    }

    @Test
    fun `own fields take precedence over table index metamethod`() {
        val table = LuaTable()
        val prototype = LuaTable()
        val metatable = LuaTable()

        table.rawSet(LuaString("answer"), LuaInteger(42))
        prototype.rawSet(LuaString("answer"), LuaInteger(99))
        metatable.rawSet(LuaString("__index"), prototype)
        table.metatable = metatable

        assertEquals(LuaInteger(42), table.get(LuaString("answer")))
    }

    @Test
    fun `raw get bypasses table index metamethod`() {
        val table = LuaTable()
        val prototype = LuaTable()
        val metatable = LuaTable()

        prototype.rawSet(LuaString("answer"), LuaInteger(42))
        metatable.rawSet(LuaString("__index"), prototype)
        table.metatable = metatable

        assertEquals(LuaNil, table.rawGet(LuaString("answer")))
    }

    @Test
    fun `table index metamethod follows chained tables`() {
        val table = LuaTable()
        val firstPrototype = LuaTable()
        val secondPrototype = LuaTable()
        val metatable = LuaTable()
        val prototypeMetatable = LuaTable()

        secondPrototype.rawSet(LuaString("answer"), LuaInteger(42))
        prototypeMetatable.rawSet(LuaString("__index"), secondPrototype)
        firstPrototype.metatable = prototypeMetatable
        metatable.rawSet(LuaString("__index"), firstPrototype)
        table.metatable = metatable

        assertEquals(LuaInteger(42), table.get(LuaString("answer")))
    }

    @Test
    fun `non table index metamethod is ignored until function calls are supported`() {
        val table = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__index"), LuaInteger(42))
        table.metatable = metatable

        assertEquals(LuaNil, table.get(LuaString("answer")))
    }

    @Test
    fun `table index metamethod detects cycles`() {
        val table = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__index"), table)
        table.metatable = metatable

        assertFailsWith<LuaMetatableException> {
            table.get(LuaString("answer"))
        }
    }
}
