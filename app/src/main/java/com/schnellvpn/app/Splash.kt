package com.schnellvpn.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * صفحه‌ی اول: لوگوی اپ با انیمیشن وارد وسط صفحه می‌شود و یک حلقه دورش می‌چرخد.
 */
@Composable
fun SplashScreen(colors: AppColors) {
    val progress = remember { Animatable(0f) }
    val infinite = rememberInfiniteTransition()
    val ring by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing))
    )

    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = tween(1100, easing = FastOutSlowInEasing))
    }
    val p = progress.value

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(210.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            rotate(degrees = ring, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, colors.teal, Color.Transparent)
                    ),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                val s = 0.4f + 0.6f * p
                scaleX = s
                scaleY = s
                alpha = p
                translationY = (1f - p) * 220f
            }
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = "SchnellVPN",
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(26.dp))
            )
            Spacer(Modifier.height(18.dp))
            Text("SchnellVPN", color = colors.text, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("سریع. امن. بدون پیچیدگی.", color = colors.textDim, fontSize = 13.sp)
        }
    }
}
