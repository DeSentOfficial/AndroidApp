package xyz.desent.presentation.ui.calendar.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.attachment.CalendarAttachmentOpener
import xyz.desent.domain.model.AttachmentMeta
import xyz.desent.domain.model.CalendarParticipant
import xyz.desent.domain.model.CalendarShareEntry
import xyz.desent.domain.model.NostrCalendar
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.NostrCalendarRsvp
import xyz.desent.domain.model.Recurrence
import xyz.desent.domain.model.RecurFreq
import xyz.desent.domain.model.RsvpStatus
import xyz.desent.domain.repository.LocationResolver
import xyz.desent.domain.repository.ResolvedLocation
import xyz.desent.domain.usecase.CalendarUseCase
import xyz.desent.domain.util.GeohashUtils
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** How a recurrence ends. */
enum class RecurEndType { NEVER, UNTIL, COUNT }

data class CalendarEventEditorUiState(
    val eventId: String? = null,
    val title: String = "",
    val allDay: Boolean = false,
    /** Epoch millis (start). For all-day events: local midnight of the start day. */
    val startMs: Long = System.currentTimeMillis(),
    /** Epoch millis (end); null = open-ended / instantaneous. All-day: local midnight of the last covered day. */
    val endMs: Long? = null,
    val location: String = "",
    /**
     * Resolved geohash (7 chars, refs/CALENDAR_PROTOCOL.md) for [location],
     * when the user picked a resolved candidate or pasted coordinates. Nulls
     * whenever the text is edited — a stale hash must never survive an edit.
     */
    val geohash: String? = null,
    /** True while a geocoder lookup is in flight. */
    val isResolving: Boolean = false,
    /** Candidate results for the current location query; empty = none shown. */
    val resolveCandidates: List<ResolvedLocation> = emptyList(),
    val description: String = "",
    /** Recurrence frequency; null = one-off event. */
    val recurFreq: RecurFreq? = null,
    /** Every N periods (e.g. 2 = every other week). */
    val recurInterval: Int = 1,
    /** WEEKLY BYDAY selection; empty = the start-date weekday only. */
    val recurWeeklyDays: Set<DayOfWeek> = emptySet(),
    /** MONTHLY/YEARLY: use an nth-weekday rule instead of the start day-of-month. */
    val useNthWeekday: Boolean = false,
    val nthWeekday: DayOfWeek = DayOfWeek.MONDAY,
    /** 1..4 = first..fourth, -1 = last, 0 = every such weekday of the month. */
    val nthOrdinal: Int = 1,
    val recurEndType: RecurEndType = RecurEndType.NEVER,
    /** Inclusive last-occurrence date (epoch millis at local midnight). */
    val recurUntilMs: Long? = null,
    val recurCount: Int = 8,
    val attachments: List<AttachmentMeta> = emptyList(),
    val calendarD: String? = null,
    val availableCalendars: List<NostrCalendar> = emptyList(),
    val shares: List<CalendarShareEntry> = emptyList(),
    val participants: List<CalendarParticipant> = emptyList(),
    val canRespond: Boolean = false,
    val rsvps: List<NostrCalendarRsvp> = emptyList(),
    val isSaving: Boolean = false,
    val isUploading: Boolean = false,
    val saved: Boolean = false,
    val toast: String? = null
) {
    /**
     * Never-ending yearly rules are birthday-style: the anchor year is
     * irrelevant, so date UIs hide it (display only — the full anchor date is
     * still stored, expansion needs it).
     */
    val hidesAnchorYear: Boolean
        get() = recurFreq == RecurFreq.YEARLY && recurEndType == RecurEndType.NEVER
}

/** Fired when a decrypted attachment is ready to hand to an ACTION_VIEW intent. */
data class CalendarAttachmentOpenEvent(val uri: Uri, val mimeType: String)

/**
 * New-event prefill (contact-anniversary "Add to calendar"): opens the
 * all-day (kind 31922) editor on the next occurrence of an MM-DD with the
 * conventional title. Nothing here implies recurrence — the protocol has
 * none, so the saved event is a single day.
 *
 * Agent proposals (ANDROID_AI_AGENTS.md §6) additionally pass [startSec]/
 * [endSec]/[location]: when both a start AND end exist the editor opens
 * time-based (kind 31923), else all-day as above.
 */
