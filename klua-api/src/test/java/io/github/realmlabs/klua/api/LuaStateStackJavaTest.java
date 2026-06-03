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
    void resolvesStackIndexesAndDuplicatesValues() {
        LuaState state = LuaState.create();
        state.pushString("first");
        state.pushInteger(2);

        assertEquals(1, state.absIndex(1));
        assertEquals(2, state.absIndex(-1));

        state.pushValue(1);

        assertEquals(3, state.getTop());
        assertEquals("first", state.toString(-1));
    }

    @Test
    void copiesAndRemovesStackValues() {
        LuaState state = LuaState.create();
        state.pushString("first");
        state.pushString("second");
        state.pushString("third");

        state.copy(-1, 1);
        state.remove(2);

        assertEquals(2, state.getTop());
        assertEquals("third", state.toString(1));
        assertEquals("third", state.toString(2));
    }

    @Test
    void reportsTypesWithoutExposingRuntimeValues() {
        LuaState state = LuaState.create();
        state.pushNil();
        state.pushBoolean(true);
        state.pushInteger(7);
        state.pushString("8");

        assertEquals("nil", state.typeName(1));
        assertEquals("boolean", state.typeName(2));
        assertEquals("number", state.typeName(3));
        assertEquals("string", state.typeName(4));
        assertEquals("none", state.typeName(5));
        assertTrue(state.isNone(5));
        assertTrue(state.isBoolean(2));
        assertTrue(state.isNumber(4));
        assertTrue(state.isString(3));
    }

    @Test
    void rejectsInvalidStackMutations() {
        LuaState state = LuaState.create();
        state.pushInteger(1);

        assertThrows(IllegalArgumentException.class, () -> state.pushValue(2));
        assertThrows(IllegalArgumentException.class, () -> state.copy(1, 2));
        assertThrows(IllegalArgumentException.class, () -> state.remove(2));
        state.pop(1);
        assertThrows(IllegalArgumentException.class, () -> state.pop(-1));
        assertThrows(IllegalArgumentException.class, () -> state.pop(1));
        assertThrows(IllegalArgumentException.class, () -> state.setTop(-2));
    }
}
