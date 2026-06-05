package io.github.realmlabs.klua.debug

import io.github.realmlabs.klua.api.LuaLocalVariable
import io.github.realmlabs.klua.api.LuaStackFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DebugFrameViewTest {
    @Test
    fun `fromLuaFrames preserves frame metadata and assigns levels`() {
        val frames = listOf(
            LuaStackFrame(
                sourceName = "main.lua",
                line = 7,
                lineDefined = 1,
                lastLineDefined = 10,
                locals = listOf(
                    LuaLocalVariable("answer", 42L),
                    LuaLocalVariable("name", "KLua"),
                ),
            ),
            LuaStackFrame(
                sourceName = "lib.lua",
                line = 3,
                lineDefined = 2,
                lastLineDefined = 4,
            ),
        )

        val views = DebugFrameView.fromLuaFrames(frames)

        assertEquals(2, views.size)
        assertEquals(0, views[0].level)
        assertEquals("main.lua", views[0].sourceName)
        assertEquals(7, views[0].line)
        assertEquals(1, views[0].lineDefined)
        assertEquals(10, views[0].lastLineDefined)
        assertEquals(DebugVariable("answer", 42L, "number", "42"), views[0].locals[0])
        assertEquals(DebugVariable("name", "KLua", "string", "KLua"), views[0].locals[1])
        assertEquals(1, views[1].level)
        assertEquals("lib.lua", views[1].sourceName)
    }

    @Test
    fun `scopes exposes frame locals as a locals scope`() {
        val frame = LuaStackFrame(
            sourceName = "main.lua",
            line = 4,
            locals = listOf(
                LuaLocalVariable("left", 1L),
                LuaLocalVariable("right", 2L),
            ),
        ).toDebugFrameView(0)

        val scopes = frame.scopes()

        assertEquals(
            listOf(
                DebugScopeView(
                    name = "Locals",
                    kind = DebugScopeKind.LOCALS,
                    variables = listOf(
                        DebugVariable("left", 1L, "number", "1"),
                        DebugVariable("right", 2L, "number", "2"),
                    ),
                ),
            ),
            scopes,
        )
    }

    @Test
    fun `toDebugVariable renders supported debugger value summaries`() {
        val hostValue = HostValue()
        val values = listOf(
            LuaLocalVariable("nilValue", null).toDebugVariable(),
            LuaLocalVariable("booleanValue", true).toDebugVariable(),
            LuaLocalVariable("numberValue", 1.5).toDebugVariable(),
            LuaLocalVariable("charValue", 'x').toDebugVariable(),
            LuaLocalVariable("tableValue", mapOf("a" to 1, "b" to 2)).toDebugVariable(),
            LuaLocalVariable("userdataValue", hostValue).toDebugVariable(),
        )

        assertEquals(DebugVariable("nilValue", null, "nil", "nil"), values[0])
        assertEquals(DebugVariable("booleanValue", true, "boolean", "true"), values[1])
        assertEquals(DebugVariable("numberValue", 1.5, "number", "1.5"), values[2])
        assertEquals(DebugVariable("charValue", 'x', "string", "x"), values[3])
        assertEquals(DebugVariable("tableValue", mapOf("a" to 1, "b" to 2), "table", "table(2)"), values[4])
        assertEquals("userdata", values[5].typeName)
        assertEquals(HostValue::class.java.name, values[5].displayValue)
    }

    @Test
    fun `childPage pages table variable entries as child variables`() {
        val variable = LuaLocalVariable(
            "tableValue",
            linkedMapOf<Any?, Any?>(
                "name" to "KLua",
                2L to 42L,
                null to false,
            ),
        ).toDebugVariable()

        val firstPage = variable.childPage(start = 0, count = 2)
        val secondPage = variable.childPage(start = 2, count = 2)

        assertEquals(
            DebugVariablePage(
                start = 0,
                total = 3,
                variables = listOf(
                    DebugVariable("name", "KLua", "string", "KLua"),
                    DebugVariable("[2]", 42L, "number", "42"),
                ),
            ),
            firstPage,
        )
        assertEquals(
            DebugVariablePage(
                start = 2,
                total = 3,
                variables = listOf(DebugVariable("[nil]", false, "boolean", "false")),
            ),
            secondPage,
        )
    }

    @Test
    fun `childPage returns empty page for scalar variables`() {
        val variable = LuaLocalVariable("value", 1L).toDebugVariable()

        assertEquals(DebugVariablePage(start = 0, total = 0, variables = emptyList()), variable.childPage())
    }

    @Test
    fun `childPage validates page arguments`() {
        val variable = LuaLocalVariable("tableValue", mapOf("a" to 1)).toDebugVariable()

        assertFailsWith<IllegalArgumentException> {
            variable.childPage(start = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            variable.childPage(count = -1)
        }
    }

    @Test
    fun `toDebugFrameView rejects negative levels`() {
        assertFailsWith<IllegalArgumentException> {
            LuaStackFrame("main.lua", 1).toDebugFrameView(-1)
        }
    }

    private class HostValue
}
