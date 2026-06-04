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
    void registeredUserDataTypesExposeMethodsToLua() {
        Lua lua = Lua.create();
        Player player = new Player(7);

        lua.globals().set("player", player);
        lua.registerType(Player.class, type -> {
            type.method("getLevel", (receiver, context) -> LuaReturn.of(receiver.getLevel()));
            type.method("addExp", (receiver, context) -> {
                receiver.addExp(context.toInteger(1));
                return LuaReturn.none();
            });
            type.property(
                    "level",
                    receiver -> LuaReturn.of(receiver.getLevel()),
                    (receiver, value) -> receiver.setLevel((Long) value)
            );
        });

        assertEquals(10L, lua.load("""
                player:addExp(100)
                player.level = player.level + 2
                return player:getLevel()
                """).evalLong());
    }

    @Test
    void registeredUserDataTypesMergeAssignableMethods() {
        Lua lua = Lua.create();
        Player player = new Player(7);

        lua.globals().set("player", player);
        lua.registerType(Named.class, type ->
                type.method("getName", (receiver, context) -> LuaReturn.of(receiver.getName()))
        );
        lua.registerType(Player.class, type ->
                type.method("getLevel", (receiver, context) -> LuaReturn.of(receiver.getLevel()))
        );

        LuaReturn result = lua.load("""
                return player:getName() == "player", player:getLevel() == 7
                """).eval();

        assertEquals(true, result.getBoolean(1));
        assertEquals(true, result.getBoolean(2));
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

    private interface Named {
        String getName();
    }

    private static final class Player implements Named {
        private long level;

        private Player(long level) {
            this.level = level;
        }

        @Override
        public String getName() {
            return "player";
        }

        private long getLevel() {
            return level;
        }

        private void addExp(long amount) {
            level += amount / 100;
        }

        private void setLevel(long level) {
            this.level = level;
        }
    }
}
