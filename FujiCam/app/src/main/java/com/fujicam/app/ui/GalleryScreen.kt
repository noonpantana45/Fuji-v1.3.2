package com.fujicam.app.ui

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fujicam.app.filters.FilmRolls
import com.fujicam.app.util.AppMedia
import com.fujicam.app.util.BitmapIO
import com.fujicam.app.util.Prefs
import com.fujicam.app.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class GalleryTab(val label: String) {
    ALL("ทั้งหมด"),
    FILM("สมุดฟิล์ม"),
    VIDEO("วิดีโอ"),
    ORIGINAL("ต้นฉบับ"),
}

/** อัลบั้มรูปของแอพ: รูป/วิดีโอใน Pictures/FujiCam และ Movies/FujiCam */
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    onEdit: (Uri) -> Unit,
    onFrameGrab: (Uri) -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var all by remember { mutableStateOf<List<AppMedia.Item>?>(null) }
    var tab by rememberSaveable { mutableStateOf(GalleryTab.ALL) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        all = withContext(Dispatchers.IO) {
            val own = AppMedia.query(context)
            val names = own.map { it.name }.toHashSet()
            val old = AppMedia.imported(context, prefs.importedMedia).filter { it.name !in names }
            (own + old).sortedWith(compareByDescending<AppMedia.Item> { it.added }.thenByDescending { it.name })
        }
    }

    // ลงแอพใหม่แล้วรูปเก่าไม่ขึ้น: ให้ผู้ใช้เลือกกลับเข้ามาเองผ่านตัวเลือกรูปของระบบ (ไม่ต้องขอสิทธิ์อ่านทั้งเครื่อง)
    val oldPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val set = prefs.importedMedia.toMutableSet()
        uris.forEach { u ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            set += u.toString()
        }
        prefs.importedMedia = set
        reloadKey++
    }
    val addOld = {
        oldPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    val items = remember(all, tab) {
        val list = all ?: emptyList()
        when (tab) {
            GalleryTab.ALL -> list.filter { !it.isOriginal }
            GalleryTab.FILM -> list.filter { it.isFilm && !it.isOriginal }
            GalleryTab.VIDEO -> list.filter { it.isVideo }
            GalleryTab.ORIGINAL -> list.filter { it.isOriginal }
        }
    }

    // ลบไฟล์: Android 11+ ระบบจะถามยืนยันก่อนเสมอ
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            toast(context, "ลบแล้ว")
            viewing = null
            reloadKey++
        }
    }
    fun delete(item: AppMedia.Item) {
        val resolver = context.contentResolver
        if (item.imported) {
            prefs.importedMedia = prefs.importedMedia - item.uri.toString()
            runCatching { resolver.releasePersistableUriPermission(item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewing = null
            reloadKey++
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val req = MediaStore.createDeleteRequest(resolver, listOf(item.uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(req.intentSender).build())
            } else {
                resolver.delete(item.uri, null, null)
                toast(context, "ลบแล้ว")
                viewing = null
                reloadKey++
            }
        } catch (e: RecoverableSecurityException) {
            deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
        } catch (e: Exception) {
            toast(context, "ลบไม่ได้: ${e.message}")
        }
    }

    BackHandler {
        if (viewing != null) viewing = null else onBack()
    }

    val v = viewing
    if (v != null && v in items.indices) {
        Viewer(
            items = items,
            start = v,
            onClose = { viewing = null },
            onEdit = onEdit,
            onFrameGrab = onFrameGrab,
            onDelete = { delete(it) },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text("อัลบั้ม", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = addOld) {
                Icon(Icons.Filled.AddPhotoAlternate, "เพิ่มรูปเก่าเข้าอัลบั้ม", tint = Color.White)
            }
            TextButton(onClick = onImport) { Text("แต่งรูป", color = Yellow) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GalleryTab.entries.forEach { t -> Pill(t.label, t == tab) { tab = t } }
        }

        when {
            all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Yellow)
            }

            items.isEmpty() -> EmptyAlbum(tab, onImport, addOld)

            tab == GalleryTab.FILM -> FilmAlbum(items) { item -> viewing = items.indexOf(item) }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items, key = { it.uri.toString() }) { item ->
                    MediaCell(item) { viewing = items.indexOf(item) }
                }
            }
        }
    }
}

