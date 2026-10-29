package com.fujicam.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
// StaticOverlaySettings อยู่ใน common หรือ effect แล้วแต่เวอร์ชัน จึงดึงทั้งสองแพ็กเกจ
import androidx.media3.common.*
import androidx.media3.effect.*
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.fujicam.app.filters.CM
import com.fujicam.app.filters.FilterEngine
import com.fujicam.app.filters.PipCorner
import com.fujicam.app.util.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** วิธีบันทึกวิดีโอในโหมดอัดคู่ */
enum class DualLayout(val label: String) {
    SEPARATE("แยก 2 ไฟล์"),
    PIP("รวม PiP"),
    SPLIT("รวมบน-ล่าง"),
}

/** สถานะงานประมวลผลวิดีโอ (ทำทีละงาน) ให้หน้ากล้องแสดงแถบเปอร์เซ็นต์และปุ่มข้าม */
object VideoJobs {
    val mutex = Mutex()

    /** null = ไม่มีงาน, 0..100 = ความคืบหน้า */
    val progress = MutableStateFlow<Int?>(null)
    val label = MutableStateFlow("")

    @Volatile
    var skipRequested = false

    fun skip() {
        skipRequested = true
    }
}

/** สั่ง Transformer ทำงาน พร้อมรายงานเปอร์เซ็นต์ และหยุดได้เมื่อผู้ใช้กดข้าม */
@OptIn(UnstableApi::class)
private suspend fun runExport(context: Context, start: (Transformer) -> Unit): Boolean =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { cont ->
            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            lateinit var transformer: Transformer
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (VideoJobs.skipRequested) {
                        transformer.cancel()
                        cont.resume(false)
                        return
                    }
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        VideoJobs.progress.value = holder.progress
                    }
                    handler.postDelayed(this, 250)
                }
            }
            transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        Log.e("VideoExport", "export failed", exportException)
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            try {
                start(transformer)
            } catch (e: Exception) {
                Log.e("VideoExport", "start failed", e)
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
            handler.postDelayed(poll, 250)
            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                }
            }
        }
    }

@OptIn(UnstableApi::class)
private fun colorEffect(colorMatrix: FloatArray): Effect {
    val m = FilterEngine.toMedia3Matrix(colorMatrix)
    return object : RgbMatrix {
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = m
    }
}

@OptIn(UnstableApi::class)
object VideoFilter {
    suspend fun apply(context: Context, input: File, colorMatrix: FloatArray, output: File): Boolean {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(emptyList(), listOf(colorEffect(colorMatrix))))
            .build()
        return runExport(context) { it.start(item, output.absolutePath) }
    }
}

/**
 * รวมวิดีโอกล้องหน้า+หลังเป็นไฟล์เดียว (ทดลอง)
 * ลำดับที่ 0 = กล้องจอเล็ก (วาดอยู่ชั้นบน), ลำดับที่ 1 = กล้องจอใหญ่
 * swap = true คือกล้องหน้าเป็นจอใหญ่ / เสียงมาจากไฟล์กล้องหลังเสมอ
 */
@OptIn(UnstableApi::class)
object VideoMerge {
    suspend fun merge(
        context: Context,
        back: File,
        front: File,
        colorMatrix: FloatArray,
        split: Boolean,
        swap: Boolean,
        corner: PipCorner,
        output: File,
    ): Boolean {
        fun effectsFor(): List<Effect> {
            val list = ArrayList<Effect>()
            if (!CM.isIdentity(colorMatrix)) list += colorEffect(colorMatrix)
            // แบบบน-ล่าง: ตัดกลางภาพให้เหลือครึ่งความสูง เพื่อไม่ให้ภาพเบี้ยว
            if (split) list += Crop(-1f, 1f, -0.5f, 0.5f)
            return list
        }
        val frontItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(front)))
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), effectsFor()))
            .build()
        val backItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(back)))
            .setEffects(Effects(emptyList(), effectsFor()))
            .build()
        val smallItem = if (swap) backItem else frontItem
        val bigItem = if (swap) frontItem else backItem
        val ax = if (corner.isStart) -1f else 1f
        val ay = if (corner.isTop) 1f else -1f

        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size {
                if (inputSizes.size < 2) return inputSizes[0]
                val f = inputSizes[0]
                val b = inputSizes[1]
                return if (split) Size(even(max(f.width, b.width)), even(f.height + b.height)) else b
            }

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long) = overlayFor(inputId)

            private fun overlayFor(inputId: Int): StaticOverlaySettings = when {
                split && inputId == 1 -> StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, 0.5f).build()
                split -> StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.5f).build()
                inputId == 0 -> StaticOverlaySettings.Builder()
                    .setScale(0.32f, 0.32f)
                    .setOverlayFrameAnchor(ax, ay)
                    .setBackgroundFrameAnchor(ax * 0.92f, ay * 0.94f)
                    .build()
                else -> StaticOverlaySettings.Builder().build()
            }
        }

        val composition = Composition.Builder(
            listOf(EditedMediaItemSequence(listOf(smallItem)), EditedMediaItemSequence(listOf(bigItem))),
        )
            .setVideoCompositorSettings(settings)
            .experimentalSetForceAudioTrack(true)
            .build()
        return runExport(context) { it.start(composition, output.absolutePath) }
    }

    private fun even(v: Int) = if (v % 2 == 0) v else v + 1
}

