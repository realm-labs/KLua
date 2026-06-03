package io.github.realmlabs.klua.core.bytecode

import io.github.realmlabs.klua.core.runtime.LuaSourceVersion
import io.github.realmlabs.klua.core.value.LuaValue

internal data class Prototype(
    val sourceName: String,
    val version: LuaSourceVersion,
    val code: IntArray,
    val constants: Array<LuaValue>,
    val nested: Array<Prototype> = emptyArray(),
    val upvalues: Array<UpvalueDescriptor> = emptyArray(),
    val lineInfo: IntArray = IntArray(code.size),
    val maxStackSize: Int,
    val numParams: Int = 0,
    val isVararg: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Prototype) return false

        return sourceName == other.sourceName &&
            version == other.version &&
            code.contentEquals(other.code) &&
            constants.contentEquals(other.constants) &&
            nested.contentEquals(other.nested) &&
            upvalues.contentEquals(other.upvalues) &&
            lineInfo.contentEquals(other.lineInfo) &&
            maxStackSize == other.maxStackSize &&
            numParams == other.numParams &&
            isVararg == other.isVararg
    }

    override fun hashCode(): Int {
        var result = sourceName.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + code.contentHashCode()
        result = 31 * result + constants.contentHashCode()
        result = 31 * result + nested.contentHashCode()
        result = 31 * result + upvalues.contentHashCode()
        result = 31 * result + lineInfo.contentHashCode()
        result = 31 * result + maxStackSize
        result = 31 * result + numParams
        result = 31 * result + isVararg.hashCode()
        return result
    }
}

internal data class UpvalueDescriptor(
    val name: String,
    val localRegister: Int,
)
