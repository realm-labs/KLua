package io.github.realmlabs.klua.debug

import io.github.realmlabs.klua.api.LuaCoroutineFunction

public data class LiveDebugThread(
    public val id: Int,
    public val name: String,
    public val session: LiveDebugSession,
)

public class LiveDebugRuntime {
    private val threadsById = linkedMapOf<Int, LiveDebugThread>()
    private var nextThreadId: Int = 1

    public fun register(
        function: LuaCoroutineFunction,
        name: String = "Lua Coroutine",
        controller: DebugController = DebugController(),
    ): LiveDebugThread {
        require(name.isNotBlank()) { "debug thread name must not be blank" }
        val id = nextThreadId++
        return LiveDebugThread(
            id = id,
            name = name,
            session = LiveDebugSession(function, controller),
        ).also { thread -> threadsById[id] = thread }
    }

    public fun threads(): List<LiveDebugThread> = threadsById.values.toList()

    public fun thread(id: Int): LiveDebugThread? = threadsById[id]

    public fun remove(id: Int): Boolean = threadsById.remove(id) != null
}
