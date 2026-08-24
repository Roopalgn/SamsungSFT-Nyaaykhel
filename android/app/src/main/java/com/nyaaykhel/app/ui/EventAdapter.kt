package com.nyaaykhel.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nyaaykhel.app.data.EventRecord
import com.nyaaykhel.app.data.EventType
import com.nyaaykhel.app.databinding.ItemEventBinding

class EventAdapter(
    private val onReviewStatusChanged: (EventRecord, String) -> Unit = { _, _ -> },
    private val showReviewControls: Boolean = false,
) : ListAdapter<EventRecord, EventAdapter.EventViewHolder>(DiffCallback) {

    inner class EventViewHolder(private val binding: ItemEventBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(event: EventRecord) {
            val type = EventType.fromString(event.eventType)

            binding.tvEventType.text = type.label
            binding.tvEventType.setBackgroundColor(
                binding.root.context.getColor(type.colorResId)
            )
            binding.tvConfidence.text = "%.0f%%".format(event.confidence * 100)
            binding.tvTimestamp.text = "video ${formatVideoTime(event.videoTimestampMs)}"
            binding.tvReviewStatus.text = "review: ${event.reviewStatus}"
            binding.tvHashPrefix.text = event.hash.take(8) + "…"
            binding.btnApprove.visibility = if (showReviewControls) View.VISIBLE else View.GONE
            binding.btnReject.visibility = if (showReviewControls) View.VISIBLE else View.GONE
            if (showReviewControls) {
                binding.btnApprove.isEnabled = event.reviewStatus != "approved"
                binding.btnReject.isEnabled = event.reviewStatus != "rejected"
                binding.btnApprove.setOnClickListener {
                    onReviewStatusChanged(event, "approved")
                }
                binding.btnReject.setOnClickListener {
                    onReviewStatusChanged(event, "rejected")
                }
            }
        }

        private fun formatVideoTime(ms: Long): String {
            val totalSeconds = ms / 1000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            val millis = ms % 1000L
            return "%02d:%02d.%03d".format(minutes, seconds, millis)
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
