package ru.lazyhat.kraftui.program

/**
 * Abstracts font width measurement for layout and compilation.
 */
fun interface FontMetrics {
    fun width(text: String): Int
}
