package io.github.realmlabs.klua.stdlib;

import io.github.realmlabs.klua.api.LuaConfig;
import io.github.realmlabs.klua.api.LuaStandardLibrary;
import io.github.realmlabs.klua.api.LuaState;
import io.github.realmlabs.klua.api.LuaStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LuaStdlibJavaTest {
    @Test
    void configuresDebugCommandInputFromJava() {
        LuaState state = LuaState.create();
        LuaStdlib.openLibs(state, ignored -> {});
        ArrayDeque<String> commands = new ArrayDeque<>(List.of("javaDebugValue = 7", "cont"));
        List<String> output = new ArrayList<>();
        LuaStdlib.openDebug(state, commands::pollFirst, output::add);

        assertEquals(LuaStatus.OK, state.load("debug.debug(); return javaDebugValue", "java-debug-command.lua"));
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1));
        assertEquals(7L, state.toInteger(1));
        assertEquals(List.of("lua_debug> ", "lua_debug> "), output);
    }

    @Test
    void opensWhitelistedLibrariesFromJavaConfig() {
        EnumSet<LuaStandardLibrary> libraries = EnumSet.of(LuaStandardLibrary.BASE, LuaStandardLibrary.MATH);
        LuaState state = LuaState.create(
                new LuaConfig(
                        true,
                        () -> "",
                        0,
                        libraries
                )
        );
        libraries.remove(LuaStandardLibrary.MATH);
        libraries.add(LuaStandardLibrary.STRING);

        LuaStdlib.openLibs(state);

        assertEquals(
                LuaStatus.OK,
                state.load("return type(math), type(string), type(package), type(debug)", "java-stdlib-whitelist.lua")
        );
        assertEquals(LuaStatus.OK, state.pcall(0, -1), state.toString(-1));

        assertEquals("table", state.toString(1));
        assertEquals("nil", state.toString(2));
        assertEquals("nil", state.toString(3));
        assertEquals("nil", state.toString(4));
    }
}
