package io.github.realmlabs.klua.dap

import io.github.realmlabs.klua.api.LuaCoroutineFunction
import io.github.realmlabs.klua.debug.BreakpointManager
import io.github.realmlabs.klua.debug.DebugController
import io.github.realmlabs.klua.debug.DebugEvaluationResult
import io.github.realmlabs.klua.debug.DebugFrameView
import io.github.realmlabs.klua.debug.DebugStopReason
import io.github.realmlabs.klua.debug.LiveDebugResult
import io.github.realmlabs.klua.debug.LiveDebugRuntime
import io.github.realmlabs.klua.debug.LiveDebugThread

public data class DapStoppedEventBody(
    public val reason: String,
    public val threadId: Int,
    public val description: String? = null,
    public val allThreadsStopped: Boolean = false,
)

public data class DapThreadEventBody(
    public val reason: String,
    public val threadId: Int,
)

public data object DapTerminatedEventBody

/**
 * Connects DAP commands to coroutine-backed debug sessions that are suspended by the VM.
 * Breakpoint definitions are shared across threads, while pause and step state remain
 * independent for each coroutine.
 */
public class LiveDapSession(
    capabilities: DapCapabilities = DapCapabilities(),
) : DapCommandSession {
    private val breakpointManager = BreakpointManager()
    private val runtime = LiveDebugRuntime()
    private val delegate = DapSession(
        capabilities = capabilities,
        breakpointManager = breakpointManager,
        threadProvider = DapThreadProvider {
            runtime.threads().map { thread -> DapThread(thread.id, thread.name) }
        },
    )
    private val pendingEvents = mutableListOf<DapSessionEvent>()
    private val lastResults = linkedMapOf<Int, LiveDebugResult>()
    private val frameContexts = linkedMapOf<Int, FrameContext>()
    private val terminalThreadIds = linkedSetOf<Int>()
    private var terminatedEventQueued = false

    public fun register(function: LuaCoroutineFunction, name: String = "Lua Coroutine"): DapThread {
        val thread = runtime.register(
            function = function,
            name = name,
            controller = DebugController(breakpointManager),
        )
        terminalThreadIds.remove(thread.id)
        terminatedEventQueued = false
        pendingEvents += DapSessionEvent("thread", DapThreadEventBody("started", thread.id))
        return DapThread(thread.id, thread.name)
    }

    public fun start(threadId: Int, arguments: List<Any?> = emptyList()): LiveDebugResult {
        val thread = requireThread(threadId)
        return recordResult(thread, thread.session.run(arguments))
    }

    public fun lastResult(threadId: Int): LiveDebugResult? = lastResults[threadId]

    override fun drainEvents(): List<DapSessionEvent> {
        return pendingEvents.toList().also { pendingEvents.clear() }
    }

    override fun handle(request: DapCommandRequest): DapCommandResponse {
        val body = when (request.command) {
            "disconnect" -> {
                runtime.threads().forEach { thread -> thread.session.controller.resume() }
                delegate.disconnect(request.arguments as? DapDisconnectRequest ?: DapDisconnectRequest())
            }
            "continue" -> {
                val thread = requireThread((request.arguments as? DapContinueRequest)?.threadId)
                val result = when (lastResults[thread.id]) {
                    is LiveDebugResult.Yielded -> thread.session.resumeYield()
                    else -> thread.session.continueExecution()
                }
                recordResult(thread, result)
                DapContinueResponse(allThreadsContinued = false)
            }
            "pause" -> {
                val thread = requireThread((request.arguments as? DapPauseRequest)?.threadId)
                thread.session.controller.pause()
                DapPauseResponse(paused = true)
            }
            "next" -> {
                val thread = requireThread((request.arguments as? DapStepRequest)?.threadId)
                recordResult(thread, thread.session.stepOver())
                DapStepResponse(thread.session.controller.currentStepMode())
            }
            "stepIn" -> {
                val thread = requireThread((request.arguments as? DapStepRequest)?.threadId)
                recordResult(thread, thread.session.stepInto())
                DapStepResponse(thread.session.controller.currentStepMode())
            }
            "stepOut" -> {
                val thread = requireThread((request.arguments as? DapStepRequest)?.threadId)
                recordResult(thread, thread.session.stepOut())
                DapStepResponse(thread.session.controller.currentStepMode())
            }
            "stackTrace" -> liveStackTrace(request.arguments as? DapStackTraceRequest)
            "evaluate" -> liveEvaluate(request.arguments as? DapEvaluateRequest)
            else -> return delegate.handle(request)
        }
        return DapCommandResponse(request.command, body)
    }

    private fun liveStackTrace(request: DapStackTraceRequest?): DapStackTraceResponse {
        requireNotNull(request) { "command stackTrace requires DapStackTraceRequest arguments" }
        val thread = requireThread(request.threadId)
        val frames = thread.session.frames
        val response = delegate.stackTrace(frames, request.startFrame, request.levels)
        frameContexts.clear()
        val requestedFrames = frames.drop(request.startFrame).let { remaining ->
            if (request.levels == null) remaining else remaining.take(request.levels)
        }
        response.stackFrames.zip(requestedFrames).forEach { (dapFrame, frame) ->
            frameContexts[dapFrame.id] = FrameContext(thread.id, frame.level)
        }
        return response
    }

    private fun liveEvaluate(request: DapEvaluateRequest?): DapEvaluateResponse {
        requireNotNull(request) { "command evaluate requires DapEvaluateRequest arguments" }
        require(request.expression.isNotBlank()) { "expression must not be blank" }
        val context = request.frameId?.let { frameId ->
            frameContexts[frameId] ?: throw IllegalArgumentException("unknown live frame: $frameId")
        } ?: defaultEvaluationContext()
        val result = requireThread(context.threadId).session.evaluate(request.expression, context.frameLevel)
        val value = when (result) {
            is DebugEvaluationResult.Success -> result.values.firstOrNull()
            is DebugEvaluationResult.Failure -> throw IllegalArgumentException(result.message)
        }
        return DapEvaluateResponse(
            result = debugDisplayValue(value),
            type = debugTypeName(value),
        )
    }

    private fun defaultEvaluationContext(): FrameContext {
        val stopped = runtime.threads().filter { thread -> thread.session.controller.isPaused }
        require(stopped.size == 1) { "evaluate without frameId requires exactly one stopped thread" }
        return FrameContext(stopped.single().id, 0)
    }

    private fun recordResult(thread: LiveDebugThread, result: LiveDebugResult): LiveDebugResult {
        lastResults[thread.id] = result
        when (result) {
            is LiveDebugResult.Stopped -> {
                terminalThreadIds.remove(thread.id)
                pendingEvents += DapSessionEvent(
                    "stopped",
                    DapStoppedEventBody(
                        reason = result.stop.reason.toDapReason(),
                        threadId = thread.id,
                    ),
                )
            }
            is LiveDebugResult.Yielded -> {
                terminalThreadIds.remove(thread.id)
                pendingEvents += DapSessionEvent(
                    "stopped",
                    DapStoppedEventBody(
                        reason = "pause",
                        threadId = thread.id,
                        description = "coroutine yielded",
                    ),
                )
            }
            is LiveDebugResult.Returned,
            is LiveDebugResult.RuntimeError,
            -> {
                terminalThreadIds += thread.id
                pendingEvents += DapSessionEvent("thread", DapThreadEventBody("exited", thread.id))
                if (!terminatedEventQueued && terminalThreadIds.size == runtime.threads().size) {
                    terminatedEventQueued = true
                    pendingEvents += DapSessionEvent("terminated", DapTerminatedEventBody)
                }
            }
        }
        return result
    }

    private fun requireThread(threadId: Int?): LiveDebugThread {
        if (threadId != null) {
            return runtime.thread(threadId)
                ?: throw IllegalArgumentException("unknown Lua debug thread: $threadId")
        }
        val threads = runtime.threads()
        require(threads.size == 1) { "DAP command requires threadId when multiple Lua threads are registered" }
        return threads.singleOrNull()
            ?: throw IllegalArgumentException("no Lua debug threads are registered")
    }
}

private data class FrameContext(
    val threadId: Int,
    val frameLevel: Int,
)

private fun DebugStopReason.toDapReason(): String {
    return when (this) {
        DebugStopReason.PAUSE -> "pause"
        DebugStopReason.BREAKPOINT -> "breakpoint"
        DebugStopReason.STEP -> "step"
    }
}

private fun debugTypeName(value: Any?): String {
    return when (value) {
        null -> "nil"
        is Boolean -> "boolean"
        is Number -> "number"
        is CharSequence,
        is Char,
        -> "string"
        is Map<*, *> -> "table"
        else -> "userdata"
    }
}

private fun debugDisplayValue(value: Any?): String {
    return when (value) {
        null -> "nil"
        is Map<*, *> -> "table(${value.size})"
        else -> value.toString()
    }
}
