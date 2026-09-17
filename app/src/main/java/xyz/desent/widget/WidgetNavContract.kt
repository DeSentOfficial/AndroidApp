package xyz.desent.widget

/**
 * Contract between the home-screen widgets and [xyz.desent.MainActivity].
 *
 * Widget item taps fire a PendingIntent at MainActivity carrying one of these
 * extras; MainActivity reads them (onCreate / onNewIntent) and routes through
 * the NavController.
 */
object WidgetNavContract {
    /** Launch the note editor viewing the note with this id. */
    const val EXTRA_NOTE_ID = "desent.widget.extra.NOTE_ID"
    /** Launch the note editor for a brand-new note. */
    const val EXTRA_NEW_NOTE = "desent.widget.extra.NEW_NOTE"
    /** Launch the calendar event editor viewing the event with this id. */
    const val EXTRA_EVENT_ID = "desent.widget.extra.EVENT_ID"
    /** Launch the calendar tab scrolled to this day (epoch day, see [java.time.LocalDate.toEpochDay]). */
    const val EXTRA_DATE_EPOCH_DAY = "desent.widget.extra.DATE_EPOCH_DAY"

    /** Used to make the nav consumer re-run when a new widget intent arrives. */
    const val EXTRA_WIDGET_TS = "desent.widget.extra.TS"
}
