package io.github.realmlabs.klua.tools

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.api.LuaException
import io.github.realmlabs.klua.debug.BreakpointManager
import io.github.realmlabs.klua.debug.DebugController
import io.github.realmlabs.klua.debug.DebugEvaluationResult
import io.github.realmlabs.klua.debug.DebugFrameView
import io.github.realmlabs.klua.debug.LiveDebugResult
import io.github.realmlabs.klua.debug.LiveDebugSession
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

public fun interface DebugCliFrameProvider {
    public fun frames(): List<DebugFrameView>
}

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
    private val frameProvider: DebugCliFrameProvider = DebugCliFrameProvider { emptyList() },
) {
    private var liveSession: LiveDebugSession? = null
    private var liveResult: LiveDebugResult? = null

    public fun execute(input: String): DebugCliResult {
        return execute(DebugCliCommandParser.parse(input))
    }

    public fun execute(command: DebugCliCommand): DebugCliResult {
        return when (command) {
            is DebugCliCommand.Break -> setBreakpoint(command)
            DebugCliCommand.Run -> runProgram()
            DebugCliCommand.Continue -> controlExecution { live ->
                if (liveResult is LiveDebugResult.Yielded) live.resumeYield() else live.continueExecution()
            }
            DebugCliCommand.Next -> controlExecution(LiveDebugSession::stepOver)
            DebugCliCommand.Step -> controlExecution(LiveDebugSession::stepInto)
            DebugCliCommand.Out -> controlExecution(LiveDebugSession::stepOut)
            DebugCliCommand.Backtrace -> backtrace()
            DebugCliCommand.Locals -> locals()
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
            debugController.resume()
            val live = LiveDebugSession(chunk.asCoroutineFunction(), debugController)
            liveSession = live
            renderLiveResult(live.run(session.args))
        } catch (error: LuaException) {
            liveSession = null
            liveResult = null
            DebugCliResult(success = false, message = error.message ?: error::class.java.simpleName)
        } catch (error: Exception) {
            liveSession = null
            liveResult = null
            DebugCliResult(success = false, message = error.message ?: error::class.java.simpleName)
        }
    }

    private fun backtrace(): DebugCliResult {
        val frames = suspendedFrames()
        if (frames.isEmpty()) {
            return DebugCliResult(success = false, message = "no suspended Lua frame")
        }
        return DebugCliResult(
            success = true,
            message = "backtrace",
            values = frames.map { frame -> "#${frame.level} ${frame.sourceName}:${frame.line}" },
        )
    }

    private fun locals(): DebugCliResult {
        val frame = suspendedFrames().firstOrNull()
            ?: return DebugCliResult(success = false, message = "no suspended Lua frame")
        return DebugCliResult(
            success = true,
            message = "locals",
            values = frame.locals.map { variable -> "${variable.name} = ${variable.displayValue}" },
        )
    }

    private fun evaluateExpression(expression: String): DebugCliResult {
        val live = liveSession
        if (live != null && liveResult is LiveDebugResult.Stopped) {
            return when (val result = live.evaluate(expression)) {
                is DebugEvaluationResult.Success -> DebugCliResult(
                    success = true,
                    message = "printed",
                    values = result.values,
                )
                is DebugEvaluationResult.Failure -> DebugCliResult(success = false, message = result.message)
            }
        }
        return try {
            val result = luaFactory().load("return $expression", "=(debug print)").eval()
            DebugCliResult(success = true, message = "printed", values = result.values)
        } catch (error: LuaException) {
            DebugCliResult(success = false, message = error.message ?: error::class.java.simpleName)
        }
    }

    private fun controlExecution(action: (LiveDebugSession) -> LiveDebugResult): DebugCliResult {
        val live = liveSession
            ?: return DebugCliResult(success = false, message = "program has not been run")
        return try {
            renderLiveResult(action(live))
        } catch (error: IllegalStateException) {
            DebugCliResult(success = false, message = error.message ?: "invalid debugger state")
        }
    }

    private fun renderLiveResult(result: LiveDebugResult): DebugCliResult {
        liveResult = result
        return when (result) {
            is LiveDebugResult.Stopped -> DebugCliResult(
                success = true,
                message = "stopped ${result.stop.reason.name.lowercase()} ${result.stop.sourceId}:${result.stop.line}",
            )
            is LiveDebugResult.Returned -> DebugCliResult(
                success = true,
                message = "completed",
                values = result.values,
            )
            is LiveDebugResult.Yielded -> DebugCliResult(
                success = true,
                message = "yielded",
                values = result.values,
            )
            is LiveDebugResult.RuntimeError -> DebugCliResult(
                success = false,
                message = result.error.message,
            )
        }
    }

    private fun suspendedFrames(): List<DebugFrameView> {
        val frames = when (liveResult) {
            is LiveDebugResult.Stopped,
            is LiveDebugResult.Yielded,
            -> liveSession?.frames.orEmpty()
            else -> emptyList()
        }
        return frames.ifEmpty(frameProvider::frames)
    }
}
