package xyz.desent.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViewsService

/**
 * Serves both collections of the calendar widget: the agenda list (day/week
 * views) and the month grid. The factory kind is carried via [EXTRA_FACTORY_KIND]
 * so a single service class can back both adapters.
 */
class CalendarWidgetRemoteViewsService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        return if (intent.getStringExtra(EXTRA_FACTORY_KIND) == KIND_MONTH) {
            CalendarMonthRemoteViewsFactory(applicationContext, widgetId)
        } else {
            CalendarAgendaRemoteViewsFactory(applicationContext, widgetId)
        }
    }

    companion object {
        const val EXTRA_FACTORY_KIND = "desent.widget.extra.CALENDAR_FACTORY_KIND"
        const val KIND_AGENDA = "agenda"
        const val KIND_MONTH = "month"
    }
}
