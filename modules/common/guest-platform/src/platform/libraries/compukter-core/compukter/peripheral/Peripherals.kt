/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.peripheral

/** A device instance. Providers describe discovery; instances perform device operations. */
public interface Peripheral

/** A side relative to the computer's front face, rather than an index in a device list. */
public value class Side private constructor(internal val index: Int) {
    public companion object {
        public val front: Side = Side(0)
        public val back: Side = Side(1)
        public val left: Side = Side(2)
        public val right: Side = Side(3)
        public val top: Side = Side(4)
        public val bottom: Side = Side(5)
    }
}

/** Typed discovery shared by device companion objects, including third-party addons. */
public interface PeripheralProvider<T : Peripheral> {
    public fun first(): T
    public fun first(predicate: (T) -> Boolean): T
    public fun firstOrNull(): T?
    public fun firstOrNull(predicate: (T) -> Boolean): T?
    public fun filter(predicate: (T) -> Boolean): List<T>
    public fun all(): List<T>
    public fun at(side: Side): T
    public fun atOrNull(side: Side): T?
    public fun named(name: String): T
    public fun namedOrNull(name: String): T?
}

/**
 * An addon supplies only its registered contract id and a typed wrapper. The base owns snapshots,
 * ordering and handle acquisition. Optional queries return null only for absence, never for a host failure.
 */
public abstract class TypedPeripheralProvider<T : Peripheral>(private val contract: String) : PeripheralProvider<T> {
    protected abstract fun wrap(handle: Int): T

    public override fun first(): T = firstOrNull() ?: throw NoSuchElementException("No peripheral satisfies the contract")

    public override fun first(predicate: (T) -> Boolean): T =
        firstOrNull(predicate) ?: throw NoSuchElementException("No peripheral matches the predicate")

    public override fun firstOrNull(): T? {
        val snapshot = PeripheralBindings.openSnapshot(contract)
        try {
            if (PeripheralBindings.snapshotSize(snapshot) == 0) return null
            return wrap(PeripheralBindings.snapshotGet(snapshot, 0))
        } finally {
            PeripheralBindings.closeSnapshot(snapshot)
        }
    }

    public override fun firstOrNull(predicate: (T) -> Boolean): T? {
        val snapshot = PeripheralBindings.openSnapshot(contract)
        try {
            val size = PeripheralBindings.snapshotSize(snapshot)
            var index = 0
            while (index < size) {
                val device = wrap(PeripheralBindings.snapshotGet(snapshot, index))
                if (predicate(device)) return device
                index += 1
            }
            return null
        } finally {
            PeripheralBindings.closeSnapshot(snapshot)
        }
    }

    public override fun filter(predicate: (T) -> Boolean): List<T> {
        val snapshot = PeripheralBindings.openSnapshot(contract)
        try {
            val size = PeripheralBindings.snapshotSize(snapshot)
            val result = ArrayList<T>()
            var index = 0
            while (index < size) {
                val device = wrap(PeripheralBindings.snapshotGet(snapshot, index))
                if (predicate(device)) result.add(device)
                index += 1
            }
            return result
        } finally {
            PeripheralBindings.closeSnapshot(snapshot)
        }
    }

    public override fun all(): List<T> {
        val snapshot = PeripheralBindings.openSnapshot(contract)
        try {
            val size = PeripheralBindings.snapshotSize(snapshot)
            val result = ArrayList<T>()
            var index = 0
            while (index < size) {
                result.add(wrap(PeripheralBindings.snapshotGet(snapshot, index)))
                index += 1
            }
            return result
        } finally {
            PeripheralBindings.closeSnapshot(snapshot)
        }
    }

    public override fun at(side: Side): T = atOrNull(side) ?: throw NoSuchElementException("No peripheral at that side")

    public override fun atOrNull(side: Side): T? {
        val handle = PeripheralBindings.at(contract, side.index)
        return if (handle == 0) null else wrap(handle)
    }

    public override fun named(name: String): T = namedOrNull(name) ?: throw NoSuchElementException("No peripheral has that name")

    public override fun namedOrNull(name: String): T? {
        val handle = PeripheralBindings.named(contract, name)
        return if (handle == 0) null else wrap(handle)
    }
}

private object PeripheralBindings {
    external fun openSnapshot(contract: String): Int
    external fun snapshotSize(snapshot: Int): Int
    external fun snapshotGet(snapshot: Int, index: Int): Int
    external fun closeSnapshot(snapshot: Int)
    external fun at(contract: String, side: Int): Int
    external fun named(contract: String, name: String): Int
}
