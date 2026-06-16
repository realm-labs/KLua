package io.github.realmlabs.klua.core.bytecode

import java.io.ByteArrayOutputStream

private const val BYTECODE_PROTOTYPE_INT_SIZE: Int = 4

internal sealed interface BytecodePrototypeDecode {
    data class Decoded(
        val prototype: Prototype,
        val nextOffset: Int,
    ) : BytecodePrototypeDecode

    data class Invalid(val reason: String) : BytecodePrototypeDecode
}

internal object BytecodePrototypeCodec {
    fun encode(prototype: Prototype): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeString(prototype.sourceName)
        output.writeString(prototype.sourceId)
        output.writeInt(prototype.maxStackSize)
        output.writeInt(prototype.numParams)
        output.write(if (prototype.isVararg) 1 else 0)
        output.writeInt(prototype.lineDefined)
        output.writeInt(prototype.lastLineDefined)
        output.writeBytes(BytecodeInstructionStreamCodec.encode(prototype.code))
        output.writeBytes(BytecodeConstantPoolCodec.encode(prototype.constants))
        output.writeUpvalues(prototype.upvalues)
        output.writeStringArray(prototype.upvalueNames)
        output.writeLocalVars(prototype.localVars)
        output.writeIntArray(prototype.lineInfo)
        output.writeCallSiteInfo(prototype.callSiteInfo)
        output.writeIntArray(prototype.validBreakpointLines)
        output.writeInt(prototype.nested.size)
        prototype.nested.forEach { nested -> output.writeBytes(encode(nested)) }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, offset: Int = 0): BytecodePrototypeDecode {
        if (offset < 0 || offset > bytes.size) {
            return BytecodePrototypeDecode.Invalid("invalid KLua prototype offset $offset")
        }

        val reader = PrototypeReader(bytes, offset)
        val sourceName = reader.readString() ?: return reader.invalid()
        val sourceId = reader.readString() ?: return reader.invalid()
        val maxStackSize = reader.readInt() ?: return reader.invalid()
        val numParams = reader.readInt() ?: return reader.invalid()
        val isVararg = reader.readBoolean() ?: return reader.invalid()
        val lineDefined = reader.readInt() ?: return reader.invalid()
        val lastLineDefined = reader.readInt() ?: return reader.invalid()

        val code = when (val decoded = BytecodeInstructionStreamCodec.decode(bytes, reader.position)) {
            is BytecodeInstructionStreamDecode.Decoded -> {
                reader.position = decoded.nextOffset
                decoded.code
            }
            is BytecodeInstructionStreamDecode.Invalid -> return BytecodePrototypeDecode.Invalid(decoded.reason)
        }

        val constants = when (val decoded = BytecodeConstantPoolCodec.decode(bytes, reader.position)) {
            is BytecodeConstantPoolDecode.Decoded -> {
                reader.position = decoded.nextOffset
                decoded.constants
            }
            is BytecodeConstantPoolDecode.Invalid -> return BytecodePrototypeDecode.Invalid(decoded.reason)
        }

        val upvalues = reader.readUpvalues() ?: return reader.invalid()
        val upvalueNames = reader.readStringArray() ?: return reader.invalid()
        val localVars = reader.readLocalVars() ?: return reader.invalid()
        val lineInfo = reader.readIntArray("line info") ?: return reader.invalid()
        val callSiteInfo = reader.readCallSiteInfo() ?: return reader.invalid()
        val validBreakpointLines = reader.readIntArray("valid breakpoint line") ?: return reader.invalid()
        val nestedCount = reader.readCount("nested prototype") ?: return reader.invalid()
        val nested = ArrayList<Prototype>(nestedCount)
        repeat(nestedCount) {
            when (val decoded = decode(bytes, reader.position)) {
                is BytecodePrototypeDecode.Decoded -> {
                    reader.position = decoded.nextOffset
                    nested += decoded.prototype
                }
                is BytecodePrototypeDecode.Invalid -> return decoded
            }
        }

        return BytecodePrototypeDecode.Decoded(
            Prototype(
                sourceName = sourceName,
                sourceId = sourceId,
                code = code,
                constants = constants,
                nested = nested.toTypedArray(),
                upvalues = upvalues,
                upvalueNames = upvalueNames,
                localVars = localVars,
                lineInfo = lineInfo,
                callSiteInfo = callSiteInfo,
                maxStackSize = maxStackSize,
                numParams = numParams,
                isVararg = isVararg,
                lineDefined = lineDefined,
                lastLineDefined = lastLineDefined,
                validBreakpointLines = validBreakpointLines,
            ),
            reader.position,
        )
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeStringArray(values: Array<String>) {
        writeInt(values.size)
        values.forEach { value -> writeString(value) }
    }

    private fun ByteArrayOutputStream.writeIntArray(values: IntArray) {
        writeInt(values.size)
        values.forEach { value -> writeInt(value) }
    }

    private fun ByteArrayOutputStream.writeUpvalues(upvalues: Array<UpvalueDescriptor>) {
        writeInt(upvalues.size)
        upvalues.forEach { upvalue ->
            writeString(upvalue.name)
            writeInt(upvalue.source.ordinal)
            writeInt(upvalue.sourceIndex)
        }
    }

    private fun ByteArrayOutputStream.writeLocalVars(localVars: Array<LocalVarInfo>) {
        writeInt(localVars.size)
        localVars.forEach { local ->
            writeString(local.name)
            writeInt(local.slot)
            writeInt(local.startPc)
            writeInt(local.endPc)
        }
    }

    private fun ByteArrayOutputStream.writeCallSiteInfo(callSiteInfo: Array<CallSiteInfo>) {
        writeInt(callSiteInfo.size)
        callSiteInfo.forEach { callSite ->
            writeInt(callSite.pc)
            writeString(callSite.name)
            writeString(callSite.nameWhat)
        }
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }
}

