package com.fujicam.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.filled.Share
import com.fujicam.app.filters.Adjustments
import com.fujicam.app.filters.Aspect
import com.fujicam.app.filters.Border
import com.fujicam.app.filters.FilmLooks
import com.fujicam.app.filters.FilterEngine
import com.fujicam.app.filters.Recipe
import com.fujicam.app.util.BitmapIO
import com.fujicam.app.util.MediaSaver
import com.fujicam.app.util.Prefs
import com.fujicam.app.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AdjKey(val label: String, val signed: Boolean) {
    BRIGHTNESS("ความสว่าง", true),
    CONTRAST("คอนทราสต์", true),
    SATURATION("ความอิ่มสี", true),
    WARMTH("อุณหภูมิสี", true),
    TINT("เขียว-ม่วง", true),
    FADE("ซีดจาง", false),
}

private fun Adjustments.get(k: AdjKey): Float = when (k) {
    AdjKey.BRIGHTNESS -> brightness
    AdjKey.CONTRAST -> contrast
    AdjKey.SATURATION -> saturation
    AdjKey.WARMTH -> warmth
    AdjKey.TINT -> tint
    AdjKey.FADE -> fade
}

private fun Adjustments.with(k: AdjKey, v: Float): Adjustments = when (k) {
    AdjKey.BRIGHTNESS -> copy(brightness = v)
    AdjKey.CONTRAST -> copy(contrast = v)
    AdjKey.SATURATION -> copy(saturation = v)
    AdjKey.WARMTH -> copy(warmth = v)
    AdjKey.TINT -> copy(tint = v)
    AdjKey.FADE -> copy(fade = v)
}

