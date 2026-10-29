package com.fujicam.app.filters

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** ค่าปรับแต่งเพิ่มเติมจากผู้ใช้ (ใช้ในหน้าแต่งรูป) */
data class Adjustments(
    val brightness: Float = 0f, // -1..1
    val contrast: Float = 0f,   // -1..1
    val saturation: Float = 0f, // -1..1
    val warmth: Float = 0f,     // -1..1
    val tint: Float = 0f,       // -1..1
    val fade: Float = 0f,       // 0..1
    val vignette: Float = 0f,   // 0..1
    val grain: Float = 0f,      // 0..1
    val halation: Float = 0f,   // 0..1 แสงฟุ้งสีแดงรอบดวงไฟ
    val bloom: Float = 0f,      // 0..1 แสงฟุ้งสีขาว
) {
    val isNeutral: Boolean get() = this == NONE

    companion object {
        val NONE = Adjustments()
    }
}

enum class Border(val label: String) {
    NONE("ไม่มีขอบ"),
    PRINT("ขอบขาว"),
    INFO("ขอบขาว+ข้อมูลฟิล์ม"),
    STRIP("แถบฟิล์ม"),
    INSTANT("อินสแตนท์"),
}

/** มุมที่วางกรอบเล็กในโหมดถ่ายคู่/อัดคู่ */
enum class PipCorner {
    TOP_START, TOP_END, BOTTOM_START, BOTTOM_END;

    val isTop: Boolean get() = this == TOP_START || this == TOP_END
    val isStart: Boolean get() = this == TOP_START || this == BOTTOM_START

    companion object {
        fun of(top: Boolean, start: Boolean): PipCorner = when {
            top && start -> TOP_START
            top -> TOP_END
            start -> BOTTOM_START
            else -> BOTTOM_END
        }
    }
}

/** สัดส่วนภาพ (ด้านยาว : ด้านสั้น) */
enum class Aspect(val label: String, val ratio: Float) {
    R4_3("4:3", 4f / 3f),
    R3_2("3:2", 3f / 2f),
    R1_1("1:1", 1f),
    R16_9("16:9", 16f / 9f),
    XPAN("65:24", 65f / 24f),
}

/** ความไม่สมบูรณ์แบบสุ่มของฟิล์มแต่ละภาพ (ใช้ในโหมดกล้องฟิล์ม) */
data class FilmFx(
    val exposure: Float = 0f,
    val leak: Float = 0f,
    val leakX: Float = 1f,
    val leakY: Float = 0f,
    val leakColor: Int = Color.rgb(255, 110, 30),
)

object FilterEngine {

    // ---------------- เมทริกซ์สี ----------------

    fun adjustMatrix(a: Adjustments): FloatArray {
        var m = CM.identity()
        if (a.saturation != 0f) m = CM.concat(m, CM.saturation(1f + a.saturation))
        if (a.warmth != 0f || a.tint != 0f) m = CM.concat(m, CM.temperature(a.warmth, a.tint))
        if (a.contrast != 0f) m = CM.concat(m, CM.contrast(1f + a.contrast * 0.5f))
        if (a.brightness != 0f) {
            val o = a.brightness * 64f
            m = CM.concat(m, CM.offset(o, o, o))
        }
        if (a.fade != 0f) m = CM.concat(m, CM.fade(a.fade * 0.2f))
        return m
    }

    /** เมทริกซ์สุดท้าย = ฟิลเตอร์ (ตามความเข้ม) แล้วตามด้วยค่าปรับแต่ง */
    fun matrixFor(look: FilmLook, adj: Adjustments, intensity: Float): FloatArray {
        val base = if (look.isOriginal) CM.identity() else CM.lerp(CM.identity(), look.matrix, intensity)
        return if (adj.isNeutral) base else CM.concat(base, adjustMatrix(adj))
    }

    fun grainOf(look: FilmLook, adj: Adjustments, intensity: Float) = (look.grain * intensity + adj.grain).coerceIn(0f, 1f)
    fun vignetteOf(look: FilmLook, adj: Adjustments, intensity: Float) = (look.vignette * intensity + adj.vignette).coerceIn(0f, 1f)
    fun leakOf(look: FilmLook, intensity: Float) = (look.leak * intensity).coerceIn(0f, 1f)
    fun flashPopOf(look: FilmLook, intensity: Float) = (look.flashPop * intensity).coerceIn(0f, 1f)
    fun halationOf(look: FilmLook, adj: Adjustments, intensity: Float) = (look.halation * intensity + adj.halation).coerceIn(0f, 1f)
    fun bloomOf(look: FilmLook, adj: Adjustments, intensity: Float) = (look.bloom * intensity + adj.bloom).coerceIn(0f, 1f)

