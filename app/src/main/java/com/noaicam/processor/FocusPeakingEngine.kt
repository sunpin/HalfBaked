package com.noaicam.processor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset

class FocusPeakingEngine {

    /**
     * Fast luminance-based Sobel/gradient edge detector.
     * Returns list of normalized or pixel offsets where high-contrast in-focus edges are detected.
     */
    fun detectInFocusEdgeOffsets(bitmap: Bitmap, threshold: Int = 40): List<Offset> {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 20 || height < 20) return emptyList()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val edgeOffsets = ArrayList<Offset>(2500)
        val step = 3 // Subsample grid for 60fps performance

        for (y in step until height - step step step) {
            val rowOffset = y * width
            for (x in step until width - step step step) {
                val idx = rowOffset + x

                val p = pixels[idx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                val pRight = pixels[idx + step]
                val rR = (pRight shr 16) and 0xFF
                val gR = (pRight shr 8) and 0xFF
                val bR = pRight and 0xFF
                val lumRight = (rR * 299 + gR * 587 + bR * 114) / 1000

                val pBottom = pixels[idx + step * width]
                val rB = (pBottom shr 16) and 0xFF
                val gB = (pBottom shr 8) and 0xFF
                val bB = pBottom and 0xFF
                val lumBottom = (rB * 299 + gB * 587 + bB * 114) / 1000

                val dx = Math.abs(lum - lumRight)
                val dy = Math.abs(lum - lumBottom)

                if (dx + dy > threshold) {
                    edgeOffsets.add(Offset(x.toFloat() / width, y.toFloat() / height))
                }
            }
        }
        return edgeOffsets
    }
}
