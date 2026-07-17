package io.github.realmlabs.klua.tests;

import io.github.realmlabs.klua.api.Lua;
import io.github.realmlabs.klua.api.LuaConfig;
import io.github.realmlabs.klua.api.LuaExitException;
import io.github.realmlabs.klua.api.LuaFunction;
import io.github.realmlabs.klua.api.LuaReturn;
import io.github.realmlabs.klua.api.LuaState;
import io.github.realmlabs.klua.api.LuaStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class JavaApiCompileTest {
    @Test
    void lowAndHighLevelApiCompileWithoutCoreOnTheCompileClasspath() {
        LuaConfig config = new LuaConfig(false);
        LuaState state = LuaState.create(config);
        state.register("answer", context -> LuaReturn.of(42L));
        assertEquals(LuaStatus.OK, state.load("return answer()", "java-contract.lua"));
        assertEquals(LuaStatus.OK, state.pcall(0, 1));
        assertEquals(42L, state.toInteger(-1));

        Lua lua = Lua.create(config);
        LuaFunction add = context -> LuaReturn.of(context.toInteger(1) + context.toInteger(2));
        lua.globals().setFunction("add", add);
        assertEquals(42L, lua.load("return add(20, 22)").evalLong());

        LuaExitException exit = new LuaExitException(7, false);
        assertEquals(7, exit.getStatus());
    }
}
