package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.Instruction
import io.github.realmlabs.klua.core.bytecode.Opcode
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.value.LuaBoolean
import io.github.realmlabs.klua.core.value.LuaFloat
import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaNil
import io.github.realmlabs.klua.core.value.LuaValue

internal class LuaVm {
    fun execute(prototype: Prototype): List<LuaValue> {
        val stack = LuaStack(prototype.maxStackSize)
        val frame = CallFrame(prototype)

        while (frame.pc < prototype.code.size) {
            val instruction = prototype.code[frame.pc++]
            when (Instruction.opcode(instruction)) {
                Opcode.LOAD_NIL -> stack.set(register(frame, Instruction.a(instruction)), LuaNil)
                Opcode.LOAD_BOOL -> {
                    stack.set(register(frame, Instruction.a(instruction)), LuaBoolean(Instruction.b(instruction) != 0))
                }
                Opcode.LOAD_INT -> {
                    stack.set(register(frame, Instruction.a(instruction)), LuaInteger(signedByte(Instruction.b(instruction)).toLong()))
                }
                Opcode.LOAD_FLOAT -> {
                    val constant = constant(prototype, Instruction.b(instruction))
                    if (constant !is LuaFloat) {
                        throw LuaVmException("LOAD_FLOAT expected float constant at K${Instruction.b(instruction)}")
                    }
                    stack.set(register(frame, Instruction.a(instruction)), constant)
                }
                Opcode.LOAD_K -> stack.set(register(frame, Instruction.a(instruction)), constant(prototype, Instruction.b(instruction)))
                Opcode.MOVE -> stack.copy(register(frame, Instruction.b(instruction)), register(frame, Instruction.a(instruction)))
                Opcode.RETURN -> return stack.slice(register(frame, Instruction.a(instruction)), Instruction.b(instruction))
            }
        }

        throw LuaVmException("prototype completed without RETURN")
    }

    private fun register(frame: CallFrame, offset: Int): Int = frame.base + offset

    private fun constant(prototype: Prototype, index: Int): LuaValue {
        if (index !in prototype.constants.indices) {
            throw LuaVmException("constant index out of range: K$index")
        }
        return prototype.constants[index]
    }

    private fun signedByte(value: Int): Int = if (value >= 128) value - 256 else value
}