    /** ข้อความบนขอบข้อมูลฟิล์ม เช่น FUJICAM  •  CLASSIC NEG.  •  35mm */
    fun captionFor(look: FilmLook, iso: Int? = null, frame: Int? = null, total: Int? = null): String {
        val parts = mutableListOf("FUJICAM")
        if (!look.isOriginal) parts += look.name.uppercase(Locale.ROOT)
        parts += if (iso != null) "ISO $iso" else "35mm"
        if (frame != null && total != null) parts += "$frame/$total"
        return parts.joinToString("  •  ")
    }

    fun randomFilmFx(leakChance: Float, rnd: Random = Random()): FilmFx {
        val exposure = (rnd.nextGaussian() * 0.035).toFloat().coerceIn(-0.09f, 0.09f)
        if (rnd.nextFloat() >= leakChance) return FilmFx(exposure = exposure)
        val t = rnd.nextFloat()
        val pos = when (rnd.nextInt(4)) {
            0 -> Pair(t, -0.05f)
            1 -> Pair(1.05f, t)
            2 -> Pair(t, 1.05f)
            else -> Pair(-0.05f, t)
        }
        val colors = intArrayOf(
            Color.rgb(255, 110, 30), Color.rgb(255, 60, 40), Color.rgb(255, 170, 40), Color.rgb(255, 90, 120),
        )
        return FilmFx(exposure, 0.35f + rnd.nextFloat() * 0.45f, pos.first, pos.second, colors[rnd.nextInt(colors.size)])
    }

    /** แปลงเป็นเมทริกซ์ 4x4 (column-major, ค่า 0..1) สำหรับ Media3 RgbMatrix */
    fun toMedia3Matrix(cm: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (row in 0 until 3) {
            for (col in 0 until 3) out[col * 4 + row] = cm[row * 5 + col]
            out[3 * 4 + row] = cm[row * 5 + 4] / 255f
        }
        out[15] = 1f
        return out
    }

    // ---------------- เกรนฟิล์ม ----------------

    /** แผ่นเกรนสีเทากลาง (128) สุ่มแบบ gaussian ใช้ทั้งพรีวิวและรูปจริง */
    val grainTile: Bitmap by lazy {
        val n = 512
        val rnd = Random(7)
        val px = IntArray(n * n) {
            val v = (128 + rnd.nextGaussian() * 46).roundToInt().coerceIn(0, 255)
            Color.rgb(v, v, v)
        }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    // ---------------- เรนเดอร์ภาพ ----------------

    /** ใส่ฟิลเตอร์ให้รูปที่ความละเอียดเต็ม คืน Bitmap ใหม่ (ไม่แตะต้นฉบับ) */
    fun render(
        src: Bitmap,
        look: FilmLook,
        adj: Adjustments,
        intensity: Float,
        dateStamp: Boolean,
        time: Long = System.currentTimeMillis(),
        fx: FilmFx? = null,
        border: Border = Border.NONE,
        iso: Int = 400,
        frame: Int = 1,
        aspect: Float = 0f,
        caption: String = "",
    ): Bitmap {
        // ครอปก่อน (สัดส่วนภาพ / ขอบอินสแตนท์) เพื่อไม่ให้วันที่ถูกตัด
        val crop = when {
            border == Border.INSTANT -> instantCrop(src.width, src.height)
            aspect > 0f -> aspectCrop(src.width, src.height, aspect)
            else -> Rect(0, 0, src.width, src.height)
        }
        val w = crop.width()
        val h = crop.height()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        var m = matrixFor(look, adj, intensity)
        if (fx != null && fx.exposure != 0f) {
            val k = 1f + fx.exposure
            m = CM.concat(m, CM.scale(k, k, k))
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) }
        c.drawBitmap(src, crop, Rect(0, 0, w, h), paint)

        val pop = flashPopOf(look, intensity)
        if (pop > 0f) drawFlashPop(c, w, h, pop)
        val hal = halationOf(look, adj, intensity)
        val blm = bloomOf(look, adj, intensity)
        if (hal > 0f || blm > 0f) {
            glowLayer(out, hal, blm)?.let { g ->
                val gp = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
                c.drawBitmap(g, null, Rect(0, 0, w, h), gp)
                g.recycle()
            }
        }
        val leak = leakOf(look, intensity)
        if (leak > 0f) drawLeak(c, w, h, leak, w * 0.95f, h * 0.05f, Color.rgb(255, 110, 30))
        if (fx != null && fx.leak > 0f) drawLeak(c, w, h, fx.leak, w * fx.leakX, h * fx.leakY, fx.leakColor)
        val vig = vignetteOf(look, adj, intensity)
        if (vig > 0f) drawVignette(c, w, h, vig)
        val grain = grainOf(look, adj, intensity)
        if (grain > 0f) drawGrain(c, w, h, grain)
        if (dateStamp) drawDateStamp(c, w, h, time)

        if (border == Border.NONE) return out
        val framed = applyBorder(out, border, iso, frame, caption, time)
        if (framed !== out) out.recycle()
        return framed
    }

