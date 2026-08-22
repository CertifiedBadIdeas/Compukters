/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.playground

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    val debug = "--debug" in arguments
    val exit =
        try {
            val payload = Path.of(requiredProperty("compukters.worker.payload"))
            val library = Path.of(requiredProperty("compukters.jni.library"))
            val java =
                Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
                )
            val application =
                PlaygroundApplication(
                    compilerFactory = { ForkedPlaygroundCompiler(payload, java) },
                    executor = NativePlaygroundExecutor(library, System.`in`, System.out),
                    stderr = System.err,
                )
            runBlocking { application.run(arguments.toList()) }
        } catch (exception: Exception) {
            System.err.println("launcher: ${exception.message}")
            if (debug) exception.printStackTrace(System.err)
            PlaygroundExit.PLATFORM
        }
    exitProcess(exit)
}

private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing system property $name" }
