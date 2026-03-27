/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ck.mod.platform

import org.jetbrains.annotations.ApiStatus
import java.util.ServiceLoader

/**
 * Utilities for loading services.
 *
 *
 * Do **NOT** directly reference this class. It exists for internal use by the API.
 */
@ApiStatus.Internal
object Services {
    /**
     * Load a service, asserting that only a single instance is registered.
     *
     * @param klass The class of the service to load.
     * @param <T>   The class of the service to load.
     * @return The constructed service instance.
     * @throws IllegalStateException When the service cannot be loaded.
     </T> */
    fun <T> load(klass: Class<T>): T {
        val services: List<ServiceLoader.Provider<T>> = ServiceLoader.load(klass, klass.classLoader).stream().toList()
        return when (services.size) {
            1 -> services.single().get()
            0 -> throw IllegalStateException("Cannot find service for ${klass.name}")
            else -> {
                val serviceTypes = services.joinToString(", ") { it.type().name }
                throw IllegalStateException("Multiple services for ${klass.name}: $serviceTypes")
            }
        }
    }

    /**
     * Attempt to load a service with [.load].
     *
     * @param klass The class of the service to load.
     * @param <T>   The class of the service to load.
     * @return The result type, either containing the service or an exception.
     * @see ComputerCraftAPIService Intended usage of this class.
     </T> */
    fun <T> tryLoad(klass: Class<T>): LoadedService<T> =
        try {
            LoadedService(load<T>(klass), null)
        } catch (e: Exception) {
            LoadedService(null, e)
        } catch (e: LinkageError) {
            LoadedService(null, e)
        }

    /**
     * Raise an exception from trying to load a specific service.
     *
     * @param klass The class of the service we failed to load.
     * @param e     The original exception caused by loading this class.
     * @param <T>   The class of the service to load.
     * @return Never
     * @see .tryLoad
     * @see LoadedService.error
     </T> */
    fun <T> raise(
        klass: Class<T>,
        e: Throwable?,
    ): T {
        // Throw a new exception so there's a useful stack trace there somewhere!
        throw ServiceException("Failed to instantiate ${klass.name}", e)
    }

    class LoadedService<T> internal constructor(
        val instance: T?,
        val error: Throwable?,
    )
}
