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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
                fullGripInfo = "Continental (Bevel 2): The universal hammer grip for serves, volleys, overheads, and slices. Enables natural forearm pronation and an open racquet face at the net."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Semi-Western",
                targetBevel = 4,
                bevelColor = BEVEL_COLORS.getValue(4),
                shortInstruction = "Rotate your index base knuckle onto Bevel 4.",
                fullGripInfo = "Semi-Western Forehand (Bevel 4): Positions the racquet face slightly closed for heavy topspin, higher net clearance, and comfortable contact on waist-to-shoulder balls."
            )
            ShotPreset.BACKHAND_DINK -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "Eastern Backhand (1H)",
                    targetBevel = 1,
                    bevelColor = BEVEL_COLORS.getValue(1),
                    shortInstruction = "Place your index base knuckle on top Bevel 1.",
                    fullGripInfo = "One-Handed Eastern Backhand (Bevel 1): Places your index knuckle squarely on the top flat bevel, locking the wrist behind the handle for crisp single-handed drives and topspin."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "Continental Anchor (2H)",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "Place your bottom index base knuckle on Bevel 2.",
                    fullGripInfo = "Two-Handed Backhand Bottom Hand (Bevel 2): Anchors your dominant bottom hand in Continental on Bevel 2 while your non-dominant top hand supplies power and topspin."
                )
            }
        }
        GripSport.PADEL -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental Hammer",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Continental (Bevel 2): Essential hammer grip for bandeja, vibora, and quick net exchanges without needing mid-rally grip changes."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Keep your index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Continental Forehand (Bevel 2): Keeps the paddle face neutral for low glass rebounds, chiquitas, and fast defensive blocks."
            )
            ShotPreset.BACKHAND_DINK -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Keep your index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Continental Backhand (Bevel 2): Gives clean slice control off the back glass and stability on fast body volleys."
            )
        }
        GripSport.PICKLEBALL -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your index base knuckle on Bevel 2.",
                fullGripInfo = "Pickleball Continental (Bevel 2): Ideal at the non-volley zone (kitchen) for rapid forehand/backhand resets, punch volleys, and blocks."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Eastern Forehand",
                targetBevel = 3,
                bevelColor = BEVEL_COLORS.getValue(3),
                shortInstruction = "Place your index base knuckle on side Bevel 3.",
                fullGripInfo = "Pickleball Eastern Forehand (Bevel 3): Natural handshake grip that delivers flat power and topspin on third-shot drives and passing shots."
            )
            ShotPreset.BACKHAND_DINK -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your index base knuckle on Bevel 2.",
                fullGripInfo = "Pickleball Continental Dink (Bevel 2): Keeps the wrist quiet and the paddle face slightly open for soft, unattackable cross-court dinks."
            )
        }
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

            // Lower confidence thresholds help retain tracking when gripping a handle
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.28f)
                .setMinHandPresenceConfidence(0.28f)
                .setMinTrackingConfidence(0.28f)
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

    // User Selections
    var selectedSport by remember { mutableStateOf(GripSport.TENNIS) }
    var selectedShot by remember { mutableStateOf(ShotPreset.SERVE_VOLLEY) }
    var backhandStyle by remember { mutableStateOf(BackhandStyle.ONE_HANDED) }
    var isLeftHanded by remember { mutableStateOf(false) }

    // 2-Step Workflow State
    var currentStep by remember { mutableStateOf(ScanStep.STEP_1_HANDLE_CAPTURE) }
    var snappedHandleBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var handState by remember { mutableStateOf(HandTrackingState()) }
    var lockScore by remember { mutableIntStateOf(0) }
    var showHandOutline by remember { mutableStateOf(true) }

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
        // Camera Feed (Live in Step 1 until snapped, and Live in Step 2 for Hand Placement)
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

        // In Step 1, once the user snaps the handle photo, freeze that snapshot so they can inspect the bevel lines
        if (currentStep == ScanStep.STEP_1_HANDLE_CAPTURE && snappedHandleBitmap != null) {
            Image(
                bitmap = snappedHandleBitmap!!.asImageBitmap(),
                contentDescription = "Captured Racket Handle",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Upright Handle Guide + Colored/Numbered Bevel Overlay (Shown in both Step 1 and Step 2)
        UprightHandleBevelOverlay(
            targetBevel = currentTarget.targetBevel,
            targetColor = currentTarget.bevelColor,
            isLeftHanded = isLeftHanded,
            isLocked = (currentStep == ScanStep.STEP_2_HAND_PLACEMENT && lockScore == 100),
            onTargetPointComputed = { targetCenterPx, screenSize ->
                if (currentStep == ScanStep.STEP_2_HAND_PLACEMENT && handState.landmarks.size >= 21) {
                    val knuckleLm = handState.landmarks[5] // Index Base Knuckle
                    val knucklePx = Offset(
                        knuckleLm.x * screenSize.width,
                        knuckleLm.y * screenSize.height
                    )
                    val distPx = hypot(
                        (knucklePx.x - targetCenterPx.x).toDouble(),
                        (knucklePx.y - targetCenterPx.y).toDouble()
                    ).toFloat()

                    val snapRadiusPx = screenSize.width * 0.11f
                    val maxRadiusPx = screenSize.width * 0.34f
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

        // Step 2 Live Hand Overlay with Matching Colored Spot on Base Knuckle (Landmark 5)
        if (currentStep == ScanStep.STEP_2_HAND_PLACEMENT && handState.isHandVisible) {
            HandKnuckleOverlay(
                landmarks = handState.landmarks,
                knuckleTargetColor = currentTarget.bevelColor,
                isLocked = (lockScore == 100),
                showFingerContour = showHandOutline
            )
        }

        // ==========================================
        // TOP HUD: STEP BADGE, SPORT, SHOT & 1H/2H
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "GripVision AI",
                        color = Color(0xFF00FF88),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .background(Color(0xAA10181C), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (currentStep == ScanStep.STEP_1_HANDLE_CAPTURE) {
                                "STEP 1: HANDLE"
                            } else {
                                "STEP 2: KNUCKLE"
                            },
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (currentStep == ScanStep.STEP_2_HAND_PLACEMENT) {
                        FilterChip(
                            selected = showHandOutline,
                            onClick = { showHandOutline = !showHandOutline },
                            label = { Text("Fingers", fontSize = 10.sp, maxLines = 1) },
                            modifier = Modifier.height(28.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0x99121A1E),
                                labelColor = Color.LightGray,
                                selectedContainerColor = Color(0xFF00E5FF),
                                selectedLabelColor = Color.Black
                            )
                        )
                    }
                    FilterChip(
                        selected = isLeftHanded,
                        onClick = { isLeftHanded = !isLeftHanded },
                        label = {
                            Text(
                                text = if (isLeftHanded) "Lefty" else "Righty",
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
            }

            // Sport Selector
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

            // Shot Selector + 1H / 2H Toggle on Tennis Backhand
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

            // Target Bevel Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xBB10181C), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${currentTarget.gripName} • Target Bevel ${currentTarget.targetBevel}",
                        color = currentTarget.bevelColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // ==========================================
        // BOTTOM CARD: STEP 1 CAPTURE vs STEP 2 LOCK
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
                            text = if (snappedHandleBitmap == null) "SNAP HANDLE" else "RETAKE PHOTO",
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
                                "1. Hold your racket upright along the tilted guide and tap SNAP HANDLE."
                            } else {
                                "Handle captured! Put your ${if (isLeftHanded) "LEFT" else "RIGHT"} index base knuckle on Bevel ${currentTarget.targetBevel} (${currentTarget.gripName})."
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            } else {
                // STEP 2: Hand Placement Screen
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
                                        lockScore == 100 -> "100% LOCKED — ${currentTarget.gripName.uppercase()}"
                                        !handState.isHandVisible -> "Bring your ${if (isLeftHanded) "left" else "right"} hand onto the handle"
                                        else -> "Align the glowing knuckle dot with Bevel ${currentTarget.targetBevel}"
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
// 4. UPRIGHT HANDLE GUIDE + NUMBERED BEVELS
// ==========================================

@Composable
fun UprightHandleBevelOverlay(
    targetBevel: Int,
    targetColor: Color,
    isLeftHanded: Boolean,
    isLocked: Boolean,
    onTargetPointComputed: (Offset, Size) -> Unit
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Offset slightly to the side so the upright racket sits naturally in frame
        val handleCenterX = if (isLeftHanded) w * 0.56f else w * 0.44f
        val handleCenterY = h * 0.48f
        val tiltDegrees = if (isLeftHanded) -12f else 12f

        val handleWidth = w * 0.17f
        val handleHeight = h * 0.34f
        val dialRadius = w * 0.21f

        val effectiveBevel = if (isLeftHanded && targetBevel in 2..4) (10 - targetBevel) else targetBevel
        val targetAngleRad = ((effectiveBevel - 1) * 45.0 - 90.0) * PI / 180.0
        val targetTargetOffset = Offset(
            x = handleCenterX + (dialRadius * cos(targetAngleRad)).toFloat(),
            y = handleCenterY + (dialRadius * sin(targetAngleRad)).toFloat()
        )
        onTargetPointComputed(targetTargetOffset, size)

        // Draw tilted upright handle silhouette
        rotate(degrees = tiltDegrees, pivot = Offset(handleCenterX, handleCenterY)) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
            drawRoundRect(
                color = if (isLocked) Color(0x9900FF88) else Color(0x66FFFFFF),
                topLeft = Offset(handleCenterX - handleWidth * 0.5f, handleCenterY - handleHeight * 0.5f),
                size = Size(handleWidth, handleHeight),
                cornerRadius = CornerRadius(22f, 22f),
                style = Stroke(width = 3.dp.toPx(), pathEffect = if (isLocked) null else dash)
            )

            // Center axis line along the upright handle
            drawLine(
                color = Color(0x44FFFFFF),
                start = Offset(handleCenterX, handleCenterY - handleHeight * 0.45f),
                end = Offset(handleCenterX, handleCenterY + handleHeight * 0.45f),
                strokeWidth = 2.dp.toPx()
            )
        }

        // Draw 8 color-coded & numbered bevel markers around the handle
        val textPaint = android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 11.sp.toPx()
            isFakeBoldText = true
            isAntiAlias = true
        }

        for (bevel in 1..8) {
            val displayBevel = if (isLeftHanded && bevel in 2..4) (10 - bevel) else bevel
            val angleRad = ((displayBevel - 1) * 45.0 - 90.0) * PI / 180.0
            val bx = handleCenterX + (dialRadius * cos(angleRad)).toFloat()
            val by = handleCenterY + (dialRadius * sin(angleRad)).toFloat()
            val innerX = handleCenterX + ((dialRadius - 18.dp.toPx()) * cos(angleRad)).toFloat()
            val innerY = handleCenterY + ((dialRadius - 18.dp.toPx()) * sin(angleRad)).toFloat()

            val isTarget = (bevel == targetBevel)
            val color = BEVEL_COLORS.getValue(bevel)

            // Colored bevel spoke line
            drawLine(
                color = if (isTarget) color else color.copy(alpha = 0.45f),
                start = Offset(innerX, innerY),
                end = Offset(bx, by),
                strokeWidth = if (isTarget) 4.dp.toPx() else 2.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Outer glow ring for the target bevel
            if (isTarget) {
                drawCircle(
                    color = targetColor.copy(alpha = 0.28f),
                    radius = 18.dp.toPx(),
                    center = Offset(bx, by)
                )
            }

            // Numbered bevel badge
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
// 5. STEP 2 HAND + BASE KNUCKLE SPOT OVERLAY
// ==========================================

@Composable
fun HandKnuckleOverlay(
    landmarks: List<SmoothedPoint>,
    knuckleTargetColor: Color,
    isLocked: Boolean,
    showFingerContour: Boolean
) {
    if (landmarks.size < 21) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        fun pt(index: Int): Offset = Offset(landmarks[index].x * w, landmarks[index].y * h)

        val wrist = pt(0)
        val indexBaseKnuckle = pt(5) // MCP joint of index finger

        if (showFingerContour) {
            fun sideOffset(point: Offset, dirFrom: Offset, dirTo: Offset, widthPx: Float): Offset {
                val vx = dirTo.x - dirFrom.x
                val vy = dirTo.y - dirFrom.y
                val len = hypot(vx.toDouble(), vy.toDouble()).toFloat().coerceAtLeast(0.0001f)
                val nx = -vy / len
                val ny = vx / len
                return Offset(point.x + nx * widthPx, point.y + ny * widthPx)
            }

            fun appendFingerContour(
                path: Path,
                joints: List<Offset>,
                halfWidthPx: Float
            ) {
                if (joints.size < 2) return
                val leftSide = mutableListOf<Offset>()
                val rightSide = mutableListOf<Offset>()

                for (i in joints.indices) {
                    val curr = joints[i]
                    val prev = joints.getOrElse(i - 1) { curr }
                    val next = joints.getOrElse(i + 1) { curr }
                    val tipTaper = if (i == joints.lastIndex) 0.4f else if (i == joints.lastIndex - 1) 0.85f else 1f
                    val hw = halfWidthPx * tipTaper

                    if (i == joints.lastIndex) {
                        leftSide.add(sideOffset(curr, prev, curr, -hw))
                        leftSide.add(curr)
                        rightSide.add(sideOffset(curr, prev, curr, hw))
                    } else {
                        leftSide.add(sideOffset(curr, prev, next, -hw))
                        rightSide.add(sideOffset(curr, prev, next, hw))
                    }
                }

                val loop = leftSide + rightSide.asReversed()
                if (loop.isEmpty()) return
                path.lineTo(loop.first().x, loop.first().y)
                for (i in 1 until loop.lastIndex) {
                    val p1 = loop[i]
                    val p2 = loop[i + 1]
                    path.quadraticBezierTo(p1.x, p1.y, (p1.x + p2.x) * 0.5f, (p1.y + p2.y) * 0.5f)
                }
                path.lineTo(loop.last().x, loop.last().y)
            }

            val thumbChain = listOf(pt(1), pt(2), pt(3), pt(4))
            val indexChain = listOf(pt(5), pt(6), pt(7), pt(8))
            val middleChain = listOf(pt(9), pt(10), pt(11), pt(12))
            val ringChain = listOf(pt(13), pt(14), pt(15), pt(16))
            val pinkyChain = listOf(pt(17), pt(18), pt(19), pt(20))

            val fingerHalfWidth = 6.dp.toPx()
            val thumbHalfWidth = 7.dp.toPx()

            val outlinePath = Path().apply {
                moveTo(wrist.x, wrist.y)
                appendFingerContour(this, thumbChain, thumbHalfWidth)
                lineTo(indexChain.first().x, indexChain.first().y)
                appendFingerContour(this, indexChain, fingerHalfWidth)
                lineTo(middleChain.first().x, middleChain.first().y)
                appendFingerContour(this, middleChain, fingerHalfWidth)
                lineTo(ringChain.first().x, ringChain.first().y)
                appendFingerContour(this, ringChain, fingerHalfWidth)
                lineTo(pinkyChain.first().x, pinkyChain.first().y)
                appendFingerContour(this, pinkyChain, fingerHalfWidth * 0.9f)
                lineTo(wrist.x, wrist.y)
            }

            val contourColor = if (isLocked) Color(0xFF00FF88) else Color.White
            val strokeBrush = Brush.linearGradient(
                colors = listOf(
                    contourColor.copy(alpha = 0.05f),
                    contourColor.copy(alpha = 0.55f),
                    contourColor.copy(alpha = 0.85f)
                ),
                start = wrist,
                end = pt(12)
            )

            drawPath(
                path = outlinePath,
                brush = strokeBrush,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Highlighted Base Knuckle (Landmark 5) matching the target bevel color
        drawCircle(
            color = knuckleTargetColor.copy(alpha = 0.32f),
            radius = 18.dp.toPx(),
            center = indexBaseKnuckle
        )
        drawCircle(
            color = knuckleTargetColor,
            radius = 9.dp.toPx(),
            center = indexBaseKnuckle
        )
        drawCircle(
            color = Color.White,
            radius = 9.dp.toPx(),
            center = indexBaseKnuckle,
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
