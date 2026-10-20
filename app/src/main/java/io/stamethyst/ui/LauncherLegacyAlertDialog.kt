package io.stamethyst.ui

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AlertDialog

/** Bridge for View-based callers: content animation, not a WindowManager transition. */
internal class LauncherLegacyAlertDialog(context: Context) : AlertDialog(context) {
    private var closing = false

    override fun show() {
        closing = false
        super.show()
        window?.setWindowAnimations(0)
        val panel = animatedPanel() ?: return
        panel.alpha = 0f
        panel.scaleX = .98f
        panel.scaleY = .98f
        panel.translationY = 12f * context.resources.displayMetrics.density
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(260).setInterpolator(PathInterpolator(.2f, .8f, .2f, 1f)).start()
    }

    override fun dismiss() {
        if (closing) return
        val panel = animatedPanel()
        if (!isShowing || panel == null || !panel.isAttachedToWindow) {
            super.dismiss()
            return
        }
        closing = true
        panel.animate().cancel()
        panel.animate().alpha(0f).scaleX(.99f).scaleY(.99f)
            .translationY(4f * context.resources.displayMetrics.density)
            .setDuration(160).setInterpolator(PathInterpolator(.4f, 0f, 1f, 1f))
            .withEndAction { super.dismiss() }.start()
    }

    private fun animatedPanel(): View? = window?.decorView?.findViewById<ViewGroup>(android.R.id.content)

    class Builder(private val context: Context) {
        private var title: CharSequence? = null
        private var message: CharSequence? = null
        private var view: View? = null
        private var cancelable = true
        private var onDismiss: DialogInterface.OnDismissListener? = null
        private val buttons = linkedMapOf<Int, Pair<CharSequence, DialogInterface.OnClickListener?>>()

        fun setTitle(value: Int) = setTitle(context.getText(value))
        fun setTitle(value: CharSequence?) = apply { title = value }
        fun setMessage(value: Int) = setMessage(context.getText(value))
        fun setMessage(value: CharSequence?) = apply { message = value }
        fun setView(value: View) = apply { view = value }
        fun setCancelable(value: Boolean) = apply { cancelable = value }
        fun setOnDismissListener(value: DialogInterface.OnDismissListener?) = apply { onDismiss = value }
        fun setPositiveButton(text: Int, listener: DialogInterface.OnClickListener?) =
            setPositiveButton(context.getText(text), listener)
        fun setPositiveButton(text: CharSequence, listener: DialogInterface.OnClickListener?) =
            apply { buttons[BUTTON_POSITIVE] = text to listener }
        fun setNegativeButton(text: Int, listener: DialogInterface.OnClickListener?) =
            apply { buttons[BUTTON_NEGATIVE] = context.getText(text) to listener }
        fun create(): LauncherLegacyAlertDialog = LauncherLegacyAlertDialog(context).apply {
            setTitle(title)
            message?.let { setMessage(it) }
            view?.let { setView(it) }
            setCancelable(cancelable)
            setCanceledOnTouchOutside(cancelable)
            setOnDismissListener(onDismiss)
            buttons.forEach { (which, value) -> setButton(which, value.first, value.second) }
        }
        fun show(): LauncherLegacyAlertDialog = create().apply { show() }
    }
}
