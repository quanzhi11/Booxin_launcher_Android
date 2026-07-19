package com.booxin.launcher.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.databinding.FragmentHomeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppContainer.repository.refreshInstalledVersions()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppContainer.repository.session.collect { refreshSelectedVersion() }
                }
                launch {
                    AppContainer.repository.installedVersions.collect { refreshSelectedVersion() }
                }
            }
        }

        binding.buttonSwitchVersion.setOnClickListener { showSwitchVersionDialog() }

        binding.buttonLaunch.setOnClickListener {
            val version = AppContainer.repository.selectedVersion()
            if (version == null || !version.installed) {
                Toast.makeText(requireContext(), R.string.home_launch_need_install, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val account = AppContainer.repository.accounts.value.firstOrNull { it.selected }
                val username = account?.name ?: "Player"
                val ctx = context ?: return@launch
                val result = AppContainer.gameRuntime.launch(ctx, version.id, username)
                if (result.isFailure) {
                    Toast.makeText(
                        ctx,
                        getString(
                            R.string.home_launch_failed,
                            result.exceptionOrNull()?.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showSwitchVersionDialog() {
        val installed = AppContainer.repository.installedVersions.value
        if (installed.isEmpty()) {
            Toast.makeText(requireContext(), R.string.home_no_installed, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = installed.map { it.id }.toTypedArray()
        val selectedId = AppContainer.repository.session.value.selectedVersionId
        val checked = installed.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_switch_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val version = installed[which]
                AppContainer.repository.selectVersion(version.id)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.home_switched, version.id),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshSelectedVersion() {
        val b = _binding ?: return
        val version = AppContainer.repository.selectedVersion()
        b.textSelectedVersion.text = version?.id ?: getString(R.string.home_no_version)
        b.textLaunchStatus.text = when {
            version == null || !version.installed -> getString(R.string.home_status_placeholder)
            else -> getString(R.string.home_status_ready)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
