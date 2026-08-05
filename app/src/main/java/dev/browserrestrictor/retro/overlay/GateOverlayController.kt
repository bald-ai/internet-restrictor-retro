package dev.browserrestrictor.retro.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.browserrestrictor.retro.data.RestrictorRepository
import dev.browserrestrictor.retro.domain.ThemePreference
import dev.browserrestrictor.retro.ui.gate.GateActions
import dev.browserrestrictor.retro.ui.gate.GateScreen
import dev.browserrestrictor.retro.ui.theme.RestrictorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class GateOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val repository: RestrictorRepository,
    private val scope: CoroutineScope,
    private val onLeaveBrowser: () -> Unit,
    private val onFocusChanged: (Boolean) -> Unit,
    private val onAttachFailure: (Throwable) -> Unit,
) {
    private var view: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var focusListener: android.view.ViewTreeObserver.OnWindowFocusChangeListener? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val owner = OverlayLifecycleOwner()
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Internet Restrictor browser pause screen"
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    onLeaveBrowser()
                    true
                } else {
                    false
                }
            }
            owner.attachTo(this)
            setContent {
                val snapshot by repository.snapshot.collectAsState()
                SideEffect {
                    val lightBars = snapshot.settings.theme == ThemePreference.LIGHT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        windowInsetsController?.setSystemBarsAppearance(
                            if (lightBars) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        systemUiVisibility = if (lightBars) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
                    }
                }
                RestrictorTheme(snapshot.settings.theme) {
                    GateScreen(
                        snapshot = snapshot,
                        actions = GateActions(
                            onStartSession = { minutes ->
                                scope.launch { repository.startWait(snapshot.stateRevision, minutes) }
                            },
                            onEmergencySkip = {
                                scope.launch {
                                    repository.emergencySkip(
                                        snapshot.stateRevision,
                                        snapshot.pendingWaitId,
                                    )
                                }
                            },
                            onTryAgain = {
                                scope.launch {
                                    repository.tryAgain(
                                        snapshot.stateRevision,
                                        snapshot.gateSessionId,
                                        snapshot.outOfTimeLatchToken,
                                    )
                                }
                            },
                            onLeaveBrowser = onLeaveBrowser,
                        ),
                    )
                }
            }
        }
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            onFocusChanged(focused)
        }
        composeView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        try {
            windowManager.addView(composeView, gateLayoutParams())
            view = composeView
            lifecycleOwner = owner
            focusListener = listener
            composeView.requestFocus()
        } catch (error: Throwable) {
            owner.destroy()
            composeView.disposeComposition()
            onAttachFailure(error)
        }
    }

    fun hide(reportFocusLoss: Boolean = true) {
        val current = view ?: return
        focusListener?.let { listener ->
            if (current.viewTreeObserver.isAlive) {
                current.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
            }
        }
        try {
            windowManager.removeViewImmediate(current)
        } catch (_: IllegalArgumentException) {
            // Already removed by the platform.
        }
        current.disposeComposition()
        lifecycleOwner?.destroy()
        view = null
        lifecycleOwner = null
        focusListener = null
        if (reportFocusLoss) onFocusChanged(false)
    }

    fun recreateForConfigurationChange() {
        if (!isShowing) return
        hide(reportFocusLoss = false)
        show()
    }

    private fun gateLayoutParams() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.FILL
        title = "Internet Restrictor browser gate"
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }
}
