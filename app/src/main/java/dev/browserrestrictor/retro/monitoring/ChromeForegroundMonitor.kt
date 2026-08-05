package dev.browserrestrictor.retro.monitoring

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.browserrestrictor.retro.domain.ForegroundClass
import dev.browserrestrictor.retro.domain.isSupportedBrowserPackage

data class ForegroundDecision(
    val foregroundClass: ForegroundClass,
    val browserActive: Boolean,
    val gateFocused: Boolean,
)

class BrowserForegroundMonitor(
    private val service: AccessibilityService,
) {
    private var latchedBrowserContext = false
    private var gateVisible = false
    private var gateHasFocus = false
    private val exitStabilizer = GateExitStabilizer()

    val hasPendingGateExit: Boolean get() = exitStabilizer.hasPendingCandidate

    fun setGateVisible(visible: Boolean) {
        gateVisible = visible
        if (!visible) {
            gateHasFocus = false
            exitStabilizer.reset()
        }
    }

    fun setGateFocus(focused: Boolean) {
        gateHasFocus = focused
    }

    fun evaluate(
        event: AccessibilityEvent? = null,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): ForegroundDecision {
        val windows = service.windows.orEmpty()
        val focused = windows.firstOrNull { it.isFocused }
        val decision = when {
            gateVisible && latchedBrowserContext -> {
                val exitCandidate = gateExitCandidatePackage(event, focused, windows)
                if (exitStabilizer.observe(exitCandidate, nowElapsedMs)) {
                    ForegroundDecision(ForegroundClass.OTHER, false, false)
                } else {
                    ForegroundDecision(
                        ForegroundClass.RESTRICTOR,
                        true,
                        gateHasFocus || focused?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                    )
                }
            }
            focused?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD -> classifyImeContext(windows)
            focused?.type == AccessibilityWindowInfo.TYPE_APPLICATION -> classifyApplication(focused, event)
            focused != null -> ForegroundDecision(ForegroundClass.OTHER, false, false)
            else -> {
                val activeApp = windows.firstOrNull {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.isFocused
                }
                activeApp?.let { classifyApplication(it, event) } ?: classifyEventFallback(event)
            }
        }

        when {
            decision.foregroundClass == ForegroundClass.BROWSER && decision.browserActive -> {
                latchedBrowserContext = true
            }
            decision.foregroundClass == ForegroundClass.RESTRICTOR && gateVisible && decision.browserActive -> Unit
            else -> latchedBrowserContext = false
        }
        return decision
    }

    fun confirmPendingGateExit(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): ForegroundDecision? {
        if (!gateVisible || !latchedBrowserContext || !exitStabilizer.hasPendingCandidate) {
            return null
        }
        val windows = service.windows.orEmpty()
        val candidateStillForeground = activeApplicationPackage(windows)
            ?.let(::isGateExitPackage) == true
        if (!exitStabilizer.confirmAfterDelay(nowElapsedMs, candidateStillForeground)) {
            return null
        }
        return forceExit()
    }

    fun forceExit(): ForegroundDecision {
        exitStabilizer.reset()
        latchedBrowserContext = false
        gateHasFocus = false
        return ForegroundDecision(ForegroundClass.OTHER, false, false)
    }

    private fun gateExitCandidatePackage(
        event: AccessibilityEvent?,
        focused: AccessibilityWindowInfo?,
        windows: List<AccessibilityWindowInfo>,
    ): String? {
        if (focused?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return null
        val eventPackage = event
            ?.takeIf { it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }
            ?.packageName
            ?.toString()
            ?.takeIf(::isGateExitPackage)
        if (eventPackage != null) return eventPackage
        return activeApplicationPackage(windows)?.takeIf(::isGateExitPackage)
    }

    private fun classifyImeContext(windows: List<AccessibilityWindowInfo>): ForegroundDecision {
        val underlying = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: return ForegroundDecision(ForegroundClass.OTHER, false, false)
        val owner = ownerClass(underlying) ?: ForegroundClass.OTHER
        return if (owner == ForegroundClass.BROWSER) {
            ForegroundDecision(ForegroundClass.BROWSER, true, false)
        } else {
            ForegroundDecision(owner, false, false)
        }
    }

    private fun classifyApplication(
        window: AccessibilityWindowInfo,
        event: AccessibilityEvent?,
    ): ForegroundDecision {
        val owner = ownerClass(window) ?: packageClass(event?.packageName?.toString())
        return if (owner == ForegroundClass.BROWSER && window.isFocused && window.isActive) {
            ForegroundDecision(ForegroundClass.BROWSER, true, false)
        } else {
            ForegroundDecision(owner, false, false)
        }
    }

    private fun classifyEventFallback(event: AccessibilityEvent?): ForegroundDecision {
        val owner = packageClass(event?.packageName?.toString())
        return if (owner == ForegroundClass.BROWSER) {
            ForegroundDecision(owner, true, false)
        } else {
            ForegroundDecision(owner, false, false)
        }
    }

    private fun packageClass(packageName: String?): ForegroundClass =
        classifyForegroundPackage(packageName, service.packageName)

    private fun ownerClass(window: AccessibilityWindowInfo): ForegroundClass? {
        val packageName = ownerPackage(window) ?: return null
        return packageClass(packageName)
    }

    private fun activeApplicationPackage(
        windows: List<AccessibilityWindowInfo>,
    ): String? = windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
    }?.let(::ownerPackage)

    private fun ownerPackage(window: AccessibilityWindowInfo): String? {
        val root: AccessibilityNodeInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) window.getRoot(0) else window.root
        } catch (_: Exception) {
            null
        } ?: return null

        val packageName = try {
            // This is the only node property the product reads. The hierarchy is never traversed.
            root.packageName?.toString()
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
        return packageName
    }

    private fun isGateExitPackage(packageName: String): Boolean =
        !isSupportedBrowserPackage(packageName) &&
            packageName != service.packageName &&
            packageName != SYSTEM_UI_PACKAGE

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

internal fun classifyForegroundPackage(
    packageName: String?,
    restrictorPackageName: String,
): ForegroundClass = when {
    isSupportedBrowserPackage(packageName) -> ForegroundClass.BROWSER
    packageName == restrictorPackageName -> ForegroundClass.RESTRICTOR
    else -> ForegroundClass.OTHER
}
