package com.schnellvpn.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * Gradient background with soft, slowly drifting colour blobs — the "light" that the
 * translucent glass panels sit on. When [enabled] is false it is just an empty Box.
 */
@Composable
fun GlassBackground(isDark: Boolean, enabled: Boolean, content: @Composable BoxScope.() -> Unit) {
    if (!enabled) {
        Box(Modifier.fillMaxSize(), content = content)
        return
    }

    val transition = rememberInfiniteTransition()
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse)
    )

    val base = if (isDark) {
        listOf(Color(0xFF0B1226), Color(0xFF141B3D), Color(0xFF0D2233))
    } else {
        listOf(Color(0xFFEAF1FF), Color(0xFFF1E9FF), Color(0xFFE3F7F3))
    }
    val blobs = if (isDark) {
        listOf(Color(0xFF6C5CE7), Color(0xFF00C2B8), Color(0xFFF5A623))
    } else {
        listOf(Color(0xFF9DB4FF), Color(0xFFB6F0E4), Color(0xFFFFD7A8))
    }
    val strength = if (isDark) 0.55f else 0.70f

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(base))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = maxOf(w, h) * 0.6f
            val d = drift
            drawBlob(blobs[0], Offset(w * (0.15f + 0.25f * d), h * (0.12f + 0.10f * d)), r, strength)
            drawBlob(blobs[1], Offset(w * (0.90f - 0.30f * d), h * (0.45f + 0.10f * d)), r * 0.9f, strength)
            drawBlob(blobs[2], Offset(w * (0.30f + 0.20f * d), h * (0.95f - 0.15f * d)), r * 0.8f, strength)
        }
        content()
    }
}

private fun DrawScope.drawBlob(color: Color, center: Offset, radius: Float, strength: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = strength), Color.Transparent),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/**
 * Card surface. With [glass] on: translucent white gradient + bright edge (frosted-glass look).
 * Off: the original solid surface + border.
 */
fun Modifier.panel(
    colors: AppColors,
    glass: Boolean,
    isDark: Boolean,
    shape: Shape,
    solid: Color = colors.surface
): Modifier {
    return if (glass) {
        val top = if (isDark) 0.16f else 0.60f
        val bottom = if (isDark) 0.05f else 0.30f
        this
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom))),
                shape
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = if (isDark) 0.45f else 0.95f), Color.White.copy(alpha = 0.08f))
                ),
                shape
            )
    } else {
        this
            .clip(shape)
            .background(solid, shape)
            .border(1.dp, colors.border, shape)
    }
}
