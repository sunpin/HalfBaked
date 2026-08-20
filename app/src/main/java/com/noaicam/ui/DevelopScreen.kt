package com.noaicam.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noaicam.data.DevelopEffect
import com.noaicam.data.DevelopParams
import com.noaicam.processor.RawDevelopmentEngine
import com.noaicam.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevelopScreen(
    dngFilePath: String,
    onBackToGallery: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val rawEngine = remember { RawDevelopmentEngine(context) }

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var developedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRawOriginalView by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    var params by remember { mutableStateOf(DevelopParams()) }
    var lastRenderedParams by remember { mutableStateOf<DevelopParams?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Exposure/WB, 1: Tone/Contrast, 2: Color, 3: Crop/Zoom, 4: Art Effects

    // Current live visual transform state (GPU hardware accelerated)
    var visualScale by remember { mutableFloatStateOf(params.cropScale) }
    var visualPanX by remember { mutableFloatStateOf(params.cropPanX) }
    var visualPanY by remember { mutableFloatStateOf(params.cropPanY) }

    // Load RAW image
    LaunchedEffect(dngFilePath) {
        isLoading = true
        val bmp = rawEngine.decodeRawToBitmap(dngFilePath)
        originalBitmap = bmp
        if (bmp != null) {
            val rendered = rawEngine.processBitmap(bmp, params)
            developedBitmap = rendered
            lastRenderedParams = params
        }
        isLoading = false
    }

    // Debounced update to params so active touch gestures smoothly animate without triggering heavy RAW decodes during touch
    LaunchedEffect(visualScale, visualPanX, visualPanY) {
        delay(150)
        params = params.copy(
            cropScale = visualScale,
            cropPanX = visualPanX,
            cropPanY = visualPanY
        )
    }

    // Re-render RAW bitmap when params change
    LaunchedEffect(params, originalBitmap) {
        val base = originalBitmap ?: return@LaunchedEffect
        val rendered = rawEngine.processBitmap(base, params)
        developedBitmap = rendered
        lastRenderedParams = params
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "RAW Developer",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackToGallery) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    // Top-Right DELETE BUTTON for RAW (.dng) file
                    IconButton(
                        onClick = { showDeleteConfirmDialog = true },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Red.copy(alpha = 0.85f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "RAW削除",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = RawGold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "RAWセンサーデータをデモザイク中...",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Photo Canvas Preview Container Outer Host
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.0f)
                        .background(Color.Black)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    val displayBmp = if (isRawOriginalView) originalBitmap else developedBitmap
                    displayBmp?.let { bmp ->
                        val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()

                        // Photo Aspect Ratio Inner Container (Guarantees gesture bounds & GPU transforms match final RAW output 1:1 with ZERO shift)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(bmpAspect)
                                .clipToBounds()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val boxW = size.width.toFloat()
                                        val boxH = size.height.toFloat()
                                        if (boxW <= 0f || boxH <= 0f) return@detectTransformGestures

                                        val newScale = (visualScale * zoom).coerceIn(1.0f, 4.0f)
                                        visualScale = newScale

                                        if (newScale <= 1.001f) {
                                            visualScale = 1.0f
                                            visualPanX = 0.0f
                                            visualPanY = 0.0f
                                        } else {
                                            val maxTx = (newScale - 1.0f) * boxW / 2.0f
                                            val maxTy = (newScale - 1.0f) * boxH / 2.0f

                                            // Natural drag direction (drag right -> photo moves right)
                                            val currentTx = - visualPanX * maxTx
                                            val currentTy = - visualPanY * maxTy

                                            val newTx = (currentTx + pan.x).coerceIn(-maxTx, maxTx)
                                            val newTy = (currentTy + pan.y).coerceIn(-maxTy, maxTy)

                                            visualPanX = if (maxTx > 0f) - newTx / maxTx else 0f
                                            visualPanY = if (maxTy > 0f) - newTy / maxTy else 0f
                                        }
                                    }
                                }
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "RAW Preview",
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val rendered = lastRenderedParams ?: params
                                        val renderedScale = rendered.cropScale.coerceAtLeast(1.0f)
                                        val relScale = visualScale / renderedScale

                                        val boxW = size.width.toFloat()
                                        val boxH = size.height.toFloat()

                                        if (boxW > 0f && boxH > 0f) {
                                            val renderedMaxTx = (renderedScale - 1.0f) * boxW / 2.0f
                                            val renderedMaxTy = (renderedScale - 1.0f) * boxH / 2.0f
                                            val renderedTx = - rendered.cropPanX * renderedMaxTx
                                            val renderedTy = - rendered.cropPanY * renderedMaxTy

                                            val targetMaxTx = (visualScale - 1.0f) * boxW / 2.0f
                                            val targetMaxTy = (visualScale - 1.0f) * boxH / 2.0f
                                            val targetTx = - visualPanX * targetMaxTx
                                            val targetTy = - visualPanY * targetMaxTy

                                            val relTx = targetTx - (renderedTx * relScale)
                                            val relTy = targetTy - (renderedTy * relScale)

                                            scaleX = relScale
                                            scaleY = relScale
                                            translationX = relTx
                                            translationY = relTy
                                        }
                                    }
                            )
                        }
                    }

                    // Compare Toggle Overlay Button (Original vs Developed)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        color = DarkSurface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable { isRawOriginalView = !isRawOriginalView }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Compare,
                                contentDescription = "Compare",
                                tint = if (isRawOriginalView) RawGold else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRawOriginalView) "RAW原画表示中" else "現像プレビュー",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }

                // Action Row Below Image (DEVELOP & SAVE JPG BUTTON + PRESETS)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Export / Save Developed JPG Button (Below Image)
                    Button(
                        onClick = {
                            val bmpToSave = developedBitmap ?: return@Button
                            coroutineScope.launch {
                                isExporting = true
                                val uri = rawEngine.saveDevelopedPhotoToGallery(bmpToSave)
                                isExporting = false
                                if (uri != null) {
                                    Toast.makeText(
                                        context,
                                        "現像完了！ギャラリー (DCIM/NOAICAM) に保存しました",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "保存に失敗しました",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RawGold),
                        enabled = !isExporting && developedBitmap != null,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SaveAlt,
                                    contentDescription = "Save",
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "現像保存",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            }
                        }
                    }

                    // Presets Quick Chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DevelopPresetChip("全リセット") {
                            visualScale = 1.0f
                            visualPanX = 0f
                            visualPanY = 0f
                            params = DevelopParams()
                        }
                        DevelopPresetChip("ナチュラル") { params = params.copy(contrast = 1.1f, saturation = 1.05f) }
                        DevelopPresetChip("フィルム") { params = params.copy(isWbAuto = false, temperature = 6500f, contrast = 1.15f, saturation = 1.1f) }
                    }
                }

                // Parameter Controls Panel (Bottom Area)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface)
                ) {
                    // Category Tabs
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = DarkSurface,
                        contentColor = RawGold,
                        edgePadding = 8.dp
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("露出 & WB", fontSize = 12.sp) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("トーン & コントラスト", fontSize = 12.sp) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("色彩・彩度", fontSize = 12.sp) }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            text = { Text("トリミング & ズーム", fontSize = 12.sp) }
                        )
                        Tab(
                            selected = selectedTab == 4,
                            onClick = { selectedTab = 4 },
                            text = { Text("アートエフェクト", fontSize = 12.sp) }
                        )
                    }

                    // Sliders Container
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (selectedTab) {
                            0 -> {
                                DevelopSliderControl(
                                    label = "露出補正 (EV)",
                                    valueDisplay = "%.2f EV".format(params.exposure),
                                    value = params.exposure,
                                    valueRange = -3.0f..3.0f,
                                    onValueChange = { params = params.copy(exposure = it) },
                                    onReset = { params = params.copy(exposure = 0f) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                // WB Mode & Temperature Control
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "ホワイトバランス", fontSize = 12.sp, color = TextPrimary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(
                                            color = if (params.isWbAuto) RawGold else DarkSurfaceVariant,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable {
                                                params = params.copy(isWbAuto = true)
                                            }
                                        ) {
                                            Text(
                                                text = "AUTO",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (params.isWbAuto) Color.Black else TextPrimary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        Surface(
                                            color = if (!params.isWbAuto) RawGold else DarkSurfaceVariant,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable {
                                                params = params.copy(isWbAuto = false)
                                            }
                                        ) {
                                            Text(
                                                text = "マニュアル",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (!params.isWbAuto) Color.Black else TextPrimary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }

                                if (!params.isWbAuto) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    DevelopSliderControl(
                                        label = "色温度 (WB)",
                                        valueDisplay = "${params.temperature.toInt()}K",
                                        value = params.temperature,
                                        valueRange = 2000f..10000f,
                                        onValueChange = { params = params.copy(isWbAuto = false, temperature = it) },
                                        onReset = { params = params.copy(temperature = 5500f) }
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                DevelopSliderControl(
                                    label = "マゼンタ/グリーン Tint",
                                    valueDisplay = "${params.tint.toInt()}",
                                    value = params.tint,
                                    valueRange = -100f..100f,
                                    onValueChange = { params = params.copy(tint = it) },
                                    onReset = { params = params.copy(tint = 0f) }
                                )
                            }
                            1 -> {
                                // NOISE REDUCTION (NR) TOGGLE (ON / OFF)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "ノイズ除去 (NR)", fontSize = 12.sp, color = TextPrimary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(
                                            color = if (!params.isNoiseReductionEnabled) RawGold else DarkSurfaceVariant,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable {
                                                params = params.copy(isNoiseReductionEnabled = false)
                                            }
                                        ) {
                                            Text(
                                                text = "OFF (自然ノイズ)",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (!params.isNoiseReductionEnabled) Color.Black else TextPrimary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        Surface(
                                            color = if (params.isNoiseReductionEnabled) RawGold else DarkSurfaceVariant,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable {
                                                params = params.copy(isNoiseReductionEnabled = true)
                                            }
                                        ) {
                                            Text(
                                                text = "ON (ノイズ低減)",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (params.isNoiseReductionEnabled) Color.Black else TextPrimary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))

                                DevelopSliderControl(
                                    label = "コントラスト",
                                    valueDisplay = "%.2fx".format(params.contrast),
                                    value = params.contrast,
                                    valueRange = 0.5f..2.0f,
                                    onValueChange = { params = params.copy(contrast = it) },
                                    onReset = { params = params.copy(contrast = 1.0f) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                DevelopSliderControl(
                                    label = "黒レベル (Black Point)",
                                    valueDisplay = "%.2f".format(params.blackLevel),
                                    value = params.blackLevel,
                                    valueRange = -1.0f..1.0f,
                                    onValueChange = { params = params.copy(blackLevel = it) },
                                    onReset = { params = params.copy(blackLevel = 0f) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                DevelopSliderControl(
                                    label = "白レベル (White Point)",
                                    valueDisplay = "%.2f".format(params.whiteLevel),
                                    value = params.whiteLevel,
                                    valueRange = -1.0f..1.0f,
                                    onValueChange = { params = params.copy(whiteLevel = it) },
                                    onReset = { params = params.copy(whiteLevel = 0f) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                DevelopSliderControl(
                                    label = "シャープネス (Sharpness)",
                                    valueDisplay = "%.2fx".format(params.sharpness),
                                    value = params.sharpness,
                                    valueRange = 0.0f..2.0f,
                                    onValueChange = { params = params.copy(sharpness = it) },
                                    onReset = { params = params.copy(sharpness = 1.0f) }
                                )
                            }
                            2 -> {
                                DevelopSliderControl(
                                    label = "彩度 (Saturation)",
                                    valueDisplay = "%.2fx".format(params.saturation),
                                    value = params.saturation,
                                    valueRange = 0.0f..2.0f,
                                    onValueChange = { params = params.copy(saturation = it) },
                                    onReset = { params = params.copy(saturation = 1.0f) }
                                )
                            }
                            3 -> {
                                DevelopSliderControl(
                                    label = "ズーム・トリミング倍率",
                                    valueDisplay = "%.2fx".format(visualScale),
                                    value = visualScale,
                                    valueRange = 1.0f..4.0f,
                                    onValueChange = {
                                        visualScale = it
                                        if (it <= 1.001f) {
                                            visualPanX = 0f
                                            visualPanY = 0f
                                        } else {
                                            visualPanX = visualPanX.coerceIn(-1.0f, 1.0f)
                                            visualPanY = visualPanY.coerceIn(-1.0f, 1.0f)
                                        }
                                    },
                                    onReset = {
                                        visualScale = 1.0f
                                        visualPanX = 0f
                                        visualPanY = 0f
                                    }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                DevelopSliderControl(
                                    label = "左右位置 (Pan X)",
                                    valueDisplay = if (visualPanX >= 0) "+%.2f".format(visualPanX) else "%.2f".format(visualPanX),
                                    value = visualPanX,
                                    valueRange = -1.0f..1.0f,
                                    onValueChange = { visualPanX = it },
                                    onReset = { visualPanX = 0f }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                DevelopSliderControl(
                                    label = "上下位置 (Pan Y)",
                                    valueDisplay = if (visualPanY >= 0) "+%.2f".format(visualPanY) else "%.2f".format(visualPanY),
                                    value = visualPanY,
                                    valueRange = -1.0f..1.0f,
                                    onValueChange = { visualPanY = it },
                                    onReset = { visualPanY = 0f }
                                )
                            }
                            4 -> {
                                Text(
                                    text = "アートエフェクト選択",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RawGold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DevelopEffect.values().forEach { eff ->
                                        val selected = params.effect == eff
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    params = params.copy(effect = eff)
                                                },
                                            color = if (selected) RawGold else DarkSurfaceVariant,
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = eff.label,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (selected) Color.Black else TextPrimary
                                                )
                                                if (selected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = Color.Black,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (params.effect != DevelopEffect.NONE) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    DevelopSliderControl(
                                        label = "エフェクト強度・タッチサイズ",
                                        valueDisplay = "%.2fx".format(params.effectIntensity),
                                        value = params.effectIntensity,
                                        valueRange = 0.2f..2.0f,
                                        onValueChange = { params = params.copy(effectIntensity = it) },
                                        onReset = { params = params.copy(effectIntensity = 1.0f) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // RAW DELETE CONFIRMATION DIALOG
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("RAWデータを消去", fontWeight = FontWeight.Bold) },
            text = { Text("このRAW (.dng) ファイルを完全に削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val file = File(dngFilePath)
                                if (file.exists()) file.delete()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            withContext(Dispatchers.Main) {
                                showDeleteConfirmDialog = false
                                onBackToGallery()
                            }
                        }
                    }
                ) {
                    Text("消去する", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

@Composable
fun DevelopPresetChip(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, RawGold.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun DevelopSliderControl(
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
