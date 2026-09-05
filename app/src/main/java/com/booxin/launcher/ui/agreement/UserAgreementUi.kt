package com.booxin.launcher.ui.agreement

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.booxin.launcher.R
import com.booxin.launcher.core.LauncherPrefs
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** First-launch user agreement gate (same terms as the PC launcher). */
object UserAgreementUi {

    fun showIfNeeded(activity: AppCompatActivity, onAccepted: () -> Unit) {
        if (LauncherPrefs.isUserAgreementAccepted()) {
            onAccepted()
            return
        }
        show(activity, onAccepted)
    }

    fun show(activity: AppCompatActivity, onAccepted: () -> Unit) {
        if (activity.isFinishing) return

        val scroll = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_user_agreement, null) as NestedScrollView
        val check = scroll.findViewById<MaterialCheckBox>(R.id.checkUserAgreement)
        val hint = scroll.findViewById<View>(R.id.textUserAgreementHint)

        // Bound height so NestedScrollView can actually scroll inside the dialog.
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
            .coerceAtLeast((360 * activity.resources.displayMetrics.density).toInt())
            .coerceAtMost((activity.resources.displayMetrics.heightPixels * 0.7f).toInt())
        scroll.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            maxHeight
        )

        check.setOnCheckedChangeListener { _, checked ->
            if (checked) hint.visibility = View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.user_agreement_title)
            .setView(scroll)
            .setCancelable(false)
            .setNegativeButton(R.string.user_agreement_decline) { _, _ ->
                exitApp(activity)
            }
            .setPositiveButton(R.string.user_agreement_accept, null)
            .create()

        dialog.setOnShowListener {
            // Keep the button enabled so taps always get feedback (theme makes
            // disabled Material buttons look clickable but swallow clicks).
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!check.isChecked) {
                    hint.visibility = View.VISIBLE
                    scroll.post {
                        scroll.fullScroll(View.FOCUS_DOWN)
                    }
                    return@setOnClickListener
                }
                LauncherPrefs.setUserAgreementAccepted(true)
                dialog.dismiss()
                if (!activity.isFinishing) onAccepted()
            }
        }

        dialog.show()
    }

    private fun exitApp(activity: Activity) {
        activity.finishAffinity()
    }
}
