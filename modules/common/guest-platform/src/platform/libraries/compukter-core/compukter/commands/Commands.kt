/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.commands

/** An immutable description. Only batch() submits it; reusing a description executes it again. */
public sealed interface Command<out T> {
    public companion object {
        public fun unit(binding: String, arguments: List<CommandArgument>): Command<Unit> {
            return createCommand(binding, arguments) { it.unit() }
        }
    }
}

/** SDK-generated factories provide a decoder matching the native operation's result. */
public fun <T> createCommand(binding: String, arguments: List<CommandArgument>, decode: (CommandReply) -> T): Command<T> {
    return CapturedCommand<T>(encodeCommand(binding, arguments), decode)
}

private interface EncodedCommand {
    fun encoded(): ByteArray
    fun result(input: CommandReply): CommandResult<Any?>
}

private class CapturedCommand<T>(
    private val packet: ByteArray,
    private val decode: (CommandReply) -> T,
) : Command<T>, EncodedCommand {
    public override fun encoded(): ByteArray = packet

    public override fun result(input: CommandReply): CommandResult<T> {
        val status = input.byte()
        if (status in 1..5) {
            val detail = input.text()
            if (!(detail.length <= 48 && input.finished())) throw IllegalStateException("Invalid command failure")
            return CompletedCommand<T>(null, CommandException(CommandFailureKind.entries[status - 1], detail))
        }
        if (!(status == 0)) throw IllegalStateException("Invalid command result status")
        return try {
            val value: T? = decode(input)
            if (!(input.finished())) throw IllegalStateException("Trailing command result")
            CompletedCommand<T>(value, null)
        } catch (failure: Exception) {
            CompletedCommand<T>(null, CommandException(CommandFailureKind.OTHER, "Invalid command result"))
        }
    }
}

private fun encodeCommand(binding: String, arguments: List<CommandArgument>): ByteArray {
    require(binding.length in 1..256 && arguments.size <= 32)
    var size = 2 + binding.length * 2 + 1
    for (argument in arguments) size += argument.encodedSize()
    val output = CommandWriter(size)
    output.text(binding)
    output.byte(arguments.size)
    for (argument in arguments) argument.writeTo(output)
    return output.finish()
}

/** A value snapshot for an SDK command binding. Byte arrays are copied at construction. */
public class CommandArgument private constructor(private val packet: ByteArray) {
    internal fun writeTo(output: CommandWriter) { output.raw(packet) }
    internal fun encodedSize(): Int = packet.size

    public companion object {
        private fun text(kind: Int, value: String): CommandArgument {
            if (value.length > 2048) throw IllegalArgumentException("Command text exceeds packet limit")
            val output = CommandWriter(3 + value.length * 2)
            output.byte(kind)
            output.text(value)
            return CommandArgument(output.finish())
        }
        public fun int(value: Int): CommandArgument = text(1, value.toString())
        public fun long(value: Long): CommandArgument = text(2, value.toString())
        public fun float(value: Float): CommandArgument = text(3, value.toString())
        public fun double(value: Double): CommandArgument = text(4, value.toString())
        public fun boolean(value: Boolean): CommandArgument = text(5, value.toString())
        public fun char(value: Char): CommandArgument = text(6, value.toString())
        public fun string(value: String): CommandArgument = text(7, value)
        public fun bytes(value: ByteArray): CommandArgument {
            require(value.size <= 4093) { "Command packet exceeds 4096 bytes" }
            val output = CommandWriter(3 + value.size)
            output.byte(8)
            output.short(value.size)
            output.raw(value)
            return CommandArgument(output.finish())
        }
    }
}

public enum class CommandFailureKind { END_OF_FILE, UNAVAILABLE, INPUT_OUTPUT, CANCELLED, OTHER }

/** The bounded failure of one command; other positions in that submission still execute. */
public class CommandException(public val kind: CommandFailureKind, message: String) : IllegalStateException(message)

public sealed interface CommandResult<out T> {
    public val isSuccess: Boolean
    public val isFailure: Boolean
    public fun exceptionOrNull(): CommandException?
    public fun getOrNull(): T?
    /** Repeated calls return the same result or throw the same stored exception object. */
    public fun getOrThrow(): T
}

