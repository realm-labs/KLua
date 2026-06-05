package io.github.realmlabs.klua.debug

public data class Breakpoint(
    public val sourceId: String,
    public val line: Int,
    public val enabled: Boolean = true,
    public val condition: String? = null,
)

public data class BreakpointRequest(
    public val line: Int,
    public val enabled: Boolean = true,
    public val condition: String? = null,
)

public class BreakpointManager {
    private val breakpoints = linkedMapOf<BreakpointKey, Breakpoint>()

    public fun setBreakpoint(
        sourceId: String,
        line: Int,
        enabled: Boolean = true,
        condition: String? = null,
    ): Breakpoint {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(line > 0) { "line must be positive: $line" }
        val breakpoint = Breakpoint(sourceId, line, enabled, condition)
        breakpoints[BreakpointKey(sourceId, line)] = breakpoint
        return breakpoint
    }

    public fun clearBreakpoint(sourceId: String, line: Int): Boolean {
        return breakpoints.remove(BreakpointKey(sourceId, line)) != null
    }

    public fun clearSource(sourceId: String): Int {
        val keys = breakpoints.keys.filter { key -> key.sourceId == sourceId }
        for (key in keys) {
            breakpoints.remove(key)
        }
        return keys.size
    }

    public fun replaceSourceBreakpoints(sourceId: String, requests: List<BreakpointRequest>): List<Breakpoint> {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        clearSource(sourceId)
        return requests.map { request ->
            setBreakpoint(sourceId, request.line, request.enabled, request.condition)
        }
    }

    public fun breakpointAt(sourceId: String, line: Int): Breakpoint? {
        return breakpoints[BreakpointKey(sourceId, line)]
    }

    public fun isBreakpoint(sourceId: String, line: Int): Boolean {
        return breakpointAt(sourceId, line)?.enabled == true
    }

    public fun listBreakpoints(sourceId: String? = null): List<Breakpoint> {
        return breakpoints.values
            .filter { breakpoint -> sourceId == null || breakpoint.sourceId == sourceId }
            .sortedWith(compareBy<Breakpoint> { it.sourceId }.thenBy { it.line })
    }
}

private data class BreakpointKey(
    val sourceId: String,
    val line: Int,
)
