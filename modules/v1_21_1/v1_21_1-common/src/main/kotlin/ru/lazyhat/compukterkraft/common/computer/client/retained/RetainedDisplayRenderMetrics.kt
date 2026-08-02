/*
 * The Compukter Kraft Developers
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

package ru.lazyhat.compukterkraft.common.computer.client.retained

data class RetainedDisplayRenderMetricsSnapshot(
    val textureCreations: Long,
    val textureReleases: Long,
    val fullTextureUploads: Long,
    val subrectangleUploads: Long,
    val uploadedPixels: Long,
    val batchCreations: Long,
    val batchReleases: Long,
    val instanceChunkCompilations: Long,
    val boundaryFragmentCompilations: Long,
    val frameSubmissions: Long,
)

class RetainedDisplayRenderMetrics {
    private var textureCreations = 0L
    private var textureReleases = 0L
    private var fullTextureUploads = 0L
    private var subrectangleUploads = 0L
    private var uploadedPixels = 0L
    private var batchCreations = 0L
    private var batchReleases = 0L
    private var instanceChunkCompilations = 0L
    private var boundaryFragmentCompilations = 0L
    private var frameSubmissions = 0L

    fun snapshot(): RetainedDisplayRenderMetricsSnapshot =
        RetainedDisplayRenderMetricsSnapshot(
            textureCreations,
            textureReleases,
            fullTextureUploads,
            subrectangleUploads,
            uploadedPixels,
            batchCreations,
            batchReleases,
            instanceChunkCompilations,
            boundaryFragmentCompilations,
            frameSubmissions,
        )

    internal fun recordTextureCreation(pixelCount: Int) {
        textureCreations += 1
        fullTextureUploads += 1
        uploadedPixels += pixelCount
    }

    internal fun recordTextureRelease() {
        textureReleases += 1
    }

    internal fun recordSubrectangleUpload(pixelCount: Int) {
        subrectangleUploads += 1
        uploadedPixels += pixelCount
    }

    internal fun recordBatchCreation() {
        batchCreations += 1
    }

    internal fun recordBatchRelease() {
        batchReleases += 1
    }

    internal fun recordInstanceChunkCompilation() {
        instanceChunkCompilations += 1
    }

    internal fun recordBoundaryFragmentCompilation() {
        boundaryFragmentCompilations += 1
    }

    internal fun recordFrameSubmission() {
        frameSubmissions += 1
    }
}
