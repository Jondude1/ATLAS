package com.jondude.anzu.overlay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.jondude.anzu.assist.Brain
import java.io.ByteArrayOutputStream

/**
 * Point the camera, tap, and Atlas labels what he sees with holographic target brackets.
 * Camera2 with a TextureView: no extra dependencies. Tap anywhere to scan; tap a label to clear.
 */
class ArActivity : Activity() {
    private lateinit var tex: TextureView
    private lateinit var marks: MarkView
    private lateinit var status: TextView
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var busy = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tex = TextureView(this)
        marks = MarkView(this)
        status = TextView(this).apply {
            setTextColor(Color.parseColor(Holo.CYAN))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            letterSpacing = 0.12f
            text = "◢ A.T.L.A.S VISION  ▸  TAP TO SCAN"
            setPadding(40, 60, 40, 20)
        }
        root.addView(tex, FrameLayout.LayoutParams(-1, -1))
        root.addView(marks, FrameLayout.LayoutParams(-1, -1))
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        root.setOnClickListener { scan() }
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
        }
    }

    override fun onResume() {
        super.onResume()
        if (tex.isAvailable) openCamera() else tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    override fun onPause() {
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        session = null; device = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED && tex.isAvailable) openCamera()
        else if (r.firstOrNull() != PackageManager.PERMISSION_GRANTED) status.text = "◢ CAMERA PERMISSION NEEDED"
    }

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || device != null) return
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.first()
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startPreview(d) }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, e: Int) { d.close(); device = null; runOnUiThread { status.text = "◢ CAMERA ERROR $e" } }
            }, null)
        } catch (t: Throwable) {
            status.text = "◢ CAMERA UNAVAILABLE"
        }
    }

    @Suppress("DEPRECATION")
    private fun startPreview(d: CameraDevice) {
        val st = tex.surfaceTexture ?: return
        st.setDefaultBufferSize(1280, 720)
        val surface = Surface(st)
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
        d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                req.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                s.setRepeatingRequest(req.build(), null, null)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { status.text = "◢ CAMERA CONFIG FAILED" } }
        }, null)
    }

    private fun scan() {
        if (busy) { return }
        if (marks.hasMarks()) { marks.clear(); status.text = "◢ A.T.L.A.S VISION  ▸  TAP TO SCAN"; return }
        val bmp: Bitmap = tex.getBitmap(960, 540) ?: return
        busy = true
        status.text = "◢ SCANNING…"
        com.jondude.anzu.Ears.earcon("scan")
        marks.scanning = true
        Thread {
            val out = try {
                val bo = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 75, bo)
                Brain.labelScene(Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP))
            } catch (e: Exception) {
                runOnUiThread { status.text = "◢ ${(e.message ?: "SCAN FAILED").take(60).uppercase()}" }
                emptyList()
            }
            runOnUiThread {
                busy = false
                marks.scanning = false
                if (out.isNotEmpty()) {
                    marks.set(out)
                    status.text = "◢ ${out.size} TARGETS  ▸  TAP TO CLEAR"
                    com.jondude.anzu.Ears.earcon("lock")
                } else if (!status.text.startsWith("◢ API") && !status.text.contains("KEY")) status.text = "◢ NOTHING RECOGNISED  ▸  TAP TO RETRY"
            }
        }.start()
    }

    private class MarkView(ctx: Context) : View(ctx) {
        private var items: List<Triple<String, FloatArray, String>> = emptyList()
        private var shownAt = 0L
        var scanning = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.SQUARE }
        private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6FBFF"); textSize = 34f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }

        fun hasMarks() = items.isNotEmpty()
        fun clear() { items = emptyList(); invalidate() }
        fun set(l: List<Triple<String, FloatArray, String>>) { items = l; shownAt = SystemClock.uptimeMillis(); invalidate() }

        override fun onTouchEvent(e: MotionEvent): Boolean = false

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val now = SystemClock.uptimeMillis()
            if (scanning) {
                val y = ((now % 1400) / 1400f) * h
                f.color = Color.argb(70, 0, 229, 255); c.drawRect(0f, y - 90f, w, y, f)
                p.color = Color.parseColor(Holo.CYAN); c.drawLine(0f, y, w, y, p)
            }
            val grow = ((now - shownAt) / 350f).coerceIn(0f, 1f)
            for ((label, b, note) in items) {
                val cx = (b[0] + b[2] / 2f) * w; val cy = (b[1] + b[3] / 2f) * h
                val bw = b[2] * w * (0.6f + 0.4f * grow); val bh = b[3] * h * (0.6f + 0.4f * grow)
                val l = cx - bw / 2; val t = cy - bh / 2; val r = cx + bw / 2; val bt = cy + bh / 2
                f.color = Color.argb(28, 0, 229, 255); c.drawRect(l, t, r, bt, f)
                p.color = Color.parseColor(Holo.CYAN)
                val k = minOf(bw, bh) * 0.22f
                c.drawLine(l, t, l + k, t, p); c.drawLine(l, t, l, t + k, p)
                c.drawLine(r, t, r - k, t, p); c.drawLine(r, t, r, t + k, p)
                c.drawLine(l, bt, l + k, bt, p); c.drawLine(l, bt, l, bt - k, p)
                c.drawLine(r, bt, r - k, bt, p); c.drawLine(r, bt, r, bt - k, p)
                val text = label.uppercase() + if (note.isNotBlank()) "  ▸ $note" else ""
                val tw = tp.measureText(text) + 24f
                val ty = if (t > 70f) t - 12f else bt + 44f
                val tx = l.coerceAtMost(w - tw)
                f.color = Color.argb(200, 3, 20, 28); c.drawRect(tx, ty - 36f, tx + tw, ty + 10f, f)
                p.strokeWidth = 2f; c.drawRect(tx, ty - 36f, tx + tw, ty + 10f, p); p.strokeWidth = 4f
                c.drawText(text, tx + 12f, ty, tp)
            }
            if (scanning || (items.isNotEmpty() && grow < 1f) || items.isNotEmpty()) postInvalidateOnAnimation()
        }
    }
}
