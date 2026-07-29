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
import com.booxin.launcher.databinding.FragmentAccountsBinding
import com.booxin.launcher.ui.auth.MicrosoftAuthErrorDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AccountsFragment : Fragment() {

    private var _binding: FragmentAccountsBinding? = null
    private val binding get() = _binding!!
    private val adapter = AccountsAdapter(
        onSelect = { AppContainer.repository.selectAccount(it.id) },
        onDelete = { AppContainer.repository.removeAccount(it.id) }
    )
    private var loginJob: Job? = null

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
                    getString(R.string.accounts_offline)
                )
            ) { _, which ->
                when (which) {
                    0 -> startMicrosoftLogin()
                    1 -> showAddOfflineDialog()
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accounts_add)
            .setMessage(R.string.accounts_offline)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppContainer.repository.addOfflineAccount(input.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
