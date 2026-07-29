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
import com.booxin.launcher.core.diag.DiagnosticLogExporter
import com.booxin.launcher.core.download.DownloadProviders
import com.booxin.launcher.core.download.DownloadSource
import com.booxin.launcher.core.java.JavaInstallState
import com.booxin.launcher.databinding.FragmentSettingsBinding
import com.booxin.launcher.ui.update.LauncherUpdateUi
import com.google.android.material.button.MaterialButton
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
        refreshDownloadSource()
        refreshJavaStatus()

        binding.buttonDownloadSource.setOnClickListener {
            val values = DownloadSource.entries
            val next = values[(DownloadProviders.source.ordinal + 1) % values.size]
            DownloadProviders.source = next
            refreshDownloadSource()
        }

        bindJavaButton(binding.buttonDownloadJava8, 8)
        bindJavaButton(binding.buttonDownloadJava17, 17)
        bindJavaButton(binding.buttonDownloadJava21, 21)
        bindJavaButton(binding.buttonDownloadJava25, 25)

        binding.buttonCheckUpdate.setOnClickListener {
            val act = activity ?: return@setOnClickListener
            LauncherUpdateUi.check(
                activity = act,
                lifecycleOwner = viewLifecycleOwner,
                silentWhenLatest = false
            )
        }

        binding.buttonExportLogs.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.buttonExportLogs.isEnabled = false
                val result = DiagnosticLogExporter.exportAndShare(requireContext())
                binding.buttonExportLogs.isEnabled = true
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.settings_export_logs_ok, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.settings_export_logs_failed,
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
                    val b = _binding ?: return@collect
                    if (progress == null) {
                        b.progressJava.isVisible = false
                        b.textJavaProgress.isVisible = false
                        return@collect
                    }
                    b.textJavaProgress.isVisible = true
                    b.textJavaProgress.text = progress.message
                    when (progress.state) {
                        JavaInstallState.DOWNLOADING -> {
                            b.progressJava.isVisible = true
                            val fraction = progress.progressFraction
                            if (fraction >= 0f) {
                                b.progressJava.isIndeterminate = false
                                b.progressJava.progress = (fraction * 100).toInt()
                            } else {
                                b.progressJava.isIndeterminate = true
                            }
                        }
                        JavaInstallState.EXTRACTING -> {
                            b.progressJava.isVisible = true
                            b.progressJava.isIndeterminate = true
                        }
                        JavaInstallState.INSTALLED, JavaInstallState.FAILED, JavaInstallState.NOT_INSTALLED -> {
                            b.progressJava.isVisible = false
                            refreshJavaStatus()
                        }
                    }
                }
            }
        }
    }

    private fun bindJavaButton(button: MaterialButton, major: Int) {
        button.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                setJavaButtonsEnabled(false)
                val result = AppContainer.javaEnvironment.ensureMajor(major)
                _binding ?: return@launch
                setJavaButtonsEnabled(true)
                refreshJavaStatus()
                val context = context ?: return@launch
                if (result.isSuccess) {
                    Toast.makeText(context, R.string.settings_java_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        getString(
                            R.string.settings_java_failed,
                            result.exceptionOrNull()?.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setJavaButtonsEnabled(enabled: Boolean) {
        val b = _binding ?: return
        b.buttonDownloadJava8.isEnabled = enabled
        b.buttonDownloadJava17.isEnabled = enabled
        b.buttonDownloadJava21.isEnabled = enabled
        b.buttonDownloadJava25.isEnabled = enabled
    }

    private fun refreshDownloadSource() {
        val b = _binding ?: return
        val source = DownloadProviders.source
        b.buttonDownloadSource.text = source.displayName
        b.textDownloadSource.text = getString(R.string.settings_download_source_hint)
    }

    private fun refreshJavaStatus() {
        val b = _binding ?: return
        b.textJavaStatus.text = AppContainer.javaEnvironment.statusText()
        updateJavaButton(b.buttonDownloadJava8, 8)
        updateJavaButton(b.buttonDownloadJava17, 17)
        updateJavaButton(b.buttonDownloadJava21, 21)
        updateJavaButton(b.buttonDownloadJava25, 25)
    }

    private fun updateJavaButton(button: MaterialButton, major: Int) {
        val installed = AppContainer.javaEnvironment.isInstalled("java-$major")
        button.text = if (installed) {
            "Java $major · ${getString(R.string.settings_java_ready)}"
        } else {
            getString(R.string.settings_java_download, major)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
