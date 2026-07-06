package io.github.realmlabs.klua.core.bytecode

import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaNativeFunction
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaUserData
import io.github.realmlabs.klua.core.value.LuaValue

internal object Disassembler {
    fun disassemble(prototype: Prototype): String {
        return prototype.code.indices.joinToString(separator = "\n") { pc ->
            val instruction = prototype.code[pc]
            val line = prototype.lineInfo.getOrElse(pc) { 0 }
            "%04d  [%d]  %s".format(pc, line, disassembleInstruction(instruction, prototype))
        }
    }

    private fun disassembleInstruction(instruction: Int, prototype: Prototype): String {
        return when (val opcode = Instruction.opcode(instruction)) {
            Opcode.LOAD_NIL -> "LOAD_NIL R${Instruction.a(instruction)}"
            Opcode.LOAD_BOOL -> "LOAD_BOOL R${Instruction.a(instruction)} ${Instruction.b(instruction) != 0}"
            Opcode.LOAD_INT -> "LOAD_INT R${Instruction.a(instruction)} ${signedByte(Instruction.b(instruction))}"
            Opcode.LOAD_FLOAT -> {
                val constant = prototype.constants[Instruction.b(instruction)]
                "LOAD_FLOAT R${Instruction.a(instruction)} ${formatConstant(constant)}"
            }
            Opcode.LOAD_K -> {
                val constant = prototype.constants[Instruction.b(instruction)]
                "LOAD_K R${Instruction.a(instruction)} K${Instruction.b(instruction)} ; ${formatConstant(constant)}"
            }
            Opcode.VARARG -> "VARARG R${Instruction.a(instruction)} ${formatCount(Instruction.b(instruction))}"
            Opcode.NEW_TABLE -> "NEW_TABLE R${Instruction.a(instruction)}"
            Opcode.GET_TABLE -> binary("GET_TABLE", instruction)
            Opcode.SET_TABLE -> binary("SET_TABLE", instruction)
            Opcode.GET_FIELD -> fieldGet(instruction, prototype)
            Opcode.SET_FIELD -> fieldSet(instruction, prototype)
            Opcode.GET_GLOBAL -> globalGet(instruction, prototype)
            Opcode.SET_GLOBAL -> globalSet(instruction, prototype)
            Opcode.CLOSURE -> "CLOSURE R${Instruction.a(instruction)} P${Instruction.b(instruction)}"
            Opcode.GET_UPVALUE -> "GET_UPVALUE R${Instruction.a(instruction)} U${Instruction.b(instruction)}"
            Opcode.SET_UPVALUE -> "SET_UPVALUE U${Instruction.a(instruction)} R${Instruction.b(instruction)}"
            Opcode.CLOSE_UPVALUES -> "CLOSE_UPVALUES R${Instruction.a(instruction)}"
            Opcode.MOVE -> "MOVE R${Instruction.a(instruction)} R${Instruction.b(instruction)}"
            Opcode.ADD -> binary("ADD", instruction)
            Opcode.SUB -> binary("SUB", instruction)
            Opcode.MUL -> binary("MUL", instruction)
            Opcode.DIV -> binary("DIV", instruction)
            Opcode.IDIV -> binary("IDIV", instruction)
            Opcode.MOD -> binary("MOD", instruction)
            Opcode.POW -> binary("POW", instruction)
            Opcode.CONCAT -> binary("CONCAT", instruction)
            Opcode.BAND -> binary("BAND", instruction)
            Opcode.BOR -> binary("BOR", instruction)
            Opcode.BXOR -> binary("BXOR", instruction)
            Opcode.SHL -> binary("SHL", instruction)
            Opcode.SHR -> binary("SHR", instruction)
            Opcode.BNOT -> "BNOT R${Instruction.a(instruction)} R${Instruction.b(instruction)}"
            Opcode.LEN -> "LEN R${Instruction.a(instruction)} R${Instruction.b(instruction)}"
            Opcode.UNM -> "UNM R${Instruction.a(instruction)} R${Instruction.b(instruction)}"
            Opcode.NOT -> "NOT R${Instruction.a(instruction)} R${Instruction.b(instruction)}"
            Opcode.EQ -> binary("EQ", instruction)
            Opcode.LT -> binary("LT", instruction)
            Opcode.LE -> binary("LE", instruction)
            Opcode.TEST -> "TEST R${Instruction.a(instruction)} ${signedByte(Instruction.b(instruction))}"
            Opcode.JMP -> "JMP ${signedByte(Instruction.a(instruction))}"
            Opcode.FOR_TEST -> "FOR_TEST R${Instruction.a(instruction)} ${signedByte(Instruction.b(instruction))}"
            Opcode.FOR_LOOP -> "FOR_LOOP R${Instruction.a(instruction)} ${signedByte(Instruction.b(instruction))}"
            Opcode.CALL -> "CALL R${Instruction.a(instruction)} ${formatCount(Instruction.b(instruction))} ${formatCount(Instruction.c(instruction))}"
            Opcode.RETURN -> "RETURN R${Instruction.a(instruction)} ${formatCount(Instruction.b(instruction))}"
            Opcode.CHECK_GLOBAL_NIL -> globalNilCheck(instruction, prototype)
            Opcode.GET_ENV -> "GET_ENV R${Instruction.a(instruction)}"
        }
    }

    private fun formatCount(count: Int): String = if (count == OPEN_RESULT_COUNT) "*" else count.toString()

    private fun binary(name: String, instruction: Int): String {
        return "$name R${Instruction.a(instruction)} R${Instruction.b(instruction)} R${Instruction.c(instruction)}"
    }

    private fun fieldGet(instruction: Int, prototype: Prototype): String {
        val constant = prototype.constants[Instruction.c(instruction)]
        return "GET_FIELD R${Instruction.a(instruction)} R${Instruction.b(instruction)} K${Instruction.c(instruction)} ; ${formatConstant(constant)}"
    }

    private fun fieldSet(instruction: Int, prototype: Prototype): String {
        val constant = prototype.constants[Instruction.b(instruction)]
        return "SET_FIELD R${Instruction.a(instruction)} K${Instruction.b(instruction)} R${Instruction.c(instruction)} ; ${formatConstant(constant)}"
    }

    private fun globalGet(instruction: Int, prototype: Prototype): String {
        val constant = prototype.constants[Instruction.b(instruction)]
        return "GET_GLOBAL R${Instruction.a(instruction)} K${Instruction.b(instruction)} ; ${formatConstant(constant)}"
    }

    private fun globalSet(instruction: Int, prototype: Prototype): String {
        val constant = prototype.constants[Instruction.a(instruction)]
        return "SET_GLOBAL K${Instruction.a(instruction)} R${Instruction.b(instruction)} ; ${formatConstant(constant)}"
    }

    private fun globalNilCheck(instruction: Int, prototype: Prototype): String {
        val constant = prototype.constants[Instruction.a(instruction)]
        return "CHECK_GLOBAL_NIL K${Instruction.a(instruction)} ; ${formatConstant(constant)}"
    }

    private fun signedByte(value: Int): Int = if (value >= 128) value - 256 else value

    private fun formatConstant(value: LuaValue): String {
        return when (value) {
            LuaNil -> "nil"
            is LuaBoolean -> value.value.toString()
            is LuaInteger -> value.value.toString()
            is LuaFloat -> value.value.toString()
            is LuaClosure,
            is LuaNativeFunction,
            -> "function"
            is LuaString -> "\"${value.value}\""
            is LuaTable -> "table"
            is LuaUserData -> "userdata"
        }
    }
}
