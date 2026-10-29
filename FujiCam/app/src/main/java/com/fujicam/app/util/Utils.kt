package com.fujicam.app.util

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fujicam.app.filters.Recipe
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun formatNanos(nanos: Long): String {
    val s = nanos / 1_000_000_000L
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

/** บันทึกไฟล์ลงแกลเลอรี่ (Pictures/FujiCam และ Movies/FujiCam) */
object MediaSaver {
    const val FOLDER = "FujiCam"
    const val ORIGINALS = "FujiCam/Originals"
    private val seq = AtomicInteger()

    private fun fileName(prefix: String, ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${prefix}_${ts}_${seq.incrementAndGet()}.$ext"
    }

    fun saveBitmap(
        context: Context,
        bmp: Bitmap,
        png: Boolean = false,
        quality: Int = 95,
        prefix: String = "FJC",
        folder: String = FOLDER,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(prefix, if (png) "png" else "jpg"))
            put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { os ->
                bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, os)
            } ?: throw IOException("open stream failed")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun saveVideo(context: Context, file: File): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName("FJC_VID", "mp4"))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 16) }
            } ?: throw IOException("open stream failed")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}

object BitmapIO {
    /**
     * โหลดรูปจาก Uri (หมุนตาม EXIF ให้อัตโนมัติ)
     * maxDim = 0 คือความละเอียดเต็ม แต่จำกัดไม่เกิน maxPixels กันแรมเต็ม
     */
    fun load(context: Context, uri: Uri, maxDim: Int = 0, maxPixels: Long = 24_000_000L): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            var scale = 1f
            if (maxDim > 0 && max(w, h) > maxDim) scale = maxDim.toFloat() / max(w, h)
            val px = w.toLong() * h
            if (px * scale * scale > maxPixels) scale = sqrt(maxPixels.toFloat() / px)
            if (scale < 1f) {
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            }
        }
    }

    /** หมุน/กลับด้านภาพ */
    fun transform(src: Bitmap, rotation: Int, mirror: Boolean, recycleSource: Boolean): Bitmap {
        if (rotation % 360 == 0 && !mirror) return src
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (recycleSource && out !== src) src.recycle()
        return out
    }

    fun scaleToMax(src: Bitmap, maxDim: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxDim) return src
        val s = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true)
    }

    /** วันที่ถ่ายจาก EXIF (ใช้กับตัวเลขวันที่สีส้ม) */
    fun exifDate(context: Context, uri: Uri): Long? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val s = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            s?.let { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }
        }
    } catch (e: Exception) {
        null
    }
}

