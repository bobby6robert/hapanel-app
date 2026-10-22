package dev.hapanel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.abs

/** Wykrywanie ruchu: różnica jasności siatki 32x24 między klatkami (po odjęciu średniej, odporne na auto-ekspozycję). */
class MotionDetector(
    private val ctx: Context,
    private val threshold: Int,
    private val onTrigger: () -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private val thread = HandlerThread("motion").also { it.start() }
    private val h = Handler(thread.looper)
    private var cam: CameraDevice? = null
    private var reader: ImageReader? = null
    private var session: CameraCaptureSession? = null
    private var prev: IntArray? = null
    private var last = 0L
    private var active = false
    private val clear = Runnable { active = false; onState(false) }

    @SuppressLint("MissingPermission")
    fun start() {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: cm.cameraIdList.firstOrNull() ?: return
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return
        val size = sizes.filter { it.width >= 320 }.minByOrNull { it.width * it.height } ?: sizes.minByOrNull { it.width * it.height } ?: return
        val rd = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        rd.setOnImageAvailableListener({ onImage(it) }, h)
        reader = rd
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                cam = d
                @Suppress("DEPRECATION")
                d.createCaptureSession(listOf(rd.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(rd.surface) }.build()
                        s.setRepeatingRequest(req, null, h)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, h)
            }
            override fun onDisconnected(d: CameraDevice) { d.close() }
            override fun onError(d: CameraDevice, e: Int) { d.close() }
        }, h)
    }

    private fun onImage(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - last < 400) return
            last = now
            val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride
            val w = img.width; val hg = img.height
            val cur = IntArray(32 * 24)
            for (gy in 0 until 24) for (gx in 0 until 32) {
                val x = gx * w / 32 + w / 64; val y = gy * hg / 24 + hg / 48
                cur[gy * 32 + gx] = buf.get(y * rs + x).toInt() and 0xFF
            }
            val p = prev; prev = cur
            if (p != null) {
                val cm = cur.average(); val pm = p.average()
                var sum = 0.0
                for (i in cur.indices) sum += abs((cur[i] - cm) - (p[i] - pm))
                if (sum / cur.size > threshold) {
                    onTrigger()
                    if (!active) { active = true; onState(true) }
                    h.removeCallbacks(clear); h.postDelayed(clear, 10_000)
                }
            }
        } finally { img.close() }
    }

    fun stop() {
        try { session?.close(); cam?.close(); reader?.close() } catch (_: Exception) {}
        thread.quitSafely()
    }
}
