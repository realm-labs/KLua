package io.github.realmlabs.klua.api

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
) {
    init {
        require(instructionLimit >= 0) { "instructionLimit must be non-negative" }
    }

    internal fun snapshot(): LuaConfig {
        return copy(standardLibraries = Collections.unmodifiableSet(standardLibraries.toSet()))
    }
}
