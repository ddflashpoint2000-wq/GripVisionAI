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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.hypot

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

enum class AppScreen {
    HANDEDNESS_START,
    HANDLE_CAPTURE,
    HAND_PLACEMENT,
    FINAL_KNUCKLE_CHECK,
    SUCCESS
}

data class GripTarget(
    val gripName: String,
    val targetBevel: Int,
    val bevelColor: Color,
    val shortInstruction: String,
    val fullGripInfo: String
)

val BEVEL_COLORS: Map<Int, Color> = mapOf(
    1 to Color(0xFFFF5252), // Red - Top Bevel 1
    2 to Color(0xFF00FF88), // Neon Green
    3 to Color(0xFF00E5FF), // Cyan
    4 to Color(0xFFFFD740), // Amber
    5 to Color(0xFFE040FB), // Purple
    6 to Color(0xFFFF6E40), // Deep Orange
    7 to Color(0xFF69F0AE), // Mint
    8 to Color(0xFF40C4FF)  // Sky Blue
)

val DOMINANT_HAND_COLOR = Color(0xFF00FF88) // Neon Green for dominant/anchor hand
val SUPPORT_HAND_COLOR = Color(0xFF00E5FF)  // Cyan for supporting top hand

fun getGripTarget(
    sport: GripSport,
    shot: ShotPreset,
    backhandStyle: BackhandStyle,
    isLeftHanded: Boolean
): GripTarget {
    val playerSide = if (isLeftHanded) "Left-Handed" else "Right-Handed"
    val anchorLabel = if (isLeftHanded) "left" else "right"
    val topHandLabel = if (isLeftHanded) "right" else "left"

    return when (sport) {
        GripSport.TENNIS -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Continental (Bevel 2): Universal hammer grip for serves, volleys, overheads, and slices with natural forearm pronation."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Semi-Western",
                targetBevel = 4,
                bevelColor = BEVEL_COLORS.getValue(4),
                shortInstruction = "Rotate your $anchorLabel index base knuckle onto Bevel 4.",
                fullGripInfo = "Semi-Western Forehand (Bevel 4): Positions the face slightly closed for heavy topspin and higher net clearance."
            )
            ShotPreset.BACKHAND_DINK -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "1H Eastern Backhand",
                    targetBevel = 1,
                    bevelColor = BEVEL_COLORS.getValue(1),
                    shortInstruction = "Place your $anchorLabel index base knuckle on top Bevel 1.",
                    fullGripInfo = "One-Handed Eastern Backhand ($playerSide): Places your $anchorLabel index base knuckle on top Bevel 1 to firm up the wrist for single-handed drives."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: green dot = $anchorLabel base knuckle, cyan dot = $topHandLabel top knuckle.",
                    fullGripInfo = "Two-Handed Backhand ($playerSide): Anchors your $anchorLabel index knuckle (green) near Bevel 2 while your $topHandLabel index knuckle (cyan) positions above for leverage and topspin."
                )
            }
        }
        GripSport.PADEL -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental Hammer",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Continental (Bevel 2): Hammer grip suited to bandejas, viboras, and quick exchanges at the net."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Keep your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Continental Forehand (Bevel 2): Keeps the face neutral for low glass rebounds and controlled blocks."
            )
            ShotPreset.BACKHAND_DINK -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "1H Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "Keep your $anchorLabel index base knuckle on Bevel 2.",
                    fullGripInfo = "One-Handed Padel Backhand ($playerSide): Supports clean slice control off the glass and relaxed wrist action."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: green dot = $anchorLabel base knuckle, cyan dot = $topHandLabel top knuckle.",
                    fullGripInfo = "Two-Handed Padel Backhand ($playerSide): Green spot marks your $anchorLabel anchor knuckle and cyan spot marks your $topHandLabel supporting knuckle."
                )
            }
        }
        GripSport.PICKLEBALL -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Pickleball Continental (Bevel 2): Neutral kitchen-line grip for fast resets, punch volleys, and blocks."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Eastern Forehand",
                targetBevel = 3,
                bevelColor = BEVEL_COLORS.getValue(3),
                shortInstruction = "Place your $anchorLabel index base knuckle on side Bevel 3.",
                fullGripInfo = "Pickleball Eastern Forehand (Bevel 3): Shake-hands grip for solid baseline drives and roll volleys."
            )
            ShotPreset.BACKHAND_DINK -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "1H Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                    fullGripInfo = "One-Handed Pickleball Backhand ($playerSide): Keeps the paddle face stable for soft, controlled dinks and blocks."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: green dot = $anchorLabel base knuckle, cyan dot = $topHandLabel top knuckle.",
                    fullGripInfo = "Two-Handed Pickleball Backhand ($playerSide): Green spot marks your $anchorLabel anchor knuckle and cyan spot marks your $topHandLabel top knuckle."
                )
            }
        }
    }
}

