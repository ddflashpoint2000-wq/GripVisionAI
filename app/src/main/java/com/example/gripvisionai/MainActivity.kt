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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.hypot
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
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
            onMetricsUpdated(GripMetrics(coachingTip = "Center your hand and racket handle inside the green octagon."))
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
        HandSkeletonOverlay(metrics = metrics)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
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

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GripSport.entries.forEach { sport ->
                        FilterChip(
                            selected = selectedSport == sport,
                            onClick = { selectedSport = sport },
                            label = { Text(sport.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
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
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ShotPreset.entries.forEach { shot ->
                        FilterChip(
                            selected = selectedShot == shot,
                            onClick = { selectedShot = shot },
                            label = { Text(shot.label, fontSize = 11.sp, maxLines = 1) },
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
                modifier = Modifier.fillMaxWidth(),
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
                                modifier = Modifier.weight(1f),
                                progress = metrics.lockScore / 100f
                            )
                            GripMetricGauge(
                                label = "Detected",
                                value = metrics.detectedBevel,
                                ringColor = Color(0xFF00E5FF),
                                modifier = Modifier.weight(1f),
                                progress = 0.85f
                            )
                            GripMetricGauge(
                                label = "Fingers",
                                value = metrics.fingerSpreadStatus,
                                ringColor = Color(0xFF00E5FF),
                                modifier = Modifier.weight(1f),
                                progress = 0.85f
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

// ==========================================
// 5. CAMERA PREVIEW, OCTAGON & GAUGES UI
// ==========================================

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
    if (landmarks.size < 21) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        fun pt(idx: Int) = Offset(
            landmarks[idx].x() * size.width,
            landmarks[idx].y() * size.height
        )

        val wrist = pt(0)
        val idxMcp = pt(5)
        val pkyMcp = pt(17)
        val palmSpan = hypot(idxMcp.x - pkyMcp.x, idxMcp.y - pkyMcp.y).coerceAtLeast(36f)

        val outlineColor = when {
            metrics.habitAlert != null -> Color(0xFFFF6B6B)
            metrics.lockScore >= 80 -> Color(0xFFE8FFF4)
            else -> Color.White
        }

        val wristFadeBrush = Brush.verticalGradient(
            colors = listOf(
                outlineColor.copy(alpha = 0.95f),
                outlineColor.copy(alpha = 0.95f),
                outlineColor.copy(alpha = 0.0f)
            ),
            startY = minOf(idxMcp.y, pkyMcp.y) - palmSpan * 0.2f,
            endY = wrist.y + palmSpan * 0.55f
        )

        // Build clean outer silhouette contour of the hand (Meta Quest passthrough outline style)
        val contourPoints = mutableListOf<Offset>()

        fun addFingerSilhouette(chain: List<Int>, baseHalfWidth: Float, tipHalfWidth: Float) {
            val pts = chain.map { pt(it) }
            val leftSide = mutableListOf<Offset>()
            val rightSide = mutableListOf<Offset>()

            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val len = hypot(dx, dy).coerceAtLeast(1f)
                val nx = -dy / len
                val ny = dx / len

                val t0 = i.toFloat() / (pts.size - 1)
                val t1 = (i + 1).toFloat() / (pts.size - 1)
                val w0 = baseHalfWidth + (tipHalfWidth - baseHalfWidth) * t0
                val w1 = baseHalfWidth + (tipHalfWidth - baseHalfWidth) * t1

                leftSide.add(Offset(a.x + nx * w0, a.y + ny * w0))
                leftSide.add(Offset(b.x + nx * w1, b.y + ny * w1))
                rightSide.add(Offset(a.x - nx * w0, a.y - ny * w0))
                rightSide.add(Offset(b.x - nx * w1, b.y - ny * w1))
            }

            // Rounded fingertip arc
            val preTip = pts[pts.size - 2]
            val tip = pts.last()
            val dirAngle = atan2(tip.y - preTip.y, tip.x - preTip.x)
            val tipCap = mutableListOf<Offset>()
            for (step in -2..2) {
                val ang = dirAngle + step * (PI / 5.0).toFloat()
                tipCap.add(Offset(tip.x + cos(ang) * tipHalfWidth, tip.y + sin(ang) * tipHalfWidth))
            }

            contourPoints.addAll(leftSide)
            contourPoints.addAll(tipCap)
            contourPoints.addAll(rightSide.asReversed())
        }

        // Determine winding order so outer edges stay on the exterior for both left and right hands
        val cross = (idxMcp.x - wrist.x) * (pkyMcp.y - wrist.y) - (idxMcp.y - wrist.y) * (pkyMcp.x - wrist.x)
        val isClockwise = cross > 0f

        val wristOffset = Offset(
            (wrist.x - (idxMcp.x + pkyMcp.x) * 0.5f) * 0.55f,
            (wrist.y - (idxMcp.y + pkyMcp.y) * 0.5f) * 0.55f
        )
        val thumbWristEdge = Offset(
            wrist.x + (idxMcp.x - pkyMcp.x) * 0.28f + wristOffset.x,
            wrist.y + (idxMcp.y - pkyMcp.y) * 0.28f + wristOffset.y
        )
        val pinkyWristEdge = Offset(
            wrist.x - (idxMcp.x - pkyMcp.x) * 0.28f + wristOffset.x,
            wrist.y - (idxMcp.y - pkyMcp.y) * 0.28f + wristOffset.y
        )

        val fingerSpecs = listOf(
            Triple(listOf(1, 2, 3, 4), palmSpan * 0.16f, palmSpan * 0.11f),
            Triple(listOf(5, 6, 7, 8), palmSpan * 0.135f, palmSpan * 0.09f),
            Triple(listOf(9, 10, 11, 12), palmSpan * 0.14f, palmSpan * 0.095f),
            Triple(listOf(13, 14, 15, 16), palmSpan * 0.13f, palmSpan * 0.088f),
            Triple(listOf(17, 18, 19, 20), palmSpan * 0.115f, palmSpan * 0.078f)
        )

        // Construct continuous outer hand contour from wrist -> thumb -> fingers -> pinky -> wrist
        val rawPathPoints = mutableListOf<Offset>()
        rawPathPoints.add(thumbWristEdge)
        rawPathPoints.add(pt(1))

        for ((chain, baseW, tipW) in fingerSpecs) {
            val pts = chain.map { pt(it) }
            val sideA = mutableListOf<Offset>()
            val sideB = mutableListOf<Offset>()

            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val len = hypot(dx, dy).coerceAtLeast(1f)
                val nx = if (isClockwise) -dy / len else dy / len
                val ny = if (isClockwise) dx / len else -dx / len

                val t0 = i.toFloat() / (pts.size - 1)
                val t1 = (i + 1).toFloat() / (pts.size - 1)
                val w0 = baseW + (tipW - baseW) * t0
                val w1 = baseW + (tipW - baseW) * t1

                sideA.add(Offset(a.x + nx * w0, a.y + ny * w0))
                sideA.add(Offset(b.x + nx * w1, b.y + ny * w1))
                sideB.add(Offset(a.x - nx * w0, a.y - ny * w0))
                sideB.add(Offset(b.x - nx * w1, b.y - ny * w1))
            }

            val preTip = pts[pts.size - 2]
            val tip = pts.last()
            val dirAngle = atan2(tip.y - preTip.y, tip.x - preTip.x)
            val sign = if (isClockwise) -1f else 1f
            val tipArc = (-2..2).map { step ->
                val ang = dirAngle + sign * step * (PI / 5.0).toFloat()
                Offset(tip.x + cos(ang) * tipW, tip.y + sin(ang) * tipW)
            }

            rawPathPoints.addAll(sideA)
            rawPathPoints.addAll(tipArc)
            rawPathPoints.addAll(sideB.asReversed())
        }

        rawPathPoints.add(pinkyWristEdge)

        // Smooth the outer contour with quadratic Bezier midpoints for crisp, organic line art
        val smoothPath = buildSmoothOpenPath(rawPathPoints)

        // Subtle soft dark-glass interior tint so white outline pops cleanly over any background
        val closedFillPath = Path().apply {
            addPath(smoothPath)
            close()
        }
        drawPath(
            path = closedFillPath,
            color = Color(0xFF10181C).copy(alpha = 0.16f)
        )

        // Outer soft glow halo
        drawPath(
            path = smoothPath,
            brush = wristFadeBrush,
            style = Stroke(
                width = 9.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            ),
            alpha = 0.22f
        )

        // Crisp continuous white hand silhouette outline (matching Meta Quest / VR hand outline)
        drawPath(
            path = smoothPath,
            brush = wristFadeBrush,
            style = Stroke(
                width = 3.2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Subtle Index Knuckle (Landmark 5) anchor ring so user still sees bevel alignment point
        val knucklePt = idxMcp
        drawCircle(
            color = Color(0xFFFFEE00).copy(alpha = 0.28f),
            radius = 16.dp.toPx(),
            center = knucklePt
        )
        drawCircle(
            color = Color(0xFFFFEE00),
            radius = 5.dp.toPx(),
            center = knucklePt
        )
    }
}

private fun buildSmoothOpenPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size == 1) return path

    for (i in 1 until points.size - 1) {
        val current = points[i]
        val next = points[i + 1]
        val midX = (current.x + next.x) / 2f
        val midY = (current.y + next.y) / 2f
        path.quadraticBezierTo(current.x, current.y, midX, midY)
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
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
