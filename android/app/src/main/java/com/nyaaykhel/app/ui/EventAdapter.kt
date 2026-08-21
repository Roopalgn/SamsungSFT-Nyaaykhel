package com.nyaaykhel.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nyaaykhel.app.data.EventRecord
import com.nyaaykhel.app.data.EventType
import com.nyaaykhel.app.databinding.ItemEventBinding

class EventAdapter : ListAdapter<EventRecord, EventAdapter.EventViewHolder>(DiffCallback) {

    inner class EventViewHolder(private val binding: ItemEventBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(event: EventRecord) {
            val type = EventType.fromString(event.eventType)

            binding.tvEventType.text = type.label
            binding.tvEventType.setBackgroundColor(
                binding.root.context.getColor(type.colorResId)
            )
            binding.tvConfidence.text = "%.0f%%".format(event.confidence * 100)
            binding.tvTimestamp.text = event.timestamp.substringAfter("T").substringBefore("+").take(12)
            binding.tvHashPrefix.text = event.hash.take(8) + "…"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemEventBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<EventRecord>() {
        override fun areItemsTheSame(oldItem: EventRecord, newItem: EventRecord) =
            oldItem.eventId == newItem.eventId
        override fun areContentsTheSame(oldItem: EventRecord, newItem: EventRecord) =
            oldItem == newItem
    }
}
