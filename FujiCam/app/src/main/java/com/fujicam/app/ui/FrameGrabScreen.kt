package com.fujicam.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.fujicam.app.camera.FrameGrabber
import com.fujicam.app.util.MediaSaver
import com.fujicam.app.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun FrameGrabScreen(initialUri: Uri?, onBack: () -> Unit, onEdit: (Uri) -> Unit) {
    val context = LocalContext.current
    val workScope = LocalLifecycleOwner.current.lifecycleScope
    BackHandler(onBack = onBack)

    var videoUri by remember { mutableStateOf(initialUri) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) videoUri = it }
    val pickVideo = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    LaunchedEffect(Unit) { if (videoUri == null) pickVideo() }

    val grabber = remember(videoUri) { videoUri?.let { FrameGrabber(context.applicationContext, it) } }
    DisposableEffect(grabber) { onDispose { grabber?.close() } }

    var info by remember(grabber) { mutableStateOf<FrameGrabber.Info?>(null) }
    var openFailed by remember(grabber) { mutableStateOf(false) }
    var index by remember(grabber) { mutableIntStateOf(0) }
    var slider by remember(grabber) { mutableFloatStateOf(0f) }
    var frame by remember(grabber) { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var png by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var savedUri by remember(grabber) { mutableStateOf<Uri?>(null) }

    LaunchedEffect(grabber) {
        val g = grabber ?: return@LaunchedEffect
        val i = g.open()
        info = i
        openFailed = i == null
    }
    LaunchedEffect(info, index) {
        val g = grabber ?: return@LaunchedEffect
        if (info == null) return@LaunchedEffect
        delay(80) // รอให้หยุดเลื่อนก่อน ค่อยถอดรหัสเฟรม
        loading = true
        try {
            g.frameAt(index)?.let { frame = it }
        } finally {
            loading = false
        }
    }

    val total = info?.totalFrames ?: 1
    fun goTo(i: Int) {
        index = i.coerceIn(0, total - 1)
        slider = if (total > 1) index.toFloat() / (total - 1) else 0f
    }

    fun capture() {
        val bmp = frame ?: return
        saving = true
        workScope.launch {
            val u = withContext(Dispatchers.IO) {
                MediaSaver.saveBitmap(context, bmp, png = png, quality = 100, prefix = "FJC_FRAME")
            }
            saving = false
            savedUri = u
            toast(context, if (u != null) "แคปเฟรมแล้ว ${bmp.width}×${bmp.height}" else "บันทึกไม่สำเร็จ")
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text("แคปภาพจากวิดีโอ", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { pickVideo() }) { Text("เลือกวิดีโอ", color = Yellow) }
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            val f = frame
            when {
                videoUri == null -> Text("เลือกวิดีโอเพื่อเริ่ม", color = Color.White)
                openFailed -> Text("เปิดวิดีโอนี้ไม่ได้", color = Color.White)
                f != null -> Image(
                    f.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> CircularProgressIndicator(color = Yellow)
            }
            if (loading && f != null) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp),
                    color = Yellow,
                    strokeWidth = 2.dp,
                )
            }
        }

        val inf = info
        if (inf != null) {
            Text(
                String.format(
                    Locale.US, "เฟรม %d / %d   %s   %d×%d   %.0f fps",
                    index + 1, total, formatMs(inf.timeMsOf(index)), inf.width, inf.height, inf.fps,
                ),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                textAlign = TextAlign.Center,
            )
            Slider(
                value = slider,
                onValueChange = {
                    slider = it
                    index = (it * (total - 1)).roundToInt()
                },
                colors = yellowSlider(),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(-10 to "−10", -1 to "−1 เฟรม", 1 to "+1 เฟรม", 10 to "+10").forEach { (step, label) ->
                    OutlinedButton(
                        onClick = { goTo(index + step) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text(label, fontSize = 12.sp, color = Color.White) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = png, onClick = { png = true }, label = { Text("PNG ไม่บีบอัด") })
                FilterChip(selected = !png, onClick = { png = false }, label = { Text("JPEG 100%") })
            }
            Button(
                onClick = { capture() },
                enabled = frame != null && !saving,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
            ) {
                Text(if (saving) "กำลังบันทึก…" else "แคปเฟรมนี้ (ความละเอียดเต็ม)", fontWeight = FontWeight.Bold)
            }
            val su = savedUri
            if (su != null) {
                TextButton(onClick = { onEdit(su) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("ใส่ฟิลเตอร์ให้ภาพที่แคป", color = Yellow)
                }
            }
            Text(
                "ภาพที่แคปละเอียดเท่าวิดีโอต้นฉบับ อัดแบบ 4K จะได้ภาพราว 8 ล้านพิกเซล",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d.%03d", s / 60, s % 60, ms % 1000)
}
