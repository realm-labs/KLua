package io.github.realmlabs.klua.jmh;

import io.github.realmlabs.klua.api.LuaState;
import org.openjdk.jmh.annotations.Benchmark;

public class DummyBenchmark {
    @Benchmark
    public int createStateStackTop() {
        return LuaState.create().getTop();
    }
}
