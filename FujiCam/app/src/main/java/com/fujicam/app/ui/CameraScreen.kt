package com.fujicam.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaActionSound
import android.net.Uri
import android.util.Size
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ZoomState
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.fujicam.app.camera.CamMode
import com.fujicam.app.camera.CameraEngine
import com.fujicam.app.camera.DualLayout
import com.fujicam.app.camera.ShutterSound
import com.fujicam.app.camera.SoundMode
import com.fujicam.app.camera.VideoJobs
import com.fujicam.app.camera.VideoPipeline
import com.fujicam.app.filters.Adjustments
import com.fujicam.app.filters.Aspect
import com.fujicam.app.filters.Border
import com.fujicam.app.filters.CM
import com.fujicam.app.filters.FilmLook
import com.fujicam.app.filters.FilmLooks
import com.fujicam.app.filters.FilmRoll
import com.fujicam.app.filters.FilmRolls
import com.fujicam.app.filters.FilterEngine
import com.fujicam.app.filters.PipCorner
import com.fujicam.app.filters.Scene
import com.fujicam.app.util.BitmapIO
import com.fujicam.app.util.AppMedia
import com.fujicam.app.util.MediaSaver
import com.fujicam.app.util.Prefs
import com.fujicam.app.util.findActivity
import com.fujicam.app.util.formatNanos
import com.fujicam.app.util.hasPermission
import com.fujicam.app.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private fun newPreviewView(context: Context) = PreviewView(context).apply {
    // COMPATIBLE = ใช้ TextureView ทำให้ใส่ฟิลเตอร์สดบนพรีวิวได้
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    scaleType = PreviewView.ScaleType.FILL_CENTER
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}

private fun <T : View> T.detachFromParent(): T {
    (parent as? ViewGroup)?.removeView(this)
    return this
}

