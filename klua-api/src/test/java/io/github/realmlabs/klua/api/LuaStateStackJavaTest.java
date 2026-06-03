package io.github.realmlabs.klua.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaStateStackJavaTest {
    @Test
    void pushesAndReadsValuesWithLuaStackIndexes() {
        LuaState state = LuaState.create();

        state.pushInteger(42);
        state.pushString("17");
        state.pushBoolean(false);
        state.pushNil();

        assertEquals(4, state.getTop());
        assertEquals(42L, state.toInteger(1));
        assertEquals("17", state.toString(2));
        assertEquals(17L, state.toInteger(2));
        assertFalse(state.toBoolean(3));
        assertTrue(state.isNil(-1));
        assertNull(state.toString(-1));
    }

    @Test
    void popRemovesValuesFromTheTop() {
        LuaState state = LuaState.create();
        state.pushInteger(1);
        state.pushInteger(2);
        state.pushInteger(3);

        state.pop(2);

        assertEquals(1, state.getTop());
        assertEquals(1L, state.toInteger(-1));
    }

    @Test
    void setTopShrinksAndExpandsWithNilValues() {
        LuaState state = LuaState.create();
        state.pushString("first");
        state.pushString("second");

        state.setTop(-2);

        assertEquals(1, state.getTop());
        assertEquals("first", state.toString(1));

        state.setTop(3);

        assertEquals(3, state.getTop());
        assertTrue(state.isNil(2));
        assertTrue(state.isNil(3));
    }

    @Test
    void rejectsInvalidStackMutations() {
        LuaState state = LuaState.create();

        assertThrows(IllegalArgumentException.class, () -> state.pop(1));
        assertThrows(IllegalArgumentException.class, () -> state.pop(-1));
        assertThrows(IllegalArgumentException.class, () -> state.setTop(-2));
    }
}
