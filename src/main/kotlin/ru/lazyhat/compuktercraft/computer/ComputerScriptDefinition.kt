package ru.lazyhat.compuktercraft.computer

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.ide

@KotlinScript(
    fileExtension = "cc.kts",
)
abstract class ComputerScriptDefinition

class ComputerScriptCompilationConfiguration :
    ScriptCompilationConfiguration({
        defaultImports(emptyList())

        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }
    })

class ComputerScriptEvaluationConfiguration :
    ScriptEvaluationConfiguration({
    })
