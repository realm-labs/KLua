package io.github.realmlabs.klua.dap

import io.github.realmlabs.klua.debug.Breakpoint
import io.github.realmlabs.klua.debug.BreakpointManager
import io.github.realmlabs.klua.debug.BreakpointRequest
import io.github.realmlabs.klua.debug.DebugController
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

public class DapSession(
    private val capabilities: DapCapabilities = DapCapabilities(),
    private val breakpointManager: BreakpointManager = BreakpointManager(),
    private val debugController: DebugController = DebugController(breakpointManager),
) {
    private var initialized = false
    private var configured = false
    private var initializeRequest: DapInitializeRequest? = null

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

    public fun breakpointAt(sourceId: String, line: Int): Breakpoint? {
        return breakpointManager.breakpointAt(sourceId, line)
    }

    public fun currentStepMode(): StepMode {
        return debugController.currentStepMode()
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
