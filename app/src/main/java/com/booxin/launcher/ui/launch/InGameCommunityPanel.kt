package com.booxin.launcher.ui.launch

import android.app.AlertDialog
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.community.CommunitySearchQuery
import com.booxin.launcher.data.model.CommunityContentType
import com.booxin.launcher.data.model.CommunityLoader
import com.booxin.launcher.data.model.ModpackInstallProgress
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.data.model.ModrinthProjectVersion
import com.booxin.launcher.data.model.ModrinthSearchPage
import com.booxin.launcher.databinding.DialogIngameCommunityBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 游戏内社区：极简悬浮层（仅系统控件），避免 Dialog / Material 组件在 :game 进程闪退。
 */
class InGameCommunityPanel(
    private val activity: AppCompatActivity,
    private val versionIdProvider: () -> String = { "" }
) {
    private var overlay: View? = null
    private var binding: DialogIngameCommunityBinding? = null
    private var searchJob: Job? = null
    private var installJob: Job? = null
    private var contentType: CommunityContentType = CommunityContentType.SHADER
    private var lastPage: ModrinthSearchPage? = null
    private var adapter: InGameCommunityAdapter? = null

    fun show() {
        try {
            showInternal()
        } catch (t: Throwable) {
            Log.e(TAG, "community show failed", t)
            Toast.makeText(
                activity,
                activity.getString(R.string.community_search_failed, t.message ?: t.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
            closePanel()
        }
    }

    private fun showInternal() {
        val versionId = versionIdProvider().trim()
        if (versionId.isBlank()) {
            Toast.makeText(activity, R.string.ingame_community_no_version, Toast.LENGTH_LONG).show()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return

        closePanel()

        val parent = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: error("activity content missing")
        val panelBinding = DialogIngameCommunityBinding.inflate(
            LayoutInflater.from(activity),
            parent,
            false
        )
        binding = panelBinding
        overlay = panelBinding.root

        val listAdapter = InGameCommunityAdapter(::onProjectClick)
        adapter = listAdapter
        panelBinding.recyclerProjects.layoutManager = GridLayoutManager(activity, 2)
        panelBinding.recyclerProjects.adapter = listAdapter
        panelBinding.recyclerProjects.itemAnimator = null
        panelBinding.textTargetVersion.text =
            activity.getString(R.string.ingame_community_target, versionId)

        applyPanelSize(panelBinding)

        refreshTypeButtons()
        panelBinding.buttonTypeShader.setOnClickListener {
            if (contentType != CommunityContentType.SHADER) {
                contentType = CommunityContentType.SHADER
                refreshTypeButtons()
                performSearch(0)
            }
        }
        panelBinding.buttonTypeResource.setOnClickListener {
            if (contentType != CommunityContentType.RESOURCE_PACK) {
                contentType = CommunityContentType.RESOURCE_PACK
                refreshTypeButtons()
                performSearch(0)
            }
        }
        panelBinding.buttonSearch.setOnClickListener { performSearch(0) }
        panelBinding.inputSearch.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) {
                performSearch(0)
                true
            } else {
                false
            }
        }
        panelBinding.buttonPrevPage.setOnClickListener {
            val page = lastPage ?: return@setOnClickListener
            if (!page.hasPrevious) return@setOnClickListener
            performSearch((page.offset - page.limit).coerceAtLeast(0))
        }
        panelBinding.buttonNextPage.setOnClickListener {
            val page = lastPage ?: return@setOnClickListener
            if (!page.hasNext) return@setOnClickListener
            performSearch(page.offset + page.limit)
        }
        panelBinding.buttonClose.setOnClickListener { closePanel() }
        panelBinding.root.setOnClickListener { closePanel() }
        panelBinding.communityPanel.setOnClickListener { /* 吞点击 */ }

        parent.addView(
            panelBinding.root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // 延后搜索，先把面板画出来，避免和游戏帧抢主线程
        panelBinding.root.postDelayed({ performSearch(0) }, 120L)
    }

    /** 限制面板与列表高度，分页始终留在屏内。 */
    private fun applyPanelSize(panelBinding: DialogIngameCommunityBinding) {
        val dm = activity.resources.displayMetrics
        val panel = panelBinding.communityPanel
        val maxPanelHeight = (dm.heightPixels * 0.82f).toInt()
        val panelWidth = (dm.widthPixels * 0.82f).toInt()

        val chromePx = (200f * dm.density).toInt()
        val listHeight = (maxPanelHeight - chromePx).coerceAtLeast((120f * dm.density).toInt())
        panelBinding.recyclerContainer.layoutParams =
            panelBinding.recyclerContainer.layoutParams.apply { height = listHeight }

        val lp = panel.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            lp.width = panelWidth
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.CENTER
            panel.layoutParams = lp
        } else if (lp != null) {
            lp.width = panelWidth
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            panel.layoutParams = lp
        }
    }

    fun dispose() {
        closePanel()
    }

    private fun closePanel() {
        searchJob?.cancel()
        searchJob = null
        installJob?.cancel()
        installJob = null
        val root = overlay
        overlay = null
        runCatching { (root?.parent as? ViewGroup)?.removeView(root) }
        binding?.recyclerProjects?.adapter = null
        binding = null
        adapter = null
        lastPage = null
    }

    private fun refreshTypeButtons() {
        val b = binding ?: return
        b.buttonTypeShader.isEnabled = contentType != CommunityContentType.SHADER
        b.buttonTypeResource.isEnabled = contentType != CommunityContentType.RESOURCE_PACK
    }

    private fun setStatus(message: CharSequence?) {
        val b = binding ?: return
        val text = message?.toString().orEmpty()
        b.textStatus.isVisible = text.isNotBlank()
        b.textStatus.text = text
    }

    private fun performSearch(offset: Int) {
        val versionId = versionIdProvider().trim()
        if (versionId.isBlank()) return
        val b = binding ?: return
        val query = b.inputSearch.text?.toString().orEmpty()
        searchJob?.cancel()
        setLoading(true)
        searchJob = activity.lifecycleScope.launch {
            try {
                setStatus(
                    if (CommunitySearchQuery.containsChinese(query)) {
                        activity.getString(R.string.community_loading_zh)
                    } else {
                        activity.getString(R.string.community_loading)
                    }
                )
                val loader = CommunityLoader.fromVersionId(versionId)
                val result = AppContainer.communityRepository.searchProjects(
                    query = query,
                    contentType = contentType,
                    loader = loader,
                    offset = offset,
                    limit = INGAME_PAGE_SIZE,
                    targetVersionId = versionId
                )
                if (binding == null) return@launch
                setLoading(false)
                result.fold(
                    onSuccess = { page ->
                        lastPage = page
                        adapter?.submit(page.projects)
                        val ui = binding ?: return@fold
                        ui.textEmpty.isVisible = page.projects.isEmpty()
                        ui.recyclerProjects.isVisible = page.projects.isNotEmpty()
                        ui.textPageInfo.text = activity.getString(
                            R.string.community_page_info,
                            page.pageNumber,
                            page.totalPages,
                            page.totalHits
                        )
                        ui.buttonPrevPage.isEnabled = page.hasPrevious
                        ui.buttonNextPage.isEnabled = page.hasNext
                        setStatus(
                            if (page.projects.isEmpty()) {
                                activity.getString(R.string.community_empty)
                            } else {
                                null
                            }
                        )
                    },
                    onFailure = { err ->
                        Log.e(TAG, "search failed", err)
                        adapter?.submit(emptyList())
                        val ui = binding ?: return@fold
                        ui.textEmpty.isVisible = true
                        ui.recyclerProjects.isVisible = false
                        setStatus(
                            activity.getString(
                                R.string.community_search_failed,
                                err.message ?: "unknown"
                            )
                        )
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "search crash", t)
                if (binding == null) return@launch
                setLoading(false)
                setStatus(
                    activity.getString(
                        R.string.community_search_failed,
                        t.message ?: t.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun onProjectClick(project: ModrinthProject) {
        val versionId = versionIdProvider().trim()
        if (versionId.isBlank()) return
        searchJob?.cancel()
        setLoading(true)
        setStatus(activity.getString(R.string.community_loading_versions))
        activity.lifecycleScope.launch {
            try {
                val versionsResult = AppContainer.communityRepository.getCompatibleProjectVersions(
                    projectId = project.id,
                    targetVersionId = versionId,
                    contentType = contentType
                )
                if (binding == null) return@launch
                setLoading(false)
                versionsResult.fold(
                    onSuccess = { compatible ->
                        when {
                            compatible.isEmpty() -> {
                                Toast.makeText(
                                    activity,
                                    R.string.community_no_compatible_target,
                                    Toast.LENGTH_LONG
                                ).show()
                                setStatus(activity.getString(R.string.community_versions_empty))
                            }
                            compatible.size == 1 -> {
                                setStatus(null)
                                installVersion(project, compatible.first(), versionId)
                            }
                            else -> {
                                setStatus(null)
                                showVersionPicker(project, compatible, versionId)
                            }
                        }
                    },
                    onFailure = { err ->
                        Toast.makeText(
                            activity,
                            activity.getString(
                                R.string.community_versions_failed,
                                err.message ?: "unknown"
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "project click failed", t)
                setLoading(false)
                Toast.makeText(activity, t.message ?: t.javaClass.simpleName, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showVersionPicker(
        project: ModrinthProject,
        versions: List<ModrinthProjectVersion>,
        targetVersionId: String
    ) {
        val labels = versions.map { version ->
            val name = version.name.ifBlank { version.versionNumber }
            val type = version.versionType.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            "$name$type"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(project.title)
            .setItems(labels) { _, which ->
                installVersion(project, versions[which], targetVersionId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun installVersion(
        project: ModrinthProject,
        version: ModrinthProjectVersion,
        targetVersionId: String
    ) {
        installJob?.cancel()
        setStatus(activity.getString(R.string.community_installing_target, targetVersionId))
        setLoading(true)
        installJob = activity.lifecycleScope.launch {
            try {
                var lastUiAt = 0L
                val result = AppContainer.communityRepository.installVersionFile(
                    contentType = contentType,
                    targetVersionId = targetVersionId,
                    version = version,
                    onProgress = { p ->
                        val now = System.currentTimeMillis()
                        if (p.bytesDownloaded < 0L || now - lastUiAt >= 200L) {
                            lastUiAt = now
                            activity.lifecycleScope.launch(Dispatchers.Main.immediate) {
                                setStatus(formatInstallProgress(p))
                            }
                        }
                    }
                )
                if (binding == null) return@launch
                setLoading(false)
                result.fold(
                    onSuccess = { file ->
                        Toast.makeText(
                            activity,
                            activity.getString(
                                R.string.community_install_done,
                                file.name,
                                targetVersionId
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                        setStatus(
                            activity.getString(
                                R.string.ingame_community_installed,
                                project.title,
                                file.name
                            )
                        )
                    },
                    onFailure = { err ->
                        Toast.makeText(
                            activity,
                            activity.getString(
                                R.string.community_install_failed,
                                err.message ?: "unknown"
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                        setStatus(
                            activity.getString(
                                R.string.community_install_failed,
                                err.message ?: "unknown"
                            )
                        )
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "install failed", t)
                setLoading(false)
                setStatus(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        val b = binding ?: return
        b.progress.isVisible = loading
        b.buttonSearch.isEnabled = !loading
        b.buttonPrevPage.isEnabled = !loading && (lastPage?.hasPrevious == true)
        b.buttonNextPage.isEnabled = !loading && (lastPage?.hasNext == true)
    }

    private fun formatInstallProgress(p: ModpackInstallProgress): String {
        val detail = p.detail.trim()
        val stage = p.stage.trim()
        val bytes = if (p.bytesDownloaded >= 0L && p.bytesTotal > 0L) {
            val pct = (p.bytesDownloaded * 100L / p.bytesTotal).coerceIn(0L, 100L)
            String.format(Locale.getDefault(), " · %d%%", pct)
        } else {
            ""
        }
        return when {
            detail.isNotBlank() && stage.isNotBlank() -> "$stage：$detail$bytes"
            detail.isNotBlank() -> detail + bytes
            else -> stage + bytes
        }
    }

    companion object {
        private const val TAG = "InGameCommunity"
        /** 游戏进程内存紧，每页少拉几条。 */
        private const val INGAME_PAGE_SIZE = 8
    }
}
