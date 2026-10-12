/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.annotation

public enum class AnnotationTarget {
    CLASS, ANNOTATION_CLASS, TYPE_PARAMETER, PROPERTY, FIELD, LOCAL_VARIABLE, VALUE_PARAMETER,
    CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, TYPE, EXPRESSION, FILE, TYPEALIAS
}

public enum class AnnotationRetention { SOURCE, BINARY, RUNTIME }

public annotation class Retention(val value: AnnotationRetention)

public annotation class Target(vararg val allowedTargets: AnnotationTarget)
