package com.fujicam.app.camera

import android.content.Context
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** ผู้ใช้เลือกเองว่าจะให้มีเสียงชัตเตอร์ไหม */
enum class SoundMode(val label: String, val hint: String) {
    FOLLOW("ตามโทรศัพท์", "โทรศัพท์เปิดเสียงก็มีเสียง ปิดเสียงหรือโหมดสั่นก็เงียบ"),
    ON("เปิดเสียงเสมอ", "มีเสียงแม้โทรศัพท์ปิดเสียงอยู่ (ใช้ระดับเสียงสื่อ)"),
    OFF("ปิดเสียง", "ไม่มีเสียงเลย"),
}

/**
 * เสียงชัตเตอร์/เสียงอัดวิดีโอ และการสั่นตอนถ่าย ตามที่ผู้ใช้ตั้งไว้
 * ยกเว้นมือถือที่กฎหมายประเทศนั้นบังคับให้มีเสียงชัตเตอร์ (เช่น ญี่ปุ่น เกาหลี) จะมีเสียงเสมอ
 */
class ShutterSound(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sound = MediaActionSound()
    private val vibrator: Vibrator? = findVibrator(appContext)
    val forcedByLaw: Boolean = isForcedByLaw()

    var mode: SoundMode = SoundMode.FOLLOW
    var vibrateOn: Boolean = true

    // เสียงสำรองที่สร้างเอง เล่นผ่านช่องเสียงสื่อ ใช้ตอน "เปิดเสียงเสมอ" แต่โทรศัพท์ปิดเสียงอยู่
    private val tracks = HashMap<Int, AudioTrack>()

    fun load() {
        sound.load(MediaActionSound.SHUTTER_CLICK)
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    /** type = MediaActionSound.SHUTTER_CLICK / START_VIDEO_RECORDING / STOP_VIDEO_RECORDING */
    fun play(type: Int) {
        when {
            forcedByLaw -> sound.play(type)
            mode == SoundMode.OFF -> Unit
            phoneSoundOn() -> sound.play(type)
            mode == SoundMode.ON -> playOwn(type)
            else -> Unit // ตามโทรศัพท์ และโทรศัพท์ปิดเสียงอยู่
        }
    }

    /** สั่นสั้น ๆ ตอนกดถ่าย (ถ้าผู้ใช้เปิดไว้) */
    fun buzz() {
        if (!vibrateOn) return
        try {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } catch (e: Exception) {
            // บางเครื่องไม่มีมอเตอร์สั่น
        }
    }

    fun release() {
        sound.release()
        tracks.values.forEach { runCatching { it.release() } }
        tracks.clear()
    }

    private fun phoneSoundOn(): Boolean = try {
        audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audio.getStreamVolume(AudioManager.STREAM_SYSTEM) > 0
    } catch (e: Exception) {
        false
    }

    private fun playOwn(type: Int) {
        try {
            val track = tracks.getOrPut(type) { buildTrack(pcmFor(type)) }
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (e: Exception) {
            // ถ้าเล่นเสียงสำรองไม่ได้ก็ข้ามไป ไม่ให้กระทบการถ่าย
        }
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    private fun pcmFor(type: Int): ShortArray = when (type) {
        MediaActionSound.START_VIDEO_RECORDING -> tone(660.0, 990.0, 0.16)
        MediaActionSound.STOP_VIDEO_RECORDING -> tone(990.0, 660.0, 0.16)
        else -> shutterClick()
    }

    /** เสียงม่านชัตเตอร์สองจังหวะ */
    private fun shutterClick(): ShortArray {
        val n = (RATE * 0.12).toInt()
        val rnd = Random(3)
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val e1 = if (t < 0.05) exp(-t * 90) else 0.0
            val t2 = t - 0.055
            val e2 = if (t2 >= 0) exp(-t2 * 110) * 0.8 else 0.0
            val v = (e1 + e2) * rnd.nextGaussian() * 0.35
            (v.coerceIn(-1.0, 1.0) * 32767 * 0.8).toInt().toShort()
        }
    }

    /** เสียงบี๊บไล่ระดับ (เริ่ม/หยุดอัด) */
    private fun tone(f0: Double, f1: Double, dur: Double): ShortArray {
        val n = (RATE * dur).toInt()
        var phase = 0.0
        return ShortArray(n) { i ->
            val p = i.toDouble() / n
            val f = f0 + (f1 - f0) * p
            phase += 2 * PI * f / RATE
            val env = minOf(1.0, p * 20) * minOf(1.0, (1 - p) * 8)
            (sin(phase) * env * 32767 * 0.35).toInt().toShort()
        }
    }

    private fun isForcedByLaw(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return MediaActionSound.mustPlayShutterSound()
        // Android 10–12: อ่านค่าที่ผู้ผลิตตั้งไว้ในระบบ
        return try {
            val res = Resources.getSystem()
            val id = res.getIdentifier("config_camera_sound_forced", "bool", "android")
            id != 0 && res.getBoolean(id)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val RATE = 44100

        @Suppress("DEPRECATION")
        private fun findVibrator(ctx: Context): Vibrator? = try {
            if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
    }
}
