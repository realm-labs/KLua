package io.github.realmlabs.klua.tools

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.api.LuaException
import java.nio.file.Files
import java.nio.file.Path

public data class DebugCliInvocation(
    public val session: DebugCliSession,
)

public data class BytecodeCompileInvocation(
    public val source: String,
    public val output: String,
)

public sealed interface ToolsCliInvocation {
    public data class Debug(
        public val invocation: DebugCliInvocation,
    ) : ToolsCliInvocation

    public data class Compile(
        public val invocation: BytecodeCompileInvocation,
    ) : ToolsCliInvocation
}

public object ToolsCliInvocationParser {
    public fun parse(args: Array<String>): ToolsCliInvocation {
        require(args.isNotEmpty()) { usage() }
        return when (args[0]) {
            "--debug" -> ToolsCliInvocation.Debug(DebugCliInvocationParser.parse(args))
            "--compile" -> parseCompile(args)
            else -> throw IllegalArgumentException(usage())
        }
    }

    public fun usage(): String {
        return "usage: klua --debug <script.lua> [args...] | " +
            "klua --compile <script.lua> <output.kluac> | klua --dap"
    }

    private fun parseCompile(args: Array<String>): ToolsCliInvocation.Compile {
        require(args.size == 3) { usage() }
        return ToolsCliInvocation.Compile(BytecodeCompileInvocation(source = args[1], output = args[2]))
    }
}

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
        readBytes: (String) -> ByteArray = defaultReadBytes,
        writeBytes: (String, ByteArray) -> Unit = defaultWriteBytes,
    ): Int {
        val invocation = try {
            ToolsCliInvocationParser.parse(args)
        } catch (error: IllegalArgumentException) {
            writeLine(error.message ?: "invalid arguments")
            return 2
        }
        if (invocation is ToolsCliInvocation.Compile) {
            return compileBytecode(invocation.invocation, luaFactory, readSource, writeBytes, writeLine)
        }
        val debugInvocation = (invocation as ToolsCliInvocation.Debug).invocation
        val runner = DebugCliRunner(
            session = debugInvocation.session,
            luaFactory = luaFactory,
            readSource = readSource,
            readBytecode = readBytes,
        )
        writeLine("debugging ${debugInvocation.session.program}")

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
        if (args.contentEquals(arrayOf("--dap"))) {
            val exitCode = DapStdioHost().run(
                input = System.`in`,
                output = System.out,
                reportError = System.err::println,
            )
            if (exitCode != 0) {
                kotlin.system.exitProcess(exitCode)
            }
            return
        }
        val exitCode = run(
            args = args,
            readLine = ::readlnOrNull,
            writeLine = ::println,
            readSource = defaultReadSource,
            readBytes = defaultReadBytes,
            writeBytes = defaultWriteBytes,
        )
        if (exitCode != 0) {
            kotlin.system.exitProcess(exitCode)
        }
    }

    private fun compileBytecode(
        invocation: BytecodeCompileInvocation,
        luaFactory: () -> Lua,
        readSource: (String) -> String,
        writeBytes: (String, ByteArray) -> Unit,
        writeLine: (String) -> Unit,
    ): Int {
        return try {
            val source = readSource(invocation.source)
            val bytecode = luaFactory().compileBytecode(source, invocation.source)
            writeBytes(invocation.output, bytecode)
            writeLine("compiled ${invocation.source} -> ${invocation.output}")
            0
        } catch (error: LuaException) {
            writeLine("error: ${error.message ?: error::class.java.simpleName}")
            1
        } catch (error: Exception) {
            writeLine("error: ${error.message ?: error::class.java.simpleName}")
            1
        }
    }
}

private val defaultReadSource: (String) -> String = { program ->
    Files.readString(Path.of(program))
}

private val defaultReadBytes: (String) -> ByteArray = { program ->
    Files.readAllBytes(Path.of(program))
}

private val defaultWriteBytes: (String, ByteArray) -> Unit = { output, bytes ->
    Files.write(Path.of(output), bytes)
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
