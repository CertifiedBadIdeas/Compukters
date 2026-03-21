package ru.lazyhat.compuktercraft.scripting.runtime

import ru.lazyhat.compuktercraft.scripting.api.ScriptingEnvironment

object ScriptingEnvironmentHolder {
    @Volatile
    var environment: ScriptingEnvironment? = null
        private set

    val isAvailable: Boolean
        get() = environment?.isAvailable == true

    fun install(environment: ScriptingEnvironment?) {
        this.environment = environment
    }

    fun clear() {
        environment = null
    }
}
