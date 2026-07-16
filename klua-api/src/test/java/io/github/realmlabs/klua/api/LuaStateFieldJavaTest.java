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

    @Test
    void fieldAccessUsesIndexAndNewIndexMetamethods() {
        LuaState state = LuaState.create();
        Object[] assigned = new Object[2];
        state.newTable();
        state.pushString("raw-value");
        state.setField(1, "raw");
        state.newTable();
        state.pushFunction(context -> LuaReturn.of("fallback:" + context.get(2)));
        state.setField(2, "__index");
        state.pushFunction(context -> {
            assigned[0] = context.get(2);
            assigned[1] = context.get(3);
            return LuaReturn.none();
        });
        state.setField(2, "__newindex");
        state.pushValue(2);
        state.setMetatable(1);

        state.getField(1, "missing");
        assertEquals("fallback:missing", state.toString(-1));
        state.pop();
        state.getField(1, "raw");
        assertEquals("raw-value", state.toString(-1));
        state.pop();

        state.pushString("assigned-value");
        state.setField(1, "created");
        assertEquals("created", assigned[0]);
        assertEquals("assigned-value", assigned[1]);
        state.getField(1, "created");
        assertEquals("fallback:created", state.toString(-1));
    }
}
