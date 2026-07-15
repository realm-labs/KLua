package io.github.realmlabs.klua.core.value

internal class LuaTableKeyException(
    message: String,
) : RuntimeException(message)

internal class LuaMetatableException(
    message: String,
) : RuntimeException(message)

internal class LuaTable(expectedSize: Int = 0) : LuaValue {
    init {
        require(expectedSize >= 0) { "expected size must be non-negative" }
    }

    private val values: MutableMap<LuaValue, LuaValue> = if (expectedSize == 0) {
        mutableMapOf()
    } else {
        LinkedHashMap(hashMapCapacity(expectedSize))
    }
    var metatable: LuaTable? = null

    fun get(key: LuaValue): LuaValue {
        val canonicalKey = canonicalKey(key)
        val value = values[canonicalKey]
        if (value != null) {
            return value
        }
        val index = metatable?.rawGet(INDEX_KEY)
        return if (index is LuaTable) {
            index.get(canonicalKey, mutableSetOf(this))
        } else {
            LuaNil
        }
    }

    fun set(key: LuaValue, value: LuaValue) {
        val canonicalKey = canonicalKey(key)
        if (values.containsKey(canonicalKey)) {
            rawSet(canonicalKey, value)
            return
        }
        val newIndex = metatable?.rawGet(NEW_INDEX_KEY)
        if (newIndex is LuaTable) {
            newIndex.set(canonicalKey, value, mutableSetOf(this))
        } else {
            rawSet(canonicalKey, value)
        }
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

    fun rawReplace(entries: Iterable<Pair<LuaValue, LuaValue>>) {
        values.clear()
        for ((key, value) in entries) {
            rawSet(key, value)
        }
    }

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

private fun hashMapCapacity(expectedSize: Int): Int {
    return ((expectedSize.toLong() * 4L + 2L) / 3L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
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
