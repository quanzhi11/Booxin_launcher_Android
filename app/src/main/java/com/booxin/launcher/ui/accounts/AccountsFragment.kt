package com.booxin.launcher.ui.accounts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.auth.MicrosoftAuthLogger
import com.booxin.launcher.core.auth.MicrosoftAuthService
import com.booxin.launcher.core.skin.OfflineSkinStore
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.databinding.FragmentAccountsBinding
import com.booxin.launcher.ui.auth.MicrosoftAuthErrorDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountsFragment : Fragment() {

    private var _binding: FragmentAccountsBinding? = null
    private val binding get() = _binding!!
    private val adapter = AccountsAdapter(
        onSelect = { AppContainer.repository.selectAccount(it.id) },
        onDelete = { AppContainer.repository.removeAccount(it.id) },
        onSkin = { showSkinDialog(it) }
    )
    private var loginJob: Job? = null
    private var pendingSkinAccountId: String? = null

    private val pickSkin = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val accountId = pendingSkinAccountId
        pendingSkinAccountId = null
        if (uri == null || accountId == null) return@registerForActivityResult
        importSkin(accountId, uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerAccounts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerAccounts.adapter = adapter

        binding.buttonAddAccount.setOnClickListener { showAddChooser() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppContainer.repository.accounts.collect { list ->
                    adapter.submit(list)
                    binding.textEmpty.isVisible = list.isEmpty()
                }
            }
        }
    }

    private fun showAddChooser() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accounts_add)
            .setItems(
                arrayOf(
                    getString(R.string.accounts_microsoft),
                    getString(R.string.accounts_offline),
                    getString(R.string.accounts_third_party_coming)
                )
            ) { _, which ->
                when (which) {
                    0 -> startMicrosoftLogin()
                    1 -> showAddOfflineDialog()
                    2 -> Toast.makeText(
                        requireContext(),
                        R.string.ai_feature_coming,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddOfflineDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.accounts_offline)
            setPadding(48, 32, 48, 32)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accounts_add)
            .setMessage(R.string.accounts_offline)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString().orEmpty().trim()
                if (!com.booxin.launcher.core.launch.OfflineAuth.isValidUsername(name)) {
                    Toast.makeText(
                        requireContext(),
                        R.string.accounts_offline_invalid_name,
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                runCatching {
                    AppContainer.repository.addOfflineAccount(name)
                    dialog.dismiss()
                }.onFailure {
                    Toast.makeText(
                        requireContext(),
                        it.message ?: getString(R.string.accounts_offline_invalid_name),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        dialog.show()
    }

    private fun showSkinDialog(account: LauncherAccount) {
        if (account.type != AccountType.OFFLINE) {
            Toast.makeText(requireContext(), R.string.accounts_skin_ms_only_offline, Toast.LENGTH_SHORT)
                .show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accounts_skin_title)
            .setItems(
                arrayOf(
                    getString(R.string.accounts_skin_import),
                    getString(R.string.accounts_skin_clear)
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        pendingSkinAccountId = account.id
                        pickSkin.launch("image/*")
                    }
                    1 -> {
                        OfflineSkinStore.clear(account.id)
                        AppContainer.repository.setOfflineSkin(account.id, null)
                        Toast.makeText(requireContext(), R.string.accounts_skin_cleared, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importSkin(accountId: String, uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                OfflineSkinStore.importFromUri(requireContext(), accountId, uri)
            }
            if (!isAdded) return@launch
            result.fold(
                onSuccess = { file ->
                    AppContainer.repository.setOfflineSkin(accountId, file.absolutePath)
                    Toast.makeText(requireContext(), R.string.accounts_skin_imported, Toast.LENGTH_SHORT)
                        .show()
                },
                onFailure = { err ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.accounts_skin_failed, err.message ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun startMicrosoftLogin() {
        val status = TextView(requireContext()).apply {
            setPadding(48, 32, 48, 32)
            text = getString(R.string.accounts_ms_starting)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accounts_microsoft)
            .setView(status)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                loginJob?.cancel()
            }
            .setCancelable(false)
            .create()
        dialog.show()

        loginJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = MicrosoftAuthService.loginWithDeviceCode { progress ->
                activity?.runOnUiThread {
                    status.text = progress.message
                    progress.userCode?.let { code ->
                        val cm = requireContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("ms_code", code))
                    }
                    progress.verificationUri?.let { uri ->
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                        }
                    }
                }
            }
            if (!isAdded) return@launch
            dialog.dismiss()
            result.fold(
                onSuccess = { account ->
                    AppContainer.repository.upsertMicrosoftAccount(account)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.accounts_ms_ok, account.name),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { err ->
                    val summary = getString(
                        R.string.accounts_ms_failed,
                        err.message ?: "unknown"
                    )
                    val log = MicrosoftAuthLogger.lastReport
                        ?: "（无详细日志）\n${err.message}"
                    MicrosoftAuthErrorDialog.show(requireContext(), summary, log)
                }
            )
        }
    }

    override fun onDestroyView() {
        loginJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
