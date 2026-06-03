package io.github.realmlabs.klua.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LuaFacadeJavaTest {
    @Test
    void evaluatesSourceWithTypedHelpers() {
        Lua lua = Lua.create();

        assertEquals(42L, lua.load("return 40 + 2").evalLong());
        assertEquals("ok", lua.load("return \"ok\"").evalString());
    }

    @Test
    void globalsExposePrimitiveValues() {
        Lua lua = Lua.create();

        lua.globals().set("answer", 41L);

        assertEquals(42L, lua.load("return answer + 1").evalLong());
        assertEquals(41L, lua.globals().get("answer"));
    }

    @Test
    void globalsExposeHostFunctions() {
        Lua lua = Lua.create();

        lua.globals().setFunction("add", context -> LuaReturn.of(context.toInteger(1) + context.toInteger(2)));

        assertEquals(42L, lua.load("return add(20, 22)").evalLong());
    }

    @Test
    void evalThrowsStructuredErrors() {
        Lua lua = Lua.create();

        assertThrows(LuaRuntimeException.class, () -> lua.load("return \"x\" + 1", "bad.lua").eval());
    }

    @Test
    void luaReturnTypedAccessorsValidateTypes() {
        LuaReturn result = LuaReturn.of(42L, "ok", true);

        assertEquals(42L, result.getLong(1));
        assertEquals("ok", result.getString(2));
        assertEquals(true, result.getBoolean(3));
        assertThrows(LuaRuntimeException.class, () -> result.getLong(2));
    }
}
