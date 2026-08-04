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
import com.booxin.launcher.core.LauncherPrefs
import com.booxin.launcher.core.diag.DiagnosticLogExporter
import com.booxin.launcher.core.download.DownloadProviders
import com.booxin.launcher.core.download.DownloadSource
import com.booxin.launcher.core.java.JavaInstallState
import com.booxin.launcher.core.launch.RealtimeLaunchLog
import com.booxin.launcher.core.runtime.RendererInstaller
import com.booxin.launcher.core.runtime.RendererPackages
import com.booxin.launcher.databinding.FragmentSettingsBinding
import com.booxin.launcher.ui.update.LauncherUpdateUi
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        setupRendererModeToggle()
        refreshRenderer()
        setupMemorySlider()
        refreshRealtimeLog()

        binding.buttonRenderer.setOnClickListener { showRendererPicker() }
        binding.buttonDownloadRenderer.setOnClickListener { downloadSelectedRenderer() }

        binding.buttonRealtimeLogStart.setOnClickListener {
            RealtimeLaunchLog.enable()
            refreshRealtimeLog()
            Toast.makeText(
                requireContext(),
                R.string.settings_realtime_log_started,
                Toast.LENGTH_LONG
            ).show()
        }
        binding.buttonRealtimeLogStop.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.buttonRealtimeLogStop.isEnabled = false
                binding.buttonRealtimeLogStart.isEnabled = false
                val result = withContext(Dispatchers.IO) {
                    RealtimeLaunchLog.stopAndExport(requireContext().applicationContext)
                }
                binding.buttonRealtimeLogStop.isEnabled = true
                binding.buttonRealtimeLogStart.isEnabled = true
                refreshRealtimeLog()
                if (result.isSuccess) {
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_realtime_log_exported,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.settings_realtime_log_export_failed,
                            result.exceptionOrNull()?.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.buttonDownloadSource.setOnClickListener {
            val values = DownloadSource.entries
            val next = values[(DownloadProviders.source.ordinal + 1) % values.size]
            DownloadProviders.setSource(requireContext(), next)
            refreshDownloadSource()
            if (next == DownloadSource.BALANCED) {
                probeDownloadSource(force = DownloadProviders.needsProbe())
            }
        }
        binding.buttonProbeDownloadSource.setOnClickListener {
            probeDownloadSource(force = true)
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

    private fun setupRendererModeToggle() {
        val b = _binding ?: return
        b.toggleRendererMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val wantAuto = checkedId == R.id.buttonRendererAuto
            if (wantAuto == LauncherPrefs.isRendererAuto()) {
                refreshRenderer()
                return@addOnButtonCheckedListener
            }
            LauncherPrefs.setRendererAuto(wantAuto)
            refreshRenderer()
        }
    }

    private fun refreshRenderer() {
        val b = _binding ?: return
        val auto = LauncherPrefs.isRendererAuto()
        val checkedId = if (auto) R.id.buttonRendererAuto else R.id.buttonRendererManual
        if (b.toggleRendererMode.checkedButtonId != checkedId) {
            b.toggleRendererMode.check(checkedId)
        }
        b.panelRendererManual.isVisible = !auto

        if (auto) {
            b.textRenderer.text = getString(
                R.string.settings_renderer_current,
                getString(R.string.settings_renderer_auto)
            )
            b.buttonDownloadRenderer.isVisible = false
            return
        }

        val kind = LauncherPrefs.rendererKind()
            ?: LauncherPrefs.lastManualRendererKind()
        val label = kind?.displayName ?: getString(R.string.settings_renderer_pick)
        val status = when {
            kind == null -> ""
            !kind.requiresPlugin -> " · ${getString(R.string.settings_renderer_builtin)}"
            RendererInstaller.isInstalled(kind) -> " · ${getString(R.string.settings_renderer_installed)}"
            else -> " · ${getString(R.string.settings_renderer_missing)}"
        }
        b.textRenderer.text = getString(R.string.settings_renderer_current, label + status)
        b.buttonRenderer.text = label
        val needDownload = kind != null && kind.requiresPlugin && !RendererInstaller.isInstalled(kind)
        b.buttonDownloadRenderer.isVisible = needDownload
    }

    private fun showRendererPicker() {
        val options = RendererPackages.selectableKinds().map { kind ->
            val status = when {
                !kind.requiresPlugin -> getString(R.string.settings_renderer_builtin)
                RendererInstaller.isInstalled(kind) -> getString(R.string.settings_renderer_installed)
                else -> getString(R.string.settings_renderer_missing)
            }
            kind to "${kind.displayName}（$status）"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_renderer_pick)
            .setItems(options.map { it.second }.toTypedArray()) { _, which ->
                LauncherPrefs.setRendererKind(options[which].first)
                refreshRenderer()
            }
            .show()
    }

    private fun downloadSelectedRenderer() {
        val kind = LauncherPrefs.rendererKind() ?: return
        if (!kind.requiresPlugin) return
        viewLifecycleOwner.lifecycleScope.launch {
            val b = _binding ?: return@launch
            b.buttonDownloadRenderer.isEnabled = false
            b.buttonRenderer.isEnabled = false
            b.buttonRendererAuto.isEnabled = false
            b.buttonRendererManual.isEnabled = false
            b.textRenderer.text = getString(R.string.settings_renderer_downloading, kind.displayName)
            val result = RendererInstaller.ensureInstalled(kind)
            _binding ?: return@launch
            b.buttonDownloadRenderer.isEnabled = true
            b.buttonRenderer.isEnabled = true
            b.buttonRendererAuto.isEnabled = true
            b.buttonRendererManual.isEnabled = true
            refreshRenderer()
            val context = context ?: return@launch
            result.fold(
                onSuccess = {
                    Toast.makeText(
                        context,
                        getString(R.string.settings_renderer_download_done, kind.displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { err ->
                    Toast.makeText(
                        context,
                        getString(
                            R.string.settings_renderer_download_failed,
                            err.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun setupMemorySlider() {
        val b = _binding ?: return
        val maxAllowed = LauncherPrefs.recommendedMaxMb().toFloat()
        b.sliderMemory.valueFrom = LauncherPrefs.MEMORY_MIN_MB.toFloat()
        b.sliderMemory.valueTo = maxAllowed
        b.sliderMemory.stepSize = LauncherPrefs.MEMORY_STEP_MB.toFloat()
        val current = LauncherPrefs.maxMemoryMb().coerceIn(
            LauncherPrefs.MEMORY_MIN_MB,
            maxAllowed.toInt()
        )
        b.sliderMemory.value = current.toFloat()
        updateMemoryLabel(current)
        b.sliderMemory.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            val mb = LauncherPrefs.clampMemory(value.toInt())
            updateMemoryLabel(mb)
            if (fromUser) LauncherPrefs.setMaxMemoryMb(mb)
        }
    }

    private fun updateMemoryLabel(mb: Int) {
        val b = _binding ?: return
        b.textMemory.text = getString(R.string.settings_memory_value, mb)
    }

    private fun bindJavaButton(button: MaterialButton, major: Int) {
        button.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val componentId = "java-$major"
                val present = AppContainer.javaEnvironment.isPresent(componentId)
                setJavaButtonsEnabled(false)
                if (present) {
                    val result = AppContainer.javaEnvironment.delete(componentId)
                    _binding ?: return@launch
                    setJavaButtonsEnabled(true)
                    refreshJavaStatus()
                    val context = context ?: return@launch
                    if (result.isSuccess) {
                        Toast.makeText(
                            context,
                            getString(R.string.settings_java_uninstall_done, major),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            getString(
                                R.string.settings_java_uninstall_failed,
                                result.exceptionOrNull()?.message ?: "unknown"
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
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
    }

    private fun setJavaButtonsEnabled(enabled: Boolean) {
        val b = _binding ?: return
        b.buttonDownloadJava8.isEnabled = enabled
        b.buttonDownloadJava17.isEnabled = enabled
        b.buttonDownloadJava21.isEnabled = enabled
        b.buttonDownloadJava25.isEnabled = enabled
    }

    private fun refreshRealtimeLog() {
        val b = _binding ?: return
        val on = RealtimeLaunchLog.isEnabled()
        b.textRealtimeLog.text = buildString {
            append(getString(R.string.settings_realtime_log_hint))
            append('\n')
            append(getString(R.string.settings_realtime_log_status, RealtimeLaunchLog.statusText()))
        }
        b.buttonRealtimeLogStart.isEnabled = !on
        b.buttonRealtimeLogStop.isEnabled = true
    }

    private fun refreshDownloadSource() {
        val b = _binding ?: return
        val source = DownloadProviders.source
        b.buttonDownloadSource.text = source.displayName
        b.textDownloadSource.text = buildString {
            append(getString(R.string.settings_download_source_hint))
            append('\n')
            append(DownloadProviders.statusText())
        }
        b.buttonProbeDownloadSource.isEnabled = true
    }

    private fun probeDownloadSource(force: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val b = _binding ?: return@launch
            b.buttonProbeDownloadSource.isEnabled = false
            b.textDownloadSource.text = getString(R.string.settings_download_source_probing)
            val result = runCatching {
                DownloadProviders.ensureProbed(requireContext(), force = force)
            }.getOrElse {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_download_source_probed, it.message ?: "失败"),
                    Toast.LENGTH_LONG
                ).show()
                null
            }
            _binding ?: return@launch
            refreshDownloadSource()
            if (result != null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_download_source_probed, result.summary),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
        val present = AppContainer.javaEnvironment.isPresent("java-$major")
        button.text = if (present) {
            getString(R.string.settings_java_uninstall, major)
        } else {
            getString(R.string.settings_java_download, major)
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            refreshRealtimeLog()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
