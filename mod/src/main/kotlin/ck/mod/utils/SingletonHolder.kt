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

package ck.mod.utils

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

abstract class SingletonHolder<T : Any> {
    private val logger: Logger = LogManager.getLogger(this::class.java)

    @Suppress("ktlint:standard:backing-property-naming")
    @Volatile
    private var _instance: T? = null

    val isInitialized get() = _instance != null

    protected var instance: T
        get() = checkNotNull(_instance) { "[SingletonHolder] holding class has not been initialized" }

        @Synchronized
        set(value) {
            _instance?.let { error("${it::class.simpleName} already initialized") } ?: run {
                _instance = value
                logger.info("${value::class.simpleName} successfully initialized")
            }
        }

    protected fun resetInstance() {
        _instance?.let {
            _instance = null
            logger.info("${it::class.simpleName} successfully uninitialized")
        }
    }
}
