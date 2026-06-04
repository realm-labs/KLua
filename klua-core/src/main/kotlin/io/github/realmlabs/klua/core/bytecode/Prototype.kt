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
    val upvalueNames: Array<String> = upvalueNames(upvalues),
    val localVars: Array<LocalVarInfo> = emptyArray(),
    val lineInfo: IntArray = IntArray(code.size),
    val maxStackSize: Int,
    val numParams: Int = 0,
    val isVararg: Boolean = false,
    val sourceId: String = sourceName,
    val lineDefined: Int = 0,
    val lastLineDefined: Int = 0,
    val validBreakpointLines: IntArray = validBreakpointLines(lineInfo),
) {
    val debugInfo: DebugInfo
        get() = DebugInfo(
            sourceName = sourceName,
            sourceId = sourceId,
            lineByPc = lineInfo,
            columnByPc = null,
            localVars = localVars,
            upvalueNames = upvalueNames,
            validBreakpointLines = validBreakpointLines,
            lineDefined = lineDefined,
            lastLineDefined = lastLineDefined,
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Prototype) return false

        return sourceName == other.sourceName &&
            sourceId == other.sourceId &&
            version == other.version &&
            code.contentEquals(other.code) &&
            constants.contentEquals(other.constants) &&
            nested.contentEquals(other.nested) &&
            upvalues.contentEquals(other.upvalues) &&
            upvalueNames.contentEquals(other.upvalueNames) &&
            localVars.contentEquals(other.localVars) &&
            lineInfo.contentEquals(other.lineInfo) &&
            lineDefined == other.lineDefined &&
            lastLineDefined == other.lastLineDefined &&
            validBreakpointLines.contentEquals(other.validBreakpointLines) &&
            maxStackSize == other.maxStackSize &&
            numParams == other.numParams &&
            isVararg == other.isVararg
    }

    override fun hashCode(): Int {
        var result = sourceName.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + code.contentHashCode()
        result = 31 * result + constants.contentHashCode()
        result = 31 * result + nested.contentHashCode()
        result = 31 * result + upvalues.contentHashCode()
        result = 31 * result + upvalueNames.contentHashCode()
        result = 31 * result + localVars.contentHashCode()
        result = 31 * result + lineInfo.contentHashCode()
        result = 31 * result + lineDefined
        result = 31 * result + lastLineDefined
        result = 31 * result + validBreakpointLines.contentHashCode()
        result = 31 * result + maxStackSize
        result = 31 * result + numParams
        result = 31 * result + isVararg.hashCode()
        return result
    }
}

internal class DebugInfo(
    val sourceName: String,
    val sourceId: String,
    val lineByPc: IntArray,
    val columnByPc: IntArray?,
    val localVars: Array<LocalVarInfo>,
    val upvalueNames: Array<String>,
    val validBreakpointLines: IntArray,
    val lineDefined: Int,
    val lastLineDefined: Int,
) {
    fun toSnapshot(): DebugInfoSnapshot {
        return DebugInfoSnapshot(
            sourceName = sourceName,
            sourceId = sourceId,
            lineByPc = lineByPc.toList(),
            columnByPc = columnByPc?.toList(),
            localVars = localVars.map { local ->
                LocalVarInfoSnapshot(
                    name = local.name,
                    slot = local.slot,
                    startPc = local.startPc,
                    endPc = local.endPc,
                )
            },
            upvalueNames = upvalueNames.toList(),
            validBreakpointLines = validBreakpointLines.toList(),
            lineDefined = lineDefined,
            lastLineDefined = lastLineDefined,
        )
    }
}

internal data class DebugInfoSnapshot(
    val sourceName: String,
    val sourceId: String,
    val lineByPc: List<Int>,
    val columnByPc: List<Int>?,
    val localVars: List<LocalVarInfoSnapshot>,
    val upvalueNames: List<String>,
    val validBreakpointLines: List<Int>,
    val lineDefined: Int,
    val lastLineDefined: Int,
)

internal data class LocalVarInfoSnapshot(
    val name: String,
    val slot: Int,
    val startPc: Int,
    val endPc: Int,
)

private fun upvalueNames(upvalues: Array<UpvalueDescriptor>): Array<String> {
    return upvalues.map { upvalue -> upvalue.name }.toTypedArray()
}

private fun validBreakpointLines(lineInfo: IntArray): IntArray {
    return lineInfo
        .asSequence()
        .filter { line -> line > 0 }
        .distinct()
        .sorted()
        .toList()
        .toIntArray()
}

internal data class UpvalueDescriptor(
    val name: String,
    val source: UpvalueSource,
    val sourceIndex: Int,
)

internal data class LocalVarInfo(
    val name: String,
    val slot: Int,
    val startPc: Int,
    val endPc: Int,
)

internal enum class UpvalueSource {
    LOCAL,
    UPVALUE,
}
