package com.tjcelaya.scribcal.ui.ledger

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.databinding.FragmentLedgerBinding
import com.tjcelaya.scribcal.ui.tracking.SectionHeaderAdapter

/**
 * A ledger of recently recorded events, newest first and grouped by day, where each entry can be
 * deleted, extended to now, or adjusted - always propagating to the event's calendar copy.
 *
 * It is also where the SAVE_WITH_UNDO voice notification's Undo action lands, via the
 * `undoEventId` argument.
 */
class LedgerFragment : Fragment() {

    companion object {
        private const val TAG = "LedgerFragment"
        const val NO_UNDO_EVENT = -1L
    }

    private var _binding: FragmentLedgerBinding? = null
    private val binding get() = _binding!!

    private val args: LedgerFragmentArgs by navArgs()

    private lateinit var viewModel: LedgerViewModel

    /** One header + one list adapter per day, keyed by the day's start-of-day millis. */
    private val headerAdapters = linkedMapOf<Long, SectionHeaderAdapter>()
    private val dayAdapters = linkedMapOf<Long, LedgerAdapter>()
    private var concatAdapter: ConcatAdapter? = null

    /** The notification-driven undo runs once, not again on every configuration change. */
    private var pendingUndoEventId = NO_UNDO_EVENT

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLedgerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as ScribCalApplication
        val factory = LedgerViewModelFactory(app.eventRepository, app.calendarRepository)
        viewModel = ViewModelProvider(this, factory)[LedgerViewModel::class.java]

        if (savedInstanceState == null) {
            pendingUndoEventId = args.undoEventId
        }

        binding.ledgerRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        attachSwipeToDelete()

        viewModel.days.observe(viewLifecycleOwner) { days ->
            render(days)
            handlePendingUndo()
        }

        viewModel.message.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                viewModel.consumeMessage()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.ledgerRecyclerView.adapter = null
        headerAdapters.clear()
        dayAdapters.clear()
        concatAdapter = null
        _binding = null
    }

    private fun render(days: List<LedgerDay>) {
        binding.ledgerEmptyState.visibility = if (days.isEmpty()) View.VISIBLE else View.GONE
        binding.ledgerRecyclerView.visibility = if (days.isEmpty()) View.GONE else View.VISIBLE

        val dayKeys = days.map { it.dayStartMillis }
        if (dayKeys != dayAdapters.keys.toList()) {
            rebuildAdapters(days)
        }
        days.forEach { day -> dayAdapters[day.dayStartMillis]?.submitList(day.rows) }
    }

    /**
     * Day groups come and go as events are added or deleted, so the ConcatAdapter is rebuilt only
     * when the set of days actually changes; otherwise each day's ListAdapter diffs in place.
     */
    private fun rebuildAdapters(days: List<LedgerDay>) {
        val previousHeaders = headerAdapters.toMap()
        val previousDays = dayAdapters.toMap()
        headerAdapters.clear()
        dayAdapters.clear()

        val parts = mutableListOf<RecyclerView.Adapter<out RecyclerView.ViewHolder>>()
        val now = System.currentTimeMillis()
        for (day in days) {
            val header = previousHeaders[day.dayStartMillis]
                ?: SectionHeaderAdapter(LedgerGrouping.dayLabel(requireContext(), day.dayStartMillis, now))
                    .also { it.setVisible(true) }
            val dayAdapter = previousDays[day.dayStartMillis] ?: LedgerAdapter(
                onExtend = { row -> extend(row) },
                onAdjust = { row -> adjust(row) }
            )
            headerAdapters[day.dayStartMillis] = header
            dayAdapters[day.dayStartMillis] = dayAdapter
            parts += header
            parts += dayAdapter
        }

        concatAdapter = ConcatAdapter(parts)
        binding.ledgerRecyclerView.adapter = concatAdapter
    }

    private fun attachSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Day headers are part of the same ConcatAdapter and must not be swipeable.
                return if (viewHolder.bindingAdapter is LedgerAdapter) {
                    super.getMovementFlags(recyclerView, viewHolder)
                } else {
                    0
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val adapter = viewHolder.bindingAdapter as? LedgerAdapter ?: return
                val row = adapter.rowAt(viewHolder.bindingAdapterPosition) ?: return
                deleteWithUndo(row.id, row.eventWithType.eventTypeName)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.ledgerRecyclerView)
    }

    private fun deleteWithUndo(eventId: Long, eventTypeName: String) {
        viewModel.hideForPendingDelete(eventId)
        Log.d(TAG, "Pending delete for event $eventId ($eventTypeName)")

        Snackbar.make(
            binding.root,
            getString(R.string.ledger_deleted_message, eventTypeName),
            Snackbar.LENGTH_LONG
        )
            .setAction(R.string.ledger_undo) { viewModel.cancelPendingDelete(eventId) }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    // Anything other than tapping Undo closes the window and commits the delete.
                    if (event != DISMISS_EVENT_ACTION) viewModel.commitPendingDelete(eventId)
                }
            })
            .show()
    }

    private fun extend(row: LedgerRow) {
        val name = row.eventWithType.eventTypeName
        viewModel.extendToNow(row.eventWithType.eventType.id, name) { result ->
            if (!isAdded || _binding == null) return@extendToNow
            if (result == null) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.ledger_extend_failed, name),
                    Snackbar.LENGTH_SHORT
                ).show()
            } else {
                Snackbar.make(
                    binding.root,
                    getString(R.string.ledger_extended_message, name),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.ledger_undo) { viewModel.revertLastExtend() }.show()
            }
        }
    }

    private fun adjust(row: LedgerRow) {
        AdjustEventSheet.show(
            context = requireContext(),
            fragmentManager = childFragmentManager,
            item = row.eventWithType
        ) { startTime, endTime, notes ->
            val name = row.eventWithType.eventTypeName
            viewModel.adjust(row.id, startTime, endTime, notes) { ok ->
                if (!isAdded || _binding == null) return@adjust
                val message = if (ok) {
                    getString(R.string.ledger_adjusted_message, name)
                } else {
                    getString(R.string.ledger_adjust_failed, name)
                }
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /** Entry point for the SAVE_WITH_UNDO notification: offer to undo that specific save. */
    private fun handlePendingUndo() {
        val eventId = pendingUndoEventId
        if (eventId == NO_UNDO_EVENT) return
        val item = viewModel.findRow(eventId) ?: return
        pendingUndoEventId = NO_UNDO_EVENT
        deleteWithUndo(eventId, item.eventTypeName)
    }
}
