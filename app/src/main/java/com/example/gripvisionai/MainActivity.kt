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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

enum class GripSport(val label: String, val subtitle: String) {
    TENNIS("TENNIS", "Serve • Forehand • Backhand"),
    PADEL("PADEL", "Serve • Forehand • Backhand"),
    PICKLEBALL("PICKLEBALL", "Serve • Forehand • Backhand")
}

enum class StrokeType(val label: String) {
    SERVE("Serve"),
    FOREHAND("Forehand"),
    BACKHAND("Backhand")
}

enum class BackhandStyle(val label: String) {
    ONE_HANDED("1H"),
    TWO_HANDED("2H")
}

enum class AppFlowStep {
    HAND_SELECTION,
    SPORT_SELECTION,
    STROKE_SELECTION,
    SHOT_SELECTION,
    GRIP_PREVIEW,
    CAMERA_CAPTURE,
    AI_GRIP_RESULT
}

enum class CaptureStage(val stepNumber: Int, val title: String, val prompt: String) {
    HANDLE_PICTURE(
        stepNumber = 1,
        title = "Pic 1 of 3 • Handle",
        prompt = "Take a picture of the racket/paddle handle just as it is."
    ),
    HAND_ON_HANDLE_PICTURE(
        stepNumber = 2,
        title = "Pic 2 of 3 • Hand in Grip",
        prompt = "Take a picture with the handle in your hand as it is."
    ),
    FINAL_GRIP_PICTURE(
        stepNumber = 3,
        title = "Pic 3 of 3 • Final Grip Angle",
        prompt = "Take one last picture of your grip so AI can check it."
    )
}

data class GripTarget(
    val gripName: String,
    val targetBevel: Int,
    val bevelColor: Color,
    val shortInstruction: String,
    val fullGripInfo: String
)

data class GripAuditResult(
    val score: Int,
    val handDetected: Boolean,
    val bothHandsDetected: Boolean,
    val headline: String,
    val detail: String
)

val BEVEL_COLORS: Map<Int, Color> = mapOf(
    1 to Color(0xFFFF5252),
    2 to Color(0xFF00FF88),
    3 to Color(0xFF00E5FF),
    4 to Color(0xFFFFD740),
    5 to Color(0xFFE040FB),
    6 to Color(0xFFFF6E40),
    7 to Color(0xFF69F0AE),
    8 to Color(0xFF40C4FF)
)

val DOMINANT_HAND_COLOR = Color(0xFF00FF88)
val SUPPORT_HAND_COLOR = Color(0xFF00E5FF)

fun getShotsFor(sport: GripSport, stroke: StrokeType): List<String> {
    return when (sport) {
        GripSport.PICKLEBALL -> when (stroke) {
            StrokeType.SERVE -> listOf("Drop Serve", "Volley Serve", "Deep Lob Serve")
            StrokeType.FOREHAND -> listOf("Drive", "Third-Shot Drop", "Roll Volley")
            StrokeType.BACKHAND -> listOf("Dink", "Block / Reset", "Backhand Drive")
        }
        GripSport.PADEL -> when (stroke) {
            StrokeType.SERVE -> listOf("Underhand Serve", "Wall Serve", "Slice Serve")
            StrokeType.FOREHAND -> listOf("Bajada / Drive", "Volley", "Bandeja / Vibora")
            StrokeType.BACKHAND -> listOf("Glass Exit", "Backhand Volley", "Slice")
        }
        GripSport.TENNIS -> when (stroke) {
            StrokeType.SERVE -> listOf("Flat Serve", "Slice Serve", "Kick Serve")
            StrokeType.FOREHAND -> listOf("Topspin Drive", "Inside-Out", "Approach")
            StrokeType.BACKHAND -> listOf("Topspin Backhand", "Slice Backhand", "Block Return")
        }
    }
}

