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
    void pcallPassesStackArgumentsToLoadedChunks() {
        LuaState state = LuaState.create();

        assertEquals(LuaStatus.OK, state.load("local a, b = ... return a + b", "args.lua"));
        state.pushInteger(20);
        state.pushInteger(22);

        assertEquals(LuaStatus.OK, state.pcall(2, 1));

        assertEquals(1, state.getTop());
        assertEquals(42L, state.toInteger(-1));
    }

    @Test
    void pcallSupportsTopLevelOpenVarargReturns() {
        LuaState state = LuaState.create();

        assertEquals(LuaStatus.OK, state.load("return ...", "varargs.lua"));
        state.pushString("a");
        state.pushString("b");

        assertEquals(LuaStatus.OK, state.pcall(2, -1));

        assertEquals(2, state.getTop());
        assertEquals("a", state.toString(1));
        assertEquals("b", state.toString(2));
    }

    @Test
    void pcallDirectlyReusesReturnedLuaFunctionsWithFixedAndOpenResults() {
        LuaState state = LuaState.create();

        assertEquals(
                LuaStatus.OK,
                state.load("return function(a, b) return a + b, a, b, 'extra' end", "returned-function.lua")
        );
        assertEquals(LuaStatus.OK, state.pcall(0, 1));

        state.pushValue(1);
        state.pushInteger(20);
        state.pushInteger(22);
        assertEquals(LuaStatus.OK, state.pcall(2, 3));

        assertEquals(4, state.getTop());
        assertEquals("function", state.typeName(1));
        assertEquals(42L, state.toInteger(2));
        assertEquals(20L, state.toInteger(3));
        assertEquals(22L, state.toInteger(4));

        state.setTop(1);
        state.pushValue(1);
        state.pushInteger(2);
        state.pushInteger(3);
        assertEquals(LuaStatus.OK, state.pcall(2, -1));

        assertEquals(5, state.getTop());
        assertEquals(5L, state.toInteger(2));
        assertEquals(2L, state.toInteger(3));
        assertEquals(3L, state.toInteger(4));
        assertEquals("extra", state.toString(5));

        state.setTop(1);
        state.pushValue(1);
        state.pushInteger(4);
        state.pushInteger(5);
        assertEquals(LuaStatus.OK, state.pcall(2, 6));

        assertEquals(7, state.getTop());
        assertEquals(9L, state.toInteger(2));
        assertEquals(4L, state.toInteger(3));
        assertEquals(5L, state.toInteger(4));
        assertEquals("extra", state.toString(5));
        assertTrue(state.isNil(6));
        assertTrue(state.isNil(7));
        assertNull(state.getLastError());
    }

    @Test
    void pcallDirectReturnedLuaFunctionBridgePreservesRuntimeErrors() {
        LuaState state = LuaState.create();

        assertEquals(
                LuaStatus.OK,
                state.load("return function(value) return value + 'x' end", "returned-function-error.lua")
        );
        assertEquals(LuaStatus.OK, state.pcall(0, 1));
        state.pushValue(1);
        state.pushInteger(42);

        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(1, -1));

        assertEquals(2, state.getTop());
        assertEquals("function", state.typeName(1));
        assertEquals("attempt to perform arithmetic on string", state.toString(2));
        assertTrue(state.getLastError() instanceof LuaRuntimeException);
    }

    @Test
    void pcallDirectReturnedLuaFunctionBridgeProtectsArgumentConversionErrors() {
        LuaState state = LuaState.create();

        assertEquals(
                LuaStatus.OK,
                state.load("return function(value) return value end", "returned-function-argument.lua")
        );
        assertEquals(LuaStatus.OK, state.pcall(0, 1));
        assertEquals(LuaStatus.OK, state.load("return 1", "unsupported-function-argument.lua"));

        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(1, -1));

        assertEquals(1, state.getTop());
        assertEquals("argument 1 is function", state.toString(1));
        assertTrue(state.getLastError() instanceof LuaRuntimeException);
    }

    @Test
    void pcallRejectsUnsupportedArgumentValuesWithoutThrowing() {
        LuaState state = LuaState.create();

        assertEquals(LuaStatus.OK, state.load("return ...", "unsupported-argument.lua"));
        assertEquals(LuaStatus.OK, state.load("return 1", "argument.lua"));

        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(1, -1));

        assertEquals(1, state.getTop());
        assertTrue(state.getLastError() instanceof LuaRuntimeException);
        assertEquals("cannot pass function as Lua argument", state.toString(-1));
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
        assertEquals("attempt to call a number value", state.toString(-1));
    }
}
