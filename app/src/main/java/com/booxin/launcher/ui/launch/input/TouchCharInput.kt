package com.booxin.launcher.ui.launch.input

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText

/**
 * FCL/Pojav [TouchCharInput]: hidden EditText that drives the system IME and
 * forwards typed characters into GLFW via [GameInput].
 */
class TouchCharInput @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private var internalEdit = false

    init {
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_NULL
            ) {
                hideAndSendEnter()
                true
            } else {
                false
            }
        }
        clearFiller()
        disable()
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        if (internalEdit) return
        val value = text ?: return
        repeat(lengthBefore) { GameInput.sendBackspace() }
        var count = 0
        var i = start
        while (count < lengthAfter && i < value.length) {
            GameInput.sendChar(value[i])
            i++
            count++
        }
        if (value.isEmpty()) clearFiller()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) disable()
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_UP
        ) {
            disable()
        }
        return super.onKeyPreIme(keyCode, event)
    }

    fun switchKeyboardState() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        if (hasFocus() && isEnabled) {
            imm.hideSoftInputFromWindow(windowToken, 0)
            clearFiller()
            disable()
        } else {
            enable()
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hide() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        GameInput.sendKeyTap(GlfwKeys.KEY_ENTER)
        clearFiller()
        disable()
    }

    private fun hideAndSendEnter() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        GameInput.sendKeyTap(GlfwKeys.KEY_ENTER)
        clearFiller()
        disable()
    }

    @SuppressLint("SetTextI18n")
    private fun clearFiller() {
        internalEdit = true
        setText(TEXT_FILLER)
        setSelection(TEXT_FILLER.length)
        internalEdit = false
    }

    private fun enable() {
        isEnabled = true
        isFocusable = true
        isFocusableInTouchMode = true
        visibility = VISIBLE
        requestFocus()
    }

    fun disable() {
        clearFiller()
        visibility = GONE
        clearFocus()
        isEnabled = false
    }

    companion object {
        private const val TEXT_FILLER = "                              "
    }
}