private class CompletedCommand<T>(private val value: T?, private val failure: CommandException?) : CommandResult<T> {
    public override val isSuccess: Boolean get() = failure == null
    public override val isFailure: Boolean get() = failure != null
    public override fun exceptionOrNull(): CommandException? = failure
    public override fun getOrNull(): T? = value

    @Suppress("UNCHECKED_CAST")
    public override fun getOrThrow(): T {
        val caught = failure
        if (caught != null) throw caught
        return value as T
    }
}

/** Immutable results of one submission, in execution order. Commands are matched by object identity. */
public class BatchResults<T> internal constructor(
    private val commands: List<Command<T>>,
    private val results: List<CommandResult<T>>,
) : Iterable<CommandResult<T>> {
    public val size: Int get() = results.size
    public fun isEmpty(): Boolean = results.isEmpty()
    public override fun iterator(): Iterator<CommandResult<T>> = results.iterator()
    public operator fun get(index: Int): CommandResult<T> = results[index]

    /** Requires exactly one occurrence; use getAll for repeated command objects. */
    @Suppress("UNCHECKED_CAST")
    public operator fun <R> get(command: Command<R>): CommandResult<R> {
        var found = -1
        for (index in 0 until commands.size) {
            if (commands[index] === command) {
                if (!(found == -1)) throw IllegalArgumentException("Command occurs more than once; use getAll")
                found = index
            }
        }
        if (!(found != -1)) throw IllegalArgumentException("Command was not part of this batch")
        return results[found] as CommandResult<R>
    }

    public fun <R> getAll(command: Command<R>): List<CommandResult<R>> {
        val matching = ArrayList<CommandResult<R>>()
        for (index in 0 until commands.size) {
            if (commands[index] === command) {
                @Suppress("UNCHECKED_CAST")
                val result = results[index] as CommandResult<R>
                matching.add(result)
            }
        }
        return matching
    }
}

/**
 * Submits immediate calls in list order in one server-thread delivery, without rollback or isolation.
 * At most 32 calls and 4096 encoded request/response bytes. Individual errors do not cancel later calls.
 * An empty list does no host work. Use async { batch(commands) } for a deferred result.
 */
@Suppress("UNCHECKED_CAST")
public fun <T> batch(commands: List<Command<T>>): BatchResults<T> {
    require(commands.size <= 32)
    val captured = ArrayList<Command<T>>(commands.size)
    for (command in commands) captured.add(command)
    if (captured.isEmpty()) return BatchResults(captured, emptyList())
    var size = 2
    for (command in captured) size += (command as EncodedCommand).encoded().size
    val output = CommandWriter(size)
    output.byte(2)
    output.byte(captured.size)
    for (command in captured) output.raw((command as EncodedCommand).encoded())
    val response = CommandBindings.submit(output.finish())
    val input = CommandReply(response, 0, response.size)
    if (!(input.byte() == 2 && input.byte() == captured.size)) throw IllegalStateException("Invalid command batch response")
    val results = ArrayList<CommandResult<T>>()
    for (command in captured) results.add((command as EncodedCommand).result(input.frame()) as CommandResult<T>)
    if (!(input.finished())) throw IllegalStateException("Trailing command batch response")
    return BatchResults(captured, results)
}

internal class CommandWriter(size: Int) {
    private val bytes = ByteArray(size.also { require(it in 0..4096) { "Command packet exceeds 4096 bytes" } })
    private var offset = 0
    fun byte(value: Int) {
        if (offset >= bytes.size) throw IllegalArgumentException("Command packet exceeds 4096 bytes")
        bytes[offset++] = value.toByte()
    }
    fun short(value: Int) { require(value in 0..65535); byte(value); byte(value ushr 8) }
    fun text(value: String) {
        if (value.length > 2048) throw IllegalArgumentException("Command text exceeds packet limit")
        if (2 + value.length * 2 > bytes.size - offset) throw IllegalArgumentException("Command packet exceeds 4096 bytes")
        bytes[offset++] = value.length.toByte()
        bytes[offset++] = (value.length ushr 8).toByte()
        for (index in 0 until value.length) {
            val code = value[index].code
            bytes[offset++] = code.toByte()
            bytes[offset++] = (code ushr 8).toByte()
        }
    }
    fun raw(value: ByteArray) {
        if (value.size > bytes.size - offset) throw IllegalArgumentException("Command packet exceeds 4096 bytes")
        value.copyInto(bytes, offset)
        offset += value.size
    }
    fun finish(): ByteArray = if (offset == bytes.size) bytes else bytes.copyOf(offset)
}

