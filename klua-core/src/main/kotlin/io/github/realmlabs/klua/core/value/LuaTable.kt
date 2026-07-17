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

    private val expectedSizeHint = expectedSize
    private val arrayValues = LuaValueSlots(1)
    private var arraySize = 0
    private var hashValues: LuaTableHashPart? = null
    private var lengthHint = 0L

    var version: Long = 0L
        private set

    var shapeVersion: Long = 0L
        private set

    var metatable: LuaTable? = null
        set(value) {
            if (field !== value) {
                field = value
                markStructuralMutation()
            }
        }

    val rawSize: Int
        get() = arrayEntryCount() + (hashValues?.size ?: 0)

    val arrayCapacity: Int
        get() = arraySize

    val hashCapacity: Int
        get() = hashValues?.capacity ?: 0

    fun get(key: LuaValue): LuaValue {
        val canonicalKey = canonicalKey(key)
        val value = rawGetCanonical(canonicalKey)
        if (value != LuaNil) {
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
        if (rawContainsCanonical(canonicalKey)) {
            rawSetCanonical(canonicalKey, value)
            return
        }
        val newIndex = metatable?.rawGet(NEW_INDEX_KEY)
        if (newIndex is LuaTable) {
            newIndex.set(canonicalKey, value, mutableSetOf(this))
        } else {
            rawSetCanonical(canonicalKey, value)
        }
    }

    fun rawGet(key: LuaValue): LuaValue = rawGetCanonical(canonicalKey(key))

    fun rawGetInteger(key: Long): LuaValue {
        val index = arrayOffset(key)
        return if (index >= 0) arrayValues.rawValue(index) else hashValues?.value(LuaInteger(key)) ?: LuaNil
    }

    fun rawCopyTo(key: LuaValue, target: LuaValueSlots, targetIndex: Int): Boolean {
        return rawCopyCanonicalTo(canonicalKey(key), target, targetIndex)
    }

    fun rawCopyIntegerTo(key: Long, target: LuaValueSlots, targetIndex: Int): Boolean {
        val index = arrayOffset(key)
        if (index >= 0) {
            if (arrayValues.rawTagCode(index) == LuaValueTag.NIL.ordinal) {
                return false
            }
            arrayValues.rawCopyTo(index, target, targetIndex)
            return true
        }
        return hashValues?.copyValueTo(LuaInteger(key), target, targetIndex) == true
    }

    fun rawCopyStringTo(key: LuaString, target: LuaValueSlots, targetIndex: Int): Boolean {
        return hashValues?.copyValueTo(key, target, targetIndex) == true
    }

    fun metatableRawGet(key: LuaString): LuaValue = metatable?.rawGet(key) ?: LuaNil

    fun rawSet(key: LuaValue, value: LuaValue) {
        rawSetCanonical(canonicalKey(key), value)
    }

    fun rawSetInteger(key: Long, value: LuaValue) {
        rawSetCanonical(LuaInteger(key), value)
    }

    fun rawSetFrom(key: LuaValue, source: LuaValueSlots, sourceIndex: Int) {
        rawSetCanonicalFrom(canonicalKey(key), source, sourceIndex)
    }

    fun rawSetIntegerFrom(key: Long, source: LuaValueSlots, sourceIndex: Int) {
        rawSetCanonicalFrom(LuaInteger(key), source, sourceIndex)
    }

    fun rawSetStringFrom(key: LuaString, source: LuaValueSlots, sourceIndex: Int) {
        rawSetCanonicalFrom(key, source, sourceIndex)
    }

    fun rawContains(key: LuaValue): Boolean = rawContainsCanonical(canonicalKey(key))

    fun allowsRawSet(key: LuaValue): Boolean {
        val canonicalKey = canonicalKey(key)
        return rawContainsCanonical(canonicalKey) || metatableRawGet(NEW_INDEX_KEY) == LuaNil
    }

    fun allowsRawSetInteger(key: Long): Boolean {
        return rawContainsInteger(key) || metatableRawGet(NEW_INDEX_KEY) == LuaNil
    }

    fun allowsRawSetString(key: LuaString): Boolean {
        return hashValues?.contains(key) == true || metatableRawGet(NEW_INDEX_KEY) == LuaNil
    }

    fun rawEntries(): Map<LuaValue, LuaValue> {
        val entries = LinkedHashMap<LuaValue, LuaValue>(hashMapCapacity(rawSize))
        forEachRawEntry { key, value -> entries[key] = value }
        return entries
    }

    fun forEachRawEntry(action: (LuaValue, LuaValue) -> Unit) {
        for (index in 0 until arraySize) {
            if (arrayValues.rawTagCode(index) != LuaValueTag.NIL.ordinal) {
                action(LuaInteger(index.toLong() + 1L), arrayValues.rawValue(index))
            }
        }
        hashValues?.forEachEntry(action)
    }

    fun rawReplace(entries: Iterable<Pair<LuaValue, LuaValue>>) {
        clearRawStorage()
        for ((key, value) in entries) {
            rawSet(key, value)
        }
    }

    /** Returns a Lua 5.5 table border; sparse tables can have more than one valid result. */
    fun rawLength(): Long {
        if (!rawContainsInteger(1L)) {
            lengthHint = 0L
            return 0L
        }
        var lower = lengthHint.coerceAtLeast(1L)
        if (!rawContainsInteger(lower)) {
            lower = 1L
        }
        var upper = if (lower <= Long.MAX_VALUE / 2L) lower * 2L else Long.MAX_VALUE
        while (rawContainsInteger(upper)) {
            lower = upper
            if (upper == Long.MAX_VALUE) {
                lengthHint = upper
                return upper
            }
            upper = if (upper <= Long.MAX_VALUE / 2L) upper * 2L else Long.MAX_VALUE
        }
        while (upper - lower > 1L) {
            val middle = lower + ((upper - lower) ushr 1)
            if (rawContainsInteger(middle)) {
                lower = middle
            } else {
                upper = middle
            }
        }
        lengthHint = lower
        return lower
    }

    private fun canonicalKey(key: LuaValue): LuaValue {
        return when {
            key == LuaNil -> throw LuaTableKeyException("table index is nil")
            key is LuaFloat && key.value.isNaN() -> throw LuaTableKeyException("table index is NaN")
            key is LuaFloat && key.value.isIntegralLong() -> LuaInteger(key.value.toLong())
            else -> key
        }
    }

    private fun rawGetCanonical(key: LuaValue): LuaValue {
        return if (key is LuaInteger) rawGetInteger(key.value) else hashValues?.value(key) ?: LuaNil
    }

    private fun rawCopyCanonicalTo(key: LuaValue, target: LuaValueSlots, targetIndex: Int): Boolean {
        return if (key is LuaInteger) {
            rawCopyIntegerTo(key.value, target, targetIndex)
        } else {
            hashValues?.copyValueTo(key, target, targetIndex) == true
        }
    }

    private fun rawSetCanonical(key: LuaValue, value: LuaValue) {
        if (value == LuaNil) {
            removeCanonical(key)
            return
        }
        if (key is LuaInteger && placeIntegerInArray(key.value)) {
            val index = arrayOffset(key.value)
            val inserted = arrayValues.rawTagCode(index) == LuaValueTag.NIL.ordinal
            arrayValues.rawSet(index, value)
            markValueMutation(inserted)
            return
        }
        putHash(key, value)
    }

    private fun rawSetCanonicalFrom(key: LuaValue, source: LuaValueSlots, sourceIndex: Int) {
        if (source.rawTagCode(sourceIndex) == LuaValueTag.NIL.ordinal) {
            removeCanonical(key)
            return
        }
        if (key is LuaInteger && placeIntegerInArray(key.value)) {
            val index = arrayOffset(key.value)
            val inserted = arrayValues.rawTagCode(index) == LuaValueTag.NIL.ordinal
            source.rawCopyTo(sourceIndex, arrayValues, index)
            markValueMutation(inserted)
            return
        }
        putHashFrom(key, source, sourceIndex)
    }

    private fun putHash(key: LuaValue, value: LuaValue) {
        val current = hashValues
        if (current != null && !current.needsRehashForInsert()) {
            markValueMutation(current.put(key, value))
            return
        }
        if (current?.contains(key) == true) {
            current.put(key, value)
            markValueMutation(structural = false)
            return
        }
        ensureHashInsertCapacity()
        val inserted = checkNotNull(hashValues).put(key, value)
        markValueMutation(inserted)
    }

    private fun putHashFrom(key: LuaValue, source: LuaValueSlots, sourceIndex: Int) {
        val current = hashValues
        if (current != null && !current.needsRehashForInsert()) {
            markValueMutation(current.putFrom(key, source, sourceIndex))
            return
        }
        if (current?.contains(key) == true) {
            current.putFrom(key, source, sourceIndex)
            markValueMutation(structural = false)
            return
        }
        ensureHashInsertCapacity()
        val inserted = checkNotNull(hashValues).putFrom(key, source, sourceIndex)
        markValueMutation(inserted)
    }

    private fun removeCanonical(key: LuaValue) {
        val removed = if (key is LuaInteger) {
            val index = arrayOffset(key.value)
            if (index >= 0 && arrayValues.rawTagCode(index) != LuaValueTag.NIL.ordinal) {
                arrayValues.rawSetNil(index)
                true
            } else {
                hashValues?.remove(key) == true
            }
        } else {
            hashValues?.remove(key) == true
        }
        if (removed) {
            markValueMutation(structural = true)
            if (hashValues?.shouldCompact() == true) {
                rehashHash(checkNotNull(hashValues).capacity)
            }
        }
    }

    private fun placeIntegerInArray(key: Long): Boolean {
        if (arrayOffset(key) >= 0) {
            return true
        }
        val target = optimalArrayGrowth(key)
        if (target <= arraySize) {
            return false
        }
        growArray(target)
        return arrayOffset(key) >= 0
    }

    private fun optimalArrayGrowth(newKey: Long): Int {
        if (newKey <= 0L || newKey > MAX_ARRAY_INDEX.toLong()) {
            return arraySize
        }
        var candidate = 1
        while (candidate < newKey && candidate <= MAX_ARRAY_INDEX / 2) {
            candidate = candidate shl 1
        }
        if (candidate < newKey) {
            return arraySize
        }
        var integerCount = 1
        for (index in 0 until minOf(arraySize, candidate)) {
            if (arrayValues.rawTagCode(index) != LuaValueTag.NIL.ordinal) {
                integerCount++
            }
        }
        hashValues?.forEachSlot { key, _ ->
            if (key is LuaInteger && key.value in 1L..candidate.toLong() && key.value != newKey) {
                integerCount++
            }
        }
        return if (candidate.toLong() <= integerCount.toLong() * 3L) candidate else arraySize
    }

    private fun growArray(newSize: Int) {
        arrayValues.ensureSlot(newSize - 1)
        arraySize = newSize
        val oldHash = hashValues ?: return
        val retained = LuaTableHashPart.withCapacity(maxOf(MIN_HASH_CAPACITY, oldHash.size * 2))
        oldHash.forEachSlot { key, slot ->
            if (key is LuaInteger && arrayOffset(key.value) >= 0) {
                oldHash.rawCopyTo(slot, arrayValues, arrayOffset(key.value))
            } else {
                retained.putFrom(key, oldHash, slot)
            }
        }
        hashValues = if (retained.size == 0) null else retained
    }

    private fun ensureHashInsertCapacity() {
        val current = hashValues
        if (current == null) {
            val hinted = maxOf(MIN_HASH_CAPACITY, hashMapCapacity(expectedSizeHint))
            hashValues = LuaTableHashPart.withCapacity(hinted)
        } else if (current.needsRehashForInsert()) {
            if (current.capacity >= MAX_HASH_CAPACITY) {
                throw LuaTableKeyException("table overflow")
            }
            rehashHash(current.capacity * 2)
        }
    }

    private fun rehashHash(minimumCapacity: Int) {
        val oldHash = hashValues ?: return
        val replacement = LuaTableHashPart.withCapacity(maxOf(MIN_HASH_CAPACITY, minimumCapacity))
        oldHash.forEachSlot { key, slot -> replacement.putFrom(key, oldHash, slot) }
        hashValues = if (replacement.size == 0) null else replacement
    }

    private fun rawContainsCanonical(key: LuaValue): Boolean {
        return if (key is LuaInteger) rawContainsInteger(key.value) else hashValues?.contains(key) == true
    }

    private fun rawContainsInteger(key: Long): Boolean {
        val index = arrayOffset(key)
        return if (index >= 0) {
            arrayValues.rawTagCode(index) != LuaValueTag.NIL.ordinal
        } else {
            hashValues?.contains(LuaInteger(key)) == true
        }
    }

    private fun arrayOffset(key: Long): Int {
        return if (key > 0L && key <= arraySize.toLong()) key.toInt() - 1 else -1
    }

    private fun arrayEntryCount(): Int {
        var count = 0
        for (index in 0 until arraySize) {
            if (arrayValues.rawTagCode(index) != LuaValueTag.NIL.ordinal) {
                count++
            }
        }
        return count
    }

    private fun clearRawStorage() {
        if (rawSize == 0) {
            return
        }
        for (index in 0 until arraySize) {
            arrayValues.rawSetNil(index)
        }
        hashValues = null
        lengthHint = 0L
        markValueMutation(structural = true)
    }

    private fun markValueMutation(structural: Boolean) {
        version++
        if (structural) {
            shapeVersion++
        }
    }

    private fun markStructuralMutation() {
        markValueMutation(structural = true)
    }

    private fun get(canonicalKey: LuaValue, visited: MutableSet<LuaTable>): LuaValue {
        val value = rawGetCanonical(canonicalKey)
        if (value != LuaNil) {
            return value
        }
        if (!visited.add(this)) {
            throw LuaMetatableException("cycle in __index chain")
        }
        val index = metatable?.rawGet(INDEX_KEY)
        return if (index is LuaTable) index.get(canonicalKey, visited) else LuaNil
    }

    private fun set(canonicalKey: LuaValue, value: LuaValue, visited: MutableSet<LuaTable>) {
        if (rawContainsCanonical(canonicalKey)) {
            rawSetCanonical(canonicalKey, value)
            return
        }
        if (!visited.add(this)) {
            throw LuaMetatableException("cycle in __newindex chain")
        }
        val newIndex = metatable?.rawGet(NEW_INDEX_KEY)
        if (newIndex is LuaTable) newIndex.set(canonicalKey, value, visited) else rawSetCanonical(canonicalKey, value)
    }

    companion object {
        private const val MAX_ARRAY_INDEX = Int.MAX_VALUE - 8
        private const val MAX_HASH_CAPACITY = 1 shl 30
        private const val MIN_HASH_CAPACITY = 4
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
