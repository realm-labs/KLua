package io.github.realmlabs.klua.jmh;

import io.github.realmlabs.klua.api.Lua;
import io.github.realmlabs.klua.api.LuaCoroutineFunction;
import io.github.realmlabs.klua.api.LuaCoroutineResult;
import io.github.realmlabs.klua.api.LuaReturn;
import io.github.realmlabs.klua.api.LuaState;
import io.github.realmlabs.klua.api.LuaStatus;
import io.github.realmlabs.klua.stdlib.LuaStdlib;
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
    private static final String INDEX_METAMETHOD_SOURCE = """
            local iterations = ...
            local values = setmetatable({}, {
                __index = function(_, key)
                    return key
                end,
            })
            local sum = 0
            for index = 1, iterations do
                sum = sum + values[index]
            end
            return sum
            """;
    private static final String JVM_TO_LUA_SOURCE = """
            return function(value)
                return value + 1
            end
            """;

    private LuaCoroutineFunction luaCalls;
    private LuaCoroutineFunction hostCalls;
    private LuaCoroutineFunction tableReadWrite;
    private LuaState indexMetamethodState;
    private LuaState jvmToLuaState;

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

        indexMetamethodState = LuaState.create();
        LuaStdlib.openBase(indexMetamethodState);
        requireStatus(indexMetamethodState.load(INDEX_METAMETHOD_SOURCE, "benchmark-index-metamethod.lua"));
        long indexMetamethodVerification = callIndexMetamethods();
        if (indexMetamethodVerification != 50_005_000L) {
            throw new IllegalStateException("index-metamethod benchmark setup produced " + indexMetamethodVerification);
        }

        jvmToLuaState = LuaState.create();
        requireStatus(jvmToLuaState.load(JVM_TO_LUA_SOURCE, "benchmark-jvm-to-lua.lua"));
        requireStatus(jvmToLuaState.pcall(0, 1));
        long jvmToLuaVerification = callLuaFromJvm();
        if (jvmToLuaVerification != 10_000L) {
            throw new IllegalStateException("JVM-to-Lua benchmark setup produced " + jvmToLuaVerification);
        }
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

    @Benchmark
    public long executeIndexMetamethodCalls() {
        return callIndexMetamethods();
    }

    @Benchmark
    public long executeJvmToLuaCalls() {
        return callLuaFromJvm();
    }

    private long callIndexMetamethods() {
        indexMetamethodState.pushValue(1);
        indexMetamethodState.pushInteger(10_000L);
        requireStatus(indexMetamethodState.pcall(1, 1));
        long result = indexMetamethodState.toInteger(-1);
        indexMetamethodState.pop();
        return result;
    }

    private long callLuaFromJvm() {
        long value = 0L;
        for (int index = 0; index < 10_000; index++) {
            jvmToLuaState.pushValue(1);
            jvmToLuaState.pushInteger(value);
            requireStatus(jvmToLuaState.pcall(1, 1));
            value = jvmToLuaState.toInteger(-1);
            jvmToLuaState.pop();
        }
        return value;
    }

    private static void verify(LuaCoroutineFunction function, long expected) {
        LuaCoroutineResult verification = function.createCoroutine().resume(ARGUMENTS);
        if (!(verification instanceof LuaCoroutineResult.Returned returned)
                || !returned.getValues().equals(List.of(expected))) {
            throw new IllegalStateException("runtime benchmark setup produced " + verification);
        }
    }

    private static void requireStatus(LuaStatus status) {
        if (status != LuaStatus.OK) {
            throw new IllegalStateException("runtime benchmark call failed with " + status);
        }
    }
}
