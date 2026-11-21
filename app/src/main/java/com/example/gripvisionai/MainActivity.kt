package com.example.gripvisionai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GripVisionApp()
            }
        }
    }
}

// ==========================================
// 1. DOMAIN MODELS & GRIP PRESETS
// ==========================================

enum class GripSport(val label: String) {
    TENNIS("Tennis"),
    PADEL("Padel"),
    PICKLEBALL("Pickleball")
}

enum class ShotPreset(val label: String) {
    SERVE_VOLLEY("Serve / Volley"),
    FOREHAND("Forehand"),
    BACKHAND_DINK("Backhand")
}

enum class BackhandStyle(val label: String) {
    ONE_HANDED("1H"),
    TWO_HANDED("2H")
}

enum class ScanStep {
    STEP_1_HANDLE_CAPTURE,
    STEP_2_HAND_PLACEMENT
}

data class GripTarget(
    val gripName: String,
    val targetBevel: Int,
    val bevelColor: Color,
    val shortInstruction: String,
    val fullGripInfo: String
)

val BEVEL_COLORS: Map<Int, Color> = mapOf(
    1 to Color(0xFFFF5252), // Red
    2 to Color(0xFF00FF88), // Neon Green
    3 to Color(0xFF00E5FF), // Cyan
    4 to Color(0xFFFFD740), // Amber
    5 to Color(0xFFE040FB), // Purple
    6 to Color(0xFFFF6E40), // Deep Orange
    7 to Color(0xFF69F0AE), // Mint
    8 to Color(0xFF40C4FF)  // Sky Blue
)

fun getGripTarget(
    sport: GripSport,
    shot: ShotPreset,
    backhandStyle: BackhandStyle
): GripTarget {
    return when (sport) {
        GripSport.TENNIS -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your index base knuckle on Bevel 2.",
                fullGripInfo = "Continental (Bevel 2): Universal hammer grip for serves, volleys, overheads, and slices with natural forearm pronation."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Semi-Western",
                targetBevel = 4,
                bevelColor = BEVEL_COLORS.getValue(4),
                shortInstruction = "Rotate your index base knuckle onto Bevel 4.",
                fullGripInfo = "Semi-Western Forehand (Bevel 4): Positions the face slightly closed for heavy topspin and higher net clearance."
            )
            ShotPreset.BACKHAND_DINK -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "Eastern Backhand (1H)",
                    targetBevel = 1,
                    bevelColor = BEVEL_COLORS.getValue(1),
                    shortInstruction = "Place your index base knuckle on top Bevel 1.",
                    fullGripInfo = "One-Handed Eastern Backhand (Bevel 1): Places your index base knuckle on top Bevel 1 to firm up the wrist for single-handed drives."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "Continental Anchor (2H)",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "Place your bottom index base knuckle on Bevel 2.",
                    fullGripInfo = "Two-Handed Backhand Bottom Hand (Bevel 2): Anchors your bottom hand on Bevel 2 while your top hand drives topspin and power."
                )
            }
        }
        GripSport.PADEL -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental Hammer",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Continental (Bevel 2): Hammer grip suited to bandejas, viboras, and quick exchanges at the net."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Keep your index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Continental Forehand (Bevel 2): Keeps the face neutral for low glass rebounds and controlled blocks."
            )
            ShotPreset.BACKHAND_DINK -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Keep your index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Continental Backhand (Bevel 2): Supports clean slice control off the glass and relaxed wrist action."
            )
        }
        GripSport.PICKLEBALL -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your index base knuckle on Bevel 2.",
                fullGripInfo = "Pickleball Continental (Bevel 2): Neutral kitchen-line grip for fast resets, punch volleys, and blocks."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Eastern Forehand",
                targetBevel = 3,
                bevelColor = BEVEL_COLORS.getValue(3),
                shortInstruction = "Place your index base knuckle on side Bevel 3.",
                fullGripInfo = "Pickleball Eastern Forehand (Bevel 3): Shake-hands grip for solid baseline drives and roll volleys."
            )
            ShotPreset.BACKHAND_DINK -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your index base knuckle on Bevel 2.",
                fullGripInfo = "Pickleball Continental Dink (Bevel 2): Keeps the paddle face stable for soft, controlled dinks."
            )
        }
    }
}

