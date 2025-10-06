package com.tjcelaya.scribcal.ui.events

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tjcelaya.scribcal.R
import com.tjcelaya.scribcal.data.database.EventType

class EventTypeAdapter(
    private val onEditClick: (EventType) -> Unit,
    private val onDeleteClick: (EventType) -> Unit
) : ListAdapter<EventType, EventTypeAdapter.EventTypeViewHolder>(EventTypeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventTypeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event_type_management, parent, false)
        return EventTypeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventTypeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EventTypeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorIndicator: View = itemView.findViewById(R.id.colorIndicator)
        private val eventTypeName: TextView = itemView.findViewById(R.id.eventTypeName)
        private val eventTypeDescription: TextView = itemView.findViewById(R.id.eventTypeDescription)
        private val editButton: MaterialButton = itemView.findViewById(R.id.editButton)
        private val deleteButton: MaterialButton = itemView.findViewById(R.id.deleteButton)

        fun bind(eventType: EventType) {
            eventTypeName.text = eventType.name

            // Handle description visibility
            if (eventType.description.isNullOrBlank()) {
                eventTypeDescription.visibility = View.GONE
            } else {
                eventTypeDescription.visibility = View.VISIBLE
                eventTypeDescription.text = eventType.description
            }

            // Set color indicator
            if (eventType.color != null) {
                colorIndicator.background.setTint(eventType.color)
            } else {
                // Use default primary color if no color is set
                val defaultColor = ContextCompat.getColor(itemView.context, R.color.purple_500)
                colorIndicator.background.setTint(defaultColor)
            }

            // Set click listeners
            editButton.setOnClickListener { onEditClick(eventType) }
            deleteButton.setOnClickListener { onDeleteClick(eventType) }
        }
    }
}

class EventTypeDiffCallback : DiffUtil.ItemCallback<EventType>() {
    override fun areItemsTheSame(oldItem: EventType, newItem: EventType): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: EventType, newItem: EventType): Boolean {
        return oldItem == newItem
    }
}
