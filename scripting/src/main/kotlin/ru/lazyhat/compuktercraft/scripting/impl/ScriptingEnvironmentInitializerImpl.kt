package ru.lazyhat.compuktercraft.scripting.impl

import ru.lazyhat.compuktercraft.scripting.api.ScriptingEnvironment
import ru.lazyhat.compuktercraft.scripting.api.ScriptingEnvironmentConfig
import ru.lazyhat.compuktercraft.scripting.api.ScriptingEnvironmentInitializer

class ScriptingEnvironmentInitializerImpl : ScriptingEnvironmentInitializer {
    override fun initialize(config: ScriptingEnvironmentConfig): ScriptingEnvironment = ScriptingEnvironmentImpl(config)
}
