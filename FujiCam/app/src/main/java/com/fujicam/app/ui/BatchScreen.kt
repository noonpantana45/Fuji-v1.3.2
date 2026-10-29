package com.fujicam.app.ui

import android.net.Uri
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.fujicam.app.filters.Aspect
import com.fujicam.app.filters.FilmLooks
import com.fujicam.app.filters.FilterEngine
import com.fujicam.app.filters.Recipe
import com.fujicam.app.util.BitmapIO
import com.fujicam.app.util.MediaSaver
import com.fujicam.app.util.Prefs
import com.fujicam.app.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** แต่งหลายรูปพร้อมกันด้วยสูตรเดียว แล้วบันทึกที่ความละเอียดเต็ม */
@Composable
fun BatchScreen(uris: List<Uri>, initial: Recipe?, onBack: () -> Unit) {
    val context = LocalContext.current
    val workScope = LocalLifecycleOwner.current.lifecycleScope
    val prefs = remember { Prefs(context) }
    val saved = remember { (1..5).map { prefs.recipe(it) } }

    // ตัวเลือก: 0 = การแต่งที่ส่งมาจากหน้าแต่งรูป, 1..5 = C1..C5
    val options = remember {
        buildList {
            if (initial != null) add(Triple(0, "การแต่งตอนนี้", initial))
            saved.forEachIndexed { i, r -> if (r != null) add(Triple(i + 1, "C${i + 1}", r)) }
        }
    }
    var choice by remember { mutableIntStateOf(options.firstOrNull()?.first ?: -1) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var failed by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    // "recipe" = ตามสูตร, "none" = คงสัดส่วนเดิม, อื่น ๆ = ชื่อ Aspect
    var aspectChoice by remember { mutableStateOf("recipe") }

    val leave = {
        job?.cancel()
        onBack()
    }
    BackHandler(onBack = leave)

    fun start() {
        val recipe = options.firstOrNull { it.first == choice }?.third ?: return
        running = true
        finished = false
        done = 0
        failed = 0
        val ratio = when (aspectChoice) {
            "recipe" -> recipe.aspect?.ratio ?: 0f
            "none" -> 0f
            else -> Aspect.entries.firstOrNull { it.name == aspectChoice }?.ratio ?: 0f
        }
        job = workScope.launch {
            val look = FilmLooks.byId(recipe.lookId)
            for (uri in uris) {
                val ok = withContext(Dispatchers.Default) {
                    ensureActive()
                    runCatching {
                        val bmp = BitmapIO.load(context, uri)
                        val time = BitmapIO.exifDate(context, uri) ?: System.currentTimeMillis()
                        val out = FilterEngine.render(
                            bmp, look, recipe.adj, recipe.intensity, recipe.dateStamp, time,
                            border = recipe.border, caption = FilterEngine.captionFor(look), aspect = ratio,
                        )
                        bmp.recycle()
                        val u = MediaSaver.saveBitmap(context, out, quality = 95, prefix = "FJC_BATCH")
                        out.recycle()
                        u != null
                    }.getOrDefault(false)
                }
                if (ok) done++ else failed++
            }
            running = false
            finished = true
            toast(context, "แต่งเสร็จ $done รูป" + if (failed > 0) " (ไม่สำเร็จ $failed)" else "")
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text("แต่งหลายรูปพร้อมกัน", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                "เลือกไว้ ${uris.size} รูป",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                items(uris) { u -> BatchThumb(u) }
            }

            Text(
                "ตั้งค่าการแต่ง",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            if (options.isEmpty()) {
                Text(
                    "ยังไม่มีสูตรที่บันทึกไว้ ไปที่หน้าแต่งรูป › แท็บ สูตรของฉัน › กด บันทึก ก่อน",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Text(
                "สัดส่วนภาพ",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                item { Pill("ตามสูตร", aspectChoice == "recipe") { if (!running) aspectChoice = "recipe" } }
                item { Pill("ต้นฉบับ", aspectChoice == "none") { if (!running) aspectChoice = "none" } }
                items(Aspect.entries) { a ->
                    Pill(if (a == Aspect.XPAN) "65:24 XPan" else a.label, aspectChoice == a.name) {
                        if (!running) aspectChoice = a.name
                    }
                }
            }
            Text(
                "เลือกสูตร",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
            )
            options.forEach { (id, name, r) ->
                val sel = id == choice
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Yellow.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f))
                        .border(1.dp, if (sel) Yellow else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(enabled = !running) { choice = id }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = sel, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Yellow))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(r.summary(), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (running || finished) {
                val total = uris.size
                LinearProgressIndicator(
                    progress = { (done + failed).toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = Yellow,
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
                Text(
                    if (finished) "เสร็จแล้ว บันทึก $done รูป" + (if (failed > 0) " ไม่สำเร็จ $failed รูป" else "") + " (Pictures/FujiCam)"
                    else "กำลังแต่ง ${done + failed + 1} / $total…",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            when {
                running -> OutlinedButton(onClick = {
                    job?.cancel()
                    running = false
                    finished = true
                }, modifier = Modifier.fillMaxWidth()) { Text("หยุด", color = Color.White) }
                finished -> Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("กลับไปหน้ากล้อง") }
                else -> Button(
                    onClick = { start() },
                    enabled = choice >= 0,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text("เริ่มแต่ง ${uris.size} รูป", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun BatchThumb(uri: Uri) {
    val context = LocalContext.current
    var img by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        img = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(160, 160), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Panel)) {
        img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