@Composable
fun EditorScreen(uri: Uri, initialLookId: String, onBack: () -> Unit, onBatch: (Recipe) -> Unit) {
    val context = LocalContext.current
    val workScope = LocalLifecycleOwner.current.lifecycleScope
    BackHandler(onBack = onBack)

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var stampTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var rotation by remember { mutableIntStateOf(0) }
    var lookId by remember { mutableStateOf(initialLookId) }
    var intensity by remember { mutableFloatStateOf(1f) }
    var adj by remember { mutableStateOf(Adjustments()) }
    var stamp by remember { mutableStateOf(false) }
    var border by remember { mutableStateOf(Border.NONE) }
    var aspect by remember { mutableStateOf<Aspect?>(null) } // null = คงสัดส่วนเดิม
    var tab by remember { mutableIntStateOf(0) }
    var adjKey by remember { mutableStateOf(AdjKey.BRIGHTNESS) }
    var rendered by remember { mutableStateOf<ImageBitmap?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val prefs = remember { Prefs(context) }
    var recipes by remember { mutableStateOf((1..5).map { prefs.recipe(it) }) }

    LaunchedEffect(uri) {
        try {
            val (bmp, date) = withContext(Dispatchers.IO) {
                BitmapIO.load(context, uri, maxDim = 1800) to BitmapIO.exifDate(context, uri)
            }
            source = bmp
            if (date != null) stampTime = date
        } catch (e: Exception) {
            loadError = true
        }
    }

    val look = remember(lookId) { FilmLooks.byId(lookId) }
    val base = remember(source, rotation) { source?.let { BitmapIO.transform(it, rotation, false, recycleSource = false) } }
    val baseImage = remember(base) { base?.asImageBitmap() }
    val thumb = remember(source) { source?.let { BitmapIO.scaleToMax(it, 160).asImageBitmap() } }

    // พรีวิวใช้ตัวเรนเดอร์เดียวกับไฟล์จริง จึงเห็นผลตรงกับที่บันทึก
    LaunchedEffect(base, lookId, intensity, adj, stamp, stampTime, border, aspect) {
        val b = base ?: return@LaunchedEffect
        delay(30)
        val out = withContext(Dispatchers.Default) {
            FilterEngine.render(
                b, look, adj, intensity, stamp, stampTime,
                border = border, caption = FilterEngine.captionFor(look), aspect = aspect?.ratio ?: 0f,
            )
        }
        rendered = out.asImageBitmap()
    }

    fun currentRecipe() = Recipe(lookId, intensity, adj, stamp, border, aspect)

    fun shareRecipe(r: Recipe) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.toShareText())
        context.startActivity(Intent.createChooser(send, "แชร์สูตร"))
    }

    fun applyRecipe(r: Recipe) {
        aspect = r.aspect
        lookId = r.lookId
        intensity = r.intensity
        adj = r.adj
        stamp = r.dateStamp
        border = r.border
    }

    fun save() {
        if (saving) return
        saving = true
        val snapLook = look
        val snapAdj = adj
        val snapIntensity = intensity
        val snapStamp = stamp
        val snapRotation = rotation
        val snapTime = stampTime
        val snapBorder = border
        val snapAspect = aspect?.ratio ?: 0f
        workScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val full = BitmapIO.load(context, uri) // ความละเอียดเต็ม
                    val rot = BitmapIO.transform(full, snapRotation, false, recycleSource = true)
                    val out = FilterEngine.render(
                        rot, snapLook, snapAdj, snapIntensity, snapStamp, snapTime,
                        border = snapBorder, caption = FilterEngine.captionFor(snapLook), aspect = snapAspect,
                    )
                    rot.recycle()
                    val u = MediaSaver.saveBitmap(context, out, quality = 95, prefix = "FJC_EDIT")
                    val size = "${out.width}×${out.height}"
                    out.recycle()
                    if (u != null) size else null
                }.getOrNull()
            }
            saving = false
            toast(context, if (result != null) "บันทึกรูปแล้ว ($result)" else "บันทึกรูปไม่สำเร็จ")
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                Text("แต่งรูป", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                    Icon(Icons.Filled.CropRotate, null, tint = Color.White)
                }
                TextButton(onClick = { save() }, enabled = base != null && !saving) {
                    Text("บันทึก", color = Yellow, fontWeight = FontWeight.Bold)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                val shown = if (showOriginal) baseImage else (rendered ?: baseImage)
                when {
                    loadError -> Text("เปิดรูปนี้ไม่ได้ ลองเลือกรูปอื่น", color = Color.White)
                    shown == null -> CircularProgressIndicator(color = Yellow)
                    else -> {
                        Image(
                            shown,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                                // แตะค้างเพื่อดูต้นฉบับ
                                detectTapGestures(onPress = {
                                    showOriginal = true
                                    tryAwaitRelease()
                                    showOriginal = false
                                })
                            },
                        )
                        if (showOriginal) {
                            Text(
                                "ต้นฉบับ",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.TopCenter)
                                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            Text(
                "แตะค้างที่รูปเพื่อเทียบกับต้นฉบับ",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                listOf("ฟิลเตอร์", "ปรับแสง/สี", "เอฟเฟกต์", "สูตรของฉัน").forEachIndexed { i, label ->
                    Text(
                        label,
                        color = if (tab == i) Yellow else Color.White,
                        fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { tab = i }.padding(6.dp),
                    )
                }
            }

            Column(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(bottom = 12.dp)) {
                when (tab) {
                    0 -> {
                        if (!look.isOriginal) LabeledSlider("ความเข้ม", intensity, { intensity = it })
                        FilterStrip(thumb = thumb, selectedId = lookId, onSelect = { lookId = it.id })
                    }

                    1 -> {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            items(AdjKey.entries) { k ->
                                val sel = k == adjKey
                                val changed = adj.get(k) != 0f
                                Text(
                                    k.label + if (changed) " •" else "",
                                    color = if (sel) Color.Black else Color.White,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(50))
                                        .background(if (sel) Yellow else Color.White.copy(alpha = 0.12f))
                                        .clickable { adjKey = k }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        LabeledSlider(
                            adjKey.label,
                            adj.get(adjKey),
                            { adj = adj.with(adjKey, it) },
                            range = if (adjKey.signed) -1f..1f else 0f..1f,
                            showSigned = adjKey.signed,
                        )
                        TextButton(
                            onClick = { adj = adj.copy(brightness = 0f, contrast = 0f, saturation = 0f, warmth = 0f, tint = 0f, fade = 0f) },
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("รีเซ็ตทั้งหมด", color = Color.White.copy(alpha = 0.7f)) }
                    }

                    2 -> {
                        LabeledSlider("เกรนฟิล์ม", adj.grain, { adj = adj.copy(grain = it) })
                        LabeledSlider("ขอบมืด", adj.vignette, { adj = adj.copy(vignette = it) })
                        LabeledSlider("ฟุ้งแดง", adj.halation, { adj = adj.copy(halation = it) })
                        LabeledSlider("ฟุ้งขาว", adj.bloom, { adj = adj.copy(bloom = it) })
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("วันที่สีส้มแบบกล้องฟิล์ม", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = stamp,
                                onCheckedChange = { stamp = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = Orange),
                            )
                        }
                        Text(
                            "ขอบภาพ",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp),
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            items(Border.entries) { b -> Pill(b.label, b == border) { border = b } }
                        }
                        Text(
                            "สัดส่วนภาพ",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp),
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            item { Pill("ต้นฉบับ", aspect == null) { aspect = null } }
                            items(Aspect.entries) { a ->
                                Pill(if (a == Aspect.XPAN) "65:24 XPan" else a.label, a == aspect) { aspect = a }
                            }
                        }
                    }

                    else -> {
                        Text(
                            "แตะ ใช้ เพื่อแต่งด้วยสูตรนั้นทันที หรือ บันทึก เพื่อเก็บการแต่งตอนนี้ไว้",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                        recipes.forEachIndexed { i, r ->
                            RecipeRow(
                                slot = i + 1,
                                recipe = r,
                                onUse = { if (r != null) applyRecipe(r) },
                                onShare = { if (r != null) shareRecipe(r) },
                                onSave = {
                                    val cur = currentRecipe()
                                    prefs.saveRecipe(i + 1, cur)
                                    recipes = recipes.toMutableList().also { it[i] = cur }
                                },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
                                val r = Recipe.fromShareText(text)
                                if (r != null) {
                                    applyRecipe(r)
                                    toast(context, "นำเข้าสูตรแล้ว กด บันทึก ที่ช่องที่ต้องการเพื่อเก็บไว้")
                                } else {
                                    toast(context, "ไม่พบโค้ดสูตร คัดลอกข้อความที่มี ${Recipe.CODE_PREFIX} มาก่อน")
                                }
                            }, modifier = Modifier.weight(1f)) { Text("วางโค้ดสูตร", color = Color.White) }
                            OutlinedButton(
                                onClick = { shareRecipe(currentRecipe()) },
                                modifier = Modifier.weight(1f),
                            ) { Text("แชร์การแต่งนี้", color = Color.White) }
                        }
                        OutlinedButton(
                            onClick = { onBatch(currentRecipe()) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        ) { Text("แต่งหลายรูปพร้อมกันด้วยการแต่งตอนนี้", color = Color.White) }
                    }
                }
            }
        }

        if (saving) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Yellow)
                    Spacer(Modifier.height(12.dp))
                    Text("กำลังบันทึกที่ความละเอียดเต็ม…", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RecipeRow(slot: Int, recipe: Recipe?, onUse: () -> Unit, onShare: () -> Unit, onSave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "C$slot",
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .background(if (recipe != null) Yellow else Color.White.copy(alpha = 0.3f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Text(
            recipe?.summary() ?: "ว่าง",
            color = Color.White.copy(alpha = if (recipe != null) 0.9f else 0.45f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        TextButton(onClick = onUse, enabled = recipe != null) { Text("ใช้", color = if (recipe != null) Yellow else Color.Gray) }
        TextButton(onClick = onSave) { Text("บันทึก", color = Color.White) }
        IconButton(onClick = onShare, enabled = recipe != null) {
            Icon(
                Icons.Filled.Share,
                contentDescription = "แชร์สูตร C$slot",
                tint = if (recipe != null) Color.White else Color.Gray,
            )
        }
    }
}
