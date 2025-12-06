// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.platform

import org.jetbrains.annotations.ApiStatus
import java.util.ServiceLoader
import java.util.stream.Collectors

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
        val services = ServiceLoader.load(klass, klass.getClassLoader()).stream().toList()
        return when (services.size) {
            1 -> {
                services[0]!!.get()
            }

            0 -> {
                throw IllegalStateException("Cannot find service for " + klass.getName())
            }

            else -> {
                val serviceTypes =
                    services.stream().map { x: ServiceLoader.Provider<T?>? -> x!!.type().getName() }.collect(
                        Collectors.joining(", "),
                    )
                throw IllegalStateException("Multiple services for " + klass.getName() + ": " + serviceTypes)
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
        throw ServiceException("Failed to instantiate " + klass.getName(), e)
    }

    class LoadedService<T> internal constructor(
        val instance: T?,
        val error: Throwable?,
    )
}
