package com.noaicam

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.noaicam.ui.CameraScreen
import com.noaicam.ui.DevelopScreen
import com.noaicam.ui.GalleryScreen
import com.noaicam.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NoAiCamTheme {
                MainAppNavHost()
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainAppNavHost() {
    val navController = rememberNavController()

    val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = permissionsToRequest)

    if (permissionsState.allPermissionsGranted) {
        NavHost(navController = navController, startDestination = "camera") {
            // Camera Screen with Live On-Screen Development & Instant Auto-Save
            composable("camera") {
                CameraScreen(
                    onOpenGallery = {
                        navController.navigate("gallery")
                    }
                )
            }

            // Gallery View Screen
            composable("gallery") {
                GalleryScreen(
                    onSelectRawFile = { filePath ->
                        val encodedPath = Uri.encode(filePath)
                        navController.navigate("develop/$encodedPath")
                    },
                    onBackToCamera = {
                        navController.popBackStack("camera", inclusive = false)
                    }
                )
            }

            // Re-Develop Studio (from Gallery)
            composable(
                route = "develop/{filePath}",
                arguments = listOf(navArgument("filePath") { type = NavType.StringType })
            ) { backStackEntry ->
                val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
                val decodedPath = Uri.decode(encodedPath)
                DevelopScreen(
                    dngFilePath = decodedPath,
                    onBackToGallery = {
                        navController.popBackStack("gallery", inclusive = false)
                    }
                )
            }
        }
    } else {
        // Request Permissions Screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "HalfBaked",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = RawGold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "AIバイパスRAW撮影と手動現像を実行するため、カメラ権限およびストレージ権限が必要です。",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { permissionsState.launchMultiplePermissionRequest() },
                    colors = ButtonDefaults.buttonColors(containerColor = RawGold)
                ) {
                    Text(text = "権限を許可して開始", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        LaunchedEffect(Unit) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }
}
