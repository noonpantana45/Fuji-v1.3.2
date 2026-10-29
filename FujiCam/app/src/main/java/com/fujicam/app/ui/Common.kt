package com.fujicam.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fujicam.app.filters.FilmLook
import com.fujicam.app.filters.FilmLooks
import com.fujicam.app.filters.FilterEngine
import com.fujicam.app.filters.LookGroup
import com.fujicam.app.filters.Recipe
import com.fujicam.app.filters.Scene
import com.fujicam.app.util.Prefs
import com.fujicam.app.util.hasPermission
import com.fujicam.app.util.toast
import kotlin.math.roundToInt

val Yellow = Color(0xFFFFD60A)
val Orange = Color(0xFFFF9F0A)
val Panel = Color(0xFF1C1C1E)
val NightBlue = Color(0xFF9DB4FF)

@Composable
fun FujiCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Yellow,
            onPrimary = Color.Black,
            secondary = Orange,
            background = Color.Black,
            surface = Panel,
        ),
        content = content,
    )
}

// ======================= การนำทางระหว่างหน้า =======================

sealed interface Screen {
    data object Camera : Screen
    data class Editor(val uri: Uri) : Screen
    data class FrameGrab(val uri: Uri?) : Screen
    data class Batch(val uris: List<Uri>, val recipe: Recipe?) : Screen
    data object Gallery : Screen
}

@Composable
fun AppRoot(registerVolumeKey: ((() -> Unit)?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { Prefs(context) }
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
    var lookId by remember { mutableStateOf(prefs.lookId) }
    var intensity by remember { mutableFloatStateOf(prefs.intensity) }
    var cameraGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraGranted = hasPermission(context, Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filterNot { hasPermission(context, it) }
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
    }
    // กลับมาจากหน้าตั้งค่า → เช็คสิทธิ์ใหม่
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) cameraGranted = hasPermission(context, Manifest.permission.CAMERA)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) screen = Screen.Editor(uri)
    }
    val pickPhoto: () -> Unit = {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // แต่งหลายรูปพร้อมกัน: เลือกรูปก่อน แล้วไปหน้าเลือกสูตร
    var pendingRecipe by remember { mutableStateOf<Recipe?>(null) }
    val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) screen = Screen.Batch(uris, pendingRecipe)
    }
    val startBatch: (Recipe?) -> Unit = { r ->
        pendingRecipe = r
        batchPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    when (val s = screen) {
        Screen.Camera -> if (cameraGranted) {
            CameraScreen(
                lookId = lookId,
                intensity = intensity,
                onLookChange = { lookId = it; prefs.lookId = it },
                onIntensityChange = { intensity = it; prefs.intensity = it },
                onPickPhoto = pickPhoto,
                onOpenFrameGrab = { screen = Screen.FrameGrab(it) },
                onEditUri = { screen = Screen.Editor(it) },
                onBatch = { startBatch(null) },
                onOpenGallery = { screen = Screen.Gallery },
                registerVolumeKey = registerVolumeKey,
            )
        } else {
            PermissionScreen(
                onRequest = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onPickPhoto = pickPhoto,
                onFrameGrab = { screen = Screen.FrameGrab(null) },
            )
        }

        is Screen.Editor -> EditorScreen(
            uri = s.uri,
            initialLookId = lookId,
            onBack = { screen = Screen.Camera },
            onBatch = { startBatch(it) },
        )
        is Screen.Batch -> BatchScreen(uris = s.uris, initial = s.recipe, onBack = { screen = Screen.Camera })
        Screen.Gallery -> GalleryScreen(
            onBack = { screen = Screen.Camera },
            onEdit = { screen = Screen.Editor(it) },
            onFrameGrab = { screen = Screen.FrameGrab(it) },
            onImport = pickPhoto,
        )
        is Screen.FrameGrab -> FrameGrabScreen(
            initialUri = s.uri,
            onBack = { screen = Screen.Camera },
            onEdit = { screen = Screen.Editor(it) },
        )
    }
}

