/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

public class Array<T> private constructor() {
    public external val size: Int

    public external operator fun get(index: Int): T

    public external operator fun set(index: Int, value: T): Unit
}

public class CharArray external constructor(size: Int) {
    public external val size: Int

    public external operator fun get(index: Int): Char

    public external operator fun set(index: Int, value: Char): Unit
}
