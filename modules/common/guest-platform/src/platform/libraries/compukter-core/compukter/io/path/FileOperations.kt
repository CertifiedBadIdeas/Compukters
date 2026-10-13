/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package compukter.io.path

import compukter.filesystem.AtomicFileWriter
import compukter.filesystem.DirectoryReader
import compukter.filesystem.FileInfo
import compukter.filesystem.FileReader
import compukter.filesystem.FileSystem
import compukter.filesystem.Path
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.sequences.Sequence

/** Optional storage conveniences. Every operation delegates to the explicit FileSystem facade. */
public fun Path.stat(): FileInfo = FileSystem.stat(this)

public fun Path.statOrNull(): FileInfo? = FileSystem.statOrNull(this)

public fun Path.openRead(): FileReader = FileSystem.openRead(this)

public fun Path.openWrite(): AtomicFileWriter = FileSystem.openWrite(this)

public fun Path.openDirectory(): DirectoryReader = FileSystem.openDirectory(this)

public fun Path.readBytes(maximumBytes: Int = 65536): ByteArray = FileSystem.readBytes(this, maximumBytes)

public fun Path.readText(maximumBytes: Int = 65536): String = FileSystem.readText(this, maximumBytes)

/** Reads all lines within the encoded file-size bound; see [FileSystem.readLines]. */
public fun Path.readLines(maximumBytes: Int = 65536): List<String> = FileSystem.readLines(this, maximumBytes)

/** Processes a scoped single-pass sequence, closing on every exit; see [FileSystem.useLines]. */
public inline fun <R> Path.useLines(
    maximumLineBytes: Int = 65536,
    block: (Sequence<String>) -> R,
): R {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return FileSystem.useLines(this, maximumLineBytes, block)
}

public fun Path.writeBytes(contents: ByteArray): Unit = FileSystem.writeBytes(this, contents)

public fun Path.writeText(contents: String): Unit = FileSystem.writeText(this, contents)

public fun Path.list(maximumEntries: Int = 256): List<Path> = FileSystem.list(this, maximumEntries)

public fun Path.createDirectory(): Unit = FileSystem.createDirectory(this)

public fun Path.createDirectories(): Unit = FileSystem.createDirectories(this)

public fun Path.delete(): Unit = FileSystem.delete(this)

public fun Path.deleteIfExists(): Boolean = FileSystem.deleteIfExists(this)

public fun Path.moveTo(destination: Path): Unit = FileSystem.move(this, destination)

public fun Path.moveTo(
    destination: Path,
    replaceExisting: Boolean,
): Unit = FileSystem.move(this, destination, replaceExisting)
