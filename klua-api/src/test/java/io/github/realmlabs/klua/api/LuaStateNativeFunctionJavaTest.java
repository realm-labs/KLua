package io.github.realmlabs.klua.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    void nativeFunctionMapResultsBecomeTablesOnStack() {
        LuaState state = LuaState.create();
        Map<Object, Object> table = new LinkedHashMap<>();
        table.put(1L, "first");
        table.put("name", "named");

        state.pushFunction(context -> LuaReturn.of(table));

        assertEquals(LuaStatus.OK, state.pcall(0, 1));
        assertEquals(1, state.getTop());
        assertTrue(state.isTable(1));

        state.getField(1, "name");
        assertEquals("named", state.toString(-1));
    }

    @Test
    void nativeFunctionMapResultsBecomeTablesFromLuaSource() {
        LuaState state = LuaState.create();

        state.register("makeTable", context -> {
            Map<Object, Object> table = new LinkedHashMap<>();
            table.put(1L, "first");
            table.put(2L, "second");
            table.put("name", "named");
            return LuaReturn.of(table);
        });

        assertEquals(
                LuaStatus.OK,
                state.load("local value = makeTable()\nreturn value[1], value[2], value.name, #value", "native-table-result.lua")
        );
        assertEquals(LuaStatus.OK, state.pcall(0, 4));

        assertEquals("first", state.toString(1));
        assertEquals("second", state.toString(2));
        assertEquals("named", state.toString(3));
        assertEquals(2L, state.toInteger(4));
    }

    @Test
    void nativeFunctionCanReturnTableArgumentOnStack() {
        LuaState state = LuaState.create();
        Map<Object, Object> table = new LinkedHashMap<>();
        table.put(1L, "first");

        state.pushFunction(context -> {
            context.setTableValue(1, "marker", "updated");
            return LuaReturn.of(context.getTable(1));
        });
        state.pushFunction(context -> LuaReturn.of(table));
        assertEquals(LuaStatus.OK, state.pcall(0, 1));

        assertEquals(LuaStatus.OK, state.pcall(1, 1));
        assertEquals(1, state.getTop());
        assertTrue(state.isTable(1));

        state.getField(1, "marker");
        assertEquals("updated", state.toString(-1));
    }

    @Test
    void nativeFunctionCanReturnTableArgumentFromLuaSource() {
        LuaState state = LuaState.create();

        state.register("identityTable", context -> {
            context.setTableValue(1, "marker", "updated");
            return LuaReturn.of(context.getTable(1));
        });

        assertEquals(
                LuaStatus.OK,
                state.load(
                        """
                        local value = {"first"}
                        local returned = identityTable(value)
                        returned[2] = "second"
                        return returned == value, value.marker, value[2], #value
                        """,
                        "native-table-identity.lua"
                )
        );
        assertEquals(LuaStatus.OK, state.pcall(0, 4));

        assertTrue(state.toBoolean(1));
        assertEquals("updated", state.toString(2));
        assertEquals("second", state.toString(3));
        assertEquals(2L, state.toInteger(4));
    }

    @Test
    void nativeFunctionReceivesSharedTableArgumentIdentityFromLuaSource() {
        LuaState state = LuaState.create();

        state.register("sameTable", context -> LuaReturn.of(context.getTable(1) == context.getTable(2)));

        assertEquals(
                LuaStatus.OK,
                state.load(
                        """
                        local value = {}
                        return sameTable(value, value), sameTable({}, {})
                        """,
                        "native-table-alias.lua"
                )
        );
        assertEquals(LuaStatus.OK, state.pcall(0, 2));

        assertTrue(state.toBoolean(1));
        assertFalse(state.toBoolean(2));
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
        IllegalStateException failure = new IllegalStateException("host failure");

        state.register("fail", context -> {
            throw failure;
        });

        assertEquals(LuaStatus.OK, state.load("return fail()", "native-error.lua"));
        assertEquals(LuaStatus.RUNTIME_ERROR, state.pcall(0, -1));

        assertEquals(1, state.getTop());
        assertTrue(state.getLastError() instanceof LuaRuntimeException);
        LuaRuntimeException error = (LuaRuntimeException) state.getLastError();
        assertSame(failure, error.getCause());
        assertEquals("native-error.lua", error.getSourceName());
        assertEquals(1, error.getLine());
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
    void nativeFunctionCanCallLuaFunctionArguments() {
        LuaState state = LuaState.create();

        state.pushFunction(context -> {
            LuaReturn returned = context.call(1, List.of("x", 2L));

            assertEquals("x2", returned.get(1));
            assertEquals(false, returned.get(2));
            assertEquals(3L, returned.get(3));

            return LuaReturn.of(returned.get(1), returned.get(2), returned.get(3));
        });
        state.setGlobal("invoke");

        assertEquals(
                LuaStatus.OK,
                state.load(
                        """
                        return invoke(function(prefix, number)
                            return prefix .. number, false, 3
                        end)
                        """,
                        "native-call-lua-function.lua"
                )
        );
        assertEquals(LuaStatus.OK, state.pcall(0, -1));

        assertEquals("x2", state.toString(1));
        assertFalse(state.toBoolean(2));
        assertEquals(3L, state.toInteger(3));
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
