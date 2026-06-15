package io.github.realmlabs.klua.api

import java.nio.charset.StandardCharsets
import java.util.function.Supplier

data class LuaConfig(
    val debugEnabled: Boolean = true,
    val standardInput: Supplier<String> = Supplier {
        String(System.`in`.readAllBytes(), StandardCharsets.UTF_8)
    },
)
