/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

public open class Exception(message: String?, cause: Throwable? = null) : Throwable(message, cause)

public open class RuntimeException(message: String?, cause: Throwable? = null) : Exception(message, cause)

public class IllegalArgumentException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

public open class IllegalStateException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

public class NoWhenBranchMatchedException public constructor() : RuntimeException(null)

public class ArithmeticException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

public open class IndexOutOfBoundsException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

public class NegativeArraySizeException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

public class NullPointerException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

public class ClassCastException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

/** No element satisfies a required collection or peripheral selection. */
public class NoSuchElementException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)
