package xyz.desent.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import xyz.desent.DeSentApplication
import xyz.desent.MainActivity
import xyz.desent.R
import java.time.LocalDate

/**
 * Home-screen calendar widget over the encrypted Nostr calendar (NIP-52).
 *
 * Three switchable views (day / week / month), prev/next navigation within
 * the current view, and a Today reset when browsing away. All event data is
 * read from the local Room cache by the [CalendarWidgetRemoteViewsService]
 * factories; this provider only renders the chrome (title, weekday header,
 * buttons) using java.time, so it never touches the database.
 */
class CalendarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateCalendarAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val helper = (context.applicationContext as DeSentApplication).widgetDataHelper
        val manager = AppWidgetManager.getInstance(context)

        when (intent.action) {
            // Midnight / clock / timezone / locale shifts change "today", the
            // weekday header, or every label — redraw all instances. A day
            // rollover additionally stops following stale browsed anchors.
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                if (intent.action == Intent.ACTION_DATE_CHANGED) {
                    helper.resetCalendarAnchorsToToday(context)
                }
                allIds(context, manager).forEach { updateCalendarAppWidget(context, manager, it) }
            }

            ACTION_SET_VIEW, ACTION_NAV_PREV, ACTION_NAV_NEXT, ACTION_TODAY, ACTION_REFRESH -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                if (intent.action != ACTION_REFRESH) {
                    applyControlAction(context, helper, appWidgetId, intent)
                }
                updateCalendarAppWidget(context, manager, appWidgetId)
            }
        }
    }

    /** Translate a header control tap into per-widget state changes. */
    private fun applyControlAction(
        context: Context,
        helper: WidgetDataHelper,
        appWidgetId: Int,
        intent: Intent
    ) {
        val view = helper.getCalendarView(context, appWidgetId)
        val anchor = LocalDate.ofEpochDay(
            helper.getCalendarAnchor(context, appWidgetId) ?: LocalDate.now().toEpochDay()
        )
        when (intent.action) {
            ACTION_SET_VIEW ->
                helper.setCalendarView(context, appWidgetId, CalendarWidgetView.fromName(intent.getStringExtra(EXTRA_VIEW_NAME)))
            ACTION_NAV_PREV -> helper.setCalendarAnchor(context, appWidgetId, shift(anchor, view, -1).toEpochDay())
            ACTION_NAV_NEXT -> helper.setCalendarAnchor(context, appWidgetId, shift(anchor, view, 1).toEpochDay())
            ACTION_TODAY -> helper.setCalendarAnchor(context, appWidgetId, null)
        }
    }

    /** Step [anchor] by one unit of [view] (a day, a week, or a month). */
    private fun shift(anchor: LocalDate, view: CalendarWidgetView, direction: Int): LocalDate = when (view) {
        CalendarWidgetView.DAY -> anchor.plusDays(direction.toLong())
        CalendarWidgetView.WEEK -> anchor.plusDays(7L * direction)
        CalendarWidgetView.MONTH -> anchor.plusMonths(direction.toLong())
    }

    private fun allIds(context: Context, manager: AppWidgetManager): List<Int> =
        manager.getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java)).toList()

    companion object {
        const val ACTION_SET_VIEW = "xyz.desent.widget.ACTION_CALENDAR_SET_VIEW"
        const val ACTION_NAV_PREV = "xyz.desent.widget.ACTION_CALENDAR_NAV_PREV"
        const val ACTION_NAV_NEXT = "xyz.desent.widget.ACTION_CALENDAR_NAV_NEXT"
        const val ACTION_TODAY = "xyz.desent.widget.ACTION_CALENDAR_TODAY"
        const val ACTION_REFRESH = "xyz.desent.widget.ACTION_CALENDAR_REFRESH"
        const val EXTRA_VIEW_NAME = "desent.widget.extra.CALENDAR_VIEW_NAME"

        // Unique request-code slots for the header PendingIntents (10 per widget).
        const val RC_PREV = 0
        const val RC_TODAY = 1
        const val RC_NEXT = 2
        const val RC_VIEW_DAY = 3
        const val RC_VIEW_WEEK = 4
        const val RC_VIEW_MONTH = 5
        const val RC_REFRESH = 6
        const val RC_TITLE = 7
        const val RC_TEMPLATE = 8
    }
}