@Composable
fun CameraScreen(
    lookId: String,
    intensity: Float,
    onLookChange: (String) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onPickPhoto: () -> Unit,
    onOpenFrameGrab: (Uri?) -> Unit,
    onEditUri: (Uri) -> Unit,
    onBatch: () -> Unit,
    onOpenGallery: () -> Unit,
    registerVolumeKey: ((() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiScope = rememberCoroutineScope()
    val workScope = lifecycleOwner.lifecycleScope // งานบันทึกไฟล์ไม่ถูกยกเลิกเมื่อเปลี่ยนหน้า
    val haptic = LocalHapticFeedback.current
    val prefs = remember { Prefs(context) }
    val engine = remember { CameraEngine(context.applicationContext) }
    val sound = remember { ShutterSound(context) } // มีเสียงเฉพาะตอนโทรศัพท์เปิดเสียง
    val mainPreview = remember { newPreviewView(context) }
    val pipPreview = remember { newPreviewView(context) }

    var mode by remember { mutableStateOf(CameraSession.mode) }
    var lens by remember { mutableIntStateOf(CameraSession.lens) }
    var flash by remember { mutableIntStateOf(CameraSession.flash) }
    var timerSec by remember { mutableIntStateOf(CameraSession.timer) }
    SideEffect {
        CameraSession.mode = mode
        CameraSession.lens = lens
        CameraSession.flash = flash
        CameraSession.timer = timerSec
    }
    var grid by remember { mutableStateOf(prefs.grid) }
    var dateStamp by remember { mutableStateOf(prefs.dateStamp) }
    var mirrorFront by remember { mutableStateOf(prefs.mirrorFront) }
    var uhd by remember { mutableStateOf(prefs.uhd) }
    var showFilters by remember { mutableStateOf(false) }
    var showExposure by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // โหมดกล้องฟิล์ม
    var rollId by remember { mutableStateOf(prefs.rollId) }
    var shotsLeft by remember {
        mutableIntStateOf(prefs.shotsLeft(prefs.rollId).let { if (it < 0) FilmRolls.byId(prefs.rollId).shots else it })
    }
    var autoReload by remember { mutableStateOf(prefs.autoReload) }

    // v1.3
    var dualSaveSeparate by remember { mutableStateOf(prefs.dualSaveSeparate) }
    val lowRam = remember {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
    }
    var glowPreview by remember { mutableStateOf(prefs.glowPreview(default = !lowRam)) }
    var pipCorner by remember { mutableStateOf(PipCorner.entries.firstOrNull { it.name == prefs.pipCorner } ?: PipCorner.TOP_START) }
    var dualSwap by remember { mutableStateOf(prefs.dualSwap) }
    var pipDrag by remember { mutableStateOf(Offset.Zero) }
    var settingsOpen by remember { mutableStateOf(false) }
    var soundMode by remember { mutableStateOf(SoundMode.entries.firstOrNull { it.name == prefs.soundMode } ?: SoundMode.FOLLOW) }
    var vibrateOn by remember { mutableStateOf(prefs.vibrate) }
    SideEffect {
        sound.mode = soundMode
        sound.vibrateOn = vibrateOn
    }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var filmBorderName by remember { mutableStateOf(prefs.filmBorder) }
    var filmDate by remember { mutableStateOf(prefs.filmDate) }
    var rollEmpty by remember { mutableStateOf(false) }
    var screenFlash by remember { mutableStateOf(false) }

    // v1.2
    var aspect by remember { mutableStateOf(Aspect.entries.firstOrNull { it.name == prefs.aspect } ?: Aspect.R4_3) }
    var saveOriginal by remember { mutableStateOf(prefs.saveOriginal) }
    var dualLayout by remember { mutableStateOf(DualLayout.entries.firstOrNull { it.name == prefs.dualLayout } ?: DualLayout.SEPARATE) }
    var camHalation by remember { mutableFloatStateOf(prefs.camHalation) }
    var dualOk by remember { mutableStateOf(false) }
    var glow by remember { mutableStateOf<ImageBitmap?>(null) }
    val videoProgress by VideoJobs.progress.collectAsState()
    val videoLabel by VideoJobs.label.collectAsState()

    var bindCount by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var evIndex by remember { mutableIntStateOf(0) }
    var evMin by remember { mutableIntStateOf(0) }
    var evMax by remember { mutableIntStateOf(0) }
    var evStep by remember { mutableFloatStateOf(0f) }

    var recording by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var recNanos by remember { mutableLongStateOf(0L) }
    var countdown by remember { mutableIntStateOf(0) }
    var processing by remember { mutableIntStateOf(0) }
    var blink by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }

    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var lastIsVideo by remember { mutableStateOf(false) }
    var lastThumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var filterThumb by remember { mutableStateOf<ImageBitmap?>(null) }

    val isFilm = mode == CamMode.FILM
    val roll = remember(rollId) { FilmRolls.byId(rollId) }
    val filmBorder = Border.entries.firstOrNull { it.name == filmBorderName } ?: roll.border
    val look = remember(lookId) { FilmLooks.byId(lookId) }
    // ในโหมดฟิล์ม สีมาจากม้วนฟิล์ม ไม่ใช่ฟิลเตอร์ที่เลือก
    val activeLook = if (isFilm) FilmLooks.byId(roll.lookId) else look
    val activeIntensity = if (isFilm) 1f else intensity
    val stampOn = if (isFilm) filmDate else dateStamp
    val matrix = remember(activeLook.id, activeIntensity) {
        FilterEngine.matrixFor(activeLook, Adjustments.NONE, activeIntensity)
    }
    // แสงฟุ้งรอบไฟ ปรับได้เมื่อเลือกฟิลเตอร์กลางคืน
    val nightLook = !isFilm && !look.isOriginal && FilmLooks.sceneOf(look) == Scene.NIGHT
    val camAdj = if (nightLook && camHalation > 0f) Adjustments(halation = camHalation) else Adjustments.NONE
    val glowHal = if (mode.isDual) 0f else FilterEngine.halationOf(activeLook, camAdj, activeIntensity)
    val glowBlm = if (mode.isDual) 0f else FilterEngine.bloomOf(activeLook, camAdj, activeIntensity)
    // สัดส่วนภาพใช้กับโหมดรูปถ่ายและฟิล์ม (4:3 = เต็มเซนเซอร์ ไม่ต้องครอป)
    // ขอบอินสแตนท์มีสัดส่วนของตัวเอง (46:62) จึงไม่ใช้ปุ่มสัดส่วนภาพ
    val instantFrame = isFilm && filmBorder == Border.INSTANT
    val aspectOn = (mode == CamMode.PHOTO || (isFilm && !instantFrame)) && aspect != Aspect.R4_3
    // กล้องหน้าไม่มีหลอดแฟลช ใช้หน้าจอสว่างแทน
    val hwFlash = if (lens == CameraSelector.LENS_FACING_FRONT) ImageCapture.FLASH_MODE_OFF else flash

    // ---------------- วงจรชีวิตกล้อง ----------------
    DisposableEffect(Unit) {
        sound.load()
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val orientation = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees != OrientationEventListener.ORIENTATION_UNKNOWN) {
                    engine.setTargetRotation(CameraEngine.rotationFor(degrees))
                }
            }
        }
        orientation.enable()
        onDispose {
            orientation.disable()
            engine.release()
            sound.release()
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ซ่อนโหมดถ่ายคู่/อัดคู่ ถ้าเครื่องเปิดสองกล้องพร้อมกันไม่ได้
    LaunchedEffect(Unit) {
        dualOk = try {
            engine.ensureInit()
            engine.dualSupported()
        } catch (e: Exception) {
            false
        }
    }

    // พรีวิวแสงฟุ้ง: ดึงภาพจากพรีวิวมาหาจุดสว่างทุก ๆ 600ms (ปิดได้ในเมนู ไฟล์รูปจริงยังมีแสงฟุ้งครบ)
    LaunchedEffect(glowHal, glowBlm, bindCount, glowPreview) {
        if (!glowPreview || (glowHal <= 0f && glowBlm <= 0f)) {
            glow = null
            return@LaunchedEffect
        }
        while (true) {
            val frame = mainPreview.bitmap
            if (frame != null) {
                val g = withContext(Dispatchers.Default) {
                    try {
                        FilterEngine.glowLayer(frame, glowHal, glowBlm)
                    } finally {
                        frame.recycle()
                    }
                }
                glow = g?.asImageBitmap()
            }
            delay(600)
        }
    }

    LaunchedEffect(mode, lens, uhd, if (mode.isVideo) mirrorFront else true) {
        engine.mirrorFrontVideo = mirrorFront
        try {
            engine.ensureInit()
        } catch (e: Exception) {
            toast(context, "เปิดกล้องไม่ได้: ${e.message}")
            return@LaunchedEffect
        }
        if (mode.isDual && !engine.dualSupported()) {
            toast(context, "มือถือเครื่องนี้ไม่รองรับการเปิดกล้องหน้า-หลังพร้อมกัน")
            mode = CamMode.PHOTO
            return@LaunchedEffect
        }
        val ok = engine.bind(lifecycleOwner, mode, lens, mainPreview, pipPreview, uhd, hwFlash)
        if (!ok) {
            toast(context, "เปิดโหมดนี้บนเครื่องนี้ไม่ได้")
            if (mode != CamMode.PHOTO) mode = CamMode.PHOTO
            return@LaunchedEffect
        }
        val es = engine.camera?.cameraInfo?.exposureState
        if (es != null && es.isExposureCompensationSupported) {
            evMin = es.exposureCompensationRange.lower
            evMax = es.exposureCompensationRange.upper
            evStep = es.exposureCompensationStep.toFloat()
            // คืนค่าปรับแสงเดิม (ไม่หายตอนกลับมาจากอัลบั้ม/หน้าแต่งรูป)
            evIndex = CameraSession.ev.coerceIn(evMin, evMax)
            if (evIndex != 0) engine.camera?.cameraControl?.setExposureCompensationIndex(evIndex)
        } else {
            evMin = 0; evMax = 0
            evIndex = 0
        }
        // คืนค่าซูมเดิม ถ้ายังเป็นกล้องตัวเดิม
        if (CameraSession.zoomLens == lens && CameraSession.zoom != 1f) {
            engine.camera?.cameraControl?.setZoomRatio(CameraSession.zoom)
        }
        bindCount++
    }

    DisposableEffect(bindCount) {
        val zoomLive = engine.camera?.cameraInfo?.zoomState
        val observer = Observer<ZoomState> { z ->
            minZoom = z.minZoomRatio
            maxZoom = z.maxZoomRatio
            zoom = z.zoomRatio
        }
        zoomLive?.observe(lifecycleOwner, observer)
        onDispose { zoomLive?.removeObserver(observer) }
    }

    LaunchedEffect(hwFlash, bindCount) { engine.applyFlash(mode, hwFlash) }

    // แฟลชเปิด/ปิดตามที่ผู้ใช้กดเองเท่านั้น เลือกฟิลเตอร์แฟลชแล้วแค่แนะนำ ไม่เปิดให้เอง
    fun suggestFlash(wants: Boolean) {
        if (wants && flash == ImageCapture.FLASH_MODE_OFF && !mode.isVideo && !mode.isDual) {
            toast(context, "แนะนำให้เปิดแฟลช: กดปุ่มสายฟ้าซ้ายบน")
        }
    }

    // ฟิลเตอร์สดบนพรีวิว
    LaunchedEffect(matrix) {
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        mainPreview.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        pipPreview.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    // รูปตัวอย่างในแถบฟิลเตอร์ อัปเดตจากภาพกล้องจริง
    LaunchedEffect(showFilters, bindCount) {
        while (showFilters) {
            mainPreview.bitmap?.let { full ->
                val small = BitmapIO.scaleToMax(full, 160)
                if (small !== full) full.recycle()
                filterThumb = small.asImageBitmap()
            }
            delay(1500)
        }
    }
    LaunchedEffect(blink) {
        if (blink) {
            delay(110)
            blink = false
        }
    }
    LaunchedEffect(focusAt) {
        if (focusAt != null) {
            delay(1600)
            focusAt = null
        }
    }

    // ---------------- การกระทำ ----------------
    fun setLast(uri: Uri?, isVideo: Boolean) {
        if (uri == null) return
        lastUri = uri
        lastIsVideo = isVideo
        workScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
            }
            lastThumb = bmp?.asImageBitmap()
        }
    }

    // เปิดแอพมา ให้รูปล่าสุดที่เคยถ่ายขึ้นมุมซ้ายล่างทันที
    LaunchedEffect(Unit) {
        val item = withContext(Dispatchers.IO) { AppMedia.latest(context) }
        if (item != null && lastUri == null) setLast(item.uri, item.isVideo)
    }

    fun setScreenBrightness(value: Float) {
        val window = context.findActivity()?.window ?: return
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    /** ถ่ายภาพ ถ้าเป็นกล้องหน้า+เปิดแฟลช จะทำจอขาวสว่างสุดแทนแฟลช */
    suspend fun captureShot(ic: ImageCapture, mirror: Boolean, screen: Boolean): Bitmap {
        if (screen) {
            screenFlash = true
            setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
            delay(450)
        }
        try {
            return engine.capture(ic, mirror)
        } finally {
            if (screen) {
                screenFlash = false
                setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            }
        }
    }

    /** ใส่ม้วนใหม่ (นับใหม่จากเต็มม้วน) */
    fun loadRoll(r: FilmRoll) {
        rollId = r.id
        prefs.rollId = r.id
        shotsLeft = r.shots
        prefs.setShotsLeft(r.id, r.shots)
    }

    /** สลับไปม้วนอื่น โดยจำจำนวนภาพที่เหลือของแต่ละม้วนไว้ */
    fun switchRoll(r: FilmRoll) {
        rollId = r.id
        prefs.rollId = r.id
        shotsLeft = prefs.shotsLeft(r.id).let { if (it < 0) r.shots else it }
        prefs.setShotsLeft(r.id, shotsLeft)
    }

    fun onVideoFinished(file: File, ok: Boolean, colorMatrix: FloatArray) {
        if (!ok) {
            file.delete()
            toast(context, "บันทึกวิดีโอไม่สำเร็จ")
            return
        }
        processing++
        workScope.launch {
            try {
                if (!CM.isIdentity(colorMatrix)) toast(context, "กำลังใส่ฟิลเตอร์ให้วิดีโอ…")
                val uri = VideoPipeline.finish(context, file, colorMatrix)
                setLast(uri, true)
                toast(context, if (uri != null) "บันทึกวิดีโอแล้ว" else "บันทึกวิดีโอไม่สำเร็จ")
            } finally {
                processing--
            }
        }
    }

    fun takePhoto(silent: Boolean = false) {
        val ic = engine.imageCapture ?: run {
            toast(context, "โหมดนี้ถ่ายภาพนิ่งไม่ได้บนเครื่องนี้")
            return
        }
        val mirror = lens == CameraSelector.LENS_FACING_FRONT && mirrorFront
        val screen = lens == CameraSelector.LENS_FACING_FRONT && flash == ImageCapture.FLASH_MODE_ON && !recording
        val snapLook = look
        val snapIntensity = intensity
        val snapAdj = camAdj
        val stamp = dateStamp
        val ratio = if (aspectOn && !recording) aspect.ratio else 0f
        val keepOriginal = saveOriginal && !recording
        if (!silent) sound.play(MediaActionSound.SHUTTER_CLICK)
        sound.buzz()
        if (!screen) blink = true
        processing++
        workScope.launch {
            try {
                val raw = captureShot(ic, mirror, screen)
                val uri = withContext(Dispatchers.Default) {
                    val out = FilterEngine.render(
                        raw, snapLook, snapAdj, snapIntensity, stamp,
                        aspect = ratio, caption = FilterEngine.captionFor(snapLook),
                    )
                    if (keepOriginal) MediaSaver.saveBitmap(context, raw, prefix = "FJC_ORIG", folder = MediaSaver.ORIGINALS)
                    raw.recycle()
                    MediaSaver.saveBitmap(context, out).also { out.recycle() }
                }
                if (uri == null) toast(context, "บันทึกรูปไม่สำเร็จ")
                setLast(uri, false)
            } catch (e: Exception) {
                toast(context, "ถ่ายไม่สำเร็จ: ${e.message}")
            } finally {
                processing--
            }
        }
    }

    fun takeFilmPhoto() {
        if (shotsLeft <= 0) {
            rollEmpty = true
            return
        }
        val ic = engine.imageCapture ?: return
        val r = roll
        val filmLook = FilmLooks.byId(r.lookId)
        val stamp = filmDate
        val border = filmBorder
        val frame = r.shots - shotsLeft + 1
        val mirror = lens == CameraSelector.LENS_FACING_FRONT && mirrorFront
        val screen = lens == CameraSelector.LENS_FACING_FRONT && flash == ImageCapture.FLASH_MODE_ON
        val ratio = if (aspectOn) aspect.ratio else 0f
        val keepOriginal = saveOriginal
        shotsLeft -= 1
        prefs.setShotsLeft(r.id, shotsLeft)
        sound.play(MediaActionSound.SHUTTER_CLICK)
        sound.buzz()
        if (!screen) blink = true
        processing++
        workScope.launch {
            try {
                val raw = captureShot(ic, mirror, screen)
                val fx = FilterEngine.randomFilmFx(r.leakChance)
                val uri = withContext(Dispatchers.Default) {
                    val out = FilterEngine.render(
                        raw, filmLook, Adjustments.NONE, 1f, stamp,
                        fx = fx, border = border, iso = r.iso, frame = frame, aspect = ratio,
                        caption = FilterEngine.captionFor(filmLook, r.iso, frame, r.shots),
                    )
                    // เก็บภาพคลีน ๆ ไว้ เผื่อแสงรั่วสุ่มไปทับจุดสำคัญ
                    if (keepOriginal) MediaSaver.saveBitmap(context, raw, prefix = "FJC_ORIG", folder = MediaSaver.ORIGINALS)
                    raw.recycle()
                    // ใส่รหัสม้วนไว้ในชื่อไฟล์ เพื่อจัดอัลบั้มตามม้วนในหน้าอัลบั้ม
                    MediaSaver.saveBitmap(context, out, prefix = "FJC_FILM_${r.id}").also { out.recycle() }
                }
                if (uri == null) toast(context, "บันทึกรูปไม่สำเร็จ")
                setLast(uri, false)
                if (shotsLeft == 0 && rollId == r.id) {
                    toast(context, if (autoReload) "ถ่ายครบม้วนแล้ว กดถ่ายต่อได้เลย จะใส่ม้วนใหม่ให้" else "ถ่ายครบม้วนแล้ว (${r.shots} ภาพ)")
                }
            } catch (e: Exception) {
                if (rollId == r.id) {
                    shotsLeft += 1
                    prefs.setShotsLeft(r.id, shotsLeft)
                }
                toast(context, "ถ่ายไม่สำเร็จ: ${e.message}")
            } finally {
                processing--
            }
        }
    }

    fun takeDualPhoto() {
        val icBack = engine.imageCapture
        val icFront = engine.imageCapture2
        if (icBack == null || icFront == null) return
        val snapLook = look
        val snapIntensity = intensity
        val stamp = dateStamp
        val mirror = mirrorFront
        val separate = dualSaveSeparate
        val swap = dualSwap
        val corner = pipCorner
        sound.play(MediaActionSound.SHUTTER_CLICK)
        sound.buzz()
        blink = true
        processing++
        workScope.launch {
            try {
                val (back, front) = coroutineScope {
                    val b = async { engine.capture(icBack, false) }
                    val f = async { engine.capture(icFront, mirror) }
                    b.await() to f.await()
                }
                val uri = withContext(Dispatchers.Default) {
                    val b = FilterEngine.render(back, snapLook, Adjustments.NONE, snapIntensity, false)
                    back.recycle()
                    val f = FilterEngine.render(front, snapLook, Adjustments.NONE, snapIntensity, false)
                    front.recycle()
                    val combo = if (swap) FilterEngine.composeDual(f, b, corner) else FilterEngine.composeDual(b, f, corner)
                    if (stamp) FilterEngine.drawDateStamp(combo, System.currentTimeMillis())
                    val u = MediaSaver.saveBitmap(context, combo, prefix = "FJC_DUAL")
                    // ค่าเริ่มต้นเก็บแค่ภาพรวม 1 ไฟล์ ไม่ให้แกลเลอรี่รก
                    if (separate) {
                        MediaSaver.saveBitmap(context, b, prefix = "FJC_BACK")
                        MediaSaver.saveBitmap(context, f, prefix = "FJC_FRONT")
                    }
                    combo.recycle(); b.recycle(); f.recycle()
                    u
                }
                setLast(uri, false)
            } catch (e: Exception) {
                toast(context, "ถ่ายคู่ไม่สำเร็จ: ${e.message}")
            } finally {
                processing--
            }
        }
    }

    fun startVideo() {
        val vc = engine.videoCapture ?: return
        val snap = matrix
        val audio = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        val file = File(context.cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        sound.buzz()
        recNanos = 0L
        paused = false
        try {
            engine.startRecording(true, vc, file, audio, onStatus = { recNanos = it }) { f, ok -> onVideoFinished(f, ok, snap) }
            recording = true
        } catch (e: Exception) {
            toast(context, "เริ่มอัดวิดีโอไม่ได้: ${e.message}")
        }
    }

    fun startDualVideo() {
        val v1 = engine.videoCapture
        val v2 = engine.videoCapture2
        if (v1 == null || v2 == null) return
        val snap = matrix
        val audio = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        val t = System.currentTimeMillis()
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        sound.buzz()
        recNanos = 0L
        paused = false
        val layout = dualLayout
        val swap = dualSwap
        val corner = pipCorner
        // รอให้ไฟล์หน้า+หลังเสร็จทั้งคู่ แล้วค่อยรวม/บันทึก
        var backFile: File? = null
        var frontFile: File? = null
        var finished = 0
        fun collect(f: File, ok: Boolean, isBack: Boolean) {
            val good = ok && f.exists() && f.length() > 0
            if (!good) f.delete()
            if (isBack) {
                backFile = if (good) f else null
            } else {
                frontFile = if (good) f else null
            }
            finished++
            if (finished < 2) return
            val b = backFile
            val fr = frontFile
            if (b == null && fr == null) {
                toast(context, "บันทึกวิดีโอไม่สำเร็จ")
                return
            }
            processing++
            workScope.launch {
                try {
                    val (uris, merged) = VideoPipeline.finishDual(context, b, fr, snap, layout, swap, corner)
                    uris.lastOrNull { it != null }?.let { setLast(it, true) }
                    toast(
                        context,
                        when {
                            merged -> "บันทึกวิดีโอรวมหน้า-หลังแล้ว"
                            layout != DualLayout.SEPARATE && b != null && fr != null -> "รวมไฟล์ไม่สำเร็จ เก็บเป็น 2 ไฟล์แทน"
                            else -> "บันทึกวิดีโอ ${uris.count { it != null }} ไฟล์แล้ว"
                        },
                    )
                } finally {
                    processing--
                }
            }
        }
        try {
            engine.startRecording(true, v1, File(context.cacheDir, "dual_back_$t.mp4"), audio, onStatus = { recNanos = it }) { f, ok ->
                collect(f, ok, isBack = true)
            }
            engine.startRecording(false, v2, File(context.cacheDir, "dual_front_$t.mp4"), false, onStatus = {}) { f, ok ->
                collect(f, ok, isBack = false)
            }
            recording = true
        } catch (e: Exception) {
            engine.stopRecordings()
            toast(context, "อัดคู่ไม่ได้: ${e.message}")
        }
    }

    fun stopVideo() {
        engine.stopRecordings()
        recording = false
        paused = false
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        sound.buzz()
    }

    fun togglePause() {
        if (paused) engine.resumeRecordings() else engine.pauseRecordings()
        paused = !paused
    }

    suspend fun runCountdown() {
        for (i in timerSec downTo 1) {
            countdown = i
            delay(1000)
        }
        countdown = 0
    }

    val onShutter: () -> Unit = {
        if (countdown == 0) {
            when (mode) {
                CamMode.FILM -> {
                    if (shotsLeft <= 0 && autoReload) {
                        // ใส่ม้วนใหม่อัตโนมัติ ไม่ให้พลาดช็อต
                        loadRoll(roll)
                        toast(context, "ใส่ม้วนใหม่แล้ว")
                    }
                    if (shotsLeft <= 0) {
                        rollEmpty = true
                    } else {
                        uiScope.launch { runCountdown(); takeFilmPhoto() }
                    }
                }
                CamMode.PHOTO -> uiScope.launch { runCountdown(); takePhoto() }
                CamMode.DUAL_PHOTO -> uiScope.launch { runCountdown(); takeDualPhoto() }
                CamMode.VIDEO -> if (recording) stopVideo() else uiScope.launch { runCountdown(); startVideo() }
                CamMode.DUAL_VIDEO -> if (recording) stopVideo() else uiScope.launch { runCountdown(); startDualVideo() }
            }
        }
    }

    // กดปุ่มเพิ่ม/ลดเสียงเพื่อถ่าย
    val latestShutter by rememberUpdatedState(onShutter)
    DisposableEffect(Unit) {
        registerVolumeKey { latestShutter() }
        onDispose { registerVolumeKey(null) }
    }

    fun flip() {
        if (recording || mode.isDual) return
        CameraSession.zoom = 1f
        lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        if (lens == CameraSelector.LENS_FACING_FRONT && (flash == ImageCapture.FLASH_MODE_AUTO || mode.isVideo)) {
            flash = ImageCapture.FLASH_MODE_OFF
        }
        sound.buzz()
    }

    fun setZoom(z: Float) {
        val target = z.coerceIn(minZoom, max(minZoom, maxZoom))
        engine.camera?.cameraControl?.setZoomRatio(target)
        zoom = target
        CameraSession.zoom = target
        CameraSession.zoomLens = lens
    }

    fun focus(p: Offset) {
        val frontBig = mode.isDual && dualSwap
        val cam = (if (frontBig) engine.camera2 else engine.camera) ?: return
        val view = if (frontBig) pipPreview else mainPreview
        val point = view.meteringPointFactory.createPoint(p.x, p.y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
        focusAt = p
    }

    /** แตะรูปมุมซ้ายล่าง → เปิดอัลบั้มในแอพ */
    fun openLast() = onOpenGallery()

    fun longPressLast() {
        val u = lastUri ?: return
        if (lastIsVideo) onOpenFrameGrab(u) else onEditUri(u)
    }

    fun cycleFlash() {
        flash = if (mode.isVideo || lens == CameraSelector.LENS_FACING_FRONT) {
            if (flash == ImageCapture.FLASH_MODE_OFF) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        } else {
            when (flash) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
        }
    }

    // ---------------- หน้าจอ ----------------
    val flashUsable = !mode.isDual && (lens == CameraSelector.LENS_FACING_BACK || !mode.isVideo)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {

            // แถบบน
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopIcon(flashIcon(flash), if (flash != ImageCapture.FLASH_MODE_OFF) Yellow else Color.White, enabled = flashUsable) {
                    cycleFlash()
                }
                TopIcon(timerIcon(timerSec), if (timerSec > 0) Yellow else Color.White, enabled = !recording) {
                    timerSec = when (timerSec) {
                        0 -> 3
                        3 -> 10
                        else -> 0
                    }
                }
                TopIcon(Icons.Filled.DateRange, if (stampOn) Orange else Color.White) {
                    if (isFilm) {
                        filmDate = !filmDate
                        prefs.filmDate = filmDate
                    } else {
                        dateStamp = !dateStamp
                        prefs.dateStamp = dateStamp
                    }
                }
                when (mode) {
                    CamMode.VIDEO -> TopTextPill(if (uhd) "4K" else "HD", uhd, enabled = !recording) {
                        uhd = !uhd
                        prefs.uhd = uhd
                    }
                    CamMode.PHOTO, CamMode.FILM -> if (instantFrame) {
                        TopTextPill("Instax", active = false, enabled = false) {}
                    } else {
                        TopTextPill(aspect.label, aspect != Aspect.R4_3) {
                            aspect = Aspect.entries[(aspect.ordinal + 1) % Aspect.entries.size]
                            prefs.aspect = aspect.name
                        }
                    }
                    CamMode.DUAL_VIDEO -> TopTextPill(dualLayout.label, dualLayout != DualLayout.SEPARATE, enabled = !recording) {
                        dualLayout = DualLayout.entries[(dualLayout.ordinal + 1) % DualLayout.entries.size]
                        prefs.dualLayout = dualLayout.name
                    }
                    CamMode.DUAL_PHOTO -> Unit
                }
                Spacer(Modifier.weight(1f))
                Box {
                    TopIcon(Icons.Filled.MoreVert) { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("อัลบั้มรูปของแอพ") },
                            leadingIcon = { Icon(Icons.Filled.PhotoAlbum, null) },
                            onClick = { menuOpen = false; onOpenGallery() },
                        )
                        DropdownMenuItem(
                            text = { Text("นำเข้ารูปจากแกลเลอรี่มาแต่ง") },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                            onClick = { menuOpen = false; onPickPhoto() },
                        )
                        DropdownMenuItem(
                            text = { Text("แต่งหลายรูปพร้อมกัน (ใช้สูตร)") },
                            leadingIcon = { Icon(Icons.Filled.Collections, null) },
                            onClick = { menuOpen = false; onBatch() },
                        )
                        DropdownMenuItem(
                            text = { Text("แคปภาพจากวิดีโอ (ชัดเต็ม)") },
                            leadingIcon = { Icon(Icons.Filled.Movie, null) },
                            onClick = { menuOpen = false; onOpenFrameGrab(null) },
                        )
                        val lu = lastUri
                        if (lastIsVideo && lu != null) {
                            DropdownMenuItem(
                                text = { Text("แคปภาพจากวิดีโอที่เพิ่งอัด") },
                                leadingIcon = { Icon(Icons.Filled.Videocam, null) },
                                onClick = { menuOpen = false; onOpenFrameGrab(lu) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("ตั้งค่า (เสียง การสั่น และอื่น ๆ)") },
                            leadingIcon = { Icon(Icons.Filled.Settings, null) },
                            onClick = { menuOpen = false; settingsOpen = true },
                        )
                    }
                }
            }

            // พื้นที่พรีวิว
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val ratio = if (mode.isVideo) 9f / 16f else 3f / 4f
                Box(Modifier.align(Alignment.Center).aspectRatio(ratio).onSizeChanged { previewSize = it }) {
                    // โหมดคู่: แตะกรอบเล็กเพื่อสลับจอใหญ่ ลากเพื่อย้ายมุม
                    val frontBig = mode.isDual && dualSwap
                    val bigMod = Modifier.fillMaxSize().zIndex(-1f)
                    val pipMod = Modifier
                        .zIndex(5f)
                        .align(
                            when (pipCorner) {
                                PipCorner.TOP_START -> Alignment.TopStart
                                PipCorner.TOP_END -> Alignment.TopEnd
                                PipCorner.BOTTOM_START -> Alignment.BottomStart
                                PipCorner.BOTTOM_END -> Alignment.BottomEnd
                            },
                        )
                        .offset { IntOffset(pipDrag.x.roundToInt(), pipDrag.y.roundToInt()) }
                        .padding(12.dp)
                        .fillMaxWidth(0.32f)
                        .aspectRatio(3f / 4f)
                    val pipFrame = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(14.dp))
                    // อัดคู่แบบบน-ล่าง: พรีวิวแบ่งครึ่งจอจริง ให้ตรงกับไฟล์ที่ได้
                    val splitView = mode == CamMode.DUAL_VIDEO && dualLayout == DualLayout.SPLIT
                    val topHalf = Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.5f)
                    val bottomHalf = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f)
                    AndroidView(
                        factory = { mainPreview.detachFromParent() },
                        modifier = when {
                            splitView -> if (frontBig) bottomHalf else topHalf
                            frontBig -> pipMod.then(pipFrame)
                            else -> bigMod
                        },
                    )
                    glow?.let { g ->
                        Canvas(Modifier.fillMaxSize()) {
                            drawImage(
                                image = g,
                                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                blendMode = BlendMode.Screen,
                                filterQuality = FilterQuality.Low,
                            )
                        }
                    }
                    val cropTarget = when {
                        instantFrame -> 46f / 62f
                        aspectOn -> 1f / aspect.ratio
                        else -> ratio
                    }
                    Box(
                        Modifier.align(Alignment.Center)
                            .aspectRatio(cropTarget, matchHeightConstraintsFirst = cropTarget < ratio),
                    ) {
                        EffectOverlay(activeLook, activeIntensity, stampOn, Modifier.fillMaxSize())
                        if (grid) GridOverlay(Modifier.fillMaxSize())
                    }
                    if (aspectOn || instantFrame) AspectMask(cropTarget, Modifier.fillMaxSize())
                    Box(
                        Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { focus(it) }, onDoubleTap = { flip() })
                            }
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoomChange, _ ->
                                    if (zoomChange != 1f) setZoom(zoom * zoomChange)
                                }
                            },
                    )
                    focusAt?.let { FocusRing(it) }
                    if (mode.isDual) {
                        AndroidView(
                            factory = { pipPreview.detachFromParent() },
                            modifier = when {
                                splitView -> if (frontBig) topHalf else bottomHalf
                                frontBig -> bigMod
                                else -> pipMod.then(pipFrame)
                            },
                        )
                        if (splitView) {
                            Box(Modifier.align(Alignment.Center).zIndex(6f).fillMaxWidth().height(2.dp).background(Color.White))
                        }
                        // ชั้นรับนิ้วบนจอรอง: แตะเพื่อสลับ ลากเพื่อย้ายมุม (แบบบน-ล่างลากไม่ได้)
                        Box(
                            (if (splitView) bottomHalf else pipMod).zIndex(6f)
                                .pointerInput(pipCorner, previewSize, splitView) {
                                    if (splitView) return@pointerInput
                                    detectDragGestures(
                                        onDrag = { change, amount ->
                                            change.consume()
                                            if (!recording) pipDrag += amount
                                        },
                                        onDragEnd = {
                                            val flipX = abs(pipDrag.x) > previewSize.width * 0.25f
                                            val flipY = abs(pipDrag.y) > previewSize.height * 0.25f
                                            val c = PipCorner.of(
                                                top = if (flipY) !pipCorner.isTop else pipCorner.isTop,
                                                start = if (flipX) !pipCorner.isStart else pipCorner.isStart,
                                            )
                                            pipCorner = c
                                            prefs.pipCorner = c.name
                                            pipDrag = Offset.Zero
                                        },
                                        onDragCancel = { pipDrag = Offset.Zero },
                                    )
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = {
                                        if (recording) {
                                            toast(context, "สลับจอระหว่างอัดไม่ได้")
                                        } else {
                                            dualSwap = !dualSwap
                                            prefs.dualSwap = dualSwap
                                        }
                                    })
                                },
                        )
                    }
                    if (recording) {
                        RecordBadge(recNanos, paused, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
                    }
                    if (isFilm) {
                        ShotCounter(shotsLeft, roll.shots, Modifier.align(Alignment.TopStart).padding(10.dp))
                        if (!showFilters) {
                            Text(
                                "🎞 " + roll.name,
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f))
                                    .clickable { showFilters = true }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    } else if (!look.isOriginal && !showFilters && !mode.isDual) {
                        Text(
                            (if (look.flash) "⚡ " else "") + look.name,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                                .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f))
                                .clickable { showFilters = true }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    if (countdown > 0) {
                        Text(
                            "$countdown",
                            color = Color.White,
                            fontSize = 110.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    if (blink) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    videoProgress?.let { p -> VideoProgressBar(videoLabel, p) { VideoJobs.skip() } }
                    if (showExposure && evMax > evMin) {
                        Row(
                            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Exposure, null, tint = Yellow)
                            Slider(
                                value = evIndex.toFloat(),
                                onValueChange = { v ->
                                    val i = v.roundToInt()
                                    if (i != evIndex) {
                                        evIndex = i
                                        CameraSession.ev = i
                                        engine.camera?.cameraControl?.setExposureCompensationIndex(i)
                                    }
                                },
                                valueRange = evMin.toFloat()..evMax.toFloat(),
                                steps = (evMax - evMin - 1).coerceAtLeast(0),
                                colors = yellowSlider(),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            Text(String.format(Locale.US, "%+.1f", evIndex * evStep), color = Color.White, fontSize = 12.sp)
                        }
                    }
                    ZoomChips(zoom, minZoom, maxZoom) { setZoom(it) }
                    if (showFilters && isFilm) {
                        RollPicker(
                            selected = roll,
                            border = filmBorder,
                            shotsLeft = shotsLeft,
                            leftOf = { r -> if (r.id == rollId) shotsLeft else prefs.shotsLeft(r.id) },
                            onRoll = { r ->
                                if (r.id != rollId) {
                                    switchRoll(r)
                                    filmBorderName = ""
                                    prefs.filmBorder = ""
                                    suggestFlash(r.flash)
                                }
                            },
                            onBorder = { b ->
                                filmBorderName = b.name
                                prefs.filmBorder = b.name
                            },
                            onReload = { loadRoll(roll) },
                        )
                    } else if (showFilters) {
                        Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(vertical = 8.dp)) {
                            if (!look.isOriginal) LabeledSlider("ความเข้ม", intensity, onIntensityChange)
                            if (nightLook && !mode.isVideo) {
                                LabeledSlider("แสงฟุ้ง", camHalation, {
                                    camHalation = it
                                    prefs.camHalation = it
                                })
                            }
                            FilterStrip(thumb = filterThumb, selectedId = lookId, onSelect = {
                                val changed = it.id != lookId
                                onLookChange(it.id)
                                if (changed) suggestFlash(it.flash)
                            })
                            if (mode.isVideo) {
                                Text(
                                    "วิดีโอ: ใส่สีฟิลเตอร์ให้หลังอัดเสร็จ (เกรน ขอบมืด แสงฟุ้ง ใส่เฉพาะรูปถ่าย)",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 14.dp, top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            ModeSelector(mode, dualOk, enabled = !recording && countdown == 0) { m ->
                mode = m
                if (m.isDual) flash = ImageCapture.FLASH_MODE_OFF
                if (m.isVideo && lens == CameraSelector.LENS_FACING_FRONT) flash = ImageCapture.FLASH_MODE_OFF
                if (m.isVideo && flash == ImageCapture.FLASH_MODE_AUTO) flash = ImageCapture.FLASH_MODE_OFF
            }

            // แถบล่าง: รูปล่าสุด | ฟิลเตอร์ | ชัตเตอร์ | ปรับแสง | สลับกล้อง
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (recording && mode == CamMode.VIDEO) {
                    RoundButton(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause) { togglePause() }
                } else {
                    LastThumbnail(lastThumb, processing > 0, onTap = { openLast() }, onLongPress = { longPressLast() })
                }
                // ปุ่มฟิลเตอร์อยู่ใกล้ชัตเตอร์ กดด้วยนิ้วโป้งได้ตอนถือมือเดียว
                SmallRoundButton(
                    if (isFilm) Icons.Filled.CameraRoll else Icons.Filled.PhotoFilter,
                    active = showFilters,
                    contentDescription = if (isFilm) "เลือกม้วนฟิล์ม" else "ฟิลเตอร์",
                ) { showFilters = !showFilters }
                ShutterButton(mode, recording, onShutter)
                SmallRoundButton(
                    Icons.Filled.Exposure,
                    active = showExposure,
                    enabled = evMax > evMin,
                    contentDescription = "ปรับแสง",
                ) { showExposure = !showExposure }
                if (recording && mode == CamMode.VIDEO) {
                    // ถ่ายภาพนิ่งความละเอียดเต็มระหว่างอัดวิดีโอ
                    SnapButton(enabled = engine.imageCapture != null) { takePhoto(silent = true) }
                } else {
                    RoundButton(Icons.Filled.FlipCameraAndroid, enabled = !mode.isDual && !recording) { flip() }
                }
            }
        }

        if (screenFlash) Box(Modifier.fillMaxSize().background(Color.White))
    }

    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("ตั้งค่า") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    SettingHeader("เสียงชัตเตอร์")
                    SoundMode.entries.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    soundMode = m
                                    prefs.soundMode = m.name
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = m == soundMode, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(m.label, fontSize = 15.sp)
                                Text(m.hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                    if (sound.forcedByLaw) {
                        Text(
                            "มือถือเครื่องนี้ต้องมีเสียงชัตเตอร์ตามกฎหมายของประเทศที่ขาย จึงปิดเสียงไม่ได้",
                            fontSize = 12.sp,
                            color = Orange,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    SettingSwitch("สั่นตอนกดถ่าย", vibrateOn) {
                        vibrateOn = it
                        prefs.vibrate = it
                        if (it) sound.buzz()
                    }
                    SettingHeader("การถ่าย")
                    SettingSwitch("เส้นตาราง", grid) { grid = it; prefs.grid = it }
                    SettingSwitch("กลับด้านรูปกล้องหน้าแบบกระจก", mirrorFront) { mirrorFront = it; prefs.mirrorFront = it }
                    SettingSwitch("บันทึกรูปต้นฉบับคู่กัน (โฟลเดอร์ Originals)", saveOriginal) {
                        saveOriginal = it
                        prefs.saveOriginal = it
                    }
                    SettingSwitch("ถ่ายคู่: เก็บภาพแยกหน้า-หลังด้วย", dualSaveSeparate) {
                        dualSaveSeparate = it
                        prefs.dualSaveSeparate = it
                    }
                    SettingSwitch("ฟิล์มหมดม้วน: ใส่ม้วนใหม่อัตโนมัติ", autoReload) { autoReload = it; prefs.autoReload = it }
                    SettingHeader("ประหยัดแบตเตอรี่")
                    SettingSwitch("พรีวิวแสงฟุ้งบนจอ (ปิดถ้าเครื่องร้อน)", glowPreview) {
                        glowPreview = it
                        prefs.setGlowPreview(it)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("เสร็จ") } },
        )
    }

    if (rollEmpty) {
        AlertDialog(
            onDismissRequest = { rollEmpty = false },
            title = { Text("ฟิล์มหมดม้วนแล้ว") },
            text = { Text("ถ่ายครบ ${roll.shots} ภาพแล้ว ใส่ม้วนใหม่เพื่อถ่ายต่อ") },
            confirmButton = {
                TextButton(onClick = {
                    loadRoll(roll)
                    rollEmpty = false
                }) { Text("ใส่ม้วนเดิมใหม่") }
            },
            dismissButton = {
                TextButton(onClick = {
                    rollEmpty = false
                    showFilters = true
                }) { Text("เปลี่ยนม้วน") }
            },
        )
    }
}

// ======================= ชิ้นส่วนของหน้ากล้อง =======================

private fun flashIcon(f: Int): ImageVector = when (f) {
    ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
    ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
    else -> Icons.Filled.FlashOff
}

private fun timerIcon(s: Int): ImageVector = when (s) {
    3 -> Icons.Filled.Timer3
    10 -> Icons.Filled.Timer10
    else -> Icons.Filled.TimerOff
}

private fun fmtZoom(z: Float): String {
    val r = (z * 10f).roundToInt() / 10f
    return if (abs(r - r.roundToInt()) < 0.01f) r.roundToInt().toString() else String.format(Locale.US, "%.1f", r)
}

@Composable
private fun ZoomChips(zoom: Float, minZoom: Float, maxZoom: Float, onSelect: (Float) -> Unit) {
    val presets = remember(minZoom, maxZoom) {
        buildList {
            if (minZoom < 0.95f) add(minZoom)
            listOf(1f, 2f, 3f, 4f, 5f).filter { it <= maxZoom + 0.01f }.forEach { add(it) }
        }
    }
    if (presets.size < 2) return
    val active = presets.lastOrNull { it <= zoom + 0.05f } ?: presets.first()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEach { p ->
                val selected = p == active
                Box(
                    Modifier.size(if (selected) 40.dp else 32.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { onSelect(p) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (selected) fmtZoom(zoom) + "×" else fmtZoom(p),
                        color = if (selected) Yellow else Color.White,
                        fontSize = if (selected) 12.sp else 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(current: CamMode, dualOk: Boolean, enabled: Boolean, onSelect: (CamMode) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
      Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        CamMode.entries.filter { !it.isDual || dualOk }.forEach { m ->
            val sel = m == current
            Text(
                m.label,
                color = when {
                    sel -> Yellow
                    enabled -> Color.White
                    else -> Color.White.copy(alpha = 0.35f)
                },
                fontSize = 14.sp,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled && !sel) { onSelect(m) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
      }
    }
}

@Composable
private fun ShutterButton(mode: CamMode, recording: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "shutter")
    val inner = if (mode.isVideo) Color(0xFFFF3B30) else Color.White
    Box(
        Modifier.size(80.dp).clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            drawCircle(Color.White, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
        }
        if (recording) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(inner))
        } else {
            Box(Modifier.size(64.dp).scale(scale).clip(CircleShape).background(inner))
        }
    }
}

@Composable
private fun SnapButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(54.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = if (enabled) 1f else 0.3f)))
    }
}

