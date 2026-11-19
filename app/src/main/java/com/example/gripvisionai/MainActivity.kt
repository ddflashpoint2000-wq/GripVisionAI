package com.example.gripvisionai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// ==========================================
// 1. SPORT & SHOT GRIP PRESETS
// ==========================================

enum class GripSport(val label: String) {
    TENNIS("Tennis"),
    PADEL("Padel"),
    PICKLEBALL("Pickleball")
}

enum class ShotPreset(val label: String) {
    SERVE_VOLLEY("Serve / Volley"),
    FOREHAND("Forehand"),
    BACKHAND("Backhand")
}

data class GripTarget(
    val gripName: String,
    val targetBevel: Int,
    val targetAngleDeg: Float,
    val tip: String
)

fun getGripTarget(sport: GripSport, shot: ShotPreset): GripTarget {
    return when (sport) {
        GripSport.TENNIS -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                targetAngleDeg = -45f,
                tip = "Place index base knuckle on Bevel 2 (hammer grip) for pronation & slice"
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Semi-Western",
                targetBevel = 4,
                targetAngleDeg = 45f,
                tip = "Rotate index knuckle to Bevel 4 (bottom-right) for heavy topspin"
            )
            ShotPreset.BACKHAND -> GripTarget(
                gripName = "Eastern Backhand",
                targetBevel = 1,
                targetAngleDeg = -90f,
                tip = "Place index knuckle on Bevel 1 (top bevel) for firm backhand drive"
            )
        }
        GripSport.PADEL -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental Hammer",
                targetBevel = 2,
                targetAngleDeg = -45f,
                tip = "Hold like a hammer on Bevel 2 — never switch to an Eastern forehand grip"
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                targetAngleDeg = -45f,
                tip = "Keep Continental Bevel 2 for fast wall rebounds and quick volleys"
            )
            ShotPreset.BACKHAND -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                targetAngleDeg = -45f,
                tip = "Stay on Bevel 2 with a relaxed wrist for bandeja and glass defense"
            )
        }
        GripSport.PICKLEBALL -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                targetAngleDeg = -45f,
                tip = "Use Bevel 2 Continental at the kitchen line so forehand & backhand are neutral"
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Eastern Forehand",
                targetBevel = 3,
                targetAngleDeg = 0f,
                tip = "Shake hands with the paddle on Bevel 3 for crisp baseline drives"
            )
            ShotPreset.BACKHAND -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                targetAngleDeg = -45f,
                tip = "Keep index finger off the paddle face and lock Bevel 2 for soft dinks"
            )
        }
    }
}

data class GripMetrics(
    val landmarks: List<NormalizedLandmark> = emptyList(),
    val worldLandmarks: List<Landmark> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val lockScore: Int = 0,
    val detectedBevel: String = "Bevel --",
    val fingerSpreadStatus: String = "Ready",
    val habitAlert: String? = null,
    val coachingTip: String = "Hold your racket handle inside the octagon guide to scan"
)
// ==========================================
// 2. 3D MEDIAPIPE GRIP ANALYZER ENGINE
// ==========================================

