/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.network.chat.Component
import ru.lazyhat.compukters.impl.ide.IdeColors

/** Canonical radial presentation; target leaves supply only their drawing API and font metrics. */
internal object ConfiguratorRadialPresentation {
    fun draw(
        width: Int,
        height: Int,
        mouseX: Int,
        mouseY: Int,
        textWidth: (Component) -> Int,
        lineHeight: Int,
        fill: (Int, Int, Int, Int, Int) -> Unit,
        text: (Component, Int, Int, Int) -> Unit,
    ) {
        val cx = width / 2
        val cy = height / 2
        val selected = ConfiguratorRadialGeometry.select((mouseX - cx).toDouble(), (mouseY - cy).toDouble())
        ConfiguratorRadialGeometry.spans.forEach { span ->
            fill(
                cx + span.x,
                cy + span.y,
                cx + span.x + span.width,
                cy + span.y + 1,
                if (selected == span.sector) IdeColors.SELECTION else IdeColors.PANEL_ALT,
            )
        }

        fun label(
            component: Component,
            x: Int,
            y: Int,
            color: Int,
        ) {
            text(component, x - textWidth(component) / 2, y - lineHeight / 2, color)
        }
        ConfiguratorRadialGeometry.modes.forEachIndexed { index, mode ->
            label(
                Component.translatable("screen.compukters.configurator.radial.$mode"),
                cx + ConfiguratorRadialGeometry.labelX(index),
                cy + ConfiguratorRadialGeometry.labelY(index),
                if (index == selected) IdeColors.ACCENT else IdeColors.TEXT,
            )
        }
        label(Component.translatable("gui.cancel"), cx, cy, IdeColors.MUTED)
        label(
            Component.translatable("screen.compukters.configurator.radial"),
            cx,
            maxOf(
                lineHeight,
                cy - ConfiguratorRadialGeometry.OUTER - 20,
            ),
            IdeColors.TEXT,
        )
        label(
            Component.translatable("screen.compukters.configurator.radial.hint"),
            cx,
            minOf(
                height - lineHeight,
                cy + ConfiguratorRadialGeometry.OUTER + 20,
            ),
            IdeColors.MUTED,
        )
    }
}