private class PrototypeReader(
    private val bytes: ByteArray,
    initialOffset: Int,
) {
    var position: Int = initialOffset
    private var invalidReason: String = "truncated KLua prototype"

    fun invalid(): BytecodePrototypeDecode.Invalid = BytecodePrototypeDecode.Invalid(invalidReason)

    fun readBoolean(): Boolean? {
        if (!hasBytes(1)) return truncated()
        val raw = bytes[position].toInt() and 0xff
        position += 1
        return when (raw) {
            0 -> false
            1 -> true
            else -> fail("invalid KLua prototype boolean value $raw")
        }
    }

    fun readString(): String? {
        val length = readCount("string") ?: return null
        if (!hasBytes(length)) return truncated()
        val value = bytes.decodeToString(position, position + length)
        position += length
        return value
    }

    fun readStringArray(): Array<String>? {
        val count = readCount("string array") ?: return null
        return Array(count) {
            readString() ?: return null
        }
    }

    fun readIntArray(name: String): IntArray? {
        val count = readCount(name) ?: return null
        return IntArray(count) {
            readInt() ?: return null
        }
    }

    fun readUpvalues(): Array<UpvalueDescriptor>? {
        val count = readCount("upvalue") ?: return null
        return Array(count) { index ->
            val name = readString() ?: return null
            val sourceOrdinal = readInt() ?: return null
            val source = UpvalueSource.entries.getOrNull(sourceOrdinal)
                ?: return fail("unsupported KLua upvalue source $sourceOrdinal at index $index")
            val sourceIndex = readInt() ?: return null
            UpvalueDescriptor(name, source, sourceIndex)
        }
    }

    fun readLocalVars(): Array<LocalVarInfo>? {
        val count = readCount("local variable") ?: return null
        return Array(count) {
            val name = readString() ?: return null
            val slot = readInt() ?: return null
            val startPc = readInt() ?: return null
            val endPc = readInt() ?: return null
            LocalVarInfo(name, slot, startPc, endPc)
        }
    }

    fun readCallSiteInfo(): Array<CallSiteInfo>? {
        val count = readCount("call site") ?: return null
        return Array(count) {
            val pc = readInt() ?: return null
            val name = readString() ?: return null
            val nameWhat = readString() ?: return null
            CallSiteInfo(pc, name, nameWhat)
        }
    }

    fun readCount(name: String): Int? {
        val count = readInt() ?: return null
        if (count < 0) return fail("invalid KLua prototype $name count $count")
        return count
    }

    fun readInt(): Int? {
        if (!hasBytes(BYTECODE_PROTOTYPE_INT_SIZE)) return truncated()
        val value = ((bytes[position].toInt() and 0xff) shl 24) or
            ((bytes[position + 1].toInt() and 0xff) shl 16) or
            ((bytes[position + 2].toInt() and 0xff) shl 8) or
            (bytes[position + 3].toInt() and 0xff)
        position += BYTECODE_PROTOTYPE_INT_SIZE
        return value
    }

    private fun hasBytes(byteCount: Int): Boolean {
        return byteCount >= 0 && position <= bytes.size && byteCount <= bytes.size - position
    }

    private fun <T> truncated(): T? {
        invalidReason = "truncated KLua prototype"
        return null
    }

    private fun <T> fail(reason: String): T? {
        invalidReason = reason
        return null
    }
}
