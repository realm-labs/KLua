package io.github.realmlabs.klua.debug

public enum class DebugEvent {
    CALL,
    RETURN,
    LINE,
    COUNT,
    TAIL_CALL,
    EXCEPTION,
}

public sealed class StepMode {
    public data object None : StepMode()
    public data object Into : StepMode()

    public data class Over(
        public val startDepth: Int,
    ) : StepMode() {
        init {
            require(startDepth >= 0) { "startDepth must be non-negative: $startDepth" }
        }
    }

    public data class Out(
        public val targetDepth: Int,
    ) : StepMode() {
        init {
            require(targetDepth >= 0) { "targetDepth must be non-negative: $targetDepth" }
        }
    }
}

public enum class DebugStopReason {
    PAUSE,
    BREAKPOINT,
    STEP,
}

public data class DebugStop(
    public val reason: DebugStopReason,
    public val sourceId: String,
    public val line: Int,
    public val event: DebugEvent,
    public val breakpoint: Breakpoint? = null,
)

public class DebugController(
    public val breakpoints: BreakpointManager = BreakpointManager(),
) {
    private var paused: Boolean = false
    private var stepMode: StepMode = StepMode.None

    public val isPaused: Boolean
        get() = paused

    public fun currentStepMode(): StepMode = stepMode

    public fun pause() {
        paused = true
    }

    public fun resume() {
        paused = false
        stepMode = StepMode.None
    }

    public fun setBreakpoint(
        sourceId: String,
        line: Int,
        enabled: Boolean = true,
        condition: String? = null,
    ): Breakpoint {
        return breakpoints.setBreakpoint(sourceId, line, enabled, condition)
    }

    public fun clearBreakpoint(sourceId: String, line: Int): Boolean {
        return breakpoints.clearBreakpoint(sourceId, line)
    }

    public fun stepInto() {
        paused = false
        stepMode = StepMode.Into
    }

    public fun stepOver(startDepth: Int) {
        paused = false
        stepMode = StepMode.Over(startDepth)
    }

    public fun stepOut(currentDepth: Int) {
        require(currentDepth >= 0) { "currentDepth must be non-negative: $currentDepth" }
        paused = false
        stepMode = StepMode.Out((currentDepth - 1).coerceAtLeast(0))
    }

    public fun shouldStop(sourceId: String, line: Int, event: DebugEvent, callDepth: Int): DebugStop? {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(line > 0) { "line must be positive: $line" }
        require(callDepth >= 0) { "callDepth must be non-negative: $callDepth" }

        if (paused) {
            return DebugStop(DebugStopReason.PAUSE, sourceId, line, event)
        }

        if (event == DebugEvent.LINE) {
            val breakpoint = breakpoints.breakpointAt(sourceId, line)
            if (breakpoint?.enabled == true) {
                paused = true
                return DebugStop(DebugStopReason.BREAKPOINT, sourceId, line, event, breakpoint)
            }
        }

        if (shouldStopForStep(event, callDepth)) {
            stepMode = StepMode.None
            paused = true
            return DebugStop(DebugStopReason.STEP, sourceId, line, event)
        }

        return null
    }

    private fun shouldStopForStep(event: DebugEvent, callDepth: Int): Boolean {
        return when (val mode = stepMode) {
            StepMode.None -> false
            StepMode.Into -> event == DebugEvent.LINE
            is StepMode.Over -> event == DebugEvent.LINE && callDepth <= mode.startDepth
            is StepMode.Out -> event == DebugEvent.RETURN && callDepth <= mode.targetDepth
        }
    }
}
