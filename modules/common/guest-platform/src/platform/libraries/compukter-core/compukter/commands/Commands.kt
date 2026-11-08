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
    fun result(bytes: ByteArray): CommandResult<Any?>
}

private class CapturedCommand<T>(
    private val packet: ByteArray,
    private val decode: (CommandReply) -> T,
) : Command<T>, EncodedCommand {
    public override fun encoded(): ByteArray = packet

    public override fun result(bytes: ByteArray): CommandResult<T> {
        val input = CommandReply(bytes)
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
    val output = CommandWriter()
    output.text(binding)
    output.byte(arguments.size)
    for (argument in arguments) argument.writeTo(output)
    return output.finish()
}

/** A value snapshot for an SDK command binding. Byte arrays are copied at construction. */
public class CommandArgument private constructor(private val packet: ByteArray) {
    internal fun writeTo(output: CommandWriter) { output.raw(packet) }

    public companion object {
        private fun text(kind: Int, value: String): CommandArgument {
            val output = CommandWriter()
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
            val output = CommandWriter()
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
    val output = CommandWriter()
    output.byte(2)
    output.byte(captured.size)
    for (command in captured) output.raw((command as EncodedCommand).encoded())
    val input = CommandReply(CommandBindings.submit(output.finish()))
    if (!(input.byte() == 2 && input.byte() == captured.size)) throw IllegalStateException("Invalid command batch response")
    val results = ArrayList<CommandResult<T>>()
    for (command in captured) results.add((command as EncodedCommand).result(input.frame()) as CommandResult<T>)
    if (!(input.finished())) throw IllegalStateException("Trailing command batch response")
    return BatchResults(captured, results)
}

internal class CommandWriter {
    private val bytes = ByteArray(4096)
    private var offset = 0
    fun byte(value: Int) {
        if (offset >= bytes.size) throw IllegalArgumentException("Command packet exceeds 4096 bytes")
        bytes[offset++] = value.toByte()
    }
    fun short(value: Int) { require(value in 0..65535); byte(value); byte(value ushr 8) }
    fun text(value: String) {
        if (value.length > 2048) throw IllegalArgumentException("Command text exceeds packet limit")
        short(value.length)
        for (index in 0 until value.length) short(value[index].code)
    }
    fun raw(value: ByteArray) {
        if (value.size > bytes.size - offset) throw IllegalArgumentException("Command packet exceeds 4096 bytes")
        for (item in value) bytes[offset++] = item
    }
    fun finish(): ByteArray = bytes.copyOf(offset)
}

/** Bounded result reader used by SDK-generated decoders, including nested records. */
public class CommandReply internal constructor(private val bytes: ByteArray) {
    private var offset = 0
    internal fun byte(): Int { if (!(offset < bytes.size)) throw IllegalStateException("Truncated command result"); return bytes[offset++].toInt() and 255 }
    private fun short(): Int = byte() or (byte() shl 8)
    internal fun text(): String {
        val size = short()
        if (!(size <= (bytes.size - offset) / 2)) throw IllegalStateException("Truncated command text")
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
    internal fun frame(): ByteArray = rawBytes()
    private fun rawBytes(): ByteArray {
        val size = short()
        if (!(size <= bytes.size - offset)) throw IllegalStateException("Truncated command bytes")
        val result = ByteArray(size)
        for (index in 0 until size) result[index] = bytes[offset++]
        return result
    }
    internal fun finished(): Boolean = offset == bytes.size
}

private object CommandBindings {
    external fun submit(packet: ByteArray): ByteArray
}
