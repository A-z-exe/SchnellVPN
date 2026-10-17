package com.schnellvpn.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** راهنمای کامل اتصال دستگاه‌های دیگر به پروکسی VPN روی هات‌اسپات. */
@Composable
fun HotspotInfoDialog(
    colors: AppColors,
    ips: List<String>,
    port: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val shownIps = if (ips.isEmpty()) listOf("192.168.43.1") else ips

    fun copy(value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("SchnellVPN", value))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .background(colors.surface, RoundedCornerShape(20.dp))
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .clickable(onClick = {})
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            Text("راهنمای اشتراک VPN روی هات‌اسپات", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "این روش بدون روت کار می‌کند. دستگاه‌های متصل باید یک‌بار پروکسی را دستی تنظیم کنند.",
                color = colors.textDim, fontSize = 11.5.sp
            )
            Spacer(Modifier.height(12.dp))

            StepText(colors, "۱", "VPN را وصل کن و گزینه «اشتراک VPN روی هات‌اسپات» را روشن بگذار.")
            StepText(colors, "۲", "هات‌اسپات گوشی را روشن کن و دستگاه دیگر را به آن وصل کن.")
            StepText(colors, "۳", "در دستگاه دیگر، تنظیمات همان شبکه‌ی وای‌فای را باز کن و Proxy را روی «Manual / دستی» بگذار.")
            StepText(colors, "۴", "آی‌پی و پورت زیر را وارد کن:")

            Spacer(Modifier.height(6.dp))
            shownIps.forEach { ip ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("$ip:$port", color = colors.amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "کپی",
                        color = colors.teal, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { copy("$ip:$port") }.padding(6.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("راهنمای سیستم‌ها:", color = colors.textDim, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("• اندروید: نگه‌داشتن شبکه → Modify → Advanced → Proxy: Manual", color = colors.textDim, fontSize = 11.sp)
            Text("• آیفون: Settings → Wi‑Fi → (i) → Configure Proxy → Manual", color = colors.textDim, fontSize = 11.sp)
            Text("• ویندوز: Settings → Network & Internet → Proxy → Manual setup", color = colors.textDim, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "اگر IP نمایش‌داده‌شده با دروازه‌ی هات‌اسپات فرق داشت، همان IP دروازه (معمولاً 192.168.43.1) را وارد کن.",
                color = colors.textDim, fontSize = 10.5.sp
            )

            Spacer(Modifier.height(14.dp))
            PrimaryButton(colors, "فهمیدم", onClick = onDismiss)
        }
    }
}

@Composable
private fun StepText(colors: AppColors, number: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("$number) ", color = colors.amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(text, color = colors.text, fontSize = 12.sp)
    }
}
