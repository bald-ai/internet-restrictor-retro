package dev.browserrestrictor.retro.overlay

/**
 * The single owner of both Accessibility overlays.
 *
 * Controllers only deal with WindowManager mechanics. This coordinator owns which overlay
 * is allowed to exist, including the bounded Leave transition.
 */
class OverlayCoordinator(
    private val gateController: GateOverlayController,
    private val badgeController: BadgeOverlayController,
    private val onGateVisibilityChanged: (Boolean) -> Unit,
) {
    enum class Target {
        HIDDEN,
        GATE,
        BADGE,
        LEAVING,
    }

    var target: Target = Target.HIDDEN
        private set

    val isLeaving: Boolean get() = target == Target.LEAVING

    fun render(requested: Target) {
        if (isLeaving) return
        applyTarget(requested)
    }

    fun beginLeaving() {
        if (isLeaving) return
        target = Target.LEAVING
    }

    fun hideWhileLeaving() {
        if (!isLeaving) return
        onGateVisibilityChanged(false)
        gateController.hide(reportFocusLoss = false)
        badgeController.hide()
    }

    fun finishLeaving() {
        hideWhileLeaving()
        target = Target.HIDDEN
    }

    fun failOpen() {
        onGateVisibilityChanged(false)
        gateController.hide(reportFocusLoss = false)
        badgeController.hide()
        target = Target.HIDDEN
    }

    fun onConfigurationChanged() {
        // Compose receives the new Configuration without detaching the full-screen gate.
        // The badge only needs its existing position clamped to the new display bounds.
        badgeController.refreshForConfigurationChange()
    }

    private fun applyTarget(requested: Target) {
        if (requested == target) return
        target = requested
        when (requested) {
            Target.GATE -> {
                badgeController.hide()
                onGateVisibilityChanged(true)
                gateController.show()
            }
            Target.BADGE -> {
                onGateVisibilityChanged(false)
                gateController.hide()
                badgeController.show()
            }
            Target.HIDDEN -> {
                onGateVisibilityChanged(false)
                gateController.hide()
                badgeController.hide()
            }
            Target.LEAVING -> Unit
        }
    }
}
