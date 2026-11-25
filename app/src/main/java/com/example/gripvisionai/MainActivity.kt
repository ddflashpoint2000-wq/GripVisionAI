package com.example.gripvisionai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint as AndroidPaint
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

const val OVERLAY_CENTER_X = 0.50f
const val OVERLAY_CENTER_Y = 0.37f
const val OVERLAY_HALF_W = 0.23f
const val OVERLAY_HALF_H = 0.125f

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

enum class AiAnalysisMode(val label: String, val badge: String) {
    ONLINE_GEMINI("Online AI (Gemini Vision)", "ONLINE AI"),
    OFFLINE_MEDIAPIPE("Offline AI (On-Device)", "OFFLINE AI")
}

enum class GripSport(val label: String, val subtitle: String) {
    TENNIS("TENNIS", "Serve • Volley • Groundstrokes"),
    PADEL("PADEL", "Serve • Smash • Volley • Groundstrokes"),
    PICKLEBALL("PICKLEBALL", "Serve • Forehand • Backhand")
}

enum class StrokeType(val label: String) {
    SERVE("Serve"),
    VOLLEY("Volley"),
    SMASH("Smash / Overhead"),
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
        prompt = "Align butt-cap bevel 1 at the top & take a picture of the handle."
    ),
    HAND_ON_HANDLE_PICTURE(
        stepNumber = 2,
        title = "Pic 2 of 3 • Hand in Grip",
        prompt = "Hold the handle as-is (live knuckle spot active) & take Pic 2."
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
    val supportTargetBevel: Int? = null,
    val bevelColor: Color,
    val shortInstruction: String,
    val fullGripInfo: String
)

