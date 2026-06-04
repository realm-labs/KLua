package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaException
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState

internal object LuaCoroutineLibrary {
    fun open(state: LuaState): LuaState {
        state.newTable()
        setFunctionField(state, "create", ::coroutineCreate)
        setFunctionField(state, "resume", ::coroutineResume)
        setFunctionField(state, "status", ::coroutineStatus)
        state.setGlobal("coroutine")
        return state
    }

    private fun coroutineCreate(context: LuaCallContext): LuaReturn {
        if (context.typeName(1) != "function") {
            throw LuaRuntimeException("bad argument #1 to 'coroutine.create' (function expected)")
        }
        val function = context.get(1) as? LuaFunction
            ?: throw LuaRuntimeException("bad argument #1 to 'coroutine.create' (function expected)")
        return LuaReturn.of(LuaCoroutine(function))
    }

    private fun coroutineResume(context: LuaCallContext): LuaReturn {
        val coroutine = requiredCoroutine(context, 1, "coroutine.resume")
        if (coroutine.status == CoroutineStatus.DEAD) {
            return LuaReturn.of(false, "cannot resume dead coroutine")
        }
        if (coroutine.status == CoroutineStatus.RUNNING) {
            return LuaReturn.of(false, "cannot resume non-suspended coroutine")
        }

        coroutine.status = CoroutineStatus.RUNNING
        return try {
            val arguments = (2..context.argumentCount).map { index -> argumentValue(context, index) }
            val result = context.call(coroutine.function, arguments)
            coroutine.status = CoroutineStatus.DEAD
            LuaReturn.ofValues(listOf(true) + result.values)
        } catch (exception: LuaException) {
            coroutine.status = CoroutineStatus.DEAD
            LuaReturn.of(false, exception.message ?: exception::class.java.simpleName)
        } catch (exception: RuntimeException) {
            coroutine.status = CoroutineStatus.DEAD
            LuaReturn.of(false, exception.message ?: exception::class.java.simpleName)
        }
    }

    private fun coroutineStatus(context: LuaCallContext): LuaReturn {
        val coroutine = requiredCoroutine(context, 1, "coroutine.status")
        return LuaReturn.of(
            when (coroutine.status) {
                CoroutineStatus.SUSPENDED -> "suspended"
                CoroutineStatus.RUNNING -> "running"
                CoroutineStatus.DEAD -> "dead"
            },
        )
    }

    private fun requiredCoroutine(
        context: LuaCallContext,
        index: Int,
        functionName: String,
    ): LuaCoroutine {
        return context.toUserData(index, LuaCoroutine::class.java)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (thread expected)")
    }

    private fun argumentValue(context: LuaCallContext, index: Int): Any? {
        return if (context.typeName(index) == "table") {
            context.getTable(index)
        } else {
            context.get(index)
        }
    }

    private fun setFunctionField(state: LuaState, name: String, function: (LuaCallContext) -> LuaReturn) {
        state.pushFunction(function)
        state.setField(-2, name)
    }

    private class LuaCoroutine(
        val function: LuaFunction,
        var status: CoroutineStatus = CoroutineStatus.SUSPENDED,
    )

    private enum class CoroutineStatus {
        SUSPENDED,
        RUNNING,
        DEAD,
    }
}
