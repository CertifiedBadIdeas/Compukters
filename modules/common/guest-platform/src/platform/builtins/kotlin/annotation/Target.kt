/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.annotation

public enum class AnnotationTarget { TYPE }

public annotation class Target(vararg val allowedTargets: AnnotationTarget)
