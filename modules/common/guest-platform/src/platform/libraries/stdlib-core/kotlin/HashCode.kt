/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

public fun Any?.hashCode(): Int = if (this == null) 0 else this.hashCode()