internal fun updateCalendarAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int
) {
    val helper = (context.applicationContext as DeSentApplication).widgetDataHelper
    val views = RemoteViews(context.packageName, R.layout.calendar_widget)

    val view = helper.getCalendarView(context, appWidgetId)
    val anchor = LocalDate.ofEpochDay(helper.getCalendarAnchor(context, appWidgetId) ?: LocalDate.now().toEpochDay())
    val today = LocalDate.now()
    val fdow = firstDayOfWeek()

    views.setTextViewText(R.id.cal_widget_title, formatCalendarWidgetTitle(view, anchor))

    // Month view shows the grid + weekday header; day/week show the agenda list.
    val isMonth = view == CalendarWidgetView.MONTH
    views.setViewVisibility(R.id.cal_widget_weekday_header, if (isMonth) View.VISIBLE else View.GONE)
    views.setViewVisibility(R.id.cal_widget_month_grid, if (isMonth) View.VISIBLE else View.GONE)
    views.setViewVisibility(R.id.cal_widget_agenda_list, if (isMonth) View.GONE else View.VISIBLE)

    if (isMonth) {
        val labels = weekdayLabels(fdow)
        weekdayHeaderIds.forEachIndexed { i, resId -> views.setTextViewText(resId, labels[i]) }
    }

    // The Today reset is only useful while browsing away from the current period.
    val anchoredOnToday = when (view) {
        CalendarWidgetView.DAY -> anchor == today
        CalendarWidgetView.WEEK -> weekStart(anchor, fdow) == weekStart(today, fdow)
        CalendarWidgetView.MONTH -> anchor.year == today.year && anchor.month == today.month
    }
    views.setViewVisibility(R.id.cal_widget_today, if (anchoredOnToday) View.INVISIBLE else View.VISIBLE)

    // Header controls → broadcasts back to this provider.
    views.setOnClickPendingIntent(
        R.id.cal_widget_prev,
        controlPendingIntent(context, appWidgetId, CalendarWidgetProvider.RC_PREV, CalendarWidgetProvider.ACTION_NAV_PREV)
    )
    views.setOnClickPendingIntent(
        R.id.cal_widget_next,
        controlPendingIntent(context, appWidgetId, CalendarWidgetProvider.RC_NEXT, CalendarWidgetProvider.ACTION_NAV_NEXT)
    )
    views.setOnClickPendingIntent(
        R.id.cal_widget_today,
        controlPendingIntent(context, appWidgetId, CalendarWidgetProvider.RC_TODAY, CalendarWidgetProvider.ACTION_TODAY)
    )
    views.setOnClickPendingIntent(
        R.id.cal_widget_refresh,
        controlPendingIntent(context, appWidgetId, CalendarWidgetProvider.RC_REFRESH, CalendarWidgetProvider.ACTION_REFRESH)
    )
    views.setOnClickPendingIntent(
        R.id.cal_widget_btn_day,
        viewPendingIntent(context, appWidgetId, CalendarWidgetProvider.RC_VIEW_DAY, CalendarWidgetView.DAY)
    )
    views.setOnClickPendingIntent(
        R.id.cal_widget_btn_week,
        viewPendingIntent(context, appWidgetId, CalendarWidgetProvider.RC_VIEW_WEEK, CalendarWidgetView.WEEK)
    )
    views.setOnClickPendingIntent(
        R.id.cal_widget_btn_month,
        viewPendingIntent(context, appWidgetId, CalendarWidgetProvider.RC_VIEW_MONTH, CalendarWidgetView.MONTH)
    )

    // Toggle chrome reflects the active view.
    val onPrimary = context.getColor(R.color.md_theme_light_onPrimary)
    val onSurfaceVariant = context.getColor(R.color.md_theme_light_onSurfaceVariant)
    fun styleToggle(id: Int, active: Boolean) {
        views.setInt(
            id, "setBackgroundResource",
            if (active) R.drawable.calendar_toggle_selected else R.drawable.calendar_toggle_unselected
        )
        views.setTextColor(id, if (active) onPrimary else onSurfaceVariant)
    }
    styleToggle(R.id.cal_widget_btn_day, view == CalendarWidgetView.DAY)
    styleToggle(R.id.cal_widget_btn_week, view == CalendarWidgetView.WEEK)
    styleToggle(R.id.cal_widget_btn_month, view == CalendarWidgetView.MONTH)

    // Title tap → open the app's calendar on the browsed period. The data URI
    // keeps this PendingIntent distinct from the notes widget's activity
    // PendingIntents (extras are not part of Intent.filterEquals).
    val titleIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(WidgetNavContract.EXTRA_DATE_EPOCH_DAY, anchor.toEpochDay())
        putExtra(WidgetNavContract.EXTRA_WIDGET_TS, System.currentTimeMillis())
        data = Uri.parse("desent-cal://title/$appWidgetId")
    }
    views.setOnClickPendingIntent(
        R.id.cal_widget_title,
        PendingIntent.getActivity(
            context,
            appWidgetId * 10 + CalendarWidgetProvider.RC_TITLE,
            titleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    )

    // Adapters: agenda list (day/week views) + month grid.
    val agendaIntent = Intent(context, CalendarWidgetRemoteViewsService::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra(CalendarWidgetRemoteViewsService.EXTRA_FACTORY_KIND, CalendarWidgetRemoteViewsService.KIND_AGENDA)
        data = Uri.parse("desent-cal-agenda://$appWidgetId")
    }
    views.setRemoteAdapter(R.id.cal_widget_agenda_list, agendaIntent)

    val monthIntent = Intent(context, CalendarWidgetRemoteViewsService::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra(CalendarWidgetRemoteViewsService.EXTRA_FACTORY_KIND, CalendarWidgetRemoteViewsService.KIND_MONTH)
        data = Uri.parse("desent-cal-month://$appWidgetId")
    }
    views.setRemoteAdapter(R.id.cal_widget_month_grid, monthIntent)

    // Shared per-item template: agenda rows fill in EXTRA_EVENT_ID (or a date
    // for the empty state), month cells fill in EXTRA_DATE_EPOCH_DAY.
    val itemTemplate = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(WidgetNavContract.EXTRA_WIDGET_TS, System.currentTimeMillis())
        data = Uri.parse("desent-cal://template/$appWidgetId")
    }
    val templatePi = PendingIntent.getActivity(
        context,
        appWidgetId * 10 + CalendarWidgetProvider.RC_TEMPLATE,
        itemTemplate,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
    views.setPendingIntentTemplate(R.id.cal_widget_agenda_list, templatePi)
    views.setPendingIntentTemplate(R.id.cal_widget_month_grid, templatePi)

    appWidgetManager.updateAppWidget(appWidgetId, views)
    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.cal_widget_agenda_list)
    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.cal_widget_month_grid)
}

private val weekdayHeaderIds = intArrayOf(
    R.id.cal_weekday_0,
    R.id.cal_weekday_1,
    R.id.cal_weekday_2,
    R.id.cal_weekday_3,
    R.id.cal_weekday_4,
    R.id.cal_weekday_5,
    R.id.cal_weekday_6
)

private fun controlPendingIntent(context: Context, appWidgetId: Int, rc: Int, action: String): PendingIntent {
    val intent = Intent(context, CalendarWidgetProvider::class.java).apply {
        this.action = action
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        data = Uri.parse("desent-cal://$action/$appWidgetId")
    }
    return PendingIntent.getBroadcast(
        context,
        appWidgetId * 10 + rc,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

private fun viewPendingIntent(
    context: Context,
    appWidgetId: Int,
    rc: Int,
    view: CalendarWidgetView
): PendingIntent {
    val intent = Intent(context, CalendarWidgetProvider::class.java).apply {
        action = CalendarWidgetProvider.ACTION_SET_VIEW
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra(CalendarWidgetProvider.EXTRA_VIEW_NAME, view.name)
        data = Uri.parse("desent-cal://view/${view.name}/$appWidgetId")
    }
    return PendingIntent.getBroadcast(
        context,
        appWidgetId * 10 + rc,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