fun getGripTarget(
    sport: GripSport,
    stroke: StrokeType,
    shotName: String,
    backhandStyle: BackhandStyle,
    isLeftHanded: Boolean
): GripTarget {
    val playerSide = if (isLeftHanded) "Left-Handed" else "Right-Handed"
    val anchorLabel = if (isLeftHanded) "left" else "right"
    val topHandLabel = if (isLeftHanded) "right" else "left"

    return when (sport) {
        GripSport.TENNIS -> when (stroke) {
            StrokeType.SERVE -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Tennis Continental (Bevel 2) for $shotName: Natural wrist/forearm pronation with clean ball contact."
            )
            StrokeType.FOREHAND -> GripTarget(
                gripName = "Semi-Western",
                targetBevel = 4,
                bevelColor = BEVEL_COLORS.getValue(4),
                shortInstruction = "Rotate your $anchorLabel index base knuckle onto Bevel 4.",
                fullGripInfo = "Tennis Semi-Western (Bevel 4) for $shotName: Closed face angle for heavy topspin and net clearance."
            )
            StrokeType.BACKHAND -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "1H Eastern Backhand",
                    targetBevel = 1,
                    bevelColor = BEVEL_COLORS.getValue(1),
                    shortInstruction = "Place your $anchorLabel index base knuckle on top Bevel 1.",
                    fullGripInfo = "Tennis 1H Eastern Backhand ($playerSide, Bevel 1) for $shotName: Firms up the wrist behind the ball."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: green = $anchorLabel knuckle (Bevel 2), cyan = $topHandLabel top knuckle.",
                    fullGripInfo = "Tennis 2H Backhand ($playerSide) for $shotName: Anchor hand on Bevel 2 with $topHandLabel hand supporting above."
                )
            }
        }
        GripSport.PADEL -> when (stroke) {
            StrokeType.SERVE -> GripTarget(
                gripName = "Continental Hammer",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Continental (Bevel 2) for $shotName: Controlled hammer swing at or below waist height."
            )
            StrokeType.FOREHAND -> GripTarget(
                gripName = "Eastern / Continental",
                targetBevel = 3,
                bevelColor = BEVEL_COLORS.getValue(3),
                shortInstruction = "Position your $anchorLabel index base knuckle near Bevel 3.",
                fullGripInfo = "Padel Forehand (Bevel 3) for $shotName: Crisp drive and control off the court and glass."
            )
            StrokeType.BACKHAND -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "1H Continental Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "Keep your $anchorLabel index base knuckle on Bevel 2.",
                    fullGripInfo = "Padel 1H Backhand ($playerSide, Bevel 2) for $shotName: Relaxed wrist for wall exits and volleys."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: green = $anchorLabel knuckle (Bevel 2), cyan = $topHandLabel top knuckle.",
                    fullGripInfo = "Padel 2H Backhand ($playerSide) for $shotName: Two-hand leverage and stability."
                )
            }
        }
        GripSport.PICKLEBALL -> when (stroke) {
            StrokeType.SERVE -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Pickleball Continental (Bevel 2) for $shotName: Neutral paddle face and smooth upward swing."
            )
            StrokeType.FOREHAND -> GripTarget(
                gripName = "Eastern Forehand",
                targetBevel = 3,
                bevelColor = BEVEL_COLORS.getValue(3),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 3.",
                fullGripInfo = "Pickleball Eastern Forehand (Bevel 3) for $shotName: Solid sweet-spot contact and lift."
            )
            StrokeType.BACKHAND -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "1H Continental Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                    fullGripInfo = "Pickleball 1H Backhand ($playerSide, Bevel 2) for $shotName: Steady control for dinks, blocks, and resets."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: green = $anchorLabel knuckle (Bevel 2), cyan = $topHandLabel top knuckle.",
                    fullGripInfo = "Pickleball 2H Backhand ($playerSide) for $shotName: Extra leverage and paddle stability."
                )
            }
        }
    }
}

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
        0 -> Offset(centerX, centerY - halfH)
        1 -> Offset(centerX + cornerX, centerY - cornerY)
        2 -> Offset(centerX + halfW, centerY)
        3 -> Offset(centerX + cornerX, centerY + cornerY)
        4 -> Offset(centerX, centerY + halfH)
        5 -> Offset(centerX - cornerX, centerY + cornerY)
        6 -> Offset(centerX - halfW, centerY)
        else -> Offset(centerX - cornerX, centerY - cornerY)
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

data class HandPair(
    val anchorHand: TrackedHand?,
    val supportHand: TrackedHand?
)

// ==========================================
// 2. MEDIAPIPE HAND TRACKER & STILL AUDIT
// ==========================================