@Composable
private fun LastThumbnail(thumb: ImageBitmap?, busy: Boolean, onTap: () -> Unit, onLongPress: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    Box(
        Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(Panel)
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }, onLongPress = { longPress() }) },
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.PhotoLibrary, null, tint = Color.White)
        }
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Yellow, strokeWidth = 2.dp)
    }
}

@Composable
private fun RecordBadge(nanos: Long, paused: Boolean, modifier: Modifier) {
    Text(
        text = formatNanos(nanos) + if (paused) "  ⏸" else "",
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFFF3B30))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
    )
}

@Composable
private fun FocusRing(at: Offset) {
    val half = with(LocalDensity.current) { 36.dp.toPx() }
    Box(
        Modifier.offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
            .size(72.dp)
            .border(1.5.dp, Yellow, RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun GridOverlay(modifier: Modifier) {
    Canvas(modifier) {
        val c = Color.White.copy(alpha = 0.35f)
        val sw = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(c, Offset(x, 0f), Offset(x, size.height), sw)
            drawLine(c, Offset(0f, y), Offset(size.width, y), sw)
        }
    }
}

/** เอฟเฟกต์ที่ไม่ใช่สี (แสงรั่ว / เกรน / ขอบมืด / วันที่) แสดงทับพรีวิว */
@Composable
private fun EffectOverlay(look: FilmLook, intensity: Float, dateStamp: Boolean, modifier: Modifier) {
    val vignette = FilterEngine.vignetteOf(look, Adjustments.NONE, intensity)
    val leak = FilterEngine.leakOf(look, intensity)
    val grain = FilterEngine.grainOf(look, Adjustments.NONE, intensity)
    val pop = FilterEngine.flashPopOf(look, intensity)
    val noiseBrush = remember {
        ShaderBrush(ImageShader(FilterEngine.grainTile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    Box(modifier) {
        if (vignette > 0f || leak > 0f || grain > 0f || pop > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                if (pop > 0f) {
                    drawRect(
                        brush = Brush.radialGradient(
                            0f to Color.White.copy(alpha = (pop * 0.67f).coerceIn(0f, 1f)),
                            0.45f to Color.White.copy(alpha = (pop * 0.22f).coerceIn(0f, 1f)),
                            1f to Color.Transparent,
                            center = Offset(size.width / 2f, size.height * 0.45f),
                            radius = size.minDimension * 0.75f,
                        ),
                        blendMode = BlendMode.Screen,
                    )
                }
                if (leak > 0f) {
                    drawRect(
                        brush = Brush.radialGradient(
                            0f to Color(1f, 0.43f, 0.12f, leak * 0.86f),
                            0.45f to Color(1f, 0.24f, 0.24f, leak * 0.43f),
                            1f to Color.Transparent,
                            center = Offset(size.width * 0.95f, size.height * 0.05f),
                            radius = size.maxDimension * 0.8f,
                        ),
                        blendMode = BlendMode.Screen,
                    )
                }
                if (vignette > 0f) {
                    drawRect(
                        brush = Brush.radialGradient(
                            0f to Color.Transparent,
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = (vignette * 0.92f).coerceIn(0f, 1f)),
                            center = center,
                            radius = hypot(size.width / 2f, size.height / 2f),
                        ),
                    )
                }
                if (grain > 0f) {
                    drawRect(brush = noiseBrush, alpha = (grain * 0.55f).coerceIn(0f, 1f), blendMode = BlendMode.Overlay)
                }
            }
        }
        if (dateStamp) DateStampLabel(Modifier.align(Alignment.BottomEnd).padding(14.dp))
    }
}

@Composable
private fun ShotCounter(left: Int, total: Int, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            String.format(Locale.US, "%02d", left),
            color = if (left > 0) Orange else Color(0xFFFF3B30),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )
        Text("เหลือ/$total", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
    }
}

@Composable
private fun RollPicker(
    selected: FilmRoll,
    leftOf: (FilmRoll) -> Int,
    border: Border,
    shotsLeft: Int,
    onRoll: (FilmRoll) -> Unit,
    onBorder: (Border) -> Unit,
    onReload: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(vertical = 8.dp)) {
        Text(
            "เลือกม้วนฟิล์ม",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(FilmRolls.all, key = { it.id }) { r -> RollCard(r, r.id == selected.id, leftOf(r)) { onRoll(r) } }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(Border.entries) { b -> Pill(b.label, b == border) { onBorder(b) } }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "เหลือ $shotsLeft จาก ${selected.shots} ภาพ",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReload) { Text("ใส่ม้วนใหม่", color = Yellow) }
        }
    }
}

