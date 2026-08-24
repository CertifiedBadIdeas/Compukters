/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.lang.runtime.fs

enum class FileSystemStoreHealth(
    internal val wireCode: Int,
) {
    ACTIVE(0),
    DRAINING(1),
    FAULTED(2),
    CLOSED(3),
}

enum class FileSystemStoreOpenFailure(
    internal val wireCode: Int,
) {
    ROOT_NOT_ABSOLUTE(1),
    ROOT_NOT_CANONICAL(2),
    ROOT_NOT_DIRECTORY(3),
    LOCKED(4),
    IO(5),
}

class FileSystemStoreOpenException(
    val failure: FileSystemStoreOpenFailure,
) : IllegalStateException("native filesystem store admission failed: $failure")
