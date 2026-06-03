package io.github.realmlabs.klua.core.bytecode

internal class BytecodeWriter {
    private val code = mutableListOf<Int>()
    private val lineInfo = mutableListOf<Int>()

    fun emit(instruction: Int, line: Int) {
        code += instruction
        lineInfo += line
    }

    fun code(): IntArray = code.toIntArray()

    fun lineInfo(): IntArray = lineInfo.toIntArray()
}
