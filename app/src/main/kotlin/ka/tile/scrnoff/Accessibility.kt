package ka.tile.scrnoff

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

fun Context.isGlobalAccessibilityServiceEnabled(): Boolean {
    val expected = ComponentName(this, GlobalService::class.java)
    return enabledAccessibilityServices().any { it.matchesGlobalAccessibilityService(expected) }
}

fun Context.enableGlobalAccessibilityService(): Boolean {
    val expected = ComponentName(this, GlobalService::class.java)
    val services = enabledAccessibilityServices()
    if (services.any { it.matchesGlobalAccessibilityService(expected) }) return true

    val serviceName = expected.flattenToString()
    val newValue = if (services.isEmpty()) serviceName else "$serviceName:${services.joinToString(":")}"

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

private fun String.matchesGlobalAccessibilityService(expected: ComponentName): Boolean {
    val parsed = ComponentName.unflattenFromString(this)
    return if (parsed != null) {
        parsed.packageName == expected.packageName && parsed.className == expected.className
    } else {
        equals(expected.flattenToString(), ignoreCase = true) ||
            equals(expected.flattenToShortString(), ignoreCase = true)
    }
}
