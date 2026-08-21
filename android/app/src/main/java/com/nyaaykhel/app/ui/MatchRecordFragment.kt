package com.nyaaykhel.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nyaaykhel.app.MainViewModel
import com.nyaaykhel.app.databinding.FragmentMatchRecordBinding
import kotlinx.coroutines.launch

/**
 * Match Record screen: full event list with hash chain view + Export JSON FAB.
 * This is the primary "demo artifact" screen judges see.
 */
class MatchRecordFragment : Fragment() {

    private var _binding: FragmentMatchRecordBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory(requireContext())
    }

    private lateinit var eventAdapter: EventAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMatchRecordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val matchId = arguments?.getString("matchId")
            ?: run {
                Toast.makeText(context, "No match ID provided", Toast.LENGTH_SHORT).show()
                return
            }

        eventAdapter = EventAdapter()
        binding.rvEvents.apply {
            adapter = eventAdapter
            layoutManager = LinearLayoutManager(context)
        }

        // Observe events for this match
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.getEventsFlow(matchId).collect { events ->
                eventAdapter.submitList(events)
                binding.tvEventCount.text = "${events.size} events detected"
                binding.tvMatchId.text = "Match: ${matchId.take(8)}…"

                // Show terminal hash (last event's hash) as proof
                if (events.isNotEmpty()) {
                    val terminalHash = events.last().hash
                    binding.tvTerminalHash.text = "Terminal hash: ${terminalHash.take(16)}…"
                    binding.tvTerminalHash.visibility = View.VISIBLE
                }
            }
        }

        // Export FAB
        binding.fabExport.setOnClickListener {
            viewModel.exportMatchRecord(matchId)
            Toast.makeText(context, "Exporting signed JSON…", Toast.LENGTH_SHORT).show()
        }

        // Show export path when available
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.exportPath.collect { path ->
                if (path != null) {
                    binding.tvExportPath.text = "Exported: ${path.substringAfterLast('/')}"
                    binding.tvExportPath.visibility = View.VISIBLE
                    Toast.makeText(context, "Exported to $path", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
