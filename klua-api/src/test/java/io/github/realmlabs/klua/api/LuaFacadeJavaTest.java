package io.github.realmlabs.klua.api;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LuaFacadeJavaTest {
    @Test
    void evaluatesSourceWithTypedHelpers() {
        Lua lua = Lua.create();

        assertEquals(42L, lua.load("return 40 + 2").evalLong());
        assertEquals("ok", lua.load("return \"ok\"").evalString());
    }

    @Test
    void loadsBytecodeResources() {
        Lua lua = Lua.create();
        byte[] bytecode = lua.compileBytecode("return ... + 1", "java-resource-bytecode.lua");

        assertEquals(
                42L,
                lua.loadBytecodeResource("scripts/main.kluac", bytecodeResource("scripts/main.kluac", bytecode))
                        .call(41L)
                        .getLong(1)
        );
    }

    @Test
    void globalsExposePrimitiveValues() {
        Lua lua = Lua.create();

        lua.globals().set("answer", 41L);

        assertEquals(42L, lua.load("return answer + 1").evalLong());
        assertEquals(41L, lua.globals().get("answer"));
    }

    @Test
    void snapshotsMutableConfigLibrarySets() {
        EnumSet<LuaStandardLibrary> libraries = EnumSet.of(LuaStandardLibrary.BASE);
        LuaConfig config = new LuaConfig(true, () -> "", 0, libraries);

        Lua lua = Lua.create(config);
        libraries.add(LuaStandardLibrary.MATH);

        assertEquals(Set.of(LuaStandardLibrary.BASE), lua.getConfig().getStandardLibraries());
        assertThrows(
                UnsupportedOperationException.class,
                () -> lua.getConfig().getStandardLibraries().add(LuaStandardLibrary.MATH)
        );
    }

    @Test
    void globalsExposeHostFunctions() {
        Lua lua = Lua.create();

        lua.globals().setFunction("add", context -> LuaReturn.of(context.toInteger(1) + context.toInteger(2)));

        assertEquals(42L, lua.load("return add(20, 22)").evalLong());
    }

    @Test
    void productionConfigIsJavaFriendlyAndPreservesExplicitHostFunctions() {
        LuaConfig config = LuaConfig.production(50_000);
        Lua lua = Lua.create(config);
        lua.globals().setFunction("hostAnswer", context -> LuaReturn.of(42L));

        assertFalse(config.getDebugEnabled());
        assertFalse(config.getUnsafeStandardLibraryAccessEnabled());
        assertEquals(50_000, config.getInstructionLimit());
        assertEquals(LuaStandardLibrary.safe(), config.getStandardLibraries());
        assertEquals(42L, lua.load("return hostAnswer()", "java-production-host.lua").evalLong());
    }

    @Test
    void debuggableCoroutinesExposeJavaFriendlyLineObservers() {
        Lua lua = Lua.create();
        LuaCoroutineFunction function = (LuaCoroutineFunction) lua.load("""
                return function()
                    local value = 41
                    return value + 1
                end
                """, "java-debug-observer.lua").eval().get(1);
        LuaDebuggableCoroutineHandle coroutine = (LuaDebuggableCoroutineHandle) function.createCoroutine();
        assertEquals(true, coroutine.setDebugObserver((event, sourceId, line, callDepth) -> line == 2));

        assertSame(LuaCoroutineResult.DebugSuspended.INSTANCE, coroutine.resume(List.of()));
        assertEquals(2, coroutine.getLuaFrames().get(0).getLine());

        coroutine.setDebugObserver(null);
        LuaCoroutineResult.Returned returned = (LuaCoroutineResult.Returned) coroutine.resume(List.of());
        assertEquals(List.of(42L), returned.getValues());
    }

    @Test
    void disabledDebugConfigurationRejectsExecutionObservers() {
        Lua lua = Lua.create(new LuaConfig(false, () -> "", 0, LuaStandardLibrary.all()));
        LuaCoroutineFunction function = (LuaCoroutineFunction) lua.load(
                "return function() return 42 end",
                "java-disabled-debug.lua"
        ).eval().get(1);
        LuaDebuggableCoroutineHandle coroutine = (LuaDebuggableCoroutineHandle) function.createCoroutine();

        assertFalse(coroutine.setDebugObserver((event, sourceId, line, callDepth) -> true));
        LuaCoroutineResult.Returned returned = (LuaCoroutineResult.Returned) coroutine.resume(List.of());
        assertEquals(List.of(42L), returned.getValues());
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
    void registeredUserDataTypesMergeAssignablePropertyAccessors() {
        Lua lua = Lua.create();
        Player player = new Player(7);

        lua.globals().set("player", player);
        lua.registerType(Named.class, type ->
                type.property("name", receiver -> LuaReturn.of(receiver.getName()))
        );
        lua.registerType(Player.class, type ->
                type.property("name", null, (receiver, value) -> receiver.setName((String) value))
        );

        assertEquals("renamed", lua.load("""
                player.name = "renamed"
                return player.name
                """).evalString());
        assertEquals("renamed", player.getName());
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

    @Test
    void luaReturnRejectsOutOfRangeIntegralDoubles() {
        LuaReturn result = LuaReturn.of(0x1.0p63, -0x1.0p63);

        assertThrows(LuaRuntimeException.class, () -> result.getLong(1));
        assertEquals(Long.MIN_VALUE, result.getLong(2));
    }

    private interface Named {
        String getName();
    }

    private static final class Player implements Named {
        private long level;
        private String name = "player";

        private Player(long level) {
            this.level = level;
        }

        @Override
        public String getName() {
            return name;
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

        private void setName(String name) {
            this.name = name;
        }
    }

    private static ClassLoader bytecodeResource(String resourceName, byte[] bytecode) {
        byte[] copy = bytecode.clone();
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (!resourceName.equals(name)) {
                    return null;
                }
                return new ByteArrayInputStream(copy);
            }
        };
    }
}
