package com.fujicam.app.filters

enum class Scene(val label: String) {
    DAY("☀️ กลางวัน"),
    NIGHT("🌙 กลางคืน"),
}

enum class LookGroup(val label: String, val scene: Scene) {
    FUJI_SIM("Film Simulation", Scene.DAY),
    FUJI_FILM("ฟิล์มฟูจิ", Scene.DAY),
    RECIPE("สูตรฟิล์ม", Scene.DAY),
    BW("ขาวดำ", Scene.DAY),
    CREATIVE("ครีเอทีฟ", Scene.DAY),
    FLASH("⚡ ยิงแฟลช", Scene.NIGHT),
    NIGHT_LIGHT("แสงไฟกลางคืน", Scene.NIGHT),
}

/**
 * นิยามฟิลเตอร์หนึ่งตัว ค่าทั้งหมดเป็นการ "เลียนแบบโทน" ไม่ใช่ LUT ทางการของ Fujifilm
 * mixer = ผสมช่องสี 3x3, shadow = เติมสีในเงา (0..255), mono = น้ำหนักขาวดำ, monoTone = ย้อมสีหลังขาวดำ
 * flash = ฟิลเตอร์ที่ออกแบบมาให้ใช้คู่กับแฟลช (แอพแค่แนะนำ ไม่เปิดแฟลชให้เอง), flashPop = จำลองแสงแฟลชตกกระทบกลางภาพ
 * halation = แสงฟุ้งสีแดงส้มรอบดวงไฟแบบฟิล์มจริง, bloom = แสงฟุ้งสีขาวรอบจุดสว่าง
 */
class FilmLook(
    val id: String,
    val name: String,
    val group: LookGroup,
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val fade: Float = 0f,
    val mixer: FloatArray? = null,
    val shadow: FloatArray? = null,
    val mono: FloatArray? = null,
    val monoTone: FloatArray? = null,
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val leak: Float = 0f,
    val flash: Boolean = false,
    val flashPop: Float = 0f,
    val halation: Float = 0f,
    val bloom: Float = 0f,
    /** ถ้ามีค่า = เป็นตัวเลือกย่อยของฟิลเตอร์นั้น (ไม่แสดงในแถบหลัก แสดงเป็นปุ่มย่อยเมื่อเลือกตัวแม่) */
    val parent: String? = null,
) {
    val isOriginal: Boolean get() = id == FilmLooks.ORIGINAL_ID
    val isMono: Boolean get() = mono != null

    /** เมทริกซ์ของฟิลเตอร์ที่ความเข้ม 100% */
    val matrix: FloatArray by lazy { buildMatrix() }

    private fun buildMatrix(): FloatArray {
        var m = CM.identity()
        mixer?.let { m = CM.concat(m, CM.mixer(it)) }
        if (mono != null) {
            m = CM.concat(m, CM.mono(mono))
            monoTone?.let { m = CM.concat(m, CM.scale(it[0], it[1], it[2])) }
        } else if (saturation != 1f) {
            m = CM.concat(m, CM.saturation(saturation))
        }
        if (warmth != 0f || tint != 0f) m = CM.concat(m, CM.temperature(warmth, tint))
        if (contrast != 1f) m = CM.concat(m, CM.contrast(contrast))
        if (brightness != 0f) m = CM.concat(m, CM.offset(brightness * 255f, brightness * 255f, brightness * 255f))
        if (fade > 0f) m = CM.concat(m, CM.fade(fade))
        shadow?.let { m = CM.concat(m, CM.offset(it[0], it[1], it[2])) }
        return m
    }
}

private fun f(vararg v: Float) = v

object FilmLooks {
    const val ORIGINAL_ID = "original"
    private val STD = f(0.30f, 0.59f, 0.11f)

    val ORIGINAL = FilmLook(ORIGINAL_ID, "ต้นฉบับ", LookGroup.FUJI_SIM)

