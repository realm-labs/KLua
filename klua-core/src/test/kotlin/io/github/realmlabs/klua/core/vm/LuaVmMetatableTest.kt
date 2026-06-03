package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Instruction
import io.github.realmlabs.klua.core.bytecode.Opcode
import io.github.realmlabs.klua.core.bytecode.Prototype
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

class LuaVmMetatableTest {
    @Test
    fun `calls closure len metamethod for table length`() {
        val table = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__len"), LuaClosure(returnConstantPrototype(LuaInteger(42))))
        table.metatable = metatable

        val result = LuaVm().execute(tableLengthPrototype(table))

        assertEquals(listOf(LuaInteger(42)), result)
    }

    @Test
    fun `uses raw table length without closure len metamethod`() {
        val table = LuaTable()
        val metatable = LuaTable()

        table.rawSet(LuaInteger(1), LuaString("a"))
        table.rawSet(LuaInteger(2), LuaString("b"))
        metatable.rawSet(LuaString("__len"), LuaInteger(42))
        table.metatable = metatable

        val result = LuaVm().execute(tableLengthPrototype(table))

        assertEquals(listOf(LuaInteger(2)), result)
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
    fun `calls closure call metamethod for table values`() {
        val table = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__call"), LuaClosure(returnSecondArgumentPrototype()))
        table.metatable = metatable

        val result = LuaVm().execute(tableCallPrototype(table, LuaString("answer"), 1))

        assertEquals(listOf(LuaString("answer")), result)
    }

    @Test
    fun `table call metamethod can return multiple results`() {
        val table = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__call"), LuaClosure(returnSelfAndFirstArgumentPrototype()))
        table.metatable = metatable

        val result = LuaVm().execute(tableCallPrototype(table, LuaString("answer"), 2))

        assertEquals(listOf(table, LuaString("answer")), result)
    }

    @Test
    fun `rejects table calls without closure call metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableCallPrototype(LuaTable(), LuaString("answer"), 1))
        }

        assertEquals("attempt to call table", error.message)
    }

    @Test
    fun `calls left closure add metamethod for table addition`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__add"), LuaClosure(returnSecondArgumentPrototype()))
        left.metatable = metatable

        val result = LuaVm().execute(tableAddPrototype(left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `calls right closure add metamethod when left has none`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__add"), LuaClosure(returnSecondArgumentPrototype()))
        right.metatable = metatable

        val result = LuaVm().execute(tableAddPrototype(left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `rejects table addition without closure add metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableAddPrototype(LuaTable(), LuaInteger(42)))
        }

        assertEquals("attempt to perform arithmetic on table", error.message)
    }

    @Test
    fun `calls left closure subtract metamethod for table subtraction`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__sub"), LuaClosure(returnSecondArgumentPrototype()))
        left.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.SUB, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `calls right closure subtract metamethod when left has none`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__sub"), LuaClosure(returnSecondArgumentPrototype()))
        right.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.SUB, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `rejects table subtraction without closure subtract metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableArithmeticPrototype(Opcode.SUB, LuaTable(), LuaInteger(42)))
        }

        assertEquals("attempt to perform arithmetic on table", error.message)
    }

    @Test
    fun `calls left closure multiply metamethod for table multiplication`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__mul"), LuaClosure(returnSecondArgumentPrototype()))
        left.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.MUL, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `calls right closure multiply metamethod when left has none`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__mul"), LuaClosure(returnSecondArgumentPrototype()))
        right.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.MUL, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `rejects table multiplication without closure multiply metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableArithmeticPrototype(Opcode.MUL, LuaTable(), LuaInteger(42)))
        }

        assertEquals("attempt to perform arithmetic on table", error.message)
    }

    @Test
    fun `calls left closure divide metamethod for table division`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__div"), LuaClosure(returnSecondArgumentPrototype()))
        left.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.DIV, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `calls right closure divide metamethod when left has none`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__div"), LuaClosure(returnSecondArgumentPrototype()))
        right.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.DIV, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `rejects table division without closure divide metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableArithmeticPrototype(Opcode.DIV, LuaTable(), LuaInteger(42)))
        }

        assertEquals("attempt to perform arithmetic on table", error.message)
    }

    @Test
    fun `calls left closure floor divide metamethod for table floor division`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__idiv"), LuaClosure(returnSecondArgumentPrototype()))
        left.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.IDIV, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `calls right closure floor divide metamethod when left has none`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__idiv"), LuaClosure(returnSecondArgumentPrototype()))
        right.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.IDIV, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `rejects table floor division without closure floor divide metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableArithmeticPrototype(Opcode.IDIV, LuaTable(), LuaInteger(42)))
        }

        assertEquals("attempt to perform arithmetic on table", error.message)
    }

    @Test
    fun `calls left closure modulo metamethod for table modulo`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__mod"), LuaClosure(returnSecondArgumentPrototype()))
        left.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.MOD, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `calls right closure modulo metamethod when left has none`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__mod"), LuaClosure(returnSecondArgumentPrototype()))
        right.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.MOD, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `rejects table modulo without closure modulo metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableArithmeticPrototype(Opcode.MOD, LuaTable(), LuaInteger(42)))
        }

        assertEquals("attempt to perform arithmetic on table", error.message)
    }

    @Test
    fun `calls left closure power metamethod for table exponentiation`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__pow"), LuaClosure(returnSecondArgumentPrototype()))
        left.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.POW, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `calls right closure power metamethod when left has none`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__pow"), LuaClosure(returnSecondArgumentPrototype()))
        right.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.POW, left, right))

        assertEquals(listOf(right), result)
    }

    @Test
    fun `rejects table exponentiation without closure power metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableArithmeticPrototype(Opcode.POW, LuaTable(), LuaInteger(42)))
        }

        assertEquals("attempt to perform arithmetic on table", error.message)
    }

    @Test
    fun `calls left closure eq metamethod for table equality`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__eq"), LuaClosure(returnConstantPrototype(LuaBoolean(true))))
        left.metatable = metatable

        val result = LuaVm().execute(tableComparePrototype(Opcode.EQ, left, right))

        assertEquals(listOf(LuaBoolean(true)), result)
    }

    @Test
    fun `calls right closure eq metamethod when left has none`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__eq"), LuaClosure(returnConstantPrototype(LuaBoolean(true))))
        right.metatable = metatable

        val result = LuaVm().execute(tableComparePrototype(Opcode.EQ, left, right))

        assertEquals(listOf(LuaBoolean(true)), result)
    }

    @Test
    fun `uses raw table identity before eq metamethod`() {
        val table = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__eq"), LuaClosure(returnConstantPrototype(LuaBoolean(false))))
        table.metatable = metatable

        val result = LuaVm().execute(tableComparePrototype(Opcode.EQ, table, table))

        assertEquals(listOf(LuaBoolean(true)), result)
    }

    @Test
    fun `does not call eq metamethod for table and non table equality`() {
        val table = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__eq"), LuaClosure(returnConstantPrototype(LuaBoolean(true))))
        table.metatable = metatable

        val result = LuaVm().execute(tableComparePrototype(Opcode.EQ, table, LuaInteger(42)))

        assertEquals(listOf(LuaBoolean(false)), result)
    }

    @Test
    fun `calls left closure lt metamethod for table less than`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__lt"), LuaClosure(returnConstantPrototype(LuaBoolean(true))))
        left.metatable = metatable

        val result = LuaVm().execute(tableComparePrototype(Opcode.LT, left, right))

        assertEquals(listOf(LuaBoolean(true)), result)
    }

    @Test
    fun `calls right closure lt metamethod when left has none`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__lt"), LuaClosure(returnConstantPrototype(LuaBoolean(true))))
        right.metatable = metatable

        val result = LuaVm().execute(tableComparePrototype(Opcode.LT, left, right))

        assertEquals(listOf(LuaBoolean(true)), result)
    }

    @Test
    fun `uses falsey lt metamethod result for table less than`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__lt"), LuaClosure(returnConstantPrototype(LuaNil)))
        left.metatable = metatable

        val result = LuaVm().execute(tableComparePrototype(Opcode.LT, left, right))

        assertEquals(listOf(LuaBoolean(false)), result)
    }

    @Test
    fun `rejects table less than without closure lt metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableComparePrototype(Opcode.LT, LuaTable(), LuaInteger(42)))
        }

        assertEquals("attempt to compare table with number", error.message)
    }

    @Test
    fun `calls left closure le metamethod for table less than or equal`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__le"), LuaClosure(returnConstantPrototype(LuaBoolean(true))))
        left.metatable = metatable

        val result = LuaVm().execute(tableComparePrototype(Opcode.LE, left, right))

        assertEquals(listOf(LuaBoolean(true)), result)
    }

    @Test
    fun `calls right closure le metamethod when left has none`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__le"), LuaClosure(returnConstantPrototype(LuaBoolean(true))))
        right.metatable = metatable

        val result = LuaVm().execute(tableComparePrototype(Opcode.LE, left, right))

        assertEquals(listOf(LuaBoolean(true)), result)
    }

    @Test
    fun `uses falsey le metamethod result for table less than or equal`() {
        val left = LuaTable()
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__le"), LuaClosure(returnConstantPrototype(LuaNil)))
        left.metatable = metatable

        val result = LuaVm().execute(tableComparePrototype(Opcode.LE, left, right))

        assertEquals(listOf(LuaBoolean(false)), result)
    }

    @Test
    fun `rejects table less than or equal without closure le metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableComparePrototype(Opcode.LE, LuaTable(), LuaInteger(42)))
        }

        assertEquals("attempt to compare table with number", error.message)
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

    private fun returnConstantPrototype(value: LuaValue): Prototype {
        return Prototype(
            sourceName = "metamethod",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, 0, 0),
                Instruction.abc(Opcode.RETURN, 0, 1),
            ),
            constants = arrayOf(value),
            lineInfo = intArrayOf(1, 1),
            maxStackSize = 1,
            numParams = 1,
        )
    }

    private fun returnSelfAndFirstArgumentPrototype(): Prototype {
        return Prototype(
            sourceName = "metamethod",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.RETURN, 0, 2),
            ),
            constants = emptyArray(),
            lineInfo = intArrayOf(1),
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

    private fun tableCallPrototype(table: LuaTable, argument: LuaValue, resultCount: Int): Prototype {
        return Prototype(
            sourceName = "metatable-test",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, 0, 0),
                Instruction.abc(Opcode.LOAD_K, 1, 1),
                Instruction.abc(Opcode.CALL, 0, 1, resultCount),
                Instruction.abc(Opcode.RETURN, 0, resultCount),
            ),
            constants = arrayOf(table, argument),
            lineInfo = intArrayOf(1, 1, 1, 1),
            maxStackSize = 2,
        )
    }

    private fun tableLengthPrototype(table: LuaTable): Prototype {
        return Prototype(
            sourceName = "metatable-test",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, 0, 0),
                Instruction.abc(Opcode.LEN, 0, 0),
                Instruction.abc(Opcode.RETURN, 0, 1),
            ),
            constants = arrayOf(table),
            lineInfo = intArrayOf(1, 1, 1),
            maxStackSize = 1,
        )
    }

    private fun tableAddPrototype(left: LuaValue, right: LuaValue): Prototype {
        return tableArithmeticPrototype(Opcode.ADD, left, right)
    }

    private fun tableArithmeticPrototype(opcode: Opcode, left: LuaValue, right: LuaValue): Prototype {
        return Prototype(
            sourceName = "metatable-test",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, 0, 0),
                Instruction.abc(Opcode.LOAD_K, 1, 1),
                Instruction.abc(opcode, 0, 0, 1),
                Instruction.abc(Opcode.RETURN, 0, 1),
            ),
            constants = arrayOf(left, right),
            lineInfo = intArrayOf(1, 1, 1, 1),
            maxStackSize = 2,
        )
    }

    private fun tableComparePrototype(opcode: Opcode, left: LuaValue, right: LuaValue): Prototype {
        return Prototype(
            sourceName = "metatable-test",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, 0, 0),
                Instruction.abc(Opcode.LOAD_K, 1, 1),
                Instruction.abc(opcode, 0, 0, 1),
                Instruction.abc(Opcode.RETURN, 0, 1),
            ),
            constants = arrayOf(left, right),
            lineInfo = intArrayOf(1, 1, 1, 1),
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
