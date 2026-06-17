package io.github.realmlabs.klua.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void convertsNumericStringsToIntegers() {
        LuaState state = LuaState.create();

        state.pushString(" \t3\n");
        state.pushString("3.0");
        state.pushString("3.5");
        state.pushString("0x10");
        state.pushString("0x1.8p1");
        state.pushString("0xFFFFFFFFFFFFFFFF");
        state.pushString("0x10000000000000000");
        state.pushString("\u20033");

        assertEquals(3L, state.toInteger(1));
        assertEquals(3L, state.toInteger(2));
        assertNull(state.toInteger(3));
        assertEquals(16L, state.toInteger(4));
        assertEquals(3L, state.toInteger(5));
        assertEquals(-1L, state.toInteger(6));
        assertEquals(0L, state.toInteger(7));
        assertNull(state.toInteger(8));
    }

    @Test
    void convertsNumericStringsToNumbers() {
        LuaState state = LuaState.create();

        state.pushString(" \t3.5\n");
        state.pushString("3.5");
        state.pushString("0x10");
        state.pushString("0x1.8p1");
        state.pushString("NaN");
        state.pushString("Infinity");
        state.pushString("\u20033.5");

        assertEquals(3.5, state.toNumber(1));
        assertEquals(3.5, state.toNumber(2));
        assertEquals(16.0, state.toNumber(3));
        assertEquals(3.0, state.toNumber(4));
        assertNull(state.toNumber(5));
        assertNull(state.toNumber(6));
        assertNull(state.toNumber(7));
    }

    @Test
    void detectsNumericStringsUsingLuaAsciiWhitespace() {
        LuaState state = LuaState.create();

        state.pushString(" \t3.5\n");
        state.pushString("\u20033.5");

        assertTrue(state.isNumber(1));
        assertFalse(state.isNumber(2));
    }

    @Test
    void reportsLuaNumericStringsAsNumbers() {
        LuaState state = LuaState.create();

        state.pushString("3.5");
        state.pushString("0x10");
        state.pushString("0x1.8p1");
        state.pushString("bad");
        state.pushString("NaN");

        assertTrue(state.isNumber(1));
        assertTrue(state.isNumber(2));
        assertTrue(state.isNumber(3));
        assertFalse(state.isNumber(4));
        assertFalse(state.isNumber(5));
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
        HostObject host = new HostObject("host");
        state.pushUserData(host);

        assertEquals("nil", state.typeName(1));
        assertEquals("boolean", state.typeName(2));
        assertEquals("number", state.typeName(3));
        assertEquals("string", state.typeName(4));
        assertEquals("userdata", state.typeName(5));
        assertEquals("none", state.typeName(6));
        assertTrue(state.isNone(6));
        assertTrue(state.isBoolean(2));
        assertTrue(state.isNumber(4));
        assertTrue(state.isString(3));
        assertTrue(state.isUserData(5));
        assertSame(host, state.toUserData(5));
        assertSame(host, state.toUserData(5, HostObject.class));
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

    private static final class HostObject {
        private final String name;

        private HostObject(String name) {
            this.name = name;
        }
    }
}
