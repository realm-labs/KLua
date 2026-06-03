package io.github.realmlabs.klua.core.value

internal class LuaTableKeyException(
    message: String,
) : RuntimeException(message)

internal class LuaMetatableException(
    message: String,
) : RuntimeException(message)

internal class LuaTable : LuaValue {
    private val values = mutableMapOf<LuaValue, LuaValue>()
    var metatable: LuaTable? = null

    fun get(key: LuaValue): LuaValue {
        val canonicalKey = canonicalKey(key)
        return get(canonicalKey, mutableSetOf())
    }

    fun set(key: LuaValue, value: LuaValue) {
        val canonicalKey = canonicalKey(key)
        set(canonicalKey, value, mutableSetOf())
    }

    fun rawGet(key: LuaValue): LuaValue {
        return values[canonicalKey(key)] ?: LuaNil
    }

    fun metatableRawGet(key: LuaString): LuaValue {
        return metatable?.rawGet(key) ?: LuaNil
    }

    fun rawSet(key: LuaValue, value: LuaValue) {
        val canonicalKey = canonicalKey(key)
        if (value == LuaNil) {
            values.remove(canonicalKey)
        } else {
            values[canonicalKey] = value
        }
    }

    fun rawEntries(): Map<LuaValue, LuaValue> = values.toMap()

    fun rawLength(): Long {
        var length = 0L
        while (length < Long.MAX_VALUE && rawGet(LuaInteger(length + 1L)) != LuaNil) {
            length++
        }
        return length
    }

    private fun canonicalKey(key: LuaValue): LuaValue {
        return when {
            key == LuaNil -> throw LuaTableKeyException("table index is nil")
            key is LuaFloat && key.value.isNaN() -> throw LuaTableKeyException("table index is NaN")
            key is LuaFloat && key.value.isIntegralLong() -> LuaInteger(key.value.toLong())
            else -> key
        }
    }

    private fun get(canonicalKey: LuaValue, visited: MutableSet<LuaTable>): LuaValue {
        val value = values[canonicalKey]
        if (value != null) {
            return value
        }

        if (!visited.add(this)) {
            throw LuaMetatableException("cycle in __index chain")
        }

        val index = metatable?.rawGet(INDEX_KEY)
        return if (index is LuaTable) {
            index.get(canonicalKey, visited)
        } else {
            LuaNil
        }
    }

    private fun set(canonicalKey: LuaValue, value: LuaValue, visited: MutableSet<LuaTable>) {
        if (values.containsKey(canonicalKey)) {
            rawSet(canonicalKey, value)
            return
        }

        if (!visited.add(this)) {
            throw LuaMetatableException("cycle in __newindex chain")
        }

        val newIndex = metatable?.rawGet(NEW_INDEX_KEY)
        if (newIndex is LuaTable) {
            newIndex.set(canonicalKey, value, visited)
        } else {
            rawSet(canonicalKey, value)
        }
    }
}

private const val LONG_MAX_EXCLUSIVE = 9223372036854775808.0
private val INDEX_KEY = LuaString("__index")
private val NEW_INDEX_KEY = LuaString("__newindex")

private fun Double.isIntegralLong(): Boolean {
    return java.lang.Double.isFinite(this) &&
        this % 1.0 == 0.0 &&
        this >= Long.MIN_VALUE.toDouble() &&
        this < LONG_MAX_EXCLUSIVE
}
