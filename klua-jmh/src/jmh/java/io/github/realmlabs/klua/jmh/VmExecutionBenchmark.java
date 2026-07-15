package io.github.realmlabs.klua.jmh;

import io.github.realmlabs.klua.api.Lua;
import io.github.realmlabs.klua.api.LuaCoroutineFunction;
import io.github.realmlabs.klua.api.LuaCoroutineResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@State(Scope.Thread)
public class VmExecutionBenchmark {
    private static final String NUMERIC_LOOP_SOURCE = """
            local limit = ...
            local sum = 0
            for index = 1, limit do
                sum = sum + (index % 7)
            end
            return sum
            """;
    private static final List<Object> ARGUMENTS = List.of(10_000L);

    private LuaCoroutineFunction numericLoop;

    @Setup
    public void setUp() {
        numericLoop = Lua.create()
                .load(NUMERIC_LOOP_SOURCE, "benchmark-vm.lua")
                .asCoroutineFunction();
        LuaCoroutineResult verification = numericLoop.createCoroutine().resume(ARGUMENTS);
        if (!(verification instanceof LuaCoroutineResult.Returned returned)
                || !returned.getValues().equals(List.of(29_998L))) {
            throw new IllegalStateException("numeric-loop benchmark setup produced " + verification);
        }
    }

    @Benchmark
    public LuaCoroutineResult executeNumericLoop() {
        return numericLoop.createCoroutine().resume(ARGUMENTS);
    }
}
