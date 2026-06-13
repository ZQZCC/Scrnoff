package ka.tile.scrnoff

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder

object AppBroadcasts {
    const val PACKAGE_NAME = "ka.tile.scrnoff"
    const val ACTION_SEND_BINDER = "intent.screenoff.sendBinder"
    const val ACTION_SET_SCREEN_OFF = "action.ScrOff"
    const val ACTION_REFRESH_FLOATING_WINDOW = "intent.screenoff.refreshFloatingWindow"
    const val ACTION_EXIT = "intent.screenoff.exit"
    const val EXTRA_BINDER = "binder"
    const val EXTRA_STATE = "state"
    const val EXTRA_FLOAT_ENABLED = "floatEnabled"

    fun explicitIntent(context: Context, action: String): Intent =
        Intent(action).setPackage(context.packageName)

    fun screenOffIntent(context: Context, turnOff: Boolean): Intent =
        explicitIntent(context, ACTION_SET_SCREEN_OFF).putExtra(EXTRA_STATE, turnOff)

    fun exitIntent(context: Context): Intent =
        explicitIntent(context, ACTION_EXIT)

    fun refreshFloatingWindowIntent(context: Context, enabled: Boolean): Intent =
        explicitIntent(context, ACTION_REFRESH_FLOATING_WINDOW)
            .putExtra(EXTRA_FLOAT_ENABLED, enabled)

    fun screenServiceFilter(): IntentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_USER_PRESENT)
        addAction(ACTION_SET_SCREEN_OFF)
        addAction(ACTION_SEND_BINDER)
        addAction(ACTION_REFRESH_FLOATING_WINDOW)
        addAction(ACTION_EXIT)
    }

    fun registerReceiver(
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        exported: Boolean,
    ): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                filter,
                if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }

    fun aliveBinderFrom(intent: Intent?): IBinder? {
        val container = binderContainerFrom(intent) ?: return null
        return container.binder?.takeIf(IBinder::pingBinder)
    }

    private fun binderContainerFrom(intent: Intent?): BinderContainer? {
        intent ?: return null
        return runCatching {
            intent.setExtrasClassLoader(BinderContainer::class.java.classLoader)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_BINDER, BinderContainer::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_BINDER)
            }
        }.getOrNull()
    }
}
