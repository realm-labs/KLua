package io.github.realmlabs.klua.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void loadedChunksReadTableGlobalsWithNativeFunctionFields() {
        LuaState state = LuaState.create();

        state.newTable();
        state.pushInteger(41);
        state.setField(-2, "base");
        state.pushFunction(context -> LuaReturn.of(context.toInteger(1) + 1));
        state.setField(-2, "increment");
        state.setGlobal("module");

        assertEquals(LuaStatus.OK, state.load("return module.base, module.increment(41)", "read-table-global.lua"));
        assertEquals(LuaStatus.OK, state.pcall(0, 2));

        assertEquals(41L, state.toInteger(1));
        assertEquals(42L, state.toInteger(2));
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

    @Test
    void loadedChunksReadAndReturnUserDataGlobals() {
        LuaState state = LuaState.create();
        HostObject host = new HostObject("host");

        state.pushUserData(host);
        state.setGlobal("host");

        assertEquals(LuaStatus.OK, state.load("return host", "read-userdata-global.lua"));
        assertEquals(LuaStatus.OK, state.pcall(0, 1));

        assertTrue(state.isUserData(-1));
        assertSame(host, state.toUserData(-1, HostObject.class));
    }

    private static final class HostObject {
        private final String name;

        private HostObject(String name) {
            this.name = name;
        }
    }
}
