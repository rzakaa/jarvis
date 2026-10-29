package pl.jarvis.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val HudShape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)

/** Tło: ciemny granat z poświatą u góry i delikatną siatką. */
@Composable
fun HudBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Hud.Background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(Hud.BackgroundGlow, Color.Transparent),
                        center = Offset(size.width / 2, size.height * 0.22f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
                val step = 28.dp.toPx()
                val line = Hud.Cyan.copy(alpha = 0.035f)
                var x = 0f
                while (x < size.width) {
                    drawLine(line, Offset(x, 0f), Offset(x, size.height))
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y))
                    y += step
                }
            },
        content = content,
    )
}

/** Panel ze ściętymi rogami i świecącą ramką. */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    accent: Color = Hud.Cyan,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(HudShape)
            .background(Hud.Panel)
            .border(1.dp, accent.copy(alpha = 0.45f), HudShape)
            .padding(16.dp),
    ) {
        if (title != null) {
            HudLabel(title, color = accent)
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

/** Mały napis w stylu HUD: wersaliki, czcionka maszynowa, rozstrzelone litery. */
@Composable
fun HudLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Hud.CyanDim,
    size: TextUnit = 11.sp,
    align: TextAlign? = null,
) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = color,
        fontFamily = Hud.Mono,
        fontSize = size,
        letterSpacing = 2.sp,
        textAlign = align,
    )
}

/** Przycisk ze ściętymi rogami. */
@Composable
fun HudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Hud.Cyan,
    filled: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(HudShape)
            .background(if (filled) accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, accent.copy(alpha = if (filled) 0.9f else 0.6f), HudShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = accent,
            fontFamily = Hud.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 2.sp,
        )
    }
}

/** Przełącznik-chip do wyboru opcji. */
@Composable
fun HudChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(HudShape)
            .background(if (selected) Hud.Cyan.copy(alpha = 0.2f) else Color.Transparent)
            .border(1.dp, if (selected) Hud.Cyan else Hud.CyanDim.copy(alpha = 0.6f), HudShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text.uppercase(),
            color = if (selected) Hud.CyanBright else Hud.TextDim,
            fontFamily = Hud.Mono,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
        )
    }
}

enum class ReactorMode { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Animowany „reaktor” Jarvisa: obracające się pierścienie, jądro pulsujące w rytm głosu.
 * [level] 0..1 – głośność (mówiącego albo otoczenia).
 */
@Composable
fun ArcReactor(level: Float, mode: ReactorMode, size: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "reactor")
    val fast = mode == ReactorMode.LISTENING || mode == ReactorMode.THINKING
    val outerRotation by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(if (fast) 4000 else 14000, easing = LinearEasing)), label = "outer",
    )
    val innerRotation by transition.animateFloat(
        360f, 0f, infiniteRepeatable(tween(if (fast) 2500 else 8000, easing = LinearEasing)), label = "inner",
    )
    val breathe by transition.animateFloat(
        0.82f, 1f, infiniteRepeatable(tween(if (mode == ReactorMode.SPEAKING) 260 else 1800), RepeatMode.Reverse),
        label = "breathe",
    )
    val smoothLevel by animateFloatAsState(level.coerceIn(0f, 1f), tween(120), label = "level")
    val color = when (mode) {
        ReactorMode.IDLE -> Hud.Cyan
        ReactorMode.LISTENING -> Hud.CyanBright
        ReactorMode.THINKING -> Hud.Amber
        ReactorMode.SPEAKING -> Hud.CyanBright
    }
    val energy = if (mode == ReactorMode.SPEAKING) 0.5f + 0.5f * breathe else smoothLevel

    Canvas(modifier = modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.18f + 0.3f * energy), Color.Transparent), c, r),
            r, c,
        )
        drawCircle(color.copy(alpha = 0.35f), r * 0.95f, c, style = Stroke(1.dp.toPx()))
        rotate(outerRotation, c) {
            drawCircle(
                color.copy(alpha = 0.75f), r * 0.86f, c,
                style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 16f))),
            )
        }
        rotate(innerRotation, c) {
            val arcR = r * 0.7f
            for (i in 0 until 3) {
                drawArc(
                    color = color,
                    startAngle = i * 120f,
                    sweepAngle = 75f,
                    useCenter = false,
                    topLeft = Offset(c.x - arcR, c.y - arcR),
                    size = Size(arcR * 2, arcR * 2),
                    style = Stroke(6.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        drawCircle(color.copy(alpha = 0.55f), r * 0.52f, c, style = Stroke(2.dp.toPx()))
        drawCircle(color.copy(alpha = 0.3f + 0.6f * energy), r * (0.38f + 0.1f * energy), c, style = Stroke(3.dp.toPx()))
        val core = r * 0.3f * (0.85f + 0.25f * energy) * (if (mode == ReactorMode.IDLE) breathe else 1f)
        drawCircle(
            Brush.radialGradient(listOf(Color.White, color, color.copy(alpha = 0f)), c, core),
            core, c,
        )
    }
}
