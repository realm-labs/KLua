package io.github.realmlabs.klua.dap

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

public class DapSession(
    private val capabilities: DapCapabilities = DapCapabilities(),
) {
    private var initialized = false
    private var initializeRequest: DapInitializeRequest? = null

    public val isInitialized: Boolean
        get() = initialized

    public val clientId: String?
        get() = initializeRequest?.clientId

    public fun initialize(request: DapInitializeRequest): DapInitializeResponse {
        initialized = true
        initializeRequest = request
        return DapInitializeResponse(capabilities)
    }
}