/** สมุดสะสมภาพฟิล์ม: จัดกลุ่มตามม้วน เรียงม้วนที่ถ่ายล่าสุดขึ้นก่อน */
@Composable
private fun FilmAlbum(items: List<AppMedia.Item>, onOpen: (AppMedia.Item) -> Unit) {
    val groups = remember(items) { items.groupBy { AppMedia.rollIdOf(it) } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEach { (rollId, list) ->
            val roll = FilmRolls.all.firstOrNull { it.id == rollId }
            item(span = { GridItemSpan(maxLineSpan) }, key = "h_" + (rollId ?: "other")) {
                Row(Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(width = 14.dp, height = 18.dp).clip(RoundedCornerShape(3.dp))
                            .background(roll?.let { Color(it.color) } ?: Color.Gray),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(roll?.name ?: "ม้วนอื่น ๆ", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("  ·  ${list.size} ภาพ", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
            items(list, key = { it.uri.toString() }) { item -> MediaCell(item) { onOpen(item) } }
        }
    }
}

@Composable
private fun EmptyAlbum(tab: GalleryTab, onImport: () -> Unit, onAddOld: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when (tab) {
                GalleryTab.FILM -> "ยังไม่มีภาพจากโหมดฟิล์ม\nลองเลื่อนแถบโหมดไปที่ ฟิล์ม แล้วถ่ายดู"
                GalleryTab.VIDEO -> "ยังไม่มีวิดีโอ"
                GalleryTab.ORIGINAL -> "ยังไม่มีรูปต้นฉบับ\nเปิด บันทึกรูปต้นฉบับคู่กัน ได้ในเมนู ⋮ ของหน้ากล้อง"
                GalleryTab.ALL -> "ยังไม่มีรูปที่ถ่ายด้วยแอพนี้"
            },
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onImport) {
            Icon(Icons.Filled.PhotoLibrary, null)
            Spacer(Modifier.width(8.dp))
            Text("นำเข้ารูปจากแกลเลอรี่มาแต่ง")
        }
        if (tab == GalleryTab.ALL || tab == GalleryTab.FILM) {
            Spacer(Modifier.height(20.dp))
            Text(
                "ถ้าเพิ่งลบแอพแล้วลงใหม่ รูปเก่ายังอยู่ในเครื่อง (Pictures/FujiCam) เลือกกลับเข้าอัลบั้มได้",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onAddOld) { Text("เพิ่มรูปเก่าเข้าอัลบั้ม", color = Yellow) }
        }
    }
}

@Composable
private fun MediaCell(item: AppMedia.Item, onClick: () -> Unit) {
    val context = LocalContext.current
    var img by remember(item.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.uri) {
        img = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(item.uri, Size(320, 320), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.aspectRatio(1f).background(Panel).clickable(onClick = onClick)) {
        img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (item.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                "วิดีโอ",
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(30.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
            )
        }
    }
}

@Composable
private fun Viewer(
    items: List<AppMedia.Item>,
    start: Int,
    onClose: () -> Unit,
    onEdit: (Uri) -> Unit,
    onFrameGrab: (Uri) -> Unit,
    onDelete: (AppMedia.Item) -> Unit,
) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = start) { items.size }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = items.getOrNull(pager.currentPage) ?: return

    fun open(item: AppMedia.Item) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(context, "ไม่พบแอพสำหรับเปิดไฟล์")
        }
    }

    fun share(item: AppMedia.Item) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (item.isVideo) "video/*" else "image/*")
            .putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "แชร์"))
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(
                    SimpleDateFormat("d MMM yyyy  HH:mm", Locale("th", "TH")).format(Date(current.added * 1000)),
                    color = Color.White,
                    fontSize = 14.sp,
                )
                Text("${pager.currentPage + 1} / ${items.size}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            }
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            ViewerPage(items[page]) { open(items[page]) }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ViewerAction(Icons.Filled.Share, "แชร์") { share(current) }
            if (current.isVideo) {
                ViewerAction(Icons.Filled.Movie, "แคปเฟรม") { onFrameGrab(current.uri) }
            } else {
                ViewerAction(Icons.Filled.Edit, "แต่ง") { onEdit(current.uri) }
            }
            ViewerAction(Icons.Filled.OpenInNew, "เปิดด้วย") { open(current) }
            ViewerAction(Icons.Filled.Delete, if (current.imported) "เอาออก" else "ลบ") { confirmDelete = true }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (current.imported) "เอาออกจากอัลบั้ม?" else "ลบไฟล์นี้?") },
            text = { Text(if (current.imported) "ไฟล์ยังอยู่ในเครื่อง แค่ไม่แสดงในอัลบั้มนี้" else "ไฟล์จะถูกลบออกจากเครื่อง") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(current)
                }) { Text("ลบ", color = Color(0xFFFF453A)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ยกเลิก") } },
        )
    }
}

@Composable
private fun ViewerPage(item: AppMedia.Item, onPlay: () -> Unit) {
    val context = LocalContext.current
    var img by remember(item.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.uri) {
        img = withContext(Dispatchers.IO) {
            runCatching {
                if (item.isVideo) {
                    context.contentResolver.loadThumbnail(item.uri, Size(1280, 1280), null).asImageBitmap()
                } else {
                    BitmapIO.load(context, item.uri, maxDim = 2048).asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val i = img
        if (i == null) {
            CircularProgressIndicator(color = Yellow)
        } else {
            ZoomableImage(i, enabled = !item.isVideo)
        }
        if (item.isVideo) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, "เล่นวิดีโอ", tint = Color.White, modifier = Modifier.size(40.dp)) }
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

/**
 * รูปที่ถ่างนิ้วซูมได้ (สูงสุด 5 เท่า) และแตะสองครั้งเพื่อซูม/คืน
 * ตอนไม่ได้ซูม ปัดนิ้วเดียวจะเลื่อนไปรูปถัดไปตามปกติ
 */
@Composable
private fun ZoomableImage(img: ImageBitmap, enabled: Boolean) {
    var scale by remember(img) { mutableStateOf(1f) }
    var offset by remember(img) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    Image(
        img,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onDoubleTap = { p ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        offset = clamp((center - p) * 1.5f, 2.5f)
                    }
                })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // จับเฉพาะตอนใช้สองนิ้ว หรือกำลังซูมอยู่ ไม่อย่างนั้นปล่อยให้ปัดเปลี่ยนรูป
                        if (fingers > 1 || scale > 1f) {
                            val s = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (s > 1f) clamp(offset + event.calculatePan(), s) else Offset.Zero
                            scale = s
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
