/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.io

/** An ordinary failure of an input/output operation in the current computer. */
public open class IOException(message: String?, cause: Throwable? = null) : Exception(message, cause)
