package dev.browserrestrictor.retro.monitoring

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import dev.browserrestrictor.retro.domain.SUPPORTED_BROWSER_PACKAGES

data class DeviceAccessSnapshot(
    val accessibilityEnabled: Boolean,
    val supportedBrowserInstalled: Boolean,
    val advancedProtectionEnabled: Boolean,
)

class DeviceAccessChecker(private val context: Context) {
    fun read(): DeviceAccessSnapshot = DeviceAccessSnapshot(
        accessibilityEnabled = isAccessibilityEnabled(),
        supportedBrowserInstalled = isSupportedBrowserInstalled(),
        advancedProtectionEnabled = isAdvancedProtectionEnabled(),
    )

    fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(context, ChromeAccessibilityService::class.java)
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return raw.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }

    fun isSupportedBrowserInstalled(): Boolean = SUPPORTED_BROWSER_PACKAGES.any(::isPackageInstalled)

    private fun isPackageInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun isAdvancedProtectionEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return try {
            context.getSystemService(AdvancedProtectionManager::class.java)
                ?.isAdvancedProtectionEnabled == true
        } catch (_: Exception) {
            false
        }
    }
}
