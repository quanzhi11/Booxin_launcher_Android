package com.booxin.launcher.core.uiplugin

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.booxin.launcher.R
import com.booxin.launcher.ui.GlassBackground
import com.google.android.material.button.MaterialButton
import java.util.concurrent.atomic.AtomicReference

/**
 * Applies customTheme packs: brand, nav labels, colors, wallpaper, welcome helpers.
 */
object UiPluginTheme {

    private val cache = AtomicReference<UiPluginResolvedTheme?>(null)

    fun invalidate() {
        // 安装/启用常在后台线程调用：这里只清缓存，勿直接碰 View。
        cache.set(null)
    }

    fun current(): UiPluginResolvedTheme? {
        cache.get()?.let { return it }
        val resolved = UiPluginManager.resolveActiveTheme()
        cache.set(resolved)
        return resolved
    }

    fun installHost(activity: AppCompatActivity) {
        applyTo(activity)
        val tagKey = R.id.tag_ui_plugin_theme_host
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

    fun applyTo(activity: Activity?) {
        if (activity == null) return
        val run = Runnable {
            applyChrome(activity)
            applyDeep(activity.window?.decorView)
            GlassBackground.notifyPluginBackgroundChanged()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run()
        } else {
            Handler(Looper.getMainLooper()).post(run)
        }
    }

    fun applyDeep(root: View?) {
        if (root == null) return
        val theme = current()
        walk(root, theme)
    }

    fun welcomeText(
        hourOfDay: Int,
        defaultWelcome: String,
        defaultMorning: String,
        defaultNoon: String,
        defaultEvening: String,
        useTimeGreeting: Boolean
    ): String {
        val theme = current()?.spec
        if (theme != null) {
            if (!useTimeGreeting && theme.welcome.isNotBlank()) return theme.welcome
            if (useTimeGreeting ||
                theme.welcomeMorning.isNotBlank() ||
                theme.welcomeNoon.isNotBlank() ||
                theme.welcomeEvening.isNotBlank()
            ) {
                return when {
                    hourOfDay < 11 -> theme.welcomeMorning.ifBlank {
                        theme.welcome.ifBlank { defaultMorning }
                    }
                    hourOfDay < 17 -> theme.welcomeNoon.ifBlank {
                        theme.welcome.ifBlank { defaultNoon }
                    }
                    else -> theme.welcomeEvening.ifBlank {
                        theme.welcome.ifBlank { defaultEvening }
                    }
                }
            }
            if (theme.welcome.isNotBlank()) return theme.welcome
        }
        return if (useTimeGreeting) {
            when {
                hourOfDay < 11 -> defaultMorning
                hourOfDay < 17 -> defaultNoon
                else -> defaultEvening
            }
        } else {
            defaultWelcome
        }
    }

    private fun applyChrome(activity: Activity) {
        val theme = current()
        val brandIcon = activity.findViewById<ImageView?>(R.id.imageChromeBrand)
        val brandName = activity.findViewById<TextView?>(R.id.textChromeBrand)
        val navHome = activity.findViewById<MaterialButton?>(R.id.nav_home)
        val navVersions = activity.findViewById<MaterialButton?>(R.id.nav_versions)
        val navCommunity = activity.findViewById<MaterialButton?>(R.id.nav_community)
        val navMultiplayer = activity.findViewById<MaterialButton?>(R.id.nav_multiplayer)
        val navAi = activity.findViewById<MaterialButton?>(R.id.nav_ai)
        val navPlugins = activity.findViewById<MaterialButton?>(R.id.nav_plugin_store)
        val navSettings = activity.findViewById<MaterialButton?>(R.id.nav_settings)

        if (theme == null) {
            brandIcon?.setImageResource(R.mipmap.ic_launcher_round)
            brandName?.setText(R.string.brand_name)
            navHome?.setText(R.string.nav_home)
            navVersions?.setText(R.string.nav_versions)
            navCommunity?.setText(R.string.nav_community)
            navMultiplayer?.setText(R.string.nav_multiplayer)
            navAi?.setText(R.string.nav_ai)
            navPlugins?.setText(R.string.nav_plugin_store)
            navSettings?.setText(R.string.nav_settings)
            return
        }

        val icon = theme.iconFile ?: UiPluginManager.resolveCustomLauncherIconFile()
        if (icon != null) {
            brandIcon?.load(icon) {
                placeholder(R.mipmap.ic_launcher_round)
                error(R.mipmap.ic_launcher_round)
            }
        } else {
            brandIcon?.setImageResource(R.mipmap.ic_launcher_round)
        }

        val name = theme.spec.brandName
        if (name.isNotBlank()) brandName?.text = name
        else brandName?.setText(R.string.brand_name)

        val nav = theme.spec.nav
        if (nav.home.isNotBlank()) navHome?.text = nav.home
        if (nav.versions.isNotBlank()) navVersions?.text = nav.versions
        if (nav.community.isNotBlank()) navCommunity?.text = nav.community
        if (nav.multiplayer.isNotBlank()) navMultiplayer?.text = nav.multiplayer
        if (nav.ai.isNotBlank()) navAi?.text = nav.ai
        if (nav.pluginStore.isNotBlank()) navPlugins?.text = nav.pluginStore
        if (nav.settings.isNotBlank()) navSettings?.text = nav.settings

        val colors = theme.spec.colors
        brandName?.let { tv ->
            colors.text?.let { tv.setTextColor(it) }
        }
        listOfNotNull(
            navHome, navVersions, navCommunity, navMultiplayer, navAi, navPlugins, navSettings
        ).forEach { tintNavButton(it, colors) }
    }

    private fun tintNavButton(button: MaterialButton, colors: UiPluginThemeColors) {
        val selected = colors.navSelected ?: colors.text
        val unselected = colors.navUnselected ?: colors.textSecondary ?: selected
        if (selected != null && unselected != null) {
            button.setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(selected, unselected)
                )
            )
        } else if (selected != null) {
            button.setTextColor(selected)
        }
        colors.glassPrimary?.let { button.backgroundTintList = ColorStateList.valueOf(it) }
        colors.rim?.let { button.strokeColor = ColorStateList.valueOf(it) }
    }

    private fun walk(view: View, theme: UiPluginResolvedTheme?) {
        when (view) {
            is MaterialButton -> applyButton(view, theme)
            is TextView -> applyText(view, theme)
            is RecyclerView -> {
                hookRecycler(view)
                for (i in 0 until view.childCount) walk(view.getChildAt(i), theme)
            }
            is ViewGroup -> {
                applySurface(view, theme)
                for (child in view.children) walk(child, theme)
            }
        }
    }

    private fun applyButton(button: MaterialButton, theme: UiPluginResolvedTheme?) {
        if (button.getTag(R.id.tag_ui_plugin_keep_color) == true) return
        val colors = theme?.spec?.colors
        if (colors == null) return
        val primary = colors.glassPrimary ?: colors.primary
        primary?.let {
            // Skip top-nav style buttons already handled in chrome, but re-tint is fine.
            if (button.id !in TOP_NAV_IDS) {
                button.backgroundTintList = ColorStateList.valueOf(it)
            }
        }
        colors.accent?.let { accent ->
            if (button.id !in TOP_NAV_IDS && button.strokeWidth > 0) {
                // outlined / secondary: prefer accent rim if rim missing
                button.strokeColor = ColorStateList.valueOf(colors.rim ?: accent)
            }
        }
        colors.rim?.let {
            if (button.strokeWidth > 0 && button.id !in TOP_NAV_IDS) {
                button.strokeColor = ColorStateList.valueOf(it)
            }
        }
        if (button.id !in TOP_NAV_IDS) {
            (colors.text ?: colors.navSelected)?.let { button.setTextColor(it) }
        }
        button.setTag(R.id.tag_ui_plugin_color_applied, true)
    }

    private fun applyText(tv: TextView, theme: UiPluginResolvedTheme?) {
        if (tv is MaterialButton) return
        if (tv.getTag(R.id.tag_ui_plugin_keep_color) == true) return
        val colors = theme?.spec?.colors ?: return
        if (theme.spec.applyAllText) {
            val color = when {
                tv.textSize < sp(tv, 13f) -> colors.textSecondary ?: colors.text
                else -> colors.text ?: colors.textSecondary
            }
            color?.let {
                tv.setTextColor(it)
                tv.setTag(R.id.tag_ui_plugin_color_applied, true)
            }
        }
    }

    private fun applySurface(group: ViewGroup, theme: UiPluginResolvedTheme?) {
        val surface = theme?.spec?.colors?.surface ?: return
        if (group.getTag(R.id.tag_ui_plugin_keep_color) == true) return
        val bg = group.background
        if (bg is GradientDrawable) {
            bg.setColor(surface)
            group.setTag(R.id.tag_ui_plugin_color_applied, true)
        }
    }

    private fun hookRecycler(rv: RecyclerView) {
        if (rv.getTag(R.id.tag_ui_plugin_theme_hook) == true) return
        rv.setTag(R.id.tag_ui_plugin_theme_hook, true)
        rv.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    applyDeep(view)
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
    }

    private fun sp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.scaledDensity

    private val TOP_NAV_IDS = setOf(
        R.id.nav_home,
        R.id.nav_versions,
        R.id.nav_community,
        R.id.nav_multiplayer,
        R.id.nav_ai,
        R.id.nav_plugin_store,
        R.id.nav_settings
    )
}
