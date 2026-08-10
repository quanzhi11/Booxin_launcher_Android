package com.booxin.launcher.ui

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.booxin.launcher.core.LauncherBackgroundAlignMode
import com.booxin.launcher.core.LauncherBackgroundTheme
import com.booxin.launcher.core.LauncherPrefs
import com.booxin.launcher.core.launch.OemLaunchProfile
import com.booxin.launcher.core.uiplugin.UiPluginManager
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Liquid-glass ambient layer: looping PC wallpaper videos + static fallback.
 *
 * Fitting is controlled by [LauncherPrefs.backgroundAlign] (auto / stretch / crop / manual).
 */
object GlassBackground {
    private const val TAG = "GlassBackground"
    private const val STATIC_ASSET = "backgrounds/glass_ambient_bg.png"

    private val hosts = CopyOnWriteArrayList<Host>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun bind(
        owner: LifecycleOwner,
        textureView: TextureView,
        imageView: ImageView,
        orbsView: View? = null
    ) {
        val host = Host(textureView, imageView, orbsView)
        hosts.add(host)
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                host.resume()
            }

            override fun onStop(owner: LifecycleOwner) {
                host.pause()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                host.release()
                hosts.remove(host)
                owner.lifecycle.removeObserver(this)
            }
        })
        host.reloadBackground()
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            host.resume()
        }
    }

    fun notifyThemeChanged() {
        runOnMain { hosts.forEach { it.reloadBackground() } }
    }

    fun notifyPluginBackgroundChanged() {
        runOnMain { hosts.forEach { it.reloadBackground() } }
    }

    fun notifyAlignChanged() {
        runOnMain { hosts.forEach { it.reapplyFill() } }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun loadStaticFallback(imageView: ImageView, alpha: Float = 0.85f) {
        val context = imageView.context.applicationContext
        thread(name = "glass-bg-static") {
            val bmp = runCatching {
                context.assets.open(STATIC_ASSET).use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@thread
            imageView.post {
                imageView.setImageBitmap(bmp)
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                imageView.alpha = alpha
            }
        }
    }

    private fun loadPluginImage(imageView: ImageView, file: File, alpha: Float) {
        thread(name = "glass-bg-plugin-image") {
            val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                ?: return@thread
            imageView.post {
                imageView.setImageBitmap(bmp)
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                imageView.alpha = alpha
                imageView.isVisible = true
            }
        }
    }

    private class Host(
        private val textureView: TextureView,
        private val imageView: ImageView,
        private val orbsView: View?
    ) {
        private val vivoDefault = OemLaunchProfile.needsTextureViewScaleHack()
        private var player: MediaPlayer? = null
        private var surface: Surface? = null
        private var currentTheme: LauncherBackgroundTheme? = null
        private var pluginVideo: File? = null
        private var pluginImage: File? = null
        private var backgroundAlpha: Float = 0.85f
        private var started = false
        private var playGeneration = 0

        init {
            Log.i(TAG, "bind oem=${OemLaunchProfile.describe()} vivoDefault=$vivoDefault")
            textureView.alpha = 0f
            textureView.isOpaque = false

            (textureView.parent as? ViewGroup)?.let { parent ->
                parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    applyFill()
                }
            }

            resetMatchParent(imageView)
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            resetMatchParent(textureView)
            clearViewScale()

            textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                applyFill()
            }
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    bindSurface(st)
                    if (started) {
                        restartCurrentVideo()
                    }
                    applyFill()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    applyFill()
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    releasePlayer()
                    surface?.release()
                    surface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }

        fun reloadBackground() {
            val pack = UiPluginManager.resolveActiveTheme()
            val alpha = pack?.spec?.backgroundAlpha ?: 0.85f
            backgroundAlpha = alpha.coerceIn(0f, 1f)
            pluginVideo = pack?.backgroundVideo
            pluginImage = pack?.backgroundImage
            val hideOrbs = pack?.spec?.hideOrbs == true
            if (pluginVideo != null || pluginImage != null) {
                currentTheme = null
                showFallback()
                if (pluginImage != null) {
                    loadPluginImage(imageView, pluginImage!!, backgroundAlpha)
                } else {
                    loadStaticFallback(imageView, backgroundAlpha)
                }
                if (started && pluginVideo != null) {
                    startVideoFromFile(pluginVideo!!)
                }
                if (hideOrbs) orbsView?.isVisible = false
                return
            }
            val theme = LauncherPrefs.backgroundTheme()
            if (currentTheme == theme && player != null && pluginVideo == null) {
                if (hideOrbs) orbsView?.isVisible = false
                return
            }
            currentTheme = theme
            pluginVideo = null
            pluginImage = null
            loadStaticFallback(imageView, backgroundAlpha)
            showFallback()
            if (hideOrbs) orbsView?.isVisible = false
            if (started) startVideoFromAsset(theme)
        }

        fun resume() {
            started = true
            ensureSurface()
            val p = player
            if (p != null) {
                runCatching {
                    if (!p.isPlaying) p.start()
                    textureView.alpha = backgroundAlpha
                    imageView.isVisible = false
                    orbsView?.isVisible = false
                    applyFill()
                }
            } else {
                restartCurrentVideo()
            }
        }

        private fun restartCurrentVideo() {
            val file = pluginVideo
            if (file != null) {
                startVideoFromFile(file)
                return
            }
            val theme = currentTheme ?: LauncherPrefs.backgroundTheme().also { currentTheme = it }
            startVideoFromAsset(theme)
        }

        fun reapplyFill() {
            applyFill()
            applyImageAlign()
        }

        fun pause() {
            started = false
            runCatching { player?.pause() }
        }

        fun release() {
            started = false
            releasePlayer()
            surface?.release()
            surface = null
        }

        private fun coverSize(): Pair<Int, Int> {
            val parent = textureView.parent as? View
            val w = parent?.width?.takeIf { it > 0 } ?: textureView.width
            val h = parent?.height?.takeIf { it > 0 } ?: textureView.height
            return w to h
        }

        private fun resetMatchParent(view: View) {
            val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.FILL
            lp.leftMargin = 0
            lp.topMargin = 0
            view.layoutParams = lp
        }

        private fun clearViewScale() {
            textureView.pivotX = 0f
            textureView.pivotY = 0f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
            textureView.translationX = 0f
            textureView.translationY = 0f
        }

        private fun ensureSurface() {
            if (surface != null) return
            val st = textureView.surfaceTexture ?: return
            bindSurface(st)
        }

        private fun usesViewScale(mode: LauncherBackgroundAlignMode): Boolean {
            return when (mode) {
                LauncherBackgroundAlignMode.STRETCH,
                LauncherBackgroundAlignMode.CROP,
                LauncherBackgroundAlignMode.MANUAL -> true
                LauncherBackgroundAlignMode.AUTO -> vivoDefault
            }
        }

        private fun bindSurface(st: SurfaceTexture) {
            surface?.release()
            surface = Surface(st)
        }

        private fun showFallback() {
            textureView.alpha = 0f
            clearViewScale()
            resetMatchParent(textureView)
            imageView.isVisible = true
            imageView.alpha = backgroundAlpha
            val hideOrbs = UiPluginManager.resolveActiveTheme()?.spec?.hideOrbs == true
            orbsView?.isVisible = !hideOrbs && pluginVideo == null && pluginImage == null
            applyImageAlign()
        }

        private fun startVideoFromAsset(theme: LauncherBackgroundTheme) {
            val context = textureView.context.applicationContext
            startVideoInternal(label = theme.id) { mp ->
                context.assets.openFd(theme.assetPath).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            }
        }

        private fun startVideoFromFile(file: File) {
            startVideoInternal(label = file.name) { mp ->
                mp.setDataSource(file.absolutePath)
            }
        }

        private fun startVideoInternal(label: String, bindSource: (MediaPlayer) -> Unit) {
            ensureSurface()
            val surf = surface ?: return
            val gen = ++playGeneration
            releasePlayer()

            thread(name = "glass-bg-video") {
                var mp: MediaPlayer? = null
                try {
                    mp = MediaPlayer()
                    bindSource(mp)
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)
                    mp.setOnVideoSizeChangedListener { _, vw, vh ->
                        Log.i(TAG, "videoSize ${vw}x$vh")
                        textureView.post { applyFill() }
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        textureView.post {
                            if (gen == playGeneration) showFallback()
                        }
                        Log.w(TAG, "video error what=$what extra=$extra source=$label")
                        true
                    }
                    mp.setOnPreparedListener { prepared ->
                        textureView.post {
                            if (gen != playGeneration || !started) {
                                prepared.release()
                                return@post
                            }
                            player = prepared
                            try {
                                applyFill()
                                prepared.setSurface(surf)
                                applyFill()
                                prepared.start()
                                textureView.alpha = backgroundAlpha
                                imageView.isVisible = false
                                orbsView?.isVisible = false
                                textureView.post { applyFill() }
                            } catch (e: Exception) {
                                Log.w(TAG, "start failed", e)
                                showFallback()
                                prepared.release()
                                player = null
                            }
                        }
                    }
                    player = mp
                    mp.prepareAsync()
                } catch (e: Exception) {
                    Log.w(TAG, "open video failed source=$label", e)
                    mp?.release()
                    if (player === mp) player = null
                    textureView.post {
                        if (gen == playGeneration) showFallback()
                    }
                }
            }
        }

        private fun releasePlayer() {
            val p = player
            player = null
            runCatching {
                p?.setOnPreparedListener(null)
                p?.setOnErrorListener(null)
                p?.setOnVideoSizeChangedListener(null)
                p?.stop()
                p?.reset()
                p?.release()
            }
        }

        private fun applyFill() {
            val (coverW, coverH) = coverSize()
            if (coverW <= 0 || coverH <= 0) return
            val align = LauncherPrefs.backgroundAlign()
            val viewScale = usesViewScale(align.mode)
            (textureView.parent as? ViewGroup)?.let { parent ->
                parent.clipChildren = !viewScale
                parent.clipToPadding = !viewScale
            }
            applyImageAlign()

            val videoW = player?.videoWidth ?: 0
            val videoH = player?.videoHeight ?: 0
            if (videoW <= 0 || videoH <= 0) {
                if (!viewScale) {
                    resetMatchParent(textureView)
                    clearViewScale()
                    textureView.setTransform(Matrix())
                }
                return
            }

            when (align.mode) {
                LauncherBackgroundAlignMode.AUTO -> {
                    if (vivoDefault) {
                        applyViewScaleFill(
                            coverW, coverH, videoW, videoH,
                            scaleX = 1f, scaleY = 1f,
                            offsetX = 0f, offsetY = 0f,
                            keepAspect = false
                        )
                    } else {
                        applyBufferFill(coverW, coverH)
                    }
                }
                LauncherBackgroundAlignMode.STRETCH -> {
                    applyViewScaleFill(
                        coverW, coverH, videoW, videoH,
                        scaleX = 1f, scaleY = 1f,
                        offsetX = 0f, offsetY = 0f,
                        keepAspect = false
                    )
                }
                LauncherBackgroundAlignMode.CROP -> {
                    applyViewScaleFill(
                        coverW, coverH, videoW, videoH,
                        scaleX = 1f, scaleY = 1f,
                        offsetX = 0f, offsetY = 0f,
                        keepAspect = true
                    )
                }
                LauncherBackgroundAlignMode.MANUAL -> {
                    applyViewScaleFill(
                        coverW, coverH, videoW, videoH,
                        scaleX = align.scaleX, scaleY = align.scaleY,
                        offsetX = align.offsetX, offsetY = align.offsetY,
                        keepAspect = false
                    )
                }
            }
        }

        private fun applyImageAlign() {
            val align = LauncherPrefs.backgroundAlign()
            resetMatchParent(imageView)
            imageView.scaleType = when (align.mode) {
                LauncherBackgroundAlignMode.STRETCH,
                LauncherBackgroundAlignMode.MANUAL -> ImageView.ScaleType.FIT_XY
                else -> ImageView.ScaleType.CENTER_CROP
            }
            imageView.translationX = 0f
            imageView.translationY = 0f
            imageView.scaleX = 1f
            imageView.scaleY = 1f
            if (align.mode == LauncherBackgroundAlignMode.MANUAL) {
                imageView.scaleX = align.scaleX
                imageView.scaleY = align.scaleY
                val (cw, ch) = coverSize()
                imageView.translationX = align.offsetX * cw
                imageView.translationY = align.offsetY * ch
            }
        }

        /** Non-vivo auto: buffer = view size, identity matrix. */
        private fun applyBufferFill(coverW: Int, coverH: Int) {
            resetMatchParent(textureView)
            clearViewScale()
            textureView.translationX = 0f
            textureView.translationY = 0f
            textureView.setTransform(Matrix())
            textureView.surfaceTexture?.let { st ->
                runCatching { st.setDefaultBufferSize(coverW, coverH) }
            }
        }

        /**
         * Layout TextureView to native video size, identity content transform,
         * then scale/translate the View to cover (works on vivo).
         */
        private fun applyViewScaleFill(
            coverW: Int,
            coverH: Int,
            videoW: Int,
            videoH: Int,
            scaleX: Float,
            scaleY: Float,
            offsetX: Float,
            offsetY: Float,
            keepAspect: Boolean
        ) {
            textureView.setTransform(Matrix())
            textureView.surfaceTexture?.let { st ->
                runCatching { st.setDefaultBufferSize(videoW, videoH) }
            }

            val lp = (textureView.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(videoW, videoH)
            if (lp.width != videoW || lp.height != videoH ||
                lp.gravity != (Gravity.TOP or Gravity.START)
            ) {
                lp.width = videoW
                lp.height = videoH
                lp.gravity = Gravity.TOP or Gravity.START
                lp.leftMargin = 0
                lp.topMargin = 0
                textureView.layoutParams = lp
            }

            val baseX: Float
            val baseY: Float
            if (keepAspect) {
                val s = max(coverW.toFloat() / videoW, coverH.toFloat() / videoH)
                baseX = s
                baseY = s
            } else {
                baseX = coverW.toFloat() / videoW
                baseY = coverH.toFloat() / videoH
            }
            val sx = baseX * scaleX
            val sy = baseY * scaleY
            textureView.pivotX = 0f
            textureView.pivotY = 0f
            textureView.scaleX = sx
            textureView.scaleY = sy

            // Center when cropped / oversized, then apply user pan.
            val drawnW = videoW * sx
            val drawnH = videoH * sy
            val centerX = (coverW - drawnW) / 2f
            val centerY = (coverH - drawnH) / 2f
            textureView.translationX = centerX + offsetX * coverW
            textureView.translationY = centerY + offsetY * coverH
        }
    }
}

