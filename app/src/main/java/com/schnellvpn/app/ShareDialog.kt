package com.schnellvpn.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private fun generateQr(text: String): Bitmap? = try {
    val size = 512
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
} catch (e: Exception) {
    null
}

/** دیالوگ اشتراک‌گذاری پروفایل: QR + کپی + ارسال با اپ‌های دیگر. */
@Composable
fun ShareDialog(colors: AppColors, title: String, text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val qr = remember(text) { generateQr(text) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .background(colors.surface, RoundedCornerShape(20.dp))
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .clickable(onClick = {})
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (qr != null) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR",
                    modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp))
                )
            } else {
                Text("ساخت QR ممکن نشد", color = colors.coral, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text,
                color = colors.textDim,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(colors, "کپی", onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("SchnellVPN", text))
                }, modifier = Modifier.weight(1f))
                PrimaryButton(colors, "اشتراک‌گذاری", onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "اشتراک‌گذاری"))
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}
