package com.example.gripvisionai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
import kotlin.math.sin

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

data class GripMetrics(
    val landmarks: List<NormalizedLandmark> = emptyList(),
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
    private val onMetricsUpdated: (GripMetrics) -> Unit
) {
    var sport: GripSport = GripSport.TENNIS
    var shot: ShotPreset = ShotPreset.SERVE_VOLLEY
    var isLeftHanded: Boolean = false
    var isFrozen: Boolean = false

    private var handLandmarker: HandLandmarker? = null

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

        if (handLandmarker != null) {
            try {
                val bitmap = imageProxy.toBitmap()
                val matrix = Matrix().apply {
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                val mpImage = BitmapImageBuilder(rotatedBitmap).build()

                val result = handLandmarker?.detect(mpImage)
                if (result != null && result.landmarks().isNotEmpty()) {
                    val landmarks = result.landmarks()[0]
                    val metrics = computeMetrics(landmarks, sport, shot, isLeftHanded)
                    onMetricsUpdated(metrics)
                } else {
                    onMetricsUpdated(GripMetrics(coachingTip = "No hand detected. Center your grip inside the guide."))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        imageProxy.close()
    }

    private fun computeMetrics(
        landmarks: List<NormalizedLandmark>,
        sport: GripSport,
        shot: ShotPreset,
        isLeftHanded: Boolean
    ): GripMetrics {
        val target = getGripTarget(sport, shot)

        val wrist = landmarks[0]
        val indexKnuckle = landmarks[5]

        val dx = indexKnuckle.x() - wrist.x()
        val dy = indexKnuckle.y() - wrist.y()
        val angleRad = atan2(dy.toDouble(), dx.toDouble())
        var angleDeg = (angleRad * 180.0 / PI).toFloat()
        if (isLeftHanded) angleDeg = -angleDeg

        val normalizedAngle = (angleDeg + 360f) % 360f
        val calculatedBevel = ((normalizedAngle / 45f).toInt() % 8) + 1

        val bevelMatch = calculatedBevel == target.targetBevel
        val lockScore = if (bevelMatch) 92 else 55

        val habitAlert = if (!bevelMatch && lockScore < 60) "Adjust knuckle position closer to Bevel ${target.targetBevel}" else null
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
}

// ==========================================
// 3. MAIN ACTIVITY & UI COMPOSABLES
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

@Composable
fun GripVisionApp() {
    val context = LocalContext.current
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

    val analyzer = remember {
        GripAnalyzer(context) { newMetrics ->
            metrics = newMetrics
        }
    }

    analyzer.sport = selectedSport
    analyzer.shot = selectedShot
    analyzer.isLeftHanded = isLeftHanded
    analyzer.isFrozen = isFrozen

    if (!hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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

        // Overlays
        OctagonHandleGuide(
            gripName = getGripTarget(selectedSport, selectedShot).gripName,
            targetBevel = getGripTarget(selectedSport, selectedShot).targetBevel,
            isLeftHanded = isLeftHanded
        )
        HandSkeletonOverlay(metrics = metrics)

        // Top & Bottom Controls
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header / Selectors
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GripSport.values().forEach { sport ->
                        Button(
                            onClick = { selectedSport = sport },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedSport == sport) Color(0xFF00FF88) else Color.DarkGray
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(sport.label, color = if (selectedSport == sport) Color.Black else Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Bottom Info Panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC10181C), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    GripMetricGauge(label = "Lock Score", value = "${metrics.lockScore}%", ringColor = Color(0xFF00FF88))
                    GripMetricGauge(label = "Bevel", value = metrics.detectedBevel, ringColor = Color(0xFF00B4D8))
                    GripMetricGauge(label = "Fingers", value = metrics.fingerSpreadStatus, ringColor = Color(0xFFFFB703))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = metrics.coachingTip,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun CameraPreview(analyzer: GripAnalyzer) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
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

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
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
            val cy = size.height / 2f
            val radius = 120f

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
                style = Stroke(width = 4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f), 0f))
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
fun HandSkeletonOverlay(metrics: GripMetrics) {
    val landmarks = metrics.landmarks
    if (landmarks.isEmpty()) return

    val connections = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 17 to 18, 18 to 19, 19 to 20,
        0 to 17
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val lineColor = if (metrics.habitAlert == null) Color(0xFF00FF88) else Color(0xFFFF3B30)

        connections.forEach { (a, b) ->
            if (a < landmarks.size && b < landmarks.size) {
                val start = Offset(landmarks[a].x() * size.width, landmarks[a].y() * size.height)
                val end = Offset(landmarks[b].x() * size.width, landmarks[b].y() * size.height)
                drawLine(color = lineColor, start = start, end = end, strokeWidth = 8f, cap = StrokeCap.Round)
            }
        }

        landmarks.forEachIndexed { idx, pt ->
            val isIndexKnuckle = idx == 5
            drawCircle(
                color = if (isIndexKnuckle) Color(0xFFFFEE00) else Color.White,
                radius = if (isIndexKnuckle) 16f else 9f,
                center = Offset(pt.x() * size.width, pt.y() * size.height)
            )
        }
    }
}

@Composable
fun GripMetricGauge(label: String, value: String, ringColor: Color, modifier: Modifier = Modifier) {
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
