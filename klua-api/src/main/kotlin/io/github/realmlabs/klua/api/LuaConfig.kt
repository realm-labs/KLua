package io.github.realmlabs.klua.api

import io.github.realmlabs.klua.core.KLuaCoreControlException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.function.Supplier

data class LuaConfig @JvmOverloads constructor(
    val debugEnabled: Boolean = true,
    val standardInput: Supplier<String> = Supplier {
        String(System.`in`.readAllBytes(), StandardCharsets.UTF_8)
    },
    val instructionLimit: Long = 0,
    val standardLibraries: Set<LuaStandardLibrary> = LuaStandardLibrary.all(),
    val unsafeStandardLibraryAccessEnabled: Boolean = true,
    val packagePathEnvironmentEnabled: Boolean = true,
    val exitHandler: LuaExitHandler = LuaExitHandler { _, _ -> },
) {
    init {
        require(instructionLimit >= 0) { "instructionLimit must be non-negative" }
    }

    internal fun snapshot(): LuaConfig {
        return copy(standardLibraries = Collections.unmodifiableSet(standardLibraries.toSet()))
    }

    companion object {
        const val DEFAULT_PRODUCTION_INSTRUCTION_LIMIT: Long = 1_000_000

        @JvmStatic
        @JvmOverloads
        fun production(
            instructionLimit: Long = DEFAULT_PRODUCTION_INSTRUCTION_LIMIT,
        ): LuaConfig {
            return LuaConfig(
                debugEnabled = false,
                instructionLimit = instructionLimit,
                standardLibraries = LuaStandardLibrary.safe(),
                unsafeStandardLibraryAccessEnabled = false,
                packagePathEnvironmentEnabled = false,
            )
        }
    }
}

fun interface LuaExitHandler {
    fun exit(status: Int, closeState: Boolean)
}

class LuaExitException(
    val status: Int,
    val closeState: Boolean,
) : KLuaCoreControlException("Lua requested process exit with status $status")
