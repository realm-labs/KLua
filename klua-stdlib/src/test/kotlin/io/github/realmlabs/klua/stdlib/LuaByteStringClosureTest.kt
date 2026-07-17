package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaStatus
import io.github.realmlabs.klua.core.value.luaRawBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LuaByteStringClosureTest {
    @Test
    fun `source construction indexing concatenation conversion and utf8 use Lua bytes`() {
        val state = LuaState.create()
        LuaStdlib.openLibs(state)

        assertEquals(
            LuaStatus.OK,
            state.load(
                """
                local source = "\0\128\195\169\255"
                local constructed = string.char(0, 128, 195, 169, 255)
                local indexed = string.sub(constructed, 2, 4)
                local joined = string.char(195) .. string.char(169)
                local invalid = string.char(128, 255)
                local validLength = utf8.len(joined)
                local invalidLength, invalidPosition = utf8.len(invalid)
                local numericWithNul = tonumber("12" .. string.char(0))
                local coerced = 12 .. string.char(0, 255)
                local b1, b2, b3, b4, b5 = string.byte(constructed, 1, -1)

                return source == constructed, #constructed, indexed,
                    joined == string.char(195, 169),
                    b1, b2, b3, b4, b5,
                    validLength, invalidLength, invalidPosition,
                    numericWithNul, coerced, tostring(constructed) == constructed
                """.trimIndent(),
                "byte-string-closure-matrix.lua",
            ),
        )
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1))

        assertTrue(state.toBoolean(1))
        assertEquals(5L, state.toInteger(2))
        assertEquals(listOf(128, 195, 169), state.rawBytes(3))
        assertTrue(state.toBoolean(4))
        assertEquals(listOf(0L, 128L, 195L, 169L, 255L), (5..9).map(state::toInteger))
        assertEquals(1L, state.toInteger(10))
        assertTrue(state.isNil(11))
        assertEquals(1L, state.toInteger(12))
        assertTrue(state.isNil(13))
        assertEquals(listOf(49, 50, 0, 255), state.rawBytes(14))
        assertTrue(state.toBoolean(15))
    }

    private fun LuaState.rawBytes(index: Int): List<Int> {
        return requireNotNull(toString(index))
            .luaRawBytes()
            .map { byte -> byte.toInt() and 0xff }
    }
}
