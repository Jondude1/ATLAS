package com.jondude.anzu.assist

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.jondude.anzu.Prefs

/**
 * Social Grace Pack (roadmap #14):
 *  - When someone is talking with Jon (the Buddie earbuds' bone-conduction sensor tells his voice from others'),
 *    music and audiobooks duck, and briefs and other low-priority lines become silent cards until they're done.
 *  - In loud-machinery surroundings, and on calls, the wake word is muted so the noise can't fake an "Atlas".
 *
 * v1 hears "a conversation with someone", not Jenna specifically: telling her voice from anyone else's needs a
 * voice sample enrolled on the phone (a speaker-ID model), which is the follow-up.
 */
object SocialGrace {
    private val others = ArrayDeque<Long>()
    @Volatile private var lastOther = 0L
    @Volatile var inConversation = false
        private set
    @Volatile private var loudSince = 0L
    @Volatile private var quietSince = 0L
    @Volatile var loud = false
        private set
    private var duck: AudioFocusRequest? = null

    /** Someone other than Jon spoke near him (a segment where the bone-conduction sensor stayed quiet). */
    @Synchronized
    fun heardOther(ctx: Context?, seconds: Float, now: Long = System.currentTimeMillis()) {
        if (seconds < 0.8f) return
        others.addLast(now); lastOther = now
        while (others.isNotEmpty() && now - others.first() > 45_000) others.removeFirst()
        if (!inConversation && others.size >= 2) {
            inConversation = true
            if (ctx != null && Prefs.duckForConversation) startDuck(ctx)
        }
    }

    /** Called on every tick: a conversation ends 25 s after the other voice was last heard. */
    @Synchronized
    fun tick(ctx: Context?, now: Long = System.currentTimeMillis()) {
        if (inConversation && now - lastOther > 25_000) {
            inConversation = false
            others.clear()
            if (ctx != null) stopDuck(ctx)
        }
    }

    /**
     * Feed the background level (RMS of 16-bit audio). Ten seconds above machinery level mutes the wake word;
     * it comes back after ten quiet seconds.
     */
    @Synchronized
    fun background(rms: Double, now: Long = System.currentTimeMillis()) {
        if (rms > LOUD) {
            quietSince = 0
            if (loudSince == 0L) loudSince = now
            if (!loud && now - loudSince > 10_000) loud = true
        } else {
            loudSince = 0
            if (quietSince == 0L) quietSince = now
            if (loud && now - quietSince > 10_000) loud = false
        }
    }

    /** Roughly a shop vac or table saw at arm's length on an earbud mic. */
    const val LOUD = 2500.0

    private fun startDuck(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (!am.isMusicActive) return
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            am.requestAudioFocus(req)
            duck = req
        } catch (_: Exception) {
        }
    }

    private fun stopDuck(ctx: Context) {
        val req = duck ?: return
        duck = null
        try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(req) } catch (_: Exception) {}
    }

    @Synchronized
    fun reset() { others.clear(); inConversation = false; lastOther = 0; loud = false; loudSince = 0; quietSince = 0; duck = null }
}
