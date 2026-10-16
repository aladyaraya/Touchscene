package com.insta360.kmpsdk.demo.touchscene

import kotlin.math.sqrt

/** Display-only circular dilation. GrabCut, its subject mask and parameters stay unchanged. */
internal object ContourBandRasterizer {
    fun build(
        boundary: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
        ignoreCanvasEdges: Boolean = false,
    ): BooleanArray {
        require(width > 0 && height > 0 && boundary.size == width * height && radius >= 0)
        // Each source pixel contributes a horizontal interval per circle row.
        // Prefix sums union those intervals without painting the same area repeatedly.
        val stride = width + 1
        val differences = IntArray(stride * height)
        val halfWidths = IntArray(radius * 2 + 1) { offset ->
            val dy = offset - radius
            sqrt((radius.toLong() * radius - dy.toLong() * dy).toDouble()).toInt()
        }
        boundary.forEachIndexed { index, value ->
            if (!value) return@forEachIndexed
            val x = index % width
            val y = index / width
            // A silhouette cut by the photo canvas is not evidence of an object edge.
            // Keep every in-image boundary, even one only a pixel away from the frame.
            if (ignoreCanvasEdges && (x == 0 || y == 0 || x == width - 1 || y == height - 1)) {
                return@forEachIndexed
            }
            for (dy in maxOf(-radius, -y)..minOf(radius, height - 1 - y)) {
                val halfWidth = halfWidths[dy + radius]
                val row = (y + dy) * stride
                differences[row + maxOf(0, x - halfWidth)]++
                differences[row + minOf(width, x + halfWidth + 1)]--
            }
        }
        return BooleanArray(boundary.size).also { output ->
            for (y in 0 until height) {
                var count = 0
                for (x in 0 until width) {
                    count += differences[y * stride + x]
                    output[y * width + x] = count > 0
                }
            }
        }
    }
}
