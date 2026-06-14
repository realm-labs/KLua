package io.github.realmlabs.klua.stdlib

internal fun luaShortSourceName(source: String): String {
    return when {
        source.startsWith("[Kotlin]") || source == "[C]" -> source
        source.startsWith("=") -> source.drop(1).take(LUA_IDSIZE - 1)
        source.startsWith("@") -> {
            val file = source.drop(1)
            if (source.length <= LUA_IDSIZE) {
                file
            } else {
                LUA_SOURCE_RETS + file.takeLast(LUA_IDSIZE - LUA_SOURCE_RETS.length)
            }
        }
        else -> {
            val firstLine = source.substringBefore('\n')
            val reserved = LUA_SOURCE_PREFIX.length + LUA_SOURCE_RETS.length + LUA_SOURCE_SUFFIX.length + 1
            val available = LUA_IDSIZE - reserved
            val body = if (source.length < available && '\n' !in source) {
                source
            } else {
                firstLine.take(available) + LUA_SOURCE_RETS
            }
            LUA_SOURCE_PREFIX + body + LUA_SOURCE_SUFFIX
        }
    }
}

private const val LUA_IDSIZE = 60
private const val LUA_SOURCE_RETS = "..."
private const val LUA_SOURCE_PREFIX = "[string \""
private const val LUA_SOURCE_SUFFIX = "\"]"