    val all: List<FilmLook> = listOf(
        ORIGINAL,

        // ---------- Fujifilm Film Simulation ----------
        FilmLook("provia", "PROVIA", LookGroup.FUJI_SIM, saturation = 1.08f, contrast = 1.06f, warmth = 0.03f),
        FilmLook(
            "velvia", "Velvia", LookGroup.FUJI_SIM, saturation = 1.42f, contrast = 1.18f, warmth = 0.03f, tint = 0.05f,
            mixer = f(1.04f, -0.02f, -0.02f, -0.03f, 1.06f, -0.03f, -0.02f, -0.03f, 1.05f),
        ),
        FilmLook("astia", "ASTIA", LookGroup.FUJI_SIM, saturation = 1.12f, contrast = 0.94f, brightness = 0.02f, warmth = 0.05f, tint = 0.02f),
        FilmLook(
            "classic_chrome", "Classic Chrome", LookGroup.FUJI_SIM, saturation = 0.72f, contrast = 1.14f, warmth = -0.02f, fade = 0.03f,
            mixer = f(0.94f, 0.06f, 0f, 0.02f, 0.96f, 0.02f, 0f, 0.12f, 0.88f), shadow = f(-3f, 1f, 5f),
        ),
        FilmLook(
            "classic_neg", "Classic Neg.", LookGroup.FUJI_SIM, saturation = 0.85f, contrast = 1.22f, warmth = -0.04f, tint = -0.06f, fade = 0.025f,
            mixer = f(1.06f, 0f, -0.06f, 0.04f, 0.96f, 0f, 0f, 0.1f, 0.9f), shadow = f(-4f, 4f, 6f),
        ),
        FilmLook("nostalgic_neg", "Nostalgic Neg.", LookGroup.FUJI_SIM, saturation = 0.95f, contrast = 1.02f, warmth = 0.18f, tint = 0.02f, fade = 0.04f, shadow = f(8f, 3f, -4f)),
        FilmLook("pro_neg_hi", "PRO Neg. Hi", LookGroup.FUJI_SIM, contrast = 1.14f, warmth = 0.02f, parent = "pro_neg_std"),
        FilmLook("pro_neg_std", "PRO Neg. Std", LookGroup.FUJI_SIM, saturation = 0.9f, contrast = 0.92f, fade = 0.02f),
        FilmLook("eterna", "ETERNA", LookGroup.FUJI_SIM, saturation = 0.72f, contrast = 0.84f, warmth = -0.03f, tint = -0.03f, fade = 0.05f, shadow = f(0f, 4f, 6f)),
        FilmLook("eterna_bb", "ETERNA Bleach Bypass", LookGroup.FUJI_SIM, saturation = 0.38f, contrast = 1.35f, warmth = -0.02f, parent = "eterna"),
        FilmLook("reala_ace", "REALA ACE", LookGroup.FUJI_SIM, saturation = 1.05f, contrast = 1.07f),
        FilmLook("acros", "ACROS", LookGroup.FUJI_SIM, mono = STD, contrast = 1.2f, grain = 0.25f),
        FilmLook("acros_ye", "ACROS + Ye", LookGroup.FUJI_SIM, mono = f(0.42f, 0.52f, 0.06f), contrast = 1.22f, grain = 0.25f, parent = "acros"),
        FilmLook("acros_r", "ACROS + R", LookGroup.FUJI_SIM, mono = f(0.62f, 0.35f, 0.03f), contrast = 1.3f, grain = 0.25f, parent = "acros"),
        FilmLook("acros_g", "ACROS + G", LookGroup.FUJI_SIM, mono = f(0.22f, 0.72f, 0.06f), contrast = 1.18f, grain = 0.25f, parent = "acros"),
        FilmLook("monochrome", "Monochrome", LookGroup.FUJI_SIM, mono = STD, contrast = 1.05f, parent = "acros"),
        FilmLook("sepia", "Sepia", LookGroup.FUJI_SIM, mono = STD, monoTone = f(1.08f, 0.95f, 0.78f), fade = 0.03f),

        // ---------- ฟิล์มฟูจิ ----------
        FilmLook("superia400", "Superia 400", LookGroup.FUJI_FILM, saturation = 1.12f, contrast = 1.08f, warmth = 0.02f, tint = -0.08f, shadow = f(-2f, 4f, 2f), grain = 0.18f),
        FilmLook("c200", "Fujicolor C200", LookGroup.FUJI_FILM, saturation = 1.05f, contrast = 1.05f, warmth = 0.08f, tint = -0.04f, fade = 0.03f, grain = 0.15f),
        FilmLook(
            "pro400h", "Pro 400H", LookGroup.FUJI_FILM, saturation = 0.9f, contrast = 0.92f, brightness = 0.03f, warmth = -0.04f, tint = -0.05f, fade = 0.05f,
            mixer = f(0.97f, 0.03f, 0f, 0f, 1f, 0f, 0f, 0.05f, 0.95f), grain = 0.12f,
        ),
        FilmLook(
            "pro160ns", "Pro 160NS", LookGroup.FUJI_FILM, saturation = 0.94f, contrast = 0.9f, brightness = 0.04f, warmth = 0.03f,
            tint = 0.04f, fade = 0.04f, grain = 0.08f, parent = "pro400h",
        ),
        FilmLook("natura1600", "Natura 1600", LookGroup.FUJI_FILM, saturation = 0.95f, contrast = 1.05f, warmth = 0.06f, tint = -0.05f, fade = 0.03f, grain = 0.32f, halation = 0.25f),
        FilmLook("velvia50", "Velvia 50", LookGroup.FUJI_FILM, saturation = 1.6f, contrast = 1.25f, warmth = 0.03f, tint = 0.06f, vignette = 0.15f),
        FilmLook("provia100f", "Provia 100F", LookGroup.FUJI_FILM, saturation = 1.15f, contrast = 1.1f, warmth = -0.02f),
        FilmLook("industrial400", "Fuji 400 Industrial", LookGroup.FUJI_FILM, contrast = 1.1f, tint = -0.07f, shadow = f(-3f, 3f, 2f), grain = 0.25f, parent = "superia400"),
        FilmLook("neopan400", "Neopan 400", LookGroup.FUJI_FILM, mono = STD, contrast = 1.35f, grain = 0.35f),
        FilmLook("instax", "Instax Mini", LookGroup.FUJI_FILM, saturation = 0.88f, contrast = 0.9f, brightness = 0.04f, warmth = 0.06f, tint = 0.02f, fade = 0.08f, vignette = 0.25f),
        FilmLook("quicksnap", "QuickSnap ใช้แล้วทิ้ง", LookGroup.FUJI_FILM, saturation = 1.1f, contrast = 1.12f, warmth = 0.1f, tint = -0.04f, fade = 0.03f, grain = 0.3f, vignette = 0.35f),

        // ---------- สูตรฟิล์ม (Fuji X recipe style) ----------
        FilmLook("portra400", "Portra 400", LookGroup.RECIPE, saturation = 0.92f, contrast = 0.95f, brightness = 0.02f, warmth = 0.12f, tint = 0.03f, fade = 0.03f, grain = 0.15f),
        FilmLook(
            "kodachrome64", "Kodachrome 64", LookGroup.RECIPE, saturation = 1.2f, contrast = 1.2f, warmth = 0.08f,
            mixer = f(1.06f, -0.02f, -0.04f, -0.02f, 1.02f, 0f, -0.02f, -0.04f, 1.06f), shadow = f(4f, 0f, -3f),
        ),
        FilmLook("cinestill800t", "CineStill 800T", LookGroup.NIGHT_LIGHT, saturation = 0.95f, contrast = 1.1f, warmth = -0.22f, tint = 0.06f, grain = 0.3f, halation = 0.7f),
        FilmLook("gold200", "Kodak Gold 200", LookGroup.RECIPE, saturation = 1.12f, contrast = 1.05f, warmth = 0.18f, tint = -0.02f, grain = 0.15f),
        FilmLook("agfa_vista", "Agfa Vista", LookGroup.RECIPE, saturation = 1.15f, contrast = 1.08f, warmth = 0.06f, tint = 0.06f, parent = "kodachrome64"),
        FilmLook("pacific_blues", "Pacific Blues", LookGroup.RECIPE, saturation = 0.9f, contrast = 1.05f, warmth = -0.12f, fade = 0.04f),
        FilmLook("tokyo_night", "Tokyo Night", LookGroup.NIGHT_LIGHT, saturation = 1.05f, contrast = 1.15f, warmth = -0.15f, tint = 0.08f, shadow = f(0f, 0f, 8f), halation = 0.4f, bloom = 0.2f),
        FilmLook("vintage_chrome", "Vintage Chrome", LookGroup.RECIPE, saturation = 0.8f, contrast = 1.18f, warmth = 0.08f, fade = 0.04f, shadow = f(4f, 2f, -2f), grain = 0.18f),
        FilmLook(
            "cinestill400d", "CineStill 400D", LookGroup.RECIPE, saturation = 1.08f, contrast = 1.08f, warmth = 0.08f, tint = 0.02f,
            fade = 0.02f, grain = 0.2f, halation = 0.45f, bloom = 0.15f,
        ),
        FilmLook(
            "ektar100", "Kodak Ektar 100", LookGroup.RECIPE, saturation = 1.38f, contrast = 1.2f, warmth = 0.08f, tint = 0.04f,
            mixer = f(1.08f, -0.04f, -0.04f, -0.02f, 1.02f, 0f, -0.04f, -0.02f, 1.06f), grain = 0.06f,
        ),
        FilmLook(
            "colorplus200", "Kodak ColorPlus 200", LookGroup.RECIPE, saturation = 1.04f, contrast = 1.02f, warmth = 0.22f, tint = -0.03f,
            fade = 0.05f, shadow = f(6f, 4f, -4f), grain = 0.18f, parent = "gold200",
        ),
        FilmLook("hp5", "Ilford HP5", LookGroup.RECIPE, mono = f(0.3f, 0.6f, 0.1f), contrast = 1.15f, fade = 0.02f, grain = 0.4f),
        FilmLook("trix", "Tri-X 400", LookGroup.RECIPE, mono = f(0.32f, 0.58f, 0.1f), contrast = 1.4f, grain = 0.45f, parent = "hp5"),

        // ---------- ครีเอทีฟ ----------
        FilmLook("summer_leak", "Summer Leak", LookGroup.CREATIVE, saturation = 1.1f, warmth = 0.12f, fade = 0.04f, leak = 0.6f, grain = 0.15f, bloom = 0.15f, parent = "golden_hour"),
        FilmLook("faded", "Faded Memory", LookGroup.CREATIVE, saturation = 0.7f, contrast = 0.85f, warmth = 0.08f, fade = 0.12f, vignette = 0.3f),
        FilmLook(
            "teal_orange", "Teal & Orange", LookGroup.CREATIVE, saturation = 1.15f, contrast = 1.1f, warmth = 0.05f,
            mixer = f(1.08f, -0.04f, -0.04f, -0.02f, 1f, 0.02f, -0.06f, 0.06f, 1f), shadow = f(-6f, 2f, 8f),
        ),
        FilmLook("golden_hour", "Golden Hour", LookGroup.CREATIVE, saturation = 1.1f, brightness = 0.02f, warmth = 0.3f, tint = 0.04f, bloom = 0.2f),
        FilmLook("cool_matte", "Cool Matte", LookGroup.CREATIVE, saturation = 0.85f, warmth = -0.1f, fade = 0.08f),
        FilmLook("noir", "Noir", LookGroup.CREATIVE, mono = STD, contrast = 1.6f, brightness = -0.03f, vignette = 0.4f),
        FilmLook(
            "cross", "Cross Process", LookGroup.CREATIVE, saturation = 1.2f, contrast = 1.2f, tint = -0.1f,
            mixer = f(1.1f, 0f, -0.1f, 0f, 1.05f, 0f, 0.05f, 0f, 0.8f), shadow = f(0f, 0f, 18f), parent = "lomo",
        ),
        FilmLook(
            "aerochrome", "Aerochrome IR", LookGroup.CREATIVE, saturation = 1.25f, contrast = 1.15f,
            mixer = f(0.2f, 0.85f, -0.05f, 0.35f, 0.25f, 0.4f, 0.1f, -0.1f, 1f), grain = 0.22f, bloom = 0.2f,
        ),
        FilmLook("lomo", "Lomo", LookGroup.CREATIVE, saturation = 1.35f, contrast = 1.3f, warmth = 0.05f, vignette = 0.6f),
        FilmLook("dreamy", "Dreamy Pastel", LookGroup.CREATIVE, saturation = 0.8f, contrast = 0.82f, brightness = 0.06f, tint = 0.05f, fade = 0.1f, bloom = 0.35f, parent = "faded"),
        FilmLook("neon_night", "Neon Night", LookGroup.NIGHT_LIGHT, saturation = 1.3f, contrast = 1.15f, warmth = -0.2f, tint = 0.18f, shadow = f(4f, -2f, 10f), halation = 0.5f, bloom = 0.3f),

        // ---------- กลางคืน: ยิงแฟลช (ใช้คู่กับแฟลชที่ผู้ใช้เปิดเอง) ----------
        FilmLook("direct_flash", "แฟลชตรง", LookGroup.FLASH, flash = true, saturation = 1.05f, contrast = 1.25f, brightness = -0.02f, flashPop = 0.3f, vignette = 0.45f),
        FilmLook("party_flash", "Party Flash", LookGroup.FLASH, flash = true, saturation = 1.25f, contrast = 1.2f, warmth = 0.1f, flashPop = 0.35f, vignette = 0.5f, grain = 0.15f),
        FilmLook(
            "disposable_flash", "ใช้แล้วทิ้ง + แฟลช", LookGroup.FLASH, flash = true, saturation = 1.1f, contrast = 1.15f, warmth = 0.12f, tint = -0.04f,
            fade = 0.03f, flashPop = 0.3f, vignette = 0.5f, grain = 0.3f, parent = "party_flash",
        ),
        FilmLook("y2k_flash", "Y2K Digicam", LookGroup.FLASH, flash = true, saturation = 1.1f, contrast = 1.1f, brightness = 0.03f, warmth = -0.12f, flashPop = 0.4f, vignette = 0.3f),
        FilmLook("paparazzi", "Paparazzi", LookGroup.FLASH, flash = true, saturation = 0.9f, contrast = 1.45f, brightness = 0.02f, flashPop = 0.45f, vignette = 0.6f),
        FilmLook("flash_noir", "แฟลชขาวดำ", LookGroup.FLASH, flash = true, mono = STD, contrast = 1.5f, flashPop = 0.4f, vignette = 0.55f, grain = 0.3f),
        FilmLook("cool_flash", "Cool Flash", LookGroup.FLASH, flash = true, saturation = 0.95f, contrast = 1.2f, warmth = -0.2f, tint = 0.03f, flashPop = 0.3f, vignette = 0.4f, parent = "direct_flash"),
        FilmLook("warm_flash", "Warm Flash", LookGroup.FLASH, flash = true, saturation = 1.1f, contrast = 1.15f, warmth = 0.22f, flashPop = 0.3f, vignette = 0.4f, parent = "direct_flash"),
        FilmLook(
            "flash_800", "Flash 800 Film", LookGroup.FLASH, flash = true, saturation = 1.05f, contrast = 1.15f, tint = -0.07f,
            shadow = f(-3f, 3f, 3f), flashPop = 0.3f, vignette = 0.45f, grain = 0.35f, parent = "party_flash",
        ),
        FilmLook(
            "instant_flash", "Instant Flash", LookGroup.FLASH, flash = true, saturation = 0.9f, contrast = 0.95f, brightness = 0.04f,
            warmth = 0.05f, fade = 0.08f, flashPop = 0.35f, vignette = 0.35f,
        ),

        // ---------- กลางคืน: แสงไฟ (ไม่ใช้แฟลช) ----------
        FilmLook(
            "night_city", "Night City", LookGroup.NIGHT_LIGHT, saturation = 1.1f, contrast = 1.12f, brightness = 0.02f, warmth = 0.04f,
            mixer = f(1.08f, -0.04f, -0.04f, -0.02f, 1f, 0.02f, -0.06f, 0.06f, 1f), shadow = f(-4f, 2f, 10f),
            halation = 0.4f,
        ),
        FilmLook("sodium_lamp", "ไฟถนนสีส้ม", LookGroup.NIGHT_LIGHT, saturation = 1.05f, contrast = 1.1f, warmth = 0.35f, shadow = f(6f, 2f, -4f), halation = 0.3f, bloom = 0.2f),
        FilmLook("blue_hour", "Blue Hour", LookGroup.NIGHT_LIGHT, saturation = 1.1f, contrast = 1.05f, warmth = -0.18f, tint = 0.06f, halation = 0.2f),
        FilmLook("moonlight", "แสงจันทร์", LookGroup.NIGHT_LIGHT, saturation = 0.5f, contrast = 1.05f, brightness = 0.02f, warmth = -0.3f, fade = 0.04f, parent = "blue_hour"),
        FilmLook("portra800_night", "Portra 800 Night", LookGroup.NIGHT_LIGHT, contrast = 1.0f, brightness = 0.04f, warmth = 0.08f, fade = 0.03f, grain = 0.3f, halation = 0.3f),
        FilmLook("superia1600_night", "Superia 1600 Night", LookGroup.NIGHT_LIGHT, saturation = 1.05f, contrast = 1.08f, brightness = 0.04f, tint = -0.08f, grain = 0.45f, halation = 0.3f, parent = "portra800_night"),
        FilmLook("low_light_grain", "Low Light Grain", LookGroup.NIGHT_LIGHT, saturation = 0.85f, contrast = 0.95f, brightness = 0.05f, fade = 0.06f, grain = 0.55f, parent = "portra800_night"),
        FilmLook("candle_light", "แสงเทียน", LookGroup.NIGHT_LIGHT, saturation = 1.1f, contrast = 1.05f, brightness = 0.03f, warmth = 0.28f, tint = 0.04f, vignette = 0.35f, halation = 0.3f, bloom = 0.4f, parent = "sodium_lamp"),
        FilmLook("neon_pink", "Neon Pink", LookGroup.NIGHT_LIGHT, saturation = 1.25f, contrast = 1.12f, warmth = -0.05f, tint = 0.22f, shadow = f(8f, -2f, 8f), halation = 0.5f, bloom = 0.3f, parent = "neon_night"),
        FilmLook("midnight_noir", "Midnight Noir", LookGroup.NIGHT_LIGHT, mono = STD, contrast = 1.4f, brightness = 0.03f, grain = 0.4f, vignette = 0.35f),
    )

