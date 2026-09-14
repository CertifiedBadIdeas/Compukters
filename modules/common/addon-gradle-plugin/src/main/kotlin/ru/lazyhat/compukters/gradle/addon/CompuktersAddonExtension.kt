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

package ru.lazyhat.compukters.gradle.addon

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class CompuktersAddonExtension
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        val id: Property<String> = objects.property(String::class.java)

        val version: Property<String> = objects.property(String::class.java)
        val toolingCoordinate: Property<String> = objects.property(String::class.java)
        val platformCoordinate: Property<String> = objects.property(String::class.java)
        val commonApiCoordinate: Property<String> = objects.property(String::class.java)
        val adapterApiCoordinate: Property<String> = objects.property(String::class.java)

        fun register(
            id: String,
            version: String? = null,
        ) {
            check(!this.id.isPresent) { "compuktersAddon.register(...) may only be called once" }
            this.id.set(id)
            version?.let(this.version::set)
        }
    }
