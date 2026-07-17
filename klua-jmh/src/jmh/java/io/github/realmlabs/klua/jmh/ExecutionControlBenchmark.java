package io.github.realmlabs.klua.jmh;

import io.github.realmlabs.klua.api.Lua;
import io.github.realmlabs.klua.api.LuaConfig;
import io.github.realmlabs.klua.api.LuaCoroutineFunction;
import io.github.realmlabs.klua.api.LuaCoroutineResult;
import io.github.realmlabs.klua.api.LuaDebuggableCoroutineHandle;
import io.github.realmlabs.klua.api.LuaFunction;
import io.github.realmlabs.klua.api.LuaReturn;
import io.github.realmlabs.klua.debug.BreakpointManager;
import io.github.realmlabs.klua.debug.DebugController;
import io.github.realmlabs.klua.debug.DebugDisplayAdapters;
import io.github.realmlabs.klua.debug.DebugEvent;
import io.github.realmlabs.klua.debug.LiveDebugResult;
import io.github.realmlabs.klua.debug.LiveDebugSession;
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
public class ExecutionControlBenchmark {
    private static final String SOURCE_ID = "benchmark-execution-controls.lua";
    private static final int BREAKPOINT_LINE = 4;
    private static final long EXPECTED_RESULT = 50_005_000L;
    private static final List<Object> ARGUMENTS = List.of(10_000L);
    private static final String SOURCE = """
            local iterations = ...
            local value = 0
            for index = 1, iterations do
                value = value + index
            end
            return value
            """;

    private static final LuaFunction NO_OP_HOOK = context -> LuaReturn.none();

    private LuaCoroutineFunction debugDisabled;
    private LuaCoroutineFunction debugEnabled;
    private LuaCoroutineFunction budgetDisabled;
    private LuaCoroutineFunction budgetEnabled;
    private DebugController noStopController;

    @Setup
    public void setUp() {
        debugDisabled = load(new LuaConfig(false));
        debugEnabled = load(new LuaConfig(true));
        budgetDisabled = load(LuaConfig.production(0));
        budgetEnabled = load(LuaConfig.production(1_000_000));

        noStopController = new DebugController(new BreakpointManager(), condition -> true);
        noStopController.setBreakpoint(SOURCE_ID, 1_000, true, null);

        verifyReturned(run(debugDisabled));
        verifyReturned(run(debugEnabled));
        verifyReturned(run(budgetDisabled));
        verifyReturned(run(budgetEnabled));
        verifyReturned(runWithObserver());
        verifyReturned(runWithHook("l", 0));
        verifyReturned(runWithHook("", 100));
        verifyBreakpointStep();
    }

    @Benchmark
    public LuaCoroutineResult executeDebugDisabled() {
        return run(debugDisabled);
    }

    @Benchmark
    public LuaCoroutineResult executeDebugEnabledWithoutObserver() {
        return run(debugEnabled);
    }

    @Benchmark
    public LuaCoroutineResult executeBreakpointObserverWithoutHit() {
        return runWithObserver();
    }

    @Benchmark
    public LuaCoroutineResult executeLineHook() {
        return runWithHook("l", 0);
    }

    @Benchmark
    public LuaCoroutineResult executeCountHook() {
        return runWithHook("", 100);
    }

    @Benchmark
    public LiveDebugResult executeBreakpointStepAndContinue() {
        return runBreakpointStep();
    }

    @Benchmark
    public LuaCoroutineResult executeInstructionBudgetDisabled() {
        return run(budgetDisabled);
    }

    @Benchmark
    public LuaCoroutineResult executeInstructionBudgetEnabled() {
        return run(budgetEnabled);
    }

    private static LuaCoroutineFunction load(LuaConfig config) {
        return Lua.create(config).load(SOURCE, SOURCE_ID).asCoroutineFunction();
    }

    private static LuaCoroutineResult run(LuaCoroutineFunction function) {
        return function.createCoroutine().resume(ARGUMENTS);
    }

    private LuaCoroutineResult runWithObserver() {
        LuaDebuggableCoroutineHandle coroutine = debuggableCoroutine();
        if (!coroutine.setDebugObserver((event, sourceId, line, callDepth) ->
                noStopController.shouldStop(sourceId, line, DebugEvent.LINE, callDepth) != null)) {
            throw new IllegalStateException("debug observer was rejected");
        }
        return coroutine.resume(ARGUMENTS);
    }

    private LuaCoroutineResult runWithHook(String mask, int count) {
        LuaDebuggableCoroutineHandle coroutine = debuggableCoroutine();
        if (!coroutine.setDebugHook(NO_OP_HOOK, mask, count)) {
            throw new IllegalStateException("debug hook was rejected");
        }
        return coroutine.resume(ARGUMENTS);
    }

    private LuaDebuggableCoroutineHandle debuggableCoroutine() {
        return (LuaDebuggableCoroutineHandle) debugEnabled.createCoroutine();
    }

    private LiveDebugResult runBreakpointStep() {
        DebugController controller = new DebugController(new BreakpointManager(), condition -> true);
        LiveDebugSession session = new LiveDebugSession(
                debugEnabled,
                controller,
                DebugDisplayAdapters.Companion.getEmpty()
        );
        controller.setBreakpoint(SOURCE_ID, BREAKPOINT_LINE, true, null);
        LiveDebugResult firstStop = session.run(ARGUMENTS);
        if (!(firstStop instanceof LiveDebugResult.Stopped)) {
            throw new IllegalStateException("breakpoint control did not stop: " + firstStop);
        }
        LiveDebugResult stepStop = session.stepInto();
        if (!(stepStop instanceof LiveDebugResult.Stopped)) {
            throw new IllegalStateException("step control did not stop: " + stepStop);
        }
        controller.clearBreakpoint(SOURCE_ID, BREAKPOINT_LINE);
        return session.continueExecution();
    }

    private LiveDebugResult verifyBreakpointStep() {
        LiveDebugResult result = runBreakpointStep();
        if (!(result instanceof LiveDebugResult.Returned returned)
                || !returned.getValues().equals(List.of(EXPECTED_RESULT))) {
            throw new IllegalStateException("breakpoint-step benchmark setup produced " + result);
        }
        return result;
    }

    private static void verifyReturned(LuaCoroutineResult result) {
        if (!(result instanceof LuaCoroutineResult.Returned returned)
                || !returned.getValues().equals(List.of(EXPECTED_RESULT))) {
            throw new IllegalStateException("execution-control benchmark setup produced " + result);
        }
    }
}
