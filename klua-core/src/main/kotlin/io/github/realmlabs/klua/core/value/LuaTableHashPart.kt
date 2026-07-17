package io.github.realmlabs.klua.core.value

/** Open-addressed hash storage for keys that do not currently live in a table's array part. */
internal class LuaTableHashPart private constructor(
    val capacity: Int,
) : LuaValueSlots(capacity) {
    private val keys = arrayOfNulls<LuaValue>(capacity)
    private val states = ByteArray(capacity)

    var size: Int = 0
        private set

    private var usedSlots: Int = 0

    fun value(key: LuaValue): LuaValue? {
        val index = findExisting(key)
        return if (index < 0) null else rawValue(index)
    }

    fun contains(key: LuaValue): Boolean = findExisting(key) >= 0

    fun slot(key: LuaValue): Int = findExisting(key)

    fun slotMatches(slot: Int, key: LuaValue): Boolean {
        return slot in states.indices && states[slot] == OCCUPIED && keys[slot] == key
    }

    fun copyValueTo(key: LuaValue, target: LuaValueSlots, targetIndex: Int): Boolean {
        val index = findExisting(key)
        if (index < 0) {
            return false
        }
        rawCopyTo(index, target, targetIndex)
        return true
    }

    fun copySlotTo(slot: Int, target: LuaValueSlots, targetIndex: Int) {
        requireOccupied(slot)
        rawCopyTo(slot, target, targetIndex)
    }

    fun setSlotFrom(slot: Int, source: LuaValueSlots, sourceIndex: Int) {
        requireOccupied(slot)
        source.rawCopyTo(sourceIndex, this, slot)
    }

    fun removeSlot(slot: Int) {
        requireOccupied(slot)
        states[slot] = DELETED
        rawSetNil(slot)
        size--
    }

    fun put(key: LuaValue, value: LuaValue): Boolean {
        val index = findInsertion(key)
        val inserted = states[index] != OCCUPIED
        if (inserted) {
            if (states[index] == EMPTY) {
                usedSlots++
            }
            states[index] = OCCUPIED
            keys[index] = key
            size++
        }
        rawSet(index, value)
        return inserted
    }

    fun putFrom(key: LuaValue, source: LuaValueSlots, sourceIndex: Int): Boolean {
        val index = findInsertion(key)
        val inserted = states[index] != OCCUPIED
        if (inserted) {
            if (states[index] == EMPTY) {
                usedSlots++
            }
            states[index] = OCCUPIED
            keys[index] = key
            size++
        }
        source.rawCopyTo(sourceIndex, this, index)
        return inserted
    }

    fun remove(key: LuaValue): Boolean {
        val index = findExisting(key)
        if (index < 0) {
            return false
        }
        states[index] = DELETED
        rawSetNil(index)
        size--
        return true
    }

    fun needsRehashForInsert(): Boolean = (usedSlots + 1L) * 4L > capacity.toLong() * 3L

    fun shouldCompact(): Boolean = usedSlots > size * 2 && usedSlots > MIN_CAPACITY

    fun forEachEntry(action: (LuaValue, LuaValue) -> Unit) {
        for (index in states.indices) {
            if (states[index] == OCCUPIED) {
                action(checkNotNull(keys[index]), rawValue(index))
            }
        }
    }

    fun forEachSlot(action: (LuaValue, Int) -> Unit) {
        for (index in states.indices) {
            if (states[index] == OCCUPIED) {
                action(checkNotNull(keys[index]), index)
            }
        }
    }

    private fun findExisting(key: LuaValue): Int {
        var index = mixedHash(key.hashCode()) and (capacity - 1)
        while (states[index] != EMPTY) {
            if (states[index] == OCCUPIED && keys[index] == key) {
                return index
            }
            index = (index + 1) and (capacity - 1)
        }
        return -1
    }

    private fun findInsertion(key: LuaValue): Int {
        var index = mixedHash(key.hashCode()) and (capacity - 1)
        var firstDeleted = -1
        while (states[index] != EMPTY) {
            if (states[index] == OCCUPIED && keys[index] == key) {
                return index
            }
            if (firstDeleted < 0 && states[index] == DELETED) {
                firstDeleted = index
            }
            index = (index + 1) and (capacity - 1)
        }
        return if (firstDeleted >= 0) firstDeleted else index
    }

    private fun requireOccupied(slot: Int) {
        require(slot in states.indices && states[slot] == OCCUPIED) { "invalid table hash slot: $slot" }
    }

    companion object {
        private const val EMPTY: Byte = 0
        private const val OCCUPIED: Byte = 1
        private const val DELETED: Byte = 2
        private const val MIN_CAPACITY = 4

        fun withCapacity(minimumCapacity: Int): LuaTableHashPart {
            var capacity = MIN_CAPACITY
            while (capacity < minimumCapacity && capacity <= (1 shl 29)) {
                capacity = capacity shl 1
            }
            if (capacity < minimumCapacity) {
                throw LuaTableKeyException("table overflow")
            }
            return LuaTableHashPart(capacity)
        }

        private fun mixedHash(hashCode: Int): Int {
            var value = hashCode
            value = value xor (value ushr 16)
            value *= -0x7a143595
            value = value xor (value ushr 13)
            return value
        }
    }
}
