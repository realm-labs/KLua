package io.github.realmlabs.klua.stdlib;

import io.github.realmlabs.klua.api.LuaConfig;
import io.github.realmlabs.klua.api.LuaStandardLibrary;
import io.github.realmlabs.klua.api.LuaState;
import io.github.realmlabs.klua.api.LuaStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LuaStdlibJavaTest {
    @Test
    void opensWhitelistedLibrariesFromJavaConfig() {
        LuaState state = LuaState.create(
                new LuaConfig(
                        true,
                        () -> "",
                        0,
                        EnumSet.of(LuaStandardLibrary.BASE, LuaStandardLibrary.MATH)
                )
        );
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
