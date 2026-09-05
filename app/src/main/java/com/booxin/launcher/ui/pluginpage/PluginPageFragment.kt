package com.booxin.launcher.ui.pluginpage

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.webkit.WebViewAssetLoader
import com.booxin.launcher.R
import com.booxin.launcher.core.uiplugin.UiPluginManager
import com.booxin.launcher.databinding.FragmentPluginPageBinding

/**
 * Hosts a plugin page: local HTML under the install dir, or an https WebView shell ([url]).
 * JS bridge is allowlisted and gated by jsCommands permission grant.
 */
class PluginPageFragment : Fragment() {

    private var _binding: FragmentPluginPageBinding? = null
    private val binding get() = _binding!!
    private var previousOrientation: Int? = null
    private var appliedFullscreen = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPluginPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val pluginId = arguments?.getString(ARG_PLUGIN_ID).orEmpty()
        val pageId = arguments?.getString(ARG_PAGE_ID).orEmpty()
        binding.buttonPluginPageBack.setOnClickListener {
            findNavController().navigateUp()
        }

        val install = UiPluginManager.findInstall(pluginId)
        if (install == null || !install.enabled) {
            showError(getString(R.string.plugin_page_missing))
            return
        }
        val page = install.manifest.pages.firstOrNull { it.id == pageId }
        if (page == null) {
            showError(getString(R.string.plugin_page_missing))
            return
        }

        applyPageChrome(page.normalizedOrientation(), page.fullscreen)

        val remoteUrl = page.remoteHttpsUrl()
        val entry = if (remoteUrl == null) {
            UiPluginManager.resolvePageEntry(install, page)
        } else {
            null
        }
        if (remoteUrl == null && entry == null) {
            showError(getString(R.string.plugin_page_bad_entry))
            return
        }

        binding.textPluginPageTitle.text = page.title
        val web = binding.webPluginPage
        val pluginRoot = install.dir
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(DOMAIN)
            .addPathHandler(
                "/plugin/",
                WebViewAssetLoader.InternalStoragePathHandler(requireContext(), pluginRoot)
            )
            .build()

        val remoteHost = remoteUrl?.let { Uri.parse(it).host.orEmpty().lowercase() }.orEmpty()
        val allowedHosts = linkedSetOf<String>().apply {
            if (remoteHost.isNotBlank()) {
                add(remoteHost)
                if (remoteHost.startsWith("www.")) add(remoteHost.removePrefix("www."))
                else add("www.$remoteHost")
            }
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = if (remoteUrl != null) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_NO_CACHE
            }
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        val jsGranted = install.manifest.jsCommands &&
            com.booxin.launcher.core.uiplugin.UiPluginPermissionStore
                .isJsCommandsGranted(requireContext(), pluginId)
        if (jsGranted) {
            web.addJavascriptInterface(
                PluginJsBridge(
                    appContext = requireContext().applicationContext,
                    pluginId = pluginId,
                    pageId = pageId,
                    requiresGrant = true,
                    navController = { findNavController() },
                    onClose = { findNavController().navigateUp() }
                ),
                "BooxinPlugin"
            )
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                assetLoader.shouldInterceptRequest(url)?.let { return it }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return true
                val host = url.host.orEmpty().lowercase()
                if (host == DOMAIN) return false
                if (allowedHosts.any { host == it || host.endsWith(".$it") }) return false
                val scheme = url.scheme?.lowercase().orEmpty()
                if (scheme == "http" || scheme == "https") {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    }
                }
                return true
            }
        }

        if (remoteUrl != null) {
            web.loadUrl(remoteUrl)
            return
        }

        val file = entry!!
        val relative = file.relativeTo(pluginRoot).invariantSeparatorsPath.trimStart('/')
        val pageUrl = "https://$DOMAIN/plugin/$relative"
        if (!file.isFile) {
            showError(getString(R.string.plugin_page_bad_entry))
            return
        }
        runCatching {
            web.loadUrl(pageUrl)
        }.onFailure {
            val html = file.readText()
            web.loadDataWithBaseURL(
                "https://$DOMAIN/plugin/",
                html,
                "text/html",
                "utf-8",
                null
            )
        }
    }

    private fun applyPageChrome(orientation: String, fullscreen: Boolean) {
        val activity = activity ?: return
        if (orientation.isNotEmpty()) {
            previousOrientation = activity.requestedOrientation
            activity.requestedOrientation = when (orientation) {
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> activity.requestedOrientation
            }
        }
        if (fullscreen) {
            appliedFullscreen = true
            binding.rowPluginPageHeader.isVisible = false
            binding.root.setPadding(0, 0, 0, 0)
            (binding.webPluginPage.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = 0
                binding.webPluginPage.layoutParams = lp
            }
            (activity as? com.booxin.launcher.ui.MainActivity)?.setTopChromeVisible(false)
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun restorePageChrome() {
        val activity = activity ?: return
        previousOrientation?.let { activity.requestedOrientation = it }
        previousOrientation = null
        if (appliedFullscreen) {
            appliedFullscreen = false
            (activity as? com.booxin.launcher.ui.MainActivity)?.setTopChromeVisible(true)
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showError(msg: String) {
        binding.textPluginPageError.isVisible = true
        binding.textPluginPageError.text = msg
        binding.webPluginPage.isVisible = false
    }

    override fun onDestroyView() {
        restorePageChrome()
        binding.webPluginPage.apply {
            stopLoading()
            loadUrl("about:blank")
            removeJavascriptInterface("BooxinPlugin")
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_PLUGIN_ID = "pluginId"
        const val ARG_PAGE_ID = "pageId"
        private const val DOMAIN = "appassets.androidplatform.net"

        fun args(pluginId: String, pageId: String) = Bundle().apply {
            putString(ARG_PLUGIN_ID, pluginId)
            putString(ARG_PAGE_ID, pageId)
        }
    }
}
