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

    @Test
    void loadedChunksReadPrimitiveGlobals() {
        LuaState state = LuaState.create();

        state.pushInteger(41);
        state.setGlobal("answer");

        assertEquals(LuaStatus.OK, state.load("return answer + 1", "read-global.lua"));
        assertEquals(LuaStatus.OK, state.pcall(0, 1));

        assertEquals(42L, state.toInteger(-1));
    }

    @Test
    void loadedChunksWritePrimitiveGlobals() {
        LuaState state = LuaState.create();

        assertEquals(LuaStatus.OK, state.load("answer = 42\nreturn answer", "write-global.lua"));
        assertEquals(LuaStatus.OK, state.pcall(0, 1));
        state.pop(1);
        state.getGlobal("answer");

        assertEquals(42L, state.toInteger(-1));
    }
}
