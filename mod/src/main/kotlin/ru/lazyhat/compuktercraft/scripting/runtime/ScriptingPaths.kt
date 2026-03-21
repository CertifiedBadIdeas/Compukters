package ru.lazyhat.compuktercraft.scripting.runtime

import java.io.File

object ScriptingPaths {
    const val SCRIPTING_JAR = "CompukterCraftScripting.jar"

    fun rootDirectory(): File = File("compuktercraft")

    fun scriptsDirectory(): File = rootDirectory().resolve("scripts")

    fun scriptingJar(): File = rootDirectory().resolve(SCRIPTING_JAR)
}
