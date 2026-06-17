package io.github.realmlabs.klua.dap

import io.github.realmlabs.klua.debug.DebugFrameView
import io.github.realmlabs.klua.debug.StepMode

public data class DapWireExchange(
    public val response: DapResponseMessage,
    public val events: List<DapEventMessage> = emptyList(),
)

public fun interface DapStackTraceFrameProvider {
    public fun frames(threadId: Int): List<DebugFrameView>
}

public class DapWireSession(
    private val session: DapSession = DapSession(),
    private val stackTraceFrameProvider: DapStackTraceFrameProvider = DapStackTraceFrameProvider { emptyList() },
) {
    private var nextSeq = 1

    public fun handle(request: DapRequestMessage): DapResponseMessage {
        return try {
            val commandResponse = session.handle(request.toCommandRequest())
            DapResponseMessage(
                seq = nextSeq++,
                requestSeq = request.seq,
                command = request.command,
                success = true,
                body = commandResponse.body.toDapJson(),
            )
        } catch (error: IllegalArgumentException) {
            DapResponseMessage(
                seq = nextSeq++,
                requestSeq = request.seq,
                command = request.command,
                success = false,
                message = error.message ?: "invalid DAP request",
            )
        }
    }

    public fun handleExchange(request: DapRequestMessage): DapWireExchange {
        val response = handle(request)
        val events = if (response.success && request.command == "initialize") {
            listOf(DapEventMessage(seq = nextSeq++, event = "initialized"))
        } else {
            emptyList()
        }
        return DapWireExchange(response, events)
    }

    public fun handleJson(json: String): String {
        val message = DapProtocolCodec.parse(json)
        require(message is DapRequestMessage) { "expected DAP request message" }
        return DapProtocolCodec.stringify(handle(message))
    }

    public fun handleJsonExchange(json: String): List<String> {
        val message = DapProtocolCodec.parse(json)
        require(message is DapRequestMessage) { "expected DAP request message" }
        val exchange = handleExchange(message)
        return listOf(DapProtocolCodec.stringify(exchange.response)) +
            exchange.events.map { event -> DapProtocolCodec.stringify(event) }
    }

    private fun DapRequestMessage.toCommandRequest(): DapCommandRequest {
        val arguments = when (command) {
            "initialize" -> argumentsObject().toInitializeRequest()
            "launch" -> argumentsObject().toLaunchRequest()
            "attach" -> argumentsObject().toAttachRequest()
            "disconnect" -> argumentsObjectOrNull()?.toDisconnectRequest() ?: DapDisconnectRequest()
            "setBreakpoints" -> argumentsObject().toSetBreakpointsRequest()
            "configurationDone", "continue", "pause", "stepIn", "threads" -> null
            "next", "stepOut" -> DapStepRequest(argumentsObjectOrNull()?.optionalInt("callDepth") ?: 0)
            "stackTrace" -> argumentsObject().toStackTraceRequest(stackTraceFrameProvider)
            "scopes" -> DapScopesRequest(argumentsObject().requiredInt("frameId"))
            "variables" -> argumentsObject().toVariablesRequest()
            "evaluate" -> argumentsObject().toEvaluateRequest()
            else -> arguments
        }
        return DapCommandRequest(command, arguments)
    }
}

private fun DapRequestMessage.argumentsObject(): DapJsonObject {
    return arguments as? DapJsonObject
        ?: throw IllegalArgumentException("command $command requires JSON object arguments")
}

private fun DapRequestMessage.argumentsObjectOrNull(): DapJsonObject? {
    return arguments as? DapJsonObject
}

private fun DapJsonObject.toInitializeRequest(): DapInitializeRequest {
    return DapInitializeRequest(
        clientId = optionalString("clientID") ?: optionalString("clientId"),
        clientName = optionalString("clientName"),
        adapterId = optionalString("adapterID") ?: optionalString("adapterId") ?: "klua",
        linesStartAt1 = optionalBoolean("linesStartAt1") ?: true,
        columnsStartAt1 = optionalBoolean("columnsStartAt1") ?: true,
    )
}

private fun DapJsonObject.toLaunchRequest(): DapLaunchRequest {
    return DapLaunchRequest(
        program = requiredString("program"),
        cwd = optionalString("cwd"),
        args = optionalStringArray("args") ?: emptyList(),
        stopOnEntry = optionalBoolean("stopOnEntry") ?: false,
    )
}

private fun DapJsonObject.toAttachRequest(): DapAttachRequest {
    return DapAttachRequest(
        processId = optionalInt("processId"),
        host = optionalString("host"),
        port = optionalInt("port"),
    )
}

private fun DapJsonObject.toDisconnectRequest(): DapDisconnectRequest {
    return DapDisconnectRequest(
        restart = optionalBoolean("restart") ?: false,
        terminateDebuggee = optionalBoolean("terminateDebuggee") ?: false,
    )
}

