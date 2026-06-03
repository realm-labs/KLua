package io.github.realmlabs.klua.core.bytecode

internal class BytecodeWriter {
    private val code = mutableListOf<Int>()
    private val lineInfo = mutableListOf<Int>()

    val size: Int
        get() = code.size

    fun emit(instruction: Int, line: Int) {
        code += instruction
        lineInfo += line
    }

    fun patch(index: Int, instruction: Int) {
        require(index in code.indices) { "instruction index out of range: $index" }
        code[index] = instruction
    }

    fun jumpOffset(fromIndex: Int, targetIndex: Int): Int {
        val offset = targetIndex - (fromIndex + 1)
        require(offset in Byte.MIN_VALUE..Byte.MAX_VALUE) { "jump offset out of range: $offset" }
        return offset and 0xFF
    }

    fun code(): IntArray = code.toIntArray()

    fun lineInfo(): IntArray = lineInfo.toIntArray()
}
