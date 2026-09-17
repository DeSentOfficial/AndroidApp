package xyz.desent.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.runBlocking
import xyz.desent.DeSentApplication
import xyz.desent.R
import java.time.LocalDate
import java.time.ZoneId

/**
 * The 6x7 month grid of the calendar widget. Each cell shows the day number
 * (today circled, out-of-month days dimmed) and an event dot; tapping a cell
 * deep-links into the app's calendar on that day.
 */
class CalendarMonthRemoteViewsFactory(
    private val context: Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private val widgetDataHelper: WidgetDataHelper by lazy {
        (context.applicationContext as DeSentApplication).widgetDataHelper
    }

    private var cells: List<CalendarMonthCell> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        runBlocking {
            val anchor = LocalDate.ofEpochDay(
                widgetDataHelper.getCalendarAnchor(context, widgetId) ?: LocalDate.now().toEpochDay()
            )
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val fdow = firstDayOfWeek()

            val gridStart = monthGridStart(anchor, fdow)
            val rangeStart = gridStart.atStartOfDay(zone).toEpochSecond()
            val rangeEnd = gridStart.plusDays(42).atStartOfDay(zone).toEpochSecond()
            val events = widgetDataHelper.getCalendarEventsInRange(rangeStart, rangeEnd)

            cells = buildMonthCells(
                anchor = anchor,
                today = today,
                firstDayOfWeek = fdow,
                eventDayCounts = buildEventDayCounts(events, gridStart, 42, zone)
            )
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getItemId(position: Int): Long =
        cells.getOrNull(position)?.epochDay ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_calendar_cell)
        val cell = cells.getOrNull(position)
        if (cell == null) {
            views.setTextViewText(R.id.cal_cell_day, "")
            views.setViewVisibility(R.id.cal_cell_dot, View.INVISIBLE)
            return views
        }

        views.setTextViewText(R.id.cal_cell_day, cell.dayOfMonth.toString())
        views.setTextColor(
            R.id.cal_cell_day,
            when {
                cell.isToday -> context.getColor(R.color.md_theme_light_onPrimary)
                cell.inMonth -> context.getColor(R.color.md_theme_light_onSurface)
                else -> context.getColor(R.color.md_theme_light_outline)
            }
        )
        views.setInt(
            R.id.cal_cell_content, "setBackgroundResource",
            if (cell.isToday) R.drawable.calendar_cell_today else R.drawable.calendar_cell_normal
        )
        if (cell.eventCount > 0) {
            views.setViewVisibility(R.id.cal_cell_dot, View.VISIBLE)
            views.setInt(
                R.id.cal_cell_dot, "setImageResource",
                if (cell.isToday) R.drawable.calendar_dot_inverse else R.drawable.calendar_dot
            )
        } else {
            views.setViewVisibility(R.id.cal_cell_dot, View.INVISIBLE)
        }

        // Tapping a day opens the app's calendar scrolled to that date.
        val fillIn = Intent().putExtra(WidgetNavContract.EXTRA_DATE_EPOCH_DAY, cell.epochDay)
        views.setOnClickFillInIntent(R.id.cal_cell_root, fillIn)
        return views
    }

    override fun getViewTypeCount(): Int = 1

    override fun onDestroy() {
        cells = emptyList()
    }

    override fun getCount(): Int = cells.size
}
