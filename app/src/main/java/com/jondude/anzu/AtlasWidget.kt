package com.jondude.anzu

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.jondude.anzu.assist.AssistActivity

/**
 * The home-screen glance: the ring (tap to talk), the top urgent item if there is one, and one line of the brief.
 * The service pushes fresh lines when the brief changes; Android also refreshes it every half hour.
 */
class AtlasWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids, last.first, last.second)
    }

    companion object {
        @Volatile
        private var last: Pair<String, String?> = "Standing by" to null

        /** What the widget should say: urgent first (orange, one line), then the brief's first sentence. */
        fun lines(brief: String?, urgent: List<String>): Pair<String, String?> {
            val line = brief?.trim()?.takeIf { it.isNotBlank() }?.let { com.jondude.anzu.firstSentence(it) } ?: "Standing by"
            val top = urgent.firstOrNull()?.let { if (urgent.size > 1) "$it  +${urgent.size - 1}" else it }
            return line to top
        }

        fun push(ctx: Context, brief: String?, urgent: List<String>) {
            val next = lines(brief, urgent)
            if (next == last) return
            last = next
            try {
                val m = AppWidgetManager.getInstance(ctx)
                val ids = m.getAppWidgetIds(ComponentName(ctx, AtlasWidget::class.java))
                if (ids.isNotEmpty()) render(ctx, m, ids, next.first, next.second)
            } catch (_: Exception) {
            }
        }

        private fun render(ctx: Context, m: AppWidgetManager, ids: IntArray, line: String, urgent: String?) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_atlas)
            v.setTextViewText(R.id.widget_line, line)
            if (urgent != null) {
                v.setTextViewText(R.id.widget_urgent, urgent)
                v.setViewVisibility(R.id.widget_urgent, View.VISIBLE)
            } else v.setViewVisibility(R.id.widget_urgent, View.GONE)
            val talk = PendingIntent.getActivity(
                ctx, 11, Intent(ctx, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
            )
            val open = PendingIntent.getActivity(
                ctx, 12, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.widget_ring, talk)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            m.updateAppWidget(ids, v)
        }
    }
}
