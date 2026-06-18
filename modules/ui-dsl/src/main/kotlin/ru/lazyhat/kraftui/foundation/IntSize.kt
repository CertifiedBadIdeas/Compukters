package ru.lazyhat.kraftui.foundation

data class IntSize(
    val width: Int,
    val height: Int,
) {
    operator fun plus(other: IntSize) =
        IntSize(
            width + other.width,
            height + other.height,
        )

    operator fun plus(other: Int) =
        IntSize(
            width + other,
            height + other,
        )

    operator fun minus(other: IntSize) =
        IntSize(
            width - other.width,
            height - other.height,
        )

    operator fun minus(other: Int) =
        IntSize(
            width - other,
            height - other,
        )

    operator fun times(other: Int) =
        IntSize(
            width * other,
            height * other,
        )

    operator fun times(other: Float) =
        IntSize(
            width.times(other).toInt(),
            height.times(other).toInt(),
        )

    operator fun times(other: Double) =
        IntSize(
            width.times(other).toInt(),
            height.times(other).toInt(),
        )

    companion object {
        val Zero = IntSize(0, 0)
    }
}