/**
 * Computes the angle (in degrees, where -90 is top/12 o'clock) for bevel 1..8.
 * Right-handed goes clockwise (1..8 around the circle).
 * Left-handed goes anti-clockwise (1..8 around the circle).
 */
fun bevelAngleDegrees(bevel: Int, isLeftHanded: Boolean): Double {
    val clampedBevel = bevel.coerceIn(1, 8)
    val step = (clampedBevel - 1) * 45.0
    return if (isLeftHanded) {
        -90.0 - step
    } else {
        -90.0 + step
    }
}

data class SmoothedPoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f
)

data class HandTrackingState(
    val landmarks: List<SmoothedPoint> = emptyList(),
    val isHandVisible: Boolean = false
)

// ==========================================
// 2. MEDIAPIPE HAND TRACKER (OCCLUSION-TUNED)
// ==========================================

class GripHandTracker(
    context: Context,
    private val smoothingAlpha: Float = 0.42f,
    private val maxStepJump: Float = 0.28f,
    private val onHandUpdated: (HandTrackingState) -> Unit
) {
    var captureNextFrame: Boolean = false
    var onHandleSnapped: ((Bitmap) -> Unit)? = null

    private var handLandmarker: HandLandmarker? = null
    private var smoothedLandmarks: List<SmoothedPoint>? = null
    private var lastValidTimestampMs: Long = 0L
    private val dropoutGraceMs: Long = 350L

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.24f)
                .setMinHandPresenceConfidence(0.24f)
                .setMinTrackingConfidence(0.24f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun processFrame(imageProxy: ImageProxy, runHandDetection: Boolean) {
        try {
            val needsBitmap = captureNextFrame || runHandDetection
            if (!needsBitmap) return

            val rawBitmap = imageProxyToBitmap(imageProxy) ?: return
            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
            val rotatedBitmap = if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                Bitmap.createBitmap(
                    rawBitmap,
                    0,
                    0,
                    rawBitmap.width,
                    rawBitmap.height,
                    matrix,
                    true
                )
            } else {
                rawBitmap
            }

            if (captureNextFrame) {
                captureNextFrame = false
                onHandleSnapped?.invoke(rotatedBitmap)
                return
            }

            if (!runHandDetection) return

            val landmarker = handLandmarker ?: return
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            val result = landmarker.detect(mpImage)
            val now = System.currentTimeMillis()

            if (result.landmarks().isNotEmpty()) {
                val steady = smoothLandmarks(result.landmarks()[0])
                lastValidTimestampMs = now
                onHandUpdated(HandTrackingState(landmarks = steady, isHandVisible = true))
            } else {
                val cached = smoothedLandmarks
                if (cached != null && (now - lastValidTimestampMs) <= dropoutGraceMs) {
                    onHandUpdated(HandTrackingState(landmarks = cached, isHandVisible = true))
                } else {
                    smoothedLandmarks = null
                    onHandUpdated(HandTrackingState(landmarks = emptyList(), isHandVisible = false))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    private fun smoothLandmarks(raw: List<NormalizedLandmark>): List<SmoothedPoint> {
        val prev = smoothedLandmarks
        if (prev == null || prev.size != raw.size) {
            val initial = raw.map { lm -> SmoothedPoint(lm.x(), lm.y(), lm.z()) }
            smoothedLandmarks = initial
            return initial
        }

        val alpha = smoothingAlpha.coerceIn(0.05f, 1.0f)
        val updated = raw.mapIndexed { idx, lm ->
            val p = prev[idx]
            val dx = lm.x() - p.x
            val dy = lm.y() - p.y
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

            val clampedX = if (dist > maxStepJump && dist > 0f) {
                p.x + (dx / dist) * maxStepJump
            } else {
                lm.x()
            }
            val clampedY = if (dist > maxStepJump && dist > 0f) {
                p.y + (dy / dist) * maxStepJump
            } else {
                lm.y()
            }

            SmoothedPoint(
                x = alpha * clampedX + (1f - alpha) * p.x,
                y = alpha * clampedY + (1f - alpha) * p.y,
                z = alpha * lm.z() + (1f - alpha) * p.z
            )
        }
        smoothedLandmarks = updated
        return updated
    }

    fun resetSmoothing() {
        smoothedLandmarks = null
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return when (image.format) {
            ImageFormat.JPEG -> {
                val buffer = image.planes[0].buffer
                buffer.rewind()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            ImageFormat.YUV_420_888 -> yuv420ToBitmap(image)
            else -> null
        }
    }

    private fun yuv420ToBitmap(image: ImageProxy): Bitmap? {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yPlane.buffer.get(nv21, 0, ySize)
        vPlane.buffer.get(nv21, ySize, vSize)
        uPlane.buffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }
}

// ==========================================
// 3. MAIN 2-STEP GRIPVISION SCREEN
// ==========================================

@Composable
fun GripVisionApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var selectedSport by remember { mutableStateOf(GripSport.TENNIS) }
    var selectedShot by remember { mutableStateOf(ShotPreset.SERVE_VOLLEY) }
    var backhandStyle by remember { mutableStateOf(BackhandStyle.ONE_HANDED) }
    var isLeftHanded by remember { mutableStateOf(false) }

    var currentStep by remember { mutableStateOf(ScanStep.STEP_1_HANDLE_CAPTURE) }
    var snappedHandleBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var handState by remember { mutableStateOf(HandTrackingState()) }
    var lockScore by remember { mutableIntStateOf(0) }

    val currentTarget = getGripTarget(selectedSport, selectedShot, backhandStyle)

    val tracker = remember {
        GripHandTracker(context) { newState ->
            handState = newState
        }
    }

    LaunchedEffect(Unit) {
        tracker.onHandleSnapped = { bitmap ->
            snappedHandleBitmap = bitmap
        }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            tracker.close()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener(
                        {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    it.setAnalyzer(cameraExecutor) { proxy ->
                                        tracker.processFrame(
                                            imageProxy = proxy,
                                            runHandDetection = (currentStep == ScanStep.STEP_2_HAND_PLACEMENT)
                                        )
                                    }
                                }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                        },
                        ContextCompat.getMainExecutor(ctx)
                    )
                    previewView
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Camera permission is required to scan your racket and grip.",
                    color = Color.White,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(text = "Grant Camera Permission", fontSize = 12.sp)
                }
            }
        }

        if (currentStep == ScanStep.STEP_1_HANDLE_CAPTURE && snappedHandleBitmap != null) {
            Image(
                bitmap = snappedHandleBitmap!!.asImageBitmap(),
                contentDescription = "Captured Racket Handle",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Bottom-of-handle silhouette + bevel numbers 1-8 (clockwise righty, anti-clockwise lefty)
        HandleBottomBevelOverlay(
            targetBevel = currentTarget.targetBevel,
            targetColor = currentTarget.bevelColor,
            isLeftHanded = isLeftHanded,
            isLocked = (currentStep == ScanStep.STEP_2_HAND_PLACEMENT && lockScore == 100),
            showBottomSilhouette = (currentStep == ScanStep.STEP_1_HANDLE_CAPTURE && snappedHandleBitmap == null),
            onTargetPointComputed = { targetCenterPx, screenSize ->
                if (currentStep == ScanStep.STEP_2_HAND_PLACEMENT && handState.landmarks.size >= 21) {
                    val knuckleLm = handState.landmarks[5] // Index base knuckle
                    val knucklePx = Offset(
                        knuckleLm.x * screenSize.width,
                        knuckleLm.y * screenSize.height
                    )
                    val distPx = hypot(
                        (knucklePx.x - targetCenterPx.x).toDouble(),
                        (knucklePx.y - targetCenterPx.y).toDouble()
                    ).toFloat()

                    val snapRadiusPx = screenSize.width * 0.12f
                    val maxRadiusPx = screenSize.width * 0.35f
                    lockScore = when {
                        distPx <= snapRadiusPx -> 100
                        distPx >= maxRadiusPx -> 15
                        else -> {
                            val ratio = 1f - ((distPx - snapRadiusPx) / (maxRadiusPx - snapRadiusPx))
                            (20 + ratio * 75f).toInt().coerceIn(15, 99)
                        }
                    }
                } else {
                    lockScore = 0
                }
            }
        )

        // Base knuckle (landmark 5) spot only
        if (currentStep == ScanStep.STEP_2_HAND_PLACEMENT && handState.isHandVisible) {
            BaseKnuckleSpotOverlay(
                landmarks = handState.landmarks,
                knuckleColor = currentTarget.bevelColor,
                isLocked = (lockScore == 100)
            )
        }

        // ==========================================
        // TOP HUD: CLEAN NON-CLIPPING CONTROLS
        // ==========================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 4.dp, start = 10.dp, end = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GripVision AI",
                    color = Color(0xFF00FF88),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )

                FilterChip(
                    selected = isLeftHanded,
                    onClick = { isLeftHanded = !isLeftHanded },
                    label = {
                        Text(
                            text = if (isLeftHanded) "Lefty (CCW)" else "Righty (CW)",
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    },
                    modifier = Modifier.height(28.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0x99121A1E),
                        labelColor = Color.White,
                        selectedContainerColor = Color(0xFF00FF88),
                        selectedLabelColor = Color.Black
                    )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GripSport.entries.forEach { sport ->
                    FilterChip(
                        selected = selectedSport == sport,
                        onClick = { selectedSport = sport },
                        label = {
                            Text(
                                text = sport.label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0x99121A1E),
                            labelColor = Color.White,
                            selectedContainerColor = Color(0xFF00FF88),
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShotPreset.entries.forEach { shot ->
                    FilterChip(
                        selected = selectedShot == shot,
                        onClick = { selectedShot = shot },
                        label = {
                            Text(
                                text = shot.label,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0x99121A1E),
                            labelColor = Color.LightGray,
                            selectedContainerColor = Color(0xFF00E5FF),
                            selectedLabelColor = Color.Black
                        )
                    )
                }

                if (selectedSport == GripSport.TENNIS && selectedShot == ShotPreset.BACKHAND_DINK) {
                    BackhandStyle.entries.forEach { style ->
                        FilterChip(
                            selected = backhandStyle == style,
                            onClick = { backhandStyle = style },
                            label = {
                                Text(
                                    text = style.label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.height(28.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0x99121A1E),
                                labelColor = Color.White,
                                selectedContainerColor = Color(0xFFFFD740),
                                selectedLabelColor = Color.Black
                            )
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(Color(0xBB10181C), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${currentTarget.gripName} • Bevel ${currentTarget.targetBevel}",
                    color = currentTarget.bevelColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }

        // ==========================================
        // BOTTOM CARD: CAPTURE & 100% GRIP INFO
        // ==========================================
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (currentStep == ScanStep.STEP_1_HANDLE_CAPTURE) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (snappedHandleBitmap == null) {
                                tracker.captureNextFrame = true
                            } else {
                                snappedHandleBitmap = null
                            }
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            text = if (snappedHandleBitmap == null) "TAKE PIC" else "RETAKE PIC",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (snappedHandleBitmap != null) {
                        Button(
                            onClick = {
                                tracker.resetSmoothing()
                                lockScore = 0
                                currentStep = ScanStep.STEP_2_HAND_PLACEMENT
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = currentTarget.bevelColor,
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = "NEXT: PLACE HAND →",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC121A1E))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (snappedHandleBitmap == null) {
                                "Place the bottom of your racket handle in the silhouette and tap TAKE PIC."
                            } else {
                                "Pic taken! Go to hand placement and put your ${if (isLeftHanded) "left" else "right"} base knuckle on Bevel ${currentTarget.targetBevel}."
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            currentStep = ScanStep.STEP_1_HANDLE_CAPTURE
                            lockScore = 0
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            text = "← BACK TO HANDLE",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC121A1E))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            GripMatchGauge(
                                score = lockScore,
                                ringColor = if (lockScore == 100) Color(0xFF00FF88) else currentTarget.bevelColor
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        lockScore == 100 -> "100% — ${currentTarget.gripName}"
                                        !handState.isHandVisible -> "Show your ${if (isLeftHanded) "left" else "right"} hand on the grip"
                                        else -> "Place your base knuckle dot on Bevel ${currentTarget.targetBevel}"
                                    },
                                    color = if (lockScore == 100) Color(0xFF00FF88) else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = if (lockScore == 100) {
                                        currentTarget.fullGripInfo
                                    } else {
                                        currentTarget.shortInstruction
                                    },
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. BOTTOM-OF-HANDLE SILHOUETTE + BEVELS 1-8
// ==========================================

@Composable
fun HandleBottomBevelOverlay(
    targetBevel: Int,
    targetColor: Color,
    isLeftHanded: Boolean,
    isLocked: Boolean,
    showBottomSilhouette: Boolean,
    onTargetPointComputed: (Offset, Size) -> Unit
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val centerX = if (isLeftHanded) w * 0.55f else w * 0.45f
        val centerY = h * 0.50f
        val tiltDegrees = if (isLeftHanded) -10f else 10f

        val capWidth = w * 0.17f
        val capHeight = h * 0.16f
        val bevelRingRadius = w * 0.21f

        val targetAngleRad = bevelAngleDegrees(targetBevel, isLeftHanded) * PI / 180.0
        val targetPointPx = Offset(
            x = centerX + (bevelRingRadius * cos(targetAngleRad)).toFloat(),
            y = centerY + (bevelRingRadius * sin(targetAngleRad)).toFloat()
        )
        onTargetPointComputed(targetPointPx, size)

        if (showBottomSilhouette) {
            rotate(degrees = tiltDegrees, pivot = Offset(centerX, centerY)) {
                val left = centerX - capWidth * 0.5f
                val right = centerX + capWidth * 0.5f
                val top = centerY - capHeight * 0.55f
                val bottom = centerY + capHeight * 0.55f
                val flare = capWidth * 0.16f

                val bottomCapPath = Path().apply {
                    moveTo(left, top)
                    lineTo(right, top)
                    lineTo(right + flare, bottom - 8.dp.toPx())
                    quadraticBezierTo(
                        right + flare,
                        bottom,
                        right - 6.dp.toPx(),
                        bottom
                    )
                    lineTo(left + 6.dp.toPx(), bottom)
                    quadraticBezierTo(
                        left - flare,
                        bottom,
                        left - flare,
                        bottom - 8.dp.toPx()
                    )
                    close()
                }

                drawPath(
                    path = bottomCapPath,
                    color = Color.White.copy(alpha = 0.28f),
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f), 0f)
                    )
                )
            }
        }

        val textPaint = android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 11.sp.toPx()
            isFakeBoldText = true
            isAntiAlias = true
        }

        for (bevel in 1..8) {
            val angleRad = bevelAngleDegrees(bevel, isLeftHanded) * PI / 180.0
            val bx = centerX + (bevelRingRadius * cos(angleRad)).toFloat()
            val by = centerY + (bevelRingRadius * sin(angleRad)).toFloat()

            val isTarget = (bevel == targetBevel)
            val color = BEVEL_COLORS.getValue(bevel)

            if (isTarget) {
                drawCircle(
                    color = if (isLocked) Color(0x3800FF88) else targetColor.copy(alpha = 0.28f),
                    radius = 18.dp.toPx(),
                    center = Offset(bx, by)
                )
            }

            drawCircle(
                color = if (isTarget) color else Color(0xCC10181C),
                radius = if (isTarget) 12.dp.toPx() else 9.dp.toPx(),
                center = Offset(bx, by)
            )
            drawCircle(
                color = color,
                radius = if (isTarget) 12.dp.toPx() else 9.dp.toPx(),
                center = Offset(bx, by),
                style = Stroke(width = 1.5.dp.toPx())
            )

            textPaint.color = if (isTarget) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            drawContext.canvas.nativeCanvas.drawText(
                bevel.toString(),
                bx,
                by + 4.dp.toPx(),
                textPaint
            )
        }
    }
}

// ==========================================
// 5. BASE KNUCKLE SPOT (INSIDE OR OUTSIDE HAND)
// ==========================================

@Composable
fun BaseKnuckleSpotOverlay(
    landmarks: List<SmoothedPoint>,
    knuckleColor: Color,
    isLocked: Boolean
) {
    if (landmarks.size < 21) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val knucklePoint = Offset(landmarks[5].x * w, landmarks[5].y * h)
        val activeColor = if (isLocked) Color(0xFF00FF88) else knuckleColor

        drawCircle(
            color = activeColor.copy(alpha = 0.30f),
            radius = 20.dp.toPx(),
            center = knucklePoint
        )
        drawCircle(
            color = activeColor,
            radius = 10.dp.toPx(),
            center = knucklePoint
        )
        drawCircle(
            color = Color.White,
            radius = 10.dp.toPx(),
            center = knucklePoint,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
fun GripMatchGauge(
    score: Int,
    ringColor: Color
) {
    val animatedSweep by animateFloatAsState(
        targetValue = (score.coerceIn(0, 100) / 100f) * 360f,
        label = "matchSweep"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(62.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = ringColor.copy(alpha = 0.22f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 6.dp.toPx())
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = animatedSweep,
                useCenter = false,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Text(
            text = "$score%",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1
        )
    }
}
