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
    private static final List<Object> CONCAT_ARGUMENTS = List.of(1_000L);
    private static final List<Object> ENTITY_ARGUMENTS = List.of(1_000L);
    private static final List<Object> FIBONACCI_ARGUMENTS = List.of(20L);
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
    private static final String STRING_CONCAT_SOURCE = """
            local iterations = ...
            local value = ""
            for index = 1, iterations do
                value = value .. "x"
            end
            return #value
            """;
    private static final String COROUTINE_YIELD_RESUME_SOURCE = """
            local iterations = ...
            local thread = coroutine.create(function()
                for index = 1, iterations do
                    coroutine.yield(index)
                end
                return iterations
            end)
            local last = 0
            for index = 1, iterations do
                local ok, value = coroutine.resume(thread)
                if not ok then
                    return -1
                end
                last = value
            end
            local ok, result = coroutine.resume(thread)
            if not ok then
                return -2
            end
            return last + result
            """;
    private static final String CLOSURE_COUNTER_SOURCE = """
            local iterations = ...
            local function makeCounter()
                local value = 0
                return function()
                    value = value + 1
                    return value
                end
            end
            local counter = makeCounter()
            local result = 0
            for index = 1, iterations do
                result = counter()
            end
            return result
            """;
    private static final String METHOD_CALL_SOURCE = """
            local iterations = ...
            local object = { value = 0 }
            function object:increment(amount)
                self.value = self.value + amount
                return self.value
            end
            local result = 0
            for index = 1, iterations do
                result = object:increment(1)
            end
            return result
            """;
    private static final String RECURSIVE_FIBONACCI_SOURCE = """
            local value = ...
            local function fibonacci(current)
                if current < 2 then
                    return current
                end
                return fibonacci(current - 1) + fibonacci(current - 2)
            end
            return fibonacci(value)
            """;
    private static final String VARARG_MULTI_RETURN_SOURCE = """
            local iterations = ...
            local function rotate(...)
                local first, second, third = ...
                return third, first, second
            end
            local first, second, third = 1, 2, 3
            for index = 1, iterations do
                first, second, third = rotate(first, second, third)
            end
            return first * 100 + second * 10 + third
            """;
    private static final String ENTITY_UPDATE_SOURCE = """
            local entityCount = ...
            local entities = {}
            for index = 1, entityCount do
                entities[index] = {
                    x = index,
                    y = index * 2,
                    velocityX = 2,
                    velocityY = -1,
                }
            end
            for step = 1, 10 do
                for index = 1, #entities do
                    local entity = entities[index]
                    entity.x = entity.x + entity.velocityX
                    entity.y = entity.y + entity.velocityY
                end
            end
            local checksum = 0
            for index = 1, #entities do
                local entity = entities[index]
                checksum = checksum + entity.x + entity.y
            end
            return checksum
            """;

    private LuaCoroutineFunction luaCalls;
    private LuaCoroutineFunction hostCalls;
    private LuaCoroutineFunction tableReadWrite;
    private LuaCoroutineFunction stringConcatenation;
    private LuaCoroutineFunction closureCounter;
    private LuaCoroutineFunction methodCalls;
    private LuaCoroutineFunction recursiveFibonacci;
    private LuaCoroutineFunction varargMultiReturn;
    private LuaCoroutineFunction entityUpdateKernel;
    private LuaState indexMetamethodState;
    private LuaState jvmToLuaState;
    private LuaState coroutineYieldResumeState;

    @Setup
    public void setUp() {
        Lua lua = Lua.create();
        lua.globals().setFunction("hostIncrement", context -> LuaReturn.of(context.toInteger(1) + 1));
        luaCalls = lua.load(LUA_CALL_SOURCE, "benchmark-lua-calls.lua").asCoroutineFunction();
        hostCalls = lua.load(HOST_CALL_SOURCE, "benchmark-host-calls.lua").asCoroutineFunction();
        tableReadWrite = lua.load(TABLE_SOURCE, "benchmark-table.lua").asCoroutineFunction();
        stringConcatenation = lua.load(STRING_CONCAT_SOURCE, "benchmark-string-concat.lua").asCoroutineFunction();
        closureCounter = lua.load(CLOSURE_COUNTER_SOURCE, "benchmark-closure-counter.lua").asCoroutineFunction();
        methodCalls = lua.load(METHOD_CALL_SOURCE, "benchmark-method-calls.lua").asCoroutineFunction();
        recursiveFibonacci = lua.load(
                RECURSIVE_FIBONACCI_SOURCE,
                "benchmark-recursive-fibonacci.lua"
        ).asCoroutineFunction();
        varargMultiReturn = lua.load(
                VARARG_MULTI_RETURN_SOURCE,
                "benchmark-vararg-multi-return.lua"
        ).asCoroutineFunction();
        entityUpdateKernel = lua.load(
                ENTITY_UPDATE_SOURCE,
                "benchmark-entity-update.lua"
        ).asCoroutineFunction();
        verify(luaCalls, 10_000L);
        verify(hostCalls, 10_000L);
        verify(tableReadWrite, 50_005_000L);
        verify(stringConcatenation, CONCAT_ARGUMENTS, 1_000L);
        verify(closureCounter, 10_000L);
        verify(methodCalls, 10_000L);
        verify(recursiveFibonacci, FIBONACCI_ARGUMENTS, 6_765L);
        verify(varargMultiReturn, 312L);
        verify(entityUpdateKernel, ENTITY_ARGUMENTS, 1_511_500L);

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

        coroutineYieldResumeState = LuaState.create();
        LuaStdlib.openCoroutine(coroutineYieldResumeState);
        requireStatus(coroutineYieldResumeState.load(
                COROUTINE_YIELD_RESUME_SOURCE,
                "benchmark-coroutine-yield-resume.lua"
        ));
        long coroutineVerification = runCoroutineYieldResume();
        if (coroutineVerification != 20_000L) {
            throw new IllegalStateException("coroutine benchmark setup produced " + coroutineVerification);
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
    public LuaCoroutineResult executeStringConcatenation() {
        return stringConcatenation.createCoroutine().resume(CONCAT_ARGUMENTS);
    }

    @Benchmark
    public LuaCoroutineResult executeClosureCounter() {
        return closureCounter.createCoroutine().resume(ARGUMENTS);
    }

    @Benchmark
    public LuaCoroutineResult executeMethodCalls() {
        return methodCalls.createCoroutine().resume(ARGUMENTS);
    }

    @Benchmark
    public LuaCoroutineResult executeRecursiveFibonacci() {
        return recursiveFibonacci.createCoroutine().resume(FIBONACCI_ARGUMENTS);
    }

    @Benchmark
    public LuaCoroutineResult executeVarargMultiReturn() {
        return varargMultiReturn.createCoroutine().resume(ARGUMENTS);
    }

    @Benchmark
    public LuaCoroutineResult executeEntityUpdateKernel() {
        return entityUpdateKernel.createCoroutine().resume(ENTITY_ARGUMENTS);
    }

    @Benchmark
    public long executeIndexMetamethodCalls() {
        return callIndexMetamethods();
    }

    @Benchmark
    public long executeJvmToLuaCalls() {
        return callLuaFromJvm();
    }

    @Benchmark
    public long executeCoroutineYieldResume() {
        return runCoroutineYieldResume();
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

    private long runCoroutineYieldResume() {
        coroutineYieldResumeState.pushValue(1);
        coroutineYieldResumeState.pushInteger(10_000L);
        requireStatus(coroutineYieldResumeState.pcall(1, 1));
        long result = coroutineYieldResumeState.toInteger(-1);
        coroutineYieldResumeState.pop();
        return result;
    }

    private static void verify(LuaCoroutineFunction function, long expected) {
        verify(function, ARGUMENTS, expected);
    }

    private static void verify(LuaCoroutineFunction function, List<Object> arguments, long expected) {
        LuaCoroutineResult verification = function.createCoroutine().resume(arguments);
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
