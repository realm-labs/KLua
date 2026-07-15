package io.github.realmlabs.klua.debug

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.api.LuaConfig
import io.github.realmlabs.klua.api.LuaCoroutineFunction
import io.github.realmlabs.klua.api.LuaCoroutineResult
import io.github.realmlabs.klua.api.LuaDebugEvent
import io.github.realmlabs.klua.api.LuaDebuggableCoroutineHandle
import io.github.realmlabs.klua.api.LuaException

public sealed interface LiveDebugResult {
    public data class Stopped(
        public val stop: DebugStop,
        public val frames: List<DebugFrameView>,
    ) : LiveDebugResult

    public data class Returned(
        public val values: List<Any?>,
    ) : LiveDebugResult

    public data class Yielded(
        public val values: List<Any?>,
    ) : LiveDebugResult

    public data class RuntimeError(
        public val error: LuaCoroutineResult.RuntimeError,
    ) : LiveDebugResult
}

public sealed interface DebugEvaluationResult {
    public data class Success(
        public val values: List<Any?>,
    ) : DebugEvaluationResult

    public data class Failure(
        public val message: String,
    ) : DebugEvaluationResult
}

public class LiveDebugSession(
    function: LuaCoroutineFunction,
    public val controller: DebugController = DebugController(),
    private val displayAdapters: DebugDisplayAdapters = DebugDisplayAdapters.Empty,
) {
    private val coroutine = function.createCoroutine() as? LuaDebuggableCoroutineHandle
        ?: throw IllegalArgumentException("Lua function does not expose a debuggable coroutine")
    private var started: Boolean = false
    private var lastStop: DebugStop? = null

    init {
        coroutine.setDebugObserver { event, sourceId, line, callDepth ->
            val stop = controller.shouldStop(sourceId, line, event.toDebugEvent(), callDepth)
            lastStop = stop
            stop != null
        }
    }

    public val frames: List<DebugFrameView>
        get() = DebugFrameView.fromLuaFrames(coroutine.luaFrames, displayAdapters)

    public fun run(arguments: List<Any?> = emptyList()): LiveDebugResult {
        check(!started) { "debug session has already started" }
        started = true
        return resumeCoroutine(arguments)
    }

    public fun resumeYield(arguments: List<Any?> = emptyList()): LiveDebugResult {
        check(started) { "debug session has not started" }
        check(!controller.isPaused) { "debug session is stopped; use continueExecution or a step command" }
        return resumeCoroutine(arguments)
    }

    public fun continueExecution(): LiveDebugResult {
        requireStopped()
        controller.resume()
        return resumeCoroutine(emptyList())
    }

    public fun stepInto(): LiveDebugResult {
        requireStopped()
        controller.stepInto()
        return resumeCoroutine(emptyList())
    }

    public fun stepOver(): LiveDebugResult {
        val callDepth = requireStopped()
        controller.stepOver(callDepth)
        return resumeCoroutine(emptyList())
    }

    public fun stepOut(): LiveDebugResult {
        val callDepth = requireStopped()
        controller.stepOut(callDepth)
        return resumeCoroutine(emptyList())
    }

    public fun evaluate(expression: String, frameLevel: Int = 0): DebugEvaluationResult {
        requireStopped()
        if (expression.isBlank()) {
            return DebugEvaluationResult.Failure("debug expression must not be blank")
        }
        val frame = frames.getOrNull(frameLevel)
            ?: return DebugEvaluationResult.Failure("debug frame level out of range: $frameLevel")
        val bindings = linkedMapOf<String, Any?>()
        addScalarBindings(bindings, frame.globals)
        addScalarBindings(bindings, frame.upvalues)
        addScalarBindings(bindings, frame.locals)
        return try {
            val evaluationLua = Lua.create(
                LuaConfig(
                    debugEnabled = false,
                    standardLibraries = emptySet(),
                ),
            )
            bindings.forEach { (name, value) -> evaluationLua.globals().set(name, value) }
            DebugEvaluationResult.Success(
                evaluationLua.load("return ($expression)", "=(debug evaluate)").eval().values,
            )
        } catch (error: LuaException) {
            DebugEvaluationResult.Failure(error.message ?: error::class.java.simpleName)
        }
    }

    private fun requireStopped(): Int {
        check(started) { "debug session has not started" }
        check(controller.isPaused) { "debug session is not stopped" }
        return frames.size.also { depth -> check(depth > 0) { "debug stop has no Lua frames" } }
    }

    private fun resumeCoroutine(arguments: List<Any?>): LiveDebugResult {
        lastStop = null
        return when (val result = coroutine.resume(arguments)) {
            is LuaCoroutineResult.Returned -> LiveDebugResult.Returned(result.values)
            is LuaCoroutineResult.Yielded -> LiveDebugResult.Yielded(result.values)
            is LuaCoroutineResult.RuntimeError -> LiveDebugResult.RuntimeError(result)
            LuaCoroutineResult.DebugSuspended -> {
                val stop = checkNotNull(lastStop) { "runtime suspended without a debugger stop" }
                LiveDebugResult.Stopped(stop, frames)
            }
        }
    }
}

private fun addScalarBindings(target: MutableMap<String, Any?>, variables: List<DebugVariable>) {
    variables.forEach { variable ->
        if (variable.name.matches(LUA_IDENTIFIER) && variable.value.isDebugScalar()) {
            target[variable.name] = if (variable.value is Char) variable.value.toString() else variable.value
        }
    }
}

private fun Any?.isDebugScalar(): Boolean {
    return this == null || this is Boolean || this is Byte || this is Short || this is Int || this is Long ||
        this is Float || this is Double || this is CharSequence || this is Char
}

private fun LuaDebugEvent.toDebugEvent(): DebugEvent {
    return when (this) {
        LuaDebugEvent.LINE -> DebugEvent.LINE
    }
}

private val LUA_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
