package io.github.realmlabs.klua.tools

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.api.LuaException
import io.github.realmlabs.klua.debug.BreakpointManager
import io.github.realmlabs.klua.debug.DebugController
import java.nio.file.Files
import java.nio.file.Path

public data class DebugCliSession(
    public val program: String,
    public val args: List<String> = emptyList(),
)

public data class DebugCliResult(
    public val success: Boolean,
    public val message: String,
    public val values: List<Any?> = emptyList(),
)

public sealed interface DebugCliCommand {
    public data class Break(
        public val source: String,
        public val line: Int,
    ) : DebugCliCommand

    public data object Run : DebugCliCommand
    public data object Continue : DebugCliCommand
    public data object Next : DebugCliCommand
    public data object Step : DebugCliCommand
    public data object Out : DebugCliCommand
    public data object Backtrace : DebugCliCommand
    public data object Locals : DebugCliCommand

    public data class Print(
        public val expression: String,
    ) : DebugCliCommand

    public data object Quit : DebugCliCommand
}

public object DebugCliCommandParser {
    public fun parse(input: String): DebugCliCommand {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "debug command must not be empty" }
        val command = trimmed.substringBefore(' ')
        val rest = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()
        return when (command) {
            "break", "b" -> parseBreak(rest)
            "run", "r" -> DebugCliCommand.Run
            "continue", "c" -> DebugCliCommand.Continue
            "next", "n" -> DebugCliCommand.Next
            "step", "s" -> DebugCliCommand.Step
            "out" -> DebugCliCommand.Out
            "bt", "backtrace" -> DebugCliCommand.Backtrace
            "locals" -> DebugCliCommand.Locals
            "print", "p" -> parsePrint(rest)
            "quit", "q" -> DebugCliCommand.Quit
            else -> throw IllegalArgumentException("unknown debug command: $command")
        }
    }

    private fun parseBreak(rest: String): DebugCliCommand.Break {
        require(rest.isNotBlank()) { "break requires <file>:<line>" }
        val source = rest.substringBeforeLast(':', missingDelimiterValue = "")
        val lineText = rest.substringAfterLast(':', missingDelimiterValue = "")
        val line = lineText.toIntOrNull()
        require(source.isNotBlank() && line != null && line > 0) { "break requires <file>:<line>" }
        return DebugCliCommand.Break(source, line)
    }

    private fun parsePrint(rest: String): DebugCliCommand.Print {
        require(rest.isNotBlank()) { "print requires an expression" }
        return DebugCliCommand.Print(rest)
    }
}

public class DebugCliRunner(
    private val session: DebugCliSession,
    private val breakpointManager: BreakpointManager = BreakpointManager(),
    private val debugController: DebugController = DebugController(breakpointManager),
    private val luaFactory: () -> Lua = { Lua.create() },
    private val readSource: (String) -> String = { program -> Files.readString(Path.of(program)) },
    private val readBytecode: (String) -> ByteArray = { program -> Files.readAllBytes(Path.of(program)) },
) {
    public fun execute(input: String): DebugCliResult {
        return execute(DebugCliCommandParser.parse(input))
    }

    public fun execute(command: DebugCliCommand): DebugCliResult {
        return when (command) {
            is DebugCliCommand.Break -> setBreakpoint(command)
            DebugCliCommand.Run -> runProgram()
            DebugCliCommand.Continue -> {
                debugController.resume()
                DebugCliResult(success = true, message = "continued")
            }
            DebugCliCommand.Next -> {
                debugController.stepOver(startDepth = 0)
                DebugCliResult(success = true, message = "next")
            }
            DebugCliCommand.Step -> {
                debugController.stepInto()
                DebugCliResult(success = true, message = "step")
            }
            DebugCliCommand.Out -> {
                debugController.stepOut(currentDepth = 1)
                DebugCliResult(success = true, message = "out")
            }
            DebugCliCommand.Backtrace -> DebugCliResult(success = false, message = "no suspended Lua frame")
            DebugCliCommand.Locals -> DebugCliResult(success = false, message = "no suspended Lua frame")
            is DebugCliCommand.Print -> evaluateExpression(command.expression)
            DebugCliCommand.Quit -> DebugCliResult(success = true, message = "quit")
        }
    }

    public fun breakpointAt(source: String, line: Int): Boolean {
        return breakpointManager.breakpointAt(source, line) != null
    }

    private fun setBreakpoint(command: DebugCliCommand.Break): DebugCliResult {
        val breakpoint = breakpointManager.setBreakpoint(command.source, command.line)
        return DebugCliResult(
            success = true,
            message = "breakpoint ${breakpoint.sourceId}:${breakpoint.line}",
        )
    }

    private fun runProgram(): DebugCliResult {
        return try {
            val lua = luaFactory()
            val chunk = if (session.program.endsWith(".kluac", ignoreCase = true)) {
                lua.loadBytecode(readBytecode(session.program))
            } else {
                lua.load(readSource(session.program), session.program)
            }
            val result = chunk.call(*session.args.toTypedArray())
            DebugCliResult(success = true, message = "completed", values = result.values)
        } catch (error: LuaException) {
            DebugCliResult(success = false, message = error.message ?: error::class.java.simpleName)
        } catch (error: Exception) {
            DebugCliResult(success = false, message = error.message ?: error::class.java.simpleName)
        }
    }

    private fun evaluateExpression(expression: String): DebugCliResult {
        return try {
            val result = luaFactory().load("return $expression", "=(debug print)").eval()
            DebugCliResult(success = true, message = "printed", values = result.values)
        } catch (error: LuaException) {
            DebugCliResult(success = false, message = error.message ?: error::class.java.simpleName)
        }
    }
}
