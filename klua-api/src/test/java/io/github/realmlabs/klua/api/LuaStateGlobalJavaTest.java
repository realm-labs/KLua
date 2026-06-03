package io.github.realmlabs.klua.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaStateGlobalJavaTest {
    @Test
    void setsAndGetsGlobalValues() {
        LuaState state = LuaState.create();

        state.pushInteger(42);
        state.setGlobal("answer");
        state.getGlobal("answer");

        assertEquals(1, state.getTop());
        assertEquals(42L, state.toInteger(-1));
    }

    @Test
    void missingGlobalsPushNil() {
        LuaState state = LuaState.create();

        state.getGlobal("missing");

        assertEquals(1, state.getTop());
        assertTrue(state.isNil(-1));
    }

    @Test
    void nilGlobalAssignmentsRemoveExistingValues() {
        LuaState state = LuaState.create();

        state.pushString("value");
        state.setGlobal("name");
        state.pushNil();
        state.setGlobal("name");
        state.getGlobal("name");

        assertTrue(state.isNil(-1));
    }

    @Test
    void globalTablesKeepTheirFields() {
        LuaState state = LuaState.create();

        state.newTable();
        state.pushString("value");
        state.setField(-2, "name");
        state.setGlobal("config");
        state.getGlobal("config");
        state.getField(-1, "name");

        assertEquals("value", state.toString(-1));
    }

    @Test
    void globalsAreIsolatedPerState() {
        LuaState first = LuaState.create();
        LuaState second = LuaState.create();

        first.pushInteger(1);
        first.setGlobal("value");
        second.getGlobal("value");

        assertTrue(second.isNil(-1));
    }
}
