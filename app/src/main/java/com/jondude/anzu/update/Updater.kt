package com.jondude.anzu.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jondude.anzu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Self-updater: checks the public repo's latest GitHub Release and installs its APK. */
object Updater {
    private const val REPO = "Jondude1/Anzu-1"

    data class Release(val code: Int, val tag: String, val url: String)

    val available = MutableStateFlow<Release?>(null)
    /** -1 idle, 0..1 downloading */
    val progress = MutableStateFlow(-1f)
    val status = MutableStateFlow("")
    private var lastCheck = 0L

    suspend fun check(force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 30 * 60_000) return@withContext available.value
        lastCheck = now
        try {
            val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) {
                status.value = "Update check failed (${conn.responseCode})"
                return@withContext null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val code = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }
            val rel = if (apk != null && code > BuildConfig.VERSION_CODE) Release(code, tag, apk) else null
            available.value = rel
            status.value = if (rel == null) "Up to date" else "Update $tag available"
            rel
        } catch (e: Exception) {
            status.value = "Update check failed"
            null
        }
    }

    /** GitHub redirects release downloads to a CDN on another host, so follow by hand. */
    private fun open(start: String): HttpURLConnection {
        var url = start
        repeat(6) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 300..399) {
                url = conn.getHeaderField("Location") ?: throw RuntimeException("Redirect without location")
                conn.disconnect()
            } else if (code == 200) {
                return conn
            } else throw RuntimeException("HTTP $code")
        }
        throw RuntimeException("Too many redirects")
    }

    suspend fun downloadAndInstall(ctx: Context, rel: Release) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val file = withContext(Dispatchers.IO) {
            try {
                progress.value = 0f
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "update-${rel.code}.apk")
                val conn = open(rel.url)
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) progress.value = done.toFloat() / total
                        }
                    }
                }
                out
            } catch (e: Exception) {
                status.value = "Download failed: ${e.message}"
                null
            } finally {
                progress.value = -1f
            }
        } ?: return
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
