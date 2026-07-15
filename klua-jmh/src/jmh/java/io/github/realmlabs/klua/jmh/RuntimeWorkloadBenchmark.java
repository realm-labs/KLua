package io.github.realmlabs.klua.jmh;

import io.github.realmlabs.klua.api.Lua;
import io.github.realmlabs.klua.api.LuaCoroutineFunction;
import io.github.realmlabs.klua.api.LuaCoroutineResult;
import io.github.realmlabs.klua.api.LuaReturn;
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
public class RuntimeWorkloadBenchmark {
    private static final List<Object> ARGUMENTS = List.of(10_000L);
    private static final String LUA_CALL_SOURCE = """
            local iterations = ...
            local function increment(value)
                return value + 1
            end
            local value = 0
            for index = 1, iterations do
                value = increment(value)
            end
            return value
            """;
    private static final String HOST_CALL_SOURCE = """
            local iterations = ...
            local value = 0
            for index = 1, iterations do
                value = hostIncrement(value)
            end
            return value
            """;
    private static final String TABLE_SOURCE = """
            local iterations = ...
            local values = {}
            for index = 1, iterations do
                values[index] = index
            end
            local sum = 0
            for index = 1, iterations do
                sum = sum + values[index]
            end
            return sum
            """;

    private LuaCoroutineFunction luaCalls;
    private LuaCoroutineFunction hostCalls;
    private LuaCoroutineFunction tableReadWrite;

    @Setup
    public void setUp() {
        Lua lua = Lua.create();
        lua.globals().setFunction("hostIncrement", context -> LuaReturn.of(context.toInteger(1) + 1));
        luaCalls = lua.load(LUA_CALL_SOURCE, "benchmark-lua-calls.lua").asCoroutineFunction();
        hostCalls = lua.load(HOST_CALL_SOURCE, "benchmark-host-calls.lua").asCoroutineFunction();
        tableReadWrite = lua.load(TABLE_SOURCE, "benchmark-table.lua").asCoroutineFunction();
        verify(luaCalls, 10_000L);
        verify(hostCalls, 10_000L);
        verify(tableReadWrite, 50_005_000L);
    }

    @Benchmark
    public LuaCoroutineResult executeLuaCalls() {
        return luaCalls.createCoroutine().resume(ARGUMENTS);
    }

    @Benchmark
    public LuaCoroutineResult executeHostCalls() {
        return hostCalls.createCoroutine().resume(ARGUMENTS);
    }

    @Benchmark
    public LuaCoroutineResult executeTableReadWrite() {
        return tableReadWrite.createCoroutine().resume(ARGUMENTS);
    }

    private static void verify(LuaCoroutineFunction function, long expected) {
        LuaCoroutineResult verification = function.createCoroutine().resume(ARGUMENTS);
        if (!(verification instanceof LuaCoroutineResult.Returned returned)
                || !returned.getValues().equals(List.of(expected))) {
            throw new IllegalStateException("runtime benchmark setup produced " + verification);
        }
    }
}
