package io.github.realmlabs.klua.debug

import io.github.realmlabs.klua.api.LuaCoroutineFunction
import io.github.realmlabs.klua.api.LuaCoroutineResult
import io.github.realmlabs.klua.api.LuaDebugEvent
import io.github.realmlabs.klua.api.LuaDebuggableCoroutineHandle

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

private fun LuaDebugEvent.toDebugEvent(): DebugEvent {
    return when (this) {
        LuaDebugEvent.LINE -> DebugEvent.LINE
    }
}
