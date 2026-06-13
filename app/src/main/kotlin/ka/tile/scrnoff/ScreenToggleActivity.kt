package ka.tile.scrnoff

import android.app.Activity
import android.os.Bundle

abstract class ScreenToggleActivity(
    private val turnOff: Boolean,
) : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_NoDisplay)
        super.onCreate(savedInstanceState)

        if (isGlobalAccessibilityServiceEnabled()) {
            sendBroadcast(AppBroadcasts.screenOffIntent(this, turnOff))
        } else {
            enableAccessibilityOrOpenSettings()
        }
    }

    override fun onResume() {
        finish()
        super.onResume()
    }

    private fun enableAccessibilityOrOpenSettings() {
        if (!enableGlobalAccessibilityService()) {
            startActivity(globalAccessibilitySettingsIntent())
        }
    }
}

class ScrOff : ScreenToggleActivity(turnOff = true)

class ScrOn : ScreenToggleActivity(turnOff = false)
