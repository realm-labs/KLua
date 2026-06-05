package io.github.realmlabs.klua.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BreakpointManagerTest {
    @Test
    fun `setBreakpoint stores and replaces breakpoints by source and line`() {
        val manager = BreakpointManager()

        val first = manager.setBreakpoint("main.lua", 3)
        val replacement = manager.setBreakpoint("main.lua", 3, enabled = false, condition = "x > 0")

        assertEquals(Breakpoint("main.lua", 3), first)
        assertEquals(Breakpoint("main.lua", 3, enabled = false, condition = "x > 0"), replacement)
        assertEquals(replacement, manager.breakpointAt("main.lua", 3))
        assertFalse(manager.isBreakpoint("main.lua", 3))
        assertEquals(listOf(replacement), manager.listBreakpoints())
    }

    @Test
    fun `isBreakpoint only reports enabled breakpoints`() {
        val manager = BreakpointManager()

        manager.setBreakpoint("main.lua", 1)
        manager.setBreakpoint("main.lua", 2, enabled = false)

        assertTrue(manager.isBreakpoint("main.lua", 1))
        assertFalse(manager.isBreakpoint("main.lua", 2))
        assertFalse(manager.isBreakpoint("missing.lua", 1))
    }

    @Test
    fun `clearBreakpoint and clearSource remove selected breakpoints`() {
        val manager = BreakpointManager()
        manager.setBreakpoint("main.lua", 1)
        manager.setBreakpoint("main.lua", 2)
        manager.setBreakpoint("other.lua", 1)

        assertTrue(manager.clearBreakpoint("main.lua", 1))
        assertFalse(manager.clearBreakpoint("main.lua", 1))
        assertNull(manager.breakpointAt("main.lua", 1))
        assertEquals(1, manager.clearSource("main.lua"))
        assertEquals(listOf(Breakpoint("other.lua", 1)), manager.listBreakpoints())
    }

    @Test
    fun `listBreakpoints returns sorted optional source view`() {
        val manager = BreakpointManager()
        val other = manager.setBreakpoint("other.lua", 5)
        val mainTwo = manager.setBreakpoint("main.lua", 2)
        val mainOne = manager.setBreakpoint("main.lua", 1)

        assertEquals(listOf(mainOne, mainTwo, other), manager.listBreakpoints())
        assertEquals(listOf(mainOne, mainTwo), manager.listBreakpoints("main.lua"))
    }

    @Test
    fun `replaceSourceBreakpoints replaces only the requested source`() {
        val manager = BreakpointManager()
        manager.setBreakpoint("main.lua", 1)
        manager.setBreakpoint("main.lua", 2)
        val other = manager.setBreakpoint("other.lua", 1)

        val replacements = manager.replaceSourceBreakpoints(
            "main.lua",
            listOf(
                BreakpointRequest(3),
                BreakpointRequest(4, enabled = false, condition = "ready"),
            ),
        )

        assertEquals(
            listOf(
                Breakpoint("main.lua", 3),
                Breakpoint("main.lua", 4, enabled = false, condition = "ready"),
            ),
            replacements,
        )
        assertNull(manager.breakpointAt("main.lua", 1))
        assertNull(manager.breakpointAt("main.lua", 2))
        assertEquals(listOf(replacements[0], replacements[1]), manager.listBreakpoints("main.lua"))
        assertEquals(other, manager.breakpointAt("other.lua", 1))
    }

    @Test
    fun `setBreakpoint rejects invalid locations`() {
        val manager = BreakpointManager()

        assertFailsWith<IllegalArgumentException> {
            manager.setBreakpoint("", 1)
        }
        assertFailsWith<IllegalArgumentException> {
            manager.setBreakpoint("main.lua", 0)
        }
        assertFailsWith<IllegalArgumentException> {
            manager.replaceSourceBreakpoints("", listOf(BreakpointRequest(1)))
        }
        assertFailsWith<IllegalArgumentException> {
            manager.replaceSourceBreakpoints("main.lua", listOf(BreakpointRequest(0)))
        }
    }
}
