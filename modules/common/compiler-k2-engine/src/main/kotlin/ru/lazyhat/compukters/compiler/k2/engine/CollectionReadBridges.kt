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

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isNullable

/** Read-only views of the trusted list implementations; mutable element types stay invariant. */
internal object CollectionReadBridges {
    private data class View(
        val root: String,
        val nullableElementView: Boolean,
        val methods: Map<String, String>,
    )

    private fun aliases(vararg operations: String): Map<String, String> =
        operations
            .flatMap { operation ->
                listOf("Any", "AnyNullable", "NullableElement").map { suffix -> "$operation$suffix" to operation }
            }.toMap()

    private val listMethods = aliases("get", "contains", "indexOf", "lastIndexOf", "iterator")
    private val iteratorMethods = aliases("next")
    private val views =
        mapOf(
            "ArrayList" to View("List", true, listMethods + ("iteratorReadOnly" to "iterator")),
            "ArrayListIterator" to View("Iterator", true, iteratorMethods),
            "ArrayListAnyIterator" to View("Iterator", false, iteratorMethods),
        ).mapKeys { (name, _) -> "kotlin.collections.$name" }

    fun methodName(function: IrSimpleFunction): String? {
        val owner = function.parent as? IrClass ?: return null
        if (owner.fqNameWhenAvailable?.asString() == "kotlin.collections.HashSet" && function.name.asString() == "iteratorReadOnly") {
            return "iterator"
        }
        return views[owner.fqNameWhenAvailable?.asString()]?.methods?.get(function.name.asString())
    }

    fun interfaces(
        declaration: IrClass,
        arguments: List<IrType>,
        anyType: IrType,
    ): List<Pair<String, IrType>> {
        val view = views[declaration.fqNameWhenAvailable?.asString()] ?: return emptyList()
        val root = "kotlin.collections.${view.root}"
        val nullableElement =
            arguments.singleOrNull()?.takeUnless { !view.nullableElementView || it.isNullable() }?.makeNullable()
        val nullableRoots =
            if (view.root == "List") {
                listOf(root, "kotlin.collections.Collection", "kotlin.collections.Iterable")
            } else {
                listOf(root)
            }
        return listOfNotNull(
            root to anyType.makeNullable(),
            (root to anyType).takeIf { arguments.none { it.isNullable() } },
        ) + nullableRoots.mapNotNull { name -> nullableElement?.let { name to it } }
    }
}