/** หลังอัดเสร็จ: ใส่สีฟิลเตอร์ (ถ้ามี) แล้วบันทึกลงแกลเลอรี่ */
object VideoPipeline {
    suspend fun finish(context: Context, file: File, colorMatrix: FloatArray, applyFilter: Boolean = true): Uri? {
        var src = file
        if (applyFilter && !CM.isIdentity(colorMatrix)) {
            val out = File(context.cacheDir, "flt_" + file.name)
            val ok = VideoJobs.mutex.withLock {
                VideoJobs.skipRequested = false
                VideoJobs.label.value = "กำลังใส่ฟิลเตอร์ให้วิดีโอ"
                VideoJobs.progress.value = 0
                try {
                    VideoFilter.apply(context.applicationContext, file, colorMatrix, out)
                } finally {
                    VideoJobs.progress.value = null
                }
            }
            if (ok) src = out else out.delete() // ไม่สำเร็จ/กดข้าม → เก็บต้นฉบับแทน
        }
        return withContext(Dispatchers.IO) {
            val uri = MediaSaver.saveVideo(context, src)
            file.delete()
            if (src != file) src.delete()
            uri
        }
    }

    /**
     * จบงานอัดคู่: รวมเป็นไฟล์เดียวตาม layout ถ้ารวมไม่สำเร็จจะเก็บเป็น 2 ไฟล์แทน
     * คืนค่า (uri ที่บันทึก, รวมไฟล์สำเร็จไหม)
     */
    suspend fun finishDual(
        context: Context,
        back: File?,
        front: File?,
        colorMatrix: FloatArray,
        layout: DualLayout,
        swap: Boolean = false,
        corner: PipCorner = PipCorner.TOP_START,
    ): Pair<List<Uri?>, Boolean> {
        if (layout != DualLayout.SEPARATE && back != null && front != null) {
            val out = File(context.cacheDir, "merged_${System.currentTimeMillis()}.mp4")
            var skipped = false
            val ok = VideoJobs.mutex.withLock {
                VideoJobs.skipRequested = false
                VideoJobs.label.value = "กำลังรวมวิดีโอหน้า-หลัง"
                VideoJobs.progress.value = 0
                try {
                    VideoMerge.merge(
                        context.applicationContext, back, front, colorMatrix,
                        split = layout == DualLayout.SPLIT, swap = swap, corner = corner, output = out,
                    )
                } finally {
                    skipped = VideoJobs.skipRequested
                    VideoJobs.progress.value = null
                }
            }
            if (ok) {
                val uri = withContext(Dispatchers.IO) {
                    val u = MediaSaver.saveVideo(context, out)
                    out.delete()
                    back.delete()
                    front.delete()
                    u
                }
                return Pair(listOf(uri), true)
            }
            out.delete()
            // กดข้าม → เก็บไฟล์ต้นฉบับทันทีโดยไม่ใส่ฟิลเตอร์
            val uris = listOf(back, front).map { finish(context, it, colorMatrix, applyFilter = !skipped) }
            return Pair(uris, false)
        }
        return Pair(listOfNotNull(back, front).map { finish(context, it, colorMatrix) }, false)
    }
}

/**
 * ดึงเฟรมจากวิดีโอที่ความละเอียดเต็มของไฟล์ (ไม่ย่อ ไม่บีบอัดซ้ำ)
 * ใช้ MediaMetadataRetriever บนเธรดเดียวของตัวเอง
 */
class FrameGrabber(private val context: Context, private val uri: Uri) : Closeable {

    data class Info(val width: Int, val height: Int, val durationMs: Long, val frameCount: Int, val fps: Float) {
        val totalFrames: Int get() = if (frameCount > 0) frameCount else max(1, (durationMs / 1000f * fps).roundToInt())
        fun timeMsOf(index: Int): Long = (index * 1000.0 / fps).toLong()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val mmr = MediaMetadataRetriever()
    private var info: Info? = null
    private var indexAccessOk = true

    suspend fun open(): Info? = withContext(dispatcher) {
        try {
            mmr.setDataSource(context, uri)
            fun meta(key: Int) = mmr.extractMetadata(key)
            val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val count = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
            val fps = if (count > 0 && dur > 0) count * 1000f / dur
            else meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
            val swap = rot == 90 || rot == 270
            Info(if (swap) h else w, if (swap) w else h, dur, count, fps).also { info = it }
        } catch (e: Exception) {
            Log.e("FrameGrabber", "open failed", e)
            null
        }
    }

    suspend fun frameAt(index: Int): Bitmap? = withContext(dispatcher) {
        val inf = info ?: return@withContext null
        var bmp: Bitmap? = null
        // ใช้ index ก่อน (แม่นยำระดับเฟรม)
        if (inf.frameCount > 0 && indexAccessOk) {
            bmp = try {
                val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
                mmr.getFrameAtIndex(index.coerceIn(0, inf.frameCount - 1), params)
            } catch (e: Exception) {
                indexAccessOk = false
                null
            }
        }
        if (bmp == null) {
            val us = inf.timeMsOf(index) * 1000L
            bmp = try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
                    mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, params)
                } else {
                    mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                }
            } catch (e: Exception) {
                null
            }
        }
        bmp
    }

    override fun close() {
        executor.execute {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
        executor.shutdown()
    }
}
