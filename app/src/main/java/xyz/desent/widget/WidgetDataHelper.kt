package xyz.desent.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import xyz.desent.data.local.database.dao.CalendarEventDao
import xyz.desent.data.local.database.dao.FavoriteNoteDao
import xyz.desent.data.local.database.dao.PrivateNoteDao
import xyz.desent.data.local.database.dao.UserDao
import xyz.desent.data.local.database.entity.CalendarEventEntity
import xyz.desent.data.local.database.entity.PrivateNoteEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.mapper.CalendarMapper
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.usecase.RecurringEventExpander
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * One note row prepared for the Notes widget. [bodyPreview] is truncated to
 * fit the widget item layout; [id] is the `desent:note:<id>` uuid portion used
 * to deep-link into the editor.
 */
data class NoteDisplayItem(
    val id: String,
    val title: String,
    val bodyPreview: String,
    val folder: String,
    val updatedAt: Long
)

class WidgetDataHelper(
    private val userDao: UserDao,
    private val preferencesManager: PreferencesManager,
    private val privateNoteDao: PrivateNoteDao,
    private val favoriteNoteDao: FavoriteNoteDao,
    private val calendarEventDao: CalendarEventDao
) {

    private val calendarMapper = CalendarMapper()

    // ------------------------------------------------------------------
    // Notes
    // ------------------------------------------------------------------

    /**
     * Pull notes for the Notes widget according to its configured mode
     * ([NotesWidgetMode]). Notes are read purely from the local Room cache
     * (the app keeps them synced during normal use), so this never hits a
     * relay.
     */
    suspend fun getNoteItems(context: Context, widgetId: Int, limit: Int = 5): List<NoteDisplayItem> {
        val currentNpub = preferencesManager.npubKey.first() ?: return emptyList()
        val mode = getNotesMode(context, widgetId)
        val favIds = favoriteNoteDao.observeNoteIds(currentNpub).first().toSet()
        // FAVORITES mode with no pins → nothing to show yet.
        if (mode == NotesWidgetMode.FAVORITES && favIds.isEmpty()) return emptyList()
        val rows = privateNoteDao.observeNotes(currentNpub).first()
        return buildNoteItems(rows, favIds, mode, limit)
    }

    /**
     * Pure transformation from cached note rows to widget display items.
     * Extracted from [getNoteItems] so the selection/sorting/mapping logic is
     * unit-testable without Android or DAO scaffolding.
     */
    internal fun buildNoteItems(
        rows: List<PrivateNoteEntity>,
        favIds: Set<String>,
        mode: NotesWidgetMode,
        limit: Int
    ): List<NoteDisplayItem> {
        val selected = when (mode) {
            NotesWidgetMode.RECENT -> rows
            NotesWidgetMode.FAVORITES -> rows.filter { it.id in favIds }
        }
        return selected
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .map { it.toDisplayItem() }
    }

    private fun PrivateNoteEntity.toDisplayItem(): NoteDisplayItem =
        NoteDisplayItem(
            id = id,
            title = title.ifBlank { "(untitled)" },
            bodyPreview = body.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "",
            folder = folder,
            updatedAt = updatedAt
        )

    // ------------------------------------------------------------------
    // Calendar widget
    // ------------------------------------------------------------------

    /**
     * Cached calendar events overlapping `[rangeStartSec, rangeEndSec)` for
     * the active account. Read purely from the local Room cache (the app
     * keeps it synced during normal use), so this never hits a relay.
     *
     * Recurring events are expanded into virtual occurrences here — the
     * stored row is only the anchor, so an infinitely recurring series costs
     * one row. Occurrence copies share the entity's id/payload with shifted
     * [CalendarEventEntity.startSec]/[CalendarEventEntity.endSec].
     */
    suspend fun getCalendarEventsInRange(rangeStartSec: Long, rangeEndSec: Long): List<CalendarEventEntity> {
        val currentNpub = preferencesManager.npubKey.first() ?: return emptyList()
        val direct = calendarEventDao.eventsInRange(currentNpub, rangeStartSec, rangeEndSec)
            .filter { it.recurFreq == null }
        val recurringRows = calendarEventDao.recurringEvents(currentNpub)
        if (recurringRows.isEmpty()) return direct.sortedBy { it.startSec }

        val byId = recurringRows.associateBy { it.id }
        val occurrences = RecurringEventExpander.expandAll(
            recurringRows.mapNotNull { runCatching { calendarMapper.entityToDomain(it) }.getOrNull() },
            rangeStartSec,
            rangeEndSec
        ).mapNotNull { occ -> byId[occ.id]?.let { anchor -> anchor.copy(startSec = occ.startSec, endSec = occ.endSec) } }
        return (direct + occurrences).sortedBy { it.startSec }
    }

    // ------------------------------------------------------------------
    // Notes widget mode persistence (per widget instance)
    // ------------------------------------------------------------------

    companion object {
        private const val NOTES_PREFS = "desent_notes_widget_prefs"
        private fun notesModeKey(widgetId: Int) = "notes_mode_$widgetId"

        private const val CALENDAR_PREFS = "desent_calendar_widget_prefs"
        private fun calendarViewKey(widgetId: Int) = "view_$widgetId"
        private fun calendarAnchorKey(widgetId: Int) = "anchor_$widgetId"
    }

    fun getNotesMode(context: Context, widgetId: Int): NotesWidgetMode {
        val prefs = context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)
        return NotesWidgetMode.fromName(prefs.getString(notesModeKey(widgetId), null))
    }

    fun setNotesMode(context: Context, widgetId: Int, mode: NotesWidgetMode) {
        val prefs = context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(notesModeKey(widgetId), mode.name).apply()
    }

    // ------------------------------------------------------------------
    // Calendar widget state persistence (per widget instance)
    // ------------------------------------------------------------------

    fun getCalendarView(context: Context, widgetId: Int): CalendarWidgetView {
        val prefs = context.getSharedPreferences(CALENDAR_PREFS, Context.MODE_PRIVATE)
        return CalendarWidgetView.fromName(prefs.getString(calendarViewKey(widgetId), null))
    }

    fun setCalendarView(context: Context, widgetId: Int, view: CalendarWidgetView) {
        val prefs = context.getSharedPreferences(CALENDAR_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(calendarViewKey(widgetId), view.name).apply()
    }

    /**
     * The anchored day the widget is browsing, as an epoch day. `null` means
     * "follow today" — the widget resolves the current date on every render so
     * it rolls over at midnight without any stored state going stale.
     */
    fun getCalendarAnchor(context: Context, widgetId: Int): Long? {
        val prefs = context.getSharedPreferences(CALENDAR_PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(calendarAnchorKey(widgetId))) {
            prefs.getLong(calendarAnchorKey(widgetId), 0L)
        } else {
            null
        }
    }

    fun setCalendarAnchor(context: Context, widgetId: Int, epochDay: Long?) {
        val prefs = context.getSharedPreferences(CALENDAR_PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (epochDay == null) editor.remove(calendarAnchorKey(widgetId)) else editor.putLong(calendarAnchorKey(widgetId), epochDay)
        editor.apply()
    }

    /** Reset every calendar widget instance back to "follow today" (day rollover). */
    fun resetCalendarAnchorsToToday(context: Context) {
        val prefs = context.getSharedPreferences(CALENDAR_PREFS, Context.MODE_PRIVATE)
        val keys = prefs.all.keys.filter { it.startsWith("anchor_") }
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }
}

/** Configurable display mode for the Notes widget. */
enum class NotesWidgetMode {
    RECENT,
    FAVORITES;

    companion object {
        fun fromName(name: String?): NotesWidgetMode =
            entries.firstOrNull { it.name == name } ?: RECENT
    }
}

/** Configurable view for the Calendar widget. */
enum class CalendarWidgetView {
    DAY,
    WEEK,
    MONTH;

    companion object {
        fun fromName(name: String?): CalendarWidgetView =
            entries.firstOrNull { it.name == name } ?: MONTH
    }
}

/** One event row prepared for the Calendar widget agenda (day/week views). */
data class CalendarWidgetEventItem(
    val id: String,
    val title: String,
    /** Pre-formatted ("9:00 AM", "9:00 AM – 10:30 AM", "All day"). */
    val timeLabel: String,
    val allDay: Boolean
)

/** Rows for the Calendar widget agenda list (day/week views). */
sealed class CalendarAgendaItem {
    data class DayHeader(val epochDay: Long, val label: String) : CalendarAgendaItem()
    data class Event(val event: CalendarWidgetEventItem) : CalendarAgendaItem()
    data object Empty : CalendarAgendaItem()
}

/** One cell of the Calendar widget month grid. */
data class CalendarMonthCell(
    val epochDay: Long,
    val dayOfMonth: Int,
    val inMonth: Boolean,
    val isToday: Boolean,
    val eventCount: Int
)

// ---------------------------------------------------------------------
// Avatar bitmap shaping (RemoteViews cannot clip, so avatars are cropped
// in code to the canonical DeSent rounded-square profile shape)
// ---------------------------------------------------------------------

/**
 * Center-crops [source] to a square and rounds its corners to the canonical
 * DeSent avatar shape: radius = 28% of the side (see the branding doc's
 * "Profile Pictures" section — matches the app's AvatarShape). Returns an
 * ARGB_8888 bitmap with transparent corners; safe for any input config.
 */
internal fun roundedAvatarBitmap(source: Bitmap): Bitmap {
    val side = minOf(source.width, source.height)
    val square = if (source.width == source.height && source.config == Bitmap.Config.ARGB_8888) {
        source
    } else {
        val cropped = if (source.width == source.height) source else Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side,
            side
        )
        if (cropped.config == Bitmap.Config.ARGB_8888) cropped else cropped.copy(Bitmap.Config.ARGB_8888, false)
    }
    val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    val radius = side * AVATAR_CORNER_RADIUS_RATIO
    canvas.drawRoundRect(0f, 0f, side.toFloat(), side.toFloat(), radius, radius, paint)
    return output
}

