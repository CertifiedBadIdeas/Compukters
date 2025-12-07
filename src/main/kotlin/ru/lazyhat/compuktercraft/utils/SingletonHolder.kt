package ru.lazyhat.compuktercraft.utils

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

abstract class SingletonHolder<T : Any> {
    private val logger: Logger = LogManager.getLogger(this::class.java)

    @Suppress("ktlint:standard:backing-property-naming")
    @Volatile
    private var _instance: T? = null

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
