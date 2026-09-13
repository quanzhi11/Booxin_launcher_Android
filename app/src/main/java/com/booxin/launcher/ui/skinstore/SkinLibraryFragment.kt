package com.booxin.launcher.ui.skinstore

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.skinstore.SkinApplyHelper
import com.booxin.launcher.core.skinstore.SkinComment
import com.booxin.launcher.core.skinstore.SkinStoreApi
import com.booxin.launcher.core.skinstore.StoreSkin
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.databinding.FragmentSkinLibraryBinding
import com.booxin.launcher.ui.home.Home3dBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SkinLibraryFragment : Fragment() {

    private var _binding: FragmentSkinLibraryBinding? = null
    private val binding get() = _binding!!

    private val api = SkinStoreApi()
    private var listAdapter: SkinStoreAdapter? = null
    private var commentAdapter: SkinCommentAdapter? = null
    private var detailSkin: StoreSkin? = null
    private var ratingBusy = false
    private var pendingUploadFile: File? = null
    private var currentQuery: String = ""
    private var currentModelFilter: String? = null
    private var allSkins: List<StoreSkin> = emptyList()
    private var pageIndex: Int = 0

    private val pickSkinLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext null
                val tmp = File(requireContext().cacheDir, "skin-upload-${System.currentTimeMillis()}.png")
                tmp.writeBytes(bytes)
                tmp
            }
            if (file == null) {
                toast(getString(R.string.skin_store_file_read_failed))
                return@launch
            }
            pendingUploadFile = file
            binding.textSkinPickedFile.text = getString(
                R.string.skin_store_picked_file,
                file.name,
                file.length() / 1024
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSkinLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listAdapter = SkinStoreAdapter(viewLifecycleOwner.lifecycleScope, ::openDetail)
        binding.recyclerSkins.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSkins.adapter = listAdapter

        commentAdapter = SkinCommentAdapter(
            onLike = { comment ->
                val skinId = detailSkin?.id ?: return@SkinCommentAdapter
                val session = AppContainer.multiplayerAuth.current() ?: run {
                    toast(getString(R.string.skin_store_need_login))
                    return@SkinCommentAdapter
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    api.toggleCommentLike(session, skinId, comment.id).onSuccess { (liked, count) ->
                        commentAdapter?.update(comment.copy(likedByMe = liked, likeCount = count))
                    }.onFailure {
                        toast(it.message ?: getString(R.string.plugin_store_like_failed))
                    }
                }
            },
            onReport = { comment ->
                val skinId = detailSkin?.id ?: return@SkinCommentAdapter
                reportComment(skinId, comment)
            }
        )
        binding.recyclerSkinComments.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSkinComments.adapter = commentAdapter

        binding.buttonSkinDetailBack.setOnClickListener { showCatalog() }
        binding.buttonSkinUploadBack.setOnClickListener { showCatalog() }
        binding.buttonWriteSkinComment.setOnClickListener {
            if (AppContainer.multiplayerAuth.current() == null) {
                toast(getString(R.string.skin_store_need_login))
                return@setOnClickListener
            }
            binding.panelSkinCommentComposer.isVisible = true
        }
        binding.buttonCancelSkinComment.setOnClickListener {
            binding.panelSkinCommentComposer.isVisible = false
            binding.inputSkinComment.setText("")
        }
        binding.buttonSubmitSkinComment.setOnClickListener { submitComment() }
        binding.buttonSkinApply.setOnClickListener { confirmApply() }
        binding.buttonSkinReport.setOnClickListener { reportSkin() }
        binding.buttonPickSkinFile.setOnClickListener {
            runCatching { pickSkinLauncher.launch("image/png") }
                .onFailure { toast(getString(R.string.skin_store_pick_failed)) }
        }
        binding.buttonSubmitSkinUpload.setOnClickListener { submitUpload() }
        binding.toggleSkinOrigin.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val reprint = checkedId == R.id.buttonOriginReprint
            binding.layoutSkinSource.isVisible = reprint
            applyAllowReprintLock(reprint)
        }
        binding.ratingBarSkinMine.setOnRatingBarChangeListener { _, rating, fromUser ->
            if (!fromUser || ratingBusy) return@setOnRatingBarChangeListener
            val score = rating.toInt().coerceIn(0, 5)
            if (score < 1) return@setOnRatingBarChangeListener
            submitRating(score)
        }

        showCatalog()
        // First load is driven by CommunityFragment (search / model filter).
    }

    fun search(query: String) {
        currentQuery = query.trim()
        pageIndex = 0
        if (_binding == null) return
        if (binding.panelSkinCatalog.isVisible) refreshList()
    }

    fun setModelFilter(model: String?) {
        if (currentModelFilter == model) {
            if (_binding != null && binding.panelSkinCatalog.isVisible && allSkins.isEmpty()) {
                refreshList()
            }
            return
        }
        currentModelFilter = model
        pageIndex = 0
        if (_binding == null) return
        if (binding.panelSkinCatalog.isVisible) refreshList()
    }

    /** Apply query + model in one request (used when entering skin tab). */
    fun applyCatalogFilters(query: String, model: String?) {
        currentQuery = query.trim()
        currentModelFilter = model
        pageIndex = 0
        if (_binding == null) return
        if (binding.panelSkinCatalog.isVisible) refreshList()
    }

    fun refresh() {
        pageIndex = 0
        refreshList()
    }

    fun openUpload() {
        showUpload()
    }

    fun prevPage() {
        if (pageIndex <= 0) return
        pageIndex--
        renderPage()
    }

    fun nextPage() {
        if (pageIndex + 1 >= totalPages()) return
        pageIndex++
        renderPage()
    }

    fun refreshList() {
        val b = _binding ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            api.listSkins(
                query = currentQuery.ifBlank { null },
                model = currentModelFilter
            ).onSuccess { list ->
                allSkins = list
                if (pageIndex >= totalPages() && pageIndex > 0) {
                    pageIndex = (totalPages() - 1).coerceAtLeast(0)
                }
                renderPage()
            }.onFailure {
                allSkins = emptyList()
                pageIndex = 0
                listAdapter?.submit(emptyList())
                b.textSkinEmpty.isVisible = true
                notifyParentPagination()
                toast(it.message ?: getString(R.string.skin_store_load_failed))
            }
        }
    }

    private fun totalPages(): Int {
        if (allSkins.isEmpty()) return 0
        return (allSkins.size + PAGE_SIZE - 1) / PAGE_SIZE
    }

    private fun renderPage() {
        val b = _binding ?: return
        val pages = totalPages()
        if (pages == 0) {
            pageIndex = 0
            listAdapter?.submit(emptyList())
            b.textSkinEmpty.isVisible = true
            notifyParentPagination()
            return
        }
        pageIndex = pageIndex.coerceIn(0, pages - 1)
        val from = pageIndex * PAGE_SIZE
        val pageItems = allSkins.subList(from, minOf(from + PAGE_SIZE, allSkins.size))
        listAdapter?.submit(pageItems)
        b.textSkinEmpty.isVisible = false
        b.recyclerSkins.scrollToPosition(0)
        notifyParentPagination()
    }

    private fun notifyParentPagination() {
        val pages = totalPages()
        val total = allSkins.size
        (parentFragment as? com.booxin.launcher.ui.community.CommunityFragment)
            ?.onSkinPageChanged(
                pageNumber = if (pages == 0) 0 else pageIndex + 1,
                totalPages = pages,
                totalHits = total,
                hasPrevious = pageIndex > 0,
                hasNext = pageIndex + 1 < pages
            )
    }

    private fun showCatalog() {
        val b = _binding ?: return
        b.panelSkinCatalog.isVisible = true
        b.panelSkinDetail.isVisible = false
        b.panelSkinUpload.isVisible = false
        detailSkin = null
        notifyParentChrome(overlay = false)
        notifyParentPagination()
    }

    private fun showUpload() {
        val b = _binding ?: return
        if (AppContainer.multiplayerAuth.current() == null) {
            toast(getString(R.string.skin_store_need_login))
            return
        }
        b.panelSkinCatalog.isVisible = false
        b.panelSkinDetail.isVisible = false
        b.panelSkinUpload.isVisible = true
        pendingUploadFile = null
        b.inputSkinName.setText("")
        b.inputSkinDesc.setText("")
        b.inputSkinSource.setText("")
        b.textSkinPickedFile.setText(R.string.skin_store_no_file)
        b.toggleSkinModel.check(R.id.buttonModelClassic)
        b.toggleSkinOrigin.check(R.id.buttonOriginOriginal)
        b.layoutSkinSource.isVisible = false
        applyAllowReprintLock(reprint = false)
        notifyParentChrome(overlay = true)
    }

    /** 转载作品不可再授权他人转载：开关强制关闭并锁定。 */
    private fun applyAllowReprintLock(reprint: Boolean) {
        val sw = _binding?.switchSkinAllowReprint ?: return
        if (reprint) {
            sw.isChecked = false
            sw.isEnabled = false
        } else {
            sw.isEnabled = true
            if (!sw.isChecked) sw.isChecked = true
        }
    }

    private fun openDetail(skin: StoreSkin) {
        val b = _binding ?: return
        detailSkin = skin
        b.panelSkinCatalog.isVisible = false
        b.panelSkinUpload.isVisible = false
        b.panelSkinDetail.isVisible = true
        notifyParentChrome(overlay = true)
        b.textSkinDetailTitle.text = skin.name
        b.textSkinDetailMeta.text = getString(
            R.string.skin_store_meta,
            skin.authorUsername.ifBlank { "-" },
            skin.model,
            skin.version
        )
        val originBadge = skin.originBadgeText()
        b.textSkinDetailOrigin.isVisible = !originBadge.isNullOrBlank()
        b.textSkinDetailOrigin.text = originBadge
        b.textSkinDetailReprintBadge.isVisible = skin.isReprint()
        val showSource = skin.isReprint() && skin.source.isNotBlank()
        b.textSkinDetailSource.isVisible = showSource
        if (showSource) {
            b.textSkinDetailSource.text = getString(R.string.skin_store_source_label, skin.source)
        }
        b.textSkinDetailDesc.text = skin.description.ifBlank { getString(R.string.skin_store_no_desc) }
        b.textSkinDetailRating.text = if (skin.ratingCount > 0) {
            getString(R.string.skin_store_rating_side, skin.ratingAvg, skin.ratingCount)
        } else {
            getString(R.string.skin_store_rating_side_none)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val skinFile = withContext(Dispatchers.IO) {
                val bytes = api.downloadTextureBytes(skin.textureUrl).getOrNull() ?: return@withContext null
                // Must live under cache/home3d for Home3dBinder asset path handler.
                val dir = File(requireContext().cacheDir, "home3d").also { it.mkdirs() }
                val out = File(dir, "preview-${skin.id}.png")
                out.writeBytes(bytes)
                out
            }
            if (_binding != null && detailSkin?.id == skin.id) {
                Home3dBinder.bindForced(
                    binding.webSkinPreview3d,
                    skinFile,
                    forceReload = true
                )
            }
        }
        bindAuthControls()
        showDetailPage()
    }

    private fun notifyParentChrome(overlay: Boolean) {
        (parentFragment as? com.booxin.launcher.ui.community.CommunityFragment)
            ?.setSkinSubPageActive(overlay)
    }

    private fun showDetailPage() {
        val b = _binding ?: return
        b.panelSkinDetailOverview.isVisible = true
        b.panelSkinDetailComments.isVisible = true
        b.panelSkinCommentComposer.isVisible = false
        refreshComments()
    }

    private fun bindAuthControls() {
        val b = _binding ?: return
        val loggedIn = AppContainer.multiplayerAuth.current() != null
        b.ratingBarSkinMine.isEnabled = loggedIn
        b.textSkinRateHint.isVisible = !loggedIn
        b.buttonWriteSkinComment.isEnabled = loggedIn
        ratingBusy = true
        b.ratingBarSkinMine.rating = 0f
        ratingBusy = false
        val skinId = detailSkin?.id ?: return
        val session = AppContainer.multiplayerAuth.current() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            api.myRating(session, skinId).onSuccess { score ->
                if (_binding == null || detailSkin?.id != skinId) return@onSuccess
                if (score != null) {
                    ratingBusy = true
                    binding.ratingBarSkinMine.rating = score.toFloat()
                    ratingBusy = false
                }
            }
        }
    }

    private fun submitRating(score: Int) {
        val skin = detailSkin ?: return
        val session = AppContainer.multiplayerAuth.current() ?: run {
            toast(getString(R.string.skin_store_need_login))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            api.submitRating(session, skin.id, score).onSuccess { result ->
                detailSkin = skin.copy(
                    ratingAvg = result.ratingAvg,
                    ratingCount = result.ratingCount
                )
                binding.textSkinDetailRating.text = getString(
                    R.string.skin_store_rating_side,
                    result.ratingAvg,
                    result.ratingCount
                )
            }.onFailure {
                toast(it.message ?: getString(R.string.skin_store_rating_failed))
            }
        }
    }

    private fun refreshComments() {
        val skinId = detailSkin?.id ?: return
        val session = AppContainer.multiplayerAuth.current()
        viewLifecycleOwner.lifecycleScope.launch {
            api.listComments(skinId, session = session).onSuccess { list ->
                commentAdapter?.submit(list)
                binding.textSkinCommentsEmpty.isVisible = list.isEmpty()
            }.onFailure {
                commentAdapter?.submit(emptyList())
                binding.textSkinCommentsEmpty.isVisible = true
            }
        }
    }

    private fun submitComment() {
        val skin = detailSkin ?: return
        val session = AppContainer.multiplayerAuth.current() ?: run {
            toast(getString(R.string.skin_store_need_login))
            return
        }
        val body = binding.inputSkinComment.text?.toString().orEmpty().trim()
        if (body.isEmpty()) {
            toast(getString(R.string.skin_store_comment_empty))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            api.submitComment(session, skin.id, body).onSuccess {
                detailSkin = skin.copy(commentCount = skin.commentCount + 1)
                binding.inputSkinComment.setText("")
                binding.panelSkinCommentComposer.isVisible = false
                toast(getString(R.string.skin_store_comment_ok))
                refreshComments()
            }.onFailure {
                toast(it.message ?: getString(R.string.skin_store_comment_failed))
            }
        }
    }

    private fun reportComment(skinId: String, comment: SkinComment) {
        val session = AppContainer.multiplayerAuth.current() ?: run {
            toast(getString(R.string.skin_store_need_login))
            return
        }
        val myId = session.user.id
        if (myId.isNotBlank() && myId == comment.userId) {
            toast(getString(R.string.skin_store_report_own))
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.skin_store_report_title)
            .setMessage(R.string.skin_store_report_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.skin_store_report) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    api.reportComment(session, skinId, comment.id).onSuccess { result ->
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.skin_store_report_result)
                            .setMessage(result.dialogText())
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        if (result.reportValid == true && result.actionLevel in 2..3) {
                            commentAdapter?.remove(comment.id)
                        }
                    }.onFailure {
                        toast(it.message ?: getString(R.string.skin_store_report_failed))
                    }
                }
            }
            .show()
    }

    private fun reportSkin() {
        val skin = detailSkin ?: return
        val session = AppContainer.multiplayerAuth.current() ?: run {
            toast(getString(R.string.skin_store_need_login))
            return
        }
        val form = layoutInflater.inflate(R.layout.dialog_skin_report, null, false)
        val group = form.findViewById<android.widget.RadioGroup>(R.id.groupSkinReportCategory)
        val detail = form.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.inputSkinReportDetail
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.skin_store_report_skin_title)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.skin_store_report) { _, _ ->
                val category = when (group.checkedRadioButtonId) {
                    R.id.radioSkinReportPlagiarism -> "plagiarism"
                    R.id.radioSkinReportNsfw -> "nsfw"
                    R.id.radioSkinReportMark -> "special_mark"
                    R.id.radioSkinReportOther -> "other"
                    else -> null
                }
                if (category.isNullOrBlank()) {
                    toast(getString(R.string.skin_store_report_skin_need_category))
                    return@setPositiveButton
                }
                val note = detail.text?.toString()?.trim().orEmpty()
                viewLifecycleOwner.lifecycleScope.launch {
                    api.reportSkin(session, skin.id, category, note.ifBlank { null }).onSuccess {
                        toast(it)
                    }.onFailure {
                        toast(it.message ?: getString(R.string.skin_store_report_failed))
                    }
                }
            }
            .show()
    }

    private fun confirmApply() {
        val skin = detailSkin ?: return
        val accounts = SkinApplyHelper.applyableAccounts()
        if (accounts.isEmpty()) {
            toast(getString(R.string.skin_store_no_offline))
            return
        }
        val labels = accounts.map { acc ->
            when (acc.type) {
                AccountType.MICROSOFT -> getString(R.string.skin_store_account_microsoft, acc.name)
                else -> getString(R.string.skin_store_account_offline, acc.name)
            }
        }.toTypedArray()
        val checked = BooleanArray(accounts.size) { false }
        val selected = AppContainer.repository.selectedAccount()
        val selectedIdx = accounts.indexOfFirst { it.id == selected?.id }.takeIf { it >= 0 } ?: 0
        checked[selectedIdx] = true

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.skin_store_apply_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNeutralButton(R.string.skin_store_apply_all) { _, _ ->
                applySkin(skin, accounts)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.skin_store_apply_selected) { _, _ ->
                val targets = accounts.filterIndexed { index, _ -> checked[index] }
                if (targets.isEmpty()) {
                    toast(getString(R.string.skin_store_apply_need_one))
                    return@setPositiveButton
                }
                applySkin(skin, targets)
            }
            .show()
    }

    private fun applySkin(skin: StoreSkin, targets: List<LauncherAccount>) {
        viewLifecycleOwner.lifecycleScope.launch {
            toast(getString(R.string.skin_store_applying))
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    api.markDownload(skin.id)
                    api.downloadTextureBytes(skin.textureUrl).getOrElse { throw it }
                }
                SkinApplyHelper.applyToAccounts(bytes, skin.model, targets)
            }.onSuccess { result ->
                toast(
                    getString(
                        R.string.skin_store_applied,
                        result.appliedOffline,
                        result.appliedMicrosoft
                    )
                )
                if (result.failed.isNotEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.skin_store_apply)
                        .setMessage(
                            getString(
                                R.string.skin_store_apply_partial,
                                result.failed.joinToString("\n")
                            )
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }.onFailure {
                toast(it.message ?: getString(R.string.skin_store_load_failed))
            }
        }
    }

    private fun submitUpload() {
        val session = AppContainer.multiplayerAuth.current() ?: run {
            toast(getString(R.string.skin_store_need_login))
            return
        }
        val name = binding.inputSkinName.text?.toString().orEmpty().trim()
        if (name.isEmpty()) {
            toast(getString(R.string.skin_store_need_name))
            return
        }
        val file = pendingUploadFile
        if (file == null || !file.isFile) {
            toast(getString(R.string.skin_store_no_file))
            return
        }
        val reprint = binding.toggleSkinOrigin.checkedButtonId == R.id.buttonOriginReprint
        val source = binding.inputSkinSource.text?.toString().orEmpty().trim()
        if (reprint && source.isEmpty()) {
            toast(getString(R.string.skin_store_need_source))
            return
        }
        val model = if (binding.toggleSkinModel.checkedButtonId == R.id.buttonModelSlim) {
            "slim"
        } else {
            "classic"
        }
        val desc = binding.inputSkinDesc.text?.toString().orEmpty().trim()
        // 转载强制不允许再转载。
        val allowReprint = if (reprint) false else binding.switchSkinAllowReprint.isChecked
        viewLifecycleOwner.lifecycleScope.launch {
            api.submitApplicationWithFile(
                session = session,
                name = name,
                description = desc,
                model = model,
                file = file,
                originType = if (reprint) "reprint" else "original",
                source = source,
                allowReprint = allowReprint
            ).onSuccess {
                toast(getString(R.string.skin_store_upload_ok, it.status))
                showCatalog()
            }.onFailure {
                toast(it.message ?: getString(R.string.skin_store_upload_failed))
            }
        }
    }

    private fun toast(msg: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        Home3dBinder.destroy(_binding?.webSkinPreview3d)
        super.onDestroyView()
        listAdapter = null
        commentAdapter = null
        _binding = null
    }

    companion object {
        const val TAG = "SkinLibraryFragment"
        const val PAGE_SIZE = 8
    }
}
