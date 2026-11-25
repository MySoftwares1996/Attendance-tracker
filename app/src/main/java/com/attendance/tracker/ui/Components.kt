package com.attendance.tracker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** White rounded card with a very soft, tinted shadow. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    shape: Shape = Soft.cardShape,
    color: Color = Soft.colors.card,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Soft.colors
    var m = modifier
        .fillMaxWidth()
        .shadow(elevation = 18.dp, shape = shape, ambientColor = c.shadow, spotColor = c.shadow)
        .clip(shape)
        .background(color)
    if (onClick != null) m = m.pressScale(onClick)
    Column(m.padding(padding), content = content)
}

/** Gentle press-to-shrink feedback used on tappable surfaces. */
@Composable
fun Modifier.pressScale(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    val haptics = LocalHapticFeedback.current
    return this
        .scale(scale)
        .clickable(interactionSource = source, indication = null) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/** Large, pill-shaped primary button with a gradient fill. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: Pair<Color, Color> = Soft.colors.accent to Soft.colors.accent.copy(alpha = 0.85f),
    contentColor: Color = Color.White,
    icon: Painter? = null,
    enabled: Boolean = true,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "btnAlpha")
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(if (enabled) 14.dp else 0.dp, Soft.pillShape, ambientColor = colors.second.copy(alpha = 0.35f), spotColor = colors.second.copy(alpha = 0.35f))
            .clip(Soft.pillShape)
            .background(Brush.horizontalGradient(listOf(colors.first.copy(alpha = alpha), colors.second.copy(alpha = alpha))))
            .then(if (enabled) Modifier.pressScale(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Quiet secondary button: tinted background, no shadow. */
@Composable
fun SoftGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Soft.colors.accent,
    background: Color = Soft.colors.accentSoft,
    icon: Painter? = null,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(Soft.pillShape)
            .background(background.copy(alpha = if (enabled) 1f else 0.5f))
            .then(if (enabled) Modifier.pressScale(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = tint.copy(alpha = if (enabled) 1f else 0.5f), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Round tinted icon container. */
@Composable
fun IconBubble(icon: Painter, tint: Color, background: Color, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Soft.colors.textPrimary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Card header row: icon bubble + title + optional subtitle. */
@Composable
fun CardHeader(icon: Painter, title: String, subtitle: String? = null, tint: Color = Soft.colors.accent, bubble: Color = Soft.colors.accentSoft) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, tint, bubble)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Soft.colors.textPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Soft.colors.textSecondary)
        }
    }
}

/** Small stat tile used in grids. */
@Composable
fun StatTile(label: String, value: String, icon: Painter, tint: Color, bubble: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .shadow(12.dp, Soft.tileShape, ambientColor = Soft.colors.shadow, spotColor = Soft.colors.shadow)
            .clip(Soft.tileShape)
            .background(Soft.colors.card)
            .padding(16.dp)
    ) {
        IconBubble(icon, tint, bubble, size = 34.dp)
        Spacer(Modifier.height(12.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = Soft.colors.textPrimary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Soft.colors.textSecondary)
    }
}

@Composable
fun Pill(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(Soft.pillShape).background(background).padding(horizontal = 12.dp, vertical = 6.dp),
        color = color, style = MaterialTheme.typography.labelMedium,
    )
}

/** Key/value line with soft secondary label. */
@Composable
fun InfoLine(label: String, value: String, valueColor: Color = Soft.colors.textPrimary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Soft.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColor, textAlign = TextAlign.End)
    }
}

/** Animated circular progress ring. */
@Composable
fun ProgressRing(
    progress: Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val s = stroke.toPx()
            val d = size.minDimension - s
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * p, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Animated horizontal progress bar with rounded ends. */
@Composable
fun SoftBar(progress: Float, color: Color, track: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(Soft.pillShape).background(track)) {
        Box(Modifier.fillMaxWidth(p).height(height).clip(Soft.pillShape).background(color))
    }
}

/** Breathing dot used to show live tracking. */
@Composable
fun PulseDot(color: Color, active: Boolean, size: Dp = 10.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val halo by t.animateFloat(1f, 2.4f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "halo")
    val haloAlpha by t.animateFloat(0.45f, 0f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "haloA")
    Box(Modifier.size(size * 2.4f), contentAlignment = Alignment.Center) {
        if (active) Box(Modifier.size(size).scale(halo).clip(CircleShape).background(color.copy(alpha = haloAlpha)))
        Box(Modifier.size(size).clip(CircleShape).background(if (active) color else color.copy(alpha = 0.5f)))
    }
}

@Composable
fun SoftSwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Soft.colors.textPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Soft.colors.textSecondary)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Soft.colors.accent,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = Soft.colors.hairline,
                uncheckedBorderColor = Color.Transparent,
            )
        )
    }
}

/** Tinted notice with an action, e.g. permissions. */
@Composable
fun NoticeCard(icon: Painter, text: String, action: String, tint: Color, background: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Soft.tileShape).background(background).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Soft.colors.textPrimary)
        Spacer(Modifier.width(10.dp))
        Text(
            action,
            Modifier.clip(Soft.pillShape).background(tint).pressScale(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
            color = Color.White, style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Animated colour helper so status colours cross-fade smoothly. */
@Composable
fun animatedColor(target: Color, label: String): Color =
    animateColorAsState(target, tween(700, easing = FastOutSlowInEasing), label = label).value