@Composable
private fun PermissionScreen(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickPhoto: () -> Unit,
    onFrameGrab: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("FujiCam ต้องใช้กล้อง", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "อนุญาตกล้องเพื่อถ่ายรูปและวิดีโอ และอนุญาตไมโครโฟนถ้าต้องการเสียงในวิดีโอ",
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("อนุญาตกล้อง") }
        TextButton(onClick = onOpenSettings) { Text("เปิดการตั้งค่าแอพ") }
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onPickPhoto) {
            Icon(Icons.Filled.PhotoLibrary, null)
            Spacer(Modifier.width(8.dp))
            Text("แต่งรูปจากแกลเลอรี่")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onFrameGrab) {
            Icon(Icons.Filled.Movie, null)
            Spacer(Modifier.width(8.dp))
            Text("แคปภาพจากวิดีโอ")
        }
    }
}

// ======================= ชิ้นส่วนที่ใช้ร่วมกัน =======================

@Composable
fun yellowSlider() = SliderDefaults.colors(
    thumbColor = Yellow,
    activeTrackColor = Yellow,
    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    showSigned: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(76.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, colors = yellowSlider(), modifier = Modifier.weight(1f))
        val pct = (value * 100).roundToInt()
        Text(
            if (showSigned && pct > 0) "+$pct" else "$pct",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
fun TopIcon(icon: ImageVector, tint: Color = Color.White, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(24.dp))
    }
}

/** ป้ายตัวหนังสือในแถบบน เช่น 4K, 3:2 */
@Composable
fun TopTextPill(text: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = if (active) Yellow else Color.White
    Text(
        text,
        color = if (enabled) c else c.copy(alpha = 0.35f),
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (enabled) c else c.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
fun RoundButton(icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(28.dp))
    }
}

@Composable
fun DateStampLabel(modifier: Modifier = Modifier, time: Long = System.currentTimeMillis()) {
    Text(
        FilterEngine.dateStampText(time),
        modifier = modifier,
        color = Color(0xFFFF9628),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        style = TextStyle(shadow = Shadow(color = Color(0xFFFF5000), blurRadius = 14f)),
    )
}

/** ปุ่มเม็ดยาสำหรับเลือกหมวด */
@Composable
fun Pill(label: String, selected: Boolean, selectedColor: Color = Yellow, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.Black else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) selectedColor else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/**
 * แถบเลือกฟิลเตอร์: ค้นหา / ⭐ โปรด / สลับกลางวัน-กลางคืน / หมวด + รูปตัวอย่าง
 * แตะค้างที่ฟิลเตอร์เพื่อเพิ่มหรือเอาออกจากรายการโปรด (ฟิลเตอร์โปรดขึ้นหน้าสุดเสมอ)
 * LazyRow สร้างเฉพาะรูปตัวอย่างที่อยู่บนจอ จึงไม่ต้องเรนเดอร์ทั้ง 67 แบบพร้อมกัน
 */
