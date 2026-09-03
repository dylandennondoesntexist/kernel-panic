package app.kernelpanic.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kernelpanic.detector.SessionPhase
import kotlin.math.sin

@Composable
fun PopcornMascot(phase: SessionPhase, modifier: Modifier = Modifier) {
    val motionAllowed = remember { ValueAnimator.areAnimatorsEnabled() }
    val energetic = phase in setOf(SessionPhase.RAMPING_UP, SessionPhase.ACTIVE, SessionPhase.DECLINING)
    val transition = rememberInfiniteTransition(label = "popcorn motion")
    val bounce = transition.animateFloat(
        initialValue = 0f,
        targetValue = if (motionAllowed && energetic) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (phase == SessionPhase.ACTIVE) 260 else 520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bounce",
    ).value
    val description = when (phase) {
        SessionPhase.DONE -> "Happy popcorn mascot"
        SessionPhase.WARNING -> "Concerned popcorn mascot"
        SessionPhase.CRITICAL -> "Panicked popcorn mascot"
        SessionPhase.ACTIVE -> "Bouncing popcorn mascot"
        else -> "Popcorn mascot"
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val scale = size.minDimension / 180f
        translate(left = (size.width - 180f * scale) / 2f, top = -bounce * 7f * scale) {
            rotate(if (energetic) sin(bounce * 6.28f) * 3f else 0f, pivot = Offset(90f * scale, 100f * scale)) {
                val outline = Color(0xFF60351F)
                val cream = Color(0xFFFFF2BB)
                val red = when (phase) {
                    SessionPhase.WARNING -> Color(0xFFF2B744)
                    SessionPhase.CRITICAL -> Color(0xFFD83A32)
                    else -> Color(0xFFE45545)
                }
                val p = Path().apply {
                    moveTo(47f * scale, 73f * scale)
                    cubicTo(25f * scale, 67f * scale, 25f * scale, 45f * scale, 44f * scale, 43f * scale)
                    cubicTo(39f * scale, 22f * scale, 68f * scale, 16f * scale, 78f * scale, 35f * scale)
                    cubicTo(91f * scale, 8f * scale, 127f * scale, 21f * scale, 124f * scale, 46f * scale)
                    cubicTo(149f * scale, 45f * scale, 156f * scale, 72f * scale, 134f * scale, 79f * scale)
                    close()
                }
                drawPath(p, cream)
                drawPath(p, outline, style = Stroke(4f * scale, cap = StrokeCap.Round))
                val bucket = Path().apply {
                    moveTo(45f * scale, 72f * scale)
                    lineTo(137f * scale, 72f * scale)
                    lineTo(126f * scale, 155f * scale)
                    quadraticTo(90f * scale, 166f * scale, 55f * scale, 155f * scale)
                    close()
                }
                drawPath(bucket, red)
                drawPath(bucket, outline, style = Stroke(4f * scale))
                drawRect(cream, topLeft = Offset(65f * scale, 76f * scale), size = Size(14f * scale, 78f * scale))
                drawRect(cream, topLeft = Offset(103f * scale, 76f * scale), size = Size(14f * scale, 78f * scale))
                val eyeY = 92f * scale
                drawCircle(outline, 4f * scale, Offset(76f * scale, eyeY))
                drawCircle(outline, 4f * scale, Offset(108f * scale, eyeY))
                when (phase) {
                    SessionPhase.CRITICAL -> {
                        drawLine(outline, Offset(68f * scale, 84f * scale), Offset(80f * scale, 88f * scale), 3f * scale)
                        drawLine(outline, Offset(112f * scale, 88f * scale), Offset(124f * scale, 84f * scale), 3f * scale)
                        drawCircle(outline, 8f * scale, Offset(92f * scale, 114f * scale), style = Stroke(3f * scale))
                    }
                    SessionPhase.WARNING -> drawArc(outline, 205f, 130f, false, topLeft = Offset(78f * scale, 105f * scale), size = Size(28f * scale, 24f * scale), style = Stroke(3f * scale))
                    else -> drawArc(outline, 20f, 140f, false, topLeft = Offset(77f * scale, 99f * scale), size = Size(30f * scale, 27f * scale), style = Stroke(3f * scale))
                }
            }
        }
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
    Canvas(modifier.semantics { contentDescription = "Recent detected pop rate graph" }) {
        if (values.size < 2) return@Canvas
        val visible = values.takeLast(120)
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
