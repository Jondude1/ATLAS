package com.jondude.anzu.assist

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jondude.anzu.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The offline brain: Jon's own Android LM app (Jondude1/Android-LM) running a model on this phone and
 * serving it on loopback with the OpenAI chat API. Android LM already detects the hardware and picks
 * the backend and thread layout, so ATLAS only has to ask it. Used when there's no internet or every
 * other tier failed. No tools in this mode: just conversation.
 */
object OnDevice {
    const val PKG = "com.jondude1.androidlm"
    const val ACTION_SERVE = "com.jondude1.androidlm.SERVE"
    private const val BASE = "http://127.0.0.1:1234"

    /** "", "starting", "ready", or "failed: reason". */
    val status = MutableStateFlow("")

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0); true
    } catch (_: Exception) {
        false
    }

    fun usable(ctx: Context): Boolean = Prefs.offlineBrain && installed(ctx)

    /** True when the phone has a working internet connection (validated, not just a Wi-Fi bar). */
    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Offline brain should take over before even trying the network. */
    fun preferred(ctx: Context): Boolean = usable(ctx) && !online(ctx)

    private fun serving(): Boolean = try {
        Net.get("$BASE/health", timeoutMs = 1_500, connectTimeoutMs = 800).first in 200..299
    } catch (_: Exception) {
        false
    }

    /** Make sure Android LM's server is up, waking the app if it isn't. Blocking, up to ~12 s. */
    fun ensureServing(ctx: Context): Boolean {
        if (serving()) return true
        status.value = "starting"
        try {
            ctx.startActivity(
                Intent(ACTION_SERVE).setClassName(PKG, "$PKG.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            status.value = "failed: couldn't open Android LM"
            return false
        }
        repeat(24) {
            Thread.sleep(500)
            if (serving()) return true
        }
        status.value = "failed: Android LM isn't serving"
        return false
    }

    fun systemPrompt(): String =
        "You are ATLAS, ${Prefs.name}'s personal AI, speaking aloud. Right now you are offline, running on his phone, " +
            "with no internet, tools or accounts. Reply in one or two short spoken sentences, no lists or markdown. " +
            "If he asks for something that needs the internet, his devices or his accounts, say plainly it will have to wait " +
            "until you're back online. Never invent facts. Address him as \"${Prefs.honorific}\" only now and then."

    fun requestBody(system: String, messages: List<Pair<String, String>>): String {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { (r, c) -> arr.put(JSONObject().put("role", r).put("content", c)) }
        return JSONObject().put("messages", arr).put("stream", false).put("max_tokens", 160).put("temperature", 0.6).toString()
    }

    fun parse(body: String): String {
        val msg = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        return msg?.optString("content").orEmpty().replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    /** Blocking chat with the on-phone model. Throws on failure. First call may wait while the model loads. */
    fun chat(ctx: Context, messages: List<Pair<String, String>>): String {
        if (!ensureServing(ctx)) throw RuntimeException(status.value)
        val recent = messages.takeLast(7)
        val (code, body) = Net.postJson("$BASE/v1/chat/completions", emptyMap(), requestBody(systemPrompt(), recent), 120_000, 3_000)
        if (code !in 200..299) {
            status.value = "failed: Android LM $code"
            throw RuntimeException("Android LM $code")
        }
        status.value = "ready"
        return parse(body)
    }
}
