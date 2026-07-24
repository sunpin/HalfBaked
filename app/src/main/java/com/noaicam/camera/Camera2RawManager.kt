package com.noaicam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import com.noaicam.data.FlashMode
import com.noaicam.data.RawImageData
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max

enum class FocusStatus {
    IDLE, SEARCHING, LOCKED, FAILED
}

data class CameraLensInfo(
    val lensId: String,
    val label: String,
    val targetFacing: Int,
    val targetZoomRatio: Float
)

class Camera2RawManager(private val context: Context) {

    private val TAG = "Camera2RawManager"

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var rawImageReader: ImageReader? = null
    private var jpegFallbackReader: ImageReader? = null
    private var cameraCharacteristics: CameraCharacteristics? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraOpenCloseLock = Semaphore(1)

    var cameraId: String? = null
        private set
    var activeLensId: String = "WIDE"
        private set
    private var targetPhysicalCameraId: String? = null

    var isRawSupported: Boolean = false
        private set
    var currentIso: Int = 100
        private set
    var currentShutterNanos: Long = 10000000L
        private set
    var sensorAspectRatio: Float = 4f / 3f
        private set

    // Zoom state
    var maxZoomRatio: Float = 8.0f
        private set
    var minZoomRatio: Float = 1.0f
        private set
    var currentZoomRatio: Float = 1.0f
        private set

    // Manual Exposure Control State
    var isManualAeEnabled: Boolean = false
        private set
    var manualIso: Int = 200
    var manualShutterNanos: Long = 10000000L // 1/100s

    // Flash Mode
    var flashMode: FlashMode = FlashMode.OFF
        private set

    var onRawCaptured: ((RawImageData) -> Unit)? = null
    var onCaptureProgress: ((Float, String) -> Unit)? = null
    var onFocusStatusChanged: ((FocusStatus) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private fun postProgress(progress: Float, text: String) {
        mainHandler.post { onCaptureProgress?.invoke(progress, text) }
    }

    private fun postCaptured(rawData: RawImageData) {
        mainHandler.post { onRawCaptured?.invoke(rawData) }
    }

    private fun postFocusStatus(status: FocusStatus) {
        mainHandler.post { onFocusStatusChanged?.invoke(status) }
    }

    private fun postError(error: String) {
        mainHandler.post { onError?.invoke(error) }
    }

    fun getAvailableCameras(): List<CameraLensInfo> {
        val list = mutableListOf<CameraLensInfo>()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)

                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    } else null

                    val minZ = zoomRange?.lower ?: 1.0f

                    var hasPhysicalUltrawide = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        for (pId in chars.physicalCameraIds) {
                            try {
                                val pChars = cameraManager.getCameraCharacteristics(pId)
                                val focal = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4.0f
                                if (focal < 3.0f) {
                                    hasPhysicalUltrawide = true
                                    break
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error checking physical camera $pId", e)
                            }
                        }
                    }

