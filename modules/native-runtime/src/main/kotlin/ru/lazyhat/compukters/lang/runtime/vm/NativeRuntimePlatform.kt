/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.lang.runtime.vm

import java.util.Locale

internal data class NativeRuntimePlatform(
    val os: String,
    val architecture: String,
    val filename: String,
) {
    val resourcePath: String = "/META-INF/natives/$os/$architecture/$filename"

    companion object {
        fun resolve(
            osName: String,
            osArch: String,
        ): NativePlatformResolution {
            val os =
                when {
                    osName.normalized().startsWith("linux") -> NativeOperatingSystem.LINUX
                    osName.normalized().startsWith("windows") -> NativeOperatingSystem.WINDOWS
                    osName.normalized().startsWith("mac") -> NativeOperatingSystem.MACOS
                    else -> null
                }
            val architecture =
                when (osArch.normalized()) {
                    "amd64", "x86_64" -> "x86_64"
                    "arm64", "aarch64" -> "aarch64"
                    else -> null
                }
            if (os == null || architecture == null) {
                return NativePlatformResolution.Unsupported(
                    osName = osName.runtimeDiagnostic("unknown operating system"),
                    osArch = osArch.runtimeDiagnostic("unknown architecture"),
                )
            }
            return NativePlatformResolution.Supported(
                NativeRuntimePlatform(
                    os = os.id,
                    architecture = architecture,
                    filename = os.filename,
                ),
            )
        }

        private fun String.normalized(): String = trim().lowercase(Locale.ROOT)
    }
}

internal sealed interface NativePlatformResolution {
    data class Supported(
        val platform: NativeRuntimePlatform,
    ) : NativePlatformResolution

    data class Unsupported(
        val osName: String,
        val osArch: String,
    ) : NativePlatformResolution
}

private enum class NativeOperatingSystem(
    val id: String,
    val filename: String,
) {
    LINUX("linux", "libcompukter_ffi.so"),
    WINDOWS("windows", "compukter_ffi.dll"),
    MACOS("macos", "libcompukter_ffi.dylib"),
}

internal const val MAXIMUM_RUNTIME_DIAGNOSTIC_CODE_UNITS = 256

internal fun String?.runtimeDiagnostic(fallback: String): String {
    val raw = this ?: fallback
    val lineEnd = raw.indexOfAny(charArrayOf('\r', '\n')).let { if (it < 0) raw.length else it }
    return raw.substring(0, lineEnd).ifEmpty { fallback }.take(MAXIMUM_RUNTIME_DIAGNOSTIC_CODE_UNITS)
}