/**
 * Returns the (x, y) position on the butt-cap silhouette for bevel 1..8,
 * where Bevel 1 is always at the top (12 o'clock).
 * Right-handed goes clockwise (1 -> 2 -> 3 ... -> 8).
 * Left-handed goes anti-clockwise (1 -> 2 -> 3 ... -> 8).
 */
fun buttCapBevelPosition(
    bevel: Int,
    isLeftHanded: Boolean,
    centerX: Float,
    centerY: Float,
    halfW: Float,
    halfH: Float
): Offset {
    val b = bevel.coerceIn(1, 8)
    val slot = if (!isLeftHanded) (b - 1) else ((8 - (b - 1)) % 8)

    val cornerX = halfW * 0.72f
    val cornerY = halfH * 0.72f

    return when (slot) {
        0 -> Offset(centerX, centerY - halfH)                 // Top (Bevel 1)
        1 -> Offset(centerX + cornerX, centerY - cornerY)     // Top-right corner
        2 -> Offset(centerX + halfW, centerY)                 // Right side
        3 -> Offset(centerX + cornerX, centerY + cornerY)     // Bottom-right corner
        4 -> Offset(centerX, centerY + halfH)                 // Bottom
        5 -> Offset(centerX - cornerX, centerY + cornerY)     // Bottom-left corner
        6 -> Offset(centerX - halfW, centerY)                 // Left side
        else -> Offset(centerX - cornerX, centerY - cornerY)  // Top-left corner
    }
}

data class SmoothedPoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f
)

data class TrackedHand(
    val landmarks: List<SmoothedPoint>,
    val isLeftDetected: Boolean?
)

data class HandTrackingState(
    val hands: List<TrackedHand> = emptyList(),
    val isHandVisible: Boolean = false
)

// ==========================================
// 2. MEDIAPIPE HAND TRACKER (1 OR 2 HANDS)
// ==========================================