                    if (minZ <= 0.6f || hasPhysicalUltrawide) {
                        list.add(CameraLensInfo("ULTRA_WIDE", "超広角 (0.5x)", CameraCharacteristics.LENS_FACING_BACK, if (minZ <= 0.6f) minZ else 0.5f))
                    }
                    list.add(CameraLensInfo("WIDE", "広角 (1x)", CameraCharacteristics.LENS_FACING_BACK, 1.0f))
                } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    list.add(CameraLensInfo("FRONT", "前面 (インカメラ)", CameraCharacteristics.LENS_FACING_FRONT, 1.0f))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera list", e)
        }

        if (list.isEmpty()) {
            list.add(CameraLensInfo("WIDE", "広角 (1x)", CameraCharacteristics.LENS_FACING_BACK, 1.0f))
        }

        return list.distinctBy { it.lensId }
    }

    fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
        targetLensId: String = "WIDE"
    ) {
        startBackgroundThread()

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                return
            }

            cleanResourcesWithoutLock()
            activeLensId = targetLensId
            targetPhysicalCameraId = null

            val wantFront = (targetLensId == "FRONT")
            val desiredFacing = if (wantFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == desiredFacing) {
                    cameraId = id
                    cameraCharacteristics = characteristics
                    break
                }
            }

            if (cameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
                cameraId = cameraManager.cameraIdList[0]
                cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId!!)
            }

            val logicalChars = cameraCharacteristics ?: run {
                cameraOpenCloseLock.release()
                return
            }

            // Inspect physical camera IDs to bind the exact physical sensor (Wide vs Ultrawide)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && logicalChars.physicalCameraIds.isNotEmpty()) {
                for (pId in logicalChars.physicalCameraIds) {
                    try {
                        val pChars = cameraManager.getCameraCharacteristics(pId)
                        val focal = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4.0f
                        if (targetLensId == "ULTRA_WIDE" && focal < 3.0f) {
                            targetPhysicalCameraId = pId
                            break
                        } else if (targetLensId == "WIDE" && focal >= 3.0f) {
                            targetPhysicalCameraId = pId
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error inspecting physical camera $pId", e)
                    }
                }
            }

            // Use physical camera characteristics if physical ID was bound
            if (targetPhysicalCameraId != null) {
                cameraCharacteristics = cameraManager.getCameraCharacteristics(targetPhysicalCameraId!!)
            }

            val characteristics = cameraCharacteristics!!

            Log.d("NOAICAM_DEBUG", "openCamera: targetLensId=$targetLensId, cameraId=$cameraId, targetPhysicalCameraId=$targetPhysicalCameraId")

            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            isRawSupported = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (range != null) {
                    minZoomRatio = range.lower
                    maxZoomRatio = Math.min(range.upper, 10.0f)
                }
            } else {
                val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8.0f
                minZoomRatio = 1.0f
                maxZoomRatio = Math.min(maxZoom, 10.0f)
            }

            currentZoomRatio = 1.0f.coerceIn(minZoomRatio, maxZoomRatio)

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            setupImageReaders(map)

            surfaceTexture.setDefaultBufferSize(width, height)
            val previewSurface = Surface(surfaceTexture)

            cameraManager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCaptureSession(previewSurface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Camera device open error: $error")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "Error opening camera", e)
        }
    }

    private fun setupImageReaders(map: StreamConfigurationMap?) {
        if (map == null) return

        if (isRawSupported) {
            val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            val largestRaw = rawSizes?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
            rawImageReader = ImageReader.newInstance(
                largestRaw.width, largestRaw.height, ImageFormat.RAW_SENSOR, 2
            )
            sensorAspectRatio = max(largestRaw.width, largestRaw.height).toFloat() / Math.min(largestRaw.width, largestRaw.height).toFloat()
        } else {
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
            val largestJpeg = jpegSizes?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
            jpegFallbackReader = ImageReader.newInstance(
                largestJpeg.width, largestJpeg.height, ImageFormat.JPEG, 2
            )
            sensorAspectRatio = max(largestJpeg.width, largestJpeg.height).toFloat() / Math.min(largestJpeg.width, largestJpeg.height).toFloat()
        }
    }

    private var previewSurfaceRef: Surface? = null

    private fun createCaptureSession(previewSurface: Surface) {
        val device = cameraDevice ?: return
        previewSurfaceRef = previewSurface

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = mutableListOf<OutputConfiguration>()

                val previewConfig = OutputConfiguration(previewSurface)
                targetPhysicalCameraId?.let { previewConfig.setPhysicalCameraId(it) }
                outputConfigs.add(previewConfig)

                rawImageReader?.surface?.let {
                    val rawConfig = OutputConfiguration(it)
                    targetPhysicalCameraId?.let { pid -> rawConfig.setPhysicalCameraId(pid) }
                    outputConfigs.add(rawConfig)
                }

                jpegFallbackReader?.surface?.let {
                    val jpgConfig = OutputConfiguration(it)
                    targetPhysicalCameraId?.let { pid -> jpgConfig.setPhysicalCameraId(pid) }
                    outputConfigs.add(jpgConfig)
                }

                val executor = Executors.newSingleThreadExecutor()
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) return
                            captureSession = session
                            updatePreviewSession()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.w(TAG, "Physical camera OutputConfiguration session failed, falling back...")
                            createFallbackCaptureSession(previewSurface)
                        }
                    }
                )
                device.createCaptureSession(sessionConfig)
                return
            }

            createFallbackCaptureSession(previewSurface)

        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session with OutputConfiguration, falling back", e)
            createFallbackCaptureSession(previewSurface)
        }
    }

    private fun createFallbackCaptureSession(previewSurface: Surface) {
        val device = cameraDevice ?: return
        val surfaces = mutableListOf(previewSurface)
        rawImageReader?.surface?.let { surfaces.add(it) }
        jpegFallbackReader?.surface?.let { surfaces.add(it) }

        try {
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    updatePreviewSession()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Fallback session failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error in createFallbackCaptureSession", e)
        }
    }

    private fun applyExposureControl(builder: CaptureRequest.Builder) {
        if (isManualAeEnabled) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNanos)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
    }

    private fun applyFlashControl(builder: CaptureRequest.Builder, isStillCapture: Boolean = false) {
        when (flashMode) {
            FlashMode.OFF -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            FlashMode.FLASH -> {
                if (isStillCapture) {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            FlashMode.TORCH -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
        } else {
            val characteristics = cameraCharacteristics ?: return
            val activeRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val cropWidth = (activeRect.width() / currentZoomRatio).toInt()
            val cropHeight = (activeRect.height() / currentZoomRatio).toInt()
            val left = activeRect.left + (activeRect.width() - cropWidth) / 2
            val top = activeRect.top + (activeRect.height() - cropHeight) / 2
            val cropRect = Rect(left, top, left + cropWidth, top + cropHeight)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        }
    }

    fun setFlashMode(mode: FlashMode) {
        flashMode = mode
        updatePreviewSession()
    }

    fun setZoomRatio(zoomRatio: Float) {
        currentZoomRatio = zoomRatio.coerceIn(minZoomRatio, maxZoomRatio)
        updatePreviewSession()
    }

    fun setManualAe(enabled: Boolean, iso: Int = manualIso, shutterNanos: Long = manualShutterNanos) {
        isManualAeEnabled = enabled
        manualIso = iso
        manualShutterNanos = shutterNanos
        updatePreviewSession()
    }

    private fun updatePreviewSession() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurfaceRef ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyExposureControl(builder)
            applyFlashControl(builder, isStillCapture = false)
            applyZoom(builder)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            session.setRepeatingRequest(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        super.onCaptureCompleted(session, request, result)
                        currentIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: manualIso
                        currentShutterNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: manualShutterNanos

                        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                        if (afState != null) {
                            when (afState) {
                                CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
                                CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> postFocusStatus(FocusStatus.SEARCHING)

                                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> postFocusStatus(FocusStatus.LOCKED)

                                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> postFocusStatus(FocusStatus.FAILED)
                            }
                        }
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error updating preview session", e)
        }
    }

    fun triggerFocusAt(xNorm: Float, yNorm: Float, viewWidth: Int, viewHeight: Int) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val characteristics = cameraCharacteristics ?: return
        val surface = previewSurfaceRef ?: return

        try {
            postFocusStatus(FocusStatus.SEARCHING)

            val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: Rect(0, 0, viewWidth, viewHeight)

            val areaSize = 220
            val centerX = (xNorm * sensorRect.width()).toInt()
            val centerY = (yNorm * sensorRect.height()).toInt()

            val left = Math.max(centerX - areaSize / 2, sensorRect.left)
            val top = Math.max(centerY - areaSize / 2, sensorRect.top)
            val right = Math.min(centerX + areaSize / 2, sensorRect.right)
            val bottom = Math.min(centerY + areaSize / 2, sensorRect.bottom)

            val focusRect = Rect(left, top, right, bottom)
            val meteringRect = MeteringRectangle(focusRect, 1000)

            val focusBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            focusBuilder.addTarget(surface)
            applyExposureControl(focusBuilder)
            applyFlashControl(focusBuilder, isStillCapture = false)
            applyZoom(focusBuilder)

            focusBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            focusBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            focusBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

            session.capture(focusBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)

                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) {
                        postFocusStatus(FocusStatus.LOCKED)
                    } else if (afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        postFocusStatus(FocusStatus.FAILED)
                    } else {
                        postFocusStatus(FocusStatus.LOCKED)
                    }

                    mainHandler.postDelayed({
                        updatePreviewSession()
                    }, 1500)
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error triggering tap to focus", e)
            postFocusStatus(FocusStatus.FAILED)
        }
    }

    fun takeRawPhoto() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val characteristics = cameraCharacteristics ?: return

        try {
            postProgress(0.15f, "センサー露出中 (RAW撮影)...")

            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            applyExposureControl(captureBuilder)
            applyFlashControl(captureBuilder, isStillCapture = true)
            applyZoom(captureBuilder)

            captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            captureBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            captureBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)

            val dngFile = File(context.cacheDir, "RAW_${System.currentTimeMillis()}.dng")

            if (isRawSupported && rawImageReader != null) {
                captureBuilder.addTarget(rawImageReader!!.surface)

                var capturedImage: Image? = null
                var capturedResult: TotalCaptureResult? = null

                fun processAndSaveDng() {
                    val img = capturedImage ?: return
                    val res = capturedResult ?: return

                    try {
                        postProgress(0.50f, "RAWデータDNGファイルへ保存中...")

                        val dngCreator = DngCreator(characteristics, res)
                        FileOutputStream(dngFile).use { out ->
                            dngCreator.writeImage(out, img)
                        }
                        dngCreator.close()
                        img.close()

                        if (flashMode == FlashMode.FLASH) {
                            updatePreviewSession()
                        }

                        postProgress(0.70f, "リアルタイム自動現像実行中...")

                        postCaptured(
                            RawImageData(
                                dngFilePath = dngFile.absolutePath,
                                iso = currentIso,
                                exposureTimeNanos = currentShutterNanos,
                                isRawHardwareSupported = true
                            )
                        )
                    } catch (e: Exception) {
                        img.close()
                        if (flashMode == FlashMode.FLASH) {
                            updatePreviewSession()
                        }
                        Log.e(TAG, "Error saving RAW DNG", e)
                        postError("RAW保存失敗: ${e.localizedMessage}")
                    }
                }

                rawImageReader!!.setOnImageAvailableListener({ reader ->
                    postProgress(0.35f, "RAWバッファデータ同期中...")
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    capturedImage = img
                    processAndSaveDng()
                }, backgroundHandler)

                session.capture(
                    captureBuilder.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            super.onCaptureCompleted(session, request, result)
                            postProgress(0.45f, "キャプチャ結果メタデータ受信完了")
                            capturedResult = result
                            processAndSaveDng()
                        }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure
                        ) {
                            super.onCaptureFailed(session, request, failure)
                            if (flashMode == FlashMode.FLASH) {
                                updatePreviewSession()
                            }
                            postError("RAWキャプチャ失敗 (hardware error)")
                        }
                    },
                    backgroundHandler
                )

            } else {
                captureBuilder.addTarget(jpegFallbackReader!!.surface)
                val jpgFile = File(context.cacheDir, "RAW_SIMULATED_${System.currentTimeMillis()}.jpg")

                jpegFallbackReader!!.setOnImageAvailableListener({ reader ->
                    postProgress(0.50f, "キャプチャ保存中...")

                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    FileOutputStream(jpgFile).use { it.write(bytes) }
                    image.close()

                    if (flashMode == FlashMode.FLASH) {
                        updatePreviewSession()
                    }

                    postCaptured(
                        RawImageData(
                            dngFilePath = jpgFile.absolutePath,
                            iso = currentIso,
                            exposureTimeNanos = currentShutterNanos,
                            isRawHardwareSupported = false
                        )
                    )
                }, backgroundHandler)

                session.capture(
                    captureBuilder.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            super.onCaptureCompleted(session, request, result)
                        }
                    },
                    backgroundHandler
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error triggering RAW photo capture", e)
            postError("Capture failed: ${e.localizedMessage}")
        }
    }

    private fun cleanResourcesWithoutLock() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            rawImageReader?.close()
            rawImageReader = null
            jpegFallbackReader?.close()
            jpegFallbackReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning resources", e)
        }
    }

    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            cleanResourcesWithoutLock()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
        }
    }
}
