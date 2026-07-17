package io.github.realmlabs.klua.consumer;

import io.github.realmlabs.klua.api.LuaConfig;
import io.github.realmlabs.klua.api.LuaReturn;
import io.github.realmlabs.klua.api.LuaState;
import io.github.realmlabs.klua.api.LuaStatus;
import io.github.realmlabs.klua.stdlib.LuaStdlib;

public final class JavaEmbeddingSmoke {
    private JavaEmbeddingSmoke() {
    }

    public static void main(String[] arguments) {
        LuaConfig config = LuaConfig.production(50_000);
        try (LuaState state = LuaState.create(config)) {
            LuaStdlib.openLibs(state);
            state.register("hostBase", context -> LuaReturn.of(40L));
            check(state.load("return math.max(hostBase(), 20) + 2", "consumer-java.lua"), state);
            check(state.pcall(0, 1), state);
            Long result = state.toInteger(-1);
            if (result == null || result != 42L) {
                throw new IllegalStateException("expected Java embedding result 42, got " + result);
            }
            System.out.println("java=42");
        }
    }

    private static void check(LuaStatus status, LuaState state) {
        if (status != LuaStatus.OK) {
            throw new IllegalStateException(state.toString(-1));
        }
    }
}
