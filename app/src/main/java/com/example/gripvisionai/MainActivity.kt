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
// 3. MAIN ACTIVITY & CAMERA PERMISSION
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GripVisionApp()
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
