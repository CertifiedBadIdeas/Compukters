/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.annotation

/**
 * Reconstruct an immutable transport value through a public companion factory with the primary
 * constructor's parameter types. A single constructor field is the wire representation.
 * [normalize] optionally names a field whose homogeneous floating components must be finite,
 * nonzero and normalized before entering a host handler. Ordinary Guest construction stays in [factory].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class RecordConstructor(val factory: String, val normalize: String = "")
