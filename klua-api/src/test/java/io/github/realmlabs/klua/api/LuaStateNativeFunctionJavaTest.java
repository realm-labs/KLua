package io.github.realmlabs.klua.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaStateNativeFunctionJavaTest {
    @Test
    void callsNativeFunctionFromTheStack() {
        LuaState state = LuaState.create();

        state.pushFunction(context -> {
            long left = context.toInteger(1);
            long right = context.toInteger(2);
            return LuaReturn.of(left + right, "ok");
        });
        state.pushInteger(20);
        state.pushInteger(22);

        assertEquals(LuaStatus.OK, state.pcall(2, -1));

        assertEquals(2, state.getTop());
        assertEquals(42L, state.toInteger(1));
        assertEquals("ok", state.toString(2));
        assertNull(state.getLastError());
    }

    @Test
    void registeredNativeFunctionsCanBeLoadedFromGlobals() {
        LuaState state = LuaState.create();

        state.register("identity", context -> LuaReturn.of(context.get(1)));
        state.getGlobal("identity");
        state.pushString("value");

        assertEquals(LuaStatus.OK, state.pcall(1, 1));

        assertEquals(1, state.getTop());
        assertEquals("value", state.toString(-1));
    }

    @Test
    void registeredNativeFunctionsCanBeCalledFromLuaSource() {
        LuaState state = LuaState.create();

        state.register("add", context -> LuaReturn.of(context.toInteger(1) + context.toInteger(2)));

        assertEquals(LuaStatus.OK, state.load("return add(20, 22)", "native-global.lua"));
        assertEquals(LuaStatus.OK, state.pcall(0, 1));

        assertEquals(1, state.getTop());
        assertEquals(42L, state.toInteger(-1));
    }

    @Test
    void pushedNativeFunctionGlobalsCanBeCalledFromLuaSource() {
        LuaState state = LuaState.create();

        state.pushFunction(context -> LuaReturn.of(context.toInteger(1) + 1));
        state.setGlobal("increment");

        assertEquals(LuaStatus.OK, state.load("return increment(41)", "pushed-native-global.lua"));
        assertEquals(LuaStatus.OK, state.pcall(0, 1));

        assertEquals(42L, state.toInteger(-1));
    }

    @Test
    void nativeFunctionErrorsFromLuaSourceBecomeRuntimeErrors() {
        LuaState state = LuaState.create();

        state.register("fail", context -> {
            throw new IllegalStateException("host failure");
        });

        assertEquals(LuaStatus.OK, state.load("return fail()", "native-error.lua"));
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1));

        assertEquals(1, state.getTop());
        assertTrue(state.getLastError() instanceof LuaRuntimeException);
        assertEquals("host failure", state.toString(-1));
    }

    @Test
    void luaAssignmentsSupersedeRegisteredNativeFunctionGlobals() {
        LuaState state = LuaState.create();

        state.register("value", context -> LuaReturn.of(1));

        assertEquals(LuaStatus.OK, state.load("value = 42\nreturn value", "overwrite-native.lua"));
        assertEquals(LuaStatus.OK, state.pcall(0, 1));
        state.pop(1);
        state.getGlobal("value");

        assertEquals(42L, state.toInteger(-1));
    }

    @Test
    void nativeFunctionContextReportsArguments() {
        LuaState state = LuaState.create();

        state.pushFunction(context -> LuaReturn.of(
                context.getArgumentCount(),
                context.typeName(1),
                context.isNil(2),
                context.isNone(3),
                context.toBoolean(2)
        ));
        state.pushInteger(7);
        state.pushNil();

        assertEquals(LuaStatus.OK, state.pcall(2, -1));

        assertEquals(5, state.getTop());
        assertEquals(2L, state.toInteger(1));
        assertEquals("number", state.toString(2));
        assertTrue(state.toBoolean(3));
        assertTrue(state.toBoolean(4));
        assertFalse(state.toBoolean(5));
    }

    @Test
    void nativeFunctionsReceiveAndReturnUserDataFromLuaSource() {
        LuaState state = LuaState.create();
        HostObject host = new HostObject("player");

        state.pushUserData(host);
        state.setGlobal("host");
        state.register("identity", context -> {
            assertEquals("userdata", context.typeName(1));
            assertSame(host, context.get(1));
            assertSame(host, context.toUserData(1));
            assertSame(host, context.toUserData(1, HostObject.class));
            return LuaReturn.of(context.get(1));
        });

        assertEquals(LuaStatus.OK, state.load("return identity(host)", "native-userdata.lua"));
        assertEquals(LuaStatus.OK, state.pcall(0, 1));

        assertEquals(1, state.getTop());
        assertTrue(state.isUserData(-1));
        assertSame(host, state.toUserData(-1));
    }

    @Test
    void nativeFunctionsInspectTableArgumentsFromLuaSource() {
        LuaState state = LuaState.create();

        state.register("inspect", context -> {
            assertTrue(context.isTable(1));
            assertEquals(3L, context.tableLength(1));
            assertEquals("first", context.getTableValue(1, 1L));
            assertEquals("second", context.getTableValue(1, 2L));
            assertEquals("named", context.getTableValue(1, "name"));
            return LuaReturn.of(context.tableLength(1), context.getTableValue(1, 3L), context.getTableValue(1, "name"));
        });

        assertEquals(
                LuaStatus.OK,
                state.load("return inspect({\"first\", \"second\", \"third\", name = \"named\"})", "native-table.lua")
        );
        assertEquals(LuaStatus.OK, state.pcall(0, 3));

        assertEquals(3L, state.toInteger(1));
        assertEquals("third", state.toString(2));
        assertEquals("named", state.toString(3));
    }

    @Test
    void nativeFunctionsMutateTableArgumentsFromLuaSource() {
        LuaState state = LuaState.create();

        state.register("mutate", context -> {
            assertTrue(context.isTable(1));
            context.setTableValue(1, 2L, "updated");
            context.setTableValue(1, 3L, "third");
            context.setTableValue(1, "name", null);
            context.setTableValue(1, "extra", 42L);
            return LuaReturn.none();
        });

        assertEquals(
                LuaStatus.OK,
                state.load(
                        """
                        local values = {"first", "second", name = "named"}
                        mutate(values)
                        return values[1], values[2], values[3], values.name, values.extra, #values
                        """,
                        "native-table-mutate.lua"
                )
        );
        assertEquals(LuaStatus.OK, state.pcall(0, 6));

        assertEquals("first", state.toString(1));
        assertEquals("updated", state.toString(2));
        assertEquals("third", state.toString(3));
        assertTrue(state.isNil(4));
        assertEquals(42L, state.toInteger(5));
        assertEquals(3L, state.toInteger(6));
    }

    @Test
    void nativeFunctionResultsRespectFixedResultCounts() {
        LuaState state = LuaState.create();

        state.pushFunction(context -> LuaReturn.of("first", "second"));

        assertEquals(LuaStatus.OK, state.pcall(0, 3));

        assertEquals(3, state.getTop());
        assertEquals("first", state.toString(1));
        assertEquals("second", state.toString(2));
        assertTrue(state.isNil(3));
    }

    @Test
    void nativeFunctionExceptionsBecomeRuntimeErrors() {
        LuaState state = LuaState.create();

        state.pushFunction(context -> {
            throw new IllegalStateException("host failure");
        });

        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1));

        assertEquals(1, state.getTop());
        assertTrue(state.getLastError() instanceof LuaRuntimeException);
        assertEquals("host failure", state.toString(-1));
    }

    private static final class HostObject {
        private final String name;

        private HostObject(String name) {
            this.name = name;
        }
    }
}