private fun DapJsonObject.toSetBreakpointsRequest(): DapSetBreakpointsRequest {
    val source = requiredObject("source")
    val breakpoints = optionalArray("breakpoints")?.values.orEmpty().map { value ->
        val breakpoint = value.asObject("source breakpoint")
        DapSourceBreakpoint(
            line = breakpoint.requiredInt("line"),
            condition = breakpoint.optionalString("condition"),
        )
    }
    return DapSetBreakpointsRequest(
        source = DapSource(
            path = source.requiredString("path"),
            name = source.optionalString("name"),
        ),
        breakpoints = breakpoints,
    )
}

private fun DapJsonObject.toVariablesRequest(): DapVariablesRequest {
    return DapVariablesRequest(
        variablesReference = requiredInt("variablesReference"),
        start = optionalInt("start") ?: 0,
        count = optionalInt("count") ?: 50,
    )
}

private fun DapJsonObject.toStackTraceRequest(
    frameProvider: DapStackTraceFrameProvider,
): DapStackTraceRequest {
    val threadId = requiredInt("threadId")
    return DapStackTraceRequest(
        frames = frameProvider.frames(threadId),
        startFrame = optionalInt("startFrame") ?: 0,
        levels = optionalInt("levels"),
    )
}

private fun DapJsonObject.toEvaluateRequest(): DapEvaluateRequest {
    return DapEvaluateRequest(
        expression = requiredString("expression"),
        frameId = optionalInt("frameId"),
        context = optionalString("context"),
    )
}

private fun Any?.toDapJson(): DapJsonValue? {
    return when (this) {
        null -> null
        is DapInitializeResponse -> DapJsonObject(linkedMapOf("capabilities" to capabilities.toDapJson()))
        is DapCapabilities -> toDapJson()
        is DapLaunchResponse -> DapJsonObject(
            linkedMapOf(
                "launched" to DapJsonBoolean(launched),
                "program" to DapJsonString(program),
            ),
        )
        is DapAttachResponse -> DapJsonObject(
            linkedMapOf(
                "attached" to DapJsonBoolean(attached),
                "target" to DapJsonString(target),
            ),
        )
        is DapDisconnectResponse -> DapJsonObject(
            linkedMapOf(
                "disconnected" to DapJsonBoolean(disconnected),
                "restart" to DapJsonBoolean(restart),
                "terminateDebuggee" to DapJsonBoolean(terminateDebuggee),
            ),
        )
        is DapSetBreakpointsResponse -> DapJsonObject(
            linkedMapOf("breakpoints" to DapJsonArray(breakpoints.map { breakpoint -> breakpoint.toDapJson() })),
        )
        is DapConfigurationDoneResponse -> DapJsonObject(linkedMapOf("configured" to DapJsonBoolean(configured)))
        is DapContinueResponse -> DapJsonObject(linkedMapOf("allThreadsContinued" to DapJsonBoolean(allThreadsContinued)))
        is DapPauseResponse -> DapJsonObject(linkedMapOf("paused" to DapJsonBoolean(paused)))
        is DapStepResponse -> DapJsonObject(linkedMapOf("stepMode" to stepMode.toDapJson()))
        is DapStackTraceResponse -> DapJsonObject(
            linkedMapOf(
                "stackFrames" to DapJsonArray(stackFrames.map { frame -> frame.toDapJson() }),
                "totalFrames" to DapJsonNumber(totalFrames.toDouble()),
            ),
        )
        is DapScopesResponse -> DapJsonObject(linkedMapOf("scopes" to DapJsonArray(scopes.map { scope -> scope.toDapJson() })))
        is DapVariablesResponse -> DapJsonObject(
            linkedMapOf("variables" to DapJsonArray(variables.map { variable -> variable.toDapJson() })),
        )
        is DapEvaluateResponse -> DapJsonObject(
            linkedMapOf(
                "result" to DapJsonString(result),
                "type" to DapJsonString(type),
                "variablesReference" to DapJsonNumber(variablesReference.toDouble()),
            ),
        )
        is DapThreadsResponse -> DapJsonObject(linkedMapOf("threads" to DapJsonArray(threads.map { thread -> thread.toDapJson() })))
        else -> throw IllegalArgumentException("unsupported DAP response body: ${this::class.java.simpleName}")
    }
}

private fun DapCapabilities.toDapJson(): DapJsonObject {
    return DapJsonObject(
        linkedMapOf(
            "supportsConfigurationDoneRequest" to DapJsonBoolean(supportsConfigurationDoneRequest),
            "supportsConditionalBreakpoints" to DapJsonBoolean(supportsConditionalBreakpoints),
            "supportsHitConditionalBreakpoints" to DapJsonBoolean(supportsHitConditionalBreakpoints),
            "supportsEvaluateForHovers" to DapJsonBoolean(supportsEvaluateForHovers),
            "supportsStepBack" to DapJsonBoolean(supportsStepBack),
            "supportsSetVariable" to DapJsonBoolean(supportsSetVariable),
        ),
    )
}

