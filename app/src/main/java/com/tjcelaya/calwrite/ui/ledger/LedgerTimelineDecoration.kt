package com.tjcelaya.calwrite.ui.ledger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.tjcelaya.calwrite.R

/**
 * Draws the ledger's timeline gutter down the left edge of the whole list.
 *
 * This is an ItemDecoration rather than a view inside the row on purpose. The fragment builds a
 * ConcatAdapter of one section header + one [LedgerAdapter] **per day**, so no single row - and no
 * single adapter - can see enough of the list to draw a line that runs past its own bounds. A
 * decoration is handed the RecyclerView's canvas and every visible child with its final laid-out
 * position, which is exactly what the compacted lane ("aligned to the rows as laid out") needs,
 * and it can draw across a day header without that header's adapter knowing anything about it.
 *
 * Each visible row carries its timing on [R.id.ledger_timeline_row] as a [LedgerTimelineRow];
 * children without that tag (the day headers) are treated as day breaks.
 *
 * Accessibility: a decoration contributes no accessibility node at all, which is the point - the
 * gutter is decorative and every time it encodes is still spoken from the row's own text views.
 *
 * Day boundaries **break** the axis. Each day gets its own band, rescaled to that day's rows, and
 * the header between two days draws a short break tick across the gutter. A single continuous
 * real-time axis would have to spend most of its pixels on the hours nobody recorded anything in.
 */
class LedgerTimelineDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private companion object {
        const val RECORDED_LANE_DP = 9f
        const val COMPACTED_LANE_DP = 20f
        const val RAIL_WIDTH_DP = 1.5f
        const val SEGMENT_WIDTH_DP = 4f
        const val INSTANT_TICK_DP = 11f
        const val INSTANT_WIDTH_DP = 3f
        const val RING_RADIUS_DP = 3.5f
        const val RING_WIDTH_DP = 2f
        const val MIN_SEGMENT_DP = 10f
        const val CONNECTOR_WIDTH_DP = 1.5f
        const val BREAK_TICK_DP = 10f
        /** Slack before the two lanes count as disagreeing; roughly the ring's own size. */
        const val MERGE_TOLERANCE_DP = 5f
        const val RAIL_ALPHA = 40
        const val CONNECTOR_ALPHA = 120
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val gutterWidth = context.resources.getDimension(R.dimen.ledger_gutter_width)
    private val railColor = resolveColor(context, com.google.android.material.R.attr.colorOutline)

    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(RAIL_WIDTH_DP)
        strokeCap = Paint.Cap.ROUND
        color = ColorUtils.setAlphaComponent(railColor, RAIL_ALPHA)
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(SEGMENT_WIDTH_DP)
        strokeCap = Paint.Cap.ROUND
    }
    private val instantPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(INSTANT_WIDTH_DP)
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(RING_WIDTH_DP)
    }
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(CONNECTOR_WIDTH_DP)
        strokeCap = Paint.Cap.ROUND
    }
    private val breakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(RAIL_WIDTH_DP)
        strokeCap = Paint.Cap.ROUND
        color = ColorUtils.setAlphaComponent(railColor, RAIL_ALPHA * 2)
    }
    private val connectorPath = Path()

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        // Headers are indented too, so the gutter is a clear column the whole way down.
        outRect.left = gutterWidth.toInt()
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.childCount == 0) return
        val now = System.currentTimeMillis()
        val entries = (0 until parent.childCount).map { entryFor(parent, parent.getChildAt(it)) }

        // A day's rows arrive together, so a day group is just a run of consecutive children.
        var groupStart = 0
        while (groupStart < entries.size) {
            val entry = entries[groupStart]
            val row = entry.row
            if (row == null) {
                drawDayBreak(canvas, entry.child)
                groupStart++
                continue
            }
            var groupEnd = groupStart
            while (groupEnd + 1 < entries.size) {
                val next = entries[groupEnd + 1].row ?: break
                if (next.dayStartMillis != row.dayStartMillis) break
                groupEnd++
            }
            drawDay(canvas, parent, entries, groupStart, groupEnd, now)
            groupStart = groupEnd + 1
        }
    }

    private fun drawDay(
        canvas: Canvas,
        parent: RecyclerView,
        entries: List<Entry>,
        firstIndex: Int,
        lastIndex: Int,
        now: Long
    ) {
        val first = entries[firstIndex]
        val last = entries[lastIndex]
        val firstRow = first.row ?: return

        val band = LedgerTimeline.extrapolateBand(
            firstVisibleTop = first.child.topWithTranslation(),
            lastVisibleBottom = last.child.bottomWithTranslation(),
            firstVisibleIndex = first.indexInDay,
            lastVisibleIndex = last.indexInDay,
            rowCount = maxOf(first.rowsInDay, last.indexInDay + 1)
        )
        val span = LedgerTimeline.daySpanMillis(firstRow.dayStartMillis, now)

        val laneLeft = first.child.left - gutterWidth
        val recordedX = laneLeft + dp(RECORDED_LANE_DP)
        val compactedX = laneLeft + dp(COMPACTED_LANE_DP)

        // The rail is the day's axis; clip it to the list so an extrapolated band does not paint
        // over the neighbouring day's space.
        val railTop = band.top.coerceAtLeast(parent.paddingTop.toFloat())
        val railBottom = band.bottom.coerceAtMost((parent.height - parent.paddingBottom).toFloat())
        if (railBottom > railTop) {
            canvas.drawLine(recordedX, railTop, recordedX, railBottom, railPaint)
        }

        for (index in firstIndex..lastIndex) {
            val entry = entries[index]
            val row = entry.row ?: continue
            drawRow(canvas, entry.child, row, band, span, recordedX, compactedX)
        }
    }

    private fun drawRow(
        canvas: Canvas,
        child: View,
        row: LedgerTimelineRow,
        band: LedgerTimeline.Band,
        spanMillis: Long,
        recordedX: Float,
        compactedX: Float
    ) {
        val mark = LedgerTimeline.recordedMark(
            startTime = row.startTime,
            endTime = row.endTime,
            isInstant = row.isInstant,
            dayStartMillis = row.dayStartMillis,
            band = band,
            spanMillis = spanMillis,
            minLengthPx = dp(MIN_SEGMENT_DP)
        )
        val rowCenter = LedgerTimeline.compactedCenter(
            child.topWithTranslation(),
            child.bottomWithTranslation()
        )
        // Rows fade as they are swiped away; their marks should go with them.
        val alpha = (child.alpha.coerceIn(0f, 1f) * 255f).toInt()

        val needsConnector = LedgerTimeline.needsConnector(mark, rowCenter, dp(MERGE_TOLERANCE_DP))
        if (needsConnector) {
            connectorPaint.color = ColorUtils.setAlphaComponent(row.color, CONNECTOR_ALPHA)
            connectorPaint.alpha = connectorPaint.alpha * alpha / 255
            // From the row's text block across to its mark on the compacted lane...
            canvas.drawLine(
                compactedX + dp(RING_RADIUS_DP),
                rowCenter,
                child.left.toFloat(),
                rowCenter,
                connectorPaint
            )
            // ...and on to where the event actually happened.
            val target = if (rowCenter < mark.top) mark.top else mark.bottom
            connectorPath.reset()
            connectorPath.moveTo(compactedX - dp(RING_RADIUS_DP), rowCenter)
            connectorPath.quadTo(recordedX, rowCenter, recordedX, target)
            canvas.drawPath(connectorPath, connectorPaint)
        }

        if (mark.isInstant) {
            instantPaint.color = row.color
            instantPaint.alpha = alpha
            val half = dp(INSTANT_TICK_DP) / 2f
            canvas.drawLine(recordedX - half, mark.center, recordedX + half, mark.center, instantPaint)
        } else {
            segmentPaint.color = row.color
            segmentPaint.alpha = alpha
            canvas.drawLine(recordedX, mark.top, recordedX, mark.bottom, segmentPaint)
        }

        // Where the lanes agree the recorded mark already stands for the row's position, so the
        // compacted ring is left off and the two read as one line.
        if (needsConnector) {
            ringPaint.color = row.color
            ringPaint.alpha = alpha
            canvas.drawCircle(compactedX, rowCenter, dp(RING_RADIUS_DP), ringPaint)
        }
    }

    /** A day header is a break in the axis, marked rather than papered over. */
    private fun drawDayBreak(canvas: Canvas, header: View) {
        val laneLeft = header.left - gutterWidth
        val x = laneLeft + dp(RECORDED_LANE_DP)
        val half = dp(BREAK_TICK_DP) / 2f
        val y = LedgerTimeline.compactedCenter(
            header.topWithTranslation(),
            header.bottomWithTranslation()
        )
        canvas.drawLine(x - half, y, x + half, y, breakPaint)
    }

    /**
     * A visible child paired with its timing. [indexInDay] and [rowsInDay] are read back from the
     * RecyclerView rather than trusted from the tag, because a row that was only *moved* by a
     * deletion is never re-bound and its tag would still describe the old list.
     */
    private class Entry(
        val child: View,
        val row: LedgerTimelineRow?,
        val indexInDay: Int,
        val rowsInDay: Int
    )

    private fun entryFor(parent: RecyclerView, child: View): Entry {
        val row = child.timelineRow() ?: return Entry(child, null, 0, 0)
        val holder = parent.getChildViewHolder(child)
        val position = holder.bindingAdapterPosition
        val count = holder.bindingAdapter?.itemCount ?: 0
        return if (position == RecyclerView.NO_POSITION || count <= 0) {
            Entry(child, row, row.indexInDay, row.rowsInDay)
        } else {
            Entry(child, row, position, count)
        }
    }

    private fun View.timelineRow(): LedgerTimelineRow? =
        getTag(R.id.ledger_timeline_row) as? LedgerTimelineRow

    private fun View.topWithTranslation(): Float = top + translationY
    private fun View.bottomWithTranslation(): Float = bottom + translationY

    private fun resolveColor(context: Context, attr: Int): Int {
        val typed = TypedValue()
        return if (context.theme.resolveAttribute(attr, typed, true)) {
            if (typed.resourceId != 0) {
                ContextCompat.getColor(context, typed.resourceId)
            } else {
                typed.data
            }
        } else {
            ContextCompat.getColor(context, R.color.calwrite_blue)
        }
    }
}