@Composable
fun FilterStrip(
    thumb: ImageBitmap?,
    selectedId: String,
    onSelect: (FilmLook) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var favs by remember { mutableStateOf(prefs.favorites) }
    var scene by rememberSaveable { mutableStateOf(FilmLooks.sceneOf(FilmLooks.byId(selectedId))) }
    var group by rememberSaveable { mutableStateOf<LookGroup?>(null) }
    var showFavs by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(scene) { FilmLooks.groupsOf(scene) }
    val looks = remember(scene, group, favs, showFavs, searching, query) {
        when {
            searching && query.isNotBlank() -> listOf(FilmLooks.ORIGINAL) + FilmLooks.search(query)
            showFavs -> listOf(FilmLooks.ORIGINAL) + FilmLooks.all.filter { it.id in favs }
            else -> {
                val l = FilmLooks.list(scene, group)
                val (f, rest) = l.drop(1).partition { it.id in favs }
                listOf(l.first()) + f + rest
            }
        }
    }
    val selectedLook = FilmLooks.byId(selectedId)
    val rootId = FilmLooks.rootOf(selectedLook).id
    val family = remember(selectedId) { FilmLooks.familyOf(selectedLook) }
    val childCount = remember { FilmLooks.all.groupingBy { it.parent }.eachCount() }
    val toggleFav: (FilmLook) -> Unit = { look ->
        if (!look.isOriginal) {
            val added = look.id !in favs
            favs = if (added) favs + look.id else favs - look.id
            prefs.favorites = favs
        }
    }

    Column(modifier) {
        if (searching) {
            SearchBar(query, onChange = { query = it }, onClose = {
                searching = false
                query = ""
            })
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                item {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                            .clickable { searching = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Search, "ค้นหาฟิลเตอร์", tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
                item { Pill("⭐ โปรด", showFavs) { showFavs = !showFavs } }
                item {
                    SceneSwitch(if (showFavs) null else scene) {
                        scene = it
                        group = null
                        showFavs = false
                    }
                }
                item { Pill("ทั้งหมด", !showFavs && group == null) { group = null; showFavs = false } }
                items(groups) { g -> Pill(g.label, !showFavs && g == group) { group = g; showFavs = false } }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (looks.size <= 1 && (showFavs || searching)) {
            Text(
                if (showFavs) "ยังไม่มีฟิลเตอร์โปรด แตะค้างที่ฟิลเตอร์เพื่อเพิ่ม" else "ไม่พบฟิลเตอร์ที่ค้นหา",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(looks, key = { it.id }) { look ->
                FilterItem(
                    look = look,
                    thumb = thumb,
                    selected = look.id == selectedId || (look.id == rootId && look.parent == null),
                    favorite = look.id in favs,
                    variants = childCount[look.id] ?: 0,
                    onClick = { onSelect(look) },
                    onLongPress = { toggleFav(look) },
                )
            }
        }
        // ตัวเลือกย่อยของฟิลเตอร์ที่เลือก เช่น ACROS → +Ye / +R / +G
        if (family.size > 1 && !selectedLook.isOriginal) {
            Spacer(Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                item { Text("แบบย่อย", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp) }
                items(family, key = { "v_" + it.id }) { v ->
                    VariantPill(
                        label = v.name,
                        selected = v.id == selectedId,
                        favorite = v.id in favs,
                        onTap = { onSelect(v) },
                        onLongPress = { toggleFav(v) },
                    )
                }
            }
        }
    }
}

/** ปุ่มแบบย่อย: แตะเพื่อเลือก แตะค้างเพื่อปักโปรด */
@Composable
private fun VariantPill(label: String, selected: Boolean, favorite: Boolean, onTap: () -> Unit, onLongPress: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onLongPress)
    Text(
        (if (favorite) "★ " else "") + label,
        color = if (selected) Color.Black else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Yellow else Color.White.copy(alpha = 0.12f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hold()
                    },
                )
            }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.14f)).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Yellow),
            modifier = Modifier.weight(1f).padding(vertical = 8.dp).focusRequester(focus),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text("ค้นหา เช่น chrome, แฟลช, portra", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp)
                    }
                    inner()
                }
            },
        )
        Box(
            Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, "ปิดการค้นหา", tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun SceneSwitch(scene: Scene?, onChange: (Scene) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).padding(2.dp)) {
        Scene.entries.forEach { s ->
            val sel = s == scene
            Text(
                s.label,
                color = if (sel) Color.Black else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (sel) (if (s == Scene.NIGHT) NightBlue else Yellow) else Color.Transparent)
                    .clickable { onChange(s) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FilterItem(
    look: FilmLook,
    thumb: ImageBitmap?,
    selected: Boolean,
    favorite: Boolean,
    variants: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val filter = remember(look.id) { ColorFilter.colorMatrix(ColorMatrix(look.matrix)) }
    val haptic = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onLongPress)
    Column(
        Modifier.width(68.dp).pointerInput(look.id) {
            detectTapGestures(
                onTap = { tap() },
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    hold()
                },
            )
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(shape).background(Panel)
                .border(if (selected) 2.dp else 0.5.dp, if (selected) Yellow else Color.White.copy(alpha = 0.2f), shape),
        ) {
            if (thumb != null) {
                Image(
                    thumb,
                    contentDescription = look.name,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (look.isOriginal) null else filter,
                    modifier = Modifier.fillMaxSize().clip(shape),
                )
            }
            if (look.flash) {
                Text("⚡", fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp))
            }
            if (variants > 0) {
                Text(
                    "+$variants",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 3.dp),
                )
            }
            if (favorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "โปรด",
                    tint = Yellow,
                    modifier = Modifier.align(Alignment.TopStart).padding(3.dp).size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            look.name,
            color = if (selected) Yellow else Color.White,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
