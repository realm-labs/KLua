package io.github.realmlabs.klua.jmh;

import io.github.realmlabs.klua.api.Lua;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@State(Scope.Thread)
public class CompileBenchmark {
    private static final String NUMERIC_LOOP_SOURCE = """
            local function accumulate(limit)
                local sum = 0
                for index = 1, limit do
                    sum = sum + (index % 7)
                end
                return sum
            end
            return accumulate(...)
            """;

    private final Lua lua = Lua.create();

    @Benchmark
    public byte[] compileNumericLoop() {
        return lua.compileBytecode(NUMERIC_LOOP_SOURCE, "benchmark-compile.lua");
    }
}
