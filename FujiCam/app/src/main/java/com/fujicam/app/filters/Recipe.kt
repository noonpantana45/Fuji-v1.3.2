package com.fujicam.app.filters

import android.util.Base64
import org.json.JSONObject
import kotlin.math.roundToInt

/** สูตรแต่งรูปของผู้ใช้ (C1–C5): ฟิลเตอร์ + ค่าปรับแต่ง + เอฟเฟกต์ + ขอบ */
data class Recipe(
    val lookId: String,
    val intensity: Float,
    val adj: Adjustments,
    val dateStamp: Boolean,
    val border: Border,
    /** null = คงสัดส่วนเดิมของรูป */
    val aspect: Aspect? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("look", lookId)
        put("int", intensity.toDouble())
        put("b", adj.brightness.toDouble())
        put("c", adj.contrast.toDouble())
        put("s", adj.saturation.toDouble())
        put("w", adj.warmth.toDouble())
        put("t", adj.tint.toDouble())
        put("f", adj.fade.toDouble())
        put("v", adj.vignette.toDouble())
        put("g", adj.grain.toDouble())
        put("h", adj.halation.toDouble())
        put("bl", adj.bloom.toDouble())
        put("date", dateStamp)
        put("border", border.name)
        put("aspect", aspect?.name ?: "")
    }.toString()

    /** ข้อความสำหรับแชร์ลงโซเชียล: สรุปสูตร + โค้ดที่คนอื่นคัดลอกไปวางในแอพได้ */
    fun toShareText(): String {
        val code = Base64.encodeToString(toJson().toByteArray(Charsets.UTF_8), B64)
        return "สูตรแต่งรูป FujiCam: ${summary()}\n$CODE_PREFIX$code"
    }

    /** สรุปสั้น ๆ เช่น "Classic Neg. 80% · สว่าง +20 · เกรน 30 · ขอบขาว" */
    fun summary(): String {
        val look = FilmLooks.byId(lookId)
        val parts = mutableListOf(look.name + if (!look.isOriginal && intensity < 0.995f) " ${pct(intensity)}%" else "")
        fun add(label: String, v: Float, signed: Boolean) {
            if (v != 0f) parts += label + " " + (if (signed && v > 0) "+" else "") + pct(v)
        }
        add("สว่าง", adj.brightness, true)
        add("คอนทราสต์", adj.contrast, true)
        add("อิ่มสี", adj.saturation, true)
        add("อุณหภูมิ", adj.warmth, true)
        add("เขียว-ม่วง", adj.tint, true)
        add("ซีด", adj.fade, false)
        add("เกรน", adj.grain, false)
        add("ขอบมืด", adj.vignette, false)
        add("แสงฟุ้งแดง", adj.halation, false)
        add("แสงฟุ้ง", adj.bloom, false)
        if (dateStamp) parts += "วันที่"
        if (border != Border.NONE) parts += border.label
        if (aspect != null) parts += "สัดส่วน " + aspect.label
        return parts.joinToString(" · ")
    }

    companion object {
        const val CODE_PREFIX = "FJR1:"
        private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        private val CODE_REGEX = Regex("FJR1:([A-Za-z0-9_-]+)")

        private fun pct(v: Float) = (v * 100).roundToInt()

        /** อ่านโค้ดสูตรจากข้อความที่วาง (มีข้อความอื่นปนได้) */
        fun fromShareText(text: CharSequence?): Recipe? {
            val m = text?.let { CODE_REGEX.find(it) } ?: return null
            return try {
                fromJson(String(Base64.decode(m.groupValues[1], B64), Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }

        fun fromJson(s: String?): Recipe? {
            if (s.isNullOrBlank()) return null
            return try {
                val o = JSONObject(s)
                fun f(key: String) = o.optDouble(key, 0.0).toFloat()
                Recipe(
                    lookId = o.optString("look", FilmLooks.ORIGINAL_ID),
                    intensity = o.optDouble("int", 1.0).toFloat(),
                    adj = Adjustments(
                        brightness = f("b"), contrast = f("c"), saturation = f("s"), warmth = f("w"), tint = f("t"),
                        fade = f("f"), vignette = f("v"), grain = f("g"), halation = f("h"), bloom = f("bl"),
                    ),
                    dateStamp = o.optBoolean("date", false),
                    border = Border.entries.firstOrNull { it.name == o.optString("border") } ?: Border.NONE,
                    aspect = Aspect.entries.firstOrNull { it.name == o.optString("aspect") },
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
