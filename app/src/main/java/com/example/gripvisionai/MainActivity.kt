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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.horizontalScroll


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

enum class GripSport(val label: String) {
    TENNIS("Tennis"),
    PADEL("Padel"),
    PICKLEBALL("Pickleball")
}

enum class ShotPreset(val label: String) {
    SERVE("Serve"),
    FOREHAND("Forehand"),
    BACKHAND("Backhand"),
    VOLLEY("Volley"),
    SLICE_DINK("Slice / Dink")
}

enum class BackhandStyle(val label: String) {
    ONE_HANDED("1 Hand"),
    TWO_HANDED("2 Hands")
}

enum class AppScreen {
    HANDEDNESS,
    HANDLE_CAPTURE,
    HAND_PLACEMENT,
    FINAL_CHECK,
    SUCCESS
}

data class GripTarget(
    val gripName: String,
    val targetBevel: Int,
    val heelPadBevel: Int,
    val bevelColor: Color,
    val shortInstruction: String,
    val fullGripInfo: String,
    val benefits: List<String>
)

val BEVEL_COLORS = mapOf(
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
val KNUCKLE_INSIDE_COLOR = Color(0xFFFFD740)
val KNUCKLE_OUTSIDE_COLOR = Color(0xFF40C4FF)
val HEEL_PAD_COLOR = Color(0xFFE040FB)

fun getGripTarget(
    sport: GripSport,
    shot: ShotPreset,
    backhandStyle: BackhandStyle,
    isLeftHanded: Boolean
): GripTarget {
    val hand = if (isLeftHanded) "left" else "right"
    val supportHand = if (isLeftHanded) "right" else "left"

    fun target(
        name: String,
        knuckleBevel: Int,
        heelBevel: Int,
        instruction: String,
        info: String,
        benefits: List<String>
    ) = GripTarget(
        gripName = name,
        targetBevel = knuckleBevel,
        heelPadBevel = heelBevel,
        bevelColor = BEVEL_COLORS.getValue(knuckleBevel),
        shortInstruction = instruction,
        fullGripInfo = info,
        benefits = benefits
    )

    return when (sport) {
        GripSport.TENNIS -> when (shot) {
            ShotPreset.SERVE -> target(
                name = "Continental Serve",
                knuckleBevel = 2,
                heelBevel = 2,
                instruction = "Place your $hand index base knuckle and heel pad around Bevel 2.",
                info = "The continental grip is the standard starting grip for serves, overheads and many volleys. Hold the racket like a hammer and keep the wrist relaxed.",
                benefits = listOf(
                    "Supports pronation",
                    "Suitable for flat, slice and kick serves",
                    "Allows a relaxed serving motion"
                )
            )

            ShotPreset.FOREHAND -> target(
                name = "Semi-Western Forehand",
                knuckleBevel = 4,
                heelBevel = 4,
                instruction = "Rotate your $hand index base knuckle and heel pad towards Bevel 4.",
                info = "The semi-western forehand grip closes the racket face slightly and is a practical modern grip for producing topspin.",
                benefits = listOf(
                    "Strong topspin potential",
                    "Good higher-ball control",
                    "Useful balance of spin and drive"
                )
            )

            ShotPreset.BACKHAND -> {
                if (backhandStyle == BackhandStyle.ONE_HANDED) {
                    target(
                        name = "Eastern One-Handed Backhand",
                        knuckleBevel = 1,
                        heelBevel = 1,
                        instruction = "Place your $hand index base knuckle near the top Bevel 1.",
                        info = "The eastern backhand grip helps support the wrist behind the racket during a one-handed backhand.",
                        benefits = listOf(
                            "Firm wrist position",
                            "Useful for topspin backhands",
                            "Supports a long forward swing"
                        )
                    )
                } else {
                    target(
                        name = "Two-Handed Backhand",
                        knuckleBevel = 2,
                        heelBevel = 2,
                        instruction = "Anchor your $hand hand near Bevel 2 and place your $supportHand hand above it.",
                        info = "The dominant hand provides stability while the supporting hand helps drive and control the racket.",
                        benefits = listOf(
                            "Extra stability",
                            "Good return-of-serve control",
                            "Support hand helps create acceleration"
                        )
                    )
                }
            }

            ShotPreset.VOLLEY -> target(
                name = "Continental Volley",
                knuckleBevel = 2,
                heelBevel = 2,
                instruction = "Keep your $hand index knuckle and heel pad around Bevel 2.",
                info = "The continental grip allows quick switching between forehand and backhand volleys.",
                benefits = listOf(
                    "Fast grip changes",
                    "Stable racket face",
                    "Works on forehand and backhand volleys"
                )
            )

            ShotPreset.SLICE_DINK -> target(
                name = "Continental Slice",
                knuckleBevel = 2,
                heelBevel = 2,
                instruction = "Place your $hand index base knuckle around Bevel 2.",
                info = "The continental grip makes it easier to keep the racket face slightly open and create backspin.",
                benefits = listOf(
                    "Natural open racket face",
                    "Good defensive control",
                    "Suitable for low balls"
                )
            )
        }

        GripSport.PADEL -> when (shot) {
            ShotPreset.SERVE -> target(
                "Continental Padel Serve",
                2,
                2,
                "Place your $hand index base knuckle and heel pad around Bevel 2.",
                "The continental grip provides a neutral racket face for an underarm padel serve.",
                listOf("Controlled placement", "Natural slice", "Simple transition to the next shot")
            )

            ShotPreset.FOREHAND -> target(
                "Continental Forehand",
                2,
                2,
                "Keep your $hand index base knuckle near Bevel 2.",
                "A continental grip keeps grip changes small and works well for compact padel strokes.",
                listOf("Quick preparation", "Useful near the glass", "Good defensive control")
            )

            ShotPreset.BACKHAND -> {
                if (backhandStyle == BackhandStyle.ONE_HANDED) {
                    target(
                        "Continental Backhand",
                        2,
                        2,
                        "Keep your $hand knuckle near Bevel 2.",
                        "A compact continental backhand is useful for blocks, volleys and controlled shots from the back court.",
                        listOf("Compact preparation", "Slice control", "Easy volley transition")
                    )
                } else {
                    target(
                        "Two-Handed Padel Backhand",
                        2,
                        2,
                        "Anchor your $hand hand near Bevel 2 and add your $supportHand hand above.",
                        "A second hand can provide support and stability on selected backhands.",
                        listOf("Extra stability", "Compact movement", "Useful defensive support")
                    )
                }
            }

            ShotPreset.VOLLEY -> target(
                "Continental Padel Volley",
                2,
                2,
                "Keep your $hand index base knuckle near Bevel 2.",
                "The continental grip supports short, controlled forehand and backhand volley movements.",
                listOf("Quick reactions", "Stable face", "Minimal grip change")
            )

            ShotPreset.SLICE_DINK -> target(
                "Continental Bandeja",
                2,
                2,
                "Place your $hand index base knuckle near Bevel 2.",
                "The continental grip supports slice, controlled overheads, bandejas and many defensive shots.",
                listOf("Slice control", "Overhead versatility", "Controlled racket face")
            )
        }

        GripSport.PICKLEBALL -> when (shot) {
            ShotPreset.SERVE -> target(
                "Eastern Serve",
                3,
                3,
                "Place your $hand index base knuckle near Bevel 3.",
                "An eastern-style grip provides a comfortable starting point for a driving pickleball serve.",
                listOf("Natural forward swing", "Good drive control", "Comfortable contact")
            )

            ShotPreset.FOREHAND -> target(
                "Eastern Forehand",
                3,
                3,
                "Place your $hand index base knuckle and heel pad near Bevel 3.",
                "The eastern grip works well for forehand drives while remaining relatively easy to adjust at the kitchen.",
                listOf("Solid drives", "Natural paddle face", "Moderate topspin potential")
            )

            ShotPreset.BACKHAND -> {
                if (backhandStyle == BackhandStyle.ONE_HANDED) {
                    target(
                        "Continental Backhand",
                        2,
                        2,
                        "Place your $hand index base knuckle near Bevel 2.",
                        "The continental grip provides a stable paddle face for blocks, resets and backhand dinks.",
                        listOf("Stable blocks", "Soft-hand control", "Quick kitchen reactions")
                    )
                } else {
                    target(
                        "Two-Handed Pickleball Backhand",
                        2,
                        2,
                        "Anchor your $hand hand near Bevel 2 and place your $supportHand hand above.",
                        "The supporting hand can add stability and acceleration to a two-handed backhand.",
                        listOf("Extra drive support", "Greater stability", "Good high-ball control")
                    )
                }
            }

            ShotPreset.VOLLEY -> target(
                "Continental Volley",
                2,
                2,
                "Keep your $hand index base knuckle near Bevel 2.",
                "A continental grip provides a neutral paddle face for blocks and punch volleys.",
                listOf("Fast reactions", "Forehand and backhand coverage", "Stable paddle face")
            )

            ShotPreset.SLICE_DINK -> target(
                "Continental Dink",
                2,
                2,
                "Keep your $hand index base knuckle and heel pad near Bevel 2.",
                "The continental grip supports soft dinks, resets, blocks and quick exchanges at the kitchen.",
                listOf("Soft touch", "Quick transitions", "Neutral paddle position")
            )
        }
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

data class KnuckleZone(
    val centre: Offset,
    val inside: Offset,
    val outside: Offset,
    val heelPad: Offset
)

data class GripScore(
    val overall: Int = 0,
    val knuckle: Int = 0,
    val heelPad: Int = 0,
    val hasRequiredHands: Boolean = false
)

fun buttCapBevelPosition(
    bevel: Int,
    isLeftHanded: Boolean,
    centerX: Float,
    centerY: Float,
    halfW: Float,
    halfH: Float
): Offset {
    val safeBevel = bevel.coerceIn(1, 8)

    val slot = if (isLeftHanded) {
        (9 - safeBevel) % 8
    } else {
        safeBevel - 1
    }

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
class GripHandTracker(
    context: Context,
    private val onHandUpdated: (HandTrackingState) -> Unit
) {
    var captureNextFrame = false
    var onHandleSnapped: ((Bitmap) -> Unit)? = null

    private var handLandmarker: HandLandmarker? = null
    private var smoothedHands = mutableListOf<List<SmoothedPoint>>()
    private var lastValidTimestamp = 0L

    private val dropoutGraceMs = 250L
    private val smoothingAlpha = 0.48f
    private val maximumStep = 0.14f

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.35f)
                .setMinHandPresenceConfidence(0.35f)
                .setMinTrackingConfidence(0.40f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
    }

    fun processFrame(
        imageProxy: ImageProxy,
        runHandDetection: Boolean,
        maximumHands: Int
    ) {
        try {
            if (!captureNextFrame && !runHandDetection) return

            val original = imageProxyToBitmap(imageProxy) ?: return
            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()

            val bitmap = if (rotation != 0f) {
                val matrix = Matrix().apply {
                    postRotate(rotation)
                }

                Bitmap.createBitmap(
                    original,
                    0,
                    0,
                    original.width,
                    original.height,
                    matrix,
                    true
                )
            } else {
                original
            }

            if (captureNextFrame) {
                captureNextFrame = false
                onHandleSnapped?.invoke(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                return
            }

            if (!runHandDetection) return

            val landmarker = handLandmarker ?: return
            val mediaPipeImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mediaPipeImage)

            val requestedHands = maximumHands.coerceIn(1, 2)
            val rawHands = result.landmarks().take(requestedHands)
            val handedness: List<List<Category>> = result.handednesses()
            val now = System.currentTimeMillis()

            if (rawHands.isNotEmpty()) {
                val newSmoothedHands = mutableListOf<List<SmoothedPoint>>()

                val trackedHands = rawHands.mapIndexed { index, landmarks ->
                    val previous = smoothedHands.getOrNull(index)
                    val smoothed = smoothLandmarks(landmarks, previous)
                    newSmoothedHands.add(smoothed)

                    val categoryName = handedness
                        .getOrNull(index)
                        ?.firstOrNull()
                        ?.categoryName()
                        ?.lowercase()

                    val detectedLeft = when {
                        categoryName == null -> null
                        categoryName.contains("left") -> true
                        categoryName.contains("right") -> false
                        else -> null
                    }

                    TrackedHand(
                        landmarks = smoothed,
                        isLeftDetected = detectedLeft
                    )
                }

                smoothedHands = newSmoothedHands
                lastValidTimestamp = now

                onHandUpdated(
                    HandTrackingState(
                        hands = trackedHands,
                        isHandVisible = true
                    )
                )
            } else {
                val insideGracePeriod =
                    smoothedHands.isNotEmpty() &&
                        now - lastValidTimestamp <= dropoutGraceMs

                if (insideGracePeriod) {
                    onHandUpdated(
                        HandTrackingState(
                            hands = smoothedHands
                                .take(requestedHands)
                                .map { TrackedHand(it, null) },
                            isHandVisible = true
                        )
                    )
                } else {
                    smoothedHands.clear()
                    onHandUpdated(HandTrackingState())
                }
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    private fun smoothLandmarks(
        raw: List<NormalizedLandmark>,
        previous: List<SmoothedPoint>?
    ): List<SmoothedPoint> {
        if (previous == null || previous.size != raw.size) {
            return raw.map {
                SmoothedPoint(it.x(), it.y(), it.z())
            }
        }

        return raw.mapIndexed { index, landmark ->
            val old = previous[index]

            val dx = landmark.x() - old.x
            val dy = landmark.y() - old.y
            val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()

            val limitedX: Float
            val limitedY: Float

            if (distance > maximumStep && distance > 0f) {
                limitedX = old.x + dx / distance * maximumStep
                limitedY = old.y + dy / distance * maximumStep
            } else {
                limitedX = landmark.x()
                limitedY = landmark.y()
            }

            val movementFactor = (distance / 0.08f).coerceIn(0.55f, 1.30f)
            val alpha = (smoothingAlpha * movementFactor).coerceIn(0.24f, 0.72f)

            SmoothedPoint(
                x = alpha * limitedX + (1f - alpha) * old.x,
                y = alpha * limitedY + (1f - alpha) * old.y,
                z = alpha * landmark.z() + (1f - alpha) * old.z
            )
        }
    }

    fun resetSmoothing() {
        smoothedHands.clear()
        lastValidTimestamp = 0L
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

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            width,
            height,
            null
        )

        val output = ByteArrayOutputStream()

        yuvImage.compressToJpeg(
            Rect(0, 0, width, height),
            85,
            output
        )

        val bytes = output.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}

private fun selectAnchorHand(
    hands: List<TrackedHand>,
    isLeftHanded: Boolean
): TrackedHand? {
    if (hands.isEmpty()) return null

    return hands.firstOrNull {
        it.isLeftDetected == isLeftHanded
    } ?: hands.first()
}

/*
 * MediaPipe does not directly provide inside-edge, outside-edge or heel-pad
 * landmarks. These points are calculated estimates based on recognised hand
 * landmarks.
 */
private fun calculateKnuckleZone(
    hand: TrackedHand,
    screenSize: Size,
    isLeftHanded: Boolean
): KnuckleZone? {
    if (hand.landmarks.size < 21) return null

    fun point(index: Int): Offset {
        return Offset(
            x = hand.landmarks[index].x * screenSize.width,
            y = hand.landmarks[index].y * screenSize.height
        )
    }

    val wrist = point(0)
    val thumbCmc = point(1)
    val indexMcp = point(5)
    val middleMcp = point(9)
    val littleMcp = point(17)

    var acrossX = middleMcp.x - indexMcp.x
    var acrossY = middleMcp.y - indexMcp.y
    var acrossLength = hypot(
        acrossX.toDouble(),
        acrossY.toDouble()
    ).toFloat()

    if (acrossLength < 1f) {
        acrossX = littleMcp.x - indexMcp.x
        acrossY = littleMcp.y - indexMcp.y
        acrossLength = hypot(
            acrossX.toDouble(),
            acrossY.toDouble()
        ).toFloat()
    }

    if (acrossLength < 1f) return null

    val unitAcrossX = acrossX / acrossLength
    val unitAcrossY = acrossY / acrossLength

    val palmWidth = hypot(
        (littleMcp.x - indexMcp.x).toDouble(),
        (littleMcp.y - indexMcp.y).toDouble()
    ).toFloat()

    val edgeOffset = (palmWidth * 0.13f)
        .coerceIn(screenSize.width * 0.012f, screenSize.width * 0.035f)

    val firstEdge = Offset(
        indexMcp.x - unitAcrossX * edgeOffset,
        indexMcp.y - unitAcrossY * edgeOffset
    )

    val secondEdge = Offset(
        indexMcp.x + unitAcrossX * edgeOffset,
        indexMcp.y + unitAcrossY * edgeOffset
    )

    val inside: Offset
    val outside: Offset

    if (isLeftHanded) {
        inside = secondEdge
        outside = firstEdge
    } else {
        inside = firstEdge
        outside = secondEdge
    }

    /*
     * Estimate the heel pad from the wrist, thumb base and centre of the palm.
     * It is intentionally conservative because there is no dedicated
     * MediaPipe heel-pad landmark.
     */
    val palmCentre = Offset(
        x = (wrist.x + indexMcp.x + middleMcp.x + littleMcp.x) / 4f,
        y = (wrist.y + indexMcp.y + middleMcp.y + littleMcp.y) / 4f
    )

    val heelPad = Offset(
        x = wrist.x * 0.58f + palmCentre.x * 0.27f + thumbCmc.x * 0.15f,
        y = wrist.y * 0.58f + palmCentre.y * 0.27f + thumbCmc.y * 0.15f
    )

    return KnuckleZone(
        centre = indexMcp,
        inside = inside,
        outside = outside,
        heelPad = heelPad
    )
}

private fun pointScore(
    point: Offset,
    target: Offset,
    screenWidth: Float,
    previousScore: Int
): Int {
    val distance = hypot(
        (point.x - target.x).toDouble(),
        (point.y - target.y).toDouble()
    ).toFloat()

    val lockRadius = screenWidth * 0.105f
    val releaseRadius = screenWidth * 0.128f
    val maximumRadius = screenWidth * 0.34f

    return when {
        previousScore >= 96 && distance <= releaseRadius -> 100
        distance <= lockRadius -> 100
        distance >= maximumRadius -> 0
        else -> {
            val ratio = 1f -
                ((distance - lockRadius) / (maximumRadius - lockRadius))

            (ratio * 99f)
                .toInt()
                .coerceIn(0, 99)
        }
    }
}

private fun computeGripScore(
    hands: List<TrackedHand>,
    isLeftHanded: Boolean,
    isTwoHandedBackhand: Boolean,
    knuckleTarget: Offset,
    heelTarget: Offset,
    screenSize: Size,
    previous: GripScore
): GripScore {
    val anchorHand = selectAnchorHand(hands, isLeftHanded)
        ?: return GripScore()

    val zone = calculateKnuckleZone(
        hand = anchorHand,
        screenSize = screenSize,
        isLeftHanded = isLeftHanded
    ) ?: return GripScore()

    val knuckleScore = pointScore(
        point = zone.centre,
        target = knuckleTarget,
        screenWidth = screenSize.width,
        previousScore = previous.knuckle
    )

    val heelScore = pointScore(
        point = zone.heelPad,
        target = heelTarget,
        screenWidth = screenSize.width,
        previousScore = previous.heelPad
    )

    val hasRequiredHands =
        !isTwoHandedBackhand || hands.size >= 2

    /*
     * The base knuckle is weighted more heavily because it is the primary
     * visual teaching reference. The heel-pad point is an estimate.
     */
    var overall = (
        knuckleScore * 0.70f +
            heelScore * 0.30f
        ).toInt()

    if (!hasRequiredHands) {
        overall = overall.coerceAtMost(89)
    }

    if (
        hasRequiredHands &&
        knuckleScore >= 94 &&
        heelScore >= 86
    ) {
        overall = 100
    }

    return GripScore(
        overall = overall.coerceIn(0, 100),
        knuckle = knuckleScore,
        heelPad = heelScore,
        hasRequiredHands = hasRequiredHands
    )
}
@Composable
fun GripVisionApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
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

    var currentScreen by remember {
        mutableStateOf(AppScreen.HANDEDNESS)
    }

    var selectedSport by remember {
        mutableStateOf(GripSport.TENNIS)
    }

    var selectedShot by remember {
        mutableStateOf(ShotPreset.SERVE)
    }

    var backhandStyle by remember {
        mutableStateOf(BackhandStyle.ONE_HANDED)
    }

    var isLeftHanded by remember {
        mutableStateOf(false)
    }

    var capturedHandle by remember {
        mutableStateOf<Bitmap?>(null)
    }

    var handState by remember {
        mutableStateOf(HandTrackingState())
    }

    var gripScore by remember {
        mutableStateOf(GripScore())
    }

    var verifiedFrames by remember {
        mutableIntStateOf(0)
    }

    val currentTarget = getGripTarget(
        sport = selectedSport,
        shot = selectedShot,
        backhandStyle = backhandStyle,
        isLeftHanded = isLeftHanded
    )

    val isBackhand = selectedShot == ShotPreset.BACKHAND

    val isTwoHandedBackhand =
        isBackhand &&
            backhandStyle == BackhandStyle.TWO_HANDED

    val tracker = remember {
        GripHandTracker(context) { state ->
            handState = state
        }
    }

    LaunchedEffect(Unit) {
        tracker.onHandleSnapped = { bitmap ->
            capturedHandle = bitmap
        }
    }

    LaunchedEffect(
        currentScreen,
        selectedSport,
        selectedShot,
        backhandStyle,
        isLeftHanded
    ) {
        tracker.resetSmoothing()
        gripScore = GripScore()
        verifiedFrames = 0
    }

    LaunchedEffect(
        currentScreen,
        gripScore.overall,
        gripScore.hasRequiredHands
    ) {
        if (currentScreen == AppScreen.FINAL_CHECK) {
            if (
                gripScore.overall == 100 &&
                gripScore.hasRequiredHands
            ) {
                verifiedFrames++

                if (verifiedFrames >= 5) {
                    currentScreen = AppScreen.SUCCESS
                }
            } else {
                verifiedFrames = 0
            }
        }
    }

    val cameraExecutor = remember {
        Executors.newSingleThreadExecutor()
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            tracker.close()
        }
    }

    val runHandDetection =
        currentScreen == AppScreen.HAND_PLACEMENT ||
            currentScreen == AppScreen.FINAL_CHECK

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (
            hasCameraPermission &&
            currentScreen != AppScreen.SUCCESS
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { androidContext ->
                    val previewView = PreviewView(androidContext).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    val providerFuture =
                        ProcessCameraProvider.getInstance(androidContext)

                    providerFuture.addListener(
                        {
                            val cameraProvider = providerFuture.get()

                            val preview = Preview.Builder()
                                .build()
                                .also {
                                    it.setSurfaceProvider(
                                        previewView.surfaceProvider
                                    )
                                }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(
                                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                )
                                .build()
                                .also { analysis ->
                                    analysis.setAnalyzer(cameraExecutor) { proxy ->
                                        tracker.processFrame(
                                            imageProxy = proxy,
                                            runHandDetection = runHandDetection,
                                            maximumHands =
                                                if (isTwoHandedBackhand) 2 else 1
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
                        ContextCompat.getMainExecutor(androidContext)
                    )

                    previewView
                }
            )
        }

        if (!hasCameraPermission) {
            CameraPermissionView(
                onRequestPermission = {
                    permissionLauncher.launch(
                        Manifest.permission.CAMERA
                    )
                }
            )
        } else {
            when (currentScreen) {
                AppScreen.HANDEDNESS -> {
                    HandednessScreen(
                        onLeftHanded = {
                            isLeftHanded = true
                            capturedHandle = null
                            currentScreen = AppScreen.HANDLE_CAPTURE
                        },
                        onRightHanded = {
                            isLeftHanded = false
                            capturedHandle = null
                            currentScreen = AppScreen.HANDLE_CAPTURE
                        }
                    )
                }

                AppScreen.HANDLE_CAPTURE -> {
                    if (capturedHandle != null) {
                        Image(
                            bitmap = capturedHandle!!.asImageBitmap(),
                            contentDescription = "Captured handle",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    GripTargetOverlay(
                        target = currentTarget,
                        isLeftHanded = isLeftHanded,
                        showHandleOutline = capturedHandle == null,
                        isLocked = false,
                        onTargetsCalculated = { _, _, _ -> }
                    )

                    GripSelectionHud(
                        selectedSport = selectedSport,
                        selectedShot = selectedShot,
                        backhandStyle = backhandStyle,
                        isLeftHanded = isLeftHanded,
                        currentTarget = currentTarget,
                        onSportSelected = {
                            selectedSport = it
                            capturedHandle = null
                        },
                        onShotSelected = {
                            selectedShot = it
                            capturedHandle = null
                        },
                        onBackhandStyleSelected = {
                            backhandStyle = it
                            capturedHandle = null
                        },
                        onChangeHandedness = {
                            currentScreen = AppScreen.HANDEDNESS
                        }
                    )

                    CaptureControls(
                        hasCapture = capturedHandle != null,
                        onCapture = {
                            if (capturedHandle == null) {
                                tracker.captureNextFrame = true
                            } else {
                                capturedHandle = null
                            }
                        },
                        onContinue = {
                            tracker.resetSmoothing()
                            gripScore = GripScore()
                            currentScreen = AppScreen.HAND_PLACEMENT
                        }
                    )
                }

                AppScreen.HAND_PLACEMENT,
                AppScreen.FINAL_CHECK -> {
                    GripTargetOverlay(
                        target = currentTarget,
                        isLeftHanded = isLeftHanded,
                        showHandleOutline = true,
                        isLocked = gripScore.overall == 100,
                        onTargetsCalculated = {
                                knuckleTarget,
                                heelTarget,
                                screenSize ->

                            gripScore = computeGripScore(
                                hands = handState.hands,
                                isLeftHanded = isLeftHanded,
                                isTwoHandedBackhand =
                                    isTwoHandedBackhand,
                                knuckleTarget = knuckleTarget,
                                heelTarget = heelTarget,
                                screenSize = screenSize,
                                previous = gripScore
                            )
                        }
                    )

                    if (handState.isHandVisible) {
                        HandTrackingOverlay(
                            hands = handState.hands,
                            isLeftHanded = isLeftHanded,
                            isTwoHandedBackhand =
                                isTwoHandedBackhand,
                            gripScore = gripScore
                        )
                    }

                    if (currentScreen == AppScreen.HAND_PLACEMENT) {
                        GripSelectionHud(
                            selectedSport = selectedSport,
                            selectedShot = selectedShot,
                            backhandStyle = backhandStyle,
                            isLeftHanded = isLeftHanded,
                            currentTarget = currentTarget,
                            onSportSelected = {
                                selectedSport = it
                            },
                            onShotSelected = {
                                selectedShot = it
                            },
                            onBackhandStyleSelected = {
                                backhandStyle = it
                            },
                            onChangeHandedness = {
                                currentScreen = AppScreen.HANDEDNESS
                            }
                        )
                    } else {
                        FinalCheckHeader(
                            target = currentTarget,
                            isTwoHanded = isTwoHandedBackhand,
                            isLeftHanded = isLeftHanded
                        )
                    }

                    GripScorePanel(
                        score = gripScore,
                        handVisible = handState.isHandVisible,
                        target = currentTarget,
                        isTwoHanded = isTwoHandedBackhand,
                        isFinalCheck =
                            currentScreen == AppScreen.FINAL_CHECK,
                        onBack = {
                            currentScreen =
                                if (
                                    currentScreen ==
                                    AppScreen.FINAL_CHECK
                                ) {
                                    AppScreen.HAND_PLACEMENT
                                } else {
                                    AppScreen.HANDLE_CAPTURE
                                }
                        },
                        onContinue = {
                            if (
                                currentScreen ==
                                AppScreen.HAND_PLACEMENT
                            ) {
                                tracker.resetSmoothing()
                                verifiedFrames = 0
                                currentScreen =
                                    AppScreen.FINAL_CHECK
                            } else if (
                                gripScore.overall >= 90 &&
                                gripScore.hasRequiredHands
                            ) {
                                currentScreen = AppScreen.SUCCESS
                            }
                        }
                    )
                }

                AppScreen.SUCCESS -> {
                    SuccessView(
                        target = currentTarget,
                        sport = selectedSport,
                        shot = selectedShot,
                        backhandStyle = backhandStyle,
                        isLeftHanded = isLeftHanded,
                        onScanAgain = {
                            capturedHandle = null
                            gripScore = GripScore()
                            currentScreen = AppScreen.HANDLE_CAPTURE
                        },
                        onChangeHandedness = {
                            capturedHandle = null
                            gripScore = GripScore()
                            currentScreen = AppScreen.HANDEDNESS
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionView(
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1114)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Camera permission required",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "GripVision uses the camera to locate your hand and guide your grip.",
                color = Color.LightGray,
                textAlign = TextAlign.Center,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DOMINANT_HAND_COLOR,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = "GRANT CAMERA PERMISSION",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HandednessScreen(
    onLeftHanded: () -> Unit,
    onRightHanded: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .statusBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "GripVision AI",
                color = DOMINANT_HAND_COLOR,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Choose your dominant hand",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HandButton(
                    text = "LEFT HANDED",
                    modifier = Modifier.weight(1f),
                    onClick = onLeftHanded
                )

                HandButton(
                    text = "RIGHT HANDED",
                    modifier = Modifier.weight(1f),
                    onClick = onRightHanded
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "You can change this later.",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun HandButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF131D22)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun GripSelectionHud(
    selectedSport: GripSport,
    selectedShot: ShotPreset,
    backhandStyle: BackhandStyle,
    isLeftHanded: Boolean,
    currentTarget: GripTarget,
    onSportSelected: (GripSport) -> Unit,
    onShotSelected: (ShotPreset) -> Unit,
    onBackhandStyleSelected: (BackhandStyle) -> Unit,
    onChangeHandedness: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "GripVision AI",
                color = DOMINANT_HAND_COLOR,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .background(
                        Color(0xD910181C),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )

            FilterChip(
                selected = true,
                onClick = onChangeHandedness,
                label = {
                    Text(
                        text =
                            if (isLeftHanded) "LEFTY" else "RIGHTY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = DOMINANT_HAND_COLOR,
                    selectedLabelColor = Color.Black
                )
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GripSport.entries.forEach { sport ->
                FilterChip(
                    selected = selectedSport == sport,
                    onClick = {
                        onSportSelected(sport)
                    },
                    label = {
                        Text(
                            text = sport.label,
                            fontSize = 10.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xD9121A1E),
                        labelColor = Color.White,
                        selectedContainerColor =
                            DOMINANT_HAND_COLOR,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScrollSafe(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ShotPreset.entries.forEach { shot ->
                FilterChip(
                    selected = selectedShot == shot,
                    onClick = {
                        onShotSelected(shot)
                    },
                    label = {
                        Text(
                            text = shot.label,
                            fontSize = 10.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xD9121A1E),
                        labelColor = Color.White,
                        selectedContainerColor =
                            SUPPORT_HAND_COLOR,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        if (selectedShot == ShotPreset.BACKHAND) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BackhandStyle.entries.forEach { style ->
                    val selected = backhandStyle == style

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .background(
                                color =
                                    if (selected) {
                                        Color(0xFFFFD740)
                                    } else {
                                        Color(0xD9121A1E)
                                    },
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                onBackhandStyleSelected(style)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = style.label,
                            color =
                                if (selected) {
                                    Color.Black
                                } else {
                                    Color.White
                                },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "${currentTarget.gripName} • Knuckle Bevel ${currentTarget.targetBevel}",
            color = currentTarget.bevelColor,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier
                .background(
                    Color(0xD910181C),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun Modifier.horizontalScrollSafe(): Modifier {
    return this.then(
        Modifier.horizontalScroll(rememberScrollState())
    )
}

@Composable
private fun CaptureControls(
    hasCapture: Boolean,
    onCapture: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onCapture,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text =
                        if (hasCapture) "RETAKE" else "TAKE PICTURE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }

            if (hasCapture) {
                Button(
                    onClick = onContinue,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DOMINANT_HAND_COLOR,
                        contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = "PLACE HAND →",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text =
                if (hasCapture) {
                    "Picture saved. Place your hand naturally around the handle."
                } else {
                    "Place the racket butt cap inside the guide with Bevel 1 at the top."
                },
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color(0xDC111A1F),
                    RoundedCornerShape(16.dp)
                )
                .padding(12.dp)
        )
    }
}
@Composable
private fun GripTargetOverlay(
    target: GripTarget,
    isLeftHanded: Boolean,
    showHandleOutline: Boolean,
    isLocked: Boolean,
    onTargetsCalculated: (Offset, Offset, Size) -> Unit
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val centreX = size.width * 0.50f
        val centreY = size.height * 0.50f

        val halfWidth = size.width * 0.24f
        val halfHeight = size.height * 0.145f

        val cutX = halfWidth * 0.26f
        val cutY = halfHeight * 0.34f

        val knuckleTarget = buttCapBevelPosition(
            bevel = target.targetBevel,
            isLeftHanded = isLeftHanded,
            centerX = centreX,
            centerY = centreY,
            halfW = halfWidth,
            halfH = halfHeight
        )

        /*
         * Offset the heel target slightly towards the centre of the handle.
         * This prevents the heel and knuckle target markers from sitting
         * directly on top of one another when they use the same bevel.
         */
        val heelBevelEdge = buttCapBevelPosition(
            bevel = target.heelPadBevel,
            isLeftHanded = isLeftHanded,
            centerX = centreX,
            centerY = centreY,
            halfW = halfWidth,
            halfH = halfHeight
        )

        val heelTarget = Offset(
            x = heelBevelEdge.x * 0.78f + centreX * 0.22f,
            y = heelBevelEdge.y * 0.78f + centreY * 0.22f
        )

        onTargetsCalculated(
            knuckleTarget,
            heelTarget,
            size
        )

        if (showHandleOutline) {
            val outline = Path().apply {
                moveTo(
                    centreX - halfWidth + cutX,
                    centreY - halfHeight
                )

                lineTo(
                    centreX + halfWidth - cutX,
                    centreY - halfHeight
                )

                lineTo(
                    centreX + halfWidth,
                    centreY - halfHeight + cutY
                )

                lineTo(
                    centreX + halfWidth,
                    centreY + halfHeight - cutY
                )

                lineTo(
                    centreX + halfWidth - cutX,
                    centreY + halfHeight
                )

                lineTo(
                    centreX - halfWidth + cutX,
                    centreY + halfHeight
                )

                lineTo(
                    centreX - halfWidth,
                    centreY + halfHeight - cutY
                )

                lineTo(
                    centreX - halfWidth,
                    centreY - halfHeight + cutY
                )

                close()
            }

            drawPath(
                path = outline,
                color = Color.White.copy(alpha = 0.5f),
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        intervals = floatArrayOf(12f, 9f),
                        phase = 0f
                    )
                )
            )
        }

        val labelPaint = android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 10.sp.toPx()
            isFakeBoldText = true
            isAntiAlias = true
        }

        for (bevel in 1..8) {
            val position = buttCapBevelPosition(
                bevel = bevel,
                isLeftHanded = isLeftHanded,
                centerX = centreX,
                centerY = centreY,
                halfW = halfWidth,
                halfH = halfHeight
            )

            val colour = BEVEL_COLORS.getValue(bevel)
            val selected = bevel == target.targetBevel

            if (selected) {
                drawCircle(
                    color =
                        if (isLocked) {
                            DOMINANT_HAND_COLOR.copy(alpha = 0.30f)
                        } else {
                            colour.copy(alpha = 0.26f)
                        },
                    radius = 21.dp.toPx(),
                    center = position
                )
            }

            drawCircle(
                color =
                    if (selected) colour
                    else Color(0xDD11191D),
                radius =
                    if (selected) 12.dp.toPx()
                    else 9.dp.toPx(),
                center = position
            )

            drawCircle(
                color = colour,
                radius =
                    if (selected) 12.dp.toPx()
                    else 9.dp.toPx(),
                center = position,
                style = Stroke(
                    width = 1.5.dp.toPx()
                )
            )

            labelPaint.color =
                if (selected) {
                    android.graphics.Color.BLACK
                } else {
                    android.graphics.Color.WHITE
                }

            drawContext.canvas.nativeCanvas.drawText(
                bevel.toString(),
                position.x,
                position.y + 4.dp.toPx(),
                labelPaint
            )
        }

        drawCircle(
            color = HEEL_PAD_COLOR.copy(alpha = 0.20f),
            radius = 17.dp.toPx(),
            center = heelTarget
        )

        drawCircle(
            color = HEEL_PAD_COLOR,
            radius = 7.dp.toPx(),
            center = heelTarget,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun HandTrackingOverlay(
    hands: List<TrackedHand>,
    isLeftHanded: Boolean,
    isTwoHandedBackhand: Boolean,
    gripScore: GripScore
) {
    val anchorHand = selectAnchorHand(
        hands = hands,
        isLeftHanded = isLeftHanded
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        hands.forEach { hand ->
            val zone = calculateKnuckleZone(
                hand = hand,
                screenSize = size,
                isLeftHanded = isLeftHanded
            ) ?: return@forEach

            val isAnchor = hand === anchorHand

            if (isAnchor || !isTwoHandedBackhand) {
                val centreColour =
                    if (gripScore.overall == 100) {
                        DOMINANT_HAND_COLOR
                    } else {
                        Color.White
                    }

                drawLine(
                    color = Color.White.copy(alpha = 0.72f),
                    start = zone.inside,
                    end = zone.outside,
                    strokeWidth = 2.dp.toPx()
                )

                drawCircle(
                    color = centreColour.copy(alpha = 0.18f),
                    radius = 23.dp.toPx(),
                    center = zone.centre
                )

                drawCircle(
                    color = centreColour,
                    radius = 10.dp.toPx(),
                    center = zone.centre
                )

                drawCircle(
                    color = Color.Black,
                    radius = 4.dp.toPx(),
                    center = zone.centre
                )

                drawCircle(
                    color = KNUCKLE_INSIDE_COLOR,
                    radius = 7.dp.toPx(),
                    center = zone.inside
                )

                drawCircle(
                    color = Color.White,
                    radius = 7.dp.toPx(),
                    center = zone.inside,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                drawCircle(
                    color = KNUCKLE_OUTSIDE_COLOR,
                    radius = 7.dp.toPx(),
                    center = zone.outside
                )

                drawCircle(
                    color = Color.White,
                    radius = 7.dp.toPx(),
                    center = zone.outside,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                drawCircle(
                    color = HEEL_PAD_COLOR.copy(alpha = 0.22f),
                    radius = 19.dp.toPx(),
                    center = zone.heelPad
                )

                drawCircle(
                    color = HEEL_PAD_COLOR,
                    radius = 9.dp.toPx(),
                    center = zone.heelPad
                )

                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = zone.heelPad,
                    style = Stroke(width = 1.8.dp.toPx())
                )
            } else {
                drawCircle(
                    color = SUPPORT_HAND_COLOR.copy(alpha = 0.22f),
                    radius = 20.dp.toPx(),
                    center = zone.centre
                )

                drawCircle(
                    color = SUPPORT_HAND_COLOR,
                    radius = 9.dp.toPx(),
                    center = zone.centre
                )

                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = zone.centre,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun FinalCheckHeader(
    target: GripTarget,
    isTwoHanded: Boolean,
    isLeftHanded: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text =
                if (isTwoHanded) {
                    "FINAL CHECK • ${
                        if (isLeftHanded) "LEFT" else "RIGHT"
                    } ANCHOR + SUPPORT HAND"
                } else {
                    "FINAL CHECK • ${target.gripName.uppercase()}"
                },
            color = DOMINANT_HAND_COLOR,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(
                    Color(0xE6111A1F),
                    RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun GripScorePanel(
    score: GripScore,
    handVisible: Boolean,
    target: GripTarget,
    isTwoHanded: Boolean,
    isFinalCheck: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                contentPadding = PaddingValues(
                    horizontal = 14.dp,
                    vertical = 7.dp
                )
            ) {
                Text(
                    text = "← BACK",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onContinue,
                enabled =
                    if (isFinalCheck) {
                        score.overall >= 90 &&
                            score.hasRequiredHands
                    } else {
                        handVisible
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DOMINANT_HAND_COLOR,
                    contentColor = Color.Black,
                    disabledContainerColor = Color.DarkGray,
                    disabledContentColor = Color.LightGray
                ),
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 7.dp
                )
            ) {
                Text(
                    text =
                        if (isFinalCheck) {
                            "CONFIRM GRIP →"
                        } else {
                            "FINAL CHECK →"
                        },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(7.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xE8131D22)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GripMatchGauge(
                    score = score.overall,
                    ringColour =
                        if (score.overall == 100) {
                            DOMINANT_HAND_COLOR
                        } else {
                            target.bevelColor
                        }
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !handVisible ->
                                "Bring your hand into the camera"

                            isTwoHanded &&
                                !score.hasRequiredHands ->
                                "Add your supporting hand"

                            score.overall == 100 ->
                                "Grip position verified"

                            score.knuckle < score.heelPad ->
                                "Move the base knuckle towards Bevel ${target.targetBevel}"

                            else ->
                                "Adjust the heel pad towards the purple target"
                        },
                        color =
                            if (score.overall == 100) {
                                DOMINANT_HAND_COLOR
                            } else {
                                Color.White
                            },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(5.dp))

                    Text(
                        text = target.shortInstruction,
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Knuckle ${score.knuckle}%",
                            color = Color.White,
                            fontSize = 10.sp
                        )

                        Text(
                            text = "Heel ${score.heelPad}%",
                            color = HEEL_PAD_COLOR,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GripMatchGauge(
    score: Int,
    ringColour: Color
) {
    val animatedSweep by animateFloatAsState(
        targetValue =
            score.coerceIn(0, 100) / 100f * 360f,
        label = "gripScore"
    )

    Box(
        modifier = Modifier.size(66.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = ringColour.copy(alpha = 0.20f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 6.dp.toPx())
            )

            drawArc(
                color = ringColour,
                startAngle = -90f,
                sweepAngle = animatedSweep,
                useCenter = false,
                style = Stroke(
                    width = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )
        }

        Text(
            text = "$score%",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun SuccessView(
    target: GripTarget,
    sport: GripSport,
    shot: ShotPreset,
    backhandStyle: BackhandStyle,
    isLeftHanded: Boolean,
    onScanAgain: () -> Unit,
    onChangeHandedness: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1114))
            .statusBarsPadding()
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            GripMatchGauge(
                score = 100,
                ringColour = DOMINANT_HAND_COLOR
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "GRIP VERIFIED",
                color = DOMINANT_HAND_COLOR,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = buildString {
                    append(sport.label)
                    append(" • ")
                    append(shot.label)

                    if (shot == ShotPreset.BACKHAND) {
                        append(" • ")
                        append(backhandStyle.label)
                    }

                    append(" • ")
                    append(
                        if (isLeftHanded) {
                            "Left-handed"
                        } else {
                            "Right-handed"
                        }
                    )
                },
                color = Color.LightGray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF142027)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = target.gripName,
                        color = target.bevelColor,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Base knuckle: Bevel ${target.targetBevel} • Heel pad: Bevel ${target.heelPadBevel}",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = target.fullGripInfo,
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Benefits",
                        color = DOMINANT_HAND_COLOR,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    target.benefits.forEach { benefit ->
                        Text(
                            text = "• $benefit",
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onChangeHandedness
                ) {
                    Text(
                        text = "HANDEDNESS",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onScanAgain,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DOMINANT_HAND_COLOR,
                        contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = "CHECK ANOTHER GRIP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
