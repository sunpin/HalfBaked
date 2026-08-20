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
            // 1. Apply Zoom Cropping / Upscaling if zoomed and enabled
            val sourceBitmap = if (zoomRatio > 1.05f && params.zoomCropMode != ZoomCropMode.OFF) {
                val origW = baseBitmap.width
                val origH = baseBitmap.height
                val cropW = (origW / zoomRatio).toInt().coerceIn(10, origW)
                val cropH = (origH / zoomRatio).toInt().coerceIn(10, origH)
                val cropX = ((origW - cropW) / 2).coerceIn(0, origW - cropW)
                val cropY = ((origH - cropH) / 2).coerceIn(0, origH - cropH)

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
            if (params.sharpness > 1.05f) {
                applySharpeningFilter(nrBitmap, params.sharpness)
            } else {
                nrBitmap
            }
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
