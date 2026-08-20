# Future Events Integration Guide

This guide explains how to integrate the future events countdown timer feature into your UI.

## Overview

The future events feature allows users to schedule events for a specific date/time and shows a countdown timer (like a timer in a clock app) counting down to that time.

## Components Created

### Backend (Already Implemented)
- `FutureEvent.kt` - Database entity with `targetTime` field
- `FutureEventDao.kt` - DAO with CRUD operations
- `EventRepository.kt` - Methods: `createFutureEvent()`, `deleteFutureEvent()`, `triggerExpiredFutureEvents()`
- `TrackingViewModel.kt` - LiveData: `futureEvents`, `futureEventsWithTypes`

### UI Components (Already Implemented)
- `FutureEventsAdapter` - RecyclerView adapter with countdown display
- `item_future_event.xml` - Layout showing countdown timer

## How to Integrate in TrackingFragment

### 1. Add RecyclerView to Layout

Add to `fragment_tracking.xml`:

```xml
<!-- Future Events Section (optional header) -->
<TextView
    android:id="@+id/futureEventsHeader"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="Scheduled Events"
    android:textAppearance="@style/TextAppearance.Material3.TitleMedium"
    android:paddingStart="16dp"
    android:paddingEnd="16dp"
    android:paddingTop="8dp"
    android:paddingBottom="8dp"
    android:visibility="gone" />

<androidx.recyclerview.widget.RecyclerView
    android:id="@+id/futureEventsRecyclerView"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:visibility="gone" />
```

### 2. Initialize Adapter in TrackingFragment

Add to `setupRecyclerViews()` method:

```kotlin
private lateinit var futureEventsAdapter: FutureEventsAdapter

private fun setupRecyclerViews() {
    // ... existing adapters ...

    // Future Events RecyclerView
    futureEventsAdapter = FutureEventsAdapter(
        onDeleteEvent = { futureEvent ->
            viewModel.deleteFutureEvent(futureEvent.id)
        }
    )

    binding.futureEventsRecyclerView.apply {
        layoutManager = LinearLayoutManager(requireContext())
        adapter = futureEventsAdapter
    }
}
```

### 3. Observe LiveData and Update Timer

Add to `observeViewModel()` method:

```kotlin
private fun observeViewModel() {
    // ... existing observers ...

    // Observe future events
    viewModel.futureEventsWithTypes.observe(viewLifecycleOwner) { futureEvents ->
        futureEventsAdapter.submitList(futureEvents)
        
        // Show/hide section based on whether there are future events
        if (futureEvents.isEmpty()) {
            binding.futureEventsHeader.visibility = View.GONE
            binding.futureEventsRecyclerView.visibility = View.GONE
        } else {
            binding.futureEventsHeader.visibility = View.VISIBLE
            binding.futureEventsRecyclerView.visibility = View.VISIBLE
        }
    }
}
```

### 4. Add Timer Updates

Update your existing timer runnable to also refresh future event countdowns:

```kotlin
private fun startTimer() {
    timerRunnable = object : Runnable {
        override fun run() {
            // Existing timer updates for ongoing events
            eventTypesAdapter.refreshTimers()
            ongoingEventsAdapter.refreshTimers()
            
            // NEW: Refresh future event countdown timers
            futureEventsAdapter.refreshTimers()
            
            timerHandler.postDelayed(this, 1000) // Update every second
        }
    }
    timerHandler.post(timerRunnable!!)
}
```

### 5. Create UI to Schedule Future Events

Add a method to show a date/time picker dialog:

```kotlin
private fun showScheduleFutureEventDialog(eventTypeId: Long) {
    // Use MaterialDatePicker and MaterialTimePicker
    val datePicker = MaterialDatePicker.Builder.datePicker()
        .setTitleText("Select date")
        .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
        .build()
    
    datePicker.addOnPositiveButtonClickListener { dateMillis ->
        // Then show time picker
        val timePicker = MaterialTimePicker.Builder()
            .setTitleText("Select time")
            .setHour(12)
            .setMinute(0)
            .build()
        
        timePicker.addOnPositiveButtonClickListener {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = dateMillis
                set(Calendar.HOUR_OF_DAY, timePicker.hour)
                set(Calendar.MINUTE, timePicker.minute)
                set(Calendar.SECOND, 0)
            }
            
            viewModel.createFutureEvent(
                eventTypeId = eventTypeId,
                targetTime = calendar.timeInMillis,
                notes = null
            )
        }
        
        timePicker.show(childFragmentManager, "time_picker")
    }
    
    datePicker.show(childFragmentManager, "date_picker")
}
```

### 6. Add FAB Button for Scheduling (Optional)

Add to your FAB menu in `fragment_tracking.xml`:

```xml
<com.google.android.material.floatingactionbutton.FloatingActionButton
    android:id="@+id/fabScheduleEvent"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:contentDescription="Schedule Future Event"
    app:srcCompat="@drawable/ic_schedule"
    app:fabSize="mini" />
```

And handle the click:

```kotlin
binding.fabScheduleEvent.setOnClickListener {
    closeFabMenu()
    // Show event type picker, then schedule dialog
    showEventTypePickerForScheduling()
}
```

### 7. Trigger Expired Events

Add periodic check in `onResume()`:

```kotlin
override fun onResume() {
    super.onResume()
    startTimer()
    
    // Check for expired future events when fragment resumes
    viewModel.checkExpiredFutureEvents()
}
```

## How It Works

1. **User schedules event**: Picks event type + date/time → creates `FutureEvent`
2. **Countdown display**: Shows time remaining in HH:MM:SS format
3. **Timer updates**: Adapter refreshes every second to update countdown
4. **Event triggers**: When `targetTime` is reached, `triggerExpiredFutureEvents()` creates an instant event at the scheduled time
5. **Automatic cleanup**: Future event is deleted after being triggered

## Countdown Display Behavior

- **Active countdown**: Shows in blue/primary color (e.g., "02:30:15")
- **Expired**: Shows "00:00:00" in red/error color
- **Format**: Always HH:MM:SS format
- **Scheduled for**: Shows target date/time (e.g., "Scheduled for Oct 26, 3:00 PM")

## Database Migration

The database version was bumped to 4 with the `FutureEvent` entity added. Since you're using `.fallbackToDestructiveMigration()`, the database will be recreated automatically on first launch.

## Next Steps

1. Add the RecyclerView to your layout
2. Initialize the adapter in TrackingFragment
3. Create UI for scheduling future events (date/time picker)
4. Test the countdown timer display
5. Test that expired events trigger correctly
