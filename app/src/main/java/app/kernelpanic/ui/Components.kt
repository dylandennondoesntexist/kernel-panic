package app.kernelpanic.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kernelpanic.R
import app.kernelpanic.detector.SessionPhase
import kotlin.math.sin

@Composable
fun PopcornMascot(phase: SessionPhase, modifier: Modifier = Modifier) {
    val motionAllowed = remember { ValueAnimator.areAnimatorsEnabled() }
    val energetic = phase in setOf(SessionPhase.RAMPING_UP, SessionPhase.ACTIVE, SessionPhase.DECLINING)
    val panicking = phase == SessionPhase.CRITICAL
    val animated = energetic || panicking
    val transition = rememberInfiniteTransition(label = "popcorn motion")
    val bounce = transition.animateFloat(
        initialValue = 0f,
        targetValue = if (motionAllowed && animated) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (panicking) 170 else if (phase == SessionPhase.ACTIVE) 260 else 520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bounce",
    ).value
    val description = when (phase) {
        SessionPhase.DONE -> "Excited popcorn mascot"
        SessionPhase.WARNING -> "Sweating popcorn mascot"
        SessionPhase.CRITICAL -> "Panicked popcorn mascot"
        SessionPhase.ACTIVE -> "Bouncing popcorn mascot"
        else -> "Popcorn mascot"
    }
    val face = when (phase) {
        SessionPhase.RAMPING_UP, SessionPhase.ACTIVE -> R.drawable.popcorn_face_popping
        SessionPhase.DECLINING, SessionPhase.DONE -> R.drawable.popcorn_face_done
        SessionPhase.WARNING -> R.drawable.popcorn_face_warning
        SessionPhase.CRITICAL -> R.drawable.popcorn_face_panicked
        else -> R.drawable.popcorn_face_heating
    }
    val rotation = when {
        panicking -> sin(bounce * 6.28f) * 5f
        energetic -> sin(bounce * 6.28f) * 3f
        else -> 0f
    }
    Box(
        modifier
            .graphicsLayer {
                translationY = if (energetic) -bounce * 7.dp.toPx() else 0f
                rotationZ = rotation
            }
            .semantics { contentDescription = description },
    ) {
        Image(painterResource(R.drawable.popcorn_mascot_base), contentDescription = null, modifier = Modifier.fillMaxSize())
        Image(painterResource(face), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun AudioBars(level: Float, modifier: Modifier = Modifier) {
    val levels = remember { mutableStateListOf<Float>().apply { repeat(28) { add(0.04f) } } }
    LaunchedEffect(level) {
        levels.removeAt(0)
        levels.add(level.coerceIn(0.02f, 1f))
    }
    Canvas(modifier.semantics { contentDescription = "Live microphone level" }) {
        val gap = 4.dp.toPx()
        val width = (size.width - gap * (levels.size - 1)) / levels.size
        levels.forEachIndexed { index, value ->
            val h = (size.height * (0.12f + value * 0.88f)).coerceAtMost(size.height)
            drawRoundRect(
                color = Color(0xFFF0A53A).copy(alpha = 0.45f + value * 0.55f),
                topLeft = Offset(index * (width + gap), (size.height - h) / 2f),
                size = Size(width, h),
            )
        }
    }
}

@Composable
fun RateGraph(values: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "Cumulative estimated pop count graph" }) {
        if (values.size < 2) return@Canvas
        // Keep the whole (maximum five-minute) session visible so the line clearly
        // builds from the first estimated pop instead of behaving like a sliding rate plot.
        val visible = values
        val maxValue = visible.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val path = Path()
        visible.forEachIndexed { index, value ->
            val x = index.toFloat() / (visible.size - 1) * size.width
            val y = size.height - (value / maxValue) * size.height * 0.9f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFFE99A2B), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

fun Modifier.standardGraphSize(): Modifier = fillMaxWidth().height(72.dp)
