// SPDX-FileCopyrightText: 2023 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.gui

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import org.joml.Matrix4f

/**
 * A [GuiGraphics]-equivalent which is suitable for both rendering in to a GUI and in-world (as part of an entity
 * renderer).
 *
 *
 * This batches all render calls together, though requires that all [TextureAtlasSprite]s are on the same sprite
 * sheet.
 */
class SpriteRenderer(
	private val transform: Matrix4f,
	private val builder: VertexConsumer,
	private val z: Int,
	private val light: Int,
	private val r: Int,
	private val g: Int,
	private val b: Int
) {
	/**
	 * Render a single sprite.
	 *
	 * @param sprite The texture to draw.
	 * @param x      The x position of the rectangle we'll draw.
	 * @param y      The x position of the rectangle we'll draw.
	 * @param width  The width of the rectangle we'll draw.
	 * @param height The height of the rectangle we'll draw.
	 */
	fun blit(sprite: TextureAtlasSprite, x: Int, y: Int, width: Int, height: Int) {
		blit(x, y, width, height, sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1())
	}

	/**
	 * Render a horizontal 3-sliced texture (i.e. split into left, middle and right). Unlike [GuiGraphics.blitNineSliced],
	 * the middle texture is stretched rather than repeated.
	 *
	 * @param sprite       The texture to draw.
	 * @param x            The x position of the rectangle we'll draw.
	 * @param y            The x position of the rectangle we'll draw.
	 * @param width        The width of the rectangle we'll draw.
	 * @param height       The height of the rectangle we'll draw.
	 * @param leftBorder   The width of the left border.
	 * @param rightBorder  The width of the right border.
	 * @param textureWidth The width of the whole texture.
	 */
	fun blitHorizontalSliced(
		sprite: TextureAtlasSprite,
		x: Int,
		y: Int,
		width: Int,
		height: Int,
		leftBorder: Int,
		rightBorder: Int,
		textureWidth: Int
	) {
		// TODO(1.20.2)/TODO(1.21.0): Drive this from mcmeta files, like vanilla does.
		require(width >= leftBorder + rightBorder) { "width is less than two borders" }

		val centerStart: Float = u(sprite, leftBorder, textureWidth)
		val centerEnd: Float = u(sprite, textureWidth - rightBorder, textureWidth)

		blit(x, y, leftBorder, height, sprite.getU0(), sprite.getV0(), centerStart, sprite.getV1())
		blit(x + leftBorder, y, width - leftBorder - rightBorder, height, centerStart, sprite.getV0(), centerEnd, sprite.getV1())
		blit(x + width - rightBorder, y, rightBorder, height, centerEnd, sprite.getV0(), sprite.getU1(), sprite.getV1())
	}

	/**
	 * Render a vertical 3-sliced texture (i.e. split into top, middle and bottom). Unlike [GuiGraphics.blitNineSliced],
	 * the middle texture is stretched rather than repeated.
	 *
	 * @param sprite        The texture to draw.
	 * @param x             The x position of the rectangle we'll draw.
	 * @param y             The x position of the rectangle we'll draw.
	 * @param width         The width of the rectangle we'll draw.
	 * @param height        The height of the rectangle we'll draw.
	 * @param topBorder     The height of the top border.
	 * @param bottomBorder  The height of the bottom border.
	 * @param textureHeight The height of the whole texture.
	 */
	fun blitVerticalSliced(
		sprite: TextureAtlasSprite,
		x: Int,
		y: Int,
		width: Int,
		height: Int,
		topBorder: Int,
		bottomBorder: Int,
		textureHeight: Int
	) {
		// TODO(1.20.2)/TODO(1.21.0): Drive this from mcmeta files, like vanilla does.
		require(width >= topBorder + bottomBorder) { "height is less than two borders" }

		val centerStart: Float = v(sprite, topBorder, textureHeight)
		val centerEnd: Float = v(sprite, textureHeight - bottomBorder, textureHeight)

		blit(x, y, width, topBorder, sprite.getU0(), sprite.getV0(), sprite.getU1(), centerStart)
		blit(x, y + topBorder, width, height - topBorder - bottomBorder, sprite.getU0(), centerStart, sprite.getU1(), centerEnd)
		blit(x, y + height - bottomBorder, width, bottomBorder, sprite.getU0(), centerEnd, sprite.getU1(), sprite.getV1())
	}

	/**
	 * The low-level blit function, used to render a portion of the sprite sheet. Unlike other functions, this takes uvs rather than a single sprite.
	 *
	 * @param x      The x position of the rectangle we'll draw.
	 * @param y      The x position of the rectangle we'll draw.
	 * @param width  The width of the rectangle we'll draw.
	 * @param height The height of the rectangle we'll draw.
	 * @param u0     The first U coordinate.
	 * @param v0     The first V coordinate.
	 * @param u1     The second U coordinate.
	 * @param v1     The second V coordinate.
	 */
	fun blit(
		x: Int, y: Int, width: Int, height: Int, u0: Float, v0: Float, u1: Float, v1: Float
	) {
		builder.vertex(transform, x.toFloat(), (y + height).toFloat(), z.toFloat()).color(r, g, b, 255).uv(u0, v1).uv2(light).endVertex()
		builder.vertex(transform, (x + width).toFloat(), (y + height).toFloat(), z.toFloat()).color(r, g, b, 255).uv(u1, v1).uv2(light)
			.endVertex()
		builder.vertex(transform, (x + width).toFloat(), y.toFloat(), z.toFloat()).color(r, g, b, 255).uv(u1, v0).uv2(light).endVertex()
		builder.vertex(transform, x.toFloat(), y.toFloat(), z.toFloat()).color(r, g, b, 255).uv(u0, v0).uv2(light).endVertex()
	}

	companion object {
		fun createForGui(graphics: GuiGraphics, renderType: RenderType): SpriteRenderer {
			return SpriteRenderer(
				graphics.pose().last().pose(), graphics.bufferSource().getBuffer(renderType),
				0, RenderTypes.FULL_BRIGHT_LIGHTMAP, 255, 255, 255
			)
		}

		fun u(sprite: TextureAtlasSprite, x: Int, width: Int): Float {
			return sprite.getU(x.toDouble() / width * 16)
		}

		fun v(sprite: TextureAtlasSprite, y: Int, height: Int): Float {
			return sprite.getV(y.toDouble() / height * 16)
		}
	}
}
