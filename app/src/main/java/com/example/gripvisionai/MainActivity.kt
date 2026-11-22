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
    HAND_SELECTION,
    GRIP_VISION
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
                fullGripInfo = "Tennis Continental (Bevel 2): Standard grip for serves, volleys, overheads, and slices—allows natural forearm pronation and smooth net transitions."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Semi-Western",
                targetBevel = 4,
                bevelColor = BEVEL_COLORS.getValue(4),
                shortInstruction = "Rotate your $anchorLabel index base knuckle onto Bevel 4.",
                fullGripInfo = "Tennis Semi-Western Forehand (Bevel 4): Moderately closed face for heavy topspin and net clearance on modern baseline shots (Eastern on Bevel 3 is flatter)."
            )
            ShotPreset.BACKHAND_DINK -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "1H Eastern Backhand",
                    targetBevel = 1,
                    bevelColor = BEVEL_COLORS.getValue(1),
                    shortInstruction = "Place your $anchorLabel index base knuckle on top Bevel 1.",
                    fullGripInfo = "Tennis 1H Eastern Backhand ($playerSide, Bevel 1): Index base knuckle on top Bevel 1 firms up the wrist for single-handed drives and slices."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: green dot = $anchorLabel base knuckle (Bevel 2), cyan dot = $topHandLabel top knuckle.",
                    fullGripInfo = "Tennis 2H Backhand ($playerSide): Dominant/bottom hand sits on Continental (Bevel 2) while the $topHandLabel supporting hand grips higher like a forehand for leverage and topspin."
                )
            }
        }
        GripSport.PADEL -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental Hammer",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Continental (Bevel 2): Core hammer grip for underarm serves, fast net volleys, bandejas, and víboras without switching grips mid-exchange."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Eastern / Continental",
                targetBevel = 3,
                bevelColor = BEVEL_COLORS.getValue(3),
                shortInstruction = "Position your $anchorLabel index base knuckle near Bevel 3 (or Bevel 2 for quick net/glass shifts).",
                fullGripInfo = "Padel Forehand (Bevel 3 / Bevel 2): Eastern (Bevel 3) adds drive and topspin from the back of the court; Continental (Bevel 2) keeps transitions fast at the net and glass."
            )
            ShotPreset.BACKHAND_DINK -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "1H Continental Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "Keep your $anchorLabel index base knuckle on Bevel 2.",
                    fullGripInfo = "Padel 1H Backhand ($playerSide, Bevel 2): Continental grip supports clean backhand volleys, wall exits, and controlled cuts with relaxed wrist action."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: green dot = $anchorLabel base knuckle (Bevel 2), cyan dot = $topHandLabel top knuckle.",
                    fullGripInfo = "Padel 2H Backhand ($playerSide): Green spot tracks your $anchorLabel anchor knuckle on Bevel 2 while cyan tracks your $topHandLabel top hand for added control on returns and glass exits."
                )
            }
        }
        GripSport.PICKLEBALL -> when (shot) {
            ShotPreset.SERVE_VOLLEY -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Pickleball Continental (Bevel 2): Neutral home-base grip for dinks, blocks, and rapid kitchen-line volleys with a relaxed (3–5/10) grip pressure."
            )
            ShotPreset.FOREHAND -> GripTarget(
                gripName = "Eastern Forehand",
                targetBevel = 3,
                bevelColor = BEVEL_COLORS.getValue(3),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 3.",
                fullGripInfo = "Pickleball Eastern Forehand (Bevel 3): Shake-hands grip providing solid drive pace, serve consistency, and controllable topspin while staying easy to reset."
            )
            ShotPreset.BACKHAND_DINK -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "1H Continental Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                    fullGripInfo = "Pickleball 1H Backhand ($playerSide, Bevel 2): Continental positioning keeps the paddle face stable for soft dinks, resets, and controlled backhand blocks."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: green dot = $anchorLabel base knuckle (Bevel 2), cyan dot = $topHandLabel top knuckle.",
                    fullGripInfo = "Pickleball 2H Backhand ($playerSide): Green spot tracks your $anchorLabel anchor knuckle and cyan tracks your $topHandLabel top knuckle for added stability on drives, third-shot drops, and returns."
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

