package dev.browserrestrictor.retro.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.browserrestrictor.retro.data.RestrictorRepository
import dev.browserrestrictor.retro.ui.gate.BadgeContent
import dev.browserrestrictor.retro.ui.theme.RestrictorTheme

class BadgeOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val repository: RestrictorRepository,
    private val onAttachFailure: (Throwable) -> Unit,
) {
    private var view: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val owner = OverlayLifecycleOwner()
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            owner.attachTo(this)
            setContent {
                val snapshot by repository.snapshot.collectAsState()
                RestrictorTheme(snapshot.settings.theme) {
                    BadgeContent(snapshot, onDrag = ::moveBy)
                }
            }
        }
        composeView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                clampPosition()
            }
        }
        val params = badgeLayoutParams()
        try {
            windowManager.addView(composeView, params)
            view = composeView
            lifecycleOwner = owner
            layoutParams = params
        } catch (error: Throwable) {
            owner.destroy()
            composeView.disposeComposition()
            onAttachFailure(error)
        }
    }

    fun hide() {
        val current = view ?: return
        try {
            windowManager.removeViewImmediate(current)
        } catch (_: IllegalArgumentException) {
            // Already removed by the platform.
        }
        current.disposeComposition()
        lifecycleOwner?.destroy()
        view = null
        lifecycleOwner = null
        layoutParams = null
    }

    fun refreshForConfigurationChange() {
        if (!isShowing) return
        clampPosition()
    }

    private fun badgeLayoutParams() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 0
        y = (context.resources.displayMetrics.heightPixels * 0.29f).toInt()
        title = "Internet Restrictor browser status"
    }

    private fun moveBy(deltaY: Float) {
        val currentView = view ?: return
        val params = layoutParams ?: return
        params.x = 0
        params.y += deltaY.toInt()
        clampPosition(currentView, params)
    }

    private fun clampPosition() {
        val currentView = view ?: return
        val params = layoutParams ?: return
        clampPosition(currentView, params)
    }

    private fun clampPosition(currentView: ComposeView, params: WindowManager.LayoutParams) {
        val display = context.resources.displayMetrics
        params.x = 0
        params.y = params.y.coerceIn(0, (display.heightPixels - currentView.height).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(currentView, params)
        } catch (_: IllegalArgumentException) {
            // The overlay was detached while an interaction was finishing.
        }
    }
}