    /**
     * ชั้นแสงฟุ้ง (ขนาดเล็ก ให้ขยายแบบนุ่มตอนวาดทับด้วยโหมด SCREEN)
     * หาเฉพาะจุดที่สว่างมาก เช่น ดวงไฟ ป้ายนีออน แล้วเบลอออกเป็นวงแคบ + วงกว้าง
     */
    fun glowLayer(src: Bitmap, halation: Float, bloom: Float): Bitmap? {
        if (halation <= 0f && bloom <= 0f) return null
        val base = downTo(src, 480)
        val bright = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bright)
        if (halation > 0f) {
            val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(thresholdMatrix(1f, 0.3f, 0.12f, 170f, halation))
            }
            c.drawBitmap(base, 0f, 0f, p)
        }
        if (bloom > 0f) {
            val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(thresholdMatrix(1f, 0.96f, 0.9f, 190f, bloom * 0.8f))
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            }
            c.drawBitmap(base, 0f, 0f, p)
        }
        if (base !== src) base.recycle()

        val tight = downTo(bright, 120)
        if (tight !== bright) bright.recycle()
        val wide = downTo(tight, 30)
        val out = Bitmap.createBitmap(tight.width, tight.height, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        oc.drawBitmap(tight, 0f, 0f, null)
        val wp = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
        oc.drawBitmap(wide, null, Rect(0, 0, tight.width, tight.height), wp)
        if (wide !== tight) wide.recycle()
        tight.recycle()
        return out
    }

    /** ตัดเหลือเฉพาะส่วนที่สว่างกว่า t แล้วย้อมสี (r,g,b) */
    private fun thresholdMatrix(r: Float, g: Float, b: Float, t: Float, strength: Float): FloatArray {
        val k = 255f / (255f - t)
        val lr = 0.2126f * k
        val lg = 0.7152f * k
        val lb = 0.0722f * k
        val o = -t * k
        fun row(tint: Float): FloatArray {
            val s = tint * strength
            return floatArrayOf(lr * s, lg * s, lb * s, 0f, o * s)
        }
        return row(r) + row(g) + row(b) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
    }

    /** ย่อภาพทีละครึ่ง (เฉลี่ยพิกเซลจริง ไม่ข้ามจุดเล็ก ๆ) จนด้านยาวไม่เกิน maxSide */
    private fun downTo(src: Bitmap, maxSide: Int): Bitmap {
        var cur = src
        while (max(cur.width, cur.height) > maxSide * 2) {
            val next = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        if (max(cur.width, cur.height) > maxSide) {
            val s = maxSide.toFloat() / max(cur.width, cur.height)
            val next = Bitmap.createScaledBitmap(
                cur, max(1, (cur.width * s).roundToInt()), max(1, (cur.height * s).roundToInt()), true,
            )
            if (cur !== src) cur.recycle()
            cur = next
        }
        return cur
    }

    /** แสงแฟลชตกกระทบกลางภาพ แล้วค่อย ๆ จางออกขอบ */
    private fun drawFlashPop(c: Canvas, w: Int, h: Int, amount: Float) {
        val a = (amount * 170).roundToInt().coerceIn(0, 255)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2f, h * 0.45f, min(w, h) * 0.75f,
                intArrayOf(Color.argb(a, 255, 255, 255), Color.argb(a / 3, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun drawLeak(c: Canvas, w: Int, h: Int, amount: Float, cx: Float, cy: Float, color: Int) {
        val a = (amount * 220).roundToInt().coerceIn(0, 255)
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, max(w, h) * 0.8f,
                intArrayOf(Color.argb(a, r, g, b), Color.argb(a / 2, 255, 60, 60), Color.argb(0, r, g, b)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    // ---------------- ขอบภาพ ----------------

    private val paperColor = Color.rgb(246, 244, 238)

    /** สัดส่วนภาพอินสแตนท์: แนวตั้ง 46:62 แนวนอน 99:62 */
    private fun instantCrop(w: Int, h: Int): Rect {
        val ratio = if (h >= w) 46f / 62f else 99f / 62f
        val cw: Int
        val ch: Int
        if (w.toFloat() / h > ratio) {
            ch = h
            cw = (h * ratio).roundToInt()
        } else {
            cw = w
            ch = (w / ratio).roundToInt()
        }
        val x = (w - cw) / 2
        val y = (h - ch) / 2
        return Rect(x, y, x + cw, y + ch)
    }

    /** ครอปกลางภาพตามสัดส่วน (ด้านยาว:ด้านสั้น) โดยคงทิศแนวตั้ง/แนวนอนของภาพ */
    fun aspectCrop(w: Int, h: Int, ratio: Float): Rect {
        val target = if (h >= w) 1f / ratio else ratio // w/h ที่ต้องการ
        val cw: Int
        val ch: Int
        if (w.toFloat() / h > target) {
            ch = h
            cw = min(w, (h * target).roundToInt())
        } else {
            cw = w
            ch = min(h, (w / target).roundToInt())
        }
        val x = (w - cw) / 2
        val y = (h - ch) / 2
        return Rect(x, y, x + cw, y + ch)
    }

    private fun applyBorder(img: Bitmap, border: Border, iso: Int, frame: Int, caption: String, time: Long): Bitmap = when (border) {
        Border.NONE -> img
        Border.PRINT -> printBorder(img)
        Border.INFO -> infoBorder(img, caption.ifBlank { "FUJICAM  •  35mm" }, time)
        Border.INSTANT -> instantBorder(img)
        Border.STRIP -> if (img.height > img.width) {
            // ภาพแนวตั้ง: หมุนให้นอนก่อนทำแถบฟิล์ม แล้วหมุนกลับ
            val land = rotate(img, -90f)
            val strip = stripBorder(land, iso, frame)
            land.recycle()
            val back = rotate(strip, 90f)
            strip.recycle()
            back
        } else {
            stripBorder(img, iso, frame)
        }
    }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees) }, true)

    private fun printBorder(img: Bitmap): Bitmap {
        val m = (min(img.width, img.height) * 0.05f).roundToInt()
        val out = Bitmap.createBitmap(img.width + m * 2, img.height + m * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(paperColor)
        c.drawBitmap(img, m.toFloat(), m.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** ขอบขาว ด้านล่างหนา พิมพ์ชื่อโทนฟิล์มและวันที่ตัวเล็ก ๆ */
    private fun infoBorder(img: Bitmap, caption: String, time: Long): Bitmap {
        val base = min(img.width, img.height).toFloat()
        val side = (base * 0.045f).roundToInt()
        val bottom = (base * 0.16f).roundToInt()
        val out = Bitmap.createBitmap(img.width + side * 2, img.height + side + bottom, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(paperColor)
        c.drawBitmap(img, side.toFloat(), side.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 45, 45)
            textSize = base * 0.032f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.12f
            textAlign = Paint.Align.CENTER
        }
        val maxW = out.width * 0.9f
        val tw = p.measureText(caption)
        if (tw > maxW) p.textSize = p.textSize * maxW / tw
        val top = (img.height + side).toFloat()
        c.drawText(caption, out.width / 2f, top + bottom * 0.46f, p)
        val p2 = Paint(p).apply {
            textSize = p.textSize * 0.75f
            color = Color.rgb(130, 130, 130)
        }
        val date = SimpleDateFormat("yyyy.MM.dd  HH:mm", Locale.US).format(Date(time))
        c.drawText(date, out.width / 2f, top + bottom * 0.74f, p2)
        return out
    }

    private fun instantBorder(img: Bitmap): Bitmap {
        val base = min(img.width, img.height).toFloat()
        val side = (base * 0.085f).roundToInt()
        val top = (base * 0.12f).roundToInt()
        val bottom = (base * 0.34f).roundToInt()
        val out = Bitmap.createBitmap(img.width + side * 2, img.height + top + bottom, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(paperColor)
        c.drawBitmap(img, side.toFloat(), top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = max(1f, base * 0.003f)
        }
        c.drawRect(side.toFloat(), top.toFloat(), (side + img.width).toFloat(), (top + img.height).toFloat(), edge)
        return out
    }

    /** แถบฟิล์มเนกาทีฟ 35มม. พร้อมรูหนามเตยและตัวเลขเฟรม (สำหรับภาพแนวนอน) */
    private fun stripBorder(img: Bitmap, iso: Int, frame: Int): Bitmap {
        val s = img.height.toFloat()
        val band = (s * 0.23f).roundToInt()
        val gap = (s * 0.05f).roundToInt()
        val ow = img.width + gap * 2
        val oh = img.height + band * 2
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(18, 16, 14))
        c.drawBitmap(img, gap.toFloat(), band.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))

        val holeW = s * 0.117f
        val holeH = s * 0.0825f
        val pitch = s * 0.198f
        val edgeGap = s * 0.03f
        val r = holeH * 0.25f
        val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(236, 232, 224) }
        var x = pitch / 2f - holeW / 2f
        while (x < ow) {
            c.drawRoundRect(x, edgeGap, x + holeW, edgeGap + holeH, r, r, holePaint)
            c.drawRoundRect(x, oh - edgeGap - holeH, x + holeW, oh - edgeGap, r, r, holePaint)
            x += pitch
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 150, 60)
            textSize = s * 0.055f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val topBase = edgeGap + holeH + s * 0.075f
        val bottomBase = oh - edgeGap - holeH - s * 0.02f
        c.drawText("$iso", ow * 0.08f, topBase, text)
        c.drawText("$frame", ow * 0.55f, topBase, text)
        c.drawText("▶ $frame", ow * 0.1f, bottomBase, text)
        c.drawText("▶ ${frame}A", ow * 0.6f, bottomBase, text)
        return out
    }

    private fun drawVignette(c: Canvas, w: Int, h: Int, amount: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val a = (amount * 235).roundToInt().coerceIn(0, 255)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, hypot(cx, cy),
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(a, 0, 0, 0)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun drawGrain(c: Canvas, w: Int, h: Int, amount: Float) {
        // ขยายเกรนตามขนาดภาพ เพื่อให้ "ขนาดเม็ด" ดูเท่ากันทั้งพรีวิวและไฟล์จริง
        val s = max(1f, min(w, h) / 1100f)
        val shader = BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(s, s) })
        }
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
            alpha = (amount * 150).roundToInt().coerceIn(0, 255)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    // ---------------- ตัวเลขวันที่สีส้มแบบกล้องฟิล์ม ----------------

    fun dateStampText(time: Long): String = SimpleDateFormat("''yy MM dd", Locale.US).format(Date(time))

    fun drawDateStamp(c: Canvas, w: Int, h: Int, time: Long) {
        val size = min(w, h) * 0.045f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 150, 40)
            textSize = size
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            setShadowLayer(size * 0.35f, 0f, 0f, Color.argb(200, 255, 80, 0))
        }
        val margin = min(w, h) * 0.05f
        c.drawText(dateStampText(time), w - margin, h - margin, p)
    }

    fun drawDateStamp(bmp: Bitmap, time: Long) = drawDateStamp(Canvas(bmp), bmp.width, bmp.height, time)

    // ---------------- รวมภาพกล้องหน้า+หลัง ----------------

    /** ภาพใหญ่เต็มเฟรม + ภาพเล็กเป็นกรอบมนที่มุมที่เลือก */
    fun composeDual(big: Bitmap, small: Bitmap, corner: PipCorner = PipCorner.TOP_START): Bitmap {
        val out = big.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val pipW = w * 0.32f
        val pipH = pipW * small.height / small.width
        val m = w * 0.04f
        val left = if (corner.isStart) m else w - m - pipW
        val top = if (corner.isTop) m else h - m - pipH
        val rect = RectF(left, top, left + pipW, top + pipH)
        val r = pipW * 0.09f
        val border = w * 0.006f
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawRoundRect(
            RectF(rect.left - border, rect.top - border, rect.right + border, rect.bottom + border),
            r + border, r + border, borderPaint,
        )
        c.save()
        c.clipPath(Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) })
        c.drawBitmap(small, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        return out
    }
}
