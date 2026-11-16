package com.jondude.anzu.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.Display
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** One on-screen element, copied out of the accessibility tree. */
data class A11yNode(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewIdResourceName: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val visibleToUser: Boolean
) {
    val label: String get() = text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() } ?: ""
}

/**
 * Show & Point: lets the app read the current screen's UI tree so Atlas can circle things.
 * It does nothing on its own — no event handling, no logging of screen content, no network, no
 * clicking or typing. It only answers snapshot/find calls from inside the app.
 */
class AtlasA11yService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    private fun walk(): List<A11yNode> {
        val out = ArrayList<A11yNode>()
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return out
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && out.size < MAX_NODES) {
            val n = stack.removeLast()
            try {
                val r = Rect()
                n.getBoundsInScreen(r)
                out.add(
                    A11yNode(
                        n.text?.toString(), n.contentDescription?.toString(), n.className?.toString(),
                        n.viewIdResourceName, r, n.isClickable, n.isVisibleToUser
                    )
                )
                // Push in reverse so children come out in reading order.
                for (i in n.childCount - 1 downTo 0) {
                    val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue
                    stack.addLast(c)
                }
            } catch (_: Throwable) {
            } finally {
                try { n.recycle() } catch (_: Throwable) {}
            }
        }
        // Anything left on the stack when the cap hit still needs recycling.
        while (stack.isNotEmpty()) try { stack.removeLast().recycle() } catch (_: Throwable) {}
        return out
    }

    companion object {
        private const val MAX_NODES = 400

        @Volatile
        private var instance: AtlasA11yService? = null

        fun isConnected(): Boolean = instance != null

        /**
         * One screenshot of whatever is on screen right now, scaled to at most [maxSide] px on the long edge
         * and returned as base64 JPEG for a vision call. Null if the service is off, the phone is below
         * Android 11, or the system refuses. Only ever called when Jon asks Atlas to look.
         */
        suspend fun screenshotJpegBase64(maxSide: Int = 1280): String? {
            val svc = instance ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val bmp = suspendCancellableCoroutine<Bitmap?> { cont ->
                try {
                    svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(soft)
                        }
                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            } ?: return null
            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val bos = java.io.ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 72, bos)
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        }

        fun snapshot(): List<A11yNode> = try { instance?.walk() ?: emptyList() } catch (_: Throwable) { emptyList() }

        /** Best visible node whose text or description contains [query]: clickable first, then smallest area. */
        fun find(query: String): A11yNode? = rank(snapshot(), query)

        /** Pure ranking so it can be unit-tested without a device. */
        fun rank(nodes: List<A11yNode>, query: String): A11yNode? {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return null
            return nodes
                .filter { it.visibleToUser && !it.bounds.isEmpty && matches(it, q) }
                .minWithOrNull(
                    compareByDescending<A11yNode> { it.clickable }
                        .thenBy { it.bounds.width().toLong() * it.bounds.height() }
                )
        }

        private fun matches(n: A11yNode, q: String) =
            n.text?.lowercase()?.contains(q) == true || n.contentDescription?.lowercase()?.contains(q) == true
    }
}