class GripAnalyzer(
    context: Context,
    private val onMetricsUpdated: (GripMetrics) -> Unit
) {
    var sport: GripSport = GripSport.TENNIS
    var shot: ShotPreset = ShotPreset.SERVE_VOLLEY
    var isLeftHanded: Boolean = false
    var isFrozen: Boolean = false

    private var handLandmarker: HandLandmarker? = null
    private var smoothedRollDeg: Float? = null

    @Volatile
    private var lastFrameWidth: Int = 0

    @Volatile
    private var lastFrameHeight: Int = 0

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.6f)
                .setMinTrackingConfidence(0.6f)
                .setResultListener { result, _ ->
                    if (!isFrozen) {
                        handleResult(result)
                    }
                }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun analyzeFrame(imageProxy: ImageProxy) {
        if (isFrozen || handLandmarker == null) {
            imageProxy.close()
            return
        }

        try {
            val frameTime = SystemClock.uptimeMillis()
            val bitmap = imageProxy.toBitmap()
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            lastFrameWidth = rotatedBitmap.width
            lastFrameHeight = rotatedBitmap.height

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            handLandmarker?.detectAsync(mpImage, frameTime)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    private fun handleResult(result: HandLandmarkerResult) {
        val screenLandmarks = result.landmarks().firstOrNull()
        val worldLandmarks = result.worldLandmarks().firstOrNull()

        if (screenLandmarks == null || worldLandmarks == null || worldLandmarks.size < 21) {
            smoothedRollDeg = null
            onMetricsUpdated(
                GripMetrics(
                    coachingTip = "Center your hand and racket handle inside the green octagon."
                )
            )
            return
        }

        val target = getGripTarget(sport, shot)

        val w = worldLandmarks[0]
val idxMcp = worldLandmarks[5]
val midMcp = worldLandmarks[9]
val pkyMcp = worldLandmarks[17]
val idxTip = worldLandmarks[8]
        

        val v1x = idxMcp.x() - w.x()
        val v1y = idxMcp.y() - w.y()
        val v1z = idxMcp.z() - w.z()

        val v2x = pkyMcp.x() - w.x()
        val v2y = pkyMcp.y() - w.y()
        val v2z = pkyMcp.z() - w.z()

        val nx = v1y * v2z - v1z * v2y
        val nz = v1x * v2y - v1y * v2x

        var rawRollDeg = (atan2(nx.toDouble(), nz.toDouble()) * 180.0 / PI).toFloat() + 180f
        if (rawRollDeg > 180f) rawRollDeg -= 360f
        if (isLeftHanded) rawRollDeg = -rawRollDeg

        val prev = smoothedRollDeg
        val smoothDeg = if (prev == null) rawRollDeg else (0.30f * rawRollDeg + 0.70f * prev)
        smoothedRollDeg = smoothDeg

        val normalized = (smoothDeg + 360f) % 360f
        val detectedBevelNum = (((normalized + 22.5f) / 45f).toInt() % 8) + 1

        var angleDiff = abs(smoothDeg - target.targetAngleDeg) % 360f
        if (angleDiff > 180f) angleDiff = 360f - angleDiff

        val lockScore = (100f - (angleDiff * 0.85f)).toInt().coerceIn(25, 99)

        val palmWidth = dist3D(idxMcp, pkyMcp).coerceAtLeast(0.01f)
        val indexExtension = dist3D(idxMcp, idxTip) / palmWidth
        val fingerSpread = dist3D(idxMcp, midMcp) / palmWidth

        var habitAlert: String? = null
        var fingerStatus = "Optimal"
        var tip = target.tip

        if (indexExtension > 1.75f) {
            habitAlert = "INDEX FINGER UP PADDLE ALERT!"
            fingerStatus = "Extended"
            tip = "Wrap your index finger around the handle instead of pointing it up the face"
        } else if (fingerSpread < 0.23f) {
            habitAlert = "TIGHT DEATH GRIP ALERT!"
            fingerStatus = "Tight"
            tip = "Slide your index finger slightly up (trigger spread) to prevent tennis elbow"
        } else if (lockScore < 78) {
            val dir = if (smoothDeg < target.targetAngleDeg) "clockwise" else "counter-clockwise"
            tip = "Rotate handle $dir (${angleDiff.toInt()}° off) to lock Bevel ${target.targetBevel} (${target.gripName})"
        } else {
            tip = "Locked on ${target.gripName} (Bevel ${target.targetBevel})! Clean 3D knuckle alignment"
        }

        onMetricsUpdated(
            GripMetrics(
                landmarks = screenLandmarks,
                worldLandmarks = worldLandmarks,
                imageWidth = lastFrameWidth,
                imageHeight = lastFrameHeight,
                lockScore = lockScore,
                detectedBevel = "Bevel $detectedBevelNum",
                fingerSpreadStatus = fingerStatus,
                habitAlert = habitAlert,
                coachingTip = tip
            )
        )
    }

    private fun dist3D(a: Landmark, b: Landmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        val dz = a.z() - b.z()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun close() {
        try {
            handLandmarker?.close()
            handLandmarker = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
// ==========================================
// 3. MAIN ACTIVITY ENTRY POINT
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                GripVisionApp()
            }
        }
    }
}

// ==========================================
// 4. MAIN CAMERA & CONTROLS UI
// ==========================================

@Composable
fun GripVisionApp() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    var selectedSport by remember { mutableStateOf(GripSport.TENNIS) }
    var selectedShot by remember { mutableStateOf(ShotPreset.SERVE_VOLLEY) }
    var isLeftHanded by remember { mutableStateOf(false) }
    var isFrozen by remember { mutableStateOf(false) }
    var metrics by remember { mutableStateOf(GripMetrics()) }
    var wasLocked by remember { mutableStateOf(false) }

    val analyzer = remember {
        GripAnalyzer(context) { newMetrics ->
            metrics = newMetrics
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.close()
        }
    }

    // Haptic pulse when user locks onto the target bevel
    val isCurrentlyLocked = metrics.lockScore >= 82 && metrics.habitAlert == null && metrics.landmarks.isNotEmpty()
    LaunchedEffect(isCurrentlyLocked) {
        if (isCurrentlyLocked && !wasLocked) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        wasLocked = isCurrentlyLocked
    }

    analyzer.sport = selectedSport
    analyzer.shot = selectedShot
    analyzer.isLeftHanded = isLeftHanded
    analyzer.isFrozen = isFrozen

    if (!hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is required to analyze grips.", color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(analyzer = analyzer)

        OctagonHandleGuide(
            gripName = getGripTarget(selectedSport, selectedShot).gripName,
            targetBevel = getGripTarget(selectedSport, selectedShot).targetBevel,
            isLeftHanded = isLeftHanded
        )

        Quest3DHandOverlay(metrics = metrics)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GripVision AI",
                        color = Color(0xFF00FF88),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    FilterChip(
                        selected = isLeftHanded,
                        onClick = { isLeftHanded = !isLeftHanded },
                        label = { Text(if (isLeftHanded) "Lefty" else "Righty", fontSize = 12.sp, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xAA121A1E),
                            labelColor = Color.White,
                            selectedContainerColor = Color(0xFF00FF88),
                            selectedLabelColor = Color.Black
                        )
                    )
                }

                // Equal-width Sport chips so labels never clip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GripSport.entries.forEach { sport ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = selectedSport == sport,
                            onClick = { selectedSport = sport },
                            label = {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(sport.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xAA121A1E),
                                labelColor = Color.White,
                                selectedContainerColor = Color(0xFF00FF88),
                                selectedLabelColor = Color.Black
                            )
                        )
                    }
                }

                // Equal-width Shot chips so "Backhand" never clips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ShotPreset.entries.forEach { shot ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = selectedShot == shot,
                            onClick = { selectedShot = shot },
                            label = {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(shot.label, fontSize = 10.5.sp, maxLines = 1)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xAA121A1E),
                                labelColor = Color.LightGray,
                                selectedContainerColor = Color(0xFF00E5FF),
                                selectedLabelColor = Color.Black
                            )
                        )
                    }
                }

                metrics.habitAlert?.let { alertMsg ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .background(Color(0xDDFF3B30), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = alertMsg,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isFrozen = !isFrozen
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFrozen) Color(0xFF00E5FF) else Color(0xFF00FF88)
                    ),
                    shape = CircleShape,
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    Text(
                        text = if (isFrozen) "RESUME LIVE SCAN" else "SNAP & CHECK GRIP",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC121A1E))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            GripMetricGauge(
                                label = "Grip Lock",
                                value = "${metrics.lockScore}%",
                                ringColor = Color(0xFF00FF88),
                                modifier = Modifier.weight(1f),
progress = metrics.lockScore / 100f
)
GripMetricGauge(
label = "Detected",
value = metrics.detectedBevel,
ringColor = Color(0xFF00E5FF),
modifier = Modifier.weight(1f),
progress = if (metrics.landmarks.isNotEmpty()) 1f else 0.15f
)
GripMetricGauge(
label = "Fingers",
value = metrics.fingerSpreadStatus,
ringColor = if (metrics.habitAlert != null) Color(0xFFFF3B30) else Color(0xFF00E5FF),
modifier = Modifier.weight(1f),
progress = if (metrics.fingerSpreadStatus == "Optimal") 1f else 0.45f
)
}
Spacer(modifier = Modifier.height(12.dp))
Text(
text = metrics.coachingTip,
color = Color.White,
fontSize = 14.sp,
lineHeight = 19.sp
)
}
}
}
}
}
}
---

