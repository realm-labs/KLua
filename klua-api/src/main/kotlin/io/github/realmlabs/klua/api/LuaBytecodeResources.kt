package io.github.realmlabs.klua.api

@JvmSynthetic
internal fun defaultBytecodeResourceClassLoader(): ClassLoader {
    return Thread.currentThread().contextClassLoader ?: Lua::class.java.classLoader
}

@JvmSynthetic
internal fun readBytecodeResource(resourceName: String, classLoader: ClassLoader): ByteArray {
    require(resourceName.isNotBlank()) { "resourceName must not be blank" }
    val normalizedName = resourceName.removePrefix("/")
    return classLoader.getResourceAsStream(normalizedName)?.use { stream -> stream.readBytes() }
        ?: throw LuaSyntaxException("KLua bytecode resource not found: $resourceName")
}
