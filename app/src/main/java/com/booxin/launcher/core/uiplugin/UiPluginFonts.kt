package com.booxin.launcher.core.uiplugin

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.booxin.launcher.R
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Applies UI-plugin custom fonts across inflated views.
 * Scope: in-app Text; does not change system fonts.
 */
object UiPluginFonts {

    private data class Cached(
        val key: String,
        val typeface: Typeface?
    )

    private val cache = AtomicReference<Cached?>(null)

    fun invalidate() {
        cache.set(null)
    }

    fun currentTypeface(): Typeface? {
        val file = UiPluginManager.resolveCustomFontFile()
        val key = file?.absolutePath ?: ""
        cache.get()?.takeIf { it.key == key }?.let { return it.typeface }
        val loaded = file?.let { loadTypeface(it) }
        cache.set(Cached(key, loaded))
        return loaded
    }

    fun applyTo(activity: Activity?) {
        val root = activity?.window?.decorView ?: return
        applyDeep(root)
    }

    fun applyDeep(root: View?) {
        if (root == null) return
        val tf = currentTypeface()
        walk(root, tf)
    }

    fun installHost(activity: AppCompatActivity) {
        applyTo(activity)
        val tagKey = R.id.tag_ui_plugin_font_host
        if (activity.window.decorView.getTag(tagKey) == true) return
        activity.window.decorView.setTag(tagKey, true)
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: android.os.Bundle?
                ) {
                    applyDeep(v)
                }
            },
            true
        )
    }

    private fun loadTypeface(file: File): Typeface? =
        runCatching { Typeface.createFromFile(file) }.getOrNull()

    private fun walk(view: View, pluginFace: Typeface?) {
        when (view) {
            is TextView -> applyText(view, pluginFace)
            is RecyclerView -> {
                hookRecycler(view)
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i), pluginFace)
                }
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i), pluginFace)
                }
            }
        }
    }

    private fun applyText(tv: TextView, pluginFace: Typeface?) {
        if (tv.getTag(R.id.tag_ui_plugin_keep_font) == true) return
        val current = tv.typeface
        if (isMonospace(current)) return

        if (pluginFace == null) {
            // Leaving system default: only restore when we previously stamped a plugin face.
            if (tv.getTag(R.id.tag_ui_plugin_font_applied) == true) {
                val style = current?.style ?: Typeface.NORMAL
                tv.typeface = Typeface.defaultFromStyle(style)
                tv.setTag(R.id.tag_ui_plugin_font_applied, null)
            }
            return
        }
        val style = current?.style ?: Typeface.NORMAL
        tv.typeface = Typeface.create(pluginFace, style)
        tv.setTag(R.id.tag_ui_plugin_font_applied, true)
    }

    private fun isMonospace(face: Typeface?): Boolean {
        if (face == null) return false
        if (face === Typeface.MONOSPACE) return true
        return face.toString().contains("monospace", ignoreCase = true)
    }

    private fun hookRecycler(rv: RecyclerView) {
        if (rv.getTag(R.id.tag_ui_plugin_font_hook) == true) return
        rv.setTag(R.id.tag_ui_plugin_font_hook, true)
        rv.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    applyDeep(view)
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
    }
}
