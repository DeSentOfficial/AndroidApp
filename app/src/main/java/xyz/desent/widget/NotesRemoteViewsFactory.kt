package xyz.desent.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.runBlocking
import xyz.desent.DeSentApplication
import xyz.desent.R

class NotesRemoteViewsFactory(
    private val context: Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private val widgetDataHelper: WidgetDataHelper by lazy {
        (context.applicationContext as DeSentApplication).widgetDataHelper
    }

    private var items: List<NoteDisplayItem> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        runBlocking {
            items = widgetDataHelper.getNoteItems(context, widgetId, limit = 8)
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_note_item)
        val item = items.getOrNull(position)

        if (item != null) {
            views.setTextViewText(R.id.note_item_title, item.title)
            views.setTextViewText(
                R.id.note_item_body,
                item.bodyPreview.ifBlank { "No content" }
            )
            if (item.folder.isNotBlank()) {
                views.setTextViewText(R.id.note_item_folder, item.folder)
                views.setViewVisibility(R.id.note_item_folder, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.note_item_folder, android.view.View.GONE)
            }

            // Per-item fill-in intent: opens this note in the editor. The
            // template PendingIntent is set on the list by the provider.
            val fillIn = Intent().apply {
                putExtra(WidgetNavContract.EXTRA_NOTE_ID, item.id)
            }
            views.setOnClickFillInIntent(R.id.note_item_root, fillIn)
        } else {
            views.setTextViewText(R.id.note_item_title, "")
            views.setTextViewText(R.id.note_item_body, "")
        }

        return views
    }

    override fun getViewTypeCount(): Int = 1

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size
}
