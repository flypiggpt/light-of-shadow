package com.yan.client.ui.guide

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
// ...existing code... (removed drawing-related imports)
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.yan.client.R
import com.yan.client.audio.ObstacleAlertPlayer
import com.yan.client.ui.theme.NeumorphismBackground
import com.yan.client.ui.theme.NeumorphismPrimary
import com.yan.client.ui.theme.NeumorphismSurfacePressed
import com.yan.client.ui.theme.NeumorphismTextPrimary
import com.yan.client.ui.theme.NeumorphismTextSecondary
import com.yan.client.vision.ObstacleDetection
import com.yan.client.vision.ObstacleDetector
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@Composable
fun GuideScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val detector = remember(context) { ObstacleDetector(context) }
    val obstacleAlertPlayer = remember(context) { ObstacleAlertPlayer(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var latestAlert by rememberSaveable { mutableStateOf("正在初始化导盲识别…") }
    var latestDetections by remember { mutableStateOf<List<ObstacleDetection>>(emptyList()) }
    var soundEnabled by rememberSaveable { mutableStateOf(true) }
    var hasPermission by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar("需要相机权限才能开启导盲识别")
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.guide_start_alert_sound))
            }
        }
    }

    LaunchedEffect(latestDetections) {
        val primary = latestDetections.firstOrNull() ?: return@LaunchedEffect
        if (!primary.isObstacle) return@LaunchedEffect

        // 限流为每 3000ms 最多一次播放提示音
        if (obstacleAlertPlayer.playIfAllowed(3000L)) {
            // 播放成功后不额外提示，避免打断连续识别
        } else if (obstacleAlertPlayer.getInitializationError() != null) {
            scope.launch {
                snackbarHostState.showSnackbar(obstacleAlertPlayer.getInitializationError() ?: context.getString(R.string.alert_sound_missing_hint))
            }
        }
    }

    DisposableEffect(hasPermission) {
        var cameraProvider: ProcessCameraProvider? = null
        if (hasPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            val mainExecutor = ContextCompat.getMainExecutor(context)
            val bindRunnable = Runnable {
                try {
                    cameraProvider = future.get()
                    val preview = CameraPreview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(Size(320, 320))
                        .build()

                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            val detections = detector.detect(imageProxy)
                            val primary = detections.firstOrNull()
                            mainExecutor.execute {
                                latestDetections = detections
                                latestAlert = primary?.alertText ?: "暂未识别到明显障碍"
                            }
                        } catch (t: Throwable) {
                            mainExecutor.execute {
                                latestAlert = "识别失败：${t.message ?: "未知错误"}"
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }

                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (t: Throwable) {
                    scope.launch {
                        snackbarHostState.showSnackbar("相机启动失败：${t.message ?: "未知错误"}")
                    }
                }
            }
            future.addListener(bindRunnable, mainExecutor)
        }

        onDispose {
            cameraProvider?.unbindAll()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            detector.close()
            obstacleAlertPlayer.close()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = NeumorphismBackground
    ) { innerPadding ->
        if (!hasPermission) {
            PermissionRequestContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onBack = onBack
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    GuideTopBar(
                        onBack = onBack,
                        onLogout = onLogout
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = NeumorphismBackground.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = latestAlert,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Text(
                            text = if (latestDetections.isEmpty()) {
                                "当前未发现稳定障碍"
                            } else {
                                "识别到 ${latestDetections.size} 个目标，检测到障碍时会播放本地提示音"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.88f)
                        )
                        latestDetections.take(3).forEach { detection ->
                            DetectionPill(detection = detection)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val played = obstacleAlertPlayer.playNow(force = true)
                                    if (played) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.alert_sound_test_message))
                                        }
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                obstacleAlertPlayer.getInitializationError()
                                                    ?: context.getString(R.string.alert_sound_missing_hint)
                                            )
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeumorphismPrimary,
                                    contentColor = Color.White
                                )
                            ) {
                                Text(text = stringResource(R.string.alert_sound_test_button))
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "提示音",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                                Switch(
                                    checked = soundEnabled,
                                    onCheckedChange = {
                                        soundEnabled = it
                                        obstacleAlertPlayer.setSoundEnabled(it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = NeumorphismPrimary,
                                        checkedTrackColor = NeumorphismPrimary.copy(alpha = 0.5f),
                                        uncheckedThumbColor = Color.Gray,
                                        uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideTopBar(
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Text(text = stringResource(R.string.back_text), color = NeumorphismPrimary)
        }
        Text(
            text = stringResource(R.string.guide_title),
            style = MaterialTheme.typography.titleLarge,
            color = NeumorphismTextPrimary
        )
        TextButton(onClick = onLogout, contentPadding = PaddingValues(0.dp)) {
            Text(text = stringResource(R.string.logout_text), color = NeumorphismTextPrimary)
        }
    }
}

@Composable
private fun PermissionRequestContent(
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = NeumorphismSurfacePressed,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.camera_permission_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = NeumorphismTextPrimary
                )
                Text(
                    text = stringResource(R.string.camera_permission_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeumorphismTextSecondary
                )
                Button(
                    onClick = onRequestPermission,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeumorphismPrimary,
                        contentColor = Color.White
                    )
                ) {
                    Text(text = stringResource(R.string.allow_camera_button))
                }
                TextButton(onClick = onBack) {
                    Text(text = stringResource(R.string.back_text), color = NeumorphismPrimary)
                }
            }
        }
    }
}

@Composable
private fun DetectionPill(detection: ObstacleDetection) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NeumorphismSurfacePressed.copy(alpha = 0.65f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = detection.alertText,
            style = MaterialTheme.typography.bodyMedium,
            color = NeumorphismTextPrimary
        )
    }
}


@Preview(showBackground = true)
@Composable
private fun GuidePermissionPreview() {
    PermissionRequestContent(
        modifier = Modifier.fillMaxSize(),
        onRequestPermission = {},
        onBack = {}
    )
}