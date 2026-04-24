package ru.lazyhat.compukterkraft.core.computer.vm.api

/**
 * Fixed-capacity byte ring buffer. Appended bytes overwrite the oldest bytes
 * once capacity is exhausted. Thread-safe via intrinsic lock.
 */
class ScrollbackRing(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    private val buffer = ByteArray(capacity)
    private var writePos = 0
    private var _size = 0

    val size: Int
        @Synchronized get() = _size

    @Synchronized
    fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        if (chunk.size >= capacity) {
            // Only the tail fits.
            val src = chunk.size - capacity
            System.arraycopy(chunk, src, buffer, 0, capacity)
            writePos = 0
            _size = capacity
            return
        }
        val first = minOf(chunk.size, capacity - writePos)
        System.arraycopy(chunk, 0, buffer, writePos, first)
        val remaining = chunk.size - first
        if (remaining > 0) {
            System.arraycopy(chunk, first, buffer, 0, remaining)
        }
        writePos = (writePos + chunk.size) % capacity
        _size = minOf(_size + chunk.size, capacity)
    }

    @Synchronized
    fun snapshotBytes(): ByteArray {
        if (_size == 0) return ByteArray(0)
        val out = ByteArray(_size)
        if (_size < capacity) {
            // Content is contiguous from 0 to writePos.
            System.arraycopy(buffer, 0, out, 0, _size)
        } else {
            // Oldest byte is at writePos.
            val tail = capacity - writePos
            System.arraycopy(buffer, writePos, out, 0, tail)
            if (writePos > 0) {
                System.arraycopy(buffer, 0, out, tail, writePos)
            }
        }
        return out
    }
}
