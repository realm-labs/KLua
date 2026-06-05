package io.github.realmlabs.klua.dap

import io.github.realmlabs.klua.debug.Breakpoint
import io.github.realmlabs.klua.debug.BreakpointManager
import io.github.realmlabs.klua.debug.BreakpointRequest
import io.github.realmlabs.klua.debug.DebugController
import io.github.realmlabs.klua.debug.DebugFrameView
import io.github.realmlabs.klua.debug.DebugScopeView
import io.github.realmlabs.klua.debug.DebugVariable
import io.github.realmlabs.klua.debug.StepMode

public data class DapInitializeRequest(
    public val clientId: String? = null,
    public val clientName: String? = null,
    public val adapterId: String = "klua",
    public val linesStartAt1: Boolean = true,
    public val columnsStartAt1: Boolean = true,
)

public data class DapCapabilities(
    public val supportsConfigurationDoneRequest: Boolean = true,
    public val supportsConditionalBreakpoints: Boolean = true,
    public val supportsHitConditionalBreakpoints: Boolean = false,
    public val supportsEvaluateForHovers: Boolean = false,
    public val supportsStepBack: Boolean = false,
    public val supportsSetVariable: Boolean = false,
)

public data class DapInitializeResponse(
    public val capabilities: DapCapabilities,
)

public data class DapSource(
    public val path: String,
    public val name: String? = null,
)

public data class DapSourceBreakpoint(
    public val line: Int,
    public val condition: String? = null,
)

public data class DapSetBreakpointsRequest(
    public val source: DapSource,
    public val breakpoints: List<DapSourceBreakpoint>,
)

public data class DapBreakpoint(
    public val verified: Boolean,
    public val source: DapSource,
    public val line: Int,
    public val condition: String? = null,
)

public data class DapSetBreakpointsResponse(
    public val breakpoints: List<DapBreakpoint>,
)

public data class DapConfigurationDoneResponse(
    public val configured: Boolean,
)

public data class DapContinueResponse(
    public val allThreadsContinued: Boolean = true,
)

public data class DapPauseResponse(
    public val paused: Boolean,
)

public data class DapStepResponse(
    public val stepMode: StepMode,
)

public data class DapStackFrame(
    public val id: Int,
    public val name: String,
    public val source: DapSource,
    public val line: Int,
    public val column: Int = 1,
)

public data class DapStackTraceResponse(
    public val stackFrames: List<DapStackFrame>,
    public val totalFrames: Int,
)

public data class DapScope(
    public val name: String,
    public val variablesReference: Int,
    public val expensive: Boolean = false,
)

public data class DapScopesResponse(
    public val scopes: List<DapScope>,
)

public data class DapVariable(
    public val name: String,
    public val value: String,
    public val type: String,
    public val variablesReference: Int = 0,
)

public data class DapVariablesResponse(
    public val variables: List<DapVariable>,
)

public data class DapThread(
    public val id: Int,
    public val name: String,
)

public data class DapThreadsResponse(
    public val threads: List<DapThread>,
)

public data class DapEvaluateRequest(
    public val expression: String,
    public val frameId: Int? = null,
    public val context: String? = null,
)

public data class DapEvaluateResponse(
    public val result: String,
    public val type: String,
    public val variablesReference: Int = 0,
)

public fun interface DapExpressionEvaluator {
    public fun evaluate(expression: String, frame: DebugFrameView?): DebugVariable
}

public fun interface DapThreadProvider {
    public fun threads(): List<DapThread>
}

