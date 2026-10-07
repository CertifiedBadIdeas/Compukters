/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType

/** Generic supertypes require source materialization even when the class itself has no type parameters. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.hasParameterizedSupertype(): Boolean =
    kind !in setOf(ClassKind.ENUM_CLASS, ClassKind.ENUM_ENTRY) &&
        superTypes.any { (it as? IrSimpleType)?.arguments?.isNotEmpty() == true }