data class GripAuditResult(
    val score: Int,
    val detectedBevel: Int?,
    val detectedSupportBevel: Int?,
    val detectedGripLabel: String,
    val handDetected: Boolean,
    val bothHandsDetected: Boolean,
    val sourceEngine: String,
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

fun getStrokesFor(sport: GripSport): List<StrokeType> {
    return when (sport) {
        GripSport.TENNIS -> listOf(
            StrokeType.SERVE,
            StrokeType.VOLLEY,
            StrokeType.FOREHAND,
            StrokeType.BACKHAND
        )
        GripSport.PADEL -> listOf(
            StrokeType.SERVE,
            StrokeType.SMASH,
            StrokeType.VOLLEY,
            StrokeType.FOREHAND,
            StrokeType.BACKHAND
        )
        GripSport.PICKLEBALL -> listOf(
            StrokeType.SERVE,
            StrokeType.FOREHAND,
            StrokeType.BACKHAND
        )
    }
}

fun getShotsFor(sport: GripSport, stroke: StrokeType): List<String> {
    return when (sport) {
        GripSport.PICKLEBALL -> when (stroke) {
            StrokeType.SERVE -> listOf("Drop Serve", "Volley Serve", "Deep Lob Serve")
            StrokeType.VOLLEY -> listOf("Punch Volley", "Block / Reset", "Roll Volley")
            StrokeType.SMASH -> listOf("Overhead Put-Away")
            StrokeType.FOREHAND -> listOf("Drive", "Third-Shot Drop", "Roll Volley")
            StrokeType.BACKHAND -> listOf("Dink", "Block / Reset", "Backhand Drive")
        }
        GripSport.PADEL -> when (stroke) {
            StrokeType.SERVE -> listOf("Underhand Serve", "Wall Serve", "Slice Serve")
            StrokeType.SMASH -> listOf(
                "Flat Smash (Por 4)",
                "Kick Smash (Por 3)",
                "Bandeja",
                "Víbora",
                "Rulo / Gancho"
            )
            StrokeType.VOLLEY -> listOf(
                "Forehand Volley",
                "Backhand Volley",
                "Block / Low Volley"
            )
            StrokeType.FOREHAND -> listOf("Bajada / Drive", "Chiquita", "Lob (Globo)")
            StrokeType.BACKHAND -> listOf("Glass Exit", "Backhand Chiquita", "Backhand Lob")
        }
        GripSport.TENNIS -> when (stroke) {
            StrokeType.SERVE -> listOf("Flat Serve", "Slice Serve", "Kick Serve")
            StrokeType.VOLLEY -> listOf(
                "Forehand Volley",
                "Backhand Volley",
                "Half / Drop Volley"
            )
            StrokeType.SMASH -> listOf("Overhead Smash")
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
                fullGripInfo = "Tennis Continental (Bevel 2) for $shotName: Natural wrist/forearm pronation and clean spin."
            )
            StrokeType.VOLLEY -> GripTarget(
                gripName = "Continental Volley",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Keep your $anchorLabel index base knuckle on Bevel 2 for volleys.",
                fullGripInfo = "Tennis Continental Volley (Bevel 2) for $shotName: Firm wrist angle with no grip change between forehand and backhand volleys."
            )
            StrokeType.SMASH -> GripTarget(
                gripName = "Continental",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Tennis Overhead Smash (Bevel 2): Same pronation mechanics as the serve."
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
                    fullGripInfo = "Tennis 1H Eastern Backhand ($playerSide, Bevel 1) for$shotName: Firms up the wrist behind the ball."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand (Bevel 2 + 7)",
                    targetBevel = 2,
                    supportTargetBevel = 7,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: Green ($anchorLabel) on Bevel 2, Cyan ($topHandLabel) on Bevel 7.",
                    fullGripInfo = "Tennis 2H Backhand ($playerSide) for$shotName: Bottom $anchorLabel hand on Continental (Bevel 2) + top$topHandLabel hand on Bevel 7/6."
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
            StrokeType.SMASH -> when {
                shotName.contains("Kick", ignoreCase = true) ||
                    shotName.contains("Por 3", ignoreCase = true) ||
                    shotName.contains("Rulo", ignoreCase = true) -> GripTarget(
                    gripName = "Eastern Backhand / Continental",
                    targetBevel = 1,
                    bevelColor = BEVEL_COLORS.getValue(1),
                    shortInstruction = "Shift $anchorLabel index base knuckle toward Bevel 1 for topspin kick.",
                    fullGripInfo = "Padel $shotName (Bevel 1–2): Slight shift toward Eastern Backhand kicks the ball over the side wall or fence."
                )
                else -> GripTarget(
                    gripName = "Continental Overhead",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                    fullGripInfo = "Padel $shotName (Bevel 2): Continental hammer grip gives clean slice on Bandeja/Víbora and power on Flat Smash."
                )
            }
            StrokeType.VOLLEY -> GripTarget(
                gripName = "Continental Volley",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Keep your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Volley (Bevel 2) for $shotName: Hammer grip keeps the face compact and punchy."
            )
            StrokeType.FOREHAND -> GripTarget(
                gripName = "Continental / Eastern",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Padel Forehand (Bevel 2) for $shotName: Neutral hammer grip for quick wall/net transitions."
            )
            StrokeType.BACKHAND -> when (backhandStyle) {
                BackhandStyle.ONE_HANDED -> GripTarget(
                    gripName = "1H Continental Backhand",
                    targetBevel = 2,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "Keep your $anchorLabel index base knuckle on Bevel 2.",
                    fullGripInfo = "Padel 1H Backhand ($playerSide, Bevel 2) for$shotName: Relaxed wrist for wall exits and volleys."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand (Bevel 2 + 7)",
                    targetBevel = 2,
                    supportTargetBevel = 7,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: Green ($anchorLabel) on Bevel 2, Cyan ($topHandLabel) on Bevel 7.",
                    fullGripInfo = "Padel 2H Backhand ($playerSide) for$shotName: Bottom $anchorLabel knuckle on Bevel 2 and top$topHandLabel knuckle on Bevel 7."
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
            StrokeType.VOLLEY -> GripTarget(
                gripName = "Continental Volley",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Pickleball Volley (Bevel 2) for $shotName: Fast kitchen-line resets and blocks."
            )
            StrokeType.SMASH -> GripTarget(
                gripName = "Continental Overhead",
                targetBevel = 2,
                bevelColor = BEVEL_COLORS.getValue(2),
                shortInstruction = "Place your $anchorLabel index base knuckle on Bevel 2.",
                fullGripInfo = "Pickleball Overhead Smash (Bevel 2): Crisp downward angle."
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
                    fullGripInfo = "Pickleball 1H Backhand ($playerSide, Bevel 2) for$shotName: Steady control for dinks, blocks, and resets."
                )
                BackhandStyle.TWO_HANDED -> GripTarget(
                    gripName = "2H Backhand (Bevel 2 + 7)",
                    targetBevel = 2,
                    supportTargetBevel = 7,
                    bevelColor = BEVEL_COLORS.getValue(2),
                    shortInstruction = "$playerSide: Green ($anchorLabel) on Bevel 2, Cyan ($topHandLabel) on Bevel 7.",
                    fullGripInfo = "Pickleball 2H Backhand ($playerSide) for$shotName: Bottom $anchorLabel knuckle on Bevel 2 and top$topHandLabel knuckle on Bevel 7."
                )
            }
        }
    }
}

fun bevelToGripName(bevel: Int): String = when (bevel) {
    1 -> "Eastern Backhand (Bevel 1)"
    2 -> "Continental (Bevel 2)"
    3 -> "Eastern Forehand (Bevel 3)"
    4 -> "Semi-Western Forehand (Bevel 4)"
    5 -> "Western Forehand (Bevel 5)"
    6 -> "2H Top-Hand Semi-Western (Bevel 6)"
    7 -> "2H Top-Hand Eastern (Bevel 7)"
    8 -> "Extreme / Opposite Continental (Bevel 8)"
    else -> "Bevel $bevel"
}

fun estimateBevelFromNormalizedPoint(
    point: SmoothedPoint,
    isLeftHanded: Boolean,
    centerX: Float = OVERLAY_CENTER_X,
    centerY: Float = OVERLAY_CENTER_Y
): Int {
    val dx = point.x - centerX
    val dy = point.y - centerY
    val rad = atan2(dx.toDouble(), (-dy).toDouble())
    var cwDeg = Math.toDegrees(rad)
    if (cwDeg < 0.0) cwDeg += 360.0

    val slot = ((cwDeg / 45.0).roundToInt()) % 8
    return if (!isLeftHanded) {
        slot + 1
    } else {
        ((8 - slot) % 8) + 1
    }
}

fun circularBevelDistance(bevelA: Int, bevelB: Int): Int {
    val diff = abs(bevelA - bevelB) % 8
    return minOf(diff, 8 - diff)
}

fun scoreBevelMatch(detectedBevel: Int, targetBevel: Int): Int {
    return when (circularBevelDistance(detectedBevel, targetBevel)) {
        0 -> 96
        1 -> 72
        2 -> 46
        3 -> 26
        else -> 15
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
// 2. ONLINE GEMINI MULTIMODAL GRIP ANALYZER
// ==========================================

object OnlineGeminiGripAnalyzer {
    suspend fun analyze(
        handleBitmap: Bitmap?,
        handInGripBitmap: Bitmap?,
        finalGripBitmap: Bitmap?,
        sport: GripSport,
        stroke: StrokeType,
        shotName: String,
        target: GripTarget,
        isLeftHanded: Boolean,
        isTwoHandedBackhand: Boolean
    ): Result<GripAuditResult> = withContext(Dispatchers.IO) {
        runCatching {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI())
                .generativeModel("gemini-3.5-flash")

            val dominantSide = if (isLeftHanded) "Left-Handed" else "Right-Handed"
            val topHandSide = if (isLeftHanded) "Right" else "Left"
            val dominantHandWord = if (isLeftHanded) "Left" else "Right"

            val promptText = """
                You are an expert racket/paddle grip coach analyzing up to 3 photos of a player's grip.
                Be completely honest and strict—do NOT inflate scores if the grip is wrong or unclear.

                Context:
                - Player Dominant Hand: $dominantSide (Bevel 1 is top 12 o'clock; bevels 1..8 go clockwise for Right-Handed and counter-clockwise for Left-Handed)
                - Sport: ${sport.label}
                - Stroke: ${stroke.label} (${if (isTwoHandedBackhand) "Two-Handed 2H" else "One-Handed 1H"})
                - Shot: $shotName
                - Target Grip: ${target.gripName}
                - Target Primary Bevel (index base knuckle of $dominantHandWord hand): Bevel ${target.targetBevel}${if (isTwoHandedBackhand) "- Target Support Top Hand ($topHandSide index base knuckle): Bevel${target.supportTargetBevel ?: 7} (or Bevel 6)" else ""}

                Photos attached in order:
                1. Handle reference photo (if provided)
                2. Hand holding the grip photo (if provided)
                3. Final grip photo

                Inspect the actual position of the player's index finger base knuckle (MCP joint) on the octagonal handle/butt-cap.
                For example, if the target is Continental (Bevel 2) but the player is holding Semi-Western (Bevel 4), report detectedBevel=4 and a low score (~40-50).
                If the hand/knuckle is too blurry or hidden to tell honestly, set handDetected=false and score=0.

                Respond ONLY with raw valid JSON (no markdown fences):
                {
                  "score": <0..100 integer>,
                  "detectedBevel": <1..8 integer or null>,
                  "detectedSupportBevel": <1..8 integer or null>,
                  "detectedGripLabel": "<e.g. Semi-Western Forehand (Bevel 4)>",
                  "handDetected": <true or false>,
                  "bothHandsDetected": <true or false>,
                  "headline": "<short verdict with score>",
                  "detail": "<2 concise sentences stating what grip/bevel you actually see and how to adjust>"
                }
            """.trimIndent()

            val requestContent = com.google.firebase.ai.type.content {
    handleBitmap?.let { image(scaleDownForUpload(it)) }
    handInGripBitmap?.let { image(scaleDownForUpload(it)) }
    finalGripBitmap?.let { image(scaleDownForUpload(it)) }
    text(promptText)
            }

            val response = model.generateContent(requestContent)
            val raw = response.text?.trim()
                ?.removePrefix("```json")
                ?.removePrefix("```")
                ?.removeSuffix("```")
                ?.trim()
                ?: error("Empty Gemini response")

            val json = JSONObject(raw)
            val score = json.optInt("score", 0).coerceIn(0, 100)
            val detectedBevel = if (json.isNull("detectedBevel")) null else json.optInt("detectedBevel")
            val detectedSupportBevel =
                if (json.isNull("detectedSupportBevel")) null else json.optInt("detectedSupportBevel")
            val detectedGripLabel = json.optString("detectedGripLabel", "Unknown Grip")
            val handDetected = json.optBoolean("handDetected", detectedBevel != null)
            val bothHandsDetected = json.optBoolean("bothHandsDetected", detectedSupportBevel != null)
            val headline = json.optString("headline", "$score% — $detectedGripLabel")
            val detail = json.optString("detail", "Online AI grip check completed.")

            GripAuditResult(
                score = score,
                detectedBevel = detectedBevel,
                detectedSupportBevel = detectedSupportBevel,
                detectedGripLabel = detectedGripLabel,
                handDetected = handDetected,
                bothHandsDetected = bothHandsDetected,
                sourceEngine = "Online AI (Gemini Vision)",
                headline = headline,
                detail = detail
            )
        }
    }

    private fun scaleDownForUpload(src: Bitmap, maxDim: Int = 768): Bitmap {
        val largest = maxOf(src.width, src.height)
        if (largest <= maxDim) return src
        val scale = maxDim.toFloat() / largest.toFloat()
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).roundToInt(),
            (src.height * scale).roundToInt(),
            true
        )
    }
}

// ==========================================
// 3. OFFLINE MEDIAPIPE TRACKER (ANTI-GUESSING)
// ==========================================

class GripHandTracker(
    context: Context,
    private val baseAlpha: Float = 0.58f,
    private val maxStepJump: Float = 0.22f,
    private val onHandUpdated: (HandTrackingState) -> Unit
) {
    @Volatile
    var captureNextFrame: Boolean = false

    @Volatile
    var runLiveHandDetection: Boolean = false

    @Volatile
    var maxHandsToTrack: Int = 1

    @Volatile
    var onFrameCaptured: ((Bitmap) -> Unit)? = null

    private var handLandmarker: HandLandmarker? = null
    private var smoothedSlots: MutableList<List<SmoothedPoint>> = mutableListOf()

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            // Honest offline thresholds so we don't hallucinate fake landmarks
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.32f)
                .setMinHandPresenceConfidence(0.32f)
                .setMinTrackingConfidence(0.35f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun processFrame(imageProxy: ImageProxy) {
        try {
            val wantCapture = captureNextFrame
            val wantLive = runLiveHandDetection
            if (!wantCapture && !wantLive) return

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
                ).also {
                    if (it != rawBitmap) rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            if (wantCapture) {
                captureNextFrame = false
                onFrameCaptured?.invoke(rotatedBitmap)
            }

            if (wantLive) {
                val detected = detectHandsInBitmap(rotatedBitmap, maxHandsToTrack)
                if (detected.isNotEmpty()) {
                    val updatedSlots = mutableListOf<List<SmoothedPoint>>()
                    val smoothedHands = detected.mapIndexed { idx, hand ->
                        val prev = smoothedSlots.getOrNull(idx)
                        val sm = smoothPoints(hand.landmarks, prev)
                        updatedSlots.add(sm)
                        TrackedHand(landmarks = sm, isLeftDetected = hand.isLeftDetected)
                    }
                    smoothedSlots = updatedSlots
                    onHandUpdated(HandTrackingState(hands = smoothedHands, isHandVisible = true))
                } else {
                    onHandUpdated(HandTrackingState(hands = emptyList(), isHandVisible = false))
                }
            }
        } catch (e: Exception) {
            captureNextFrame = false
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    private fun detectHandsInBitmap(bitmap: Bitmap, maxHands: Int): List<TrackedHand> {
        val landmarker = handLandmarker ?: return emptyList()
        val candidates = buildDetectionCandidates(bitmap)

        for (candidate in candidates) {
            try {
                val mpImage = BitmapImageBuilder(candidate).build()
                val res = landmarker.detect(mpImage)
                val rawHands = res.landmarks().take(maxHands.coerceIn(1, 2))
                if (rawHands.isNotEmpty()) {
                    val handednessLists: List<List<Category>> = res.handednesses()
                    return rawHands.mapIndexed { idx, lmList ->
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
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun buildDetectionCandidates(source: Bitmap): List<Bitmap> {
        val list = mutableListOf<Bitmap>()
        list.add(source)

        val boosted = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(boosted)
        val cm = ColorMatrix().apply {
            setScale(1.15f, 1.15f, 1.15f, 1f)
        }
        val paint = AndroidPaint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        list.add(boosted)

        return list
    }

    private fun smoothPoints(
        raw: List<SmoothedPoint>,
        prev: List<SmoothedPoint>?
    ): List<SmoothedPoint> {
        if (prev == null || prev.size != raw.size) return raw
        return raw.mapIndexed { idx, cur ->
            val p = prev[idx]
            val dx = cur.x - p.x
            val dy = cur.y - p.y
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

            val clampedX = if (dist > maxStepJump && dist > 0f) p.x + (dx / dist) * maxStepJump else cur.x
            val clampedY = if (dist > maxStepJump && dist > 0f) p.y + (dy / dist) * maxStepJump else cur.y

            val speedFactor = (dist / 0.08f).coerceIn(0.5f, 1.4f)
            val alpha = (baseAlpha * speedFactor).coerceIn(0.28f, 0.78f)

            SmoothedPoint(
                x = alpha * clampedX + (1f - alpha) * p.x,
                y = alpha * clampedY + (1f - alpha) * p.y,
                z = alpha * cur.z + (1f - alpha) * p.z
            )
        }
    }

    fun resetSmoothing() {
        smoothedSlots.clear()
    }

    fun analyzeGripBitmapsOffline(
        bitmaps: List<Bitmap>,
        target: GripTarget,
        isLeftHanded: Boolean,
        isTwoHandedBackhand: Boolean,
        shotName: String,
        fallbackNotice: String? = null
    ): GripAuditResult {
        val engineLabel = fallbackNotice ?: "Offline AI (On-Device)"
        val landmarker = handLandmarker
        if (landmarker == null) {
            return GripAuditResult(
                score = 0,
                detectedBevel = null,
                detectedSupportBevel = null,
                detectedGripLabel = "Offline Model Unavailable",
                handDetected = false,
                bothHandsDetected = false,
                sourceEngine = engineLabel,
                headline = "Offline Tracker Unavailable",
                detail = "Could not load hand_landmarker.task."
            )
        }

        return try {
            val maxHands = if (isTwoHandedBackhand) 2 else 1
            var bestTrackedHands: List<TrackedHand> = emptyList()

            for (bmp in bitmaps.asReversed()) {
                val found = detectHandsInBitmap(bmp, maxHands)
                if (found.isNotEmpty()) {
                    bestTrackedHands = found
                    if (!isTwoHandedBackhand || found.size >= 2) break
                }
            }

            if (bestTrackedHands.isEmpty()) {
                onHandUpdated(HandTrackingState(emptyList(), false))
                return GripAuditResult(
                    score = 0,
                    detectedBevel = null,
                    detectedSupportBevel = null,
                    detectedGripLabel = "Unclear View — Not Guessing",
                    handDetected = false,
                    bothHandsDetected = false,
                    sourceEngine = engineLabel,
                    headline = "Unclear View — Retake Pic 3",
                    detail = "Offline AI could not lock onto your index base knuckle cleanly, so it refused to guess. Step back slightly or use a 3/4 angle showing both your knuckle and the butt cap, or switch to Online AI."
                )
            }

            onHandUpdated(HandTrackingState(hands = bestTrackedHands, isHandVisible = true))

            val pair = resolveHandPair(bestTrackedHands, isLeftHanded)
            val anchor = pair.anchorHand ?: bestTrackedHands.first()
            val anchorKnuckle = anchor.landmarks.getOrNull(5)
                ?: return GripAuditResult(
                    score = 0,
                    detectedBevel = null,
                    detectedSupportBevel = null,
                    detectedGripLabel = "Knuckle Not Found",
                    handDetected = false,
                    bothHandsDetected = false,
                    sourceEngine = engineLabel,
                    headline = "Base Knuckle Not Visible",
                    detail = "Retake Pic 3 with your index base knuckle clearly visible on the handle."
                )

            val detectedAnchorBevel = estimateBevelFromNormalizedPoint(
                point = anchorKnuckle,
                isLeftHanded = isLeftHanded
            )
            val detectedAnchorGrip = bevelToGripName(detectedAnchorBevel)
            val anchorScore = scoreBevelMatch(detectedAnchorBevel, target.targetBevel)
            val handLabel = if (isLeftHanded) "left" else "right"
            val topHandLabel = if (isLeftHanded) "right" else "left"

            if (isTwoHandedBackhand) {
                val support = pair.supportHand
                val supportKnuckle = support?.landmarks?.getOrNull(5)
                val hasSecond = supportKnuckle != null
                val supportTarget = target.supportTargetBevel ?: 7

                if (!hasSecond) {
                    val partialScore = (anchorScore * 0.50f).roundToInt().coerceIn(20, 55)
                    GripAuditResult(
                        score = partialScore,
                        detectedBevel = detectedAnchorBevel,
                        detectedSupportBevel = null,
                        detectedGripLabel = "$detectedAnchorGrip (2nd hand missing)",
                        handDetected = true,
                        bothHandsDetected = false,
                        sourceEngine = engineLabel,
                        headline = "$partialScore% — Only 1 Hand Detected (Need 2H)",
                        detail = "Bottom $handLabel knuckle is on Bevel $detectedAnchorBevel ($detectedAnchorGrip; target Bevel ${target.targetBevel}), but your top $topHandLabel hand wasn't visible. Retake Pic 3 showing both base knuckles."
                    )
                } else {
                    val detectedSupportBevel = estimateBevelFromNormalizedPoint(
                        point = supportKnuckle!!,
                        isLeftHanded = isLeftHanded
                    )
                    val supportScore = maxOf(
                        scoreBevelMatch(detectedSupportBevel, supportTarget),
                        scoreBevelMatch(detectedSupportBevel, 6)
                    )
                    val combinedScore = ((anchorScore * 0.55f) + (supportScore * 0.45f))
                        .roundToInt()
                        .coerceIn(15, 96)

                    val statusTitle = when {
                        combinedScore >= 90 -> "$combinedScore% — Locked ${target.gripName}"
                        combinedScore >= 70 -> "$combinedScore% — Close to ${target.gripName}"
                        else -> "$combinedScore% — Wrong 2H Grip Detected"
                    }

                    GripAuditResult(
                        score = combinedScore,
                        detectedBevel = detectedAnchorBevel,
                        detectedSupportBevel = detectedSupportBevel,
                        detectedGripLabel = "Bottom: Bevel $detectedAnchorBevel • Top: Bevel $detectedSupportBevel",
                        handDetected = true,
                        bothHandsDetected = true,
                        sourceEngine = engineLabel,
                        headline = statusTitle,
                        detail = "Bottom $handLabel knuckle on Bevel $detectedAnchorBevel (target Bevel ${target.targetBevel}); top $topHandLabel knuckle on Bevel $detectedSupportBevel (target Bevel 7/6) for $shotName."
                    )
                }
            } else {
                val statusTitle = when {
                    anchorScore >= 90 -> "$anchorScore% — Correct ${target.gripName}"
                    anchorScore >= 70 -> "$anchorScore% — 1 Bevel Off (${target.gripName})"
                    else -> "$anchorScore% — Detected $detectedAnchorGrip"
                }

                val coachingTip = if (anchorScore >= 90) {
                    "Your $handLabel index base knuckle is aligned on Bevel ${target.targetBevel} for $shotName."
                } else {
                    "Detected $detectedAnchorGrip instead of ${target.gripName} (Bevel ${target.targetBevel}). Rotate your $handLabel index base knuckle from Bevel $detectedAnchorBevel to Bevel ${target.targetBevel}."
                }

                GripAuditResult(
                    score = anchorScore,
                    detectedBevel = detectedAnchorBevel,
                    detectedSupportBevel = null,
                    detectedGripLabel = detectedAnchorGrip,
                    handDetected = true,
                    bothHandsDetected = false,
                    sourceEngine = engineLabel,
                    headline = statusTitle,
                    detail = coachingTip
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            GripAuditResult(
                score = 0,
                detectedBevel = null,
                detectedSupportBevel = null,
                detectedGripLabel = "Analysis Error",
                handDetected = false,
                bothHandsDetected = false,
                sourceEngine = engineLabel,
                headline = "Could Not Analyze Photo",
                detail = "Please retake Pic 3 with clear lighting on your hand and handle."
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
// 4. HAND, SPORT, STROKE, SHOT & PREVIEW SCREENS
// ==========================================

@Composable
fun AiModeToggleRow(
    aiMode: AiAnalysisMode,
    onSelectAiMode: (AiAnalysisMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AiAnalysisMode.entries.forEach { mode ->
            val selected = aiMode == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .background(
                        color = if (selected) Color(0xFF00E5FF) else Color(0xFF121A1E),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelectAiMode(mode) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode.badge,
                    color = if (selected) Color.Black else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
fun DominantHandSelectionScreen(
    aiMode: AiAnalysisMode,
    onSelectAiMode: (AiAnalysisMode) -> Unit,
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

            Spacer(modifier = Modifier.height(30.dp))

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

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "AI Analysis Mode",
                color = Color.LightGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            AiModeToggleRow(
                aiMode = aiMode,
                onSelectAiMode = onSelectAiMode
            )
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
            .height(180.dp)
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
                            fontSize = 11.sp,
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
    val strokes = remember(sport) { getStrokesFor(sport) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F12))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
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

            Spacer(modifier = Modifier.height(16.dp))

            strokes.forEach { stroke ->
                val extraLabel = if (stroke == StrokeType.BACKHAND) " (${backhandStyle.label})" else ""
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clickable { onSelectStroke(stroke) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    border = BorderStroke(1.2.dp, Color(0xFF1E2930))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${stroke.label.uppercase()}$extraLabel",
                            color = Color.White,
                            fontSize = 16.sp,
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

        // Moved Backhand 1H / 2H selector to the bottom of the page
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BackhandStyle.entries.forEach { style ->
                val selected = backhandStyle == style
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .background(
                            color = if (selected) Color(0xFFFFD740) else Color(0xFF121A1E),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectBackhandStyle(style) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (style == BackhandStyle.ONE_HANDED) "Backhand: 1H" else "Backhand: 2H",
                        color = if (selected) Color.Black else Color.White,
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
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

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "${sport.label} • ${stroke.label}",
            color = Color(0xFF00FF88),
            fontSize = 23.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "${if (isLeftHanded) "Left-Handed" else "Right-Handed"} • Select Shot",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        shots.forEach { shot ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clickable { onSelectShot(shot) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                border = BorderStroke(1.2.dp, Color(0xFF1E2930))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 15.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = shot.uppercase(),
                        color = Color.White,
                        fontSize = 16.sp,
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
    aiMode: AiAnalysisMode,
    onSelectAiMode: (AiAnalysisMode) -> Unit,
    onStartCapture: () -> Unit,
    onBackToShot: () -> Unit,
    onMainMenu: () -> Unit
) {
    val bevelSummary = if (target.supportTargetBevel != null) {
        "Bevel ${target.targetBevel} (Bottom) + Bevel ${target.supportTargetBevel} (Top)"
    } else {
        "Bevel ${target.targetBevel}"
    }

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

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "${sport.label} • ${stroke.label} • $shotName",
            color = Color.LightGray,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = target.gripName,
            color = target.bevelColor,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )

        Text(
            text = bevelSummary,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            border = BorderStroke(1.2.dp, Color(0xFF1E2930))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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
                    text = "1. Take pic of handle just as it is (Bevel 1 at top)\n2. Take pic with handle in hand as-is\n3. Take one last pic and AI checks your grip",
                    color = Color(0xFF00E5FF),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        AiModeToggleRow(
            aiMode = aiMode,
            onSelectAiMode = onSelectAiMode
        )

        Spacer(modifier = Modifier.height(18.dp))

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
    var aiMode by remember { mutableStateOf(AiAnalysisMode.ONLINE_GEMINI) }
    var isLeftHanded by remember { mutableStateOf(false) }
    var selectedSport by remember { mutableStateOf(GripSport.PADEL) }
    var selectedStroke by remember { mutableStateOf(StrokeType.SMASH) }
    var selectedShot by remember { mutableStateOf("Bandeja") }
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
                aiMode = aiMode,
                onSelectAiMode = { aiMode = it },
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
                aiMode = aiMode,
                onSelectAiMode = { aiMode = it },
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
                stroke = selectedStroke,
                shotName = selectedShot,
                target = currentTarget,
                aiMode = aiMode,
                onToggleAiMode = {
                    aiMode = if (aiMode == AiAnalysisMode.ONLINE_GEMINI) {
                        AiAnalysisMode.OFFLINE_MEDIAPIPE
                    } else {
                        AiAnalysisMode.ONLINE_GEMINI
                    }
                },
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
                isLeftHanded = isLeftHanded,
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
    stroke: StrokeType,
    shotName: String,
    target: GripTarget,
    aiMode: AiAnalysisMode,
    onToggleAiMode: () -> Unit,
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

    val activePreviewBitmap = when (captureStage) {
        CaptureStage.HANDLE_PICTURE -> handleBitmap
        CaptureStage.HAND_ON_HANDLE_PICTURE -> handInGripBitmap
        CaptureStage.FINAL_GRIP_PICTURE -> finalGripBitmap
    }

    val liveTrackingEnabled = captureStage != CaptureStage.HANDLE_PICTURE

    LaunchedEffect(captureStage, isTwoHandedBackhand) {
        tracker.resetSmoothing()
        tracker.maxHandsToTrack = if (isTwoHandedBackhand) 2 else 1
        tracker.runLiveHandDetection = liveTrackingEnabled
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
            tracker.runLiveHandDetection = false
            tracker.onFrameCaptured = null
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

        ButtCapHandleOverlay(
            targetBevel = target.targetBevel,
            supportTargetBevel = target.supportTargetBevel,
            targetColor = target.bevelColor,
            isLeftHanded = isLeftHanded,
            showSilhouette = (captureStage == CaptureStage.HANDLE_PICTURE && handleBitmap == null)
        )

        if (captureStage != CaptureStage.HANDLE_PICTURE && handState.isHandVisible) {
            BaseKnuckleSpotOverlay(
                hands = handState.hands,
                isLeftHanded = isLeftHanded,
                isTwoHandedBackhand = isTwoHandedBackhand,
                singleHandColor = target.bevelColor
            )
        }

        // Top Bar with Online / Offline Switch
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
                    .background(
                        color = if (aiMode == AiAnalysisMode.ONLINE_GEMINI) Color(0xFF00E5FF) else Color(0xFF28333A),
                        shape = CircleShape
                    )
                    .clickable { onToggleAiMode() }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    text = aiMode.badge,
                    color = if (aiMode == AiAnalysisMode.ONLINE_GEMINI) Color.Black else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }
        }

        // Live Knuckle Status Pill in Pic 2 & Pic 3
        if (captureStage != CaptureStage.HANDLE_PICTURE) {
            val pair = resolveHandPair(handState.hands, isLeftHanded)
            val anchorBevel = pair.anchorHand?.landmarks?.getOrNull(5)?.let {
                estimateBevelFromNormalizedPoint(it, isLeftHanded)
            }
            val supportBevel = pair.supportHand?.landmarks?.getOrNull(5)?.let {
                estimateBevelFromNormalizedPoint(it, isLeftHanded)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 50.dp, start = 12.dp, end = 12.dp)
                    .background(Color(0xDD10181C), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isTwoHandedBackhand) {
                        val b1 = anchorBevel?.toString() ?: "--"
                        val b2 = supportBevel?.toString() ?: "--"
                        "2H Knuckles • Green: B$b1 (Tgt ${target.targetBevel}) • Cyan: B$b2 (Tgt 7)"
                    } else {
                        val b1 = anchorBevel?.toString() ?: "--"
                        "Base Knuckle • Bevel $b1 (Target Bevel ${target.targetBevel})"
                    },
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                colors = CardDefaults.cardColors(containerColor = Color(0xE6121A1E))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "${captureStage.title} • ${aiMode.badge}",
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
                        text = if (target.supportTargetBevel != null) {
                            "Target: ${target.gripName} (Bottom Bevel ${target.targetBevel} + Top Bevel ${target.supportTargetBevel})"
                        } else {
                            "Target: ${target.gripName} (Bevel ${target.targetBevel})"
                        },
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
                                        val aiResult = if (aiMode == AiAnalysisMode.ONLINE_GEMINI) {
                                            val onlineAttempt = OnlineGeminiGripAnalyzer.analyze(
                                                handleBitmap = handleBitmap,
                                                handInGripBitmap = handInGripBitmap,
                                                finalGripBitmap = finalGripBitmap,
                                                sport = sport,
                                                stroke = stroke,
                                                shotName = shotName,
                                                target = target,
                                                isLeftHanded = isLeftHanded,
                                                isTwoHandedBackhand = isTwoHandedBackhand
                                            )
                                            onlineAttempt.getOrElse {
                                                withContext(Dispatchers.Default) {
                                                    tracker.analyzeGripBitmapsOffline(
                                                        bitmaps = picsToCheck,
                                                        target = target,
                                                        isLeftHanded = isLeftHanded,
                                                        isTwoHandedBackhand = isTwoHandedBackhand,
                                                        shotName = shotName,
                                                        fallbackNotice = "Offline Fallback (No Online Connection)"
                                                    )
                                                }
                                            }
                                        } else {
                                            withContext(Dispatchers.Default) {
                                                tracker.analyzeGripBitmapsOffline(
                                                    bitmaps = picsToCheck,
                                                    target = target,
                                                    isLeftHanded = isLeftHanded,
                                                    isTwoHandedBackhand = isTwoHandedBackhand,
                                                    shotName = shotName
                                                )
                                            }
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
    isLeftHanded: Boolean,
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
            .padding(horizontal = 20.dp, vertical = 18.dp),
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
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    Image(
                        bitmap = finalGripBitmap.asImageBitmap(),
                        contentDescription = "Final Grip Photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    ButtCapHandleOverlay(
                        targetBevel = target.targetBevel,
                        supportTargetBevel = target.supportTargetBevel,
                        targetColor = target.bevelColor,
                        isLeftHanded = isLeftHanded,
                        showSilhouette = false
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            GripMatchGauge(
                score = result.score,
                ringColor = when {
                    result.score >= 90 -> Color(0xFF00FF88)
                    result.score >= 70 -> Color(0xFFFFD740)
                    else -> Color(0xFFFF5252)
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = result.sourceEngine,
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = result.headline,
                color = when {
                    result.score >= 90 -> Color(0xFF00FF88)
                    result.score >= 70 -> Color(0xFFFFD740)
                    else -> Color(0xFFFF5252)
                },
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Detected: ${result.detectedGripLabel}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                border = BorderStroke(1.2.dp, Color(0xFF1E2930))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
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
    supportTargetBevel: Int? = null,
    targetColor: Color,
    isLeftHanded: Boolean,
    showSilhouette: Boolean
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val centerX = w * OVERLAY_CENTER_X
        val centerY = h * OVERLAY_CENTER_Y

        val halfW = w * OVERLAY_HALF_W
        val halfH = h * OVERLAY_HALF_H
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

            val isPrimaryTarget = (bevel == targetBevel)
            val isSupportTarget = (supportTargetBevel != null && bevel == supportTargetBevel)
            val isAnyTarget = isPrimaryTarget || isSupportTarget
            val color = when {
                isPrimaryTarget -> targetColor
                isSupportTarget -> SUPPORT_HAND_COLOR
                else -> BEVEL_COLORS.getValue(bevel)
            }

            if (isAnyTarget) {
                drawCircle(
                    color = color.copy(alpha = 0.30f),
                    radius = 18.dp.toPx(),
                    center = pos
                )
            }

            drawCircle(
                color = if (isAnyTarget) color else Color(0xCC10181C),
                radius = if (isAnyTarget) 12.dp.toPx() else 9.dp.toPx(),
                center = pos
            )
            drawCircle(
                color = color,
                radius = if (isAnyTarget) 12.dp.toPx() else 9.dp.toPx(),
                center = pos,
                style = Stroke(width = 1.5.dp.toPx())
            )

            textPaint.color = if (isAnyTarget) android.graphics.Color.BLACK else android.graphics.Color.WHITE
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
                color = spotColor.copy(alpha = 0.28f),
                radius = 22.dp.toPx(),
                center = knucklePoint
            )
            drawCircle(
                color = spotColor.copy(alpha = 0.95f),
                radius = 22.dp.toPx(),
                center = knucklePoint,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = spotColor,
                radius = 10.5.dp.toPx(),
                center = knucklePoint
            )
            drawCircle(
                color = Color.White,
                radius = 10.5.dp.toPx(),
                center = knucklePoint,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
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
