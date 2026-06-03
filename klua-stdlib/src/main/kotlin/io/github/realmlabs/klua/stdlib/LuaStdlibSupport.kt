package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import io.github.realmlabs.klua.api.LuaStatus

internal fun installLuaSource(state: LuaState, source: String, chunkName: String) {
    val loadStatus = state.load(source, chunkName)
    if (loadStatus != LuaStatus.OK) {
        throw LuaRuntimeException(state.toString(-1) ?: "failed to load $chunkName")
    }
    val callStatus = state.pcall(0, 0)
    if (callStatus != LuaStatus.OK) {
        throw LuaRuntimeException(state.toString(-1) ?: "failed to run $chunkName")
    }
}