/** Corner-radius/side ratio of the canonical DeSent avatar shape. */
internal const val AVATAR_CORNER_RADIUS_RATIO = 0.28f

// ---------------------------------------------------------------------
// Calendar widget pure builders (unit-testable, no Android dependencies)
// ---------------------------------------------------------------------

private val agendaDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
private val agendaTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

/** Half-open day window `[startSec, endSec)` for [date] in [zone], unix seconds. */
internal fun dayRangeSec(date: LocalDate, zone: ZoneId): Pair<Long, Long> {
    val start = date.atStartOfDay(zone).toEpochSecond()
    val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    return start to end
}

/** First day (inclusive) of the week containing [anchor], per [firstDayOfWeek]. */
internal fun weekStart(anchor: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
    val offset = (anchor.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    return anchor.minusDays(offset.toLong())
}

/** First cell of the 6x7 month grid containing [anchor]'s month. */
internal fun monthGridStart(anchor: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate =
    weekStart(anchor.withDayOfMonth(1), firstDayOfWeek)

/**
 * The entity's day span `[startDay, endDay)` in epoch days, via
 * [NostrCalendarEvent.daySpan] — all-day (kind 31922) rows derive their days
 * from the UTC-midnight index (never a zone conversion; the ISO dates are
 * floating local dates), time-based rows convert their instants in [zone].
 */
internal fun CalendarEventEntity.daySpan(zone: ZoneId): Pair<Long, Long> =
    NostrCalendarEvent.daySpan(startSec, endSec ?: startSec, kind == NostrCalendarEvent.KIND_DATE, zone)

/**
 * Group cached events into per-day counts for the month grid. An event
 * covering multiple days contributes to every day it overlaps. Instantaneous
 * time-based events (null end) yield an empty span and count for nothing,
 * matching the widget's historical behavior.
 */
internal fun buildEventDayCounts(
    events: List<CalendarEventEntity>,
    rangeStart: LocalDate,
    days: Int,
    zone: ZoneId
): Map<Long, Int> = buildSpanDayCounts(
    spans = events.map { it.daySpan(zone) },
    rangeStart = rangeStart,
    days = days
)

/**
 * Span-based core of the month-grid day counting: each pair is a day span
 * `[startDay, endDay)` in epoch days, already resolved per the event's kind
 * (see [daySpan]). Spans are clipped to the window; a multi-day span counts
 * on every day it overlaps.
 */
internal fun buildSpanDayCounts(
    spans: List<Pair<Long, Long>>,
    rangeStart: LocalDate,
    days: Int
): Map<Long, Int> {
    val counts = mutableMapOf<Long, Int>()
    val rangeStartDay = rangeStart.toEpochDay()
    val rangeEndDay = rangeStartDay + days
    for ((startDayRaw, endDayRaw) in spans) {
        val startDay = maxOf(startDayRaw, rangeStartDay)
        val endDay = minOf(endDayRaw, rangeEndDay)
        for (day in startDay until endDay) {
            counts.merge(day, 1, Int::plus)
        }
    }
    return counts
}

/** Build the 42-cell month grid for the month containing [anchor]. */
internal fun buildMonthCells(
    anchor: LocalDate,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    eventDayCounts: Map<Long, Int>
): List<CalendarMonthCell> {
    val gridStart = monthGridStart(anchor, firstDayOfWeek)
    return (0 until 42).map { i ->
        val date = gridStart.plusDays(i.toLong())
        CalendarMonthCell(
            epochDay = date.toEpochDay(),
            dayOfMonth = date.dayOfMonth,
            inMonth = date.year == anchor.year && date.month == anchor.month,
            isToday = date == today,
            eventCount = eventDayCounts[date.toEpochDay()] ?: 0
        )
    }
}

/** Events overlapping [date] (day-span membership), sorted by start. */
private fun eventsOnDay(
    events: List<CalendarEventEntity>,
    date: LocalDate,
    zone: ZoneId
): List<CalendarEventEntity> {
    val day = date.toEpochDay()
    return events.filter { event ->
        val span = event.daySpan(zone)
        span.first <= day && day < span.second
    }.sortedBy { it.startSec }
}

/** Agenda rows for the day view: just the events of the anchor day. */
internal fun buildDayAgendaItems(
    events: List<CalendarEventEntity>,
    anchor: LocalDate,
    zone: ZoneId
): List<CalendarAgendaItem> {
    val dayEvents = eventsOnDay(events, anchor, zone)
    if (dayEvents.isEmpty()) return listOf(CalendarAgendaItem.Empty)
    return dayEvents.map { it.toAgendaEvent(zone) }
}

/**
 * Agenda rows for the week view: day headers interleaved with events.
 * Days with no events are skipped (matches the in-app agenda behavior).
 */
internal fun buildWeekAgendaItems(
    events: List<CalendarEventEntity>,
    anchor: LocalDate,
    firstDayOfWeek: DayOfWeek,
    zone: ZoneId
): List<CalendarAgendaItem> {
    val start = weekStart(anchor, firstDayOfWeek)
    val out = mutableListOf<CalendarAgendaItem>()
    for (i in 0 until 7) {
        val date = start.plusDays(i.toLong())
        val dayEvents = eventsOnDay(events, date, zone)
        if (dayEvents.isEmpty()) continue
        out.add(CalendarAgendaItem.DayHeader(date.toEpochDay(), date.format(agendaDayFormatter)))
        dayEvents.forEach { out.add(it.toAgendaEvent(zone)) }
    }
    if (out.isEmpty()) out.add(CalendarAgendaItem.Empty)
    return out
}

private fun CalendarEventEntity.toAgendaEvent(zone: ZoneId): CalendarAgendaItem.Event =
    CalendarAgendaItem.Event(
        event = CalendarWidgetEventItem(
            id = id,
            title = title.ifBlank { "(untitled)" },
            timeLabel = formatEventTimeLabel(startSec, endSec, kind == NostrCalendarEvent.KIND_DATE, zone),
            allDay = kind == NostrCalendarEvent.KIND_DATE
        )
    )

/** "All day" / "9:00 AM" / "9:00 AM – 10:30 AM", mirroring the in-app formatter. */
internal fun formatEventTimeLabel(startSec: Long, endSec: Long?, allDay: Boolean, zone: ZoneId): String {
    if (allDay) {
        return if (endSec == null || endSec - startSec <= 86_400L) "All day" else "All day (multi-day)"
    }
    val start = java.time.Instant.ofEpochSecond(startSec).atZone(zone)
    val end = endSec?.let { java.time.Instant.ofEpochSecond(it).atZone(zone) }
    return if (end == null) {
        start.format(agendaTimeFormatter)
    } else {
        "${start.format(agendaTimeFormatter)} – ${end.format(agendaTimeFormatter)}"
    }
}

/** Header title for the widget's current view/anchor. */
internal fun formatCalendarWidgetTitle(view: CalendarWidgetView, anchor: LocalDate): String = when (view) {
    CalendarWidgetView.DAY -> anchor.format(agendaDayFormatter)
    CalendarWidgetView.WEEK -> {
        val start = anchor
        val end = start.plusDays(6)
        if (start.month == end.month) {
            "${start.format(DateTimeFormatter.ofPattern("MMM d"))} – ${end.format(DateTimeFormatter.ofPattern("d"))}"
        } else {
            "${start.format(DateTimeFormatter.ofPattern("MMM d"))} – ${end.format(DateTimeFormatter.ofPattern("MMM d"))}"
        }
    }
    CalendarWidgetView.MONTH -> anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
}

/** Short weekday labels (e.g. "S M T W T F S") starting at [firstDayOfWeek]. */
internal fun weekdayLabels(firstDayOfWeek: DayOfWeek, locale: Locale = Locale.getDefault()): List<String> =
    (0 until 7).map { firstDayOfWeek.plus(it.toLong()).getDisplayName(TextStyle.NARROW, locale) }

/** Localized first day of week for the calendar grid. */
internal fun firstDayOfWeek(locale: Locale = Locale.getDefault()): DayOfWeek =
    java.time.temporal.WeekFields.of(locale).firstDayOfWeek
