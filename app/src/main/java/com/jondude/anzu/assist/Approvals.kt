package com.jondude.anzu.assist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jondude.anzu.MainActivity
import com.jondude.anzu.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Approval cards: a gated action (a text, a call, a house action on the amber list) that can't get a spoken okay
 * waits here for one tap, on the home screen or in a notification. Silence for two minutes means no.
 * Every decision is logged on the phone and filed with the hub.
 */
object Approvals {
    class Ask(val id: Long, val question: String) {
        internal val answer = CompletableDeferred<Boolean>()
    }

    val pending = MutableStateFlow<Ask?>(null)
    /** True while a pending approval can be answered with a nod or shake. */
    val nodAvailable = MutableStateFlow(false)
    private const val CHANNEL = "atlas_approvals"
    const val ACTION = "com.jondude.anzu.APPROVAL"
    private const val WAIT_MS = 120_000L

    suspend fun ask(ctx: Context, question: String): Boolean {
        val a = Ask(System.currentTimeMillis(), question)
        pending.value?.answer?.complete(false) // only one question at a time; an older one lapses
        pending.value = a
        notify(ctx.applicationContext, a)
        // A nod approves, a shake declines, when the headset reports head motion.
        val stopNod = HeadGesture.listen(ctx.applicationContext) { yes -> a.answer.complete(yes) }
        nodAvailable.value = stopNod != null
        val ok = try { withTimeoutOrNull(WAIT_MS) { a.answer.await() } ?: false } finally { stopNod?.invoke(); nodAvailable.value = false }
        if (pending.value === a) pending.value = null
        cancel(ctx.applicationContext)
        val verdict = if (ok) "Approved" else if (a.answer.isCompleted) "Declined" else "Lapsed (no answer)"
        Store.logAction("$verdict: $question")
        Hub.report("atlas", "$verdict: ${question.take(80)}", question, key = "approval-${a.id}")
        return ok
    }

    fun answer(id: Long, ok: Boolean) {
        val a = pending.value ?: return
        if (a.id == id) a.answer.complete(ok)
    }

    private fun notify(ctx: Context, a: Ask) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Atlas asks first", NotificationManager.IMPORTANCE_HIGH))
            fun act(ok: Boolean) = PendingIntent.getBroadcast(
                ctx, if (ok) 1 else 2,
                Intent(ctx, ApprovalReceiver::class.java).setAction(ACTION).putExtra("id", a.id).putExtra("ok", ok),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                7, NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Atlas is asking")
                    .setContentText(a.question)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(a.question))
                    .setContentIntent(open)
                    .addAction(0, "Approve", act(true))
                    .addAction(0, "Deny", act(false))
                    .setTimeoutAfter(WAIT_MS)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    private fun cancel(ctx: Context) = try {
        ctx.getSystemService(NotificationManager::class.java).cancel(7)
    } catch (_: Exception) {
    }
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Approvals.ACTION) return
        Approvals.answer(intent.getLongExtra("id", -1), intent.getBooleanExtra("ok", false))
    }
}
