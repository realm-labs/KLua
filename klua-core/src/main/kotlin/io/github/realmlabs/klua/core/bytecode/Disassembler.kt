package io.github.realmlabs.klua.core.bytecode

import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaClosure
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaString
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
            Opcode.CLOSURE -> "CLOSURE R${Instruction.a(instruction)} P${Instruction.b(instruction)}"
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
            Opcode.RETURN -> "RETURN R${Instruction.a(instruction)} ${Instruction.b(instruction)}"
        }
    }

    private fun binary(name: String, instruction: Int): String {
        return "$name R${Instruction.a(instruction)} R${Instruction.b(instruction)} R${Instruction.c(instruction)}"
    }

    private fun signedByte(value: Int): Int = if (value >= 128) value - 256 else value

    private fun formatConstant(value: LuaValue): String {
        return when (value) {
            LuaNil -> "nil"
            is LuaBoolean -> value.value.toString()
            is LuaInteger -> value.value.toString()
            is LuaFloat -> value.value.toString()
            is LuaClosure -> "function"
            is LuaString -> "\"${value.value}\""
        }
    }
}
