package com.fujicam.app.filters

import kotlin.math.abs

/**
 * เมทริกซ์สี 4x5 แบบเดียวกับ android.graphics.ColorMatrix (ค่า offset อยู่ในช่วง 0..255)
 * ใช้ชุดเดียวกันทั้ง พรีวิวกล้อง / รูปถ่าย / วิดีโอ เพื่อให้สีตรงกันทุกที่
 */
object CM {
    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** ผลลัพธ์ = ใช้ a ก่อน แล้วตามด้วย b */
    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(20)
        for (i in 0 until 4) {
            for (j in 0 until 5) {
                var s = 0f
                for (k in 0 until 4) s += b[i * 5 + k] * a[k * 5 + j]
                if (j == 4) s += b[i * 5 + 4]
                r[i * 5 + j] = s
            }
        }
        return r
    }

    fun lerp(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        FloatArray(20) { a[it] + (b[it] - a[it]) * t }

    fun scale(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun offset(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, r,
        0f, 1f, 0f, 0f, g,
        0f, 0f, 1f, 0f, b,
        0f, 0f, 0f, 1f, 0f,
    )

    fun saturation(s: Float): FloatArray {
        val lr = 0.2126f * (1 - s)
        val lg = 0.7152f * (1 - s)
        val lb = 0.0722f * (1 - s)
        return floatArrayOf(
            lr + s, lg, lb, 0f, 0f,
            lr, lg + s, lb, 0f, 0f,
            lr, lg, lb + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun contrast(c: Float): FloatArray {
        val o = 128f * (1 - c)
        return floatArrayOf(
            c, 0f, 0f, 0f, o,
            0f, c, 0f, 0f, o,
            0f, 0f, c, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** warmth > 0 = อุ่น(ส้ม), < 0 = เย็น(ฟ้า) | tint > 0 = อมม่วง, < 0 = อมเขียว */
    fun temperature(warmth: Float, tint: Float): FloatArray = scale(
        1f + 0.16f * warmth + 0.04f * tint,
        1f + 0.02f * warmth - 0.10f * tint,
        1f - 0.16f * warmth + 0.04f * tint,
    )

    /** ยกจุดดำขึ้น ให้ภาพดูซีดแบบฟิล์ม */
    fun fade(f: Float): FloatArray {
        val k = 1f - f
        val o = 255f * f
        return floatArrayOf(
            k, 0f, 0f, 0f, o,
            0f, k, 0f, 0f, o,
            0f, 0f, k, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** ผสมช่องสี 3x3 (แถวละ R,G,B) */
    fun mixer(m: FloatArray): FloatArray = floatArrayOf(
        m[0], m[1], m[2], 0f, 0f,
        m[3], m[4], m[5], 0f, 0f,
        m[6], m[7], m[8], 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** ขาวดำ พร้อมน้ำหนักช่องสี (จำลองฟิลเตอร์เหลือง/แดง/เขียว ของฟิล์มขาวดำ) */
    fun mono(w: FloatArray): FloatArray {
        val s = w[0] + w[1] + w[2]
        val r = w[0] / s
        val g = w[1] / s
        val b = w[2] / s
        return floatArrayOf(
            r, g, b, 0f, 0f,
            r, g, b, 0f, 0f,
            r, g, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun isIdentity(m: FloatArray): Boolean {
        val id = identity()
        return m.indices.all { i -> abs(m[i] - id[i]) < (if (i % 5 == 4) 0.5f else 0.002f) }
    }
}
