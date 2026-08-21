package com.nyaaykhel.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.nyaaykhel.app.MainViewModel
import com.nyaaykhel.app.R
import com.nyaaykhel.app.databinding.FragmentAnalysisBinding
import kotlinx.coroutines.launch

/**
 * Analysis screen: shows real-time progress bar and live event feed
 * as the video is being processed.
 */
class AnalysisFragment : Fragment() {

    private var _binding: FragmentAnalysisBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory(requireContext())
    }

    private lateinit var eventAdapter: EventAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        eventAdapter = EventAdapter()
        binding.rvLiveEvents.apply {
            adapter = eventAdapter
            layoutManager = LinearLayoutManager(context).also { it.stackFromEnd = true }
        }

        binding.btnCancel.setOnClickListener {
            viewModel.cancelAnalysis()
            findNavController().navigateUp()
        }

        // Observe analysis state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is MainViewModel.AnalysisState.Processing -> {
                        val total = state.framesTotal
                        if (total > 0) {
                            binding.progressBar.max = total
                            binding.progressBar.progress = state.framesDone
                            val pct = (state.framesDone * 100 / total)
                            binding.tvProgress.text = "Processing… $pct%"
                        } else {
                            binding.tvProgress.text = "Starting…"
                        }
                    }
                    is MainViewModel.AnalysisState.Done -> {
                        binding.tvProgress.text = "Done — ${state.eventCount} events detected"
                        binding.progressBar.progress = binding.progressBar.max
                        binding.btnCancel.text = "View Match Record"
                        binding.btnCancel.setOnClickListener {
                            findNavController().navigate(
                                R.id.action_analysis_to_matchRecord,
                                bundleOf("matchId" to state.matchId),
                            )
                        }
                    }
                    is MainViewModel.AnalysisState.Error -> {
                        binding.tvProgress.text = "Error: ${state.message}"
                        binding.btnCancel.text = "Back"
                        binding.btnCancel.setOnClickListener { findNavController().navigateUp() }
                    }
                    else -> Unit
                }
            }
        }

        // Observe live event feed
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.liveEvents.collect { events ->
                eventAdapter.submitList(events)
                if (events.isNotEmpty()) {
                    binding.rvLiveEvents.scrollToPosition(events.size - 1)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
