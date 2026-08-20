package com.noaicam.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noaicam.data.DevelopParams
import com.noaicam.processor.RawDevelopmentEngine
import com.noaicam.ui.theme.*
import kotlinx.coroutines.Dispatchers
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
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Exposure/WB, 1: Tone/Contrast, 2: Color, 3: Crop/Zoom

    // Load RAW image
    LaunchedEffect(dngFilePath) {
        isLoading = true
        val bmp = rawEngine.decodeRawToBitmap(dngFilePath)
        originalBitmap = bmp
        if (bmp != null) {
            developedBitmap = rawEngine.processBitmap(bmp, params)
        }
        isLoading = false
    }

    // Re-render when parameters change
    LaunchedEffect(params, originalBitmap) {
        val base = originalBitmap ?: return@LaunchedEffect
        developedBitmap = rawEngine.processBitmap(base, params)
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
                // Photo Canvas Preview (Upper Area with Pinch-to-Zoom & Pan Gesture Support)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.0f)
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (params.cropScale * zoom).coerceIn(1.0f, 4.0f)
                                val panFactor = 0.003f
                                val newPanX = (params.cropPanX + pan.x * panFactor).coerceIn(-1.0f, 1.0f)
                                val newPanY = (params.cropPanY + pan.y * panFactor).coerceIn(-1.0f, 1.0f)
                                params = params.copy(
                                    cropScale = newScale,
                                    cropPanX = newPanX,
                                    cropPanY = newPanY
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val displayBmp = if (isRawOriginalView) originalBitmap else developedBitmap
                    displayBmp?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "RAW Preview",
                            modifier = Modifier.fillMaxSize()
                        )
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
                        DevelopPresetChip("全リセット") { params = DevelopParams() }
                        DevelopPresetChip("ナチュラル") { params = DevelopParams(contrast = 1.1f, saturation = 1.05f) }
                        DevelopPresetChip("フィルム") { params = DevelopParams(isWbAuto = false, temperature = 6500f, contrast = 1.15f, saturation = 1.1f) }
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
                                    valueDisplay = "%.2fx".format(params.cropScale),
                                    value = params.cropScale,
                                    valueRange = 1.0f..4.0f,
                                    onValueChange = { params = params.copy(cropScale = it) },
                                    onReset = { params = params.copy(cropScale = 1.0f, cropPanX = 0f, cropPanY = 0f) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                DevelopSliderControl(
                                    label = "左右位置 (Pan X)",
                                    valueDisplay = if (params.cropPanX >= 0) "+%.2f".format(params.cropPanX) else "%.2f".format(params.cropPanX),
                                    value = params.cropPanX,
                                    valueRange = -1.0f..1.0f,
                                    onValueChange = { params = params.copy(cropPanX = it) },
                                    onReset = { params = params.copy(cropPanX = 0f) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                DevelopSliderControl(
                                    label = "上下位置 (Pan Y)",
                                    valueDisplay = if (params.cropPanY >= 0) "+%.2f".format(params.cropPanY) else "%.2f".format(params.cropPanY),
                                    value = params.cropPanY,
                                    valueRange = -1.0f..1.0f,
                                    onValueChange = { params = params.copy(cropPanY = it) },
                                    onReset = { params = params.copy(cropPanY = 0f) }
                                )
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
