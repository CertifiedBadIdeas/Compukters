/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

public annotation class Suppress(vararg val names: String)

public annotation class Deprecated(val message: String)

public annotation class PublishedApi

@kotlin.annotation.Target(kotlin.annotation.AnnotationTarget.TYPE)
public annotation class UnsafeVariance

public annotation class ExtensionFunctionType

public annotation class ParameterName(val name: String)

/** Marks declarations whose callers must acknowledge an experimental API. */
@kotlin.annotation.Target(kotlin.annotation.AnnotationTarget.ANNOTATION_CLASS)
@kotlin.annotation.Retention(kotlin.annotation.AnnotationRetention.BINARY)
public annotation class RequiresOptIn(val message: String = "", val level: Level = Level.ERROR) {
    public enum class Level { WARNING, ERROR }
}

/** Accepts the named experimental markers within this declaration or file. */
@kotlin.annotation.Target(
    kotlin.annotation.AnnotationTarget.CLASS,
    kotlin.annotation.AnnotationTarget.ANNOTATION_CLASS,
    kotlin.annotation.AnnotationTarget.PROPERTY,
    kotlin.annotation.AnnotationTarget.LOCAL_VARIABLE,
    kotlin.annotation.AnnotationTarget.VALUE_PARAMETER,
    kotlin.annotation.AnnotationTarget.CONSTRUCTOR,
    kotlin.annotation.AnnotationTarget.FUNCTION,
    kotlin.annotation.AnnotationTarget.PROPERTY_GETTER,
    kotlin.annotation.AnnotationTarget.PROPERTY_SETTER,
    kotlin.annotation.AnnotationTarget.FILE,
    kotlin.annotation.AnnotationTarget.TYPEALIAS,
)
@kotlin.annotation.Retention(kotlin.annotation.AnnotationRetention.SOURCE)
public annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<out Annotation>)
