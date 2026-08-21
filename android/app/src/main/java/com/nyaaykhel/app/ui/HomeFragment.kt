package com.nyaaykhel.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.nyaaykhel.app.MainViewModel
import com.nyaaykhel.app.R
import com.nyaaykhel.app.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

/**
 * Home screen: entry point for starting a new analysis.
 * Primary path: load a video file.
 * Secondary path: live camera (stub for Phase E).
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory(requireContext())
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            viewModel.analyseVideo(uri)
            findNavController().navigate(R.id.action_home_to_analysis)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnLoadVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            pickVideoLauncher.launch(intent)
        }

        // Live camera — stub (Phase E)
        binding.btnLiveCamera.setOnClickListener {
            android.widget.Toast.makeText(
                requireContext(),
                "Live camera mode: Phase E (not yet implemented). Use Load Video for demo.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }

        // Show last export path if available
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.exportPath.collect { path ->
                if (path != null) {
                    binding.tvLastExport.text = "Last export: ${path.substringAfterLast('/')}"
                    binding.tvLastExport.visibility = View.VISIBLE
                } else {
                    binding.tvLastExport.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