/** จำค่าตั้งค่าไว้ใช้ครั้งหน้า */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("fujicam", Context.MODE_PRIVATE)

    var lookId: String
        get() = sp.getString("look", "provia") ?: "provia"
        set(v) = sp.edit().putString("look", v).apply()
    var intensity: Float
        get() = sp.getFloat("intensity", 1f)
        set(v) = sp.edit().putFloat("intensity", v).apply()
    var dateStamp: Boolean
        get() = sp.getBoolean("dateStamp", false)
        set(v) = sp.edit().putBoolean("dateStamp", v).apply()
    var grid: Boolean
        get() = sp.getBoolean("grid", false)
        set(v) = sp.edit().putBoolean("grid", v).apply()
    var mirrorFront: Boolean
        get() = sp.getBoolean("mirrorFront", true)
        set(v) = sp.edit().putBoolean("mirrorFront", v).apply()
    var uhd: Boolean
        get() = sp.getBoolean("uhd", false)
        set(v) = sp.edit().putBoolean("uhd", v).apply()

    // โหมดกล้องฟิล์ม
    var rollId: String
        get() = sp.getString("rollId", "quicksnap") ?: "quicksnap"
        set(v) = sp.edit().putString("rollId", v).apply()
    /** จำนวนภาพที่เหลือ แยกตามม้วน (-1 = ยังไม่เคยใส่ม้วนนี้) */
    fun shotsLeft(rollId: String): Int {
        val v = sp.getInt("shotsLeft_$rollId", -1)
        if (v >= 0) return v
        // ย้ายค่าจากเวอร์ชันก่อนที่เก็บรวมตัวเดียว มาให้ม้วนที่ใช้อยู่
        return if (rollId == this.rollId) sp.getInt("shotsLeft", -1) else -1
    }

    fun setShotsLeft(rollId: String, v: Int) = sp.edit().putInt("shotsLeft_$rollId", v).apply()

    var autoReload: Boolean
        get() = sp.getBoolean("autoReload", false)
        set(v) = sp.edit().putBoolean("autoReload", v).apply()
    /** ว่าง = ใช้ขอบตั้งต้นของม้วน */
    var filmBorder: String
        get() = sp.getString("filmBorder", "") ?: ""
        set(v) = sp.edit().putString("filmBorder", v).apply()
    var filmDate: Boolean
        get() = sp.getBoolean("filmDate", true)
        set(v) = sp.edit().putBoolean("filmDate", v).apply()

    // v1.2
    var favorites: Set<String>
        get() = HashSet(sp.getStringSet("favorites", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("favorites", HashSet(v)).apply()
    var aspect: String
        get() = sp.getString("aspect", "R4_3") ?: "R4_3"
        set(v) = sp.edit().putString("aspect", v).apply()
    var saveOriginal: Boolean
        get() = sp.getBoolean("saveOriginal", false)
        set(v) = sp.edit().putBoolean("saveOriginal", v).apply()
    var dualLayout: String
        get() = sp.getString("dualLayout", "SEPARATE") ?: "SEPARATE"
        set(v) = sp.edit().putString("dualLayout", v).apply()
    var camHalation: Float
        get() = sp.getFloat("camHalation", 0f)
        set(v) = sp.edit().putFloat("camHalation", v).apply()

    // v1.3
    var dualSaveSeparate: Boolean
        get() = sp.getBoolean("dualSaveSeparate", false)
        set(v) = sp.edit().putBoolean("dualSaveSeparate", v).apply()
    /** ค่าตั้งต้น: ปิดในมือถือแรมน้อย เพื่อไม่ให้เครื่องร้อน */
    fun glowPreview(default: Boolean): Boolean = sp.getBoolean("glowPreview", default)
    fun setGlowPreview(v: Boolean) = sp.edit().putBoolean("glowPreview", v).apply()
    var pipCorner: String
        get() = sp.getString("pipCorner", "TOP_START") ?: "TOP_START"
        set(v) = sp.edit().putString("pipCorner", v).apply()
    var dualSwap: Boolean
        get() = sp.getBoolean("dualSwap", false)
        set(v) = sp.edit().putBoolean("dualSwap", v).apply()

    /** รูปเก่าที่ผู้ใช้เลือกกลับเข้าอัลบั้มผ่านตัวเลือกรูปของระบบ (ไม่ต้องขอสิทธิ์อ่านรูปทั้งเครื่อง) */
    var importedMedia: Set<String>
        get() = HashSet(sp.getStringSet("importedMedia", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("importedMedia", HashSet(v)).apply()

    // v1.3.1 เสียงและการสั่น (ผู้ใช้เลือกเอง)
    var soundMode: String
        get() = sp.getString("soundMode", "FOLLOW") ?: "FOLLOW"
        set(v) = sp.edit().putString("soundMode", v).apply()
    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()

    fun recipe(slot: Int): Recipe? = Recipe.fromJson(sp.getString("recipe$slot", null))
    fun saveRecipe(slot: Int, r: Recipe) = sp.edit().putString("recipe$slot", r.toJson()).apply()
}

/** รูปและวิดีโอที่แอพนี้บันทึกไว้ (Pictures/FujiCam และ Movies/FujiCam) */
object AppMedia {
    data class Item(
        val uri: Uri,
        val isVideo: Boolean,
        val name: String,
        val added: Long,
        val isOriginal: Boolean,
        /** เพิ่มเข้ามาเองผ่านตัวเลือกรูป (ลบออกจากอัลบั้มได้ แต่ไม่ลบไฟล์) */
        val imported: Boolean = false,
    ) {
        val isFilm: Boolean get() = name.startsWith("FJC_FILM")
    }

    private val ROLL_IN_NAME = Regex("^FJC_FILM_(.+?)_\\d{8}_\\d{6}")

    /** รหัสม้วนฟิล์มจากชื่อไฟล์ (null = ไฟล์จากเวอร์ชันเก่าที่ไม่ได้เก็บไว้) */
    fun rollIdOf(item: Item): String? = ROLL_IN_NAME.find(item.name)?.groupValues?.get(1)

    fun query(context: Context): List<Item> {
        val out = ArrayList<Item>()
        fun run(collection: Uri, path: String, video: Boolean) {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
            try {
                context.contentResolver.query(
                    collection, proj, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$path%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { c ->
                    val idI = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val addI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val pathI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        out += Item(
                            uri = ContentUris.withAppendedId(collection, c.getLong(idI)),
                            isVideo = video,
                            name = c.getString(nameI) ?: "",
                            added = c.getLong(addI),
                            isOriginal = (c.getString(pathI) ?: "").contains("Originals"),
                        )
                    }
                }
            } catch (e: Exception) {
                // อ่านไม่ได้ก็แสดงเท่าที่มี
            }
        }
        run(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${Environment.DIRECTORY_PICTURES}/${MediaSaver.FOLDER}/", false)
        run(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "${Environment.DIRECTORY_MOVIES}/${MediaSaver.FOLDER}/", true)
        return out.sortedWith(compareByDescending<Item> { it.added }.thenByDescending { it.name })
    }

    private val DATE_IN_NAME = Regex("_(\\d{8})_(\\d{6})_")

    /** อ่านรูปเก่าที่ผู้ใช้เพิ่มเข้ามา ตัวที่เปิดไม่ได้แล้ว (ถูกลบ/สิทธิ์หมด) จะถูกข้ามไป */
    fun imported(context: Context, uris: Set<String>): List<Item> = uris.mapNotNull { str ->
        try {
            val uri = Uri.parse(str)
            var name = ""
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: ""
            } ?: return@mapNotNull null
            val type = context.contentResolver.getType(uri) ?: ""
            // ไฟล์ของแอพเราตั้งชื่อด้วยวันเวลา ใช้เรียงลำดับได้
            val added = DATE_IN_NAME.find(name)?.let { m ->
                runCatching {
                    SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(m.groupValues[1] + m.groupValues[2])?.time?.div(1000)
                }.getOrNull()
            } ?: 0L
            Item(
                uri = uri,
                isVideo = type.startsWith("video"),
                name = name,
                added = added,
                isOriginal = name.startsWith("FJC_ORIG"),
                imported = true,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** รูป/วิดีโอล่าสุด (ไม่นับไฟล์ต้นฉบับ) */
    fun latest(context: Context): Item? = query(context).firstOrNull { !it.isOriginal }
}
