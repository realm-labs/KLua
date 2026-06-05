package io.github.realmlabs.klua.tools

import io.github.realmlabs.klua.api.Lua

public data class DebugCliInvocation(
    public val session: DebugCliSession,
)

public object DebugCliInvocationParser {
    public fun parse(args: Array<String>): DebugCliInvocation {
        require(args.isNotEmpty()) { usage() }
        require(args[0] == "--debug") { usage() }
        require(args.size >= 2) { usage() }
        return DebugCliInvocation(
            DebugCliSession(
                program = args[1],
                args = args.drop(2),
            ),
        )
    }

    private fun usage(): String {
        return "usage: klua --debug <script.lua> [args...]"
    }
}

public object DebugCliMain {
    public fun run(
        args: Array<String>,
        readLine: () -> String?,
        writeLine: (String) -> Unit,
        luaFactory: () -> Lua = { Lua.create() },
        readSource: (String) -> String,
    ): Int {
        val invocation = try {
            DebugCliInvocationParser.parse(args)
        } catch (error: IllegalArgumentException) {
            writeLine(error.message ?: "invalid arguments")
            return 2
        }
        val runner = DebugCliRunner(
            session = invocation.session,
            luaFactory = luaFactory,
            readSource = readSource,
        )
        writeLine("debugging ${invocation.session.program}")

        while (true) {
            val input = readLine() ?: return 0
            val command = try {
                DebugCliCommandParser.parse(input)
            } catch (error: IllegalArgumentException) {
                writeLine("error: ${error.message ?: "invalid command"}")
                continue
            }
            val result = runner.execute(command)
            writeLine(result.format())
            if (command == DebugCliCommand.Quit) {
                return 0
            }
        }
    }

    @JvmStatic
    public fun main(args: Array<String>) {
        val exitCode = run(
            args = args,
            readLine = ::readlnOrNull,
            writeLine = ::println,
            readSource = defaultReadSource,
        )
        if (exitCode != 0) {
            kotlin.system.exitProcess(exitCode)
        }
    }
}

private val defaultReadSource: (String) -> String = { program ->
    java.nio.file.Files.readString(java.nio.file.Path.of(program))
}

private fun DebugCliResult.format(): String {
    val prefix = if (success) "ok" else "error"
    val valuesText = if (values.isEmpty()) {
        ""
    } else {
        values.joinToString(prefix = " ", separator = "\t") { value -> value?.toString() ?: "nil" }
    }
    return "$prefix: $message$valuesText"
}
