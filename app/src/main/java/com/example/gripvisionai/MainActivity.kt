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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
        HandSilhouetteOverlay(metrics = metrics)

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
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 2.dp),
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

// ==========================================
// 5. CAMERA PREVIEW, OCTAGON, SILHOUETTE & GAUGES UI
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
fun HandSilhouetteOverlay(metrics: GripMetrics) {
    val landmarks = metrics.landmarks
    if (landmarks.size < 21) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val imgW = if (metrics.imageWidth > 0) metrics.imageWidth.toFloat() else size.width
        val imgH = if (metrics.imageHeight > 0) metrics.imageHeight.toFloat() else size.height

        // Exact FILL_CENTER coordinate mapping matching PreviewView
        val scale = max(size.width / imgW, size.height / imgH)
        val scaledW = imgW * scale
        val scaledH = imgH * scale
        val offsetX = (size.width - scaledW) / 2f
        val offsetY = (size.height - scaledH) / 2f

        fun pt(idx: Int) = Offset(
            landmarks[idx].x() * scaledW + offsetX,
            landmarks[idx].y() * scaledH + offsetY
        )

        fun perp(a: Offset, b: Offset): Offset {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy).coerceAtLeast(0.001f)
            return Offset(-dy / len, dx / len)
        }

        fun hypot(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

        fun lerp(a: Offset, b: Offset, t: Float) = Offset(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t
        )

        val wrist = pt(0)
        val indexMcp = pt(5)
        val pinkyMcp = pt(17)
        val palmSpan = hypot(indexMcp.x - pinkyMcp.x, indexMcp.y - pinkyMcp.y).coerceAtLeast(30f)

        // Ensure outward normal always points away from palm center regardless of left/right hand
        val palmCenter = Offset(
            (wrist.x + indexMcp.x + pinkyMcp.x) / 3f,
            (wrist.y + indexMcp.y + pinkyMcp.y) / 3f
        )
        val rawWristDir = perp(wrist, pt(9))
        val toPinky = Offset(pinkyMcp.x - palmCenter.x, pinkyMcp.y - palmCenter.y)
        val sign = if (rawWristDir.x * toPinky.x + rawWristDir.y * toPinky.y >= 0f) 1f else -1f

        fun outNormal(a: Offset, b: Offset): Offset {
            val n = perp(a, b)
            return Offset(n.x * sign, n.y * sign)
        }

        // Anatomical finger half-widths scaled to actual hand size
        val wThumb = palmSpan * 0.145f
        val wIndex = palmSpan * 0.125f
        val wMiddle = palmSpan * 0.130f
        val wRing = palmSpan * 0.122f
        val wPinky = palmSpan * 0.105f

        // Build outer boundary points following the real skin edge of the hand
        val contour = mutableListOf<Offset>()

        // 1. Ulnar forearm/wrist entry -> outer heel of palm -> pinky base
        val wristDir = Offset(pt(9).x - wrist.x, pt(9).y - wrist.y)
        val wristLen = hypot(wristDir.x, wristDir.y).coerceAtLeast(1f)
        val downArm = Offset(-wristDir.x / wristLen, -wristDir.y / wristLen)
        val ulnarSide = outNormal(wrist, pinkyMcp)
        val radialSide = Offset(-ulnarSide.x, -ulnarSide.y)

        contour.add(Offset(wrist.x + ulnarSide.x * palmSpan * 0.34f + downArm.x * palmSpan * 0.55f,
                           wrist.y + ulnarSide.y * palmSpan * 0.34f + downArm.y * palmSpan * 0.55f))
        contour.add(Offset(wrist.x + ulnarSide.x * palmSpan * 0.38f,
                           wrist.y + ulnarSide.y * palmSpan * 0.38f))
        contour.add(Offset(pinkyMcp.x + ulnarSide.x * (wPinky * 1.15f),
                           pinkyMcp.y + ulnarSide.y * (wPinky * 1.15f)))

        // Helper to trace a finger from base -> outer edge -> rounded tip -> inner edge
        fun addFinger(mcpIdx: Int, pipIdx: Int, dipIdx: Int, tipIdx: Int, halfW: Float) {
            val mcp = pt(mcpIdx)
            val pip = pt(pipIdx)
            val dip = pt(dipIdx)
            val tip = pt(tipIdx)

            val n1 = outNormal(mcp, pip)
            val n2 = outNormal(pip, dip)
            val n3 = outNormal(dip, tip)

            val axis = Offset(tip.x - dip.x, tip.y - dip.y)
            val axisLen = hypot(axis.x, axis.y).coerceAtLeast(1f)
            val tipDir = Offset(axis.x / axisLen, axis.y / axisLen)

            // Outer side going up
            contour.add(Offset(pip.x + n1.x * halfW * 0.96f, pip.y + n1.y * halfW * 0.96f))
            contour.add(Offset(dip.x + n2.x * halfW * 0.88f, dip.y + n2.y * halfW * 0.88f))

            // Smooth rounded fingertip arc
            val tipCrown = Offset(tip.x + tipDir.x * halfW * 0.72f, tip.y + tipDir.y * halfW * 0.72f)
            contour.add(Offset(tip.x + n3.x * halfW * 0.72f + tipDir.x * halfW * 0.35f,
                               tip.y + n3.y * halfW * 0.72f + tipDir.y * halfW * 0.35f))
            contour.add(tipCrown)
            contour.add(Offset(tip.x - n3.x * halfW * 0.72f + tipDir.x * halfW * 0.35f,
                               tip.y - n3.y * halfW * 0.72f + tipDir.y * halfW * 0.35f))

            // Inner side going down
            contour.add(Offset(dip.x - n2.x * halfW * 0.88f, dip.y - n2.y * halfW * 0.88f))
            contour.add(Offset(pip.x - n1.x * halfW * 0.96f, pip.y - n1.y * halfW * 0.96f))
        }

        // Finger webbing valley sits ~28% up from MCP toward PIP (real anatomical web height)
        fun addWebbing(mcpA: Int, pipA: Int, mcpB: Int, pipB: Int) {
            val webA = lerp(pt(mcpA), pt(pipA), 0.26f)
            val webB = lerp(pt(mcpB), pt(pipB), 0.26f)
            contour.add(lerp(webA, webB, 0.5f))
        }

        // 2. Pinky -> Ring -> Middle -> Index with high anatomical webbing valleys
        addFinger(17, 18, 19, 20, wPinky)
        addWebbing(17, 18, 13, 14)

        addFinger(13, 14, 15, 16, wRing)
        addWebbing(13, 14, 9, 10)

        addFinger(9, 10, 11, 12, wMiddle)
        addWebbing(9, 10, 5, 6)

        addFinger(5, 6, 7, 8, wIndex)

        // 3. Radial side of Index -> Thumb saddle (purlicue) -> Thumb -> Radial wrist exit
        val idxBaseRadial = Offset(
            indexMcp.x + radialSide.x * (wIndex * 1.12f),
            indexMcp.y + radialSide.y * (wIndex * 1.12f)
        )
        contour.add(idxBaseRadial)

        val thumbCmc = pt(1)
        val thumbMcp = pt(2)
        val thumbIp = pt(3)
        val thumbTip = pt(4)

        // Smooth curved saddle between index base and thumb IP joint
        val saddleMid = lerp(idxBaseRadial, lerp(thumbMcp, thumbIp, 0.45f), 0.52f)
        contour.add(saddleMid)

        val tn1 = outNormal(thumbMcp, thumbIp)
        val tn2 = outNormal(thumbIp, thumbTip)
        val tAxis = Offset(thumbTip.x - thumbIp.x, thumbTip.y - thumbIp.y)
        val tLen = hypot(tAxis.x, tAxis.y).coerceAtLeast(1f)
        val tTipDir = Offset(tAxis.x / tLen, tAxis.y / tLen)

        // Inner thumb edge up to rounded thumb tip
        contour.add(Offset(thumbIp.x + tn1.x * wThumb * 0.88f, thumbIp.y + tn1.y * wThumb * 0.88f))
        contour.add(Offset(thumbTip.x + tn2.x * wThumb * 0.72f + tTipDir.x * wThumb * 0.35f,
                           thumbTip.y + tn2.y * wThumb * 0.72f + tTipDir.y * wThumb * 0.35f))
        contour.add(Offset(thumbTip.x + tTipDir.x * wThumb * 0.72f,
                           thumbTip.y + tTipDir.y * wThumb * 0.72f))
        contour.add(Offset(thumbTip.x - tn2.x * wThumb * 0.72f + tTipDir.x * wThumb * 0.35f,
                           thumbTip.y - tn2.y * wThumb * 0.72f + tTipDir.y * wThumb * 0.35f))

        // Outer thumb edge & thenar eminence down to radial wrist
        contour.add(Offset(thumbIp.x - tn1.x * wThumb * 0.95f, thumbIp.y - tn1.y * wThumb * 0.95f))
        contour.add(Offset(thumbMcp.x - tn1.x * wThumb * 1.05f, thumbMcp.y - tn1.y * wThumb * 1.05f))
        contour.add(Offset(thumbCmc.x + radialSide.x * palmSpan * 0.22f, thumbCmc.y + radialSide.y * palmSpan * 0.22f))
        contour.add(Offset(wrist.x + radialSide.x * palmSpan * 0.36f, wrist.y + radialSide.y * palmSpan * 0.36f))
        contour.add(Offset(wrist.x + radialSide.x * palmSpan * 0.34f + downArm.x * palmSpan * 0.55f,
                           wrist.y + radialSide.y * palmSpan * 0.34f + downArm.y * palmSpan * 0.55f))

        // Build smooth Catmull-Rom / quadratic spline through contour points
        val smoothPath = Path()
        if (contour.size >= 3) {
            smoothPath.moveTo(contour[0].x, contour[0].y)
            for (i in 0 until contour.size - 1) {
                val p0 = contour[i]
                val p1 = contour[i + 1]
                val mid = lerp(p0, p1, 0.5f)
                smoothPath.quadraticBezierTo(p0.x, p0.y, mid.x, mid.y)
            }
            val last = contour.last()
            smoothPath.lineTo(last.x, last.y)
        }

        val topY = contour.minOfOrNull { it.y } ?: 0f
        val bottomY = contour.maxOfOrNull { it.y } ?: size.height
        val fadeSpan = (bottomY - topY).coerceAtLeast(100f)

        val glowBrush = Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.28f),
            0.78f to Color.White.copy(alpha = 0.20f),
            1.0f to Color.Transparent,
            startY = topY,
            endY = topY + fadeSpan
        )

        val lineBrush = Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.96f),
            0.78f to Color.White.copy(alpha = 0.85f),
            1.0f to Color.Transparent,
            startY = topY,
            endY = topY + fadeSpan
        )

        .y * palmSpan * 0.38f
            ).x + tipDir.x * halfW * 0.72f, tip.y + tipDir.y * halfW * 0.72f)
            contour.add(
                Offset(
                    tip.x + n3.x * halfW * 0.72f + tipDir.x * halfW * 0.35f,
                    tip.y + n3.y * halfW * 0.72f + tipDir.y * halfW * 0.35f
                )
            )
            contour.add(tipCrown)
            contour.add(
                Offset(
                    tip.x - n3.x * halfW * 0.72f + tipDir.x * halfW * 0.35f,
                    tip.y - n3.y * halfW * 0.72f + tipDir.y * halfW * 0.35f
                )
            )

            // Inner side going down
            contour.add(Offset(dip.x - n2.x * halfW * 0.88f, dip.y - n2.y * halfW * 0.88f))
            contour.add(Offset(pip.x - n1.x * halfW * 0.96f, pip.y - n1.y * halfW * 0.96f))
        }

        fun addWebbing(mcpA: Int, pipA: Int, mcpB: Int, pipB: Int) {
            val webA = lerp(pt(mcpA), pt(pipA), 0.26f)
            val webB = lerp(pt(mcpB), pt(pipB), 0.26f)
            contour.add(lerp(webA, webB, 0.5f))
        }
        drawPath(
            path = smoothPath,
            brush = glowBrush,
            style = Stroke(
                width = 8.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

                // Crisp continuous white silhouette outline
        drawPath(
            path = smoothPath,
            brush = lineBrush,
            style = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Subtle index base knuckle indicator (Landmark 5) for bevel alignment
        val knuckle = pt(5)
        drawCircle(
            color = Color(0xFFFFEE00).copy(alpha = 0.32f),
            radius = 16.dp.toPx(),
            center = knuckle
        )
        drawCircle(
            color = Color(0xFFFFEE00),
            radius = 5.dp.toPx(),
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
