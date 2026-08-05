package dev.browserrestrictor.retro.monitoring

/**
 * Prevents a single noisy Accessibility window event from releasing the browser gate.
 *
 * A candidate must be observed twice and remain unchanged for [stableForMs]. The service
 * also performs a delayed foreground re-check so a legitimate app switch does not depend
 * on Android delivering a third Accessibility event.
 */
class GateExitStabilizer(
    private val stableForMs: Long = DEFAULT_STABLE_FOR_MS,
) {
    private var candidatePackage: String? = null
    private var firstObservedAtElapsedMs = 0L
    private var observations = 0

    val hasPendingCandidate: Boolean get() = candidatePackage != null

    fun observe(packageName: String?, nowElapsedMs: Long): Boolean {
        if (packageName == null) {
            reset()
            return false
        }
        if (packageName != candidatePackage) {
            candidatePackage = packageName
            firstObservedAtElapsedMs = nowElapsedMs
            observations = 1
            return false
        }
        observations += 1
        return observations >= REQUIRED_OBSERVATIONS &&
            nowElapsedMs - firstObservedAtElapsedMs >= stableForMs
    }

    fun confirmAfterDelay(
        nowElapsedMs: Long,
        candidateStillForeground: Boolean,
    ): Boolean {
        val pending = candidatePackage ?: return false
        if (!candidateStillForeground) {
            reset()
            return false
        }
        return observe(pending, nowElapsedMs)
    }

    fun reset() {
        candidatePackage = null
        firstObservedAtElapsedMs = 0L
        observations = 0
    }

    companion object {
        const val DEFAULT_STABLE_FOR_MS = 300L
        private const val REQUIRED_OBSERVATIONS = 2
    }
}
