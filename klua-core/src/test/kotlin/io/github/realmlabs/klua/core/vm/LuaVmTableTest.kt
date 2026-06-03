package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Instruction
import io.github.realmlabs.klua.core.bytecode.Opcode
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.compiler.Compiler
import io.github.realmlabs.klua.core.runtime.LuaSourceVersion
import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUpvalue
import io.github.realmlabs.klua.core.value.LuaValue
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
    fun `executes named table constructor fields`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = { answer = 42 }
                return table.answer
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `executes keyed table constructor fields`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local key = "answer"
                local table = { [key] = 42 }
                return table.answer
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `executes expression keys in table constructors`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = { [1 + 1] = 42 }
                return table[2]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `keeps named table fields out of list indexes`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = {10, answer = 42, 20}
                return table[1], table[2], table.answer
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(10), LuaInteger(20), LuaInteger(42)), result)
    }

    @Test
    fun `gets table length for contiguous list entries`() {
        val result = LuaVm().execute(Compiler.compile("return #{10, 20, 30}"))

        assertEquals(listOf(LuaInteger(3)), result)
    }

    @Test
    fun `gets table length before first missing list key`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = {10, 20, 30}
                table[2] = nil
                return #table
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(1)), result)
    }

    @Test
    fun `ignores named fields for table length`() {
        val result = LuaVm().execute(Compiler.compile("return #{10, answer = 42, 20}"))

        assertEquals(listOf(LuaInteger(2)), result)
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
    fun `executes indexed table assignments`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = {}
                table[1] = 42
                return table[1]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `executes dot field assignments and reads`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = {}
                table.answer = 42
                return table.answer
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `removes table keys assigned nil`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = {10}
                table[1] = nil
                return table[1]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaNil), result)
    }

    @Test
    fun `normalizes integral float table keys`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = {}
                table[1.0] = 42
                return table[1]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `removes normalized numeric table keys`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = {}
                table[1] = 42
                table[1.0] = nil
                return table[1]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaNil), result)
    }

    @Test
    fun `keeps non integral float table keys distinct`() {
        val result = LuaVm().execute(
            Compiler.compile(
                """
                local table = {}
                table[1.5] = 42
                return table[1.5], table[1]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(LuaInteger(42), LuaNil), result)
    }

    @Test
    fun `calls closure index metamethod for missing table keys`() {
        val table = LuaTable()
        val metatable = LuaTable()
        metatable.rawSet(LuaString("__index"), LuaClosure(returnSecondArgumentPrototype()))
        table.metatable = metatable

        val result = LuaVm().execute(tableFieldReadPrototype(table, "answer"))

        assertEquals(listOf(LuaString("answer")), result)
    }

    @Test
    fun `calls closure index metamethod through table index chain`() {
        val table = LuaTable()
        val prototype = LuaTable()
        val metatable = LuaTable()
        val prototypeMetatable = LuaTable()

        prototypeMetatable.rawSet(LuaString("__index"), LuaClosure(returnSecondArgumentPrototype()))
        prototype.metatable = prototypeMetatable
        metatable.rawSet(LuaString("__index"), prototype)
        table.metatable = metatable

        val result = LuaVm().execute(tableFieldReadPrototype(table, "answer"))

        assertEquals(listOf(LuaString("answer")), result)
    }

    @Test
    fun `calls closure newindex metamethod for missing table keys`() {
        val table = LuaTable()
        val sink = LuaTable()
        val metatable = LuaTable()
        metatable.rawSet(LuaString("__newindex"), LuaClosure(storeThirdArgumentPrototype(), listOf(LuaUpvalue(sink))))
        table.metatable = metatable

        LuaVm().execute(tableFieldWritePrototype(table, "answer", LuaInteger(42)))

        assertEquals(LuaNil, table.rawGet(LuaString("answer")))
        assertEquals(LuaInteger(42), sink.rawGet(LuaString("answer")))
    }

    @Test
    fun `calls closure newindex metamethod through table newindex chain`() {
        val table = LuaTable()
        val target = LuaTable()
        val sink = LuaTable()
        val metatable = LuaTable()
        val targetMetatable = LuaTable()

        targetMetatable.rawSet(LuaString("__newindex"), LuaClosure(storeThirdArgumentPrototype(), listOf(LuaUpvalue(sink))))
        target.metatable = targetMetatable
        metatable.rawSet(LuaString("__newindex"), target)
        table.metatable = metatable

        LuaVm().execute(tableFieldWritePrototype(table, "answer", LuaInteger(42)))

        assertEquals(LuaNil, table.rawGet(LuaString("answer")))
        assertEquals(LuaNil, target.rawGet(LuaString("answer")))
        assertEquals(LuaInteger(42), sink.rawGet(LuaString("answer")))
    }

    @Test
    fun `existing table keys bypass closure newindex metamethods`() {
        val table = LuaTable()
        val sink = LuaTable()
        val metatable = LuaTable()

        table.rawSet(LuaString("answer"), LuaInteger(1))
        metatable.rawSet(LuaString("__newindex"), LuaClosure(storeThirdArgumentPrototype(), listOf(LuaUpvalue(sink))))
        table.metatable = metatable

        LuaVm().execute(tableFieldWritePrototype(table, "answer", LuaInteger(42)))

        assertEquals(LuaInteger(42), table.rawGet(LuaString("answer")))
        assertEquals(LuaNil, sink.rawGet(LuaString("answer")))
    }

    @Test
    fun `rejects indexing non table values`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(Compiler.compile("return 1[1]"))
        }

        assertEquals("attempt to index number", error.message)
    }

    @Test
    fun `rejects nil table read keys`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(Compiler.compile("return {}[nil]"))
        }

        assertEquals("table index is nil", error.message)
    }

    @Test
    fun `rejects nil table write keys`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(
                Compiler.compile(
                    """
                    local table = {}
                    table[nil] = 1
                    """.trimIndent(),
                ),
            )
        }

        assertEquals("table index is nil", error.message)
    }

    @Test
    fun `rejects nan table keys`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(
                Compiler.compile(
                    """
                    local table = {}
                    table[0 / 0] = 1
                    """.trimIndent(),
                ),
            )
        }

        assertEquals("table index is NaN", error.message)
    }

    private fun returnSecondArgumentPrototype(): Prototype {
        return Prototype(
            sourceName = "metamethod",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.MOVE, 0, 1),
                Instruction.abc(Opcode.RETURN, 0, 1),
            ),
            constants = emptyArray(),
            lineInfo = intArrayOf(1, 1),
            maxStackSize = 2,
            numParams = 2,
        )
    }

    private fun storeThirdArgumentPrototype(): Prototype {
        return Prototype(
            sourceName = "metamethod",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.GET_UPVALUE, 3, 0),
                Instruction.abc(Opcode.SET_TABLE, 3, 1, 2),
                Instruction.abc(Opcode.RETURN, 0, 0),
            ),
            constants = emptyArray(),
            lineInfo = intArrayOf(1, 1, 1),
            maxStackSize = 4,
            numParams = 3,
        )
    }

    private fun tableFieldReadPrototype(table: LuaTable, field: String): Prototype {
        return Prototype(
            sourceName = "metatable-test",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, 0, 0),
                Instruction.abc(Opcode.GET_FIELD, 1, 0, 1),
                Instruction.abc(Opcode.RETURN, 1, 1),
            ),
            constants = arrayOf<LuaValue>(table, LuaString(field)),
            lineInfo = intArrayOf(1, 1, 1),
            maxStackSize = 2,
        )
    }

    private fun tableFieldWritePrototype(table: LuaTable, field: String, value: LuaValue): Prototype {
        return Prototype(
            sourceName = "metatable-test",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, 0, 0),
                Instruction.abc(Opcode.LOAD_K, 1, 2),
                Instruction.abc(Opcode.SET_FIELD, 0, 1, 1),
                Instruction.abc(Opcode.RETURN, 0, 0),
            ),
            constants = arrayOf(table, LuaString(field), value),
            lineInfo = intArrayOf(1, 1, 1, 1),
            maxStackSize = 2,
        )
    }
}
