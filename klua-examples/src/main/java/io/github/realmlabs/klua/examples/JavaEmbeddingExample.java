package io.github.realmlabs.klua.examples;

import io.github.realmlabs.klua.api.LuaConfig;
import io.github.realmlabs.klua.api.LuaReturn;
import io.github.realmlabs.klua.api.LuaState;
import io.github.realmlabs.klua.api.LuaStatus;
import io.github.realmlabs.klua.stdlib.LuaStdlib;

public final class JavaEmbeddingExample {
    private JavaEmbeddingExample() {
    }

    public static void main(String[] arguments) {
        System.out.println(evaluate());
    }

    public static long evaluate() {
        LuaConfig config = LuaConfig.production(50_000);
        try (LuaState state = LuaState.create(config)) {
            LuaStdlib.openLibs(state);
            state.register("hostBase", context -> LuaReturn.of(40L));

            check(state.load("return math.max(hostBase(), 20) + 2", "java-example.lua"), state);
            check(state.pcall(0, 1), state);
            Long result = state.toInteger(-1);
            if (result == null) {
                throw new IllegalStateException("Lua result is not an integer");
            }
            return result;
        }
    }

    private static void check(LuaStatus status, LuaState state) {
        if (status != LuaStatus.OK) {
            throw new IllegalStateException(state.toString(-1));
        }
    }
}