@Composable
private fun RollCard(roll: FilmRoll, selected: Boolean, left: Int, onClick: () -> Unit) {
    val bg = Color(roll.color)
    val fg = if (bg.luminance() > 0.5f) Color.Black else Color.White
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(72.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(width = 56.dp, height = 70.dp).clip(shape).background(bg)
                .border(if (selected) 2.dp else 0.5.dp, if (selected) Yellow else Color.White.copy(alpha = 0.3f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${roll.iso}", color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                // -1 = ยังไม่เคยใส่ม้วนนี้ → แสดงจำนวนเต็มม้วน
                Text(
                    if (left in 0 until roll.shots) "$left/${roll.shots}" else "${roll.shots} ภาพ",
                    color = fg.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                )
                val icons = (if (roll.flash) "⚡" else "") + (if (roll.night) "🌙" else "")
                if (icons.isNotEmpty()) Text(icons, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            roll.name,
            color = if (selected) Yellow else Color.White,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** แถบสถานะตอนใส่ฟิลเตอร์/รวมวิดีโอ พร้อมปุ่มข้าม (เก็บไฟล์ต้นฉบับแทน) */
@Composable
private fun VideoProgressBar(label: String, percent: Int, onSkip: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label  $percent%", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onSkip) { Text("ข้าม · เก็บต้นฉบับ", color = Yellow, fontSize = 12.sp) }
        }
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = Yellow,
            trackColor = Color.White.copy(alpha = 0.2f),
        )
    }
}

/** แถบดำบังส่วนที่จะถูกครอปตามสัดส่วนภาพ */
@Composable
private fun AspectMask(target: Float, modifier: Modifier) {
    Canvas(modifier) {
        val cur = size.width / size.height
        val shade = Color.Black.copy(alpha = 0.82f)
        if (abs(target - cur) < 0.01f) return@Canvas
        if (target < cur) {
            val w = size.height * target
            val x = (size.width - w) / 2f
            drawRect(shade, Offset.Zero, GeoSize(x, size.height))
            drawRect(shade, Offset(x + w, 0f), GeoSize(x, size.height))
        } else {
            val h = size.width / target
            val y = (size.height - h) / 2f
            drawRect(shade, Offset.Zero, GeoSize(size.width, y))
            drawRect(shade, Offset(0f, y + h), GeoSize(size.width, y))
        }
    }
}

@Composable
private fun SmallRoundButton(
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (active) Yellow.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.3f)
                active -> Yellow
                else -> Color.White
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SettingHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Yellow,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** ค่าหน้ากล้องที่ต้องคงไว้ตอนไปหน้าอื่นแล้วกลับมา (อยู่ตลอดที่แอพยังเปิดอยู่) */
private object CameraSession {
    var mode: CamMode = CamMode.PHOTO
    var lens: Int = CameraSelector.LENS_FACING_BACK
    var flash: Int = ImageCapture.FLASH_MODE_OFF
    var timer: Int = 0
    var zoom: Float = 1f
    var zoomLens: Int = CameraSelector.LENS_FACING_BACK
    var ev: Int = 0
}
