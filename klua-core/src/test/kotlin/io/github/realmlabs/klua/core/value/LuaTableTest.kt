package io.github.realmlabs.klua.core.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LuaTableTest {
    @Test
    fun `stores and retrieves raw values`() {
        val table = LuaTable()

        table.rawSet(LuaString("answer"), LuaInteger(42))

        assertEquals(LuaInteger(42), table.rawGet(LuaString("answer")))
        assertEquals(LuaInteger(42), table.get(LuaString("answer")))
    }

    @Test
    fun `matches string keys by raw bytes`() {
        val table = LuaTable()
        val rawUtf8Key = byteArrayOf(195.toByte(), 169.toByte()).toLuaByteString()

        table.rawSet(LuaString(rawUtf8Key), LuaInteger(42))

        assertEquals(LuaInteger(42), table.rawGet(LuaString("é")))
        assertEquals(LuaInteger(42), table.get(LuaString("é")))
    }

    @Test
    fun `matches distinct JVM strings that encode the same raw Lua bytes`() {
        val table = LuaTable()
        val surrogateCodeUnit = "\uD800"
        val rawSurrogateBytes = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()).toLuaByteString()
        val codeUnitKey = LuaString(surrogateCodeUnit)
        val rawBytesKey = LuaString(rawSurrogateBytes)

        assertNotEquals(surrogateCodeUnit, rawSurrogateBytes)
        assertEquals(codeUnitKey, rawBytesKey)
        assertEquals(codeUnitKey.hashCode(), rawBytesKey.hashCode())

        table.rawSet(codeUnitKey, LuaInteger(42))

        assertEquals(LuaInteger(42), table.rawGet(rawBytesKey))
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
    fun `keeps out of range integral float raw keys distinct`() {
        val table = LuaTable()

        table.rawSet(LuaFloat(9223372036854775808.0), LuaString("float upper"))
        table.rawSet(LuaInteger(Long.MAX_VALUE), LuaString("integer max"))
        table.rawSet(LuaFloat(Long.MIN_VALUE.toDouble()), LuaString("integer min"))

        assertEquals(LuaString("float upper"), table.rawGet(LuaFloat(9223372036854775808.0)))
        assertEquals(LuaString("integer max"), table.rawGet(LuaInteger(Long.MAX_VALUE)))
        assertEquals(LuaString("integer min"), table.rawGet(LuaInteger(Long.MIN_VALUE)))
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
    fun `rebalances dense integer keys into the array before hash entries`() {
        val table = LuaTable()

        table.rawSet(LuaInteger(8), LuaString("eight"))
        for (index in 1L..7L) {
            table.rawSet(LuaInteger(index), LuaInteger(index * 10L))
        }
        table.rawSet(LuaString("name"), LuaString("klua"))

        assertTrue(table.arrayCapacity >= 8)
        assertEquals(LuaString("eight"), table.rawGet(LuaInteger(8)))
        assertEquals(9, table.rawSize)
        assertEquals(
            (1L..8L).map(::LuaInteger),
            table.rawEntries().keys.take(8),
        )
        assertEquals(LuaString("name"), table.rawEntries().keys.last())
    }

    @Test
    fun `keeps sparse and nonpositive integer keys in the hash part`() {
        val table = LuaTable()

        table.rawSet(LuaInteger(-1), LuaString("negative"))
        table.rawSet(LuaInteger(1_000_000), LuaString("sparse"))

        assertEquals(0, table.arrayCapacity)
        assertTrue(table.hashCapacity >= 4)
        assertEquals(LuaString("negative"), table.rawGet(LuaInteger(-1)))
        assertEquals(LuaString("sparse"), table.rawGet(LuaInteger(1_000_000)))
    }

    @Test
    fun `hash deletion compaction preserves every surviving and reinserted key`() {
        val table = LuaTable()
        for (index in 0 until 64) {
            table.rawSet(LuaString("key-$index"), LuaInteger(index.toLong()))
        }
        for (index in 0 until 64 step 2) {
            table.rawSet(LuaString("key-$index"), LuaNil)
        }
        for (index in 0 until 64 step 2) {
            table.rawSet(LuaString("key-$index"), LuaInteger((index + 100).toLong()))
        }

        assertEquals(64, table.rawSize)
        for (index in 0 until 64) {
            val expected = if (index % 2 == 0) index + 100 else index
            assertEquals(LuaInteger(expected.toLong()), table.rawGet(LuaString("key-$index")))
        }
    }

    @Test
    fun `content and shape versions distinguish replacement from structural mutation`() {
        val table = LuaTable()
        val metatable = LuaTable()

        table.rawSet(LuaString("answer"), LuaInteger(1))
        assertEquals(1, table.version)
        assertEquals(1, table.shapeVersion)

        table.rawSet(LuaString("answer"), LuaInteger(2))
        assertEquals(2, table.version)
        assertEquals(1, table.shapeVersion)

        table.rawSet(LuaString("answer"), LuaNil)
        assertEquals(3, table.version)
        assertEquals(2, table.shapeVersion)

        table.rawSet(LuaString("answer"), LuaNil)
        assertEquals(3, table.version)
        assertEquals(2, table.shapeVersion)

        table.metatable = metatable
        assertEquals(4, table.version)
        assertEquals(3, table.shapeVersion)
        table.metatable = metatable
        assertEquals(4, table.version)
        assertEquals(3, table.shapeVersion)

        metatable.rawSet(LuaString("__index"), LuaInteger(42))
        assertEquals(4, table.version)
        assertEquals(1, metatable.version)
        assertEquals(1, metatable.shapeVersion)
        metatable.rawSet(LuaString("__index"), LuaInteger(43))
        assertEquals(2, metatable.version)
        assertEquals(1, metatable.shapeVersion)
    }

    @Test
    fun `raw length returns stable borders as dense tails change`() {
        val table = LuaTable()
        for (index in 1L..4L) {
            table.rawSetInteger(index, LuaInteger(index))
        }

        assertEquals(4, table.rawLength())
        table.rawSetInteger(4, LuaNil)
        assertEquals(3, table.rawLength())
        table.rawSetInteger(2, LuaNil)
        assertEquals(3, table.rawLength())
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

    @Test
    fun `table newindex metamethod stores missing fields in delegate table`() {
        val table = LuaTable()
        val target = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__newindex"), target)
        table.metatable = metatable

        table.set(LuaString("answer"), LuaInteger(42))

        assertEquals(LuaNil, table.rawGet(LuaString("answer")))
        assertEquals(LuaInteger(42), target.rawGet(LuaString("answer")))
    }

    @Test
    fun `own fields take precedence over table newindex metamethod`() {
        val table = LuaTable()
        val target = LuaTable()
        val metatable = LuaTable()

        table.rawSet(LuaString("answer"), LuaInteger(1))
        target.rawSet(LuaString("answer"), LuaInteger(2))
        metatable.rawSet(LuaString("__newindex"), target)
        table.metatable = metatable

        table.set(LuaString("answer"), LuaInteger(42))

        assertEquals(LuaInteger(42), table.rawGet(LuaString("answer")))
        assertEquals(LuaInteger(2), target.rawGet(LuaString("answer")))
    }

    @Test
    fun `raw set bypasses table newindex metamethod`() {
        val table = LuaTable()
        val target = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__newindex"), target)
        table.metatable = metatable

        table.rawSet(LuaString("answer"), LuaInteger(42))

        assertEquals(LuaInteger(42), table.rawGet(LuaString("answer")))
        assertEquals(LuaNil, target.rawGet(LuaString("answer")))
    }

    @Test
    fun `table newindex metamethod follows chained tables`() {
        val table = LuaTable()
        val firstTarget = LuaTable()
        val secondTarget = LuaTable()
        val metatable = LuaTable()
        val targetMetatable = LuaTable()

        targetMetatable.rawSet(LuaString("__newindex"), secondTarget)
        firstTarget.metatable = targetMetatable
        metatable.rawSet(LuaString("__newindex"), firstTarget)
        table.metatable = metatable

        table.set(LuaString("answer"), LuaInteger(42))

        assertEquals(LuaNil, table.rawGet(LuaString("answer")))
        assertEquals(LuaNil, firstTarget.rawGet(LuaString("answer")))
        assertEquals(LuaInteger(42), secondTarget.rawGet(LuaString("answer")))
    }

    @Test
    fun `non table newindex metamethod is ignored until function calls are supported`() {
        val table = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__newindex"), LuaInteger(42))
        table.metatable = metatable

        table.set(LuaString("answer"), LuaInteger(99))

        assertEquals(LuaInteger(99), table.rawGet(LuaString("answer")))
    }

    @Test
    fun `table newindex metamethod detects cycles`() {
        val table = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__newindex"), table)
        table.metatable = metatable

        assertFailsWith<LuaMetatableException> {
            table.set(LuaString("answer"), LuaInteger(42))
        }
    }
}
