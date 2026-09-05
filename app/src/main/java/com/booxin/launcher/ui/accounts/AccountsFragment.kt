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
import com.booxin.launcher.core.skin.OfflineSkinMode
import com.booxin.launcher.core.skin.OfflineSkinService
import com.booxin.launcher.core.skin.OfflineSkinStore
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.databinding.DialogOfflineSkinBinding
import com.booxin.launcher.databinding.FragmentAccountsBinding
import com.booxin.launcher.ui.auth.MicrosoftAuthErrorDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountsFragment : Fragment() {

    private var _binding: FragmentAccountsBinding? = null
    private val binding get() = _binding!!
    private var loginJob: Job? = null
    private var pendingSkinAccountId: String? = null
    private var skinDialogBinding: DialogOfflineSkinBinding? = null
    private var skinDialogAccount: LauncherAccount? = null
    private var pendingCustomUri: Uri? = null
    private var fetchedPlayer: OfflineSkinService.PlayerProfile? = null

    private val pickSkin = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val b = skinDialogBinding ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取图片")
                    val validation = OfflineSkinService.validateSkinBytes(bytes)
                    require(validation.isValid) { validation.message }
                    validation to uri
                }
            }
            if (!isAdded) return@launch
            result.fold(
                onSuccess = { (validation, picked) ->
                    pendingCustomUri = picked
                    b.radioSkinCustom.isChecked = true
                    b.textCustomSkinPath.text = picked.lastPathSegment ?: picked.toString()
                    b.textSkinStatus.text = "${validation.message}。点击「保存」后才会应用。"
                    updateSkinPreview(b)
                },
                onFailure = { err ->
                    b.textSkinStatus.text = err.message ?: getString(R.string.accounts_skin_failed, "unknown")
                }
            )
        }
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
        val accountsAdapter = AccountsAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onSelect = { AppContainer.repository.selectAccount(it.id) },
            onDelete = { confirmDeleteAccount(it) },
            onSkin = { showSkinDialog(it) }
        )
        binding.recyclerAccounts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerAccounts.adapter = accountsAdapter

        binding.buttonAddAccount.setOnClickListener { showAddChooser() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppContainer.repository.accounts.collect { list ->
                    accountsAdapter.submit(list)
                    binding.textEmpty.isVisible = list.isEmpty()
                }
            }
        }
    }

    private fun confirmDeleteAccount(account: LauncherAccount) {
        val message = if (account.type == AccountType.OFFLINE) {
            getString(R.string.accounts_delete_offline_warning, account.name)
        } else {
            getString(R.string.accounts_delete_confirm, account.name)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accounts_delete_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.accounts_delete_action) { _, _ ->
                AppContainer.repository.removeAccount(account.id)
            }
            .show()
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
        val dialogBinding = DialogOfflineSkinBinding.inflate(layoutInflater)
        skinDialogBinding = dialogBinding
        skinDialogAccount = account
        pendingCustomUri = null
        fetchedPlayer = null
        pendingSkinAccountId = account.id

        when (OfflineSkinService.effectiveMode(account)) {
            OfflineSkinMode.STEVE -> dialogBinding.radioSkinSteve.isChecked = true
            OfflineSkinMode.ALEX -> dialogBinding.radioSkinAlex.isChecked = true
            OfflineSkinMode.PLAYER -> dialogBinding.radioSkinPlayer.isChecked = true
            OfflineSkinMode.CUSTOM -> dialogBinding.radioSkinCustom.isChecked = true
            OfflineSkinMode.RANDOM -> dialogBinding.radioSkinRandom.isChecked = true
        }
        if (OfflineSkinService.isSlimModel(account.skinModel)) {
            dialogBinding.radioModelSlim.isChecked = true
        } else {
            dialogBinding.radioModelClassic.isChecked = true
        }
        dialogBinding.inputSkinPlayerName.setText(account.skinPlayerName.orEmpty())
        val existing = account.skinPath?.takeIf { File(it).isFile }
            ?: OfflineSkinStore.skinFile(account.id).takeIf { it.isFile }?.absolutePath
        dialogBinding.textCustomSkinPath.text = existing
            ?: getString(R.string.accounts_skin_custom_empty)

        fun refreshPanels() {
            val mode = selectedMode(dialogBinding)
            dialogBinding.panelSkinPlayer.isVisible = mode == OfflineSkinMode.PLAYER
            dialogBinding.panelSkinCustom.isVisible = mode == OfflineSkinMode.CUSTOM
            updateSkinPreview(dialogBinding)
        }

        dialogBinding.groupSkinMode.setOnCheckedChangeListener { _, _ -> refreshPanels() }
        dialogBinding.groupSkinModel.setOnCheckedChangeListener { _, _ ->
            if (selectedMode(dialogBinding) == OfflineSkinMode.CUSTOM) {
                updateSkinPreview(dialogBinding)
            }
        }
        dialogBinding.buttonFetchPlayerSkin.setOnClickListener {
            fetchPlayerSkin(dialogBinding)
        }
        dialogBinding.buttonPickCustomSkin.setOnClickListener {
            pickSkin.launch("image/*")
        }
        refreshPanels()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accounts_skin_title)
            .setView(dialogBinding.root)
            .setNeutralButton(R.string.accounts_skin_reset) { _, _ ->
                resetSkin(account)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener {
                            if (saveSkinSelection(account, dialogBinding)) {
                                dialog.dismiss()
                            }
                        }
                }
                dialog.setOnDismissListener {
                    if (skinDialogBinding === dialogBinding) {
                        skinDialogBinding = null
                        skinDialogAccount = null
                        pendingCustomUri = null
                        fetchedPlayer = null
                        pendingSkinAccountId = null
                    }
                }
                dialog.show()
            }
    }

    private fun selectedMode(b: DialogOfflineSkinBinding): OfflineSkinMode = when {
        b.radioSkinSteve.isChecked -> OfflineSkinMode.STEVE
        b.radioSkinAlex.isChecked -> OfflineSkinMode.ALEX
        b.radioSkinPlayer.isChecked -> OfflineSkinMode.PLAYER
        b.radioSkinCustom.isChecked -> OfflineSkinMode.CUSTOM
        else -> OfflineSkinMode.RANDOM
    }

    private fun updateSkinPreview(b: DialogOfflineSkinBinding) {
        val account = skinDialogAccount ?: return
        when (selectedMode(b)) {
            OfflineSkinMode.STEVE -> {
                b.imageSkinPreview.setImageResource(R.drawable.ic_avatar_placeholder)
                b.textSkinPreviewCaption.text = "Steve · Classic"
            }
            OfflineSkinMode.ALEX -> {
                b.imageSkinPreview.setImageResource(R.drawable.ic_avatar_placeholder)
                b.textSkinPreviewCaption.text = "Alex · Slim"
            }
            OfflineSkinMode.PLAYER -> {
                val profile = fetchedPlayer
                val cached = account.skinPath?.takeIf { File(it).isFile }
                    ?: OfflineSkinStore.skinFile(account.id).takeIf { it.isFile }?.absolutePath
                when {
                    profile != null -> {
                        val tmp = File(requireContext().cacheDir, "skin-preview-${account.id}.png")
                        tmp.writeBytes(profile.skinBytes)
                        applyHead(b, tmp.absolutePath)
                        b.textSkinPreviewCaption.text = "${profile.playerName} · ${profile.model}"
                    }
                    cached != null &&
                        account.skinPlayerName.equals(
                            b.inputSkinPlayerName.text?.toString()?.trim(),
                            ignoreCase = true
                        ) -> {
                        applyHead(b, cached)
                        b.textSkinPreviewCaption.text =
                            "${account.skinPlayerName} · 已缓存"
                    }
                    else -> {
                        b.imageSkinPreview.setImageResource(R.drawable.ic_avatar_placeholder)
                        b.textSkinPreviewCaption.text = "请获取玩家皮肤预览"
                    }
                }
            }
            OfflineSkinMode.CUSTOM -> {
                val path = pendingCustomUri?.let { null }
                    ?: account.skinPath?.takeIf { File(it).isFile }
                    ?: OfflineSkinStore.skinFile(account.id).takeIf { it.isFile }?.absolutePath
                if (pendingCustomUri != null) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val bytes = withContext(Dispatchers.IO) {
                            requireContext().contentResolver.openInputStream(pendingCustomUri!!)
                                ?.use { it.readBytes() }
                        } ?: return@launch
                        val tmp = File(requireContext().cacheDir, "skin-preview-custom.png")
                        withContext(Dispatchers.IO) {
                            tmp.writeBytes(OfflineSkinService.normalizeToPng(bytes))
                        }
                        if (!isAdded) return@launch
                        applyHead(b, tmp.absolutePath)
                    }
                    val model = if (b.radioModelSlim.isChecked) "Slim" else "Classic"
                    b.textSkinPreviewCaption.text = "本地自定义 · $model"
                } else if (path != null) {
                    applyHead(b, path)
                    val model = if (b.radioModelSlim.isChecked) "Slim" else "Classic"
                    b.textSkinPreviewCaption.text = "本地自定义 · $model"
                } else {
                    b.imageSkinPreview.setImageResource(R.drawable.ic_avatar_placeholder)
                    b.textSkinPreviewCaption.text = "请选择本地皮肤"
                }
            }
            OfflineSkinMode.RANDOM -> {
                val uuid = account.uuid
                    ?: com.booxin.launcher.core.launch.OfflineAuth.uuidNoDash(account.name)
                val sel = OfflineSkinService.resolveDefaultSkin(uuid, null)
                b.imageSkinPreview.setImageResource(R.drawable.ic_avatar_placeholder)
                b.textSkinPreviewCaption.text =
                    "随机默认皮肤 · ${sel.name} · ${if (sel.slim) "Slim" else "Classic"}"
            }
        }
    }

    private fun applyHead(b: DialogOfflineSkinBinding, path: String) {
        val head = AccountsAdapter.decodeSkinHead(path)
        if (head != null) {
            b.imageSkinPreview.setImageBitmap(head)
        } else {
            b.imageSkinPreview.setImageResource(R.drawable.ic_avatar_placeholder)
        }
    }

    private fun fetchPlayerSkin(b: DialogOfflineSkinBinding) {
        val name = b.inputSkinPlayerName.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            b.textSkinStatus.text = "请输入正版玩家名。"
            return
        }
        b.buttonFetchPlayerSkin.isEnabled = false
        b.textSkinStatus.setText(R.string.accounts_skin_status_fetching)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { OfflineSkinService.resolvePlayerSkin(name) }
            }
            if (!isAdded) return@launch
            b.buttonFetchPlayerSkin.isEnabled = true
            result.fold(
                onSuccess = { profile ->
                    fetchedPlayer = profile
                    b.inputSkinPlayerName.setText(profile.playerName)
                    if (OfflineSkinService.isSlimModel(profile.model)) {
                        b.radioModelSlim.isChecked = true
                    } else {
                        b.radioModelClassic.isChecked = true
                    }
                    b.textSkinStatus.setText(R.string.accounts_skin_status_fetched)
                    updateSkinPreview(b)
                },
                onFailure = { err ->
                    fetchedPlayer = null
                    b.textSkinStatus.text = err.message ?: "获取失败"
                }
            )
        }
    }

    private fun saveSkinSelection(account: LauncherAccount, b: DialogOfflineSkinBinding): Boolean {
        val mode = selectedMode(b)
        return try {
            when (mode) {
                OfflineSkinMode.RANDOM, OfflineSkinMode.STEVE, OfflineSkinMode.ALEX -> {
                    AppContainer.repository.updateOfflineSkin(
                        accountId = account.id,
                        skinMode = mode.toConfigValue(),
                        skinModel = "classic",
                        skinPlayerName = null,
                        skinPlayerUuid = null,
                        clearSkinFile = true
                    )
                    OfflineSkinStore.clear(account.id, account.name)
                }
                OfflineSkinMode.PLAYER -> {
                    var profile = fetchedPlayer
                    if (profile == null) {
                        val cached = OfflineSkinStore.skinFile(account.id)
                        val canReuse = cached.isFile &&
                            account.skinPlayerName.equals(
                                b.inputSkinPlayerName.text?.toString()?.trim(),
                                ignoreCase = true
                            ) &&
                            !account.skinPlayerUuid.isNullOrBlank()
                        require(canReuse) { getString(R.string.accounts_skin_status_need_player) }
                        profile = OfflineSkinService.PlayerProfile(
                            playerName = account.skinPlayerName!!,
                            uuid = account.skinPlayerUuid!!,
                            model = account.skinModel,
                            skinBytes = cached.readBytes()
                        )
                    }
                    val file = OfflineSkinStore.saveBytes(account.id, profile.skinBytes)
                    AppContainer.repository.updateOfflineSkin(
                        accountId = account.id,
                        skinPath = file.absolutePath,
                        skinMode = mode.toConfigValue(),
                        skinModel = profile.model,
                        skinPlayerName = profile.playerName,
                        skinPlayerUuid = profile.uuid
                    )
                }
                OfflineSkinMode.CUSTOM -> {
                    val uri = pendingCustomUri
                    val existing = account.skinPath?.takeIf { File(it).isFile }
                        ?: OfflineSkinStore.skinFile(account.id).takeIf { it.isFile }?.absolutePath
                    require(uri != null || existing != null) {
                        getString(R.string.accounts_skin_status_need_custom)
                    }
                    val file = if (uri != null) {
                        OfflineSkinStore.importFromUri(requireContext(), account.id, uri).getOrThrow()
                    } else {
                        File(existing!!)
                    }
                    val model = if (b.radioModelSlim.isChecked) "slim" else "classic"
                    AppContainer.repository.updateOfflineSkin(
                        accountId = account.id,
                        skinPath = file.absolutePath,
                        skinMode = mode.toConfigValue(),
                        skinModel = model,
                        skinPlayerName = null,
                        skinPlayerUuid = null
                    )
                }
            }
            Toast.makeText(requireContext(), R.string.accounts_skin_status_saved, Toast.LENGTH_SHORT)
                .show()
            true
        } catch (t: Throwable) {
            b.textSkinStatus.text = t.message ?: getString(R.string.accounts_skin_failed, "unknown")
            false
        }
    }

    private fun resetSkin(account: LauncherAccount) {
        OfflineSkinStore.clear(account.id, account.name)
        AppContainer.repository.updateOfflineSkin(
            accountId = account.id,
            skinMode = OfflineSkinMode.RANDOM.toConfigValue(),
            skinModel = "classic",
            skinPlayerName = null,
            skinPlayerUuid = null,
            clearSkinFile = true
        )
        Toast.makeText(requireContext(), R.string.accounts_skin_cleared, Toast.LENGTH_SHORT).show()
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
