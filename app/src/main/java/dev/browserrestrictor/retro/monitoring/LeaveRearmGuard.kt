package dev.browserrestrictor.retro.monitoring

/**
 * Keeps a completed Leave action fail-open until the browser has genuinely been left.
 *
 * This prevents the Accessibility event caused by detaching the gate from immediately
 * showing the gate again over the same browser window.
 */
class LeaveRearmGuard {
    var suppressingOverlays: Boolean = false
        private set

    private var observedNonBrowser = false

    fun beginSuppression() {
        suppressingOverlays = true
        observedNonBrowser = false
    }

    /**
     * Returns true only when a later browser entry is allowed to re-arm overlays.
     */
    fun observe(browserActive: Boolean): Boolean {
        if (!suppressingOverlays) return false
        if (!browserActive) {
            observedNonBrowser = true
            return false
        }
        if (!observedNonBrowser) return false
        suppressingOverlays = false
        observedNonBrowser = false
        return true
    }

    fun reset() {
        suppressingOverlays = false
        observedNonBrowser = false
    }
}
