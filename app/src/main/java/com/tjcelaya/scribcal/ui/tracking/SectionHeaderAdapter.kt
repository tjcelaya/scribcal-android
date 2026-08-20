package com.tjcelaya.scribcal.ui.tracking

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tjcelaya.scribcal.R

/**
 * Minimal single-item adapter that renders a section header inside a [androidx.recyclerview.widget.ConcatAdapter].
 * Call [setVisible] to show/hide the header (e.g. only show "Scheduled Events" when there are scheduled events).
 */
class SectionHeaderAdapter(private val title: String) :
    RecyclerView.Adapter<SectionHeaderAdapter.ViewHolder>() {

    private var visible = false

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_section_header, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.title.text = title
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.sectionHeaderText)
    }
}
