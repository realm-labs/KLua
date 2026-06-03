package io.github.realmlabs.klua.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaStateFieldJavaTest {
    @Test
    void createsTablesAndReportsTableType() {
        LuaState state = LuaState.create();

        state.newTable();

        assertEquals(1, state.getTop());
        assertTrue(state.isTable(-1));
        assertEquals("table", state.typeName(-1));
    }

    @Test
    void setsAndGetsStringFields() {
        LuaState state = LuaState.create();
        state.newTable();

        state.pushInteger(42);
        state.setField(1, "answer");
        state.getField(1, "answer");

        assertEquals(2, state.getTop());
        assertEquals(42L, state.toInteger(-1));
    }

    @Test
    void missingFieldsPushNil() {
        LuaState state = LuaState.create();
        state.newTable();

        state.getField(-1, "missing");

        assertEquals(2, state.getTop());
        assertTrue(state.isNil(-1));
    }

    @Test
    void nilFieldAssignmentsRemoveExistingFields() {
        LuaState state = LuaState.create();
        state.newTable();

        state.pushString("value");
        state.setField(1, "name");
        state.pushNil();
        state.setField(1, "name");
        state.getField(1, "name");

        assertTrue(state.isNil(-1));
    }

    @Test
    void fieldAccessRequiresTableValues() {
        LuaState state = LuaState.create();
        state.pushInteger(1);

        assertThrows(IllegalArgumentException.class, () -> state.getField(1, "x"));
        state.pushInteger(2);
        assertThrows(IllegalArgumentException.class, () -> state.setField(1, "x"));
    }
}
