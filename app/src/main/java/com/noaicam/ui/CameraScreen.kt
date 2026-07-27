package com.noaicam.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.noaicam.camera.Camera2RawManager
import com.noaicam.camera.CameraLensInfo
import com.noaicam.camera.FocusStatus
import com.noaicam.data.CaptureResolution
import com.noaicam.data.DevelopParams
import com.noaicam.data.FlashMode
import com.noaicam.data.RawImageData
import com.noaicam.data.SettingsManager
import com.noaicam.data.ZoomCropMode
import com.noaicam.processor.RawDevelopmentEngine
import com.noaicam.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

val ISO_OPTIONS = listOf(100, 200, 400, 800, 1600, 3200, 6400)
val SHUTTER_OPTIONS = listOf(
    1000000L to "1/1000s",
    2000000L to "1/500s",
    4000000L to "1/250s",
    8000000L to "1/125s",
    16666666L to "1/60s",
    33333333L to "1/30s",
    66666666L to "1/15s",
    125000000L to "1/8s",
    250000000L to "1/4s",
    500000000L to "1/2s",
    1000000000L to "1s"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onOpenGallery: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val cameraManager = remember { Camera2RawManager(context) }
    val rawEngine = remember { RawDevelopmentEngine(context) }
    val settingsManager = remember { SettingsManager(context) }

    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    var isCapturing by remember { mutableStateOf(false) }
    var captureProgress by remember { mutableStateOf(0f) }
    var captureStatusText by remember { mutableStateOf("RAWキャプチャ中...") }

    var currentIso by remember { mutableIntStateOf(100) }
    var currentShutter by remember { mutableStateOf("1/50s") }
    var isRawHardwareSupported by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Multi-Camera Lens Selection State
    var availableLenses by remember { mutableStateOf<List<CameraLensInfo>>(emptyList()) }
    var activeLensId by remember { mutableStateOf(settingsManager.selectedCameraId ?: "WIDE") }

    // PERSISTENT USER SETTINGS
    var showGrid by remember { mutableStateOf(settingsManager.showGrid) }
    var flashMode by remember { mutableStateOf(settingsManager.flashMode) }
    var captureResolution by remember { mutableStateOf(settingsManager.captureResolution) }
    var isManualAe by remember { mutableStateOf(settingsManager.isManualAe) }
    var selectedIso by remember { mutableIntStateOf(settingsManager.manualIso) }
    var selectedShutterNanos by remember { mutableLongStateOf(settingsManager.manualShutterNanos) }
    var developParams by remember { mutableStateOf(settingsManager.getDevelopParams()) }

    // Pinch-to-Zoom State (PERSISTED across library transitions & app navigation)
    var zoomRatio by remember { mutableFloatStateOf(settingsManager.savedZoomRatio) }

    var showManualAeDrawer by remember { mutableStateOf(false) }
    var showSliders by remember { mutableStateOf(false) }

    // Tap-to-Focus Indicator State & Focus Status
    var focusTapOffset by remember { mutableStateOf<Offset?>(null) }
    var focusStatus by remember { mutableStateOf(FocusStatus.IDLE) }

    // Real-time Viewfinder ColorMatrix Simulation based on Live Develop Parameters
    val liveColorMatrix = remember(developParams) {
        val matrix = ColorMatrix()

        val expMult = 2.0f.pow(developParams.exposure)
        val tempFactor = if (developParams.isWbAuto) 0f else (developParams.temperature - 5500f) / 4500f
        val redGain = (1.0f + tempFactor * 0.3f) * expMult
        val blueGain = (1.0f - tempFactor * 0.3f) * expMult
        val greenGain = (1.0f + (developParams.tint / 100f) * 0.2f) * expMult

        val expTempMatrix = ColorMatrix(
            floatArrayOf(
                redGain, 0f, 0f, 0f, 0f,
                0f, greenGain, 0f, 0f, 0f,
                0f, 0f, blueGain, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        matrix.timesAssign(expTempMatrix)

        val c = developParams.contrast
        val translate = 128f * (1f - c)
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, translate,
                0f, c, 0f, 0f, translate,
                0f, 0f, c, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        matrix.timesAssign(contrastMatrix)

        val blOffset = developParams.blackLevel * 35f
        val wlScale = 1.0f + developParams.whiteLevel * 0.4f

        val bwMatrix = ColorMatrix(
            floatArrayOf(
                wlScale, 0f, 0f, 0f, blOffset,
                0f, wlScale, 0f, 0f, blOffset,
                0f, 0f, wlScale, 0f, blOffset,
                0f, 0f, 0f, 1f, 0f
            )
        )
        matrix.timesAssign(bwMatrix)

        val satMatrix = ColorMatrix()
        satMatrix.setToSaturation(developParams.saturation)
        matrix.timesAssign(satMatrix)

        matrix
    }

    LaunchedEffect(developParams) {
        settingsManager.saveDevelopParams(developParams)
    }

    // Discover available camera lenses on launch
    LaunchedEffect(Unit) {
        val lenses = cameraManager.getAvailableCameras()
        availableLenses = lenses
        if (settingsManager.selectedCameraId == null && lenses.isNotEmpty()) {
            activeLensId = lenses[0].lensId
            settingsManager.selectedCameraId = lenses[0].lensId
        }
    }

    // Function to switch camera lens (Resets zoom to 1.0f when changing lens)
    fun switchCameraLens(lensId: String) {
        activeLensId = lensId
        settingsManager.selectedCameraId = lensId
        zoomRatio = 1.0f // Reset zoom when explicitly changing camera lens!
        settingsManager.savedZoomRatio = 1.0f

        val tv = textureViewRef
        if (tv != null && tv.isAvailable) {
            val surface = tv.surfaceTexture
            if (surface != null) {
                cameraManager.openCamera(surface, tv.width, tv.height, lensId)
                cameraManager.setZoomRatio(1.0f)
                cameraManager.setManualAe(isManualAe, selectedIso, selectedShutterNanos)
                cameraManager.setFlashMode(flashMode)
                isRawHardwareSupported = cameraManager.isRawSupported
            }
        }
    }

    // Last Captured Developed JPG Thumbnail
    var lastSavedThumbnail by remember { mutableStateOf<Bitmap?>(null) }

    // App Resume / Lifecycle Manager (Restores previous zoom when returning from library/pause)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    cameraManager.closeCamera()
                }
                Lifecycle.Event.ON_RESUME -> {
                    val tv = textureViewRef
                    if (tv != null && tv.isAvailable) {
                        val surface = tv.surfaceTexture
                        if (surface != null) {
                            cameraManager.openCamera(surface, tv.width, tv.height, activeLensId)
                            cameraManager.setZoomRatio(zoomRatio) // Restore previous saved zoom!
                            cameraManager.setManualAe(isManualAe, selectedIso, selectedShutterNanos)
                            cameraManager.setFlashMode(flashMode)
                            isRawHardwareSupported = cameraManager.isRawSupported
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        cameraManager.onRawCaptured = { rawData ->
            coroutineScope.launch {
                captureProgress = 0.85f
                captureStatusText = "自動現像・高画質保存中..."

                val maxDim = when (captureResolution) {
                    CaptureResolution.FULL -> 8192
                    CaptureResolution.MEDIUM -> 4096
                    CaptureResolution.COMPACT -> 2048
                }

                val baseBmp = rawEngine.decodeRawToBitmap(rawData.dngFilePath, maxDim)
                if (baseBmp != null) {
                    val developedBmp = rawEngine.processBitmap(baseBmp, developParams, zoomRatio)
                    lastSavedThumbnail = developedBmp
                    rawEngine.saveDevelopedPhotoToGallery(developedBmp)
                }
                captureProgress = 1.0f
                delay(200)
                isCapturing = false
            }
        }

        cameraManager.onCaptureProgress = { progress, text ->
            captureProgress = progress
            captureStatusText = text
        }

        cameraManager.onFocusStatusChanged = { status ->
            focusStatus = status
        }

        cameraManager.onError = { error ->
            isCapturing = false
            statusMessage = error
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraManager.closeCamera()
        }
    }

    // SINGLE STABLE BOX LAYOUT
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // 1. DYNAMIC LETTERBOX/PILLARBOX VIEWFINDER (MAXIMIZED LANDSCAPE VIEWPORT ALLOWING OVERLAY BUTTONS)
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val containerW = maxWidth
            val containerH = maxHeight

            val isLandscape = containerW > containerH
            val sensorRatio = if (cameraManager.sensorAspectRatio > 0f) cameraManager.sensorAspectRatio else (4f / 3f)

            // Target aspect ratio (width / height)
            val targetAspectRatio = if (isLandscape) sensorRatio else (1f / sensorRatio)
            val containerAspect = if (containerH.value > 0f) containerW.value / containerH.value else 1.0f

            val (boxW, boxH) = if (isLandscape) {
                // In landscape mode: maximize height and allow buttons/UI to overlay transparently
                val h = containerH
                val w = (h * targetAspectRatio).coerceAtMost(containerW)
                Pair(w, h)
            } else {
                // In portrait mode: fit within screen bounds
                val w = containerW
                val h = (w / targetAspectRatio).coerceAtMost(containerH)
                Pair(w, h)
            }

            Box(
                modifier = Modifier
                    .size(boxW, boxH)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .drawWithContent {
                        val paint = Paint().apply {
                            colorFilter = ColorFilter.colorMatrix(liveColorMatrix)
                        }
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(size.toRect(), paint)
                            drawContent()
                            canvas.restore()
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            zoomRatio = (zoomRatio * zoom).coerceIn(1.0f, cameraManager.maxZoomRatio)
                            settingsManager.savedZoomRatio = zoomRatio
                            cameraManager.setZoomRatio(zoomRatio)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            focusTapOffset = offset
                            focusStatus = FocusStatus.SEARCHING
                            val width = size.width
                            val height = size.height
                            if (width > 0 && height > 0) {
                                cameraManager.triggerFocusAt(
                                    xNorm = offset.x / width,
                                    yNorm = offset.y / height,
                                    viewWidth = width,
                                    viewHeight = height
                                )
                            }
                        }
                    }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            textureViewRef = this
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    cameraManager.openCamera(surface, width, height, activeLensId)
                                    cameraManager.setZoomRatio(zoomRatio)
                                    cameraManager.setManualAe(isManualAe, selectedIso, selectedShutterNanos)
                                    cameraManager.setFlashMode(flashMode)
                                    isRawHardwareSupported = cameraManager.isRawSupported
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {}

                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                    cameraManager.closeCamera()
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                                    currentIso = cameraManager.currentIso
                                    val shutterNanos = cameraManager.currentShutterNanos
                                    if (shutterNanos > 0) {
                                        val seconds = shutterNanos / 1_000_000_000.0
                                        currentShutter = if (seconds >= 1.0) {
                                            "%.1fs".format(seconds)
                                        } else {
                                            val denom = (1.0 / seconds).roundToInt()
                                            "1/${denom}s"
                                        }
                                    }
                                }
                            }
                        }
                    }
                )

                // Grid Overlay
                if (showGrid) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .align(Alignment.CenterStart)
                                .offset(x = (boxW.value * 0.33f).dp)
                                .background(Color.White.copy(alpha = 0.25f))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .align(Alignment.CenterStart)
                                .offset(x = (boxW.value * 0.66f).dp)
                                .background(Color.White.copy(alpha = 0.25f))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .align(Alignment.TopCenter)
                                .offset(y = (boxH.value * 0.33f).dp)
                                .background(Color.White.copy(alpha = 0.25f))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .align(Alignment.TopCenter)
                                .offset(y = (boxH.value * 0.66f).dp)
                                .background(Color.White.copy(alpha = 0.25f))
                        )
                    }
                }

                // Zoom Ratio Floating Badge
                if (zoomRatio > 1.05f) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp),
                        color = DarkSurface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "%.1fx".format(zoomRatio),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = RawGold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Tap-to-Focus Ring Overlay
                focusTapOffset?.let { offset ->
                    LaunchedEffect(focusStatus) {
                        if (focusStatus == FocusStatus.LOCKED || focusStatus == FocusStatus.FAILED) {
                            delay(1800)
                            focusTapOffset = null
                            focusStatus = FocusStatus.IDLE
                        }
                    }

                    val ringColor = when (focusStatus) {
                        FocusStatus.SEARCHING -> RawGold
                        FocusStatus.LOCKED -> RawBypassGreen
                        FocusStatus.FAILED -> Color.Red
                        FocusStatus.IDLE -> RawGold
                    }

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(offset.x.toInt() - 40, offset.y.toInt() - 40) }
                            .size(80.dp)
                            .border(
                                width = if (focusStatus == FocusStatus.LOCKED) 3.dp else 2.dp,
                                color = ringColor,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (focusStatus == FocusStatus.LOCKED) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(RawBypassGreen)
                            )
                        }
                    }
                }
            }
        }

        // 2. CAMERA LENS / ANGLE SELECTOR BAR (PLACED STRICTLY OUTSIDE PREVIEW IMAGE: BELOW PREVIEW, ABOVE BOTTOM BAR)
        if (availableLenses.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 105.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                availableLenses.forEach { lens ->
                    val isSelected = (lens.lensId == activeLensId)
                    Surface(
                        color = if (isSelected) RawGold else DarkSurfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable {
                            switchCameraLens(lens.lensId)
                        }
                    ) {
                        Text(
                            text = lens.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.Black else TextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // 3. TOP HEADER ROW (FLASH MODE SELECTOR + IDLE SENSOR INFO PILL + ACTION BUTTONS)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flash Mode Selector Button (OFF, FLASH, TORCH)
                IconButton(
                    onClick = {
                        val nextMode = when (flashMode) {
                            FlashMode.OFF -> FlashMode.FLASH
                            FlashMode.FLASH -> FlashMode.TORCH
                            FlashMode.TORCH -> FlashMode.OFF
                        }
                        flashMode = nextMode
                        settingsManager.flashMode = nextMode
                        cameraManager.setFlashMode(nextMode)
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (flashMode != FlashMode.OFF) RawGold else DarkSurface.copy(alpha = 0.8f))
                ) {
                    val icon = when (flashMode) {
                        FlashMode.OFF -> Icons.Default.FlashOff
                        FlashMode.FLASH -> Icons.Default.FlashOn
                        FlashMode.TORCH -> Icons.Default.Highlight
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = "Flash Mode",
                        tint = if (flashMode != FlashMode.OFF) Color.Black else TextPrimary
                    )
                }

                // PERSISTENT IDLE SENSOR INFO PILL (CENTERED OUTSIDE / ABOVE PREVIEW IMAGE)
                Surface(
                    modifier = Modifier
                        .clickable {
                            showSliders = false
                            showManualAeDrawer = !showManualAeDrawer
                        },
                    color = DarkSurface.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RawGold.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (isManualAe) RawGold else DarkSurfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (isManualAe) "M" else "AUTO",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isManualAe) Color.Black else TextPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Text(
                            text = "ISO $currentIso",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = RawGold
                        )

                        Text(
                            text = currentShutter,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )

                        val evFormatted = if (developParams.exposure >= 0f) "+%.1fEV".format(developParams.exposure) else "%.1fEV".format(developParams.exposure)
                        Text(
                            text = evFormatted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = RawBypassGreen
                        )
                    }
                }

                // Controls Right (Sliders Drawer Toggle, Grid)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            showManualAeDrawer = false
                            showSliders = !showSliders
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (showSliders) RawGold else DarkSurface.copy(alpha = 0.8f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Develop Sliders",
                            tint = if (showSliders) Color.Black else TextPrimary
                        )
                    }

                    IconButton(
                        onClick = {
                            showGrid = !showGrid
                            settingsManager.showGrid = showGrid
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(DarkSurface.copy(alpha = 0.8f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridOn,
                            contentDescription = "Grid",
                            tint = if (showGrid) RawGold else TextSecondary
                        )
                    }
                }
            }
        }

        // 4. SCRIM OVERLAY FOR CLOSING MENUS WHEN CLICKING OUTSIDE (Swallows click so underlying elements aren't triggered)
        if (showManualAeDrawer || showSliders) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            showManualAeDrawer = false
                            showSliders = false
                        }
                    }
            )
        }

        // 5. FLOATING MANUAL EXPOSURE SELECTOR DRAWER
        AnimatedVisibility(
            visible = showManualAeDrawer,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 90.dp, start = 12.dp, end = 12.dp)
        ) {
            Surface(
                color = DarkSurface.copy(alpha = 0.95f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, RawGold)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "露出制御モード", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RawGold)

                        Surface(
                            color = if (isManualAe) RawGold else DarkSurfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.clickable {
                                isManualAe = !isManualAe
                                settingsManager.isManualAe = isManualAe
                                cameraManager.setManualAe(isManualAe, selectedIso, selectedShutterNanos)
                            }
                        ) {
                            Text(
                                text = if (isManualAe) "マニュアル (M)" else "オート (AUTO)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isManualAe) Color.Black else TextPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (isManualAe) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = "マニュアル ISO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ISO_OPTIONS) { iso ->
                                val selected = selectedIso == iso
                                Surface(
                                    color = if (selected) RawGold else DarkSurfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        selectedIso = iso
                                        settingsManager.manualIso = iso
                                        cameraManager.setManualAe(true, selectedIso, selectedShutterNanos)
                                    }
                                ) {
                                    Text(
                                        text = "ISO $iso",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.Black else TextPrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(text = "マニュアル シャッタースピード", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(SHUTTER_OPTIONS) { (nanos, label) ->
                                val selected = selectedShutterNanos == nanos
                                Surface(
                                    color = if (selected) RawGold else DarkSurfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        selectedShutterNanos = nanos
                                        settingsManager.manualShutterNanos = nanos
                                        cameraManager.setManualAe(true, selectedIso, selectedShutterNanos)
                                    }
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.Black else TextPrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. FLOATING LIVE DEVELOPMENT PARAMETERS DRAWER
        AnimatedVisibility(
            visible = showSliders,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 100.dp, start = 12.dp, end = 12.dp)
        ) {
            Surface(
                color = DarkSurface.copy(alpha = 0.95f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, RawGold.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "キャプチャ・現像設定",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = RawGold
                        )
                        TextButton(onClick = {
                            developParams = DevelopParams()
                            settingsManager.saveDevelopParams(developParams)
                        }) {
                            Text("全パラメータリセット", fontSize = 11.sp, color = TextSecondary)
                        }
                    }

                    // Resolution Mode Selector
                    Text(
                        text = "撮影解像度設定",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RawGold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(CaptureResolution.values()) { res ->
                            val selected = captureResolution == res
                            Surface(
                                color = if (selected) RawGold else DarkSurfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    captureResolution = res
                                    settingsManager.captureResolution = res
                                }
                            ) {
                                Text(
                                    text = res.label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Color.Black else TextPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Zoom Crop Mode Selector
                    Text(
                        text = "ズーム画角現像モード",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RawGold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ZoomCropMode.values()) { mode ->
                            val selected = developParams.zoomCropMode == mode
                            Surface(
                                color = if (selected) RawGold else DarkSurfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    developParams = developParams.copy(zoomCropMode = mode)
                                    settingsManager.saveDevelopParams(developParams)
                                }
                            ) {
                                Text(
                                    text = mode.label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Color.Black else TextPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Sliders with Individual Parameter Resets & WB Auto Mode
                    SliderControl(
                        label = "露出補正 (EV)",
                        valueDisplay = "%.2f EV".format(developParams.exposure),
                        value = developParams.exposure,
                        valueRange = -3.0f..3.0f,
                        onValueChange = {
                            developParams = developParams.copy(exposure = it)
                            settingsManager.saveDevelopParams(developParams)
                        },
                        onReset = {
                            developParams = developParams.copy(exposure = 0f)
                            settingsManager.saveDevelopParams(developParams)
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    // White Balance Section (Auto / Manual Kelvin)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "ホワイトバランス (WB)", fontSize = 12.sp, color = TextPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                color = if (developParams.isWbAuto) RawGold else DarkSurfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    developParams = developParams.copy(isWbAuto = true)
                                    settingsManager.saveDevelopParams(developParams)
                                }
                            ) {
                                Text(
                                    text = "AUTO",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (developParams.isWbAuto) Color.Black else TextPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Surface(
                                color = if (!developParams.isWbAuto) RawGold else DarkSurfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    developParams = developParams.copy(isWbAuto = false)
                                    settingsManager.saveDevelopParams(developParams)
                                }
                            ) {
                                Text(
                                    text = "マニュアル",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!developParams.isWbAuto) Color.Black else TextPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    if (!developParams.isWbAuto) {
                        Spacer(modifier = Modifier.height(4.dp))
                        SliderControl(
                            label = "色温度 (WB)",
                            valueDisplay = "${developParams.temperature.toInt()}K",
                            value = developParams.temperature,
                            valueRange = 2000f..10000f,
                            onValueChange = {
                                developParams = developParams.copy(isWbAuto = false, temperature = it)
                                settingsManager.saveDevelopParams(developParams)
                            },
                            onReset = {
                                developParams = developParams.copy(temperature = 5500f)
                                settingsManager.saveDevelopParams(developParams)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    SliderControl(
                        label = "コントラスト",
                        valueDisplay = "%.2fx".format(developParams.contrast),
                        value = developParams.contrast,
                        valueRange = 0.5f..2.0f,
                        onValueChange = {
                            developParams = developParams.copy(contrast = it)
                            settingsManager.saveDevelopParams(developParams)
                        },
                        onReset = {
                            developParams = developParams.copy(contrast = 1.0f)
                            settingsManager.saveDevelopParams(developParams)
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SliderControl(
                        label = "黒レベル (Black Point)",
                        valueDisplay = "%.2f".format(developParams.blackLevel),
                        value = developParams.blackLevel,
                        valueRange = -1.0f..1.0f,
                        onValueChange = {
                            developParams = developParams.copy(blackLevel = it)
                            settingsManager.saveDevelopParams(developParams)
                        },
                        onReset = {
                            developParams = developParams.copy(blackLevel = 0f)
                            settingsManager.saveDevelopParams(developParams)
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SliderControl(
                        label = "白レベル (White Point)",
                        valueDisplay = "%.2f".format(developParams.whiteLevel),
                        value = developParams.whiteLevel,
                        valueRange = -1.0f..1.0f,
                        onValueChange = {
                            developParams = developParams.copy(whiteLevel = it)
                            settingsManager.saveDevelopParams(developParams)
                        },
                        onReset = {
                            developParams = developParams.copy(whiteLevel = 0f)
                            settingsManager.saveDevelopParams(developParams)
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SliderControl(
                        label = "シャープネス (Sharpness)",
                        valueDisplay = "%.2fx".format(developParams.sharpness),
                        value = developParams.sharpness,
                        valueRange = 0.0f..2.0f,
                        onValueChange = {
                            developParams = developParams.copy(sharpness = it)
                            settingsManager.saveDevelopParams(developParams)
                        },
                        onReset = {
                            developParams = developParams.copy(sharpness = 1.0f)
                            settingsManager.saveDevelopParams(developParams)
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SliderControl(
                        label = "彩度 (Saturation)",
                        valueDisplay = "%.2fx".format(developParams.saturation),
                        value = developParams.saturation,
                        valueRange = 0.0f..2.0f,
                        onValueChange = {
                            developParams = developParams.copy(saturation = it)
                            settingsManager.saveDevelopParams(developParams)
                        },
                        onReset = {
                            developParams = developParams.copy(saturation = 1.0f)
                            settingsManager.saveDevelopParams(developParams)
                        }
                    )
                }
            }
        }

        // 7. FLOATING PROCESSING PROGRESS OVERLAY BANNER
        if (isCapturing) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                color = DarkSurface.copy(alpha = 0.95f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, RawGold)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        progress = { captureProgress },
                        modifier = Modifier.size(52.dp),
                        color = RawGold,
                        trackColor = DarkSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "RAWキャプチャ & 自動現像中 ${(captureProgress * 100).toInt()}%",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = RawGold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = captureStatusText,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        // 8. BOTTOM CONTROLS BAR
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp, start = 24.dp, end = 24.dp)
        ) {
            // Thumbnail of last developed JPG photo (Left)
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceVariant)
                    .border(1.dp, RawGold, RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenGallery),
                contentAlignment = Alignment.Center
            ) {
                if (lastSavedThumbnail != null) {
                    Image(
                        bitmap = lastSavedThumbnail!!.asImageBitmap(),
                        contentDescription = "Last Developed JPG Photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Surface(
                        color = RawGold,
                        shape = RoundedCornerShape(topStart = 4.dp, bottomEnd = 4.dp),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Text(
                            text = "JPG",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "Gallery",
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Shutter Button (Center)
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .border(4.dp, RawGold, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (isCapturing) RawGoldDark else Color.White)
                    .clickable(enabled = !isCapturing) {
                        isCapturing = true
                        captureProgress = 0.05f
                        captureStatusText = "撮影リクエスト開始..."
                        cameraManager.takeRawPhoto()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isCapturing) {
                    Text(
                        text = "${(captureProgress * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }

            // Settings Toggle Button (Right)
            IconButton(
                onClick = {
                    showManualAeDrawer = false
                    showSliders = !showSliders
                },
                modifier = Modifier
                    .size(52.dp)
                    .align(Alignment.CenterEnd)
                    .clip(CircleShape)
                    .background(if (showSliders) RawGold else DarkSurface)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Settings",
                    tint = if (showSliders) Color.Black else TextPrimary
                )
            }
        }

        // Status Toast Card
        statusMessage?.let { msg ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp, start = 16.dp, end = 16.dp),
                color = DarkSurfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    color = Color.Red,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
fun SliderControl(
    label: String,
    valueDisplay: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onReset: (() -> Unit)? = null
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = label, fontSize = 12.sp, color = TextPrimary)
                onReset?.let {
                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable(onClick = it)
                    ) {
                        Text(
                            text = "リセット",
                            fontSize = 9.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(text = valueDisplay, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RawGold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = RawGold,
                activeTrackColor = RawGold,
                inactiveTrackColor = DarkSurfaceVariant
            )
        )
    }
}
