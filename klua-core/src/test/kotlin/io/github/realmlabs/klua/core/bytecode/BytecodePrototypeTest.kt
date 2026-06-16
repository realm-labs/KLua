package io.github.realmlabs.klua.core.bytecode

import io.github.realmlabs.klua.core.value.LuaInteger
import io.github.realmlabs.klua.core.value.LuaString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BytecodePrototypeTest {
    @Test
    fun `encodes and decodes prototypes with debug metadata and nested functions`() {
        val nested = Prototype(
            sourceName = "chunk.lua",
            sourceId = "src:chunk",
            code = intArrayOf(
                Instruction.abc(Opcode.GET_UPVALUE, a = 0, b = 0),
                Instruction.abc(Opcode.RETURN, a = 0, b = 1),
            ),
            constants = arrayOf(LuaString("nested")),
            upvalues = arrayOf(UpvalueDescriptor("outer", UpvalueSource.LOCAL, 0)),
            upvalueNames = arrayOf("outer"),
            localVars = arrayOf(LocalVarInfo("inner", slot = 0, startPc = 0, endPc = 2)),
            lineInfo = intArrayOf(2, 3),
            maxStackSize = 2,
            numParams = 1,
            isVararg = true,
            lineDefined = 2,
            lastLineDefined = 3,
            validBreakpointLines = intArrayOf(2, 3),
        )
        val prototype = Prototype(
            sourceName = "chunk.lua",
            sourceId = "src:chunk",
            code = intArrayOf(
                Instruction.abc(Opcode.LOAD_K, a = 0, b = 0),
                Instruction.abc(Opcode.CLOSURE, a = 1, b = 0),
                Instruction.abc(Opcode.RETURN, a = 0, b = 2),
            ),
            constants = arrayOf(LuaInteger(42), LuaString("root")),
            nested = arrayOf(nested),
            upvalues = arrayOf(UpvalueDescriptor("_ENV", UpvalueSource.UPVALUE, 0)),
            upvalueNames = arrayOf("_ENV"),
            localVars = arrayOf(LocalVarInfo("answer", slot = 0, startPc = 0, endPc = 3)),
            lineInfo = intArrayOf(1, 2, 4),
            maxStackSize = 3,
            numParams = 0,
            isVararg = false,
            lineDefined = 0,
            lastLineDefined = 4,
            validBreakpointLines = intArrayOf(1, 2, 4),
        )

        val encoded = BytecodePrototypeCodec.encode(prototype)
        val decoded = assertIs<BytecodePrototypeDecode.Decoded>(
            BytecodePrototypeCodec.decode(encoded),
        )

        assertEquals(prototype, decoded.prototype)
        assertEquals(encoded.size, decoded.nextOffset)
    }

    @Test
    fun `decodes prototypes from nonzero offset`() {
        val prototype = Prototype(
            sourceName = "offset.lua",
            code = intArrayOf(Instruction.abc(Opcode.RETURN, a = 0, b = 0)),
            constants = emptyArray(),
            maxStackSize = 1,
        )
        val encoded = BytecodePrototypeCodec.encode(prototype)
        val prefix = byteArrayOf(0x7f)

        val decoded = assertIs<BytecodePrototypeDecode.Decoded>(
            BytecodePrototypeCodec.decode(prefix + encoded, offset = prefix.size),
        )

        assertEquals(prototype, decoded.prototype)
        assertEquals(prefix.size + encoded.size, decoded.nextOffset)
    }

    @Test
    fun `rejects invalid prototype offsets`() {
        assertEquals(
            BytecodePrototypeDecode.Invalid("invalid KLua prototype offset -1"),
            BytecodePrototypeCodec.decode(byteArrayOf(), offset = -1),
        )
    }

    @Test
    fun `rejects truncated prototypes`() {
        assertEquals(
            BytecodePrototypeDecode.Invalid("truncated KLua prototype"),
            BytecodePrototypeCodec.decode(byteArrayOf(0x00, 0x00, 0x00)),
        )
    }

    @Test
    fun `rejects unsupported upvalue sources`() {
        val prototype = Prototype(
            sourceName = "upvalue.lua",
            code = intArrayOf(Instruction.abc(Opcode.RETURN, a = 0, b = 0)),
            constants = emptyArray(),
            upvalues = arrayOf(UpvalueDescriptor("bad", UpvalueSource.LOCAL, 0)),
            maxStackSize = 1,
        )
        val encoded = BytecodePrototypeCodec.encode(prototype)
        val corrupted = encoded.copyOf()
        val sourceOffset = encoded.indexOfUpvalueSourceOrdinal()
        corrupted[sourceOffset + 3] = 99

        assertEquals(
            BytecodePrototypeDecode.Invalid("unsupported KLua upvalue source 99 at index 0"),
            BytecodePrototypeCodec.decode(corrupted),
        )
    }

    @Test
    fun `prototype serialization is deterministic for simple chunks`() {
        val prototype = Prototype(
            sourceName = "simple.lua",
            code = intArrayOf(Instruction.abc(Opcode.RETURN, a = 0, b = 0)),
            constants = emptyArray(),
            maxStackSize = 1,
        )

        assertContentEquals(
            BytecodePrototypeCodec.encode(prototype),
            BytecodePrototypeCodec.encode(prototype),
        )
    }

    private fun ByteArray.indexOfUpvalueSourceOrdinal(): Int {
        val marker = "bad".encodeToByteArray()
        val nameOffset = asList().windowed(marker.size).indexOf(marker.toList())
        require(nameOffset >= 0) { "upvalue marker not found" }
        return nameOffset + marker.size
    }
}