private fun DapBreakpoint.toDapJson(): DapJsonObject {
    val properties = linkedMapOf<String, DapJsonValue>(
        "verified" to DapJsonBoolean(verified),
        "source" to source.toDapJson(),
        "line" to DapJsonNumber(line.toDouble()),
    )
    if (condition != null) properties["condition"] = DapJsonString(condition)
    return DapJsonObject(properties)
}

private fun DapSource.toDapJson(): DapJsonObject {
    val properties = linkedMapOf<String, DapJsonValue>("path" to DapJsonString(path))
    if (name != null) properties["name"] = DapJsonString(name)
    return DapJsonObject(properties)
}

private fun StepMode.toDapJson(): DapJsonObject {
    val properties = linkedMapOf<String, DapJsonValue>()
    when (this) {
        StepMode.None -> properties["mode"] = DapJsonString("none")
        StepMode.Into -> properties["mode"] = DapJsonString("into")
        is StepMode.Over -> {
            properties["mode"] = DapJsonString("over")
            properties["startDepth"] = DapJsonNumber(startDepth.toDouble())
        }
        is StepMode.Out -> {
            properties["mode"] = DapJsonString("out")
            properties["targetDepth"] = DapJsonNumber(targetDepth.toDouble())
        }
    }
    return DapJsonObject(properties)
}

private fun DapStackFrame.toDapJson(): DapJsonObject {
    return DapJsonObject(
        linkedMapOf(
            "id" to DapJsonNumber(id.toDouble()),
            "name" to DapJsonString(name),
            "source" to source.toDapJson(),
            "line" to DapJsonNumber(line.toDouble()),
            "column" to DapJsonNumber(column.toDouble()),
        ),
    )
}

private fun DapScope.toDapJson(): DapJsonObject {
    return DapJsonObject(
        linkedMapOf(
            "name" to DapJsonString(name),
            "variablesReference" to DapJsonNumber(variablesReference.toDouble()),
            "expensive" to DapJsonBoolean(expensive),
        ),
    )
}

private fun DapVariable.toDapJson(): DapJsonObject {
    return DapJsonObject(
        linkedMapOf(
            "name" to DapJsonString(name),
            "value" to DapJsonString(value),
            "type" to DapJsonString(type),
            "variablesReference" to DapJsonNumber(variablesReference.toDouble()),
        ),
    )
}

private fun DapThread.toDapJson(): DapJsonObject {
    return DapJsonObject(
        linkedMapOf(
            "id" to DapJsonNumber(id.toDouble()),
            "name" to DapJsonString(name),
        ),
    )
}

private fun DapJsonValue.asObject(description: String): DapJsonObject {
    return this as? DapJsonObject
        ?: throw IllegalArgumentException("expected $description to be a JSON object")
}

private fun DapJsonObject.requiredObject(name: String): DapJsonObject {
    return properties[name] as? DapJsonObject
        ?: throw IllegalArgumentException("expected object property: $name")
}

private fun DapJsonObject.optionalArray(name: String): DapJsonArray? {
    val value = properties[name] ?: return null
    return value as? DapJsonArray
        ?: throw IllegalArgumentException("expected array property: $name")
}

private fun DapJsonObject.optionalStringArray(name: String): List<String>? {
    return optionalArray(name)?.values?.mapIndexed { index, value ->
        (value as? DapJsonString)?.value
            ?: throw IllegalArgumentException("expected string element in $name at index $index")
    }
}

private fun DapJsonObject.requiredString(name: String): String {
    return (properties[name] as? DapJsonString)?.value
        ?: throw IllegalArgumentException("expected string property: $name")
}

private fun DapJsonObject.optionalString(name: String): String? {
    val value = properties[name] ?: return null
    return (value as? DapJsonString)?.value
        ?: throw IllegalArgumentException("expected string property: $name")
}

private fun DapJsonObject.optionalBoolean(name: String): Boolean? {
    val value = properties[name] ?: return null
    return (value as? DapJsonBoolean)?.value
        ?: throw IllegalArgumentException("expected boolean property: $name")
}

private fun DapJsonObject.requiredInt(name: String): Int {
    return optionalInt(name) ?: throw IllegalArgumentException("expected integer property: $name")
}

private fun DapJsonObject.optionalInt(name: String): Int? {
    val value = properties[name] ?: return null
    val number = (value as? DapJsonNumber)?.value
        ?: throw IllegalArgumentException("expected number property: $name")
    require(number % 1.0 == 0.0 && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE) {
        "expected integer property: $name"
    }
    return number.toInt()
}