    private val byIdMap = all.associateBy { it.id }

    fun byId(id: String): FilmLook = byIdMap[id] ?: ORIGINAL

    /** ฟิลเตอร์ตัวแม่ของตระกูล (ตัวมันเองถ้าไม่มีแม่) */
    fun rootOf(look: FilmLook): FilmLook = look.parent?.let { byId(it) } ?: look

    /** ทั้งตระกูล: ตัวแม่ + ตัวเลือกย่อย */
    fun familyOf(look: FilmLook): List<FilmLook> {
        val root = rootOf(look)
        return listOf(root) + all.filter { it.parent == root.id }
    }

    /** ค้นหาจากชื่อหรือรหัสฟิลเตอร์ (ไม่สนตัวพิมพ์เล็ก/ใหญ่) */
    fun search(query: String): List<FilmLook> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return all.filter { !it.isOriginal && (it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true)) }
    }

    fun sceneOf(look: FilmLook): Scene = if (look.isOriginal) Scene.DAY else look.group.scene

    fun groupsOf(scene: Scene): List<LookGroup> = LookGroup.entries.filter { it.scene == scene }

    /** group = null คือ "ทั้งหมด" ของช่วงเวลานั้น */
    fun list(scene: Scene, group: LookGroup?): List<FilmLook> {
        val inScene = all.filter { !it.isOriginal && it.group.scene == scene }
        val roots = inScene.filter { it.parent == null }
        val picked = when (group) {
            null -> roots
            LookGroup.BW -> inScene.filter { it.isMono && (it.parent == null || !rootOf(it).isMono) }
            else -> inScene.filter { it.group == group && (it.parent == null || rootOf(it).group != group) }
        }
        return listOf(ORIGINAL) + picked
    }
}
