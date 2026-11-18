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

        var rawRollDeg = (atan2(nx.toDouble(), nz.toDouble()) * 180.0 / PI).toFloat()
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
                            GripMetricGauge("Grip Lock", "${metrics.lockScore}%", Color(0xFF00FF88), Modifier.weight(1f))
                            GripMetricGauge("Detected", metrics.detectedBevel, Color(0xFF00E5FF), Modifier.weight(1f))
                            GripMetricGauge("Fingers", metrics.fingerSpreadStatus, Color(0xFF00E5FF), Modifier.weight(1f))
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
                            GripMetricGauge("Grip Lock", "${metrics.lockScore}%", Color(0xFF00FF88), Modifier.weight(1f))
                            GripMetricGauge("Detected", metrics.detectedBevel, Color(0xFF00E5FF), Modifier.weight(1f))
                            GripMetricGauge("Fingers", metrics.fingerSpreadStatus, Color(0xFF00E5FF), Modifier.weight(1f))
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
