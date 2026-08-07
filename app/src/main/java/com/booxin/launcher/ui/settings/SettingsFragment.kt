package com.booxin.launcher.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.booxin.launcher.AppContainer
import com.booxin.launcher.BuildConfig
import com.booxin.launcher.R
import com.booxin.launcher.core.GameDirItem
import com.booxin.launcher.core.GameDirLocation
import com.booxin.launcher.core.GameDirRegistry
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.LauncherPrefs
import com.booxin.launcher.core.SafTreePath
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
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var gameDirListDialog: androidx.appcompat.app.AlertDialog? = null
    private var refreshGameDirListUi: (() -> Unit)? = null
    private var pendingAfterStoragePermission: (() -> Unit)? = null

    private val requestLegacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            val next = pendingAfterStoragePermission
            pendingAfterStoragePermission = null
            if (granted) {
                next?.invoke()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.settings_game_dir_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
                // Still proceed — internal / app-external paths do not need it.
                next?.invoke()
            }
        }

    private val requestManageAllFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val next = pendingAfterStoragePermission
            pendingAfterStoragePermission = null
            if (!hasFullStorageAccess()) {
                Toast.makeText(
                    requireContext(),
                    R.string.settings_game_dir_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
            next?.invoke()
        }

    private val pickGameDirFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                ctx.contentResolver.takePersistableUriPermission(uri, flags)
            }
            val path = SafTreePath.toAbsolutePath(uri)
            if (path.isNullOrBlank()) {
                Toast.makeText(
                    ctx,
                    R.string.settings_game_dir_pick_folder_failed,
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
            addAndMaybeSelect(path)
        }

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
        refreshGameDir()
        binding.textAbout.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        refreshDownloadSource()
        refreshJavaStatus()
        setupRendererModeToggle()
        refreshRenderer()
        setupMemorySlider()
        refreshRealtimeLog()

        binding.buttonGameDir.setOnClickListener {
            ensureStoragePermissionThen { showGameDirListDialog() }
        }
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

    private fun refreshGameDir() {
        val b = _binding ?: return
        b.textGameDir.text = LauncherPaths.currentLocationLabel(requireContext())
    }

    private fun hasFullStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun ensureStoragePermissionThen(onReady: () -> Unit) {
        val ctx = requireContext()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    onReady()
                    return
                }
                Toast.makeText(ctx, R.string.settings_game_dir_manage_storage, Toast.LENGTH_LONG)
                    .show()
                pendingAfterStoragePermission = onReady
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                }
                runCatching { requestManageAllFiles.launch(intent) }
                    .onFailure {
                        pendingAfterStoragePermission = null
                        // Fallback: open generic all-files page, then continue.
                        runCatching {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                        onReady()
                    }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val need = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    need += Manifest.permission.READ_EXTERNAL_STORAGE
                }
                if (Build.VERSION.SDK_INT <= 28 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    need += Manifest.permission.WRITE_EXTERNAL_STORAGE
                }
                if (need.isEmpty()) {
                    onReady()
                } else {
                    Toast.makeText(ctx, R.string.settings_game_dir_permission_needed, Toast.LENGTH_SHORT)
                        .show()
                    pendingAfterStoragePermission = onReady
                    requestLegacyStoragePermission.launch(need.toTypedArray())
                }
            }
            else -> onReady()
        }
    }

    private fun showGameDirListDialog() {
        val ctx = requireContext()
        val content = layoutInflater.inflate(R.layout.dialog_game_dir_list, null, false)
        val listHost = content.findViewById<LinearLayout>(R.id.layoutGameDirList)
        val addButton = content.findViewById<MaterialButton>(R.id.buttonGameDirAdd)

        fun bindList() {
            listHost.removeAllViews()
            val selected = GameDirRegistry.selectedPath(ctx)
            GameDirRegistry.list(ctx).forEach { item ->
                listHost.addView(inflateGameDirRow(listHost, item, selected == item.path))
            }
        }

        refreshGameDirListUi = { bindList() }
        addButton.setOnClickListener { showAddGameDirPicker() }
        bindList()

        gameDirListDialog?.dismiss()
        gameDirListDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_game_dir_pick)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                gameDirListDialog = null
                refreshGameDirListUi = null
            }
            .show()
    }

    private fun refreshOpenGameDirList() {
        refreshGameDirListUi?.invoke()
    }

    private fun inflateGameDirRow(
        parent: ViewGroup,
        item: GameDirItem,
        isSelected: Boolean
    ): View {
        val row = layoutInflater.inflate(R.layout.item_game_dir, parent, false)
        val title = row.findViewById<TextView>(R.id.textGameDirTitle)
        val pathView = row.findViewById<TextView>(R.id.textGameDirPath)
        val select = row.findViewById<View>(R.id.layoutGameDirSelect)
        val remove = row.findViewById<ImageButton>(R.id.buttonGameDirRemove)

        val label = when {
            item.isDefault -> getString(R.string.settings_game_dir_internal)
            else -> displayNameForPath(item.path)
        }
        title.text = if (isSelected) {
            "$label ${getString(R.string.settings_game_dir_current_mark)}"
        } else {
            label
        }
        pathView.text = item.path

        select.setOnClickListener { confirmSwitchToPath(item.path) }
        if (item.isDefault) {
            remove.isVisible = false
        } else {
            remove.isVisible = true
            remove.setOnClickListener { confirmRemoveGameDir(item.path) }
        }
        return row
    }

    private fun displayNameForPath(path: String): String {
        val ctx = requireContext()
        val external = GameDirLocation.EXTERNAL_APP.resolve(ctx, null).absolutePath
        val publicGames = GameDirLocation.PUBLIC_GAMES.resolve(ctx, null).absolutePath
        return when (File(path).absolutePath) {
            external -> getString(R.string.settings_game_dir_external)
            publicGames -> getString(R.string.settings_game_dir_public)
            else -> getString(R.string.settings_game_dir_custom)
        }
    }

    private fun showAddGameDirPicker() {
        val options = listOf(
            GameDirLocation.EXTERNAL_APP,
            GameDirLocation.PUBLIC_GAMES,
            GameDirLocation.CUSTOM
        )
        val labels = options.map { getString(it.labelRes()) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_game_dir_add_pick)
            .setItems(labels) { _, which ->
                when (val picked = options[which]) {
                    GameDirLocation.CUSTOM -> openFolderPicker()
                    else -> addAndMaybeSelect(picked.resolve(requireContext(), null).absolutePath)
                }
            }
            .show()
    }

    private fun openFolderPicker() {
        // Prefer starting in shared storage root when the system supports it.
        val initial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:"
                )
            }.getOrNull()
        } else {
            null
        }
        pickGameDirFolder.launch(initial)
    }

    private fun addAndMaybeSelect(path: String) {
        val ctx = requireContext()
        GameDirRegistry.addPath(ctx, path).fold(
            onSuccess = {
                Toast.makeText(ctx, R.string.settings_game_dir_added, Toast.LENGTH_SHORT).show()
                refreshOpenGameDirList()
            },
            onFailure = { err ->
                Toast.makeText(
                    ctx,
                    getString(R.string.settings_game_dir_failed, err.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun confirmRemoveGameDir(path: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_game_dir_remove_title)
            .setMessage(getString(R.string.settings_game_dir_remove_message, path))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ctx = requireContext()
                val wasSelected = GameDirRegistry.isSelected(ctx, path)
                GameDirRegistry.removePath(ctx, path).fold(
                    onSuccess = {
                        if (wasSelected) {
                            // Persist default and restart so runtime picks it up.
                            applySwitchToPath(GameDirRegistry.defaultPath(ctx).absolutePath)
                        } else {
                            refreshOpenGameDirList()
                            refreshGameDir()
                        }
                    },
                    onFailure = { err ->
                        Toast.makeText(
                            ctx,
                            getString(R.string.settings_game_dir_failed, err.message ?: "unknown"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
            .show()
    }

    private fun confirmSwitchToPath(path: String) {
        val ctx = requireContext()
        val abs = File(path).absolutePath
        if (abs == LauncherPaths.rootDir.absolutePath &&
            abs == GameDirRegistry.selectedPath(ctx)
        ) {
            Toast.makeText(ctx, R.string.settings_game_dir_same, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_game_dir_confirm_title)
            .setMessage(getString(R.string.settings_game_dir_confirm_message, abs))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applySwitchToPath(abs)
            }
            .show()
    }

    private fun applySwitchToPath(path: String) {
        val ctx = requireContext().applicationContext
        val result = LauncherPaths.switchToRegistered(ctx, path)
        result.fold(
            onSuccess = { root ->
                runCatching {
                    com.booxin.launcher.core.launch.AndroidGameRuntime.ensure(ctx)
                }
                refreshGameDir()
                Toast.makeText(ctx, root.absolutePath, Toast.LENGTH_SHORT).show()
                restartApp(ctx)
            },
            onFailure = { err ->
                // Path may not be in registry yet (legacy callers) — try switchRoot CUSTOM.
                val fallback = LauncherPaths.switchRoot(
                    ctx,
                    GameDirLocation.CUSTOM,
                    path
                )
                fallback.fold(
                    onSuccess = { root ->
                        runCatching {
                            com.booxin.launcher.core.launch.AndroidGameRuntime.ensure(ctx)
                        }
                        refreshGameDir()
                        Toast.makeText(ctx, root.absolutePath, Toast.LENGTH_SHORT).show()
                        restartApp(ctx)
                    },
                    onFailure = { e2 ->
                        Toast.makeText(
                            requireContext(),
                            getString(
                                R.string.settings_game_dir_failed,
                                e2.message ?: err.message ?: "unknown"
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        )
    }

    private fun restartApp(context: android.content.Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) {
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            context.startActivity(launch)
        }
        Process.killProcess(Process.myPid())
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
        gameDirListDialog?.dismiss()
        gameDirListDialog = null
        refreshGameDirListUi = null
        pendingAfterStoragePermission = null
        super.onDestroyView()
        _binding = null
    }
}
