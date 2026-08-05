package dev.browserrestrictor.retro.monitoring

import dev.browserrestrictor.retro.domain.ForegroundClass
import dev.browserrestrictor.retro.domain.SUPPORTED_BROWSERS
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserPackageClassifierTest {
    private val restrictorPackage = "dev.browserrestrictor.retro"

    @Test
    fun allSupportedBrowserPackagesUseTheSharedBrowserClass() {
        assertEquals(
            listOf(
                "com.android.chrome",
                "org.mozilla.firefox",
                "com.microsoft.emmx",
                "com.opera.browser",
            ),
            SUPPORTED_BROWSERS.map { it.packageName },
        )
        SUPPORTED_BROWSERS.forEach { browser ->
            assertEquals(
                browser.name,
                ForegroundClass.BROWSER,
                classifyForegroundPackage(browser.packageName, restrictorPackage),
            )
        }
    }

    @Test
    fun restrictorAndOtherAppsAreNotClassifiedAsBrowsers() {
        assertEquals(
            ForegroundClass.RESTRICTOR,
            classifyForegroundPackage(restrictorPackage, restrictorPackage),
        )
        assertEquals(
            ForegroundClass.OTHER,
            classifyForegroundPackage("com.android.settings", restrictorPackage),
        )
    }
}