data class CalendarEventPrefill(
    val title: String,
    val epochDay: Long,
    val description: String = "",
    /** Agent-proposed start, unix seconds; 0 = use [epochDay] all-day. */
    val startSec: Long = 0L,
    /** Agent-proposed end, unix seconds; 0/≤start = all-day (31922). */
    val endSec: Long = 0L,
    val location: String = ""
)

class CalendarEventEditorViewModel(
    private val useCase: CalendarUseCase,
    private val opener: CalendarAttachmentOpener,
    private val locationResolver: LocationResolver,
    /** null = new event. */
    private val eventId: String?,
    /** New-event prefill; ignored when [eventId] is set. */
    private val prefill: CalendarEventPrefill? = null,
    /**
     * The zone date pickers and all-day conversions resolve in. All-day
     * (31922) dates are floating local dates — they must be built and read
     * at local midnight, never UTC (CALENDAR_PROTOCOL.md §Date-based event).
     * Injected for deterministic tests; production uses the device zone.
     */
    private val zone: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarEventEditorUiState(eventId = eventId))
    val uiState: StateFlow<CalendarEventEditorUiState> = _uiState.asStateFlow()

    private val _openEvents = MutableSharedFlow<CalendarAttachmentOpenEvent>(extraBufferCapacity = 4)
    val openEvents: SharedFlow<CalendarAttachmentOpenEvent> = _openEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub()
            val activeHex = secureActiveHex()
            if (eventId != null && npub != null) {
                val event = useCase.getEvent(npub, eventId)
                if (event != null) {
                    val organizerHex = event.participants.firstOrNull { it.role == "organizer" }?.pubkey
                    val canRespond = organizerHex != null && organizerHex != activeHex
                    // All-day events hydrate at local midnight of the floating
                    // ISO days (never the UTC index — that displays a day off
                    // for any non-UTC viewer). The editor's End is the LAST
                    // covered day, i.e. the wire's exclusive end minus one.
                    val startMs: Long
                    val endMs: Long?
                    if (event.allDay) {
                        val startDay = Math.floorDiv(event.startSec, NostrCalendarEvent.SECONDS_PER_DAY)
                        val endDayExclusive = Math.floorDiv(
                            event.endSec ?: (event.startSec + NostrCalendarEvent.SECONDS_PER_DAY),
                            NostrCalendarEvent.SECONDS_PER_DAY
                        )
                        startMs = LocalDate.ofEpochDay(startDay).atStartOfDay(zone).toInstant().toEpochMilli()
                        endMs = if (endDayExclusive - startDay > 1) {
                            LocalDate.ofEpochDay(endDayExclusive - 1).atStartOfDay(zone).toInstant().toEpochMilli()
                        } else {
                            null
                        }
                    } else {
                        startMs = event.startSec * 1000L
                        endMs = event.endSec?.times(1000L)
                    }
                    _uiState.value = _uiState.value.copy(
                        eventId = event.id,
                        title = event.title,
                        allDay = event.allDay,
                        startMs = startMs,
                        endMs = endMs,
                        location = event.location.orEmpty(),
                        geohash = event.geohash,
                        description = event.description.orEmpty(),
                        attachments = event.attachments,
                        calendarD = event.calendarD,
                        shares = event.shares,
                        participants = event.participants,
                        canRespond = canRespond
                    )
                    // Hydrate the recurrence editor state from the stored rule.
                    event.recurrence?.let { rule ->
                        _uiState.value = _uiState.value.withRecurrence(rule)
                    }
                    // If this is the user's own event, show received RSVP counts.
                    if (!canRespond) {
                        useCase.observeRsvps(npub, event.dTag).collect { rsvps ->
                            _uiState.value = _uiState.value.copy(rsvps = rsvps)
                        }
                    }
                }
            } else if (prefill != null) {
                if (prefill.startSec > 0 && prefill.endSec > prefill.startSec) {
                    // Agent proposal with a time range (§6): time-based
                    // editor (kind 31923), instants exactly as proposed.
                    _uiState.value = _uiState.value.copy(
                        title = prefill.title,
                        allDay = false,
                        startMs = prefill.startSec * 1000L,
                        endMs = prefill.endSec * 1000L,
                        location = prefill.location,
                        description = prefill.description
                    )
                } else {
                    // Prefilled all-day event (contact anniversary): local
                    // midnight of the requested day, single day, no recurrence.
                    val start = LocalDate.ofEpochDay(prefill.epochDay)
                        .atStartOfDay(zone).toInstant().toEpochMilli()
                    _uiState.value = _uiState.value.copy(
                        title = prefill.title,
                        allDay = true,
                        startMs = start,
                        endMs = null,
                        location = prefill.location,
                        description = prefill.description
                    )
                }
            } else if (npub != null) {
                // New event: default to the top of the next hour, duration 1h.
                val now = Instant.now().atZone(zone)
                val start = now.toLocalDate().atTime(now.hour, 0).plusHours(1).atZone(zone).toInstant().toEpochMilli()
                _uiState.value = _uiState.value.copy(startMs = start, endMs = start + 60L * 60_000L)
            }
        }
        // Observe available calendars for the picker.
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: return@launch
            useCase.observeCalendars(npub).collect { calendars ->
                _uiState.value = _uiState.value.copy(availableCalendars = calendars)
            }
        }
    }

    private suspend fun secureActiveHex(): String? =
        useCase.activeOwnerNpub()?.let { runCatching { Bech32Utils.npubToHex(it) }.getOrNull() }

    fun updateTitle(v: String) { _uiState.value = _uiState.value.copy(title = v) }
    fun updateAllDay(v: Boolean) { _uiState.value = _uiState.value.copy(allDay = v) }
    fun updateStart(ms: Long) {
        val cur = _uiState.value
        _uiState.value = cur.copy(
            startMs = ms,
            endMs = if (cur.endMs != null && cur.endMs < ms) ms else cur.endMs
        )
    }
    fun updateEnd(ms: Long?) { _uiState.value = _uiState.value.copy(endMs = ms) }
    /**
     * Editing the text invalidates any previously resolved geohash and hides
     * the candidates the old query produced.
     */
    fun updateLocation(v: String) {
        _uiState.value = _uiState.value.copy(
            location = v,
            geohash = null,
            resolveCandidates = emptyList()
        )
    }

    // ------------------------------------------------------------------
    // Location resolution (geohash)
    // ------------------------------------------------------------------

    /**
     * Resolves the current location text to coordinate candidates. Pasted
     * coordinates (or a `geo:` URI) resolve offline — no geocoder, no
     * network; anything else goes through the injected [LocationResolver].
     */
    fun resolveLocation() {
        val state = _uiState.value
        val query = state.location.trim()
        if (query.isBlank()) {
            _uiState.value = state.copy(toast = "Enter a location to resolve")
            return
        }
        GeohashUtils.parseCoordinates(query)?.let { point ->
            _uiState.value = state.copy(
                resolveCandidates = listOf(
                    ResolvedLocation(
                        displayName = String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude),
                        point = point
                    )
                )
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isResolving = true)
            val result = locationResolver.resolve(query)
            val candidates = result.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(
                isResolving = false,
                resolveCandidates = candidates,
                toast = result.fold(
                    onSuccess = { if (it.isEmpty()) "No matches for \"${query.take(48)}\"" else null },
                    onFailure = { "Location lookup failed: ${it.message}" }
                )
            )
        }
    }

    /**
     * Commits a picked candidate: the display text is replaced with the
     * canonical resolved address and the geohash is encoded from its
     * coordinates (7 chars ≈ 150 m cell).
     */
    fun selectCandidate(candidate: ResolvedLocation) {
        _uiState.value = _uiState.value.copy(
            location = candidate.displayName,
            geohash = GeohashUtils.encode(candidate.point.latitude, candidate.point.longitude),
            resolveCandidates = emptyList()
        )
    }

    /** Drops the resolved geohash but keeps the display text. */
    fun clearResolvedLocation() {
        _uiState.value = _uiState.value.copy(geohash = null)
    }

    /** Hides the candidate list without picking anything. */
    fun dismissCandidates() {
        _uiState.value = _uiState.value.copy(resolveCandidates = emptyList())
    }

    fun updateDescription(v: String) { _uiState.value = _uiState.value.copy(description = v) }
    fun setCalendarD(dTag: String?) { _uiState.value = _uiState.value.copy(calendarD = dTag) }
    fun clearToast() { _uiState.value = _uiState.value.copy(toast = null) }

    // ------------------------------------------------------------------
    // Recurrence editing
    // ------------------------------------------------------------------

    fun updateRecurFreq(freq: RecurFreq?) {
        val cur = _uiState.value
        _uiState.value = cur.copy(
            recurFreq = freq,
            // Switching to a nth-weekday rule only makes sense for monthly/yearly.
            useNthWeekday = if (freq == RecurFreq.MONTHLY || freq == RecurFreq.YEARLY) cur.useNthWeekday else false
        )
    }

    fun updateRecurInterval(delta: Int) {
        val next = (_uiState.value.recurInterval + delta).coerceIn(1, 99)
        _uiState.value = _uiState.value.copy(recurInterval = next)
    }

    fun toggleRecurWeeklyDay(day: DayOfWeek) {
        val cur = _uiState.value
        val days = if (day in cur.recurWeeklyDays) cur.recurWeeklyDays - day else cur.recurWeeklyDays + day
        _uiState.value = cur.copy(recurWeeklyDays = days)
    }

    fun setUseNthWeekday(use: Boolean) { _uiState.value = _uiState.value.copy(useNthWeekday = use) }
    fun setNthWeekday(day: DayOfWeek) { _uiState.value = _uiState.value.copy(nthWeekday = day) }
    fun setNthOrdinal(ordinal: Int) { _uiState.value = _uiState.value.copy(nthOrdinal = ordinal) }

    fun setRecurEndType(type: RecurEndType) {
        val cur = _uiState.value
        _uiState.value = cur.copy(
            recurEndType = type,
            // Default the bound the first time it is needed.
            recurUntilMs = if (type == RecurEndType.UNTIL && cur.recurUntilMs == null) {
                Instant.ofEpochMilli(cur.startMs).atZone(zone).toLocalDate().plusYears(1)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
            } else {
                cur.recurUntilMs
            }
        )
    }

    fun setRecurUntil(ms: Long) { _uiState.value = _uiState.value.copy(recurUntilMs = ms) }

    fun updateRecurCount(delta: Int) {
        val next = (_uiState.value.recurCount + delta).coerceIn(1, 999)
        _uiState.value = _uiState.value.copy(recurCount = next)
    }

    fun addAttachment(data: ByteArray, mimeType: String, fileName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true)
            val result = useCase.uploadAttachment(data, mimeType, fileName)
            _uiState.value = _uiState.value.copy(isUploading = false)
            result.onSuccess { meta ->
                _uiState.value = _uiState.value.copy(attachments = _uiState.value.attachments + meta)
            }.onFailure {
                _uiState.value = _uiState.value.copy(toast = "Upload failed: ${it.message}")
            }
        }
    }

    fun removeAttachment(meta: AttachmentMeta) {
        _uiState.value = _uiState.value.copy(
            attachments = _uiState.value.attachments.filterNot { it.sha256 == meta.sha256 }
        )
    }

    fun openAttachment(meta: AttachmentMeta) {
        viewModelScope.launch {
            opener.open(meta).onSuccess { (uri, mimeType) ->
                _openEvents.emit(CalendarAttachmentOpenEvent(uri, mimeType))
            }.onFailure {
                _uiState.value = _uiState.value.copy(toast = "Can't open attachment: ${it.message}")
            }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.value = state.copy(toast = "Title is required")
            return
        }
        if (state.recurEndType == RecurEndType.UNTIL && state.recurUntilMs != null &&
            state.recurUntilMs < startOfDayMillis(state.startMs)
        ) {
            _uiState.value = state.copy(toast = "Recurrence must end on or after the start date")
            return
        }
        if (state.recurEndType == RecurEndType.UNTIL && state.recurUntilMs == null) {
            _uiState.value = state.copy(toast = "Pick an end date for the recurrence")
            return
        }
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: run {
                _uiState.value = state.copy(toast = "No active account")
                return@launch
            }
            _uiState.value = state.copy(isSaving = true)
            val id = state.eventId ?: UUID.randomUUID().toString()
            val event = buildEvent(state, npub, id)
            val result = useCase.saveEvent(event)
            // If a calendar is selected, keep that calendar's membership in sync.
            if (result.isSuccess && state.calendarD != null) {
                useCase.addEventToCalendar(npub, state.calendarD.removePrefix(NostrCalendar.D_PREFIX), event.dTag)
            }
            // Re-push the updated event to every prior share recipient.
            if (result.isSuccess && event.shares.isNotEmpty()) {
                useCase.pushEventUpdateToShares(event)
            }
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                saved = result.isSuccess,
                toast = if (result.isSuccess) "Event saved" else "Save failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    /**
     * Share the current event with [recipientNpub]. Persists the event first if
     * it is new, then gift-wraps the plaintext and pushes it to the recipient,
     * recording them in the encrypted share ledger.
     */
    fun shareEvent(recipientNpub: String, role: String) {
        val state = _uiState.value
        if (recipientNpub.isBlank()) {
            _uiState.value = state.copy(toast = "Recipient is required")
            return
        }
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: run {
                _uiState.value = state.copy(toast = "No active account")
                return@launch
            }
            _uiState.value = state.copy(isSaving = true)
            val id = state.eventId ?: UUID.randomUUID().toString()
            // Build the current event from editor state (ensure it is persisted first).
            val event = buildEvent(state, npub, id)
            val saveResult = useCase.saveEvent(event)
            if (saveResult.isFailure) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    toast = "Share failed: ${saveResult.exceptionOrNull()?.message}"
                )
                return@launch
            }
            val shareResult = useCase.shareEvent(event, recipientNpub.trim(), role)
            // Refresh shares in state from the updated event ledger.
            val refreshed = useCase.getEvent(npub, id)
            _uiState.value = _uiState.value.copy(
                eventId = id,
                isSaving = false,
                shares = refreshed?.shares ?: event.shares,
                toast = if (shareResult.isSuccess) "Shared with recipient" else "Share failed: ${shareResult.exceptionOrNull()?.message}"
            )
        }
    }

    private fun buildEvent(state: CalendarEventEditorUiState, npub: String, id: String): NostrCalendarEvent {
        val (startSec, endSec, startDateIso, endDateIso) = if (state.allDay) {
            // All-day picks arrive as local-midnight millis; resolve the
            // calendar day in the local zone (never UTC floor-division,
            // which stores the previous day east of UTC). The wire end ISO
            // is exclusive per the protocol: the picked last day plus one,
            // omitted for single-day events.
            val startDate = Instant.ofEpochMilli(state.startMs).atZone(zone).toLocalDate()
            val lastDay = state.endMs
                ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                ?.takeIf { !it.isBefore(startDate) }
            val sSec = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val eSec = (lastDay ?: startDate).plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            Quad(sSec, eSec, startDate.toString(), lastDay?.plusDays(1)?.toString())
        } else {
            Quad(state.startMs / 1000L, state.endMs?.let { it / 1000L }, null, null)
        }
        return NostrCalendarEvent(
            id = id,
            ownerNpub = npub,
            kind = if (state.allDay) NostrCalendarEvent.KIND_DATE else NostrCalendarEvent.KIND_TIME,
            dTag = NostrCalendarEvent.D_PREFIX + id,
            title = state.title.trim(),
            startSec = startSec, endSec = endSec, allDay = state.allDay,
            startDateIso = startDateIso, endDateIso = endDateIso,
            startTzid = null, endTzid = null, summary = null,
            description = state.description.ifBlank { null },
            location = state.location.ifBlank { null },
            geohash = state.geohash, image = null,
            links = emptyList(), hashtags = emptyList(),
            calendarD = state.calendarD,
            attachments = state.attachments,
            shares = state.shares,
            participants = state.participants,
            recurrence = buildRecurrence(state),
            updatedAt = System.currentTimeMillis() / 1000,
            createdAt = System.currentTimeMillis() / 1000
        )
    }

    /** Translate the editor's recurrence UI state into a [Recurrence] rule; null = one-off. */
    private fun buildRecurrence(state: CalendarEventEditorUiState): Recurrence? {
        val freq = state.recurFreq ?: return null
        val anchorDow = Instant.ofEpochMilli(state.startMs).atZone(zone).dayOfWeek
        val byDay = when (freq) {
            RecurFreq.DAILY -> emptyList()
            RecurFreq.WEEKLY ->
                (state.recurWeeklyDays.ifEmpty { setOf(anchorDow) })
                    .sortedBy { it.value }
                    .map { it.code() }
            RecurFreq.MONTHLY, RecurFreq.YEARLY -> if (state.useNthWeekday) {
                val prefix = when (state.nthOrdinal) {
                    in 1..4 -> state.nthOrdinal.toString()
                    -1 -> "-1"
                    else -> "" // every such weekday of the month
                }
                listOf(prefix + state.nthWeekday.code())
            } else {
                emptyList()
            }
        }
        return Recurrence(
            freq = freq,
            interval = state.recurInterval.coerceIn(1, 99),
            byDay = byDay,
            untilIso = if (state.recurEndType == RecurEndType.UNTIL && state.recurUntilMs != null) {
                localDate(state.recurUntilMs).toString()
            } else {
                null
            },
            count = if (state.recurEndType == RecurEndType.COUNT) state.recurCount.coerceIn(1, 999) else null,
            omitYear = freq == RecurFreq.YEARLY && state.recurEndType == RecurEndType.NEVER
        )
    }

    /** Hydrate the editor state from a stored rule (edit flow). */
    private fun CalendarEventEditorUiState.withRecurrence(rule: Recurrence): CalendarEventEditorUiState {
        val anchorDow = Instant.ofEpochMilli(startMs).atZone(zone).dayOfWeek
        val ordinalEntry = rule.byDay.firstOrNull { it.length > 2 }
        val plainEntry = rule.byDay.firstOrNull { it.length == 2 }
        val parsedOrdinal = ordinalEntry?.dropLast(2)?.toIntOrNull()
        return copy(
            recurFreq = rule.freq,
            recurInterval = rule.interval.coerceIn(1, 99),
            recurWeeklyDays = if (rule.freq == RecurFreq.WEEKLY) {
                rule.byDay.mapNotNull { it.toDayOfWeek() }.toSet()
            } else {
                emptySet()
            },
            useNthWeekday = (rule.freq == RecurFreq.MONTHLY || rule.freq == RecurFreq.YEARLY) &&
                rule.byDay.isNotEmpty(),
            nthWeekday = (ordinalEntry ?: plainEntry)?.takeLast(2)?.let { code ->
                DayOfWeek.values().firstOrNull { it.name.startsWith(code, ignoreCase = true) }
            } ?: anchorDow,
            nthOrdinal = when {
                ordinalEntry != null && parsedOrdinal != null && parsedOrdinal != 0 -> parsedOrdinal
                plainEntry != null && rule.freq != RecurFreq.WEEKLY -> 0 // every such weekday
                else -> 1
            },
            recurEndType = when {
                rule.untilIso != null -> RecurEndType.UNTIL
                rule.count != null -> RecurEndType.COUNT
                else -> RecurEndType.NEVER
            },
            recurUntilMs = rule.untilIso?.let {
                runCatching { LocalDate.parse(it.substringBefore('T')) }.getOrNull()
                    ?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            },
            recurCount = rule.count ?: recurCount
        )
    }

    /** The calendar date a picker-chosen epoch-millis value lands on, in [zone]. */
    private fun localDate(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** Local-midnight millis of the day [ms] lands on, in [zone]. */
    private fun startOfDayMillis(ms: Long): Long =
        localDate(ms).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun DayOfWeek.code(): String = name.take(2)

    private fun String.toDayOfWeek(): DayOfWeek? =
        DayOfWeek.values().firstOrNull { it.name.startsWith(takeLast(2), ignoreCase = true) }

    fun sendRsvp(status: RsvpStatus) {
        val state = _uiState.value
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: run {
                _uiState.value = state.copy(toast = "No active account"); return@launch
            }
            val id = state.eventId ?: run {
                _uiState.value = state.copy(toast = "Save the event first"); return@launch
            }
            val event = buildEvent(state, npub, id)
            val result = useCase.sendRsvp(event, status, freebusy = null, note = null)
            _uiState.value = _uiState.value.copy(
                toast = if (result.isSuccess) "RSVP sent (${status.wire})" else "RSVP failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    private data class Quad(
        val startSec: Long,
        val endSec: Long?,
        val startDateIso: String?,
        val endDateIso: String?
    )
}
