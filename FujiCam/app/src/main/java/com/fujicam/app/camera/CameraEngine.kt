package com.fujicam.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.fujicam.app.util.BitmapIO
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CamMode(val label: String, val isDual: Boolean, val isVideo: Boolean) {
    FILM("ฟิล์ม", false, false),
    PHOTO("รูปถ่าย", false, false),
    VIDEO("วิดีโอ", false, true),
    DUAL_PHOTO("ถ่ายคู่", true, false),
    DUAL_VIDEO("อัดคู่", true, true),
}

class CameraEngine(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    var camera: Camera? = null; private set          // กล้องหลัก (ในโหมดคู่ = กล้องหลัง)
    var camera2: Camera? = null; private set         // กล้องหน้าในโหมดคู่
    var imageCapture: ImageCapture? = null; private set
    var imageCapture2: ImageCapture? = null; private set
    var videoCapture: VideoCapture<Recorder>? = null; private set
    var videoCapture2: VideoCapture<Recorder>? = null; private set

    private var recording: Recording? = null
    private var recording2: Recording? = null
    private var targetRotation = Surface.ROTATION_0

    /** กลับด้านวิดีโอกล้องหน้าแบบกระจก ให้ตรงกับที่เห็นบนจอ (มีผลตอน bind ครั้งถัดไป) */
    var mirrorFrontVideo = true

    suspend fun ensureInit(): ProcessCameraProvider {
        provider?.let { return it }
        val p = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, mainExecutor)
        }
        provider = p
        return p
    }

    /** หาคู่กล้องหน้า+หลังที่เปิดพร้อมกันได้ (Concurrent Camera) */
    private fun dualSelectors(): Pair<CameraSelector, CameraSelector>? {
        val p = provider ?: return null
        val groups = try {
            p.availableConcurrentCameraInfos
        } catch (e: Exception) {
            emptyList()
        }
        for (infos in groups) {
            val back = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            val front = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            if (back != null && front != null) return back.cameraSelector to front.cameraSelector
        }
        return null
    }

    fun dualSupported(): Boolean = dualSelectors() != null

    private fun selector(lens: Int) = CameraSelector.Builder().requireLensFacing(lens).build()

    private fun resolution(ratio: AspectRatioStrategy, bound: Size): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(ratio)
            .setResolutionStrategy(ResolutionStrategy(bound, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()

    private fun newImageCapture(rs: ResolutionSelector?, flash: Int): ImageCapture {
        val b = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flash)
            .setTargetRotation(targetRotation)
        if (rs != null) b.setResolutionSelector(rs)
        return b.build()
    }

    private fun newVideoCapture(quality: Quality): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .build()
        return VideoCapture.Builder(recorder)
            .setTargetRotation(targetRotation)
            .setMirrorMode(if (mirrorFrontVideo) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
            .build()
    }

    private fun clearRefs() {
        camera = null; camera2 = null
        imageCapture = null; imageCapture2 = null
        videoCapture = null; videoCapture2 = null
    }

    fun bind(
        owner: LifecycleOwner,
        mode: CamMode,
        lens: Int,
        main: PreviewView,
        pip: PreviewView,
        uhd: Boolean,
        flash: Int,
    ): Boolean {
        val p = provider ?: return false
        stopRecordings()
        p.unbindAll()
        clearRefs()
        val r43 = resolution(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY, Size(4000, 3000))
        val r169 = resolution(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY, Size(4000, 2250))
        return try {
            when (mode) {
                CamMode.PHOTO, CamMode.FILM -> {
                    val preview = Preview.Builder().setResolutionSelector(r43).build()
                    preview.setSurfaceProvider(main.surfaceProvider)
                    val ic = newImageCapture(r43, flash)
                    camera = p.bindToLifecycle(owner, selector(lens), preview, ic)
                    imageCapture = ic
                }

                CamMode.VIDEO -> {
                    val preview = Preview.Builder().setResolutionSelector(r169).build()
                    preview.setSurfaceProvider(main.surfaceProvider)
                    val vc = newVideoCapture(if (uhd) Quality.UHD else Quality.FHD)
                    // ImageCapture ไว้ถ่ายภาพนิ่งความละเอียดเต็มระหว่างอัด (ถ้าเครื่องรองรับ 3 สตรีม)
                    val ic = newImageCapture(r169, ImageCapture.FLASH_MODE_OFF)
                    camera = try {
                        p.bindToLifecycle(owner, selector(lens), preview, vc, ic).also { imageCapture = ic }
                    } catch (e: IllegalArgumentException) {
                        p.unbindAll()
                        p.bindToLifecycle(owner, selector(lens), preview, vc)
                    }
                    videoCapture = vc
                }

                CamMode.DUAL_PHOTO, CamMode.DUAL_VIDEO -> {
                    val (backSel, frontSel) = dualSelectors() ?: return false
                    val previewBack = Preview.Builder().build().also { it.setSurfaceProvider(main.surfaceProvider) }
                    val previewFront = Preview.Builder().build().also { it.setSurfaceProvider(pip.surfaceProvider) }
                    val groupBack = UseCaseGroup.Builder().addUseCase(previewBack)
                    val groupFront = UseCaseGroup.Builder().addUseCase(previewFront)
                    var ic1: ImageCapture? = null
                    var ic2: ImageCapture? = null
                    var vc1: VideoCapture<Recorder>? = null
                    var vc2: VideoCapture<Recorder>? = null
                    if (mode == CamMode.DUAL_PHOTO) {
                        ic1 = newImageCapture(null, ImageCapture.FLASH_MODE_OFF)
                        ic2 = newImageCapture(null, ImageCapture.FLASH_MODE_OFF)
                        groupBack.addUseCase(ic1)
                        groupFront.addUseCase(ic2)
                    } else {
                        // โหมดกล้องพร้อมกัน ระบบจำกัดความละเอียด จึงขอ HD
                        vc1 = newVideoCapture(Quality.HD)
                        vc2 = newVideoCapture(Quality.HD)
                        groupBack.addUseCase(vc1)
                        groupFront.addUseCase(vc2)
                    }
                    val concurrent = p.bindToLifecycle(
                        listOf(
                            ConcurrentCamera.SingleCameraConfig(backSel, groupBack.build(), owner),
                            ConcurrentCamera.SingleCameraConfig(frontSel, groupFront.build(), owner),
                        ),
                    )
                    camera = concurrent.cameras.getOrNull(0)
                    camera2 = concurrent.cameras.getOrNull(1)
                    imageCapture = ic1; imageCapture2 = ic2
                    videoCapture = vc1; videoCapture2 = vc2
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            p.unbindAll()
            clearRefs()
            false
        }
    }

    fun applyFlash(mode: CamMode, flash: Int) {
        when (mode) {
            CamMode.PHOTO, CamMode.FILM -> imageCapture?.flashMode = flash
            CamMode.VIDEO -> camera?.cameraControl?.enableTorch(flash == ImageCapture.FLASH_MODE_ON)
            else -> Unit
        }
    }

    fun setTargetRotation(rotation: Int) {
        if (rotation == targetRotation) return
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
        imageCapture2?.targetRotation = rotation
        if (recording == null) videoCapture?.targetRotation = rotation
        if (recording2 == null) videoCapture2?.targetRotation = rotation
    }

    /** ถ่ายภาพเป็น Bitmap ความละเอียดเต็ม (หมุนให้ถูกทิศแล้ว) */
    suspend fun capture(ic: ImageCapture, mirror: Boolean): Bitmap = suspendCancellableCoroutine { cont ->
        ic.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val result = runCatching {
                    val rotation = image.imageInfo.rotationDegrees
                    BitmapIO.transform(image.toBitmap(), rotation, mirror, recycleSource = true)
                }
                image.close()
                result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        primary: Boolean,
        vc: VideoCapture<Recorder>,
        file: File,
        withAudio: Boolean,
        onStatus: (Long) -> Unit,
        onDone: (File, Boolean) -> Unit,
    ) {
        var pending = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        if (withAudio) pending = pending.withAudioEnabled()
        val rec = pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Status -> onStatus(event.recordingStats.recordedDurationNanos)
                is VideoRecordEvent.Finalize -> {
                    val ok = event.error == VideoRecordEvent.Finalize.ERROR_NONE ||
                        (file.exists() && file.length() > 0 && event.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA)
                    if (primary) recording = null else recording2 = null
                    onDone(file, ok)
                }
                else -> Unit
            }
        }
        if (primary) recording = rec else recording2 = rec
    }

    fun stopRecordings() {
        recording?.stop()
        recording2?.stop()
    }

    fun pauseRecordings() {
        recording?.pause()
        recording2?.pause()
    }

    fun resumeRecordings() {
        recording?.resume()
        recording2?.resume()
    }

    fun release() {
        stopRecordings()
        provider?.unbindAll()
        clearRefs()
    }

    companion object {
        private const val TAG = "CameraEngine"
        private val captureExecutor = Executors.newSingleThreadExecutor()

        /** แปลงองศาการถือเครื่อง -> Surface.ROTATION_* (แอพล็อกแนวตั้ง แต่รูปออกมาถูกทิศ) */
        fun rotationFor(degrees: Int): Int = when (degrees) {
            in 45 until 135 -> Surface.ROTATION_270
            in 135 until 225 -> Surface.ROTATION_180
            in 225 until 315 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
    }
}