### Part 4 of 5: Camera Preview & Octagon Handle Guide

```kotlin
// ==========================================
// 5. CAMERA PREVIEW & OCTAGON HANDLE GUIDE
// ==========================================

@Composable
fun CameraPreview(analyzer: GripAnalyzer) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val executor = Executors.newSingleThreadExecutor()
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    analyzer.analyzeFrame(imageProxy)
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

@Composable
fun OctagonHandleGuide(gripName: String, targetBevel: Int, isLeftHanded: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.46f
            val radius = 115f

            val path = Path()
            for (i in 0 until 8) {
                val angle = (i * 45.0 - 90.0) * PI / 180.0
                val x = cx + (radius * cos(angle)).toFloat()
                val y = cy + (radius * sin(angle)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            drawPath(
                path = path,
                color = Color(0xFF00FF88).copy(alpha = 0.6f),
                style = Stroke(
                    width = 4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f), 0f)
                )
            )

            val mappedBevel = if (isLeftHanded && targetBevel in 2..4) (10 - targetBevel) else targetBevel
            val targetAngle = ((mappedBevel - 1) * 45.0 - 90.0) * PI / 180.0
            val tx = cx + (radius * cos(targetAngle)).toFloat()
            val ty = cy + (radius * sin(targetAngle)).toFloat()

            drawCircle(
                color = Color(0xFF00FF88),
                radius = 18f,
                center = Offset(tx, ty)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-145).dp)
                .background(Color(0xBB10181C), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Target: $gripName (Bevel $targetBevel)",
                color = Color(0xFF00FF88),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}
// ==========================================
// 6. META QUEST 3 VOLUMETRIC HAND MESH & GAUGES
// ==========================================

private data class BoneSegment3D(
    val a: Offset,
    val b: Offset,
    val zDepth: Float,
    val radiusA: Float,
    val radiusB: Float
)

@Composable
fun Quest3DHandOverlay(metrics: GripMetrics) {
    val landmarks = metrics.landmarks
    val world = metrics.worldLandmarks
    if (landmarks.size < 21 || world.size < 21) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val imgW = if (metrics.imageWidth > 0) metrics.imageWidth.toFloat() else size.width
        val imgH = if (metrics.imageHeight > 0) metrics.imageHeight.toFloat() else size.height

        val scale = max(size.width / imgW, size.height / imgH)
        val scaledW = imgW * scale
        val scaledH = imgH * scale
        val offsetX = (size.width - scaledW) / 2f
        val offsetY = (size.height - scaledH) / 2f

        fun pt(idx: Int) = Offset(
            landmarks[idx].x() * scaledW + offsetX,
            landmarks[idx].y() * scaledH + offsetY
        )

        fun zAt(idx: Int): Float = world[idx].z()

        fun dist(a: Offset, b: Offset): Float =
            sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)).coerceAtLeast(1f)

        fun lerp(a: Offset, b: Offset, t: Float) = Offset(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t
        )

        val wrist = pt(0)
        val indexMcp = pt(5)
        val middleMcp = pt(9)
        val pinkyMcp = pt(17)
        val palmSpan = dist(indexMcp, pinkyMcp).coerceAtLeast(32f)

        val rimTint = when {
            metrics.habitAlert != null -> Color(0xFFFF453A)
            metrics.lockScore >= 78 -> Color(0xFF64FFDA)
            else -> Color(0xFFB8E8FF)
        }

        // 1. Holographic Forearm Cuff with Transparency Fade
        val midKnuckle = lerp(indexMcp, pinkyMcp, 0.5f)
        val armLen = dist(midKnuckle, wrist)
        val downArm = Offset((wrist.x - midKnuckle.x) / armLen, (wrist.y - midKnuckle.y) / armLen)
        val cuffEnd = Offset(wrist.x + downArm.x * palmSpan * 0.65f, wrist.y + downArm.y * palmSpan * 0.65f)
        val cuffPerp = Offset(-downArm.y, downArm.x)

        val cuffPath = Path().apply {
            moveTo(wrist.x + cuffPerp.x * palmSpan * 0.36f, wrist.y + cuffPerp.y * palmSpan * 0.36f)
            lineTo(cuffEnd.x + cuffPerp.x * palmSpan * 0.33f, cuffEnd.y + cuffPerp.y * palmSpan * 0.33f)
            lineTo(cuffEnd.x - cuffPerp.x * palmSpan * 0.33f, cuffEnd.y - cuffPerp.y * palmSpan * 0.33f)
            lineTo(wrist.x - cuffPerp.x * palmSpan * 0.36f, wrist.y - cuffPerp.y * palmSpan * 0.36f)
            close()
        }

        val cuffFadeBrush = Brush.linearGradient(
            0.0f to rimTint.copy(alpha = 0.22f),
            1.0f to Color.Transparent,
            start = wrist,
            end = cuffEnd
        )
        drawPath(path = cuffPath, brush = cuffFadeBrush)

        // 2. Shaded 3D Metacarpal Palm Facets
        val palmTris = listOf(
            Triple(0, 1, 2),
            Triple(0, 2, 5),
            Triple(0, 5, 9),
            Triple(0, 9, 13),
            Triple(0, 13, 17)
        )

        val palmCenter = lerp(wrist, middleMcp, 0.52f)
        val palmBrush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.24f),
                rimTint.copy(alpha = 0.14f),
                Color(0xFF0A192F).copy(alpha = 0.28f)
            ),
            center = palmCenter,
            radius = palmSpan * 1.15f
        )

        palmTris.forEach { (i1, i2, i3) ->
            val p1 = pt(i1); val p2 = pt(i2); val p3 = pt(i3)
            val triPath = Path().apply {
                moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close()
            }
            drawPath(path = triPath, brush = palmBrush)
            drawPath(
                path = triPath,
                color = rimTint.copy(alpha = 0.18f),
                style = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round)
            )
        }

        // 3. Build 3D Phalanx Bones & Sort Back-to-Front by Z-Depth
        val fingerSpecs = listOf(
            Pair(listOf(1, 2, 3, 4), listOf(0.18f, 0.15f, 0.13f, 0.11f)),
            Pair(listOf(5, 6, 7, 8), listOf(0.13f, 0.115f, 0.10f, 0.088f)),
            Pair(listOf(9, 10, 11, 12), listOf(0.135f, 0.12f, 0.105f, 0.092f)),
            Pair(listOf(13, 14, 15, 16), listOf(0.128f, 0.112f, 0.098f, 0.085f)),
            Pair(listOf(17, 18, 19, 20), listOf(0.115f, 0.10f, 0.088f, 0.075f))
        )

        val bones = mutableListOf<BoneSegment3D>()
        fingerSpecs.forEach { (indices, ratios) ->
            for (i in 0 until indices.lastIndex) {
                val idxA = indices[i]
                val idxB = indices[i + 1]
                val avgZ = (zAt(idxA) + zAt(idxB)) * 0.5f
                bones.add(
                    BoneSegment3D(
                        a = pt(idxA),
                        b = pt(idxB),
                        zDepth = avgZ,
                        radiusA = palmSpan * ratios[i],
                        radiusB = palmSpan * ratios[i + 1]
                    )
                )
            }
        }

        bones.sortByDescending { it.zDepth }

        // 4. Render Each Phalanx as a Lit 3D Cylinder + Joint Sphere
        bones.forEach { bone ->
            val dx = bone.b.x - bone.a.x
            val dy = bone.b.y - bone.a.y
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val nx = -dy / len
            val ny = dx / len

            val avgR = (bone.radiusA + bone.radiusB) * 0.5f
            val depthBoost = (-bone.zDepth * 4f).coerceIn(-0.08f, 0.14f)

            val capsulePath = Path().apply {
                moveTo(bone.a.x + nx * bone.radiusA, bone.a.y + ny * bone.radiusA)
                lineTo(bone.b.x + nx * bone.radiusB, bone.b.y + ny * bone.radiusB)
                lineTo(bone.b.x - nx * bone.radiusB, bone.b.y - ny * bone.radiusB)
                lineTo(bone.a.x - nx * bone.radiusA, bone.a.y - ny * bone.radiusA)
                close()
            }

            val cylinderBrush = Brush.linearGradient(
                0.0f to Color.White.copy(alpha = (0.58f + depthBoost).coerceIn(0.2f, 0.85f)),
                0.35f to rimTint.copy(alpha = (0.26f + depthBoost).coerceIn(0.15f, 0.5f)),
                0.78f to Color(0xFF0D1B2A).copy(alpha = 0.42f),
                1.0f to rimTint.copy(alpha = 0.48f),
                start = Offset(bone.a.x + nx * avgR, bone.a.y + ny * avgR),
                end = Offset(bone.a.x - nx * avgR, bone.a.y - ny * avgR)
            )

            drawLine(
                color = Color.Black.copy(alpha = 0.28f),
                start = Offset(bone.a.x + 2.dp.toPx(), bone.a.y + 3.dp.toPx()),
                end = Offset(bone.b.x + 2.dp.toPx(), bone.b.y + 3.dp.toPx()),
                strokeWidth = avgR * 2.05f,
                cap = StrokeCap.Round
            )

            drawPath(path = capsulePath, brush = cylinderBrush)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.45f), rimTint.copy(alpha = 0.18f)),
                    center = Offset(bone.b.x + nx * bone.radiusB * 0.3f, bone.b.y + ny * bone.radiusB * 0.3f),
                    radius = bone.radiusB * 1.1f
                ),
                radius = bone.radiusB,
                center = bone.b
            )

            drawLine(
                color = Color.White.copy(alpha = (0.72f + depthBoost).coerceIn(0.35f, 0.95f)),
                start = Offset(bone.a.x + nx * bone.radiusA, bone.a.y + ny * bone.radiusA),
                end = Offset(bone.b.x + nx * bone.radiusB, bone.b.y + ny * bone.radiusB),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = rimTint.copy(alpha = 0.55f),
                start = Offset(bone.a.x - nx * bone.radiusA, bone.a.y - ny * bone.radiusA),
                end = Offset(bone.b.x - nx * bone.radiusB, bone.b.y - ny * bone.radiusB),
                strokeWidth = 1.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 5. Glowing 3D Knuckle Target Node (Landmark 5)
        val knuckle = pt(5)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFFFEE00).copy(alpha = 0.65f), Color.Transparent),
                center = knuckle,
                radius = 20.dp.toPx()
            ),
            radius = 20.dp.toPx(),
            center = knuckle
        )
        drawCircle(
            color = Color(0xFFFFEE00),
            radius = 5.5.dp.toPx(),
            center = knuckle
        )
    }
}

@Composable
fun GripMetricGauge(
    label: String,
    value: String,
    ringColor: Color,
    modifier: Modifier = Modifier,
    progress: Float = 0.79f
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = ringColor.copy(alpha = 0.25f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = 7.dp.toPx())
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            Text(
                text = value,
                color = Color.White,
fontWeight = FontWeight.Bold,
fontSize = 13.sp,
maxLines = 1
)
}
Spacer(modifier = Modifier.height(6.dp))
Text(
text = label,
color = Color.LightGray,
fontSize = 11.sp,
maxLines = 1
)
}
}
