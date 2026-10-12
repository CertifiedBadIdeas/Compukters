/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.experimental

/**
 * Returns the bitwise AND of this value and [other].
 *
 * @param other bit pattern to combine with this value.
 * @receiver 8-bit value whose bits are used.
 * @return the resulting Byte bit pattern.
 */
public infix fun Byte.and(other: Byte): Byte = (this.toInt() and other.toInt()).toByte()

/**
 * Returns the bitwise OR of this value and [other].
 *
 * @param other bit pattern to combine with this value.
 * @receiver 8-bit value whose bits are used.
 * @return the resulting Byte bit pattern.
 */
public infix fun Byte.or(other: Byte): Byte = (this.toInt() or other.toInt()).toByte()

/**
 * Returns the bitwise exclusive OR of this value and [other].
 *
 * @param other bit pattern to combine with this value.
 * @receiver 8-bit value whose bits are used.
 * @return the resulting Byte bit pattern.
 */
public infix fun Byte.xor(other: Byte): Byte = (this.toInt() xor other.toInt()).toByte()

/**
 * Inverts all 8 bits of this value.
 * @receiver 8-bit value whose bits are used.
 * @return the resulting Byte bit pattern.
 */
public fun Byte.inv(): Byte = this.toInt().inv().toByte()

/**
 * Returns the bitwise AND of this value and [other].
 *
 * @param other bit pattern to combine with this value.
 * @receiver 16-bit value whose bits are used.
 * @return the resulting Short bit pattern.
 */
public infix fun Short.and(other: Short): Short = (this.toInt() and other.toInt()).toShort()

/**
 * Returns the bitwise OR of this value and [other].
 *
 * @param other bit pattern to combine with this value.
 * @receiver 16-bit value whose bits are used.
 * @return the resulting Short bit pattern.
 */
public infix fun Short.or(other: Short): Short = (this.toInt() or other.toInt()).toShort()

/**
 * Returns the bitwise exclusive OR of this value and [other].
 *
 * @param other bit pattern to combine with this value.
 * @receiver 16-bit value whose bits are used.
 * @return the resulting Short bit pattern.
 */
public infix fun Short.xor(other: Short): Short = (this.toInt() xor other.toInt()).toShort()

/**
 * Inverts all 16 bits of this value.
 * @receiver 16-bit value whose bits are used.
 * @return the resulting Short bit pattern.
 */
public fun Short.inv(): Short = this.toInt().inv().toShort()
