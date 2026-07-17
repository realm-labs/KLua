package io.github.realmlabs.klua.core.vm

import io.github.realmlabs.klua.core.bytecode.CallSiteInfo
import io.github.realmlabs.klua.core.bytecode.Prototype
import io.github.realmlabs.klua.core.value.LuaString
import io.github.realmlabs.klua.core.value.LuaTable
import io.github.realmlabs.klua.core.value.LuaValue
import java.lang.ref.WeakReference

internal sealed interface LuaInstructionCache

internal class LuaTableReadCache private constructor(
    table: LuaTable,
    shapeVersion: Long,
    private val hashSlot: Int,
    key: LuaString?,
    private val fallback: LuaTableMetamethodGuard?,
) : LuaInstructionCache {
    private val tableReference = WeakReference(table)
    private val shapeVersion = shapeVersion
    private val keyReference = key?.let(::WeakReference)

    fun directSlot(table: LuaTable): Int {
        if (fallback != null) {
            return -1
        } else if (tableReference.get() === table && table.shapeVersion == shapeVersion) {
            return hashSlot
        } else if (table.rawStringSlotMatches(hashSlot, keyReference?.get() ?: return -1)) {
            return hashSlot
        } else {
            return -1
        }
    }

    fun fallbackTarget(table: LuaTable): LuaValue? {
        return fallback?.targetIfValid(table)
    }

    companion object {
        fun direct(table: LuaTable, key: LuaString, hashSlot: Int): LuaTableReadCache {
            return LuaTableReadCache(table, table.shapeVersion, hashSlot, key, null)
        }

        fun fallback(table: LuaTable, target: LuaValue): LuaTableReadCache {
            return LuaTableReadCache(
                table,
                table.shapeVersion,
                -1,
                null,
                LuaTableMetamethodGuard(table, target),
            )
        }
    }
}

internal class LuaTableWriteCache private constructor(
    table: LuaTable,
    shapeVersion: Long,
    private val hashSlot: Int,
    key: LuaString?,
    private val fallback: LuaTableMetamethodGuard?,
) : LuaInstructionCache {
    private val tableReference = WeakReference(table)
    private val shapeVersion = shapeVersion
    private val keyReference = key?.let(::WeakReference)

    fun directSlot(table: LuaTable): Int {
        if (fallback != null) {
            return -1
        } else if (tableReference.get() === table && table.shapeVersion == shapeVersion) {
            return hashSlot
        } else if (table.rawStringSlotMatches(hashSlot, keyReference?.get() ?: return -1)) {
            return hashSlot
        } else {
            return -1
        }
    }

    fun fallbackTarget(table: LuaTable): LuaValue? = fallback?.targetIfValid(table)

    companion object {
        fun direct(table: LuaTable, key: LuaString, hashSlot: Int): LuaTableWriteCache {
            return LuaTableWriteCache(table, table.shapeVersion, hashSlot, key, null)
        }

        fun fallback(table: LuaTable, target: LuaValue): LuaTableWriteCache {
            return LuaTableWriteCache(
                table,
                table.shapeVersion,
                -1,
                null,
                LuaTableMetamethodGuard(table, target),
            )
        }
    }
}

internal class LuaCallInstructionCache(
    val callSiteInfo: CallSiteInfo?,
) : LuaInstructionCache {
    var callableTableGuard: LuaTableMetamethodGuard? = null
}

internal class LuaTableMetamethodGuard(
    table: LuaTable,
    target: LuaValue,
) {
    private val tableReference = WeakReference(table)
    private val shapeVersion = table.shapeVersion
    private val metatableReference = table.metatable?.let(::WeakReference)
    private val metatableVersion = table.metatable?.version ?: -1L
    private val targetReference = WeakReference(target)

    fun targetIfValid(table: LuaTable): LuaValue? {
        if (tableReference.get() !== table || table.shapeVersion != shapeVersion) {
            return null
        }
        val metatable = metatableReference?.get()
        if (table.metatable !== metatable || metatable != null && metatable.version != metatableVersion) {
            return null
        }
        return targetReference.get()
    }
}

internal class LuaInlineCacheStore {
    private val caches = ArrayList<PrototypeCache>()

    fun read(prototype: Prototype, pc: Int): LuaTableReadCache? = entry(prototype, pc) as? LuaTableReadCache

    fun write(prototype: Prototype, pc: Int): LuaTableWriteCache? = entry(prototype, pc) as? LuaTableWriteCache

    fun call(prototype: Prototype, pc: Int): LuaCallInstructionCache? = entry(prototype, pc) as? LuaCallInstructionCache

    fun put(prototype: Prototype, pc: Int, cache: LuaInstructionCache) {
        require(pc in prototype.code.indices) { "instruction cache pc out of range: $pc" }
        val existing = prototypeCache(prototype)
        if (existing != null) {
            existing.instructions[pc] = cache
            return
        }
        var index = caches.lastIndex
        while (index >= 0) {
            if (caches[index].prototypeReference.get() == null) {
                caches.removeAt(index)
            }
            index--
        }
        val created = PrototypeCache(prototype)
        created.instructions[pc] = cache
        caches += created
    }

    private fun entry(prototype: Prototype, pc: Int): LuaInstructionCache? {
        return if (pc in prototype.code.indices) prototypeCache(prototype)?.instructions?.get(pc) else null
    }

    private fun prototypeCache(prototype: Prototype): PrototypeCache? {
        for (index in caches.indices) {
            val candidate = caches[index]
            if (candidate.prototypeReference.get() === prototype) {
                return candidate
            }
        }
        return null
    }

    private class PrototypeCache(prototype: Prototype) {
        val prototypeReference = WeakReference(prototype)
        val instructions = arrayOfNulls<LuaInstructionCache>(prototype.code.size)
    }
}
