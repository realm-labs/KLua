package io.github.realmlabs.klua.core.bytecode

internal object Instruction {
    fun abc(opcode: Opcode, a: Int, b: Int = 0, c: Int = 0): Int {
        require(a in 0..255) { "A operand out of range: $a" }
        require(b in 0..255) { "B operand out of range: $b" }
        require(c in 0..255) { "C operand out of range: $c" }
        return opcode.ordinal or (a shl 8) or (b shl 16) or (c shl 24)
    }

    fun a(instruction: Int): Int = (instruction ushr 8) and 0xFF

    fun b(instruction: Int): Int = (instruction ushr 16) and 0xFF

    fun c(instruction: Int): Int = (instruction ushr 24) and 0xFF

    fun opcode(instruction: Int): Opcode = Opcode.entries[instruction and 0xFF]
}
