package com.booxin.launcher.ui.settings

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
import com.booxin.launcher.BuildConfig
import com.booxin.launcher.R
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.java.JavaInstallState
import com.booxin.launcher.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.textGameDir.text = LauncherPaths.rootDir.absolutePath
        binding.textAbout.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        refreshJavaStatus()

        binding.buttonDownloadJava17.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.buttonDownloadJava17.isEnabled = false
                val result = AppContainer.javaEnvironment.ensureMajor(17)
                binding.buttonDownloadJava17.isEnabled = true
                refreshJavaStatus()
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.settings_java_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.settings_java_failed,
                            result.exceptionOrNull()?.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppContainer.javaEnvironment.progress.collect { progress ->
                    if (progress == null) {
                        binding.progressJava.isVisible = false
                        binding.textJavaProgress.isVisible = false
                        return@collect
                    }
                    binding.textJavaProgress.isVisible = true
                    binding.textJavaProgress.text = progress.message
                    when (progress.state) {
                        JavaInstallState.DOWNLOADING -> {
                            binding.progressJava.isVisible = true
                            val fraction = progress.progressFraction
                            if (fraction >= 0f) {
                                binding.progressJava.isIndeterminate = false
                                binding.progressJava.progress = (fraction * 100).toInt()
                            } else {
                                binding.progressJava.isIndeterminate = true
                            }
                        }
                        JavaInstallState.EXTRACTING -> {
                            binding.progressJava.isVisible = true
                            binding.progressJava.isIndeterminate = true
                        }
                        JavaInstallState.INSTALLED, JavaInstallState.FAILED, JavaInstallState.NOT_INSTALLED -> {
                            binding.progressJava.isVisible = false
                            refreshJavaStatus()
                        }
                    }
                }
            }
        }
    }

    private fun refreshJavaStatus() {
        binding.textJavaStatus.text = AppContainer.javaEnvironment.statusText()
        val installed17 = AppContainer.javaEnvironment.isInstalled("java-17")
        binding.buttonDownloadJava17.text = if (installed17) {
            getString(R.string.settings_java_done)
        } else {
            getString(R.string.settings_java_download_17)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
