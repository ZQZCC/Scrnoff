package ka.tile.scrnoff

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.Process

object AppBroadcasts {
    const val PACKAGE_NAME = "ka.tile.scrnoff"
    const val ACTION_SEND_BINDER = "$PACKAGE_NAME.action.SEND_BINDER"
    const val ACTION_SET_SCREEN_OFF = "$PACKAGE_NAME.action.SET_SCREEN_OFF"
    const val ACTION_EXIT = "$PACKAGE_NAME.action.EXIT"
    const val EXTRA_BINDER = "binder"
    const val EXTRA_STATE = "state"

    fun explicitIntent(context: Context, action: String): Intent =
        Intent(action).setPackage(context.packageName)

    fun screenOffIntent(context: Context, turnOff: Boolean): Intent =
        explicitIntent(context, ACTION_SET_SCREEN_OFF).putExtra(EXTRA_STATE, turnOff)

    fun exitIntent(context: Context): Intent =
        explicitIntent(context, ACTION_EXIT)

    fun binderFilter(): IntentFilter = IntentFilter(ACTION_SEND_BINDER)

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun stickyBinderIntent(context: Context): Intent? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(null, binderFilter(), Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(null, binderFilter())
            }
        }.getOrNull()

    fun systemScreenFilter(): IntentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_USER_PRESENT)
    }

    fun commandFilter(): IntentFilter = IntentFilter().apply {
        addAction(ACTION_SET_SCREEN_OFF)
        addAction(ACTION_EXIT)
    }

    fun isTrustedControllerSender(receiver: BroadcastReceiver, context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return when (receiver.sentFromUid) {
            Process.INVALID_UID,
            Process.ROOT_UID,
            Process.SYSTEM_UID,
            Process.SHELL_UID,
            context.applicationInfo.uid,
                -> true

            else -> false
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
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
