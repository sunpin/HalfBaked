package com.noaicam.data

import android.content.Context
import android.content.SharedPreferences

enum class FlashMode(val label: String) {
    OFF("オフ"),
    FLASH("フラッシュ"),
    TORCH("定常発光")
}

enum class CaptureResolution(val label: String, val scaleFactor: Float) {
    FULL("最高解像度 (RAWフル)", 1.0f),
    MEDIUM("高画質 (12MP相当)", 0.75f),
    COMPACT("標準 (8MP相当)", 0.5f)
}

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("noaicam_settings", Context.MODE_PRIVATE)

    var selectedCameraId: String?
        get() = prefs.getString("selected_camera_id", null)
        set(value) = prefs.edit().putString("selected_camera_id", value).apply()

    var savedZoomRatio: Float
        get() = prefs.getFloat("saved_zoom_ratio", 1.0f)
        set(value) = prefs.edit().putFloat("saved_zoom_ratio", value).apply()

    var captureResolution: CaptureResolution
        get() {
            val name = prefs.getString("capture_resolution", CaptureResolution.FULL.name)
            return try { CaptureResolution.valueOf(name!!) } catch (e: Exception) { CaptureResolution.FULL }
        }
        set(value) = prefs.edit().putString("capture_resolution", value.name).apply()

    var isManualAe: Boolean
        get() = prefs.getBoolean("is_manual_ae", false)
        set(value) = prefs.edit().putBoolean("is_manual_ae", value).apply()

    var manualIso: Int
        get() = prefs.getInt("manual_iso", 200)
        set(value) = prefs.edit().putInt("manual_iso", value).apply()

    var manualShutterNanos: Long
        get() = prefs.getLong("manual_shutter_nanos", 10000000L)
        set(value) = prefs.edit().putLong("manual_shutter_nanos", value).apply()

    var flashMode: FlashMode
        get() {
            val name = prefs.getString("flash_mode", FlashMode.OFF.name)
            return try { FlashMode.valueOf(name!!) } catch (e: Exception) { FlashMode.OFF }
        }
        set(value) = prefs.edit().putString("flash_mode", value.name).apply()

    var lastGalleryTab: Int
        get() = prefs.getInt("last_gallery_tab", 1) // Default to 1 (JPG tab)
        set(value) = prefs.edit().putInt("last_gallery_tab", value).apply()

    var isWbAuto: Boolean
        get() = prefs.getBoolean("develop_wb_auto", true)
        set(value) = prefs.edit().putBoolean("develop_wb_auto", value).apply()

    var isNoiseReductionEnabled: Boolean
        get() = prefs.getBoolean("develop_noise_reduction", false)
        set(value) = prefs.edit().putBoolean("develop_noise_reduction", value).apply()

    var exposure: Float
        get() = prefs.getFloat("develop_exposure", 0f)
        set(value) = prefs.edit().putFloat("develop_exposure", value).apply()

    var temperature: Float
        get() = prefs.getFloat("develop_temperature", 5500f)
        set(value) = prefs.edit().putFloat("develop_temperature", value).apply()

    var tint: Float
        get() = prefs.getFloat("develop_tint", 0f)
        set(value) = prefs.edit().putFloat("develop_tint", value).apply()

    var contrast: Float
        get() = prefs.getFloat("develop_contrast", 1.0f)
        set(value) = prefs.edit().putFloat("develop_contrast", value).apply()

    var blackLevel: Float
        get() = prefs.getFloat("develop_black_level", 0f)
        set(value) = prefs.edit().putFloat("develop_black_level", value).apply()

    var whiteLevel: Float
        get() = prefs.getFloat("develop_white_level", 0f)
        set(value) = prefs.edit().putFloat("develop_white_level", value).apply()

    var saturation: Float
        get() = prefs.getFloat("develop_saturation", 1.0f)
        set(value) = prefs.edit().putFloat("develop_saturation", value).apply()

    var sharpness: Float
        get() = prefs.getFloat("develop_sharpness", 1.0f)
        set(value) = prefs.edit().putFloat("develop_sharpness", value).apply()

    var cropScale: Float
        get() = prefs.getFloat("develop_crop_scale", 1.0f)
        set(value) = prefs.edit().putFloat("develop_crop_scale", value).apply()

    var cropPanX: Float
        get() = prefs.getFloat("develop_crop_pan_x", 0f)
        set(value) = prefs.edit().putFloat("develop_crop_pan_x", value).apply()

    var cropPanY: Float
        get() = prefs.getFloat("develop_crop_pan_y", 0f)
        set(value) = prefs.edit().putFloat("develop_crop_pan_y", value).apply()

    var zoomCropMode: ZoomCropMode
        get() {
            val name = prefs.getString("zoom_crop_mode", ZoomCropMode.CROP_UPSCALE.name)
            return try { ZoomCropMode.valueOf(name!!) } catch (e: Exception) { ZoomCropMode.CROP_UPSCALE }
        }
        set(value) = prefs.edit().putString("zoom_crop_mode", value.name).apply()

    var showGrid: Boolean
        get() = prefs.getBoolean("show_grid", false)
        set(value) = prefs.edit().putBoolean("show_grid", value).apply()

    fun getDevelopParams(): DevelopParams {
        return DevelopParams(
            isWbAuto = isWbAuto,
            isNoiseReductionEnabled = isNoiseReductionEnabled,
            exposure = exposure,
            temperature = temperature,
            tint = tint,
            contrast = contrast,
            blackLevel = blackLevel,
            whiteLevel = whiteLevel,
            saturation = saturation,
            sharpness = sharpness,
            cropScale = cropScale,
            cropPanX = cropPanX,
            cropPanY = cropPanY,
            zoomCropMode = zoomCropMode
        )
    }

    fun saveDevelopParams(params: DevelopParams) {
        isWbAuto = params.isWbAuto
        isNoiseReductionEnabled = params.isNoiseReductionEnabled
        exposure = params.exposure
        temperature = params.temperature
        tint = params.tint
        contrast = params.contrast
        blackLevel = params.blackLevel
        whiteLevel = params.whiteLevel
        saturation = params.saturation
        sharpness = params.sharpness
        cropScale = params.cropScale
        cropPanX = params.cropPanX
        cropPanY = params.cropPanY
        zoomCropMode = params.zoomCropMode
    }
}