class GripHandTracker(
    context: Context,
    private val baseAlpha: Float = 0.52f,
    private val maxStepJump: Float = 0.16f,
    private val onHandUpdated: (HandTrackingState) -> Unit
) {
    var captureNextFrame: Boolean = false
    var onHandleSnapped: ((Bitmap) -> Unit)? = null

    private var handLandmarker: HandLandmarker? = null
    private var smoothedSlots: MutableList<List<SmoothedPoint>> = mutableListOf()
    private var lastValidTimestampMs: Long = 0L
    private val dropoutGraceMs: Long = 280L

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.24f)
                .setMinHandPresenceConfidence(0.24f)
                .setMinTrackingConfidence(0.26f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun processFrame(imageProxy: ImageProxy, runHandDetection: Boolean, maxHands: Int) {
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

            val rawHands = result.landmarks().take(maxHands.coerceIn(1, 2))
            val handednessLists: List<List<Category>> = result.handednesses()

            if (rawHands.isNotEmpty()) {
                val updatedSlots = mutableListOf<List<SmoothedPoint>>()
                val trackedHands = rawHands.mapIndexed { slotIdx, lmList ->
                    val prevSlot = smoothedSlots.getOrNull(slotIdx)
                    val smoothed = smoothHand(lmList, prevSlot)
                    updatedSlots.add(smoothed)

                    val handednessLabel = handednessLists
                        .getOrNull(slotIdx)
                        ?.firstOrNull()
                        ?.categoryName()
                        ?.lowercase()

                    val isLeft = when {
                        handednessLabel == null -> null
                        handednessLabel.contains("left") -> true
                        handednessLabel.contains("right") -> false
                        else -> null
                    }
                    TrackedHand(landmarks = smoothed, isLeftDetected = isLeft)
                }
                smoothedSlots = updatedSlots
                lastValidTimestampMs = now
                onHandUpdated(HandTrackingState(hands = trackedHands, isHandVisible = true))
            } else {
                if (smoothedSlots.isNotEmpty() && (now - lastValidTimestampMs) <= dropoutGraceMs) {
                    val cachedHands = smoothedSlots.take(maxHands).map { slot ->
                        TrackedHand(landmarks = slot, isLeftDetected = null)
                    }
                    onHandUpdated(HandTrackingState(hands = cachedHands, isHandVisible = true))
                } else {
                    smoothedSlots.clear()
                    onHandUpdated(HandTrackingState(hands = emptyList(), isHandVisible = false))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    private fun smoothHand(
        raw: List<NormalizedLandmark>,
        prev: List<SmoothedPoint>?
    ): List<SmoothedPoint> {
        if (prev == null || prev.size != raw.size) {
            return raw.map { lm -> SmoothedPoint(lm.x(), lm.y(), lm.z()) }
        }

        return raw.mapIndexed { idx, lm ->
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

            val speedFactor = (dist / 0.08f).coerceIn(0.45f, 1.35f)
            val alpha = (baseAlpha * speedFactor).coerceIn(0.22f, 0.72f)

            SmoothedPoint(
                x = alpha * clampedX + (1f - alpha) * p.x,
                y = alpha * clampedY + (1f - alpha) * p.y,
                z = alpha * lm.z() + (1f - alpha) * p.z
            )
        }
    }

    fun resetSmoothing() {
        smoothedSlots.clear()
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
// 3. GUIDED WORKFLOW SCREENS
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

    var currentScreen by remember { mutableStateOf(AppScreen.HANDEDNESS_START) }
    var selectedSport by remember { mutableStateOf(GripSport.TENNIS) }
    var selectedShot by remember { mutableStateOf(ShotPreset.SERVE_VOLLEY) }
    var backhandStyle by remember { mutableStateOf(BackhandStyle.ONE_HANDED) }
    var isLeftHanded by remember { mutableStateOf(false) }
    var hasChosenHandedness by remember { mutableStateOf(false) }

    var snappedHandleBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var handState by remember { mutableStateOf(HandTrackingState()) }
    var lockScore by remember { mutableIntStateOf(0) }
    var verifiedStreak by remember { mutableIntStateOf(0) }

    val isBackhandSelected = selectedShot == ShotPreset.BACKHAND_DINK
    val isTwoHandedBackhand = isBackhandSelected && backhandStyle == BackhandStyle.TWO_HANDED

    val currentTarget = getGripTarget(selectedSport, selectedShot, backhandStyle, isLeftHanded)

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

    LaunchedEffect(currentScreen, isTwoHandedBackhand) {
        tracker.resetSmoothing()
        verifiedStreak = 0
    }

    LaunchedEffect(currentScreen, lockScore, handState.hands.size, isTwoHandedBackhand) {
        if (currentScreen == AppScreen.FINAL_KNUCKLE_CHECK) {
            val meetsRequirement = lockScore == 100 && (!isTwoHandedBackhand || handState.hands.size >= 2)
            if (meetsRequirement) {
                verifiedStreak += 1
                if (verifiedStreak >= 4) {
                    currentScreen = AppScreen.SUCCESS
                }
            } else {
                verifiedStreak = 0
            }
        }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            tracker.close()
        }
    }

    val runHandDetection = currentScreen == AppScreen.HAND_PLACEMENT ||
        currentScreen == AppScreen.FINAL_KNUCKLE_CHECK

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission && currentScreen != AppScreen.SUCCESS) {
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
                                            runHandDetection = runHandDetection,
                                            maxHands = if (isTwoHandedBackhand) 2 else 1
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
        } else if (!hasCameraPermission) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Camera permission is required to scan your racket and grip.",
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(text = "Grant Camera Permission", fontSize = 12.sp)
                }
            }
        }

        when (currentScreen) {
            AppScreen.HANDEDNESS_START -> {
                HandednessStartOverlay(
                    isLeftHanded = isLeftHanded,
                    hasChosenHandedness = hasChosenHandedness,
                    onSelectLeftHanded = {
                        isLeftHanded = true
                        hasChosenHandedness = true
                        snappedHandleBitmap = null
                        lockScore = 0
                        currentScreen = AppScreen.HANDLE_CAPTURE
                    },
                    onSelectRightHanded = {
                        isLeftHanded = false
                        hasChosenHandedness = true
                        snappedHandleBitmap = null
                        lockScore = 0
                        currentScreen = AppScreen.HANDLE_CAPTURE
                    }
                )
            }

            AppScreen.HANDLE_CAPTURE -> {
                if (snappedHandleBitmap != null) {
                    Image(
                        bitmap = snappedHandleBitmap!!.asImageBitmap(),
                        contentDescription = "Captured Racket Handle",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                ButtCapHandleOverlay(
                    targetBevel = currentTarget.targetBevel,
                    targetColor = currentTarget.bevelColor,
                    isLeftHanded = isLeftHanded,
                    isLocked = false,
                    showSilhouette = (snappedHandleBitmap == null),
                    onTargetPointComputed = { _, _ -> }
                )

                TopGripControlsHud(
                    isLeftHanded = isLeftHanded,
                    selectedSport = selectedSport,
                    selectedShot = selectedShot,
                    backhandStyle = backhandStyle,
                    isBackhandSelected = isBackhandSelected,
                    isTwoHandedBackhand = isTwoHandedBackhand,
                    currentTarget = currentTarget,
                    onToggleHandedness = { isLeftHanded = !isLeftHanded },
                    onSelectSport = { selectedSport = it },
                    onSelectShot = { selectedShot = it },
                    onSelectBackhandStyle = { backhandStyle = it }
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { currentScreen = AppScreen.HANDEDNESS_START },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = "← HANDEDNESS",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

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
                                    currentScreen = AppScreen.HAND_PLACEMENT
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = currentTarget.bevelColor,
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = "PLACE HAND →",
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
                        Text(
                            text = if (snappedHandleBitmap == null) {
                                "Line up your racket handle with top bevel 1 and tap TAKE PIC."
                            } else {
                                "Pic taken! Tap PLACE HAND to bring your hand onto the grip."
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            AppScreen.HAND_PLACEMENT -> {
                ButtCapHandleOverlay(
                    targetBevel = currentTarget.targetBevel,
                    targetColor = currentTarget.bevelColor,
                    isLeftHanded = isLeftHanded,
                    isLocked = (lockScore == 100),
                    showSilhouette = false,
                    onTargetPointComputed = { targetCenterPx: Offset, screenSize: Size ->
                        lockScore = computeKnuckleLockScore(
                            hands = handState.hands,
                            isLeftHanded = isLeftHanded,
                            isTwoHandedBackhand = isTwoHandedBackhand,
                            targetCenterPx = targetCenterPx,
                            screenSize = screenSize,
                            previousScore = lockScore
                        )
                    }
                )

                if (handState.isHandVisible) {
                    BaseKnuckleSpotOverlay(
                        hands = handState.hands,
                        isLeftHanded = isLeftHanded,
                        isTwoHandedBackhand = isTwoHandedBackhand,
                        singleHandColor = currentTarget.bevelColor,
                        lockScore = lockScore
                    )
                }

                TopGripControlsHud(
                    isLeftHanded = isLeftHanded,
                    selectedSport = selectedSport,
                    selectedShot = selectedShot,
                    backhandStyle = backhandStyle,
                    isBackhandSelected = isBackhandSelected,
                    isTwoHandedBackhand = isTwoHandedBackhand,
                    currentTarget = currentTarget,
                    onToggleHandedness = { isLeftHanded = !isLeftHanded },
                    onSelectSport = { selectedSport = it },
                    onSelectShot = { selectedShot = it },
                    onSelectBackhandStyle = { backhandStyle = it }
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                currentScreen = AppScreen.HANDLE_CAPTURE
                                lockScore = 0
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = "← HANDLE PIC",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                tracker.resetSmoothing()
                                verifiedStreak = 0
                                currentScreen = AppScreen.FINAL_KNUCKLE_CHECK
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00FF88),
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = "CHECK KNUCKLE →",
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            GripMatchGauge(
                                score = lockScore,
                                ringColor = if (lockScore == 100) Color(0xFF00FF88) else currentTarget.bevelColor
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (!handState.isHandVisible) {
                                        "Bring your ${if (isLeftHanded) "left" else "right"} hand onto the grip"
                                    } else {
                                        "Align your base knuckle dot with Bevel ${currentTarget.targetBevel}"
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = currentTarget.shortInstruction,
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            AppScreen.FINAL_KNUCKLE_CHECK -> {
                ButtCapHandleOverlay(
                    targetBevel = currentTarget.targetBevel,
                    targetColor = currentTarget.bevelColor,
                    isLeftHanded = isLeftHanded,
                    isLocked = (lockScore == 100),
                    showSilhouette = false,
                    onTargetPointComputed = { targetCenterPx: Offset, screenSize: Size ->
                        lockScore = computeKnuckleLockScore(
                            hands = handState.hands,
                            isLeftHanded = isLeftHanded,
                            isTwoHandedBackhand = isTwoHandedBackhand,
                            targetCenterPx = targetCenterPx,
                            screenSize = screenSize,
                            previousScore = lockScore
                        )
                    }
                )

                if (handState.isHandVisible) {
                    BaseKnuckleSpotOverlay(
                        hands = handState.hands,
                        isLeftHanded = isLeftHanded,
                        isTwoHandedBackhand = isTwoHandedBackhand,
                        singleHandColor = currentTarget.bevelColor,
                        lockScore = lockScore
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 10.dp, start = 14.dp, end = 14.dp)
                        .background(Color(0xCC10181C), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isTwoHandedBackhand) {
                            "Final Check • Verify ${if (isLeftHanded) "Left (Green)" else "Right (Green)"} & ${if (isLeftHanded) "Right (Cyan)" else "Left (Cyan)"} Knuckles"
                        } else {
                            "Final Check • Verify Base Knuckle on Bevel ${currentTarget.targetBevel}"
                        },
                        color = Color(0xFF00FF88),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { currentScreen = AppScreen.HAND_PLACEMENT },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = "← BACK",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { currentScreen = AppScreen.SUCCESS },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00FF88),
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = "CONFIRM 100% →",
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            GripMatchGauge(
                                score = lockScore,
                                ringColor = if (lockScore == 100) Color(0xFF00FF88) else currentTarget.bevelColor
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (lockScore == 100) {
                                        "Knuckle Verified — Moving to Success..."
                                    } else {
                                        "Check top knuckle spot on Bevel ${currentTarget.targetBevel}"
                                    },
                                    color = if (lockScore == 100) Color(0xFF00FF88) else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Hold the spot steady on Bevel ${currentTarget.targetBevel} to complete 100% verification.",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            AppScreen.SUCCESS -> {
                SuccessScreenView(
                    target = currentTarget,
                    sportLabel = selectedSport.label,
                    shotLabel = selectedShot.label,
                    isTwoHandedBackhand = isTwoHandedBackhand,
                    isLeftHanded = isLeftHanded,
                    onScanAgain = {
                        snappedHandleBitmap = null
                        lockScore = 0
                        verifiedStreak = 0
                        currentScreen = AppScreen.HANDLE_CAPTURE
                    },
                    onChangeHandedness = {
                        snappedHandleBitmap = null
                        lockScore = 0
                        verifiedStreak = 0
                        hasChosenHandedness = false
                        currentScreen = AppScreen.HANDEDNESS_START
                    }
                )
            }
        }
    }
}

private fun computeKnuckleLockScore(
    hands: List<TrackedHand>,
    isLeftHanded: Boolean,
    isTwoHandedBackhand: Boolean,
    targetCenterPx: Offset,
    screenSize: Size,
    previousScore: Int
): Int {
    if (hands.isEmpty()) return 0
    val primaryHand = selectAnchorHand(hands, isLeftHanded) ?: return 0
    if (primaryHand.landmarks.size < 21) return 0

    val knuckleLm = primaryHand.landmarks[5] // Index base knuckle
    val frameWidth = screenSize.width
    val frameHeight = screenSize.height
    val knucklePx = Offset(
        knuckleLm.x * frameWidth,
        knuckleLm.y * frameHeight
    )
    val distPx = hypot(
        (knucklePx.x - targetCenterPx.x).toDouble(),
        (knucklePx.y - targetCenterPx.y).toDouble()
    ).toFloat()

    val snapRadiusPx = frameWidth * 0.11f
    val releaseRadiusPx = frameWidth * 0.135f
    val maxRadiusPx = frameWidth * 0.34f
    val spanPx = (maxRadiusPx - snapRadiusPx).coerceAtLeast(1f)

    val baseScore = when {
        previousScore == 100 && distPx <= releaseRadiusPx -> 100
        distPx <= snapRadiusPx -> 100
        distPx >= maxRadiusPx -> 15
        else -> {
            val ratio = 1f - ((distPx - snapRadiusPx) / spanPx)
            (20f + ratio * 75f).toInt().coerceIn(15, 99)
        }
    }

    return if (isTwoHandedBackhand && baseScore == 100 && hands.size < 2) {
        92
    } else {
        baseScore
    }
}

private fun selectAnchorHand(hands: List<TrackedHand>, isLeftHanded: Boolean): TrackedHand? {
    if (hands.isEmpty()) return null
    val matching = hands.firstOrNull { hand ->
        hand.isLeftDetected != null && hand.isLeftDetected == isLeftHanded
    }
    return matching ?: hands.first()
}

// ==========================================
// 4. HANDEDNESS START SCREEN & SUCCESS SCREEN
// ==========================================

@Composable
fun HandednessStartOverlay(
    isLeftHanded: Boolean,
    hasChosenHandedness: Boolean,
    onSelectLeftHanded: () -> Unit,
    onSelectRightHanded: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .statusBarsPadding()
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "GripVision AI",
                color = Color(0xFF00FF88),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Select Your Dominant Hand",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HandChoiceCard(
                    title = "LEFT HANDED",
                    selected = hasChosenHandedness && isLeftHanded,
                    onClick = onSelectLeftHanded,
                    modifier = Modifier.weight(1f)
                )

                HandChoiceCard(
                    title = "RIGHT HANDED",
                    selected = hasChosenHandedness && !isLeftHanded,
                    onClick = onSelectRightHanded,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HandChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF00FF88) else Color(0xFF101416)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                color = if (selected) Color.Black else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SuccessScreenView(
    target: GripTarget,
    sportLabel: String,
    shotLabel: String,
    isTwoHandedBackhand: Boolean,
    isLeftHanded: Boolean,
    onScanAgain: () -> Unit,
    onChangeHandedness: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1114))
            .statusBarsPadding()
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GripMatchGauge(
                score = 100,
                ringColor = Color(0xFF00FF88)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "100% GRIP LOCKED",
                color = Color(0xFF00FF88),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "$sportLabel • $shotLabel • ${if (isLeftHanded) "Lefty" else "Righty"}",
                color = Color.LightGray,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141E24))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "${target.gripName} • Target Bevel ${target.targetBevel}",
                        color = target.bevelColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = target.fullGripInfo,
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    if (isTwoHandedBackhand) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(DOMINANT_HAND_COLOR, CircleShape)
                            )
                            Text(
                                text = "${if (isLeftHanded) "Left" else "Right"} base knuckle (anchor)",
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(SUPPORT_HAND_COLOR, CircleShape)
                            )
                            Text(
                                text = "${if (isLeftHanded) "Right" else "Left"} top knuckle (support)",
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onChangeHandedness,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "HANDEDNESS",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Button(
                    onClick = onScanAgain,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00FF88),
                        contentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "CHECK ANOTHER GRIP",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ==========================================
// 5. TOP HUD, BUTT-CAP & KNUCKLE OVERLAYS
// ==========================================

@Composable
private fun TopGripControlsHud(
    isLeftHanded: Boolean,
    selectedSport: GripSport,
    selectedShot: ShotPreset,
    backhandStyle: BackhandStyle,
    isBackhandSelected: Boolean,
    isTwoHandedBackhand: Boolean,
    currentTarget: GripTarget,
    onToggleHandedness: () -> Unit,
    onSelectSport: (GripSport) -> Unit,
    onSelectShot: (ShotPreset) -> Unit,
    onSelectBackhandStyle: (BackhandStyle) -> Unit
) {
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
            Box(
                modifier = Modifier
                    .background(Color(0xBB10181C), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "GripVision AI",
                    color = Color(0xFF00FF88),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            FilterChip(
                selected = isLeftHanded,
                onClick = onToggleHandedness,
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
                    onClick = { onSelectSport(sport) },
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
                    onClick = { onSelectShot(shot) },
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
        }

        if (isBackhandSelected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackhandStyle.entries.forEach { style ->
                    val selected = backhandStyle == style
                    val labelText = if (style == BackhandStyle.ONE_HANDED) {
                        "1H (1 Hand)"
                    } else {
                        "2H (2 Hands)"
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .background(
                                color = if (selected) Color(0xFFFFD740) else Color(0xCC121A1E),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectBackhandStyle(style) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = labelText,
                            color = if (selected) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
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

            if (isTwoHandedBackhand) {
                val anchorHandName = if (isLeftHanded) "L" else "R"
                val topHandName = if (isLeftHanded) "R" else "L"
                Box(
                    modifier = Modifier
                        .background(Color(0xBB10181C), RoundedCornerShape(14.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$anchorHandName: Green • $topHandName: Cyan",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun ButtCapHandleOverlay(
    targetBevel: Int,
    targetColor: Color,
    isLeftHanded: Boolean,
    isLocked: Boolean,
    showSilhouette: Boolean,
    onTargetPointComputed: (Offset, Size) -> Unit
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val centerX = w * 0.50f
        val centerY = h * 0.50f

        val halfW = w * 0.24f
        val halfH = h * 0.145f
        val bevelCutX = halfW * 0.26f
        val bevelCutY = halfH * 0.34f

        val targetPos = buttCapBevelPosition(
            bevel = targetBevel,
            isLeftHanded = isLeftHanded,
            centerX = centerX,
            centerY = centerY,
            halfW = halfW,
            halfH = halfH
        )
        onTargetPointComputed(targetPos, size)

        if (showSilhouette) {
            val capPath = Path().apply {
                moveTo(centerX - halfW + bevelCutX, centerY - halfH)
                lineTo(centerX + halfW - bevelCutX, centerY - halfH)
                lineTo(centerX + halfW, centerY - halfH + bevelCutY)
                lineTo(centerX + halfW, centerY + halfH - bevelCutY)
                lineTo(centerX + halfW - bevelCutX, centerY + halfH)
                lineTo(centerX - halfW + bevelCutX, centerY + halfH)
                lineTo(centerX - halfW, centerY + halfH - bevelCutY)
                lineTo(centerX - halfW, centerY - halfH + bevelCutY)
                close()
            }

            drawPath(
                path = capPath,
                color = Color.White.copy(alpha = 0.32f),
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f), 0f)
                )
            )
        }

        val textPaint = android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 11.sp.toPx()
            isFakeBoldText = true
            isAntiAlias = true
        }

        for (bevel in 1..8) {
            val pos = buttCapBevelPosition(
                bevel = bevel,
                isLeftHanded = isLeftHanded,
                centerX = centerX,
                centerY = centerY,
                halfW = halfW,
                halfH = halfH
            )

            val isTarget = (bevel == targetBevel)
            val color = BEVEL_COLORS.getValue(bevel)

            if (isTarget) {
                drawCircle(
                    color = if (isLocked) Color(0x3800FF88) else targetColor.copy(alpha = 0.28f),
                    radius = 18.dp.toPx(),
                    center = pos
                )
            }

            drawCircle(
                color = if (isTarget) color else Color(0xCC10181C),
                radius = if (isTarget) 12.dp.toPx() else 9.dp.toPx(),
                center = pos
            )
            drawCircle(
                color = color,
                radius = if (isTarget) 12.dp.toPx() else 9.dp.toPx(),
                center = pos,
                style = Stroke(width = 1.5.dp.toPx())
            )

            textPaint.color = if (isTarget) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            drawContext.canvas.nativeCanvas.drawText(
                bevel.toString(),
                pos.x,
                pos.y + 4.dp.toPx(),
                textPaint
            )
        }
    }
}

@Composable
fun BaseKnuckleSpotOverlay(
    hands: List<TrackedHand>,
    isLeftHanded: Boolean,
    isTwoHandedBackhand: Boolean,
    singleHandColor: Color,
    lockScore: Int
) {
    if (hands.isEmpty()) return

    val isLocked = lockScore == 100
    val proximityRatio = (lockScore / 100f).coerceIn(0f, 1f)
    val anchorHand = selectAnchorHand(hands, isLeftHanded)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        hands.forEach { hand ->
            if (hand.landmarks.size < 21) return@forEach
            val knucklePoint = Offset(hand.landmarks[5].x * w, hand.landmarks[5].y * h)

            val isAnchor = (hand === anchorHand)
            val spotColor = when {
                !isTwoHandedBackhand -> if (isLocked) Color(0xFF00FF88) else singleHandColor
                isAnchor -> if (isLocked) Color(0xFF00FF88) else DOMINANT_HAND_COLOR
                else -> SUPPORT_HAND_COLOR
            }

            val outerRingRadius = if (isAnchor || !isTwoHandedBackhand) {
                (26.dp - (8.dp * proximityRatio)).toPx()
            } else {
                20.dp.toPx()
            }

            drawCircle(
                color = spotColor.copy(alpha = if (isLocked && isAnchor) 0.35f else 0.24f),
                radius = outerRingRadius,
                center = knucklePoint
            )
            drawCircle(
                color = spotColor.copy(alpha = 0.9f),
                radius = outerRingRadius,
                center = knucklePoint,
                style = Stroke(width = if (isLocked && isAnchor) 2.5.dp.toPx() else 1.5.dp.toPx())
            )

            drawCircle(
                color = spotColor,
                radius = 9.5.dp.toPx(),
                center = knucklePoint
            )
            drawCircle(
                color = Color.White,
                radius = 9.5.dp.toPx(),
                center = knucklePoint,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = 2.5.dp.toPx(),
                center = knucklePoint
            )
        }
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
