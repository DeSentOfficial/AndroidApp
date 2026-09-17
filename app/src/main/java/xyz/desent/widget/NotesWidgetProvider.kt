package xyz.desent.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import xyz.desent.MainActivity
import xyz.desent.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateNotesAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_NOTES_WIDGET) {
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            val manager = AppWidgetManager.getInstance(context)
            val toRefresh = ids?.toList() ?: manager.getAppWidgetIds(
                android.content.ComponentName(context, NotesWidgetProvider::class.java)
            ).toList()
            toRefresh.forEach { id ->
                saveLastRefreshTime(context, id)
                updateNotesAppWidget(context, manager, id)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_NOTES_WIDGET = "xyz.desent.widget.ACTION_REFRESH_NOTES_WIDGET"
        private const val PREFS = "desent_notes_widget_prefs"
        private fun lastRefreshKey(widgetId: Int) = "last_refresh_$widgetId"

        internal fun saveLastRefreshTime(context: Context, widgetId: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(lastRefreshKey(widgetId), System.currentTimeMillis())
                .apply()
        }

        internal fun getLastRefreshTime(context: Context, widgetId: Int): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(lastRefreshKey(widgetId), 0L)
    }
}

internal fun updateNotesAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int
) {
    val views = RemoteViews(context.packageName, R.layout.notes_widget)

    views.setTextViewText(
        R.id.notes_widget_last_reload,
        formatNotesRefreshTime(NotesWidgetProvider.getLastRefreshTime(context, appWidgetId))
    )

    // Refresh button → forces a local re-query of notes (no relay round-trip).
    val refreshIntent = Intent(context, NotesWidgetProvider::class.java).apply {
        action = NotesWidgetProvider.ACTION_REFRESH_NOTES_WIDGET
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
        data = Uri.parse("desent-notes-refresh://$appWidgetId")
    }
    views.setOnClickPendingIntent(
        R.id.notes_widget_refresh,
        PendingIntent.getBroadcast(
            context,
            appWidgetId,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    )

    // "+ New note" button → opens the editor for a brand-new note.
    val newNoteIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(WidgetNavContract.EXTRA_NEW_NOTE, true)
        putExtra(WidgetNavContract.EXTRA_WIDGET_TS, System.currentTimeMillis())
    }
    views.setOnClickPendingIntent(
        R.id.notes_widget_add,
        PendingIntent.getActivity(
            context,
            appWidgetId,
            newNoteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    )

    // Bind the notes list.
    val serviceIntent = Intent(context, NotesRemoteViewsService::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        data = Uri.parse("desent-notes-list://$appWidgetId")
    }
    views.setRemoteAdapter(R.id.notes_widget_list, serviceIntent)

    // Template intent for per-item taps → opens MainActivity which routes to
    // the note editor. Each item augments it with EXTRA_NOTE_ID.
    val itemTemplate = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(WidgetNavContract.EXTRA_WIDGET_TS, System.currentTimeMillis())
    }
    views.setPendingIntentTemplate(
        R.id.notes_widget_list,
        PendingIntent.getActivity(
            context,
            0,
            itemTemplate,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    )

    appWidgetManager.updateAppWidget(appWidgetId, views)
    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.notes_widget_list)
}

private fun formatNotesRefreshTime(timestamp: Long): String {
    if (timestamp == 0L) return "Notes"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "Updated just now"
        diff < 3600_000 -> "Updated ${diff / 60_000} min ago"
        diff < 86400_000 -> {
            val hours = diff / 3600_000
            if (hours == 1L) "Updated 1 hr ago" else "Updated $hours hrs ago"
        }
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            "Updated ${sdf.format(Date(timestamp))}"
        }
    }
}
