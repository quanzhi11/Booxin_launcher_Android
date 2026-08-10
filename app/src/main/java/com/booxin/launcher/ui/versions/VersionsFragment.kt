package com.booxin.launcher.ui.versions

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.data.model.GameVersion
import com.booxin.launcher.data.model.ModpackInstallProgress
import com.booxin.launcher.databinding.FragmentVersionsBinding
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

    private val pickModpack = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        installModpackFromUri(uri)
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
            onDelete = { version -> confirmDeleteVersion(version) },
            onManage = { version -> openVersionManage(version) }
        ) { version ->
            AppContainer.repository.selectVersion(version.id)
            adapter.notifyDataSetChanged()
            Toast.makeText(
                requireContext(),
                getString(R.string.home_switched, version.id),
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.recyclerVersions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerVersions.adapter = adapter

        binding.buttonDownload.setOnClickListener {
            findNavController().navigate(R.id.action_versions_to_download)
        }
        binding.buttonInstallModpack.setOnClickListener { showInstallModpackChooser() }

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

    private fun showProgress(message: String) {
        val existing = progressDialog
        if (existing?.isShowing == true) {
            existing.setMessage(message)
            return
        }
        progressDialog?.dismiss()
        progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.versions_install_modpack_title)
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
            .setMessage(getString(R.string.versions_delete_confirm, version.id))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.versions_delete_action) { _, _ ->
                val result = AppContainer.repository.deleteInstalledVersion(version.id)
                if (result.isSuccess) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.versions_delete_done, version.id),
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
