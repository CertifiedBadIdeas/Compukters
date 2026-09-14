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

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class CompuktersAddonExtension
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        val addon: Property<String> = objects.property(String::class.java)
        val module: Property<String> = objects.property(String::class.java)
        val moduleVersion: Property<String> = objects.property(String::class.java).convention("1.0.0")
        val dependencies: ListProperty<String> = objects.listProperty(String::class.java).convention(listOf("stdlib:core"))
        val toolingCoordinate: Property<String> = objects.property(String::class.java)
        val platformCoordinate: Property<String> = objects.property(String::class.java)
        val commonApiCoordinate: Property<String> = objects.property(String::class.java)
        val adapterApiCoordinate: Property<String> = objects.property(String::class.java)
        val capabilities: NamedDomainObjectContainer<CompuktersAddonCapability> =
            objects.domainObjectContainer(CompuktersAddonCapability::class.java) { name ->
                objects.newInstance(CompuktersAddonCapability::class.java, name)
            }

        fun capability(
            name: String,
            configure: Action<in CompuktersAddonCapability>,
        ) {
            capabilities.maybeCreate(name).also(configure::execute)
        }

        fun capability(name: String) {
            capabilities.maybeCreate(name)
        }
    }

abstract class CompuktersAddonCapability
    @Inject
    constructor(
        private val capabilityName: String,
        objects: ObjectFactory,
    ) : Named {
        override fun getName(): String = capabilityName

        val abiMajor: Property<Int> = objects.property(Int::class.java).convention(1)
        val abiMinor: Property<Int> = objects.property(Int::class.java).convention(0)
        val bindingOwner: Property<String> = objects.property(String::class.java)
    }
