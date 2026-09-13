package com.booxin.launcher.ui.pluginstore

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.net.FileDownloader
import com.booxin.launcher.core.plugin.PluginManager
import com.booxin.launcher.core.pluginstore.PluginComment
import com.booxin.launcher.core.pluginstore.PluginStoreApi
import com.booxin.launcher.core.pluginstore.PluginStorePlatforms
import com.booxin.launcher.core.pluginstore.PluginStoreTypes
import com.booxin.launcher.core.pluginstore.StoreInstallAction
import com.booxin.launcher.core.pluginstore.StoreInstallState
import com.booxin.launcher.core.pluginstore.StorePlugin
import com.booxin.launcher.core.uiplugin.UiPluginFonts
import com.booxin.launcher.core.uiplugin.UiPluginManager
import com.booxin.launcher.core.uiplugin.UiPluginTheme
import com.booxin.launcher.databinding.DialogMyApplicationsBinding
import com.booxin.launcher.databinding.DialogPluginApplyBinding
import com.booxin.launcher.databinding.DialogPluginQqBinding
import com.booxin.launcher.databinding.FragmentPluginStoreBinding
import com.booxin.launcher.ui.controller.ControllerNavBinder
import com.booxin.launcher.ui.plugin.PluginsFragment
import com.booxin.launcher.ui.uiplugin.UiPluginsFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PluginStoreFragment : Fragment() {

    private var _binding: FragmentPluginStoreBinding? = null
    private val binding get() = _binding!!
    private val api = PluginStoreApi()
    private lateinit var storeAdapter: StorePluginAdapter
    private var allCatalog: List<StorePlugin> = emptyList()
    private var filterTypeId: String? = null
    private var searchQuery: String = ""
    private var localAttached = false
    private var uiLocalAttached = false

    private var applyBinding: DialogPluginApplyBinding? = null
    private var applyDialog: AlertDialog? = null
    private var pickedFile: File? = null
    private var detailPlugin: StorePlugin? = null
    private var commentAdapter: PluginCommentAdapter? = null
    private var ratingBusy = false
    private var detailWired = false

    private val pickPluginFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        handlePickedUri(uri)
    }

    private val pickPluginFileFallback = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        handlePickedUri(uri)
    }

    private val pickPluginFileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        handlePickedUri(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPluginStoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        storeAdapter = StorePluginAdapter(::showPluginDetail, ::downloadPlugin)
        binding.recyclerStore.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerStore.adapter = storeAdapter
        ControllerNavBinder.bindRecycler(binding.recyclerStore)

        setupFilterSpinner()
        binding.togglePluginSide.check(R.id.tabStore)
        binding.togglePluginSide.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showSide(checkedId)
        }
        binding.buttonRefreshStore.setOnClickListener { loadCatalog() }
        binding.buttonOpenApply.setOnClickListener { showApplyDialog(keepPickedFile = false) }
        binding.buttonMyApps.setOnClickListener { showMyAppsDialog() }
        binding.inputStoreSearch.doAfterTextChanged {
            searchQuery = it?.toString().orEmpty().trim()
            applyFilter()
        }
        wireDetailUi()

        showSide(R.id.tabStore)
        loadCatalog()
    }

    private fun wireDetailUi() {
        if (detailWired) return
        detailWired = true
        commentAdapter = PluginCommentAdapter(
            onLike = { comment ->
                val s = AppContainer.multiplayerAuth.current()
                val pluginId = detailPlugin?.id
                if (s == null) {
                    toast(getString(R.string.plugin_store_need_login))
                } else if (pluginId != null) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        api.toggleCommentLike(s, pluginId, comment.id)
                            .onSuccess { (liked, count) ->
                                commentAdapter?.update(
                                    comment.copy(likedByMe = liked, likeCount = count)
                                )
                            }
                            .onFailure {
                                toast(it.message ?: getString(R.string.plugin_store_like_failed))
                            }
                    }
                }
            },
            onReport = { comment ->
                val pluginId = detailPlugin?.id
                if (pluginId != null) {
                    reportPluginComment(pluginId, comment) { hidden ->
                        if (hidden) {
                            commentAdapter?.remove(comment.id)
                            val empty = (commentAdapter?.itemCount ?: 0) == 0
                            binding.textCommentsEmpty.isVisible = empty
                            binding.recyclerComments.isVisible = !empty
                        }
                    }
                }
            }
        )
        binding.recyclerComments.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerComments.adapter = commentAdapter

        binding.buttonDetailBack.setOnClickListener { closePluginDetail() }
        binding.buttonPageRating.setOnClickListener { showDetailPage(comments = false) }
        binding.buttonPageComments.setOnClickListener { showDetailPage(comments = true) }
        binding.buttonWriteComment.setOnClickListener {
            if (AppContainer.multiplayerAuth.current() == null) {
                toast(getString(R.string.plugin_store_need_login))
                return@setOnClickListener
            }
            setCommentComposerVisible(true)
        }
        binding.buttonCancelComment.setOnClickListener { setCommentComposerVisible(false) }
        binding.buttonSubmitComment.setOnClickListener { submitDetailComment() }
        binding.ratingBarMine.setOnRatingBarChangeListener { _, rating, fromUser ->
            if (!fromUser || ratingBusy) return@setOnRatingBarChangeListener
            submitDetailRating(rating.toInt())
        }
    }

    private fun setupFilterSpinner() {
        val applyLabels = PluginStoreTypes.labels(requireContext())
        val filterLabels = listOf(getString(R.string.plugin_store_filter_all)) + applyLabels
        binding.spinnerStoreFilter.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            filterLabels
        )
        binding.spinnerStoreFilter.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    filterTypeId = if (position <= 0) null else PluginStoreTypes.idAt(position - 1)
                    applyFilter()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun showSide(checkedId: Int) {
        val store = checkedId == R.id.tabStore
        val uiLocal = checkedId == R.id.tabUiLocal
        val rendererLocal = checkedId == R.id.tabLocal
        if (!store) {
            closePluginDetail(keepSelection = true)
        }
        val showingDetail = store && detailPlugin != null
        binding.panelStore.isVisible = store && !showingDetail
        binding.panelPluginDetail.isVisible = showingDetail
        binding.panelDetailMenu.isVisible = showingDetail
        binding.uiPluginHost.isVisible = uiLocal
        binding.localPluginHost.isVisible = rendererLocal
        when {
            uiLocal -> {
                ensureUiLocalFragment()
                refreshUiLocalFragment()
            }
            rendererLocal -> ensureLocalFragment()
        }
    }

    private fun verticalTypeText(typeLabel: String): String =
        typeLabel.toCharArray().joinToString("\n") { it.toString() }

    private fun closePluginDetail(keepSelection: Boolean = false) {
        detailPlugin = null
        binding.panelPluginDetail.isVisible = false
        binding.panelDetailMenu.isVisible = false
        if (!keepSelection) {
            binding.panelStore.isVisible = true
            binding.togglePluginSide.check(R.id.tabStore)
        }
    }

    private fun showDetailPage(comments: Boolean) {
        binding.panelDetailOverview.isVisible = !comments
        binding.panelDetailComments.isVisible = comments
        binding.buttonPageRating.alpha = if (comments) 0.55f else 1f
        binding.buttonPageComments.alpha = if (comments) 1f else 0.55f
        if (comments) {
            setCommentComposerVisible(false)
            refreshDetailComments()
        }
    }

    private fun setCommentComposerVisible(visible: Boolean) {
        binding.panelCommentComposer.isVisible = visible
        binding.buttonWriteComment.isVisible = !visible
        if (!visible) {
            binding.inputComment.setText("")
            binding.inputComment.clearFocus()
        } else {
            binding.inputComment.requestFocus()
        }
    }

    private fun bindDetailSidebar(p: StorePlugin) {
        binding.textDetailName.text = p.name
        binding.textDetailDeveloper.text = getString(
            R.string.plugin_store_developer_label,
            p.developerUsername.ifBlank { p.developerUserId }.ifBlank { "-" }
        )
        binding.textDetailPlatforms.text = PluginStorePlatforms.label(requireContext(), p.platforms)
        binding.textDetailTypeVertical.text = verticalTypeText(
            PluginStoreTypes.label(requireContext(), p.type)
        )
        binding.textDetailRating.text = if (p.ratingCount > 0) {
            getString(R.string.plugin_store_rating_side, p.ratingAvg, p.ratingCount)
        } else {
            getString(R.string.plugin_store_rating_side_none)
        }
        binding.textDetailDesc.text = p.description.ifBlank {
            getString(R.string.plugin_store_no_desc)
        }
        applyStoreActionButton(binding.buttonDetailDownload, p)
    }

    private fun applyStoreActionButton(
        button: com.google.android.material.button.MaterialButton,
        plugin: StorePlugin
    ) {
        when (StoreInstallState.actionFor(plugin)) {
            StoreInstallAction.DOWNLOAD -> {
                button.isEnabled = true
                button.alpha = 1f
                button.setText(R.string.plugin_store_download)
                button.setOnClickListener { downloadPlugin(plugin) }
            }
            StoreInstallAction.INSTALLED -> {
                button.isEnabled = false
                button.alpha = 0.55f
                button.setText(R.string.plugin_store_downloaded)
                button.setOnClickListener(null)
            }
            StoreInstallAction.UPDATE -> {
                button.isEnabled = true
                button.alpha = 1f
                button.setText(R.string.plugin_store_update)
                button.setOnClickListener { downloadPlugin(plugin) }
            }
        }
    }

    private fun refreshStoreActionUi() {
        if (::storeAdapter.isInitialized) {
            storeAdapter.notifyDataSetChanged()
        }
        detailPlugin?.let { applyStoreActionButton(binding.buttonDetailDownload, it) }
    }

    private fun refreshDetailComments() {
        val plugin = detailPlugin ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            api.listComments(plugin.id, session = AppContainer.multiplayerAuth.current())
                .onSuccess { list ->
                    commentAdapter?.submit(list)
                    binding.textCommentsEmpty.isVisible = list.isEmpty()
                    binding.recyclerComments.isVisible = list.isNotEmpty()
                }
                .onFailure {
                    toast(it.message ?: getString(R.string.plugin_store_load_failed))
                }
        }
    }

    private fun submitDetailRating(score: Int) {
        if (score !in 1..5) return
        val plugin = detailPlugin ?: return
        val session = AppContainer.multiplayerAuth.current() ?: run {
            toast(getString(R.string.plugin_store_need_login))
            binding.ratingBarMine.rating = 0f
            return
        }
        ratingBusy = true
        viewLifecycleOwner.lifecycleScope.launch {
            api.submitRating(session, plugin.id, score)
                .onSuccess { result ->
                    val updated = plugin.copy(
                        ratingAvg = result.ratingAvg,
                        ratingCount = result.ratingCount
                    )
                    detailPlugin = updated
                    bindDetailSidebar(updated)
                    allCatalog = allCatalog.map {
                        if (it.id == plugin.id) {
                            it.copy(ratingAvg = result.ratingAvg, ratingCount = result.ratingCount)
                        } else it
                    }
                    applyFilter()
                    toast(getString(R.string.plugin_store_rate_ok, result.score))
                }
                .onFailure {
                    toast(it.message ?: getString(R.string.plugin_store_rate_failed))
                }
            ratingBusy = false
        }
    }

    private fun submitDetailComment() {
        val plugin = detailPlugin ?: return
        val session = AppContainer.multiplayerAuth.current() ?: run {
            toast(getString(R.string.plugin_store_need_login))
            return
        }
        val text = binding.inputComment.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            toast(getString(R.string.plugin_store_comment_empty))
            return
        }
        binding.buttonSubmitComment.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            api.submitComment(session, plugin.id, text)
                .onSuccess {
                    binding.inputComment.setText("")
                    setCommentComposerVisible(false)
                    detailPlugin = plugin.copy(commentCount = plugin.commentCount + 1)
                    allCatalog = allCatalog.map {
                        if (it.id == plugin.id) it.copy(commentCount = plugin.commentCount + 1)
                        else it
                    }
                    applyFilter()
                    refreshDetailComments()
                    toast(getString(R.string.plugin_store_comment_ok))
                }
                .onFailure {
                    toast(it.message ?: getString(R.string.plugin_store_comment_failed))
                }
            binding.buttonSubmitComment.isEnabled = true
        }
    }

    private fun ensureLocalFragment() {
        if (localAttached) return
        childFragmentManager.beginTransaction()
            .replace(R.id.localPluginHost, PluginsFragment())
            .commitNowAllowingStateLoss()
        localAttached = true
    }

    private fun ensureUiLocalFragment() {
        if (uiLocalAttached) return
        childFragmentManager.beginTransaction()
            .replace(R.id.uiPluginHost, UiPluginsFragment(), TAG_UI_LOCAL)
            .commitNowAllowingStateLoss()
        uiLocalAttached = true
    }

    private fun refreshUiLocalFragment() {
        (childFragmentManager.findFragmentByTag(TAG_UI_LOCAL) as? UiPluginsFragment)?.refresh()
    }

    private fun loadCatalog() {
        viewLifecycleOwner.lifecycleScope.launch {
            api.listPlugins()
                .onSuccess {
                    allCatalog = it
                    applyFilter()
                }
                .onFailure {
                    toast(it.message ?: getString(R.string.plugin_store_load_failed))
                }
        }
    }

    private fun applyFilter() {
        var list = allCatalog.filter {
            PluginStorePlatforms.supportsAndroid(it.platforms)
        }
        filterTypeId?.let { id ->
            list = list.filter { it.type.equals(id, ignoreCase = true) }
        }
        if (searchQuery.isNotEmpty()) {
            val q = searchQuery.lowercase()
            list = list.filter {
                it.name.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.developerUsername.lowercase().contains(q) ||
                    it.type.lowercase().contains(q)
            }
        }
        storeAdapter.submit(list)
        binding.textStoreEmpty.isVisible = list.isEmpty()
        binding.recyclerStore.isVisible = list.isNotEmpty()
        if (list.isNotEmpty()) {
            binding.storeAppBar.setExpanded(true, false)
        }
    }

    private fun handlePickedUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyUriToCache(uri) }
            if (file == null) {
                toast(getString(R.string.plugin_store_file_read_failed))
                return@launch
            }
            if (file.length() > PluginStoreApi.MAX_UPLOAD_BYTES) {
                file.delete()
                showOversizeDialog()
                return@launch
            }
            pickedFile = file
            val label = getString(
                R.string.plugin_store_picked_file,
                file.name,
                file.length() / 1024
            )
            // 部分机型打开文件器会关掉弹窗，选完后自动重新打开并带上文件
            if (applyDialog?.isShowing != true || applyBinding == null) {
                showApplyDialog(keepPickedFile = true)
            } else {
                applyBinding?.textPickedFile?.text = label
            }
            toast(label)
        }
    }

    private fun launchFilePicker() {
        try {
            pickPluginFile.launch(
                arrayOf(
                    "*/*",
                    "application/vnd.android.package-archive",
                    "application/zip",
                    "application/octet-stream",
                    "application/x-zip-compressed"
                )
            )
            return
        } catch (_: Exception) {
            // fall through
        }
        try {
            pickPluginFileFallback.launch("*/*")
            return
        } catch (_: Exception) {
            // fall through
        }
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("*/*", "application/zip", "application/vnd.android.package-archive")
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            pickPluginFileChooser.launch(
                Intent.createChooser(intent, getString(R.string.plugin_store_pick_file))
            )
        }.onFailure {
            toast(getString(R.string.plugin_store_pick_failed))
        }
    }

    private fun showApplyStep(binding: DialogPluginApplyBinding, step: Int) {
        val step1 = step <= 1
        binding.panelApplyStep1.isVisible = step1
        binding.panelApplyStep2.isVisible = !step1
        binding.buttonApplyNext.isVisible = step1
        binding.buttonApplyBack.isVisible = !step1
        binding.buttonSubmitApply.isVisible = !step1
        binding.textApplyStep.setText(
            if (step1) R.string.plugin_store_apply_step1 else R.string.plugin_store_apply_step2
        )
        binding.textApplyLoginHint.isVisible = step1
    }

    private fun showApplyDialog(keepPickedFile: Boolean = false) {
        if (!keepPickedFile) {
            pickedFile = null
        }
        applyDialog?.dismiss()
        val dialogBinding = DialogPluginApplyBinding.inflate(layoutInflater)
        applyBinding = dialogBinding
        dialogBinding.spinnerApplyType.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            PluginStoreTypes.labels(requireContext())
        )
        val uiIndex = PluginStoreTypes.ALL.indexOfFirst { it.id == "ui" }.coerceAtLeast(0)
        dialogBinding.spinnerApplyType.setSelection(uiIndex)

        val loggedIn = AppContainer.multiplayerAuth.current() != null
        dialogBinding.textApplyLoginHint.text = getString(
            if (loggedIn) R.string.plugin_store_apply_hint else R.string.plugin_store_need_login
        )
        dialogBinding.buttonSubmitApply.isEnabled = loggedIn
        dialogBinding.buttonApplyNext.isEnabled = true
        dialogBinding.buttonPickFile.isEnabled = true
        dialogBinding.buttonPickFile.setOnClickListener { launchFilePicker() }
        pickedFile?.let { file ->
            dialogBinding.textPickedFile.text = getString(
                R.string.plugin_store_picked_file,
                file.name,
                file.length() / 1024
            )
        }
        showApplyStep(dialogBinding, if (keepPickedFile && pickedFile != null) 2 else 1)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()
        applyDialog = dialog
        dialogBinding.buttonApplyCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.buttonApplyNext.setOnClickListener {
            val name = dialogBinding.inputPluginName.text?.toString().orEmpty().trim()
            if (name.isEmpty()) {
                toast(getString(R.string.plugin_store_apply_need_name))
                return@setOnClickListener
            }
            if (!dialogBinding.checkPlatformAndroid.isChecked &&
                !dialogBinding.checkPlatformDesktop.isChecked
            ) {
                toast(getString(R.string.plugin_store_platforms_need_one))
                return@setOnClickListener
            }
            if (!loggedIn) {
                toast(getString(R.string.plugin_store_need_login))
                return@setOnClickListener
            }
            showApplyStep(dialogBinding, 2)
        }
        dialogBinding.buttonApplyBack.setOnClickListener {
            showApplyStep(dialogBinding, 1)
        }
        dialogBinding.buttonSubmitApply.setOnClickListener {
            submitApply(dialogBinding) { dialog.dismiss() }
        }
        dialog.setOnDismissListener {
            if (applyDialog === dialog) {
                applyDialog = null
                applyBinding = null
            }
        }
        dialog.show()
        val dm = resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.9f).toInt(),
            (dm.heightPixels * 0.82f).toInt()
        )
    }

    private fun submitApply(dialogBinding: DialogPluginApplyBinding, onDone: () -> Unit) {
        val session = AppContainer.multiplayerAuth.current() ?: run {
            toast(getString(R.string.plugin_store_need_login))
            return
        }
        val name = dialogBinding.inputPluginName.text?.toString().orEmpty().trim()
        val url = dialogBinding.inputPluginUrl.text?.toString().orEmpty().trim()
        val desc = dialogBinding.inputPluginDesc.text?.toString().orEmpty().trim()
        val type = PluginStoreTypes.idAt(dialogBinding.spinnerApplyType.selectedItemPosition)
        val platforms = buildList {
            if (dialogBinding.checkPlatformAndroid.isChecked) {
                add(PluginStorePlatforms.ANDROID)
            }
            if (dialogBinding.checkPlatformDesktop.isChecked) {
                add(PluginStorePlatforms.DESKTOP)
            }
        }
        val file = pickedFile
        if (name.isEmpty()) {
            toast(getString(R.string.plugin_store_form_incomplete))
            return
        }
        if (platforms.isEmpty()) {
            toast(getString(R.string.plugin_store_platforms_need_one))
            return
        }
        if (file == null && url.isEmpty()) {
            toast(getString(R.string.plugin_store_need_file_or_url))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            dialogBinding.buttonSubmitApply.isEnabled = false
            val result = if (file != null) {
                if (file.length() > PluginStoreApi.MAX_UPLOAD_BYTES) {
                    showOversizeDialog()
                    dialogBinding.buttonSubmitApply.isEnabled = true
                    return@launch
                }
                api.submitApplicationWithFile(session, name, desc, type, file, platforms)
            } else {
                api.submitApplication(session, name, url, desc, type, platforms)
            }
            dialogBinding.buttonSubmitApply.isEnabled = true
            result.onSuccess {
                toast(getString(R.string.plugin_store_submit_ok))
                onDone()
            }.onFailure {
                val msg = it.message.orEmpty()
                if (msg.contains("250") || msg.contains("OVERSIZE", true) || msg.contains("QQ")) {
                    showOversizeDialog()
                } else {
                    toast(msg.ifBlank { getString(R.string.plugin_store_submit_failed) })
                }
            }
        }
    }

    private fun showMyAppsDialog() {
        val session = AppContainer.multiplayerAuth.current() ?: run {
            toast(getString(R.string.plugin_store_need_login))
            return
        }
        val dialogBinding = DialogMyApplicationsBinding.inflate(layoutInflater)
        val adapter = PluginApplicationAdapter()
        dialogBinding.recyclerApplications.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.recyclerApplications.adapter = adapter
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        viewLifecycleOwner.lifecycleScope.launch {
            api.myApplications(session)
                .onSuccess { adapter.submit(it) }
                .onFailure { toast(it.message ?: getString(R.string.plugin_store_load_failed)) }
        }
    }

    private fun showOversizeDialog() {
        val dialogBinding = DialogPluginQqBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copyUriToCache(uri: Uri): File? {
        return runCatching {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "plugin.bin"
            val dest = File(requireContext().cacheDir, "upload-${System.currentTimeMillis()}-$name")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            dest
        }.getOrNull()
    }

    private fun showPluginDetail(plugin: StorePlugin) {
        detailPlugin = plugin
        binding.togglePluginSide.check(R.id.tabStore)
        binding.panelStore.isVisible = false
        binding.panelPluginDetail.isVisible = true
        binding.panelDetailMenu.isVisible = true
        binding.uiPluginHost.isVisible = false
        binding.localPluginHost.isVisible = false
        bindDetailSidebar(plugin)
        showDetailPage(comments = false)

        val session = AppContainer.multiplayerAuth.current()
        val loggedIn = session != null
        binding.ratingBarMine.isEnabled = loggedIn
        binding.buttonWriteComment.isEnabled = loggedIn
        binding.buttonSubmitComment.isEnabled = loggedIn
        binding.inputComment.isEnabled = loggedIn
        binding.textRateHint.isVisible = !loggedIn
        ratingBusy = true
        binding.ratingBarMine.rating = 0f
        ratingBusy = false
        setCommentComposerVisible(false)

        viewLifecycleOwner.lifecycleScope.launch {
            if (loggedIn && session != null) {
                api.myRating(session, plugin.id)
                    .onSuccess { score ->
                        if (score != null && score in 1..5) {
                            ratingBusy = true
                            binding.ratingBarMine.rating = score.toFloat()
                            ratingBusy = false
                        }
                    }
            }
            api.getPlugin(plugin.id)
                .onSuccess {
                    detailPlugin = it
                    bindDetailSidebar(it)
                }
        }
    }

    private fun reportPluginComment(
        pluginId: String,
        comment: PluginComment,
        onHidden: (Boolean) -> Unit
    ) {
        val session = AppContainer.multiplayerAuth.current() ?: run {
            toast(getString(R.string.plugin_store_need_login))
            return
        }
        val myId = session.user.id
        if (myId.isNotBlank() && myId == comment.userId) {
            toast(getString(R.string.plugin_store_report_own))
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.plugin_store_report_title)
            .setMessage(R.string.plugin_store_report_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plugin_store_report) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    api.reportComment(session, pluginId, comment.id)
                        .onSuccess { result ->
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.plugin_store_report_result)
                                .setMessage(result.dialogText())
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                            val hidden =
                                result.reportValid == true && result.actionLevel in 2..3
                            onHidden(hidden)
                        }
                        .onFailure {
                            toast(it.message ?: getString(R.string.plugin_store_report_failed))
                        }
                }
            }
            .show()
    }

    private fun downloadPlugin(plugin: StorePlugin) {
        val url = plugin.downloadUrl.trim()
        if (url.isEmpty()) {
            toast(getString(R.string.plugin_store_no_url))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            toast(
                getString(
                    if (StoreInstallState.actionFor(plugin) == StoreInstallAction.UPDATE) {
                        R.string.ui_plugin_updating
                    } else {
                        R.string.plugin_store_downloading
                    },
                    plugin.name
                )
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(requireContext().cacheDir, "store-${plugin.id}.bin")
                    // Short/CDN links often break multipart Range; always single-stream.
                    // OkHttp followRedirects=true → finalUrl is after redirects.
                    val downloaded = FileDownloader()
                        .downloadResolved(url, dest, accelerate = false)
                        .getOrThrow()
                    when (
                        resolveStorePackageKind(
                            plugin = plugin,
                            requestUrl = url,
                            finalUrl = downloaded.finalUrl,
                            contentType = downloaded.contentType,
                            contentDisposition = downloaded.contentDisposition,
                            file = downloaded.file
                        )
                    ) {
                        StorePackageKind.RENDERER_APK -> {
                            val installed = PluginManager.installFromApk(
                                requireContext(),
                                downloaded.file,
                                preferredName = plugin.name
                            ).getOrThrow()
                            StoreInstallOutcome.Renderer(installed.name)
                        }
                        StorePackageKind.UI_ZIP -> {
                            val installed = UiPluginManager.installFromZip(downloaded.file).getOrThrow()
                            UiPluginManager.writeStoreMeta(
                                pluginId = installed.manifest.id,
                                storePluginId = plugin.id,
                                storeVersion = plugin.version.ifBlank { installed.manifest.version }
                            )
                            StoreInstallOutcome.Ui(installed.manifest.name)
                        }
                    }
                }
            }
            result.onSuccess { outcome ->
                when (outcome) {
                    is StoreInstallOutcome.Renderer -> {
                        toast(getString(R.string.plugins_install_done, outcome.name))
                    }
                    is StoreInstallOutcome.Ui -> {
                        toast(getString(R.string.ui_plugin_installed, outcome.name))
                        UiPluginFonts.applyTo(activity)
                        UiPluginTheme.applyTo(activity)
                        refreshUiLocalFragment()
                    }
                }
                refreshStoreActionUi()
            }.onFailure {
                toast(
                    getString(
                        R.string.plugins_install_failed,
                        it.message ?: "unknown"
                    )
                )
            }
        }
    }

    private enum class StorePackageKind { UI_ZIP, RENDERER_APK }

    private sealed class StoreInstallOutcome {
        data class Ui(val name: String) : StoreInstallOutcome()
        data class Renderer(val name: String) : StoreInstallOutcome()
    }

    /**
     * Decide zip UI vs renderer APK using store type, redirected URL, headers, then file peek.
     */
    private fun resolveStorePackageKind(
        plugin: StorePlugin,
        requestUrl: String,
        finalUrl: String,
        contentType: String?,
        contentDisposition: String?,
        file: File
    ): StorePackageKind {
        val type = plugin.type.trim().lowercase()
        if (type == "renderer" || type == "driver") return StorePackageKind.RENDERER_APK
        val uiHint = type in setOf("ui", "control", "overlay", "utility", "input", "pack", "other")

        val nameHints = buildList {
            add(requestUrl)
            add(finalUrl)
            contentDisposition?.let { add(it) }
        }.joinToString("\n").lowercase()

        val pathHint = sequenceOf(requestUrl, finalUrl)
            .map { it.substringBefore('#').substringBefore('?').lowercase() }
            .toList()

        if (pathHint.any { it.endsWith(".apk") } || nameHints.contains(".apk")) {
            return StorePackageKind.RENDERER_APK
        }
        if (pathHint.any { it.endsWith(".zip") } || nameHints.contains(".zip")) {
            return if (zipLooksLikeApk(file)) StorePackageKind.RENDERER_APK
            else StorePackageKind.UI_ZIP
        }

        val ct = contentType?.lowercase().orEmpty()
        if ("android.package" in ct || ct == "application/vnd.android.package-archive") {
            return StorePackageKind.RENDERER_APK
        }

        if (zipContainsEntry(file, "booxin-plugin.json")) return StorePackageKind.UI_ZIP
        if (zipLooksLikeApk(file)) return StorePackageKind.RENDERER_APK
        if (uiHint) return StorePackageKind.UI_ZIP
        // Marketplace default: treat unknown packages as UI zip.
        return StorePackageKind.UI_ZIP
    }

    private fun zipLooksLikeApk(file: File): Boolean =
        zipContainsEntry(file, "AndroidManifest.xml") ||
            zipContainsEntry(file, "classes.dex") ||
            zipContainsEntry(file, "resources.arsc")

    private fun zipContainsEntry(file: File, suffix: String): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return runCatching {
            java.util.zip.ZipFile(file).use { zf ->
                zf.entries().asSequence().any { e ->
                    !e.isDirectory && e.name.replace('\\', '/').endsWith(suffix, ignoreCase = true)
                }
            }
        }.getOrDefault(false)
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        applyDialog?.dismiss()
        applyDialog = null
        applyBinding = null
        detailPlugin = null
        commentAdapter = null
        detailWired = false
        localAttached = false
        uiLocalAttached = false
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG_UI_LOCAL = "ui_plugins_local"
    }
}
