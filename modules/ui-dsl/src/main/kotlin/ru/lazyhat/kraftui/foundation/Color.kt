package ru.lazyhat.kraftui.foundation

/**
 * ARGB color packed as a 32-bit value (`0xAARRGGBB`).
 *
 * Common named colors are exposed as constants on the companion object to
 * preserve call sites like [Color.White] after the migration from an enum.
 * Use [argb] / [rgb] / [hex] for arbitrary colors.
 */
@JvmInline
value class Color(
    val value: UInt,
) {
    companion object {
        val Transparent = Color(0x00000000u)
        val Black = Color(0xFF000000u)
        val White = Color(0xFFFFFFFFu)
        val Blue = Color(0xFF0000FFu)
        val Green = Color(0xFF00FF00u)
        val Red = Color(0xFFFF0000u)

        fun argb(
            alpha: Int,
            red: Int,
            green: Int,
            blue: Int,
        ): Color =
            Color(
                (
                    ((alpha and 0xFF).toUInt() shl 24) or
                        ((red and 0xFF).toUInt() shl 16) or
                        ((green and 0xFF).toUInt() shl 8) or
                        (blue and 0xFF).toUInt()
                ),
            )

        fun rgb(
            red: Int,
            green: Int,
            blue: Int,
        ): Color = argb(0xFF, red, green, blue)

        fun hex(value: UInt): Color = Color(value)

        /**
         * Accepts an `Int` literal that may be negative (typical JVM
         * `0xFF12151D.toInt()` form). Bits are reinterpreted unchanged.
         */
        fun hex(value: Int): Color = Color(value.toUInt())
    }
}
