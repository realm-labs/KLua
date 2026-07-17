package io.github.realmlabs.klua.tools

import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.dap.DapCommandRequest
import io.github.realmlabs.klua.dap.DapCommandResponse
import io.github.realmlabs.klua.dap.DapCommandSession
import io.github.realmlabs.klua.dap.DapConfigurationDoneResponse
import io.github.realmlabs.klua.dap.DapDisconnectResponse
import io.github.realmlabs.klua.dap.DapLaunchRequest
import io.github.realmlabs.klua.dap.DapMessageConnection
import io.github.realmlabs.klua.dap.DapPauseRequest
import io.github.realmlabs.klua.dap.DapSessionEvent
import io.github.realmlabs.klua.dap.DapWireSession
import io.github.realmlabs.klua.dap.LiveDapSession
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

internal class DapStdioHost(
    private val session: DapLaunchSession = DapLaunchSession(),
) {
    fun run(input: InputStream, output: OutputStream, reportError: (String) -> Unit): Int {
        val connection = DapMessageConnection(DapWireSession(session))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            while (!session.isDisconnected) {
                val count = input.read(buffer)
                if (count < 0) return 0
                if (count == 0) continue
                connection.feed(buffer.copyOf(count)).forEach { frame -> output.write(frame) }
                output.flush()
            }
            0
        } catch (error: Exception) {
            reportError("DAP host error: ${error.message ?: error::class.java.simpleName}")
            1
        }
    }
}

internal class DapLaunchSession(
    private val delegate: LiveDapSession = LiveDapSession(),
    private val luaFactory: () -> Lua = { Lua.create() },
    private val readSource: (Path) -> String = Files::readString,
    private val readBytes: (Path) -> ByteArray = Files::readAllBytes,
) : DapCommandSession {
    private var launch: LaunchState? = null
    private var started = false

    var isDisconnected: Boolean = false
        private set

    override fun handle(request: DapCommandRequest): DapCommandResponse {
        return when (request.command) {
            "attach" -> throw IllegalArgumentException("the standalone KLua adapter supports launch requests only")
            "launch" -> launch(request)
            "configurationDone" -> configurationDone(request)
            "disconnect" -> {
                isDisconnected = true
                delegate.handle(request).let { response ->
                    check(response.body is DapDisconnectResponse)
                    response
                }
            }
            else -> delegate.handle(request)
        }
    }

    override fun drainEvents(): List<DapSessionEvent> = delegate.drainEvents()

    private fun launch(request: DapCommandRequest): DapCommandResponse {
        require(launch == null) { "a Lua program is already registered in this adapter process" }
        val arguments = request.arguments as? DapLaunchRequest
            ?: throw IllegalArgumentException("command launch requires DapLaunchRequest arguments")
        val program = resolveProgram(arguments)
        val function = try {
            val lua = luaFactory()
            if (program.toString().endsWith(".kluac", ignoreCase = true)) {
                lua.loadBytecode(readBytes(program)).asCoroutineFunction()
            } else {
                lua.load(readSource(program), program.toString()).asCoroutineFunction()
            }
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "cannot launch ${arguments.program}: ${error.message ?: error::class.java.simpleName}",
                error,
            )
        }
        val thread = delegate.register(function, program.fileName?.toString() ?: "Lua Program")
        launch = LaunchState(thread.id, arguments.args, arguments.stopOnEntry)
        return delegate.handle(request)
    }

    private fun configurationDone(request: DapCommandRequest): DapCommandResponse {
        val current = requireNotNull(launch) { "configurationDone requires a successful launch request" }
        require(!started) { "the launched Lua program has already started" }
        val response = delegate.handle(request)
        check(response.body is DapConfigurationDoneResponse)
        if (current.stopOnEntry) {
            delegate.handle(DapCommandRequest("pause", DapPauseRequest(current.threadId)))
        }
        delegate.start(current.threadId, current.arguments)
        started = true
        return response
    }

    private fun resolveProgram(request: DapLaunchRequest): Path {
        val requested = Path.of(request.program)
        if (requested.isAbsolute) return requested.normalize()
        val base = (request.cwd?.let(Path::of) ?: Path.of("")).toAbsolutePath()
        return base.resolve(requested).normalize()
    }
}

private data class LaunchState(
    val threadId: Int,
    val arguments: List<String>,
    val stopOnEntry: Boolean,
)