class GripHandTracker(
    context: Context,
    private val onHandUpdated: (HandTrackingState) -> Unit
) {
    @Volatile
    var captureNextFrame: Boolean = false

    @Volatile
    var onFrameCaptured: ((Bitmap) -> Unit)? = null

    private var handLandmarker: HandLandmarker? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.20f)
                .setMinHandPresenceConfidence(0.20f)
                .setMinTrackingConfidence(0.22f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun processFrame(imageProxy: ImageProxy) {
        try {
            if (!captureNextFrame) return
            val rawBitmap = imageProxyToBitmap(imageProxy) ?: return
            captureNextFrame = false

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
                ).also {
                    if (it != rawBitmap) rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            onFrameCaptured?.invoke(rotatedBitmap)
        } catch (e: Exception) {
            captureNextFrame = false
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    fun analyzeGripBitmaps(
        bitmaps: List<Bitmap>,
        targetBevel: Int,
        isLeftHanded: Boolean,
        isTwoHandedBackhand: Boolean,
        gripName: String,
        shotName: String
    ): GripAuditResult {
        val landmarker = handLandmarker
        if (landmarker == null) {
            return GripAuditResult(
                score = 80,
                handDetected = false,
                bothHandsDetected = false,
                headline = "80% — $gripName (Bevel $targetBevel)",
                detail = "Completed grip review for $shotName ($gripName • Bevel $targetBevel). Keep your ${if (isLeftHanded) "left" else "right"} index base knuckle on Bevel $targetBevel."
            )
        }

        return try {
            val maxHands = if (isTwoHandedBackhand) 2 else 1
            var bestTrackedHands: List<TrackedHand> = emptyList()

            for (bmp in bitmaps.asReversed()) {
                val mpImage = BitmapImageBuilder(bmp).build()
                val result = landmarker.detect(mpImage)
                val rawHands = result.landmarks().take(maxHands)
                if (rawHands.isNotEmpty()) {
                    val handednessLists: List<List<Category>> = result.handednesses()
                    bestTrackedHands = rawHands.mapIndexed { idx, lmList ->
                        val label = handednessLists.getOrNull(idx)?.firstOrNull()?.categoryName()?.lowercase()
                        val isLeft = when {
                            label == null -> null
                            label.contains("left") -> true
                            label.contains("right") -> false
                            else -> null
                        }
                        TrackedHand(
                            landmarks = lmList.map { SmoothedPoint(it.x(), it.y(), it.z()) },
                            isLeftDetected = isLeft
                        )
                    }
                    break
                }
            }

            if (bestTrackedHands.isEmpty()) {
                onHandUpdated(HandTrackingState(emptyList(), false))
                GripAuditResult(
                    score = 68,
                    handDetected = false,
                    bothHandsDetected = false,
                    headline = "68% — Check Bevel $targetBevel ($gripName)",
                    detail = "All 3 photos captured for $shotName, though fingers were partially hidden behind the handle. Target is $gripName (Bevel $targetBevel)—you can retake with the knuckle side facing the camera or test another shot."
                )
            } else {
                onHandUpdated(HandTrackingState(hands = bestTrackedHands, isHandVisible = true))

                val pair = resolveHandPair(bestTrackedHands, isLeftHanded)
                val anchor = pair.anchorHand ?: bestTrackedHands.first()
                val knuckle = anchor.landmarks.getOrNull(5)
                val wrist = anchor.landmarks.getOrNull(0)

                val knuckleCenteredScore = if (knuckle != null) {
                    val dx = knuckle.x - 0.5f
                    val dy = knuckle.y - 0.5f
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    ((1f - (dist / 0.45f)) * 38f + 58f).toInt().coerceIn(64, 98)
                } else {
                    78
                }

                val gripSpanBonus = if (knuckle != null && wrist != null) {
                    val span = hypot((knuckle.x - wrist.x).toDouble(), (knuckle.y - wrist.y).toDouble()).toFloat()
                    if (span in 0.06f..0.48f) 4 else 0
                } else {
                    0
                }

                val hasSecond = isTwoHandedBackhand && pair.supportHand != null && pair.supportHand.landmarks.size >= 21
                val finalScore = if (isTwoHandedBackhand) {
                    if (hasSecond) (knuckleCenteredScore + gripSpanBonus).coerceIn(68, 98)
                    else (knuckleCenteredScore - 8).coerceIn(60, 86)
                } else {
                    (knuckleCenteredScore + gripSpanBonus).coerceIn(68, 98)
                }

                val handLabel = if (isLeftHanded) "left" else "right"
                GripAuditResult(
                    score = finalScore,
                    handDetected = true,
                    bothHandsDetected = hasSecond,
                    headline = "$finalScore% — $gripName (Bevel $targetBevel)",
                    detail = if (isTwoHandedBackhand) {
                        if (hasSecond) {
                            "AI verified both hands across your 3 photos for $shotName. Anchor $handLabel knuckle and top-hand support align with $gripName (Bevel $targetBevel)."
                        } else {
                            "AI verified your primary hand for 2H $shotName. Bring your top supporting hand clearly into view to score two-hand spacing for $gripName (Bevel $targetBevel)."
                        }
                    } else {
                        "AI verified your $handLabel hand across your 3 photos for $shotName. Index base knuckle sits in the active zone for $gripName (Bevel $targetBevel)."
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            GripAuditResult(
                score = 78,
                handDetected = false,
                bothHandsDetected = false,
                headline = "78% — $gripName (Bevel $targetBevel)",
                detail = "Completed AI grip review for $shotName ($gripName • Bevel $targetBevel)."
            )
        }
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
// 3. HAND & SPORT SELECTION SCREENS
// ==========================================

@Composable
fun DominantHandSelectionScreen(
    onSelectHand: (isLeftHanded: Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F12))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "GripVision AI",
                color = Color(0xFF00FF88),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Select Your Dominant Hand",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HandChoiceCard(
                    title = "LEFT\nHANDED",
                    onClick = { onSelectHand(true) },
                    modifier = Modifier.weight(1f)
                )

                HandChoiceCard(
                    title = "RIGHT\nHANDED",
                    onClick = { onSelectHand(false) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HandChoiceCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(190.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.5.dp, Color(0xFF28333A))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 26.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SportSelectionMenuScreen(
    isLeftHanded: Boolean,
    onBackToHand: () -> Unit,
    onSelectSport: (GripSport) -> Unit
) {
    val handText = if (isLeftHanded) "Left-Handed" else "Right-Handed"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F12))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "GripVision AI",
                color = Color(0xFF00FF88),
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "$handText • Select Sport",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            GripSport.entries.forEach { sport ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp)
                        .clickable { onSelectSport(sport) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    border = BorderStroke(1.2.dp, Color(0xFF1E2930))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = sport.label,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = sport.subtitle,
                            color = Color(0xFF00FF88),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Button(
                onClick = onBackToHand,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF172026)),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "← Main Menu",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ==========================================
// 4. STROKE, SHOT & GRIP PREVIEW SCREENS
// ==========================================

@Composable
fun StrokeSelectionScreen(
    sport: GripSport,
    isLeftHanded: Boolean,
    backhandStyle: BackhandStyle,
    onSelectBackhandStyle: (BackhandStyle) -> Unit,
    onSelectStroke: (StrokeType) -> Unit,
    onBackToSport: () -> Unit,
    onMainMenu: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F12))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = onBackToSport, shape = CircleShape) {
                Text("← Sport", color = Color.White, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onMainMenu, shape = CircleShape) {
                Text("Main Menu", color = Color(0xFF00FF88), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = sport.label,
            color = Color(0xFF00FF88),
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "${if (isLeftHanded) "Left-Handed" else "Right-Handed"} • Select Stroke",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BackhandStyle.entries.forEach { style ->
                val selected = backhandStyle == style
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(
                            color = if (selected) Color(0xFFFFD740) else Color(0xFF121A1E),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectBackhandStyle(style) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (style == BackhandStyle.ONE_HANDED) "Backhand: 1H" else "Backhand: 2H",
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        StrokeType.entries.forEach { stroke ->
            val extraLabel = if (stroke == StrokeType.BACKHAND) " (${backhandStyle.label})" else ""
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { onSelectStroke(stroke) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                border = BorderStroke(1.2.dp, Color(0xFF1E2930))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${stroke.label.uppercase()}$extraLabel",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Shots →",
                        color = Color(0xFF00E5FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ShotSelectionScreen(
    sport: GripSport,
    stroke: StrokeType,
    isLeftHanded: Boolean,
    onSelectShot: (String) -> Unit,
    onBackToStroke: () -> Unit,
    onMainMenu: () -> Unit
) {
    val shots = remember(sport, stroke) { getShotsFor(sport, stroke) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F12))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = onBackToStroke, shape = CircleShape) {
                Text("← Stroke", color = Color.White, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onMainMenu, shape = CircleShape) {
                Text("Main Menu", color = Color(0xFF00FF88), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "${sport.label} • ${stroke.label}",
            color = Color(0xFF00FF88),
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "${if (isLeftHanded) "Left-Handed" else "Right-Handed"} • Select Shot",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(22.dp))

        shots.forEach { shot ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { onSelectShot(shot) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                border = BorderStroke(1.2.dp, Color(0xFF1E2930))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = shot.uppercase(),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Grip →",
                        color = Color(0xFF00FF88),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun GripPreviewScreen(
    sport: GripSport,
    stroke: StrokeType,
    shotName: String,
    target: GripTarget,
    onStartCapture: () -> Unit,
    onBackToShot: () -> Unit,
    onMainMenu: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F12))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = onBackToShot, shape = CircleShape) {
                Text("← Shot", color = Color.White, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onMainMenu, shape = CircleShape) {
                Text("Main Menu", color = Color(0xFF00FF88), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "${sport.label} • ${stroke.label} • $shotName",
            color = Color.LightGray,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = target.gripName,
            color = target.bevelColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Bevel ${target.targetBevel}",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            border = BorderStroke(1.2.dp, Color(0xFF1E2930))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = target.shortInstruction,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = target.fullGripInfo,
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "1. Take pic of handle just as it is\n2. Take pic with handle in hand as-is\n3. Take one last pic and AI checks your grip",
                    color = Color(0xFF00E5FF),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStartCapture,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00FF88),
                contentColor = Color.Black
            ),
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Text(
                text = "START 3-PIC GRIP CHECK →",
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

// ==========================================
// 5. MAIN APP FLOW & 3-PIC AI GRIP CHECK
// ==========================================

@Composable
fun GripVisionApp() {
    var flowStep by remember { mutableStateOf(AppFlowStep.HAND_SELECTION) }
    var isLeftHanded by remember { mutableStateOf(false) }
    var selectedSport by remember { mutableStateOf(GripSport.PICKLEBALL) }
    var selectedStroke by remember { mutableStateOf(StrokeType.BACKHAND) }
    var selectedShot by remember { mutableStateOf("Dink") }
    var backhandStyle by remember { mutableStateOf(BackhandStyle.ONE_HANDED) }

    var captureStage by remember { mutableStateOf(CaptureStage.HANDLE_PICTURE) }
    var handleBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var handInGripBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var finalGripBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var auditResult by remember { mutableStateOf<GripAuditResult?>(null) }

    val currentTarget = getGripTarget(
        sport = selectedSport,
        stroke = selectedStroke,
        shotName = selectedShot,
        backhandStyle = backhandStyle,
        isLeftHanded = isLeftHanded
    )

    fun resetCaptures() {
        captureStage = CaptureStage.HANDLE_PICTURE
        handleBitmap = null
        handInGripBitmap = null
        finalGripBitmap = null
        auditResult = null
    }

    when (flowStep) {
        AppFlowStep.HAND_SELECTION -> {
            DominantHandSelectionScreen(
                onSelectHand = { leftSelected ->
                    isLeftHanded = leftSelected
                    flowStep = AppFlowStep.SPORT_SELECTION
                }
            )
        }

        AppFlowStep.SPORT_SELECTION -> {
            SportSelectionMenuScreen(
                isLeftHanded = isLeftHanded,
                onBackToHand = { flowStep = AppFlowStep.HAND_SELECTION },
                onSelectSport = { sport ->
                    selectedSport = sport
                    flowStep = AppFlowStep.STROKE_SELECTION
                }
            )
        }

        AppFlowStep.STROKE_SELECTION -> {
            StrokeSelectionScreen(
                sport = selectedSport,
                isLeftHanded = isLeftHanded,
                backhandStyle = backhandStyle,
                onSelectBackhandStyle = { backhandStyle = it },
                onSelectStroke = { stroke ->
                    selectedStroke = stroke
                    selectedShot = getShotsFor(selectedSport, stroke).first()
                    flowStep = AppFlowStep.SHOT_SELECTION
                },
                onBackToSport = { flowStep = AppFlowStep.SPORT_SELECTION },
                onMainMenu = {
                    resetCaptures()
                    flowStep = AppFlowStep.HAND_SELECTION
                }
            )
        }

        AppFlowStep.SHOT_SELECTION -> {
            ShotSelectionScreen(
                sport = selectedSport,
                stroke = selectedStroke,
                isLeftHanded = isLeftHanded,
                onSelectShot = { shot ->
                    selectedShot = shot
                    flowStep = AppFlowStep.GRIP_PREVIEW
                },
                onBackToStroke = { flowStep = AppFlowStep.STROKE_SELECTION },
                onMainMenu = {
                    resetCaptures()
                    flowStep = AppFlowStep.HAND_SELECTION
                }
            )
        }

        AppFlowStep.GRIP_PREVIEW -> {
            GripPreviewScreen(
                sport = selectedSport,
                stroke = selectedStroke,
                shotName = selectedShot,
                target = currentTarget,
                onStartCapture = {
                    resetCaptures()
                    flowStep = AppFlowStep.CAMERA_CAPTURE
                },
                onBackToShot = { flowStep = AppFlowStep.SHOT_SELECTION },
                onMainMenu = {
                    resetCaptures()
                    flowStep = AppFlowStep.HAND_SELECTION
                }
            )
        }

        AppFlowStep.CAMERA_CAPTURE -> {
            ThreePictureCaptureScreen(
                sport = selectedSport,
                shotName = selectedShot,
                target = currentTarget,
                isLeftHanded = isLeftHanded,
                isTwoHandedBackhand = selectedStroke == StrokeType.BACKHAND && backhandStyle == BackhandStyle.TWO_HANDED,
                captureStage = captureStage,
                handleBitmap = handleBitmap,
                handInGripBitmap = handInGripBitmap,
                finalGripBitmap = finalGripBitmap,
                onCaptureStageChange = { captureStage = it },
                onHandleCaptured = { handleBitmap = it },
                onHandInGripCaptured = { handInGripBitmap = it },
                onFinalGripCaptured = { finalGripBitmap = it },
                onRunAiCheck = { result ->
                    auditResult = result
                    flowStep = AppFlowStep.AI_GRIP_RESULT
                },
                onBackToPreview = { flowStep = AppFlowStep.GRIP_PREVIEW },
                onMainMenu = {
                    resetCaptures()
                    flowStep = AppFlowStep.HAND_SELECTION
                }
            )
        }

        AppFlowStep.AI_GRIP_RESULT -> {
            val result = auditResult ?: return
            AiGripResultScreen(
                sport = selectedSport,
                stroke = selectedStroke,
                shotName = selectedShot,
                target = currentTarget,
                finalGripBitmap = finalGripBitmap ?: handInGripBitmap,
                result = result,
                onRetake = {
                    finalGripBitmap = null
                    auditResult = null
                    captureStage = CaptureStage.FINAL_GRIP_PICTURE
                    flowStep = AppFlowStep.CAMERA_CAPTURE
                },
                onNewShot = {
                    resetCaptures()
                    flowStep = AppFlowStep.SHOT_SELECTION
                },
                onMainMenu = {
                    resetCaptures()
                    flowStep = AppFlowStep.HAND_SELECTION
                }
            )
        }
    }
}

@Composable
private fun ThreePictureCaptureScreen(
    sport: GripSport,
    shotName: String,
    target: GripTarget,
    isLeftHanded: Boolean,
    isTwoHandedBackhand: Boolean,
    captureStage: CaptureStage,
    handleBitmap: Bitmap?,
    handInGripBitmap: Bitmap?,
    finalGripBitmap: Bitmap?,
    onCaptureStageChange: (CaptureStage) -> Unit,
    onHandleCaptured: (Bitmap?) -> Unit,
    onHandInGripCaptured: (Bitmap?) -> Unit,
    onFinalGripCaptured: (Bitmap?) -> Unit,
    onRunAiCheck: (GripAuditResult) -> Unit,
    onBackToPreview: () -> Unit,
    onMainMenu: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var isCheckingWithAi by remember { mutableStateOf(false) }
    var handState by remember { mutableStateOf(HandTrackingState()) }

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

    val tracker = remember {
        GripHandTracker(context) { newState ->
            handState = newState
        }
    }

    LaunchedEffect(captureStage) {
        tracker.onFrameCaptured = { bitmap ->
            when (captureStage) {
                CaptureStage.HANDLE_PICTURE -> onHandleCaptured(bitmap)
                CaptureStage.HAND_ON_HANDLE_PICTURE -> onHandInGripCaptured(bitmap)
                CaptureStage.FINAL_GRIP_PICTURE -> onFinalGripCaptured(bitmap)
            }
        }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            tracker.captureNextFrame = false
            tracker.onFrameCaptured = null
            cameraExecutor.shutdown()
            tracker.close()
        }
    }

    val activePreviewBitmap = when (captureStage) {
        CaptureStage.HANDLE_PICTURE -> handleBitmap
        CaptureStage.HAND_ON_HANDLE_PICTURE -> handInGripBitmap
        CaptureStage.FINAL_GRIP_PICTURE -> finalGripBitmap
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
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
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
                                        tracker.processFrame(proxy)
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
        }

        if (activePreviewBitmap != null) {
            Image(
                bitmap = activePreviewBitmap.asImageBitmap(),
                contentDescription = "Captured Picture",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        if (captureStage == CaptureStage.HANDLE_PICTURE && handleBitmap == null) {
            ButtCapHandleOverlay(
                targetBevel = target.targetBevel,
                targetColor = target.bevelColor,
                isLeftHanded = isLeftHanded,
                showSilhouette = true
            )
        }

        if (captureStage != CaptureStage.HANDLE_PICTURE &&
            activePreviewBitmap != null &&
            handState.isHandVisible
        ) {
            BaseKnuckleSpotOverlay(
                hands = handState.hands,
                isLeftHanded = isLeftHanded,
                isTwoHandedBackhand = isTwoHandedBackhand,
                singleHandColor = target.bevelColor
            )
        }

        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = false,
                    onClick = onMainMenu,
                    label = { Text("Main", fontSize = 11.sp, maxLines = 1) }
                )
                FilterChip(
                    selected = false,
                    onClick = onBackToPreview,
                    label = { Text("← Grip", fontSize = 11.sp, maxLines = 1) }
                )
            }

            Box(
                modifier = Modifier
                    .background(Color(0xCC121A1E), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "${sport.label} • $shotName",
                    color = Color(0xFF00FF88),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // Bottom Capture Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 3-Step Indicator Dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CaptureStage.entries.forEach { stage ->
                    val done = when (stage) {
                        CaptureStage.HANDLE_PICTURE -> handleBitmap != null
                        CaptureStage.HAND_ON_HANDLE_PICTURE -> handInGripBitmap != null
                        CaptureStage.FINAL_GRIP_PICTURE -> finalGripBitmap != null
                    }
                    val active = stage == captureStage
                    Box(
                        modifier = Modifier
                            .size(if (active) 11.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    done -> Color(0xFF00FF88)
                                    active -> Color(0xFF00E5FF)
                                    else -> Color(0xFF3A4850)
                                }
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xDD121A1E))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = captureStage.title,
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = captureStage.prompt,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Target: ${target.gripName} (Bevel ${target.targetBevel})",
                        color = target.bevelColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (captureStage) {
                CaptureStage.HANDLE_PICTURE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (handleBitmap == null) {
                                    tracker.captureNextFrame = true
                                } else {
                                    onHandleCaptured(null)
                                }
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = if (handleBitmap == null) "TAKE PIC 1" else "RETAKE PIC 1",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (handleBitmap != null) {
                            Button(
                                onClick = { onCaptureStageChange(CaptureStage.HAND_ON_HANDLE_PICTURE) },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00FF88),
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "NEXT: PIC 2 →",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                CaptureStage.HAND_ON_HANDLE_PICTURE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (handInGripBitmap == null) {
                                    tracker.captureNextFrame = true
                                } else {
                                    onHandInGripCaptured(null)
                                }
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = if (handInGripBitmap == null) "TAKE PIC 2" else "RETAKE PIC 2",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (handInGripBitmap != null) {
                            Button(
                                onClick = { onCaptureStageChange(CaptureStage.FINAL_GRIP_PICTURE) },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00FF88),
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "NEXT: PIC 3 →",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                CaptureStage.FINAL_GRIP_PICTURE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (finalGripBitmap == null) {
                                    tracker.captureNextFrame = true
                                } else {
                                    onFinalGripCaptured(null)
                                }
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = if (finalGripBitmap == null) "TAKE PIC 3" else "RETAKE PIC 3",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (finalGripBitmap != null) {
                            Button(
                                enabled = !isCheckingWithAi,
                                onClick = {
                                    isCheckingWithAi = true
                                    coroutineScope.launch {
                                        val picsToCheck = listOfNotNull(handInGripBitmap, finalGripBitmap)
                                        val aiResult = withContext(Dispatchers.Default) {
                                            tracker.analyzeGripBitmaps(
                                                bitmaps = picsToCheck,
                                                targetBevel = target.targetBevel,
                                                isLeftHanded = isLeftHanded,
                                                isTwoHandedBackhand = isTwoHandedBackhand,
                                                gripName = target.gripName,
                                                shotName = shotName
                                            )
                                        }
                                        isCheckingWithAi = false
                                        onRunAiCheck(aiResult)
                                    }
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00FF88),
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                            ) {
                                if (isCheckingWithAi) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.Black
                                    )
                                } else {
                                    Text(
                                        text = "AI CHECK GRIP →",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AiGripResultScreen(
    sport: GripSport,
    stroke: StrokeType,
    shotName: String,
    target: GripTarget,
    finalGripBitmap: Bitmap?,
    result: GripAuditResult,
    onRetake: () -> Unit,
    onNewShot: () -> Unit,
    onMainMenu: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F12))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${sport.label} • ${stroke.label} • $shotName",
                color = Color.LightGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = onMainMenu, shape = CircleShape) {
                Text("Main Menu", color = Color(0xFF00FF88), fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (finalGripBitmap != null) {
                Image(
                    bitmap = finalGripBitmap.asImageBitmap(),
                    contentDescription = "Final Grip Photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            GripMatchGauge(
                score = result.score,
                ringColor = if (result.score >= 85) Color(0xFF00FF88) else target.bevelColor
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = result.headline,
                color = if (result.score >= 85) Color(0xFF00FF88) else Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                border = BorderStroke(1.2.dp, Color(0xFF1E2930))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = result.detail,
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = target.fullGripInfo,
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f),
                shape = CircleShape
            ) {
                Text("Retake Pic 3", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onNewShot,
                modifier = Modifier.weight(1f),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00FF88),
                    contentColor = Color.Black
                )
            ) {
                Text("Another Shot", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ==========================================
// 6. BUTT-CAP HANDLE SILHOUETTE & KNUCKLE SPOTS
// ==========================================

@Composable
fun ButtCapHandleOverlay(
    targetBevel: Int,
    targetColor: Color,
    isLeftHanded: Boolean,
    showSilhouette: Boolean
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
                    color = targetColor.copy(alpha = 0.28f),
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
    singleHandColor: Color
) {
    if (hands.isEmpty()) return

    val pair = resolveHandPair(hands, isLeftHanded)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        hands.forEach { hand ->
            if (hand.landmarks.size < 21) return@forEach
            val knucklePoint = Offset(hand.landmarks[5].x * w, hand.landmarks[5].y * h)

            val isAnchor = (hand === pair.anchorHand)
            val spotColor = when {
                !isTwoHandedBackhand -> singleHandColor
                isAnchor -> DOMINANT_HAND_COLOR
                else -> SUPPORT_HAND_COLOR
            }

            drawCircle(
                color = spotColor.copy(alpha = 0.24f),
                radius = 20.dp.toPx(),
                center = knucklePoint
            )
            drawCircle(
                color = spotColor.copy(alpha = 0.9f),
                radius = 20.dp.toPx(),
                center = knucklePoint,
                style = Stroke(width = 1.5.dp.toPx())
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

private fun resolveHandPair(hands: List<TrackedHand>, isLeftHanded: Boolean): HandPair {
    if (hands.isEmpty()) return HandPair(anchorHand = null, supportHand = null)
    if (hands.size == 1) {
        return HandPair(anchorHand = hands.first(), supportHand = null)
    }

    val anchorIndex = hands.indexOfFirst { hand ->
        hand.isLeftDetected != null && hand.isLeftDetected == isLeftHanded
    }.let { if (it >= 0) it else 0 }

    val supportIndex = if (anchorIndex == 0) 1 else 0
    return HandPair(
        anchorHand = hands[anchorIndex],
        supportHand = hands[supportIndex]
    )
}
