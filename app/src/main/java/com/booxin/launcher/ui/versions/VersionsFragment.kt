package com.booxin.launcher.ui.versions

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.import.ForeignLauncherImporter
import com.booxin.launcher.core.import.ForeignMinecraftRoot
import com.booxin.launcher.core.import.ForeignVersionEntry
import com.booxin.launcher.data.model.GameVersion
import com.booxin.launcher.data.model.ModpackInstallProgress
import com.booxin.launcher.databinding.FragmentVersionsBinding
import com.booxin.launcher.ui.controller.ControllerNavBinder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VersionsFragment : Fragment() {

    private var _binding: FragmentVersionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VersionsAdapter
    private var progressDialog: AlertDialog? = null
    private var lastProgressUiAtMs = 0L
    private var itemTouchHelper: ItemTouchHelper? = null

    private val pickModpack = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        installModpackFromUri(uri)
    }

    private val requestManageAllFiles = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (ForeignLauncherImporter.hasAllFilesAccess()) {
            startForeignImportFlow()
        } else {
            Toast.makeText(
                requireContext(),
                R.string.versions_import_foreign_need_storage,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private var pendingManualImportKind: com.booxin.launcher.core.import.ForeignLauncherKind? = null

    private val pickForeignMinecraftDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                pendingManualImportKind = null
                return@registerForActivityResult
            }
            val kind = pendingManualImportKind
            pendingManualImportKind = null
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            }
            val path = com.booxin.launcher.core.SafTreePath.toAbsolutePath(uri)
            val rootFile = path?.let { File(it) }
            val resolved = rootFile?.let { dir ->
                when {
                    File(dir, "versions").isDirectory -> dir
                    File(dir, ".minecraft/versions").isDirectory -> File(dir, ".minecraft")
                    else -> null
                }
            }
            if (resolved == null || kind == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.versions_import_foreign_pick_folder_failed,
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
            loadAndPickForeignVersions(
                ForeignMinecraftRoot(
                    kind = kind,
                    root = resolved,
                    packageInstalled = true,
                    readable = true
                )
            )
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
        adapter = VersionsAdapter(
            selectedIdProvider = { AppContainer.repository.session.value.selectedVersionId },
            onMenu = { version -> showVersionMenu(version) },
            onManage = { version -> openVersionManage(version) }
        ) { version ->
            AppContainer.repository.selectVersion(version.id)
            adapter.notifyDataSetChanged()
            Toast.makeText(
                requireContext(),
                getString(R.string.home_switched, version.displayName),
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.recyclerVersions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerVersions.adapter = adapter
        ControllerNavBinder.bindRecycler(binding.recyclerVersions)
        ControllerNavBinder.bindButton(binding.buttonDownload)
        ControllerNavBinder.bindButton(binding.buttonInstallModpack)
        ControllerNavBinder.bindButton(binding.buttonImportForeign)
        attachDragReorder()

        binding.buttonDownload.setOnClickListener {
            findNavController().navigate(R.id.action_versions_to_download)
        }
        binding.buttonInstallModpack.setOnClickListener { showInstallModpackChooser() }
        binding.buttonImportForeign.setOnClickListener { onImportForeignClicked() }

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

    private fun attachDragReorder() {
        itemTouchHelper?.attachToRecyclerView(null)
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                AppContainer.repository.reorderInstalledVersions(adapter.currentIds())
            }
        }
        itemTouchHelper = ItemTouchHelper(callback).also {
            it.attachToRecyclerView(binding.recyclerVersions)
        }
    }

    private fun showVersionMenu(version: GameVersion) {
        val isDefault = version.isDefault
        val items = buildList {
            add(getString(R.string.versions_rename))
            add(
                if (isDefault) getString(R.string.versions_clear_default)
                else getString(R.string.versions_set_default)
            )
            add(getString(R.string.versions_delete_action))
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(version.displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameDialog(version)
                    1 -> {
                        if (isDefault) {
                            AppContainer.repository.setDefaultVersion(null)
                        } else {
                            AppContainer.repository.setDefaultVersion(version.id)
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.versions_set_default_done, version.displayName),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    2 -> confirmDeleteVersion(version)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(version: GameVersion) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.versions_rename_hint)
            setPadding(pad, pad / 2, pad, 0)
        }
        val edit = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setText(version.customDisplayName ?: version.id)
            setSelectAllOnFocus(true)
        }
        inputLayout.addView(edit)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.versions_rename_title)
            .setMessage(getString(R.string.versions_rename_hint) + "\nID: ${version.id}")
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppContainer.repository.setVersionDisplayName(
                    version.id,
                    edit.text?.toString()
                )
                Toast.makeText(requireContext(), R.string.versions_rename_done, Toast.LENGTH_SHORT)
                    .show()
            }
            .show()
        edit.requestFocus()
    }

    private fun showInstallModpackChooser() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.versions_install_modpack_title)
            .setItems(
                arrayOf(
                    getString(R.string.versions_install_modpack_local),
                    getString(R.string.versions_install_modpack_url)
                )
            ) { _, which ->
                when (which) {
                    0 -> pickModpack.launch(
                        arrayOf(
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                    1 -> showModpackUrlDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showModpackUrlDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.versions_install_modpack_url_hint)
            setPadding(pad, pad / 2, pad, 0)
        }
        val edit = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3
            setTextIsSelectable(true)
        }
        inputLayout.addView(edit)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.versions_install_modpack_url_title)
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                installModpackFromUrl(edit.text?.toString().orEmpty().trim())
            }
            .show()
        edit.requestFocus()
    }

    private fun installModpackFromUrl(url: String) {
        if (!isHttpUrl(url)) {
            Toast.makeText(
                requireContext(),
                R.string.versions_install_modpack_url_invalid,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            showProgress(getString(R.string.versions_install_modpack_progress))
            val result = AppContainer.communityRepository.installModpackFromUrl(
                url = url,
                onProgress = { progress -> updateModpackProgress(progress) }
            )
            dismissProgress()
            if (_binding == null) return@launch
            result.fold(
                onSuccess = { target ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.versions_install_modpack_done, target),
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { err ->
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.versions_install_modpack_failed,
                            err.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun installModpackFromUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            showProgress(getString(R.string.versions_install_modpack_copying))
            val archiveResult = withContext(Dispatchers.IO) { copyUriToModpackCache(uri) }
            if (archiveResult.isFailure) {
                dismissProgress()
                if (_binding == null) return@launch
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.versions_install_modpack_failed,
                        archiveResult.exceptionOrNull()?.message
                            ?: getString(R.string.versions_install_modpack_read_failed)
                    ),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            showProgress(getString(R.string.versions_install_modpack_progress))
            val result = AppContainer.communityRepository.installModpackFromArchive(
                archive = archiveResult.getOrThrow(),
                onProgress = { progress -> updateModpackProgress(progress) }
            )
            dismissProgress()
            if (_binding == null) return@launch
            result.fold(
                onSuccess = { target ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.versions_install_modpack_done, target),
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { err ->
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.versions_install_modpack_failed,
                            err.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun copyUriToModpackCache(uri: Uri): Result<File> = runCatching {
        val resolver = requireContext().contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
            ?.takeIf { it.isNotBlank() }
            ?: "modpack.mrpack"
        val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val cacheDir = File(LauncherPaths.rootDir, "cache/modrinth/modpacks").also { it.mkdirs() }
        val dest = File(cacheDir, safeName)
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error(getString(R.string.versions_install_modpack_read_failed))
        require(dest.isFile && dest.length() > 0L) {
            getString(R.string.versions_install_modpack_read_failed)
        }
        dest
    }

    private fun updateModpackProgress(progress: ModpackInstallProgress) {
        val now = System.currentTimeMillis()
        // Stage / file-index changes always refresh; byte ticks are throttled.
        val stageOnly = progress.bytesDownloaded < 0L
        if (!stageOnly && now - lastProgressUiAtMs < 200L) return
        lastProgressUiAtMs = now
        val text = formatModpackProgress(progress)
        // Callback may arrive on IO; hop to main for dialog updates.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main.immediate) {
            if (_binding == null) return@launch
            showProgress(text)
        }
    }

    private fun formatModpackProgress(progress: ModpackInstallProgress): String {
        var line = progress.stage
        if (progress.total > 0) {
            line = getString(
                R.string.versions_install_modpack_progress_files,
                progress.stage,
                progress.current,
                progress.total
            )
        }
        if (progress.bytesTotal > 0L) {
            line = getString(
                R.string.versions_install_modpack_progress_bytes,
                line,
                formatBytes(progress.bytesDownloaded.coerceAtLeast(0L)),
                formatBytes(progress.bytesTotal)
            )
        } else if (progress.bytesDownloaded > 0L) {
            line = "$line · ${formatBytes(progress.bytesDownloaded)}"
        }
        if (progress.detail.isNotBlank()) {
            line = "$line\n${progress.detail}"
        }
        return line
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun onImportForeignClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.versions_import_foreign_title)
                .setMessage(R.string.versions_import_foreign_need_storage)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.versions_import_foreign_go_settings) { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    runCatching { requestManageAllFiles.launch(intent) }
                        .onFailure {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                }
                .show()
            return
        }
        startForeignImportFlow()
    }

    private fun startForeignImportFlow() {
        viewLifecycleOwner.lifecycleScope.launch {
            showProgress(
                getString(R.string.versions_import_foreign_scanning),
                R.string.versions_import_foreign_title
            )
            val roots = withContext(Dispatchers.IO) {
                ForeignLauncherImporter.detectRoots(requireContext())
            }
            dismissProgress()
            if (!isAdded) return@launch
            if (roots.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.versions_import_foreign_title)
                    .setMessage(R.string.versions_import_foreign_none)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            showForeignRootPicker(roots)
        }
    }

    private fun showForeignRootPicker(roots: List<ForeignMinecraftRoot>) {
        val labels = roots.map { root ->
            val status = when {
                !root.readable -> getString(R.string.versions_import_foreign_unreadable)
                root.packageInstalled -> getString(R.string.versions_import_foreign_installed)
                else -> getString(R.string.versions_import_foreign_not_installed)
            }
            getString(
                R.string.versions_import_foreign_root_item,
                root.kind.displayName,
                status,
                root.root.absolutePath
            )
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.versions_import_foreign_pick_root)
            .setItems(labels) { _, which ->
                val root = roots.getOrNull(which) ?: return@setItems
                if (!root.readable) {
                    showUnreadableImportHelp(root)
                } else {
                    loadAndPickForeignVersions(root)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showUnreadableImportHelp(root: ForeignMinecraftRoot) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.versions_import_foreign_pick_folder_title, root.kind.displayName))
            .setMessage(R.string.versions_import_foreign_pick_folder_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.versions_import_foreign_rescan) { _, _ ->
                startForeignImportFlow()
            }
            .setPositiveButton(R.string.versions_import_foreign_pick_folder) { _, _ ->
                pendingManualImportKind = root.kind
                pickForeignMinecraftDir.launch(null)
            }
            .show()
    }

    private fun loadAndPickForeignVersions(root: ForeignMinecraftRoot) {
        viewLifecycleOwner.lifecycleScope.launch {
            showProgress(
                getString(R.string.versions_import_foreign_scanning),
                R.string.versions_import_foreign_title
            )
            val versions = withContext(Dispatchers.IO) {
                ForeignLauncherImporter.listVersions(root)
            }
            dismissProgress()
            if (!isAdded) return@launch
            if (versions.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.versions_import_foreign_no_versions,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            showForeignVersionPicker(root, versions)
        }
    }

    private fun showForeignVersionPicker(
        root: ForeignMinecraftRoot,
        versions: List<ForeignVersionEntry>
    ) {
        val labels = versions.map { it.summaryLine() }.toTypedArray()
        val checked = BooleanArray(versions.size) { true }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.versions_import_foreign_pick_versions)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.versions_import_foreign) { _, _ ->
                val selected = versions.filterIndexed { i, _ -> checked.getOrElse(i) { false } }
                if (selected.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.versions_import_foreign_none_selected,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                runForeignImport(root, selected)
            }
            .show()
    }

    private fun runForeignImport(
        root: ForeignMinecraftRoot,
        selected: List<ForeignVersionEntry>
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            showProgress(
                getString(R.string.versions_import_foreign_progress),
                R.string.versions_import_foreign_title
            )
            val result = withContext(Dispatchers.IO) {
                ForeignLauncherImporter.importVersions(root, selected) { progress ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUiAtMs < 200L &&
                        progress.current < progress.total
                    ) {
                        return@importVersions
                    }
                    lastProgressUiAtMs = now
                    val msg = buildString {
                        append(progress.stage)
                        if (progress.total > 0) {
                            append("（${progress.current}/${progress.total}）")
                        }
                        if (progress.detail.isNotBlank()) {
                            append('\n')
                            append(progress.detail)
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        if (isAdded) {
                            showProgress(msg, R.string.versions_import_foreign_title)
                        }
                    }
                }
            }
            dismissProgress()
            if (!isAdded) return@launch
            AppContainer.repository.refreshInstalledVersions()
            val summary = getString(
                R.string.versions_import_foreign_done_detail,
                result.importedIds.size,
                result.skippedIds.size,
                result.failed.size
            )
            if (result.failed.isNotEmpty() && result.importedIds.isEmpty()) {
                val err = result.failed.joinToString("\n") { "${it.first}: ${it.second}" }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.versions_import_foreign_title)
                    .setMessage(getString(R.string.versions_import_foreign_failed, err))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.versions_import_foreign_title)
                    .setMessage(
                        getString(R.string.versions_import_foreign_done, result.importedIds.size) +
                            "\n" + summary
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showProgress(
        message: String,
        titleRes: Int = R.string.versions_install_modpack_title
    ) {
        val existing = progressDialog
        if (existing?.isShowing == true) {
            existing.setTitle(titleRes)
            existing.setMessage(message)
            return
        }
        progressDialog?.dismiss()
        progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(message)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
        lastProgressUiAtMs = 0L
    }

    private fun isHttpUrl(url: String): Boolean =
        url.isNotBlank() &&
            (url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true))

    private fun openVersionManage(version: GameVersion) {
        findNavController().navigate(
            R.id.action_versions_to_version_manage,
            Bundle().apply { putString("versionId", version.id) }
        )
    }

    private fun confirmDeleteVersion(version: GameVersion) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.versions_delete_title)
            .setMessage(getString(R.string.versions_delete_confirm, version.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.versions_delete_action) { _, _ ->
                val result = AppContainer.repository.deleteInstalledVersion(version.id)
                if (result.isSuccess) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.versions_delete_done, version.displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.versions_delete_failed,
                            result.exceptionOrNull()?.message ?: "unknown"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        AppContainer.repository.refreshInstalledVersions()
    }

    override fun onDestroyView() {
        dismissProgress()
        super.onDestroyView()
        _binding = null
    }
}