/** Bounded result reader used by SDK-generated decoders, including nested records. */
public class CommandReply internal constructor(private val bytes: ByteArray, private var offset: Int, private val limit: Int) {
    internal fun byte(): Int { if (!(offset < limit)) throw IllegalStateException("Truncated command result"); return bytes[offset++].toInt() and 255 }
    private fun short(): Int = byte() or (byte() shl 8)
    internal fun text(): String {
        val size = short()
        if (!(size <= (limit - offset) / 2)) throw IllegalStateException("Truncated command text")
        val chars = CharArray(size)
        for (index in 0 until size) chars[index] = short().toChar()
        return chars.concatToString(0, chars.size)
    }
    private fun expect(tag: Int) { if (!(byte() == tag)) throw IllegalStateException("Unexpected command value type") }
    public fun unit(): Unit { expect(0) }
    private fun intBits(): Int {
        var value = 0
        for (index in 0 until 4) value = value or (byte() shl (index * 8))
        return value
    }
    private fun longBits(): Long {
        var value = 0L
        for (index in 0 until 8) value = value or (byte().toLong() shl (index * 8))
        return value
    }
    public fun int(): Int { expect(1); return intBits() }
    public fun long(): Long { expect(2); return longBits() }
    public fun float(): Float { expect(3); return floating(intBits().toLong(), 23, 8, 127).toFloat() }
    public fun double(): Double { expect(4); return floating(longBits(), 52, 11, 1023) }
    public fun boolean(): Boolean {
        expect(5)
        return when (byte()) { 0 -> false; 1 -> true; else -> throw IllegalStateException("Invalid command Boolean") }
    }
    public fun char(): Char { expect(6); return short().toChar() }

    // Reconstruct IEEE values with exact powers of two; Guest Kotlin has no bit-reinterpretation primitive.
    private fun floating(bits: Long, fractionBits: Int, exponentBits: Int, bias: Int): Double {
        val denominator = 1L shl fractionBits
        val fraction = bits and (denominator - 1L)
        val maximumExponent = (1 shl exponentBits) - 1
        val encodedExponent = ((bits ushr fractionBits) and maximumExponent.toLong()).toInt()
        val negative = ((bits ushr (fractionBits + exponentBits)) and 1L) != 0L
        if (encodedExponent == maximumExponent) {
            if (fraction != 0L) return Double.NaN
            return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        }
        var value = fraction.toDouble() / denominator.toDouble()
        if (encodedExponent != 0) value += 1.0
        var exponent = (if (encodedExponent == 0) 1 else encodedExponent) - bias
        var factor = if (exponent < 0) 0.5 else 2.0
        if (exponent < 0) exponent = -exponent
        while (exponent != 0) {
            if ((exponent and 1) != 0) value *= factor
            exponent = exponent ushr 1
            if (exponent != 0) factor *= factor
        }
        return if (negative) -value else value
    }
    public fun string(): String { expect(7); return text() }
    public fun bytes(): ByteArray { expect(8); return rawBytes() }
    public fun record(fields: Int): Unit {
        expect(9)
        if (!(short() == fields)) throw IllegalStateException("Unexpected command record shape")
    }
    internal fun frame(): CommandReply {
        val size = short()
        if (!(size <= limit - offset)) throw IllegalStateException("Truncated command bytes")
        val frame = CommandReply(bytes, offset, offset + size)
        offset += size
        return frame
    }
    private fun rawBytes(): ByteArray {
        val size = short()
        if (!(size <= limit - offset)) throw IllegalStateException("Truncated command bytes")
        val result = ByteArray(size)
        bytes.copyInto(result, 0, offset, offset + size)
        offset += size
        return result
    }
    internal fun finished(): Boolean = offset == limit
}

private object CommandBindings {
    external fun submit(packet: ByteArray): ByteArray
}
