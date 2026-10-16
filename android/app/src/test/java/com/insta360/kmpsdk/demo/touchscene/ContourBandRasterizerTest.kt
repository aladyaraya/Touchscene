package com.insta360.kmpsdk.demo.touchscene

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ContourBandRasterizerTest {
    @Test fun circleUnionMatchesOriginalExactlyWithoutFrameFiltering() {
        val random = Random(124)
        for ((w, h) in listOf(1 to 1, 3 to 7, 80 to 64, 64 to 120)) {
            for (radius in listOf(0, 1, 2, 7, 12)) {
                val boundary = BooleanArray(w * h) { random.nextInt(10) < 2 }
                assertArrayEquals("$w x $h radius $radius", ContourHapticMask.build(boundary, w, h, radius),
                    ContourBandRasterizer.build(boundary, w, h, radius))
            }
        }
    }

    @Test fun canvasRectangleAloneDoesNotBecomeAnObjectOutline() {
        val w = 80; val h = 120
        val frame = BooleanArray(w * h) { i -> i % w == 0 || i % w == w - 1 || i / w == 0 || i / w == h - 1 }
        assertFalse(ContourBandRasterizer.build(frame, w, h, 4, ignoreCanvasEdges = true).any { it })
        assertTrue(ContourBandRasterizer.build(frame, w, h, 4).any { it })
    }

    @Test fun realContoursAdjacentToFrameAreNotDiscarded() {
        val w = 40; val h = 80
        val boundary = BooleanArray(w * h)
        for (y in 1 until h - 1) boundary[y * w + 1] = true
        val expected = ContourHapticMask.build(boundary, w, h, 3)
        assertArrayEquals(expected, ContourBandRasterizer.build(boundary, w, h, 3, ignoreCanvasEdges = true))
    }

    @Test fun filteringDoesNotMutateTheOriginalSegmentationBoundary() {
        val boundary = BooleanArray(16 * 32) { it % 3 == 0 }
        val before = boundary.copyOf()
        ContourBandRasterizer.build(boundary, 16, 32, 4, ignoreCanvasEdges = true)
        assertArrayEquals(before, boundary)
    }

    @Test fun emptyAndDenseImagesMatchOriginal() {
        for (filled in listOf(false, true)) for (radius in listOf(0, 3, 15)) {
            val boundary = BooleanArray(20 * 30) { filled }
            assertArrayEquals(ContourHapticMask.build(boundary, 20, 30, radius),
                ContourBandRasterizer.build(boundary, 20, 30, radius))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensionsAreRejected() { ContourBandRasterizer.build(BooleanArray(4), 3, 3, 2) }
}
