package ru.lazyhat.compuktercraft.utils

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

abstract class SingletonHolder<T : Any> {
    private val logger: Logger = LogManager.getLogger(this::class.java)

    @Volatile
    private var _instance: T? = null

    var instance: T
        get() =
            checkNotNull(_instance) {
                "[SingletonHolder] holding class has not been initialized"
            }

        @Synchronized
        set(value) {
            if (_instance != null) error("${instance::class.simpleName} already initialized")
            _instance = value
            logger.info("${value::class.simpleName} successfully initialized")
        }
}
