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
import io.github.realmlabs.klua.core.value.LuaValue
import kotlin.test.Test
import kotlin.test.assertEquals

class LuaVmMetamethodOperatorTest {
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

    @Test
    fun `calls left closure concat metamethod for table concatenation`() {
        val left = LuaTable()
        val right = LuaString("suffix")
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__concat"), LuaClosure(returnConstantPrototype(LuaString("joined"))))
        left.metatable = metatable

        val result = LuaVm().execute(tableConcatPrototype(left, right))

        assertEquals(listOf(LuaString("joined")), result)
    }

    @Test
    fun `calls right closure concat metamethod when left has none`() {
        val left = LuaString("prefix")
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__concat"), LuaClosure(returnConstantPrototype(LuaString("joined"))))
        right.metatable = metatable

        val result = LuaVm().execute(tableConcatPrototype(left, right))

        assertEquals(listOf(LuaString("joined")), result)
    }

    @Test
    fun `continues to use primitive concatenation for stringable values`() {
        val result = LuaVm().execute(tableConcatPrototype(LuaString("a"), LuaInteger(1)))

        assertEquals(listOf(LuaString("a1")), result)
    }

    @Test
    fun `rejects table concatenation without closure concat metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableConcatPrototype(LuaTable(), LuaString("suffix")))
        }

        assertEquals("attempt to concatenate table", error.message)
    }

    @Test
    fun `calls closure unary minus metamethod for table values`() {
        val table = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__unm"), LuaClosure(returnConstantPrototype(LuaString("negated"))))
        table.metatable = metatable

        val result = LuaVm().execute(tableUnaryPrototype(Opcode.UNM, table))

        assertEquals(listOf(LuaString("negated")), result)
    }

    @Test
    fun `continues to use primitive unary minus for numbers`() {
        val result = LuaVm().execute(tableUnaryPrototype(Opcode.UNM, LuaInteger(42)))

        assertEquals(listOf(LuaInteger(-42)), result)
    }

    @Test
    fun `rejects table unary minus without closure unary minus metamethod`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableUnaryPrototype(Opcode.UNM, LuaTable()))
        }

        assertEquals("attempt to perform arithmetic on table", error.message)
    }

    @Test
    fun `calls left closure band metamethod for table bitwise and`() {
        val left = LuaTable()
        val right = LuaInteger(3)
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__band"), LuaClosure(returnConstantPrototype(LuaString("anded"))))
        left.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.BAND, left, right))

        assertEquals(listOf(LuaString("anded")), result)
    }

    @Test
    fun `calls right closure band metamethod when left has none`() {
        val left = LuaInteger(6)
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__band"), LuaClosure(returnConstantPrototype(LuaString("anded"))))
        right.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.BAND, left, right))

        assertEquals(listOf(LuaString("anded")), result)
    }

    @Test
    fun `continues to use primitive bitwise and for integers`() {
        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.BAND, LuaInteger(6), LuaInteger(3)))

        assertEquals(listOf(LuaInteger(2)), result)
    }

    @Test
    fun `rejects table bitwise and without closure band metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableArithmeticPrototype(Opcode.BAND, LuaTable(), LuaInteger(3)))
        }

        assertEquals("attempt to perform bitwise operation on table", error.message)
    }

    @Test
    fun `calls left closure bor metamethod for table bitwise or`() {
        val left = LuaTable()
        val right = LuaInteger(1)
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__bor"), LuaClosure(returnConstantPrototype(LuaString("ored"))))
        left.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.BOR, left, right))

        assertEquals(listOf(LuaString("ored")), result)
    }

    @Test
    fun `calls right closure bor metamethod when left has none`() {
        val left = LuaInteger(4)
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__bor"), LuaClosure(returnConstantPrototype(LuaString("ored"))))
        right.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.BOR, left, right))

        assertEquals(listOf(LuaString("ored")), result)
    }

    @Test
    fun `continues to use primitive bitwise or for integers`() {
        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.BOR, LuaInteger(4), LuaInteger(1)))

        assertEquals(listOf(LuaInteger(5)), result)
    }

    @Test
    fun `rejects table bitwise or without closure bor metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableArithmeticPrototype(Opcode.BOR, LuaTable(), LuaInteger(1)))
        }

        assertEquals("attempt to perform bitwise operation on table", error.message)
    }

    @Test
    fun `calls left closure bxor metamethod for table bitwise xor`() {
        val left = LuaTable()
        val right = LuaInteger(3)
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__bxor"), LuaClosure(returnConstantPrototype(LuaString("xored"))))
        left.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.BXOR, left, right))

        assertEquals(listOf(LuaString("xored")), result)
    }

    @Test
    fun `calls right closure bxor metamethod when left has none`() {
        val left = LuaInteger(6)
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__bxor"), LuaClosure(returnConstantPrototype(LuaString("xored"))))
        right.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.BXOR, left, right))

        assertEquals(listOf(LuaString("xored")), result)
    }

    @Test
    fun `continues to use primitive bitwise xor for integers`() {
        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.BXOR, LuaInteger(6), LuaInteger(3)))

        assertEquals(listOf(LuaInteger(5)), result)
    }

    @Test
    fun `rejects table bitwise xor without closure bxor metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableArithmeticPrototype(Opcode.BXOR, LuaTable(), LuaInteger(3)))
        }

        assertEquals("attempt to perform bitwise operation on table", error.message)
    }

    @Test
    fun `calls left closure shl metamethod for table shift left`() {
        val left = LuaTable()
        val right = LuaInteger(2)
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__shl"), LuaClosure(returnConstantPrototype(LuaString("shifted"))))
        left.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.SHL, left, right))

        assertEquals(listOf(LuaString("shifted")), result)
    }

    @Test
    fun `calls right closure shl metamethod when left has none`() {
        val left = LuaInteger(4)
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__shl"), LuaClosure(returnConstantPrototype(LuaString("shifted"))))
        right.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.SHL, left, right))

        assertEquals(listOf(LuaString("shifted")), result)
    }

    @Test
    fun `continues to use primitive shift left for integers`() {
        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.SHL, LuaInteger(4), LuaInteger(2)))

        assertEquals(listOf(LuaInteger(16)), result)
    }

    @Test
    fun `rejects table shift left without closure shl metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableArithmeticPrototype(Opcode.SHL, LuaTable(), LuaInteger(2)))
        }

        assertEquals("attempt to perform bitwise operation on table", error.message)
    }

    @Test
    fun `calls left closure shr metamethod for table shift right`() {
        val left = LuaTable()
        val right = LuaInteger(2)
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__shr"), LuaClosure(returnConstantPrototype(LuaString("shifted"))))
        left.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.SHR, left, right))

        assertEquals(listOf(LuaString("shifted")), result)
    }

    @Test
    fun `calls right closure shr metamethod when left has none`() {
        val left = LuaInteger(16)
        val right = LuaTable()
        val metatable = LuaTable()

        metatable.rawSet(LuaString("__shr"), LuaClosure(returnConstantPrototype(LuaString("shifted"))))
        right.metatable = metatable

        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.SHR, left, right))

        assertEquals(listOf(LuaString("shifted")), result)
    }

    @Test
    fun `continues to use primitive shift right for integers`() {
        val result = LuaVm().execute(tableArithmeticPrototype(Opcode.SHR, LuaInteger(16), LuaInteger(2)))

        assertEquals(listOf(LuaInteger(4)), result)
    }

    @Test
    fun `rejects table shift right without closure shr metamethods`() {
        val error = kotlin.test.assertFailsWith<LuaVmException> {
            LuaVm().execute(tableArithmeticPrototype(Opcode.SHR, LuaTable(), LuaInteger(2)))
        }

        assertEquals("attempt to perform bitwise operation on table", error.message)
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

    private fun tableConcatPrototype(left: LuaValue, right: LuaValue): Prototype {
        return Prototype(
            sourceName = "metatable-test",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, 0, 0),
                Instruction.abc(Opcode.LOAD_K, 1, 1),
                Instruction.abc(Opcode.CONCAT, 0, 0, 1),
                Instruction.abc(Opcode.RETURN, 0, 1),
            ),
            constants = arrayOf(left, right),
            lineInfo = intArrayOf(1, 1, 1, 1),
            maxStackSize = 2,
        )
    }

    private fun tableUnaryPrototype(opcode: Opcode, value: LuaValue): Prototype {
        return Prototype(
            sourceName = "metatable-test",
            version = LuaSourceVersion.LUA_54,
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, 0, 0),
                Instruction.abc(opcode, 0, 0),
                Instruction.abc(Opcode.RETURN, 0, 1),
            ),
            constants = arrayOf(value),
            lineInfo = intArrayOf(1, 1, 1),
            maxStackSize = 1,
        )
    }
}
