package ka.tile.scrnoff

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

@TargetApi(Build.VERSION_CODES.N)
@SuppressLint("StartActivityAndCollapseDeprecated", "UseRequiresApi")
class ScreenTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (qsTile == null) return

        if (isGlobalAccessibilityServiceEnabled()) {
            sendBroadcast(AppBroadcasts.screenOffIntent(this, turnOff = true))
            return
        }

        val intent = Intent(this, ScrOff::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
