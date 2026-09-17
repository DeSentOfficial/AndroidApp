package xyz.desent.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.runBlocking
import xyz.desent.DeSentApplication
import xyz.desent.R
import java.time.LocalDate
import java.time.ZoneId

/**
 * Agenda rows for the calendar widget's day and week views. Re-queries the
 * local Room cache in [onDataSetChanged] (runBlocking is fine here — it runs
 * on the RemoteViews binder thread, matching the notes widget pattern).
 */
class CalendarAgendaRemoteViewsFactory(
    private val context: Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private val widgetDataHelper: WidgetDataHelper by lazy {
        (context.applicationContext as DeSentApplication).widgetDataHelper
    }

    private var items: List<CalendarAgendaItem> = emptyList()
    private var anchorEpochDay: Long = LocalDate.now().toEpochDay()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        runBlocking {
            val view = widgetDataHelper.getCalendarView(context, widgetId)
            anchorEpochDay = widgetDataHelper.getCalendarAnchor(context, widgetId)
                ?: LocalDate.now().toEpochDay()
            val anchor = LocalDate.ofEpochDay(anchorEpochDay)
            val zone = ZoneId.systemDefault()
            val fdow = firstDayOfWeek()

            val (rangeStart, rangeEnd) = when (view) {
                CalendarWidgetView.DAY -> dayRangeSec(anchor, zone)
                CalendarWidgetView.WEEK -> {
                    val weekStart = weekStart(anchor, fdow)
                    weekStart.atStartOfDay(zone).toEpochSecond() to
                        weekStart.plusDays(7).atStartOfDay(zone).toEpochSecond()
                }
                // Month renders through the grid factory; keep a sane range.
                CalendarWidgetView.MONTH -> dayRangeSec(anchor, zone)
            }

            val events = widgetDataHelper.getCalendarEventsInRange(rangeStart, rangeEnd)
            items = when (view) {
                CalendarWidgetView.DAY -> buildDayAgendaItems(events, anchor, zone)
                CalendarWidgetView.WEEK -> buildWeekAgendaItems(events, anchor, fdow, zone)
                CalendarWidgetView.MONTH -> emptyList()
            }
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getItemId(position: Int): Long = when (val item = items.getOrNull(position)) {
        is CalendarAgendaItem.DayHeader -> "header:${item.epochDay}".hashCode().toLong()
        is CalendarAgendaItem.Event -> item.event.id.hashCode().toLong()
        CalendarAgendaItem.Empty -> Long.MIN_VALUE
        null -> position.toLong()
    }

    override fun hasStableIds(): Boolean = true

    override fun getViewAt(position: Int): RemoteViews = when (val item = items.getOrNull(position)) {
        is CalendarAgendaItem.DayHeader ->
            RemoteViews(context.packageName, R.layout.widget_calendar_day_header).apply {
                setTextViewText(R.id.cal_day_header_text, item.label)
            }

        is CalendarAgendaItem.Event ->
            RemoteViews(context.packageName, R.layout.widget_calendar_event_item).apply {
                setTextViewText(R.id.cal_event_title, item.event.title)
                setTextViewText(R.id.cal_event_time, item.event.timeLabel)
                // Per-item fill-in intent: opens this event in the editor.
                val fillIn = Intent().putExtra(WidgetNavContract.EXTRA_EVENT_ID, item.event.id)
                setOnClickFillInIntent(R.id.cal_event_item_root, fillIn)
            }

        CalendarAgendaItem.Empty ->
            RemoteViews(context.packageName, R.layout.widget_calendar_empty_item).apply {
                // Tap the empty state → the app's calendar on the browsed day.
                val fillIn = Intent().putExtra(WidgetNavContract.EXTRA_DATE_EPOCH_DAY, anchorEpochDay)
                setOnClickFillInIntent(R.id.cal_empty_root, fillIn)
            }

        null -> RemoteViews(context.packageName, R.layout.widget_calendar_event_item)
    }

    override fun getViewTypeCount(): Int = 3

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size
}
