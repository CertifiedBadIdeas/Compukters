/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package create.logistics

public value class LogisticsSide internal constructor(internal val index: Int) {
    init {
        require(index in 0..5)
    }

    public fun stockTicker(): StockTicker = StockTicker(LogisticsBindings.acquireStockTicker(index))
}

public value class StockTicker internal constructor(private val handle: Int) {
    public fun snapshot(): StockSnapshot = StockSnapshot(LogisticsBindings.snapshot(handle))
}

public value class StockSnapshot internal constructor(private val handle: Int) {
    public fun size(): Int = LogisticsBindings.snapshotSize(handle)

    public fun itemId(index: Int): String = LogisticsBindings.snapshotItemId(handle, index)

    public fun displayName(index: Int): String = LogisticsBindings.snapshotDisplayName(handle, index)

    public fun count(index: Int): Int = LogisticsBindings.snapshotCount(handle, index)

    /** Returns the next exact item-ID match at or after [fromIndex], or -1 when none remains. */
    public fun findItem(itemId: String, fromIndex: Int = 0): Int = LogisticsBindings.findItem(handle, itemId, fromIndex)

    public fun request(index: Int, quantity: Int, address: String): Boolean =
        LogisticsBindings.request(handle, index, quantity, address)

    public fun close() {
        LogisticsBindings.closeSnapshot(handle)
    }
}

public object Logistics {
    public fun stockTicker(name: String): StockTicker = StockTicker(LogisticsBindings.acquireStockTickerByName(name))

    public val front: LogisticsSide
        get() = LogisticsSide(0)

    public val back: LogisticsSide
        get() = LogisticsSide(1)

    public val left: LogisticsSide
        get() = LogisticsSide(2)

    public val right: LogisticsSide
        get() = LogisticsSide(3)

    public val top: LogisticsSide
        get() = LogisticsSide(4)

    public val bottom: LogisticsSide
        get() = LogisticsSide(5)
}

private object LogisticsBindings {
    external fun acquireStockTicker(side: Int): Int

    external fun acquireStockTickerByName(name: String): Int

    external fun snapshot(handle: Int): Int

    external fun snapshotSize(handle: Int): Int

    external fun snapshotItemId(handle: Int, index: Int): String

    external fun snapshotDisplayName(handle: Int, index: Int): String

    external fun snapshotCount(handle: Int, index: Int): Int

    external fun findItem(handle: Int, itemId: String, fromIndex: Int): Int

    external fun request(handle: Int, index: Int, quantity: Int, address: String): Boolean

    external fun closeSnapshot(handle: Int)
}
