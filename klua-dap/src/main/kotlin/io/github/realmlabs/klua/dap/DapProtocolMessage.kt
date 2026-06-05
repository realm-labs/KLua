package io.github.realmlabs.klua.dap

public sealed interface DapProtocolMessage {
    public val seq: Int
}

public data class DapRequestMessage(
    override val seq: Int,
    public val command: String,
    public val arguments: DapJsonValue? = null,
) : DapProtocolMessage

public data class DapResponseMessage(
    override val seq: Int,
    public val requestSeq: Int,
    public val command: String,
    public val success: Boolean,
    public val body: DapJsonValue? = null,
    public val message: String? = null,
) : DapProtocolMessage

public data class DapEventMessage(
    override val seq: Int,
    public val event: String,
    public val body: DapJsonValue? = null,
) : DapProtocolMessage

public object DapProtocolCodec {
    public fun parse(json: String): DapProtocolMessage {
        val message = DapJson.parse(json).asObject("DAP message")
        return when (val type = message.requiredString("type")) {
            "request" -> DapRequestMessage(
                seq = message.requiredInt("seq"),
                command = message.requiredString("command"),
                arguments = message.properties["arguments"],
            )
            "response" -> DapResponseMessage(
                seq = message.requiredInt("seq"),
                requestSeq = message.requiredInt("request_seq"),
                command = message.requiredString("command"),
                success = message.requiredBoolean("success"),
                body = message.properties["body"],
                message = message.optionalString("message"),
            )
            "event" -> DapEventMessage(
                seq = message.requiredInt("seq"),
                event = message.requiredString("event"),
                body = message.properties["body"],
            )
            else -> throw IllegalArgumentException("unsupported DAP message type: $type")
        }
    }

    public fun stringify(message: DapProtocolMessage): String {
        return DapJson.stringify(message.toJsonObject())
    }
}

private fun DapProtocolMessage.toJsonObject(): DapJsonObject {
    val properties = linkedMapOf<String, DapJsonValue>(
        "seq" to DapJsonNumber(seq.toDouble()),
    )
    when (this) {
        is DapRequestMessage -> {
            properties["type"] = DapJsonString("request")
            properties["command"] = DapJsonString(command)
            if (arguments != null) properties["arguments"] = arguments
        }
        is DapResponseMessage -> {
            properties["type"] = DapJsonString("response")
            properties["request_seq"] = DapJsonNumber(requestSeq.toDouble())
            properties["success"] = DapJsonBoolean(success)
            properties["command"] = DapJsonString(command)
            if (message != null) properties["message"] = DapJsonString(message)
            if (body != null) properties["body"] = body
        }
        is DapEventMessage -> {
            properties["type"] = DapJsonString("event")
            properties["event"] = DapJsonString(event)
            if (body != null) properties["body"] = body
        }
    }
    return DapJsonObject(properties)
}

private fun DapJsonValue.asObject(description: String): DapJsonObject {
    return this as? DapJsonObject
        ?: throw IllegalArgumentException("expected $description to be a JSON object")
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

private fun DapJsonObject.requiredBoolean(name: String): Boolean {
    return (properties[name] as? DapJsonBoolean)?.value
        ?: throw IllegalArgumentException("expected boolean property: $name")
}

private fun DapJsonObject.requiredInt(name: String): Int {
    val value = (properties[name] as? DapJsonNumber)?.value
        ?: throw IllegalArgumentException("expected number property: $name")
    require(value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
        "expected integer property: $name"
    }
    return value.toInt()
}
