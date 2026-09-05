package com.booxin.launcher.ui.home

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.isVisible
import androidx.webkit.WebViewAssetLoader
import com.booxin.launcher.core.uiplugin.UiPluginManager
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/**
 * Shows a local Three.js Minecraft-player viewer when home3dModel UI plugin is enabled.
 * Skin is loaded via WebViewAssetLoader file URL (stable); JS inject kept as refresh fallback.
 */
object Home3dBinder {

    private const val DOMAIN = "appassets.androidplatform.net"
    private var lastPageUrl: String? = null
    private var lastSkinKey: String? = null
    private var boundWebView: WeakReference<WebView>? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun bind(
        webView: WebView,
        welcomePanel: View,
        skinPng: File?,
        forceReload: Boolean = false
    ) {
        val enabled = UiPluginManager.isFeatureEnabled("home3dModel")
        if (!enabled) {
            resetState()
            webView.isVisible = false
            welcomePanel.isVisible = true
            webView.stopLoading()
            webView.loadUrl("about:blank")
            return
        }

        welcomePanel.isVisible = false
        webView.isVisible = true
        prepareWebView(webView, skinPng, forceReload)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun prepareWebView(webView: WebView, skinPng: File?, forceReload: Boolean) {
        if (boundWebView?.get() !== webView) {
            lastPageUrl = null
            lastSkinKey = null
            boundWebView = WeakReference(webView)
        }

        val context = webView.context.applicationContext
        val skinFile = skinPng?.takeIf { it.isFile && it.length() > 64L }
        val skinKey = skinFile?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
            ?: "default"
        val skinDir = File(context.cacheDir, "home3d").also { it.mkdirs() }

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/skin/", WebViewAssetLoader.InternalStoragePathHandler(context, skinDir))
            .build()

        webView.setBackgroundColor(0x00000000)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(url)
            }
        }

        val skinQuery = if (skinFile != null) {
            val skinUrl = "https://$DOMAIN/skin/${skinFile.name}?v=${skinFile.lastModified()}"
            "&skin=" + Uri.encode(skinUrl)
        } else {
            ""
        }
        val pageUrl = "https://$DOMAIN/assets/home3d/viewer.html?rotate=1$skinQuery"
        val current = webView.url.orEmpty()
        val pageAlive = current.contains("viewer.html") && current != "about:blank"
        val needReload = forceReload ||
            lastPageUrl != pageUrl ||
            lastSkinKey != skinKey ||
            !pageAlive

        if (needReload) {
            lastPageUrl = pageUrl
            lastSkinKey = skinKey
            webView.loadUrl(pageUrl)
        } else if (skinFile != null) {
            // Same page / same file — nudge texture in case WebView blanked the GL context.
            val skinUrl = "https://$DOMAIN/skin/${skinFile.name}?v=${skinFile.lastModified()}"
            injectSkin(webView, skinUrl)
        }
    }

    private fun injectSkin(webView: WebView?, skinUrl: String?) {
        webView ?: return
        val arg = if (skinUrl.isNullOrBlank()) {
            "null"
        } else {
            JSONObject.quote(skinUrl)
        }
        val js =
            "(function(){try{if(window.__booxinSetSkin){window.__booxinSetSkin($arg);}else{window.__booxinPendingSkin=$arg;}}catch(e){}})();"
        runCatching { webView.evaluateJavascript(js, null) }
    }

    fun pause(webView: WebView?) {
        webView ?: return
        runCatching { webView.onPause() }
    }

    fun resume(webView: WebView?) {
        webView ?: return
        if (webView.isVisible) {
            runCatching { webView.onResume() }
            val url = webView.url.orEmpty()
            if (!url.contains("viewer.html")) {
                lastPageUrl = null
            }
        }
    }

    fun destroy(webView: WebView?) {
        webView ?: return
        resetState()
        runCatching {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        }
    }

    private fun resetState() {
        lastPageUrl = null
        lastSkinKey = null
        boundWebView = null
    }
}