public class DapSession(
    private val capabilities: DapCapabilities = DapCapabilities(),
    private val breakpointManager: BreakpointManager = BreakpointManager(),
    private val debugController: DebugController = DebugController(breakpointManager),
    private val expressionEvaluator: DapExpressionEvaluator = DapExpressionEvaluator { _, _ ->
        DebugVariable("", null, "nil", "nil")
    },
    private val threadProvider: DapThreadProvider = DapThreadProvider {
        listOf(DapThread(id = 1, name = "main"))
    },
) {
    private var initialized = false
    private var configured = false
    private var initializeRequest: DapInitializeRequest? = null
    private var nextFrameId = 1
    private var nextVariablesReference = 1
    private val framesById = linkedMapOf<Int, DebugFrameView>()
    private val variablesByReference = linkedMapOf<Int, VariableReference>()

    public val isInitialized: Boolean
        get() = initialized

    public val isConfigured: Boolean
        get() = configured

    public val clientId: String?
        get() = initializeRequest?.clientId

    public fun initialize(request: DapInitializeRequest): DapInitializeResponse {
        initialized = true
        initializeRequest = request
        return DapInitializeResponse(capabilities)
    }

    public fun setBreakpoints(request: DapSetBreakpointsRequest): DapSetBreakpointsResponse {
        val sourceId = request.source.path
        val breakpoints = breakpointManager.replaceSourceBreakpoints(
            sourceId,
            request.breakpoints.map { breakpoint ->
                BreakpointRequest(
                    line = breakpoint.line,
                    condition = breakpoint.condition,
                )
            },
        )
        return DapSetBreakpointsResponse(
            breakpoints.map { breakpoint -> breakpoint.toDapBreakpoint(request.source) },
        )
    }

    public fun configurationDone(): DapConfigurationDoneResponse {
        configured = true
        return DapConfigurationDoneResponse(configured = true)
    }

    public fun continueExecution(): DapContinueResponse {
        debugController.resume()
        return DapContinueResponse()
    }

    public fun pause(): DapPauseResponse {
        debugController.pause()
        return DapPauseResponse(paused = debugController.isPaused)
    }

    public fun next(callDepth: Int): DapStepResponse {
        debugController.stepOver(callDepth)
        return DapStepResponse(debugController.currentStepMode())
    }

    public fun stepIn(): DapStepResponse {
        debugController.stepInto()
        return DapStepResponse(debugController.currentStepMode())
    }

    public fun stepOut(callDepth: Int): DapStepResponse {
        debugController.stepOut(callDepth)
        return DapStepResponse(debugController.currentStepMode())
    }

    public fun stackTrace(frames: List<DebugFrameView>): DapStackTraceResponse {
        framesById.clear()
        val dapFrames = frames.map { frame ->
            val id = nextFrameId++
            framesById[id] = frame
            DapStackFrame(
                id = id,
                name = frame.sourceName,
                source = DapSource(path = frame.sourceName, name = frame.sourceName.substringAfterLast('/')),
                line = frame.line,
            )
        }
        return DapStackTraceResponse(dapFrames, dapFrames.size)
    }

    public fun scopes(frameId: Int): DapScopesResponse {
        val frame = framesById[frameId] ?: return DapScopesResponse(emptyList())
        val scopes = frame.scopes().map { scope ->
            DapScope(
                name = scope.name,
                variablesReference = storeVariables(VariableReference.Scope(scope)),
            )
        }
        return DapScopesResponse(scopes)
    }

    public fun variables(variablesReference: Int, start: Int = 0, count: Int = 50): DapVariablesResponse {
        require(start >= 0) { "start must be non-negative: $start" }
        require(count >= 0) { "count must be non-negative: $count" }
        val reference = variablesByReference[variablesReference] ?: return DapVariablesResponse(emptyList())
        val variables = when (reference) {
            is VariableReference.Scope -> reference.scope.variables.drop(start).take(count)
            is VariableReference.Variable -> reference.variable.childPage(start, count).variables
        }
        return DapVariablesResponse(variables.map { variable -> variable.toDapVariable() })
    }

    public fun evaluate(request: DapEvaluateRequest): DapEvaluateResponse {
        require(request.expression.isNotBlank()) { "expression must not be blank" }
        val frame = request.frameId?.let { frameId -> framesById[frameId] }
        val variable = expressionEvaluator.evaluate(request.expression, frame)
        val dapVariable = variable.toDapVariable()
        return DapEvaluateResponse(
            result = dapVariable.value,
            type = dapVariable.type,
            variablesReference = dapVariable.variablesReference,
        )
    }

    public fun threads(): DapThreadsResponse {
        return DapThreadsResponse(threadProvider.threads())
    }

    public fun breakpointAt(sourceId: String, line: Int): Breakpoint? {
        return breakpointManager.breakpointAt(sourceId, line)
    }

    public fun currentStepMode(): StepMode {
        return debugController.currentStepMode()
    }

    private fun storeVariables(reference: VariableReference): Int {
        val id = nextVariablesReference++
        variablesByReference[id] = reference
        return id
    }

    private fun DebugVariable.toDapVariable(): DapVariable {
        val reference = if (typeName == "table") {
            storeVariables(VariableReference.Variable(this))
        } else {
            0
        }
        return DapVariable(
            name = name,
            value = displayValue,
            type = typeName,
            variablesReference = reference,
        )
    }
}

private fun Breakpoint.toDapBreakpoint(source: DapSource): DapBreakpoint {
    return DapBreakpoint(
        verified = enabled,
        source = source,
        line = line,
        condition = condition,
    )
}

private sealed class VariableReference {
    data class Scope(
        val scope: DebugScopeView,
    ) : VariableReference()

    data class Variable(
        val variable: DebugVariable,
    ) : VariableReference()
}
