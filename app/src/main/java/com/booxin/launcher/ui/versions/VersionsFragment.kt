package com.booxin.launcher.ui.versions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.databinding.FragmentVersionsBinding
import kotlinx.coroutines.launch

class VersionsFragment : Fragment() {

    private var _binding: FragmentVersionsBinding? = null
    private val binding get() = _binding!!
    private val adapter = VersionsAdapter { version ->
        AppContainer.repository.selectVersion(version.id)
        Toast.makeText(requireContext(), "已选择 ${version.id}", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVersionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerVersions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerVersions.adapter = adapter

        binding.buttonRefresh.setOnClickListener {
            refreshVersions()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppContainer.repository.versions.collect { list ->
                    adapter.submit(list)
                    binding.textEmpty.isVisible = list.isEmpty()
                }
            }
        }

        if (AppContainer.repository.versions.value.isEmpty()) {
            refreshVersions()
        }
    }

    private fun refreshVersions() {
        viewLifecycleOwner.lifecycleScope.launch {
            val b = _binding ?: return@launch
            b.buttonRefresh.isEnabled = false
            b.buttonRefresh.text = getString(R.string.versions_refreshing)
            val result = AppContainer.repository.refreshVersions()
            val end = _binding ?: return@launch
            end.buttonRefresh.isEnabled = true
            end.buttonRefresh.text = getString(R.string.versions_refresh)
            if (result.isFailure) {
                val context = context ?: return@launch
                Toast.makeText(
                    context,
                    getString(
                        R.string.versions_refresh_failed,
                        result.exceptionOrNull()?.message ?: "unknown"
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
