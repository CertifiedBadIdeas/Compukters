package ru.lazyhat.compuktercraft.utils

abstract class SingletonHolder<T : Any> {
    @Volatile
    private var _instance: T? = null

    var instance: T
        get() =
            checkNotNull(_instance) {
                "${instance::class.simpleName} has not been initialized"
            }

        @Synchronized
        set(value) {
            if (_instance != null) error("${instance::class.simpleName} already initialized")
            _instance = value
        }
}
