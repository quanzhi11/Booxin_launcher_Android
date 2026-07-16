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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.databinding.FragmentVersionsBinding
import kotlinx.coroutines.launch

class VersionsFragment : Fragment() {

    private var _binding: FragmentVersionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VersionsAdapter

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
        adapter = VersionsAdapter(
            selectedIdProvider = { AppContainer.repository.session.value.selectedVersionId },
            onClick = { version ->
                AppContainer.repository.selectVersion(version.id)
                adapter.notifyDataSetChanged()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.home_switched, version.id),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        binding.recyclerVersions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerVersions.adapter = adapter

        binding.buttonDownload.setOnClickListener {
            findNavController().navigate(R.id.action_versions_to_download)
        }

        AppContainer.repository.refreshInstalledVersions()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppContainer.repository.installedVersions.collect { list ->
                        adapter.submit(list)
                        binding.textEmpty.isVisible = list.isEmpty()
                    }
                }
                launch {
                    AppContainer.repository.session.collect {
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppContainer.repository.refreshInstalledVersions()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
