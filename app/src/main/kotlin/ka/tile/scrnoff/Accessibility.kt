package ka.tile.scrnoff

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

fun Context.isGlobalAccessibilityServiceEnabled(): Boolean =
    enabledAccessibilityServices().any { it.matchesGlobalAccessibilityService(this) }

fun Context.enableGlobalAccessibilityService(): Boolean {
    if (isGlobalAccessibilityServiceEnabled()) return true

    val serviceName = globalAccessibilityServiceName()
    val services = enabledAccessibilityServices()
    val newValue =
        if (services.any { it.matchesGlobalAccessibilityService(this) }) {
            services.joinToString(":")
        } else {
            (listOf(serviceName) + services).joinToString(":")
        }

    return runCatching {
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            newValue,
        )
    }.isSuccess
}

fun Context.globalAccessibilitySettingsIntent(): Intent {
    val serviceName = globalAccessibilityServiceName()
    val args = Bundle().apply {
        putString(":settings:fragment_args_key", serviceName)
    }
    return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .putExtra(":settings:fragment_args_key", serviceName)
        .putExtra(":settings:show_fragment_args", args)
}

fun Context.globalAccessibilityServiceName(): String =
    ComponentName(this, GlobalService::class.java).flattenToString()

private fun Context.enabledAccessibilityServices(): List<String> =
    Settings.Secure.getString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    )
        ?.split(':')
        ?.filter { it.isNotBlank() }
        .orEmpty()

private fun String.matchesGlobalAccessibilityService(context: Context): Boolean {
    val expected = ComponentName(context, GlobalService::class.java)
    val parsed = ComponentName.unflattenFromString(this)
    return if (parsed != null) {
        parsed.packageName == expected.packageName && parsed.className == expected.className
    } else {
        equals(expected.flattenToString(), ignoreCase = true) ||
            equals(expected.flattenToShortString(), ignoreCase = true)
    }
}