data class HandPair(
    val anchorHand: TrackedHand?,
    val supportHand: TrackedHand?
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
// 3. FIRST SCREEN: DOMINANT HAND SELECTION
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

// ==========================================
// 4. GRIPVISION MAIN SCREEN
// ==========================================

@Composable
fun GripVisionApp() {
    var currentScreen by remember { mutableStateOf(AppScreen.HAND_SELECTION) }
    var isLeftHanded by remember { mutableStateOf(false) }

    when (currentScreen) {
        AppScreen.HAND_SELECTION -> {
            DominantHandSelectionScreen(
                onSelectHand = { leftSelected ->
                    isLeftHanded = leftSelected
                    currentScreen = AppScreen.GRIP_VISION
                }
            )
        }

        AppScreen.GRIP_VISION -> {
            GripVisionMainContent(
                isLeftHanded = isLeftHanded,
                onToggleHandedness = { isLeftHanded = !isLeftHanded }
            )
        }
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

private fun computeAnchorScore(
    hand: TrackedHand,
    targetCenterPx: Offset,
    frameWidth: Float,
    frameHeight: Float,
    currentlyLocked: Boolean
): Int {
    if (hand.landmarks.size < 21) return 0
    val knuckleLm = hand.landmarks[5] // Index finger base knuckle (MCP)
    val knucklePx = Offset(knuckleLm.x * frameWidth, knuckleLm.y * frameHeight)
    val distPx = hypot(
        (knucklePx.x - targetCenterPx.x).toDouble(),
        (knucklePx.y - targetCenterPx.y).toDouble()
    ).toFloat()

    val snapRadiusPx = frameWidth * 0.11f
    val releaseRadiusPx = frameWidth * 0.135f
    val maxRadiusPx = frameWidth * 0.34f
    val spanPx = (maxRadiusPx - snapRadiusPx).coerceAtLeast(1f)

    return when {
        currentlyLocked && distPx <= releaseRadiusPx -> 100
        distPx <= snapRadiusPx -> 100
        distPx >= maxRadiusPx -> 15
        else -> {
            val ratio = 1f - ((distPx - snapRadiusPx) / spanPx)
            (20f + ratio * 75f).toInt().coerceIn(15, 99)
        }
    }
}

private fun computeSupportKnuckleScore(
    supportHand: TrackedHand?,
    anchorHand: TrackedHand,
    targetCenterPx: Offset,
    frameWidth: Float,
    frameHeight: Float
): Int {
    if (supportHand == null || supportHand.landmarks.size < 21 || anchorHand.landmarks.size < 21) {
        return 0
    }

    val anchorKnuckle = Offset(
        anchorHand.landmarks[5].x * frameWidth,
        anchorHand.landmarks[5].y * frameHeight
    )
    val supportKnuckle = Offset(
        supportHand.landmarks[5].x * frameWidth,
        supportHand.landmarks[5].y * frameHeight
    )

    val distToAnchor = hypot(
        (supportKnuckle.x - anchorKnuckle.x).toDouble(),
        (supportKnuckle.y - anchorKnuckle.y).toDouble()
    ).toFloat()
    val distToTarget = hypot(
        (supportKnuckle.x - targetCenterPx.x).toDouble(),
        (supportKnuckle.y - targetCenterPx.y).toDouble()
    ).toFloat()

    val idealMaxSpanPx = frameWidth * 0.28f
    val minSpanPx = frameWidth * 0.025f

    val spanScore = when {
        distToAnchor in minSpanPx..idealMaxSpanPx -> 100f
        distToAnchor < minSpanPx -> ((distToAnchor / minSpanPx) * 75f).coerceIn(20f, 90f)
        else -> ((1f - ((distToAnchor - idealMaxSpanPx) / (frameWidth * 0.25f))) * 100f).coerceIn(10f, 95f)
    }

    val targetProximityScore = when {
        distToTarget <= frameWidth * 0.22f -> 100f
        distToTarget >= frameWidth * 0.45f -> 20f
        else -> {
            val r = 1f - ((distToTarget - frameWidth * 0.22f) / (frameWidth * 0.23f))
            (25f + r * 70f).coerceIn(20f, 95f)
        }
    }

    return ((spanScore * 0.6f) + (targetProximityScore * 0.4f)).toInt().coerceIn(0, 100)
}

@Composable
private fun GripVisionMainContent(
    isLeftHanded: Boolean,
    onToggleHandedness: () -> Unit
) {
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
    var backhandStyle by remember { mutableStateOf(BackhandStyle.ONE_HANDED) }

    var currentStep by remember { mutableStateOf(ScanStep.STEP_1_HANDLE_CAPTURE) }
    var snappedHandleBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var handState by remember { mutableStateOf(HandTrackingState()) }
    var lockScore by remember { mutableIntStateOf(0) }
    var bothKnucklesAligned by remember { mutableStateOf(false) }

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

    LaunchedEffect(isTwoHandedBackhand) {
        tracker.resetSmoothing()
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
                                            runHandDetection = (currentStep == ScanStep.STEP_2_HAND_PLACEMENT),
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

        if (currentStep == ScanStep.STEP_1_HANDLE_CAPTURE && snappedHandleBitmap != null) {
            Image(
                bitmap = snappedHandleBitmap!!.asImageBitmap(),
                contentDescription = "Captured Racket Handle",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        val isLocked = (currentStep == ScanStep.STEP_2_HAND_PLACEMENT && lockScore == 100)

        ButtCapHandleOverlay(
            targetBevel = currentTarget.targetBevel,
            targetColor = currentTarget.bevelColor,
            isLeftHanded = isLeftHanded,
            isLocked = isLocked,
            showSilhouette = (currentStep == ScanStep.STEP_1_HANDLE_CAPTURE && snappedHandleBitmap == null),
            onTargetPointComputed = { targetCenterPx: Offset, screenSize: Size ->
                if (currentStep == ScanStep.STEP_2_HAND_PLACEMENT && handState.hands.isNotEmpty()) {
                    val pair = resolveHandPair(handState.hands, isLeftHanded)
                    val primaryHand = pair.anchorHand
                    if (primaryHand != null && primaryHand.landmarks.size >= 21) {
                        val anchorScore = computeAnchorScore(
                            hand = primaryHand,
                            targetCenterPx = targetCenterPx,
                            frameWidth = screenSize.width,
                            frameHeight = screenSize.height,
                            currentlyLocked = (lockScore == 100)
                        )

                        if (isTwoHandedBackhand) {
                            val supportScore = computeSupportKnuckleScore(
                                supportHand = pair.supportHand,
                                anchorHand = primaryHand,
                                targetCenterPx = targetCenterPx,
                                frameWidth = screenSize.width,
                                frameHeight = screenSize.height
                            )
                            val hasSecondHand = pair.supportHand != null && pair.supportHand.landmarks.size >= 21
                            bothKnucklesAligned = hasSecondHand && anchorScore >= 95 && supportScore >= 75

                            val combined = if (!hasSecondHand) {
                                (anchorScore * 0.65f).toInt().coerceIn(10, 65)
                            } else {
                                ((anchorScore * 0.68f) + (supportScore * 0.32f))
                                    .toInt()
                                    .coerceIn(15, if (bothKnucklesAligned) 100 else 99)
                            }
                            lockScore = if (bothKnucklesAligned) 100 else combined
                        } else {
                            bothKnucklesAligned = anchorScore == 100
                            lockScore = anchorScore
                        }
                    } else {
                        bothKnucklesAligned = false
                        lockScore = 0
                    }
                } else {
                    bothKnucklesAligned = false
                    lockScore = 0
                }
            }
        )

        if (currentStep == ScanStep.STEP_2_HAND_PLACEMENT && handState.isHandVisible) {
            BaseKnuckleSpotOverlay(
                hands = handState.hands,
                isLeftHanded = isLeftHanded,
                isTwoHandedBackhand = isTwoHandedBackhand,
                singleHandColor = currentTarget.bevelColor,
                lockScore = lockScore,
                bothKnucklesAligned = bothKnucklesAligned
            )
        }

        // ==========================================
        // TOP HUD: CLEAN NON-CLIPPING CONTROLS
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
                Text(
                    text = "GripVision AI",
                    color = Color(0xFF00FF88),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )

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
                                .clickable { backhandStyle = style },
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
                modifier = Modifier
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
                    val pair = resolveHandPair(handState.hands, isLeftHanded)
                    val trackedCount = listOfNotNull(pair.anchorHand, pair.supportHand).size
                    Box(
                        modifier = Modifier
                            .background(Color(0xBB10181C), RoundedCornerShape(14.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$anchorHandName: Green • $topHandName: Cyan ($trackedCount/2)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // ==========================================
        // BOTTOM CARD: CAPTURE & 100% GRIP INFO
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
                                "Line up your racket/paddle handle with top bevel 1 and tap TAKE PIC."
                            } else if (isTwoHandedBackhand) {
                                "Pic taken! Bring both hands in: green dot = ${if (isLeftHanded) "left" else "right"} base knuckle, cyan dot = ${if (isLeftHanded) "right" else "left"} top knuckle."
                            } else {
                                "Pic taken! Go to hand placement and put your ${if (isLeftHanded) "left" else "right"} base knuckle on Bevel ${currentTarget.targetBevel}."
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            } else {
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
                                val pair = resolveHandPair(handState.hands, isLeftHanded)
                                Text(
                                    text = when {
                                        lockScore == 100 -> "100% — ${currentTarget.gripName}"
                                        !handState.isHandVisible && isTwoHandedBackhand -> "Bring both hands onto the grip"
                                        !handState.isHandVisible -> "Show your ${if (isLeftHanded) "left" else "right"} hand on the grip"
                                        isTwoHandedBackhand && pair.supportHand == null -> "Track both base knuckles (Green anchor + Cyan top hand)"
                                        isTwoHandedBackhand -> "Align Green knuckle on Bevel ${currentTarget.targetBevel} & Cyan knuckle above"
                                        else -> "Place your base knuckle dot on Bevel ${currentTarget.targetBevel}"
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
// 5. BUTT-CAP HANDLE SILHOUETTE (TOP = 1)
// ==========================================

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

// ==========================================
// 6. BASE KNUCKLE SPOT(S) FOR 1H & 2H
// ==========================================

@Composable
fun BaseKnuckleSpotOverlay(
    hands: List<TrackedHand>,
    isLeftHanded: Boolean,
    isTwoHandedBackhand: Boolean,
    singleHandColor: Color,
    lockScore: Int,
    bothKnucklesAligned: Boolean
) {
    if (hands.isEmpty()) return

    val isLocked = lockScore == 100
    val proximityRatio = (lockScore / 100f).coerceIn(0f, 1f)
    val pair = resolveHandPair(hands, isLeftHanded)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        hands.forEach { hand ->
            if (hand.landmarks.size < 21) return@forEach
            val knucklePoint = Offset(hand.landmarks[5].x * w, hand.landmarks[5].y * h)

            val isAnchor = (hand === pair.anchorHand)
            val spotColor = when {
                !isTwoHandedBackhand -> if (isLocked) Color(0xFF00FF88) else singleHandColor
                bothKnucklesAligned -> Color(0xFF00FF88)
                isAnchor -> DOMINANT_HAND_COLOR
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
