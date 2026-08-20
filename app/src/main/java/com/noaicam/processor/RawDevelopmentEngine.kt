package com.noaicam.processor

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.noaicam.data.DevelopEffect
import com.noaicam.data.DevelopParams
import com.noaicam.data.ZoomCropMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.pow

class RawDevelopmentEngine(private val context: Context) {

    private val TAG = "RawDevelopmentEngine"

    suspend fun decodeRawToBitmap(filePath: String, maxDimension: Int = 8192): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@withContext null

                var decodedBitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(file)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.isMutableRequired = true
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE

                        val size = info.size
                        val maxSide = Math.max(size.width, size.height)
                        if (maxSide > maxDimension) {
                            val sample = Math.round(maxSide.toFloat() / maxDimension)
                            if (sample > 1) {
                                decoder.setTargetSampleSize(sample)
                            }
                        }
                    }
                } else {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(filePath, options)

                    val maxSide = Math.max(options.outWidth, options.outHeight)
                    var sampleSize = 1
                    if (maxSide > maxDimension) {
                        sampleSize = Math.round(maxSide.toFloat() / maxDimension)
                    }

                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inMutable = true
                    }
                    BitmapFactory.decodeFile(filePath, decodeOptions)
                }

                // Check EXIF Orientation from DNG file and rotate bitmap physically to ensure Portrait orientation when shot upright
                if (decodedBitmap != null) {
                    try {
                        val exif = ExifInterface(filePath)
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )

                        val rotationDegrees = when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }

                        if (rotationDegrees != 0) {
                            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                            val rotated = Bitmap.createBitmap(
                                decodedBitmap, 0, 0,
                                decodedBitmap.width, decodedBitmap.height,
                                matrix, true
                            )
                            if (rotated != decodedBitmap) {
                                decodedBitmap.recycle()
                            }
                            decodedBitmap = rotated
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error checking EXIF orientation on RAW file", e)
                    }
                }

                decodedBitmap
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding RAW file", e)
                null
            }
        }

    suspend fun processBitmap(
        baseBitmap: Bitmap,
        params: DevelopParams,
        zoomRatio: Float = 1.0f
    ): Bitmap =
        withContext(Dispatchers.Default) {
            // 1. Apply Arbitrary Pan/Zoom Cropping & Upscaling for Post-Development
            val effectiveScale = if (params.cropScale > 1.02f) params.cropScale else zoomRatio
            val sourceBitmap = if (effectiveScale > 1.05f && params.zoomCropMode != ZoomCropMode.OFF) {
                val origW = baseBitmap.width
                val origH = baseBitmap.height
                val cropW = (origW / effectiveScale).toInt().coerceIn(10, origW)
                val cropH = (origH / effectiveScale).toInt().coerceIn(10, origH)

                val maxShiftX = (origW - cropW) / 2
                val maxShiftY = (origH - cropH) / 2

                val shiftX = (params.cropPanX * maxShiftX).toInt()
                val shiftY = (params.cropPanY * maxShiftY).toInt()

                val centerX = origW / 2 + shiftX
                val centerY = origH / 2 + shiftY

                val cropX = (centerX - cropW / 2).coerceIn(0, origW - cropW)
                val cropY = (centerY - cropH / 2).coerceIn(0, origH - cropH)

                val cropped = Bitmap.createBitmap(baseBitmap, cropX, cropY, cropW, cropH)

                if (params.zoomCropMode == ZoomCropMode.CROP_UPSCALE) {
                    Bitmap.createScaledBitmap(cropped, origW, origH, true)
                } else {
                    cropped
                }
            } else {
                baseBitmap
            }

            val resultBitmap = Bitmap.createBitmap(
                sourceBitmap.width,
                sourceBitmap.height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            val matrix = ColorMatrix()

            // 2. Exposure Compensation
            val expMult = 2.0f.pow(params.exposure)

            // 3. White Balance / Temperature & Tint
            val tempFactor = if (params.isWbAuto) 0f else (params.temperature - 5500f) / 4500f
            val redGain = (1.0f + tempFactor * 0.3f) * expMult
            val blueGain = (1.0f - tempFactor * 0.3f) * expMult
            val greenGain = (1.0f + (params.tint / 100f) * 0.2f) * expMult

            val expTempMatrix = ColorMatrix(
                floatArrayOf(
                    redGain, 0f, 0f, 0f, 0f,
                    0f, greenGain, 0f, 0f, 0f,
                    0f, 0f, blueGain, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            matrix.postConcat(expTempMatrix)

            // 4. Contrast adjustment
            val c = params.contrast
            val translate = 128f * (1f - c)
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, translate,
                    0f, c, 0f, 0f, translate,
                    0f, 0f, c, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            matrix.postConcat(contrastMatrix)

            // 5. Black Level & White Level
            val blOffset = params.blackLevel * 35f
            val wlScale = 1.0f + params.whiteLevel * 0.4f

            val bwMatrix = ColorMatrix(
                floatArrayOf(
                    wlScale, 0f, 0f, 0f, blOffset,
                    0f, wlScale, 0f, 0f, blOffset,
                    0f, 0f, wlScale, 0f, blOffset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            matrix.postConcat(bwMatrix)

            // 6. Saturation adjustment
            val satMatrix = ColorMatrix()
            satMatrix.setSaturation(params.saturation)
            matrix.postConcat(satMatrix)

            paint.colorFilter = ColorMatrixColorFilter(matrix)
            canvas.drawBitmap(sourceBitmap, 0f, 0f, paint)

            // 7. Noise Reduction Filter (if ON)
            val nrBitmap = if (params.isNoiseReductionEnabled) {
                applyNoiseReductionFilter(resultBitmap)
            } else {
                resultBitmap
            }

            // 8. Sharpness (Convolution sharpening if > 1.05)
            val sharpenedBitmap = if (params.sharpness > 1.05f) {
                applySharpeningFilter(nrBitmap, params.sharpness)
            } else {
                nrBitmap
            }

            // 9. Art Effect Filters (if selected)
            if (params.effect != DevelopEffect.NONE) {
                applyArtEffect(sharpenedBitmap, params.effect, params.effectIntensity)
            } else {
                sharpenedBitmap
            }
        }

    private fun applyArtEffect(src: Bitmap, effect: DevelopEffect, intensity: Float): Bitmap {
        return when (effect) {
            DevelopEffect.GRAVURE -> applyCmykGravureHalftoneEffect(src, intensity)
            DevelopEffect.SUPER_PORTRAIT -> applySuperPortraitEffect(src, intensity)
            DevelopEffect.OIL_PAINT -> applyImpastoOilPaintEffect(src, intensity)
            DevelopEffect.PEN_SKETCH -> applyCrossHatchPenSketchEffect(src, intensity)
            DevelopEffect.ANIME -> applyCelShadedAnimeEffect(src, intensity)
            DevelopEffect.RETRO_FILM -> applyRetroFilmEffect(src, intensity)
            DevelopEffect.NOIR -> applyNoirEffect(src, intensity)
            DevelopEffect.NONE -> src
        }
    }

    // 1. グラビア調 (網点印刷 - Authentic CMYK Halftone Screen Printing)
    private fun applyCmykGravureHalftoneEffect(src: Bitmap, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Off-white paper background
        canvas.drawColor(Color.rgb(250, 248, 242))

        val step = Math.max(5, (Math.min(w, h) / 130 * intensity).toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val maxRadius = step * 0.75f

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0

                for (dy in 0 until step) {
                    val py = y + dy
                    if (py >= h) break
                    val rowOffset = py * w
                    for (dx in 0 until step) {
                        val px = x + dx
                        if (px >= w) break
                        val c = pixels[rowOffset + px]
                        sumR += Color.red(c)
                        sumG += Color.green(c)
                        sumB += Color.blue(c)
                        count++
                    }
                }

                if (count > 0) {
                    val r = sumR.toFloat() / (count * 255f)
                    val g = sumG.toFloat() / (count * 255f)
                    val b = sumB.toFloat() / (count * 255f)

                    val k = 1.0f - Math.max(r, Math.max(g, b))
                    val c = if (k < 1.0f) (1.0f - r - k) / (1.0f - k) else 0f
                    val m = if (k < 1.0f) (1.0f - g - k) / (1.0f - k) else 0f
                    val yCol = if (k < 1.0f) (1.0f - b - k) / (1.0f - k) else 0f

                    val centerX = x + step / 2f
                    val centerY = y + step / 2f

                    // Cyan Dot
                    if (c > 0.05f) {
                        paint.color = Color.argb(190, 0, 180, 220)
                        canvas.drawCircle(centerX - step * 0.1f, centerY - step * 0.1f, c * maxRadius, paint)
                    }
                    // Magenta Dot
                    if (m > 0.05f) {
                        paint.color = Color.argb(190, 220, 0, 140)
                        canvas.drawCircle(centerX + step * 0.1f, centerY - step * 0.1f, m * maxRadius, paint)
                    }
                    // Yellow Dot
                    if (yCol > 0.05f) {
                        paint.color = Color.argb(190, 230, 210, 0)
                        canvas.drawCircle(centerX, centerY + step * 0.1f, yCol * maxRadius, paint)
                    }
                    // Black Dot (K)
                    if (k > 0.05f) {
                        paint.color = Color.argb(220, 30, 30, 30)
                        canvas.drawCircle(centerX, centerY, k * maxRadius, paint)
                    }
                }
            }
        }
        return output
    }

    // 2. スーパーポートレート (美肌 / ソフトグロウ Orton Bloom)
    private fun applySuperPortraitEffect(src: Bitmap, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.drawBitmap(src, 0f, 0f, paint)

        val scale = 0.25f
        val small = Bitmap.createScaledBitmap(src, Math.max(1, (w * scale).toInt()), Math.max(1, (h * scale).toInt()), true)
        val blurred = Bitmap.createScaledBitmap(small, w, h, true)
        small.recycle()

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        paint.alpha = (120 * intensity).toInt().coerceIn(10, 220)
        canvas.drawBitmap(blurred, 0f, 0f, paint)
        paint.xfermode = null
        paint.alpha = 255
        blurred.recycle()

        val warmMatrix = ColorMatrix(
            floatArrayOf(
                1.08f, 0f, 0f, 0f, 8f,
                0f, 1.02f, 0f, 0f, 4f,
                0f, 0f, 0.95f, 0f, 0f,
                0f, 0f, 0.95f, 1f, 0f
            )
        )
        val finalBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val finalCanvas = Canvas(finalBmp)
        paint.colorFilter = ColorMatrixColorFilter(warmMatrix)
        finalCanvas.drawBitmap(output, 0f, 0f, paint)
        output.recycle()

        return finalBmp
    }

    // 3. 油絵調 (オイルペイント - 画面全体の色をクラスタ分け＋同じ色の領域を筆で塗る)
    private fun applyImpastoOilPaintEffect(src: Bitmap, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Draw canvas base
        canvas.drawColor(Color.rgb(240, 238, 230))

        // 1. Screen-wide Color Clustering (画面全体の色をクラスタ分け)
        val numClusters = 6
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val clusteredPixels = IntArray(w * h)

        for (i in pixels.indices) {
            val c = pixels[i]
            var r = Color.red(c)
            var g = Color.green(c)
            var b = Color.blue(c)

            r = (Math.round(r.toFloat() / 255f * numClusters) * (255 / numClusters)).coerceIn(0, 255)
            g = (Math.round(g.toFloat() / 255f * numClusters) * (255 / numClusters)).coerceIn(0, 255)
            b = (Math.round(b.toFloat() / 255f * numClusters) * (255 / numClusters)).coerceIn(0, 255)

            clusteredPixels[i] = Color.rgb(r, g, b)
        }

        // 2. Region Paint with Brush (同じ色の領域を太い筆で塗る)
        val step = Math.max(10, (24 * intensity).toInt())
        val strokeWidth = step * 1.5f
        val strokeLen = step * 2.6f

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }

        val stepStep = Math.max(5, (step * 0.65f).toInt())

        for (y in step / 2 until h step stepStep) {
            val rowOffset = y * w
            for (x in step / 2 until w step stepStep) {
                val offset = rowOffset + x
                if (offset >= clusteredPixels.size) continue

                val clusterColor = clusteredPixels[offset]

                // Calculate gradient direction to lay down brush strokes following region contours
                val x1 = Math.min(x + 3, w - 1)
                val y1 = Math.min(y + 3, h - 1)
                val c0 = clusteredPixels[offset]
                val cR = clusteredPixels[rowOffset + x1]
                val cD = clusteredPixels[y1 * w + x]

                val l0 = (Color.red(c0) * 2 + Color.green(c0) * 5 + Color.blue(c0)) shr 3
                val lR = (Color.red(cR) * 2 + Color.green(cR) * 5 + Color.blue(cR)) shr 3
                val lD = (Color.red(cD) * 2 + Color.green(cD) * 5 + Color.blue(cD)) shr 3

                val gx = (lR - l0).toFloat()
                val gy = (lD - l0).toFloat()
                val angle = Math.atan2(gy.toDouble(), gx.toDouble()).toFloat() + (Math.PI / 2).toFloat()

                val dx = (Math.cos(angle.toDouble()) * strokeLen / 2).toFloat()
                val dy = (Math.sin(angle.toDouble()) * strokeLen / 2).toFloat()

                // Paint the clustered color region with overlapping oil brush strokes
                strokePaint.color = clusterColor
                strokePaint.strokeWidth = strokeWidth
                canvas.drawLine(x - dx, y - dy, x + dx, y + dy, strokePaint)

                // Subtle impasto highlight sheen on stroke
                strokePaint.color = Color.argb(40, 255, 255, 255)
                strokePaint.strokeWidth = strokeWidth * 0.22f
                canvas.drawLine(x - dx - 2f, y - dy - 2f, x + dx - 2f, y + dy - 2f, strokePaint)
            }
        }
        return output
    }

    // 4. ペン画調 (カラーペン画 - 純黒ペン色(Black)で下地の上に明度(クロスハッチ)と輪郭を描く)
    private fun applyCrossHatchPenSketchEffect(src: Bitmap, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 1. Soft blurred color wash base preserving original HUE & SATURATION
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val basePixels = IntArray(w * h)
        val hsv = FloatArray(3)

        for (i in pixels.indices) {
            val c = pixels[i]
            Color.colorToHSV(c, hsv)
            hsv[2] = 0.88f // High uniform Value for soft watercolor wash
            basePixels[i] = Color.HSVToColor(hsv)
        }

        val baseBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        baseBmp.setPixels(basePixels, 0, w, 0, 0, w, h)

        val scale = 0.5f
        val small = Bitmap.createScaledBitmap(baseBmp, Math.max(1, (w * scale).toInt()), Math.max(1, (h * scale).toInt()), true)
        val blurredBase = Bitmap.createScaledBitmap(small, w, h, true)
        small.recycle()
        baseBmp.recycle()

        canvas.drawBitmap(blurredBase, 0f, 0f, paint)
        blurredBase.recycle()

        // 2. Pure Black Ink Pen (`Color.BLACK`) for Outlines and Cross-Hatch Value Shading
        val step = Math.max(6, (12 * intensity).toInt())
        val blackPenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = Math.max(1.3f, 2.0f * intensity)
            style = Paint.Style.STROKE
        }

        // A. Cross-Hatching for Value / Luminance Shading
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                var sumLum = 0
                var count = 0
                for (dy in 0 until step) {
                    val py = y + dy
                    if (py >= h) break
                    val rowOffset = py * w
                    for (dx in 0 until step) {
                        val px = x + dx
                        if (px >= w) break
                        val c = pixels[rowOffset + px]
                        val lum = (Color.red(c) * 2 + Color.green(c) * 5 + Color.blue(c)) shr 3
                        sumLum += lum
                        count++
                    }
                }

                if (count > 0) {
                    val avgLum = sumLum / count

                    val x0 = x.toFloat()
                    val y0 = y.toFloat()
                    val x1 = (x + step).toFloat()
                    val y1 = (y + step).toFloat()

                    if (avgLum < 205) {
                        canvas.drawLine(x0, y1, x1, y0, blackPenPaint)
                    }
                    if (avgLum < 145) {
                        canvas.drawLine(x0, y0, x1, y1, blackPenPaint)
                    }
                    if (avgLum < 90) {
                        canvas.drawLine(x0, y0 + step / 2f, x1, y0 + step / 2f, blackPenPaint)
                    }
                    if (avgLum < 45) {
                        canvas.drawLine(x0 + step / 2f, y0, x0 + step / 2f, y1, blackPenPaint)
                    }
                }
            }
        }

        // B. Pure Black Ink Contour Outlines (輪郭)
        val thresh = (20f / intensity).coerceIn(8f, 45f)
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = Math.max(1.5f, 2.2f * intensity)
            style = Paint.Style.STROKE
        }

        for (y in 1 until h - 1 step 2) {
            val offset = y * w
            for (x in 1 until w - 1 step 2) {
                val c0 = pixels[offset + x]
                val cR = pixels[offset + x + 1]
                val cD = pixels[offset + w + x]

                val l0 = (Color.red(c0) * 2 + Color.green(c0) * 5 + Color.blue(c0)) shr 3
                val lR = (Color.red(cR) * 2 + Color.green(cR) * 5 + Color.blue(cR)) shr 3
                val lD = (Color.red(cD) * 2 + Color.green(cD) * 5 + Color.blue(cD)) shr 3

                if (Math.abs(l0 - lR) + Math.abs(l0 - lD) > thresh) {
                    canvas.drawPoint(x.toFloat(), y.toFloat(), outlinePaint)
                }
            }
        }

        return output
    }

    // 5. アニメ調 (セル画風 Cel-Shaded Anime)
    private fun applyCelShadedAnimeEffect(src: Bitmap, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val outPixels = IntArray(w * h)

        val steps = 5
        val darkLineColor = Color.rgb(20, 20, 25)
        val thresh = (25f / intensity).coerceIn(10f, 60f)

        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                val c = pixels[offset + x]
                var r = Color.red(c)
                var g = Color.green(c)
                var b = Color.blue(c)

                // Quantize RGB colors into discrete cel-shading bands
                r = (Math.round(r.toFloat() / 255f * steps) * (255 / steps)).coerceIn(0, 255)
                g = (Math.round(g.toFloat() / 255f * steps) * (255 / steps)).coerceIn(0, 255)
                b = (Math.round(b.toFloat() / 255f * steps) * (255 / steps)).coerceIn(0, 255)

                if (x > 0 && x < w - 1 && y > 0 && y < h - 1) {
                    val cR = pixels[offset + x + 1]
                    val cD = pixels[offset + w + x]
                    val l0 = (Color.red(c) * 2 + Color.green(c) * 5 + Color.blue(c)) shr 3
                    val lR = (Color.red(cR) * 2 + Color.green(cR) * 5 + Color.blue(cR)) shr 3
                    val lD = (Color.red(cD) * 2 + Color.green(cD) * 5 + Color.blue(cD)) shr 3

                    if (Math.abs(l0 - lR) + Math.abs(l0 - lD) > thresh) {
                        outPixels[offset + x] = darkLineColor
                        continue
                    }
                }

                outPixels[offset + x] = Color.rgb(r, g, b)
            }
        }

        output.setPixels(outPixels, 0, w, 0, 0, w, h)

        // Boost saturation for vivid anime look
        val finalBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val finalCanvas = Canvas(finalBmp)
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.4f)
        paint.colorFilter = ColorMatrixColorFilter(satMatrix)
        finalCanvas.drawBitmap(output, 0f, 0f, paint)
        output.recycle()

        return finalBmp
    }

    // 6. レトロフィルム (トイカメラ Lomo Vignette)
    private fun applyRetroFilmEffect(src: Bitmap, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Vintage warm tint
        val retroMatrix = ColorMatrix(
            floatArrayOf(
                1.15f, 0f, 0f, 0f, 10f,
                0f, 1.05f, 0f, 0f, 5f,
                0f, 0f, 0.88f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(retroMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        paint.colorFilter = null

        // Radial Vignette mask on corners
        val radius = Math.hypot(w / 2.0, h / 2.0).toFloat()
        val darkAlpha = (210 * intensity).toInt().coerceIn(50, 255)
        val vignetteShader = RadialGradient(
            w / 2f, h / 2f, radius,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(darkAlpha, 0, 0, 0)),
            floatArrayOf(0.0f, 0.50f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = vignetteShader
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        return output
    }

    // 7. モノクロノワール (高コントラスト白黒 Noir)
    private fun applyNoirEffect(src: Bitmap, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val noirMatrix = ColorMatrix()
        noirMatrix.setSaturation(0f)

        val contrast = 1.35f + 0.35f * intensity
        val translate = 128f * (1f - contrast) - 15f
        val highContrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        noirMatrix.postConcat(highContrastMatrix)

        paint.colorFilter = ColorMatrixColorFilter(noirMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)

        return output
    }

    private fun applyNoiseReductionFilter(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val scaledW = Math.max(1, w / 2)
        val scaledH = Math.max(1, h / 2)
        val downscaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val smooth = Bitmap.createScaledBitmap(downscaled, w, h, true)
        downscaled.recycle()

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, 0f, 0f, paint)
        paint.alpha = 140
        canvas.drawBitmap(smooth, 0f, 0f, paint)
        smooth.recycle()
        return output
    }

    private fun applySharpeningFilter(src: Bitmap, sharpness: Float): Bitmap {
        val amount = (sharpness - 1.0f) * 0.75f

        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, src.config ?: Bitmap.Config.ARGB_8888)

        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, 0f, 0f, paint)

        return output
    }

    suspend fun saveDevelopedPhotoToGallery(bitmap: Bitmap): Uri? =
        withContext(Dispatchers.IO) {
            val filename = "NOAICAM_DEV_${System.currentTimeMillis()}.jpg"
            var imageUri: Uri? = null

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DCIM + "/NOAICAM"
                        )
                    }

                    val resolver = context.contentResolver
                    imageUri = resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    )

                    if (imageUri != null) {
                        resolver.openOutputStream(imageUri).use { out ->
                            if (out != null) {
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
                            }
                        }
                    }
                } else {
                    val dcimDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "NOAICAM"
                    )
                    if (!dcimDir.exists()) dcimDir.mkdirs()
                    val file = File(dcimDir, filename)
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
                    }
                    imageUri = Uri.fromFile(file)
                }

                Log.d(TAG, "Developed photo successfully saved to gallery: $imageUri (Size: ${bitmap.width}x${bitmap.height})")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving developed photo to gallery", e)
            }

            imageUri
        }
}
