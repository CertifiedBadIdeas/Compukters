/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.commands

/** An immutable description. Only batch() submits it; reusing a description executes it again. */
public class Command<T> private constructor(private val packet: ByteArray) {
    internal fun encoded(): ByteArray = packet

    public companion object {
        /** Addon authoring: use the exact external binding symbol. The server must explicitly allow it. */
        public fun unit(binding: String, arguments: List<CommandArgument>): Command<Unit> {
            return Command<Unit>(encodeCommand(binding, arguments))
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

public class CommandResult<T> internal constructor(private val value: T?, private val failure: CommandException?) {
    public val isSuccess: Boolean get() = failure == null
    public val isFailure: Boolean get() = failure != null
    public fun exceptionOrNull(): CommandException? = failure
    public fun getOrNull(): T? = value

    /** Repeated calls return the same result or throw the same stored exception object. */
    @Suppress("UNCHECKED_CAST")
    public fun getOrThrow(): T {
        val caught = failure
        if (caught != null) throw caught
        return value as T
    }
}

/**
 * Submits one group of immediate Unit commands, applied in list order in one server tick delivery.
 *
 * At most 32 commands and 4096 encoded bytes. A group is never split. Each position receives its own
 * result; command errors do not cancel others and do not roll back earlier effects. An empty list does
 * no host work. This blocks only the calling task; use async { batch(commands) } for a deferred result.
 */
public fun batch(commands: List<Command<Unit>>): List<CommandResult<Unit>> {
    require(commands.size <= 32)
    if (commands.isEmpty()) return emptyList()
    val output = CommandWriter()
    output.byte(1)
    output.byte(commands.size)
    for (command in commands) output.raw(command.encoded())
    val input = CommandReader(CommandBindings.submit(output.finish()))
    if (input.byte() != 1 || input.byte() != commands.size) throw IllegalStateException("Invalid command batch response")
    val results = ArrayList<CommandResult<Unit>>()
    for (index in 0 until commands.size) {
        val status = input.byte()
        if (status == 0) {
            results.add(CommandResult<Unit>(Unit, null))
        } else {
            if (status !in 1..5) throw IllegalStateException("Invalid command failure status")
            val detail = input.text()
            if (detail.length > 48) throw IllegalStateException("Invalid command failure detail")
            results.add(CommandResult<Unit>(null, CommandException(CommandFailureKind.entries[status - 1], detail)))
        }
    }
    if (!input.finished()) throw IllegalStateException("Trailing command batch response")
    return results
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

private class CommandReader(private val bytes: ByteArray) {
    private var offset = 0
    fun byte(): Int { if (offset >= bytes.size) throw IllegalStateException("Truncated command batch response"); return bytes[offset++].toInt() and 255 }
    fun short(): Int = byte() or (byte() shl 8)
    fun text(): String {
        val size = short()
        if (size > (bytes.size - offset) / 2) throw IllegalStateException("Truncated command failure detail")
        val chars = CharArray(size)
        for (index in 0 until size) chars[index] = short().toChar()
        return chars.concatToString(0, chars.size)
    }
    fun finished(): Boolean = offset == bytes.size
}

private object CommandBindings {
    external fun submit(packet: ByteArray): ByteArray
}
