package com.tjcelaya.calwrite.ui.events

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tjcelaya.calwrite.R
import com.tjcelaya.calwrite.data.database.EventType

class EventTypeAutocompleteAdapter(
    private val context: Context,
    private var eventTypes: List<EventType> = emptyList()
) : BaseAdapter(), Filterable {

    private var filteredEventTypes: List<EventType> = emptyList()
    private val inflater: LayoutInflater = LayoutInflater.from(context)

    fun updateEventTypes(newEventTypes: List<EventType>) {
        eventTypes = newEventTypes
        filteredEventTypes = newEventTypes
        notifyDataSetChanged()
    }

    override fun getCount(): Int = filteredEventTypes.size

    override fun getItem(position: Int): EventType = filteredEventTypes[position]

    override fun getItemId(position: Int): Long = filteredEventTypes[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_event_type_autocomplete, parent, false)

        val eventType = filteredEventTypes[position]

        val colorIndicator = view.findViewById<View>(R.id.colorIndicator)
        val eventTypeName = view.findViewById<TextView>(R.id.eventTypeName)

        eventTypeName.text = eventType.name

        // Set color indicator using the appropriate display color
        val displayColor = eventType.getDisplayColor()
        if (displayColor != null) {
            colorIndicator.background.setTint(displayColor)
        } else {
            val defaultColor = ContextCompat.getColor(context, R.color.calwrite_blue)
            colorIndicator.background.setTint(defaultColor)
        }

        return view
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val filterResults = FilterResults()

                if (constraint.isNullOrEmpty()) {
                    filteredEventTypes = eventTypes
                } else {
                    val query = constraint.toString().lowercase().trim()
                    filteredEventTypes = eventTypes.filter { eventType ->
                        eventType.name.lowercase().contains(query)
                    }
                }

                filterResults.values = filteredEventTypes
                filterResults.count = filteredEventTypes.size
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                if (results?.values != null) {
                    filteredEventTypes = results.values as List<EventType>
                    notifyDataSetChanged()
                }
            }
        }
    }
}