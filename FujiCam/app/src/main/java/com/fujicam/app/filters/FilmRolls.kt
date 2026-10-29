package com.fujicam.app.filters

/** ม้วนฟิล์มสำหรับโหมดกล้องฟิล์ม: จำนวนภาพจำกัด ขอบภาพ และแสงรั่วแบบสุ่ม */
class FilmRoll(
    val id: String,
    val name: String,
    val lookId: String,
    val shots: Int,
    val iso: Int,
    val color: Long,
    val border: Border = Border.NONE,
    val flash: Boolean = false,
    val leakChance: Float = 0.2f,
    val night: Boolean = false,
)

object FilmRolls {
    val all: List<FilmRoll> = listOf(
        FilmRoll("quicksnap", "กล้องใช้แล้วทิ้ง", "quicksnap", 27, 400, 0xFF1F8A4C, leakChance = 0.3f),
        FilmRoll("superia400", "Superia 400", "superia400", 36, 400, 0xFF2E7D32),
        FilmRoll("c200", "C200", "c200", 36, 200, 0xFF1565C0),
        FilmRoll("pro400h", "Pro 400H", "pro400h", 36, 400, 0xFF6A8CAF),
        FilmRoll("portra400", "Portra 400", "portra400", 36, 400, 0xFFC9A227),
        FilmRoll("gold200", "Gold 200", "gold200", 36, 200, 0xFFE0A800),
        FilmRoll("colorplus200", "ColorPlus 200", "colorplus200", 36, 200, 0xFFD64545, leakChance = 0.25f),
        FilmRoll("ektar100", "Ektar 100", "ektar100", 36, 100, 0xFFD9A21B, leakChance = 0.15f),
        FilmRoll("pro160ns", "Pro 160NS", "pro160ns", 36, 160, 0xFF7BAF9A),
        FilmRoll("cinestill400d", "CineStill 400D", "cinestill400d", 36, 400, 0xFFF2A541, leakChance = 0.2f),
        FilmRoll("aerochrome", "Aerochrome IR", "aerochrome", 36, 400, 0xFF8E244D, leakChance = 0.15f),
        FilmRoll("instax", "Instax Mini", "instax", 10, 800, 0xFFE8E4DA, border = Border.INSTANT, flash = true, leakChance = 0.1f),
        FilmRoll("neopan400", "Neopan 400", "neopan400", 36, 400, 0xFF424242),
        FilmRoll("natura1600", "Natura 1600", "natura1600", 36, 1600, 0xFF5D4037, night = true),
        FilmRoll("cinestill800t", "CineStill 800T", "cinestill800t", 36, 800, 0xFFB71C1C, night = true, leakChance = 0.15f),
        FilmRoll("party_flash", "ปาร์ตี้แฟลช", "party_flash", 27, 800, 0xFFAD1457, flash = true, night = true, leakChance = 0.3f),
        FilmRoll("flash_noir", "แฟลชขาวดำ", "flash_noir", 36, 400, 0xFF212121, flash = true, night = true),
    )

    fun byId(id: String): FilmRoll = all.firstOrNull { it.id == id } ?: all.first()
}
