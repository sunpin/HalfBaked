package com.noaicam.ui

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.noaicam.data.SettingsManager
import com.noaicam.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class GalleryItem(
    val uri: Uri,
    val filePath: String,
    val name: String,
    val isDng: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onSelectRawFile: (String) -> Unit,
    onBackToCamera: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }

    // PERSISTENT GALLERY TAB SELECTION (Defaults to last chosen tab, or 1: JPG)
    var selectedTab by remember { mutableIntStateOf(settingsManager.lastGalleryTab) }

    var rawDngItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var developedJpgItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }

    // Full-Screen Image Viewer Dialog State
    var selectedJpgForViewer by remember { mutableStateOf<GalleryItem?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    fun loadGalleryItems() {
        coroutineScope.launch(Dispatchers.IO) {
            val dngList = mutableListOf<GalleryItem>()
            val jpgList = mutableListOf<GalleryItem>()

            // 1. Scan Cache & App DNG RAW Files
            try {
                val cacheDir = context.cacheDir
                val files = cacheDir.listFiles() ?: emptyArray()
                files.sortedByDescending { it.lastModified() }.forEach { file ->
                    if (file.name.endsWith(".dng", ignoreCase = true)) {
                        dngList.add(
                            GalleryItem(
                                uri = Uri.fromFile(file),
                                filePath = file.absolutePath,
                                name = file.name,
                                isDng = true
                            )
                        )
                    } else if (file.name.endsWith(".jpg", ignoreCase = true)) {
                        jpgList.add(
                            GalleryItem(
                                uri = Uri.fromFile(file),
                                filePath = file.absolutePath,
                                name = file.name,
                                isDng = false
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Scan System MediaStore for Developed JPGs (DCIM/NOAICAM)
            try {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA
                )
                val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%DCIM/NOAICAM%")
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                val cursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )

                cursor?.use { c ->
                    val idColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dataColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                    while (c.moveToNext()) {
                        val id = c.getLong(idColumn)
                        val name = c.getString(nameColumn)
                        val data = c.getString(dataColumn)
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                        jpgList.add(
                            GalleryItem(
                                uri = contentUri,
                                filePath = data,
                                name = name,
                                isDng = false
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            rawDngItems = dngList.distinctBy { it.name }
            developedJpgItems = jpgList.distinctBy { it.name }
        }
    }

    LaunchedEffect(Unit) {
        loadGalleryItems()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "NOAICAM ライブラリ",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackToCamera) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
                )

                // Persistent Tab Selector
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = DarkSurface,
                    contentColor = RawGold
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            settingsManager.lastGalleryTab = 0
                        },
                        text = {
                            Text(
                                text = "RAWデータ (${rawDngItems.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            settingsManager.lastGalleryTab = 1
                        },
                        text = {
                            Text(
                                text = "現像済みJPG (${developedJpgItems.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                }
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->

        val itemsToShow = if (selectedTab == 0) rawDngItems else developedJpgItems

        if (itemsToShow.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Empty",
                        tint = TextSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (selectedTab == 0) "まだRAWデータがありません" else "まだ現像済みJPGがありません",
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(itemsToShow) { item ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1.0f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurfaceVariant)
                            .clickable {
                                if (item.isDng) {
                                    onSelectRawFile(item.filePath)
                                } else {
                                    selectedJpgForViewer = item
                                }
                            }
                    ) {
                        Image(
                            painter = rememberAsyncImagePainter(item.uri),
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )

                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp),
                            color = if (item.isDng) RawGold else RawBypassGreen,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (item.isDng) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Develop",
                                        tint = Color.Black,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                }
                                Text(
                                    text = if (item.isDng) "RAW現像" else "JPG",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // FULL SCREEN IMAGE VIEWER DIALOG WITH DELETE BUTTON & PINCH ZOOM
    selectedJpgForViewer?.let { item ->
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.8f, 5.0f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = rememberAsyncImagePainter(item.uri),
                    contentDescription = item.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        ),
                    contentScale = ContentScale.Fit
                )
            }

            // Top Toolbar overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { selectedJpgForViewer = null },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(DarkSurface.copy(alpha = 0.8f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextPrimary
                    )
                }

                Text(
                    text = item.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                // Delete Button
                IconButton(
                    onClick = { showDeleteConfirmDialog = true },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Red.copy(alpha = 0.85f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Photo",
                        tint = Color.White
                    )
                }
            }
        }

        // Delete Confirmation Dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("写真を消去", fontWeight = FontWeight.Bold) },
                text = { Text("この現像済みJPG写真を削除してもよろしいですか？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val file = File(item.filePath)
                                    if (file.exists()) file.delete()
                                    context.contentResolver.delete(item.uri, null, null)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                withContext(Dispatchers.Main) {
                                    showDeleteConfirmDialog = false
                                    selectedJpgForViewer = null
                                    loadGalleryItems()
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
}
