package io.github.realmlabs.klua.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaStateLoadJavaTest {
    @Test
    void loadsAndCallsLuaSource() {
        LuaState state = LuaState.create();

        assertEquals(LuaStatus.OK, state.load("return 1 + 2, \"ok\"", "api.lua"));
        assertEquals(1, state.getTop());
        assertEquals("function", state.typeName(-1));

        assertEquals(LuaStatus.OK, state.pcall(0, -1));

        assertEquals(2, state.getTop());
        assertEquals(3L, state.toInteger(1));
        assertEquals("ok", state.toString(2));
        assertNull(state.getLastError());
    }

    @Test
    void pcallPadsFixedResultCountsWithNil() {
        LuaState state = LuaState.create();

        assertEquals(LuaStatus.OK, state.load("return 42", "fixed-results.lua"));
        assertEquals(LuaStatus.OK, state.pcall(0, 3));

        assertEquals(3, state.getTop());
        assertEquals(42L, state.toInteger(1));
        assertTrue(state.isNil(2));
        assertTrue(state.isNil(3));
    }

    @Test
    void loadReturnsSyntaxErrorAndPushesMessage() {
        LuaState state = LuaState.create();

        assertEquals(LuaStatus.SYNTAX_ERROR, state.load("return (", "syntax.lua"));

        assertEquals(1, state.getTop());
        assertTrue(state.getLastError() instanceof LuaSyntaxException);
        assertTrue(state.toString(-1).contains("syntax.lua"));
    }

    @Test
    void pcallReturnsRuntimeErrorAndPushesMessage() {
        LuaState state = LuaState.create();

        assertEquals(LuaStatus.OK, state.load("return {} + 1", "runtime.lua"));
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1));

        assertEquals(1, state.getTop());
        assertTrue(state.getLastError() instanceof LuaRuntimeException);
        assertEquals("attempt to perform arithmetic on table", state.toString(-1));
    }

    @Test
    void pcallRejectsNonCallableValuesWithoutThrowing() {
        LuaState state = LuaState.create();
        state.pushInteger(42);

        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1));

        assertEquals(1, state.getTop());
        assertTrue(state.getLastError() instanceof LuaRuntimeException);
        assertEquals("attempt to call number", state.toString(-1));
    }
}
