package com.example.gripvisionai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.compose.ui.graphics.PathJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GripCameraScreen()
            }
        }
    }
}

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
    BACKHAND_DINK("Backhand / Dink")
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
            ShotPreset.BACKHAND_DINK -> GripTarget(
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
            ShotPreset.BACKHAND_DINK -> GripTarget(
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
            ShotPreset.BACKHAND_DINK -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                targetAngleDeg = -45f,
                tip = "Keep index finger off the paddle face and lock Bevel 2 for soft dinks"
            )
        }
    }
}

data class SmoothedPoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f
)

data class GripMetrics(
    val landmarks: List<SmoothedPoint> = emptyList(),
    val lockScore: Int = 0,
    val detectedBevel: String = "Bevel --",
    val fingerSpreadStatus: String = "Ready",
    val habitAlert: String? = null,
    val coachingTip: String = "Hold your racket handle inside the octagon guide to scan"
)

// ==========================================
// 2. MEDIAPIPE GRIP ANALYZER ENGINE
// ==========================================

class GripAnalyzer(
    context: Context,
    private val smoothingAlpha: Float = 0.35f,
    private val maxStepJump: Float = 0.22f,
    private val onMetricsUpdated: (GripMetrics) -> Unit
) {
    var sport: GripSport = GripSport.TENNIS
    var shot: ShotPreset = ShotPreset.SERVE_VOLLEY
    var isLeftHanded: Boolean = false
    var isFrozen: Boolean = false

    private var handLandmarker: HandLandmarker? = null
    private var smoothedLandmarks: List<SmoothedPoint>? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun analyzeFrame(imageProxy: ImageProxy) {
        if (isFrozen) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null && handLandmarker != null) {
            try {
                val bitmap = imageProxy.toBitmap()
                val matrix = Matrix().apply {
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                }
                val rotatedBitmap = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true
                )
                val mpImage = BitmapImageBuilder(rotatedBitmap).build()

                val result = handLandmarker?.detect(mpImage)
                if (result != null && result.landmarks().isNotEmpty()) {
                    val rawLandmarks = result.landmarks()[0]
                    val steadyLandmarks = smoothLandmarks(rawLandmarks)
                    val computed = computeMetrics(steadyLandmarks, sport, shot)
                    onMetricsUpdated(computed)
                } else {
                    smoothedLandmarks = null
                    onMetricsUpdated(
                        GripMetrics(coachingTip = "No hand detected. Center your grip inside the guide.")
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        imageProxy.close()
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

            // Clamp sudden single-frame glitches while allowing intentional fast hand movement
            val clampedTargetX = if (dist > maxStepJump && dist > 0f) {
                p.x + (dx / dist) * maxStepJump
            } else {
                lm.x()
            }
            val clampedTargetY = if (dist > maxStepJump && dist > 0f) {
                p.y + (dy / dist) * maxStepJump
            } else {
                lm.y()
            }

            SmoothedPoint(
                x = alpha * clampedTargetX + (1f - alpha) * p.x,
                y = alpha * clampedTargetY + (1f - alpha) * p.y,
                z = alpha * lm.z() + (1f - alpha) * p.z
            )
        }
        smoothedLandmarks = updated
        return updated
    }

    private fun computeMetrics(
        landmarks: List<SmoothedPoint>,
        sport: GripSport,
        shot: ShotPreset
    ): GripMetrics {
        val target = getGripTarget(sport, shot)

        val wrist = landmarks[0]
        val indexKnuckle = landmarks[5]

        val dx = indexKnuckle.x - wrist.x
        val dy = indexKnuckle.y - wrist.y
        val angleDeg = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()

        val normalizedAngle = (angleDeg + 180f) % 360f
        val calculatedBevel = ((normalizedAngle / 45f).toInt() % 8) + 1

        val bevelMatch = calculatedBevel == target.targetBevel
        val lockScore = if (bevelMatch) 92 else 55

        val habitAlert = if (!bevelMatch && lockScore < 60) {
            "Adjust knuckle position closer to Bevel ${target.targetBevel}"
        } else {
            null
        }
        val fingerSpreadStatus = if (landmarks.size > 20) "Relaxed" else "Tight"

        return GripMetrics(
            landmarks = landmarks,
            lockScore = lockScore,
            detectedBevel = "Bevel $calculatedBevel",
            fingerSpreadStatus = fingerSpreadStatus,
            habitAlert = habitAlert,
            coachingTip = target.tip
        )
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}

// ==========================================
// 3. LIVE GRIP SCANNER + 8-BEVEL AR DIAL
// ==========================================

@Composable
fun GripCameraScreen() {
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
    var isLeftHanded by remember { mutableStateOf(false) }
    var isFrozen by remember { mutableStateOf(false) }
    var metrics by remember { mutableStateOf(GripMetrics()) }

    val analyzer = remember {
        GripAnalyzer(context) { newMetrics -> metrics = newMetrics }
    }

    LaunchedEffect(selectedSport, selectedShot, isLeftHanded, isFrozen) {
        analyzer.sport = selectedSport
        analyzer.shot = selectedShot
        analyzer.isLeftHanded = isLeftHanded
        analyzer.isFrozen = isFrozen
    }

    val currentTarget = getGripTarget(selectedSport, selectedShot)
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            analyzer.close()
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
                                        analyzer.analyzeFrame(proxy)
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
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Camera permission is required to scan grips.",
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(text = "Grant Camera Permission")
                }
            }
        }

        BevelOctagonOverlay(
            targetBevel = currentTarget.targetBevel,
            gripName = currentTarget.gripName,
            isLocked = metrics.lockScore >= 80,
            isLeftHanded = isLeftHanded
        )

        HandSkeletonOverlay(metrics = metrics)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp, start = 14.dp, end = 14.dp)
        ) {
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
                    label = {
                        Text(
                            text = if (isLeftHanded) "Lefty" else "Righty",
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xAA121A1E),
                        labelColor = Color.White,
                        selectedContainerColor = Color(0xFF00FF88),
                        selectedLabelColor = Color.Black
                    )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GripSport.entries.forEach { sport ->
                    FilterChip(
                        selected = selectedSport == sport,
                        onClick = { selectedSport = sport },
                        label = {
                            Text(
                                text = sport.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ShotPreset.entries.forEach { shot ->
                    FilterChip(
                        selected = selectedShot == shot,
                        onClick = { selectedShot = shot },
                        label = {
                            Text(
                                text = shot.label,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
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
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { isFrozen = !isFrozen },
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
                            modifier = Modifier.weight(1f)
                        )
                        GripMetricGauge(
                            label = "Detected",
                            value = metrics.detectedBevel,
                            ringColor = Color(0xFF00E5FF),
                            modifier = Modifier.weight(1f)
                        )
                        GripMetricGauge(
                            label = "Fingers",
                            value = metrics.fingerSpreadStatus,
                            ringColor = Color(0xFF00E5FF),
                            modifier = Modifier.weight(1f)
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

// ==========================================
// 4. 8-BEVEL OCTAGON & HAND CONTOUR UI
// ==========================================

@Composable
fun BevelOctagonOverlay(
    targetBevel: Int,
    gripName: String,
    isLocked: Boolean,
    isLeftHanded: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.48f
            val radius = size.width * 0.24f
            val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f)

            val path = Path()
            for (i in 0 until 8) {
                val angle = (i * 45.0 - 112.5) * PI / 180.0
                val x = cx + (radius * cos(angle)).toFloat()
                val y = cy + (radius * sin(angle)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            drawPath(
                path = path,
                color = if (isLocked) Color(0xAA00FF88) else Color(0x8800E5FF),
                style = Stroke(width = 8f, pathEffect = if (isLocked) null else dash)
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
                .offset(y = (-135).dp)
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

@Composable
fun HandSkeletonOverlay(
    metrics: GripMetrics,
    showLandmarkPoints: Boolean = false
) {
    val landmarks = metrics.landmarks
    if (landmarks.size < 21) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        fun pt(index: Int): Offset = Offset(landmarks[index].x * w, landmarks[index].y * h)

        val contourPoints = listOf(
            pt(0),
            pt(1),
            pt(2),
            pt(3),
            pt(4),
            pt(8),
            pt(12),
            pt(16),
            pt(20),
            pt(17),
            pt(0)
        )

        val outlinePath = Path().apply {
            if (contourPoints.size >= 3) {
                moveTo(contourPoints.first().x, contourPoints.first().y)

                for (i in 1 until contourPoints.lastIndex) {
                    val curr = contourPoints[i]
                    val next = contourPoints[i + 1]
                    val midX = (curr.x + next.x) * 0.5f
                    val midY = (curr.y + next.y) * 0.5f
                    quadraticTo(curr.x, curr.y, midX, midY)
                }

                val last = contourPoints.last()
                lineTo(last.x, last.y)
            }
        }

        val wrist = pt(0)
        val middleTip = pt(12)
        val primaryColor = when {
            metrics.habitAlert != null -> Color(0xFFFF3B30)
            metrics.lockScore >= 80 -> Color(0xFF00FF88)
            else -> Color(0xFFF2F4F7)
        }

        val strokeBrush = Brush.linearGradient(
            colors = listOf(
                primaryColor.copy(alpha = 0.12f),
                primaryColor.copy(alpha = 0.75f),
                primaryColor
            ),
            start = wrist,
            end = middleTip
        )

        val glowBrush = Brush.linearGradient(
            colors = listOf(
                primaryColor.copy(alpha = 0.0f),
                primaryColor.copy(alpha = 0.22f),
                primaryColor.copy(alpha = 0.30f)
            ),
            start = wrist,
            end = middleTip
        )

        drawPath(
            path = outlinePath,
            brush = glowBrush,
            style = Stroke(
                width = 12.dp.toPx(),
                cap = StrokeCap.Round,
                join = PathJoin.Round
            )
        )

        drawPath(
            path = outlinePath,
            brush = strokeBrush,
            style = Stroke(
                width = 5.dp.toPx(),
                cap = StrokeCap.Round,
                join = PathJoin.Round
            )
        )

        drawCircle(
            color = Color(0xFFFFEE00).copy(alpha = 0.85f),
            radius = 6.dp.toPx(),
            center = pt(5)
        )

        if (showLandmarkPoints) {
            landmarks.forEachIndexed { idx, lm ->
                if (idx != 5) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.55f),
                        radius = 3.dp.toPx(),
                        center = Offset(lm.x * w, lm.y * h)
                    )
                }
            }
        }
    }
}

@Composable
fun GripMetricGauge(
    label: String,
    value: String,
    ringColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(74.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = ringColor.copy(alpha = 0.25f),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    style = Stroke(width = 7.dp.toPx())
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f, sweepAngle = 285f, useCenter = false,
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
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = label,
            color = Color.LightGray,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}
