package com.booxin.launcher.ui.home

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
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.download.game.GameInstallPhase
import com.booxin.launcher.databinding.FragmentHomeBinding
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppContainer.repository.session.collect {
                    refreshSelectedVersion()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppContainer.repository.installProgress.collect { progress ->
                    val b = _binding ?: return@collect
                    if (progress == null) {
                        b.progressInstall.isVisible = false
                        return@collect
                    }
                    b.textLaunchStatus.text = progress.message
                    when (progress.phase) {
                        GameInstallPhase.DONE, GameInstallPhase.FAILED, GameInstallPhase.IDLE -> {
                            b.progressInstall.isVisible = false
                        }
                        else -> {
                            b.progressInstall.isVisible = true
                            val fraction = progress.fraction
                            if (fraction >= 0f) {
                                b.progressInstall.isIndeterminate = false
                                b.progressInstall.progress = (fraction * 100).toInt()
                            } else {
                                b.progressInstall.isIndeterminate = true
                            }
                        }
                    }
                }
            }
        }

        binding.buttonInstall.setOnClickListener {
            val version = AppContainer.repository.selectedVersion()
            if (version == null) {
                Toast.makeText(requireContext(), R.string.home_no_version, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val b = _binding ?: return@launch
                b.buttonInstall.isEnabled = false
                b.buttonLaunch.isEnabled = false
                b.textLaunchStatus.text = getString(R.string.home_installing)
                val result = AppContainer.gameRuntime.prepare(version.id)
                val end = _binding ?: return@launch
                end.buttonInstall.isEnabled = true
                end.buttonLaunch.isEnabled = true
                refreshSelectedVersion()
                val context = context ?: return@launch
                if (result.isSuccess) {
                    Toast.makeText(context, R.string.home_install_done, Toast.LENGTH_SHORT).show()
                    end.textLaunchStatus.text = getString(R.string.home_status_ready)
                } else {
                    val message = result.exceptionOrNull()?.message ?: "unknown"
                    Toast.makeText(
                        context,
                        getString(R.string.home_install_failed, message),
                        Toast.LENGTH_LONG
                    ).show()
                    end.textLaunchStatus.text = message
                }
            }
        }

        binding.buttonLaunch.setOnClickListener {
            val version = AppContainer.repository.selectedVersion()
            if (version == null) {
                Toast.makeText(requireContext(), R.string.home_no_version, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!version.installed) {
                Toast.makeText(requireContext(), R.string.home_launch_need_install, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val account = AppContainer.repository.accounts.value.firstOrNull { it.selected }
                val username = account?.name ?: "Player"
                val result = AppContainer.gameRuntime.launch(version.id, username)
                val context = context ?: return@launch
                val message = result.exceptionOrNull()?.message
                    ?: getString(R.string.action_coming_soon)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshSelectedVersion() {
        val b = _binding ?: return
        val version = AppContainer.repository.selectedVersion()
        b.textSelectedVersion.text = version?.id ?: getString(R.string.home_no_version)
        b.textLaunchStatus.text = when {
            version == null -> getString(R.string.home_status_placeholder)
            version.installed -> getString(R.string.home_status_ready)
            else -> getString(R.string.home_status_placeholder)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
