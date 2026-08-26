package com.tjcelaya.calwrite.ui.ledger

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.tjcelaya.calwrite.data.CalendarRepository
import com.tjcelaya.calwrite.data.EventRepository
import com.tjcelaya.calwrite.data.ExtendResult
import com.tjcelaya.calwrite.data.database.EventWithType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LedgerViewModel(
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository
) : ViewModel() {

    companion object {
        private const val TAG = "LedgerViewModel"
        private const val RECENT_EVENT_LIMIT = 200
    }

    /**
     * Rows the user has swiped away but whose Snackbar undo window is still open. They are hidden
     * from the list immediately and only actually deleted once the window closes, which avoids
     * deleting and re-creating the calendar copy just to support undo.
     */
    private val pendingDeletions = MutableStateFlow<Set<Long>>(emptySet())

    val days: LiveData<List<LedgerDay>> =
        combine(
            eventRepository.getRecentCompletedEventsWithType(RECENT_EVENT_LIMIT),
            pendingDeletions
        ) { events, pending ->
            LedgerGrouping.groupByDay(events.filterNot { it.id in pending })
        }.asLiveData()

    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> = _message

    /** The most recent extend, so the fragment can offer a one-shot revert. */
    private var lastExtend: ExtendResult? = null

    fun consumeMessage() {
        _message.value = null
    }

    // === Delete with an undo window ===

    fun hideForPendingDelete(eventId: Long) {
        pendingDeletions.value = pendingDeletions.value + eventId
    }

    fun cancelPendingDelete(eventId: Long) {
        pendingDeletions.value = pendingDeletions.value - eventId
    }

    fun commitPendingDelete(eventId: Long) {
        viewModelScope.launch {
            try {
                eventRepository.deleteEventWithCalendarCopy(eventId, calendarRepository)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete event $eventId", e)
            } finally {
                // Whether or not the delete succeeded, stop hiding the row: the flow is the
                // source of truth and will show it again if it is still there.
                pendingDeletions.value = pendingDeletions.value - eventId
            }
        }
    }

    // === Extend ===

    fun extendToNow(eventTypeId: Long, eventTypeName: String, onResult: (ExtendResult?) -> Unit) {
        viewModelScope.launch {
            val result = try {
                eventRepository.extendLastEvent(eventTypeId, calendarRepository)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extend last event of type $eventTypeId", e)
                null
            }
            lastExtend = result
            if (result == null) Log.w(TAG, "Nothing to extend for $eventTypeName")
            onResult(result)
        }
    }

    fun revertLastExtend() {
        val result = lastExtend ?: return
        lastExtend = null
        viewModelScope.launch {
            try {
                eventRepository.revertExtend(result, calendarRepository)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to revert extend of event ${result.eventId}", e)
            }
        }
    }

    // === Adjust ===

    fun adjust(eventId: Long, startTime: Long, endTime: Long, notes: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = try {
                eventRepository.adjustEvent(eventId, startTime, endTime, notes, calendarRepository)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to adjust event $eventId", e)
                false
            }
            onResult(ok)
        }
    }

    /** Look up a single row for the notification-driven undo entry point. */
    fun findRow(eventId: Long): EventWithType? =
        days.value?.asSequence()
            ?.flatMap { it.rows.asSequence() }
            ?.firstOrNull { it.id == eventId }
            ?.eventWithType
}

class LedgerViewModelFactory(
    private val eventRepository: EventRepository,
    private val calendarRepository: CalendarRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LedgerViewModel::class.java)) {
            return LedgerViewModel(eventRepository, calendarRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
