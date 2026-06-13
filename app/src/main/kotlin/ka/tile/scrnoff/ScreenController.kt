package ka.tile.scrnoff

import android.app.IApplicationThread
import android.content.IIntentReceiver
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import java.lang.reflect.Method
import kotlin.concurrent.thread
import kotlin.system.exitProcess

object ScreenController {
    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2

    private val booleanType = Boolean::class.javaPrimitiveType!!
    private val intType = Int::class.javaPrimitiveType!!

    private val surfaceControlClass: Class<*> = Class.forName("android.view.SurfaceControl")
    @Volatile private var screenState = IScreenOff.STATE_ON
    @Volatile private var isBroadcastSent = false
    @Volatile private var stickyIntent: Intent? = null
    @Volatile private var activityManager: Any? = null
    private var getBuiltInDisplayMethod: Method? = null
    private var setDisplayPowerModeMethod: Method? = null

    private val useDisplayControl: Boolean = Build.VERSION.SDK_INT >= 34

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }

        val uid = android.os.Process.myUid()
        if (uid != 0 && uid != 2000) {
            exitProcess(-1)
        }

        isBroadcastSent = sendBinderToAppByStickyBroadcast()
        if (!isBroadcastSent) {
            exitProcess(-1)
        }

        Runtime.getRuntime().addShutdownHook(
            thread(start = false) { removeBroadcastIfSent() },
        )

        Looper.loop()

        removeBroadcastIfSent()
        exitProcess(0)
    }

    fun getBuiltInDisplay(): IBinder? =
        runCatching {
            if (useDisplayControl) {
                DisplayControl.getPhysicalDisplayIds()
                    ?.asSequence()
                    ?.mapNotNull(DisplayControl::getPhysicalDisplayToken)
                    ?.firstOrNull()
                    ?.let { return@runCatching it }
            }

            val method = builtInDisplayMethod()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                method.invoke(null, 0) as? IBinder
            } else {
                method.invoke(null) as? IBinder
            }
        }.getOrNull()

    fun setDisplayPowerMode(displayToken: IBinder?, mode: Int): Boolean {
        displayToken ?: return false
        return runCatching {
            displayPowerModeMethod().invoke(null, displayToken, mode)
            true
        }.getOrElse { false }
    }

    private fun builtInDisplayMethod(): Method =
        getBuiltInDisplayMethod ?: if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            surfaceControlClass.getMethod("getBuiltInDisplay", intType)
        } else {
            surfaceControlClass.getMethod("getInternalDisplayToken")
        }.also { getBuiltInDisplayMethod = it }

    private fun displayPowerModeMethod(): Method =
        setDisplayPowerModeMethod ?: surfaceControlClass
            .getMethod("setDisplayPowerMode", IBinder::class.java, intType)
            .also { setDisplayPowerModeMethod = it }

    private fun sendBinderToAppByStickyBroadcast(): Boolean =
        runCatching {
            val binder = object : IScreenOff.Stub() {
                @Throws(RemoteException::class)
                override fun setPowerMode(turnOff: Boolean) {
                    if (turnOff && screenState == IScreenOff.STATE_ON) {
                        if (setDisplayPowerMode(getBuiltInDisplay(), POWER_MODE_OFF)) {
                            screenState = IScreenOff.STATE_SPECIAL
                        }
                    } else if (!turnOff && screenState == IScreenOff.STATE_SPECIAL) {
                        if (setDisplayPowerMode(getBuiltInDisplay(), POWER_MODE_NORMAL)) {
                            screenState = IScreenOff.STATE_ON
                        }
                    }
                }

                @Throws(RemoteException::class)
                override fun getNowScreenState(): Int = screenState

                @Throws(RemoteException::class)
                override fun updateNowScreenState(isScreenOn: Boolean) {
                    screenState = if (isScreenOn) IScreenOff.STATE_ON else IScreenOff.STATE_OFF
                }

                @Throws(RemoteException::class)
                override fun closeAndExit() {
                    removeBroadcastIfSent()
                    exitProcess(0)
                }
            }

            stickyIntent = Intent(AppBroadcasts.ACTION_SEND_BINDER)
                .setPackage(AppBroadcasts.PACKAGE_NAME)
                .putExtra(AppBroadcasts.EXTRA_BINDER, BinderContainer(binder))

            activityManager = obtainActivityManager()
            sendStickyBroadcast(requireNotNull(activityManager), requireNotNull(stickyIntent))
        }.isSuccess

    private fun obtainActivityManager(): Any =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val activityService = serviceManager
                .getDeclaredMethod("getService", String::class.java)
                .invoke(null, "activity") as IBinder
            Class.forName("android.app.IActivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, activityService) as Any
        } else {
            val activityManagerNative = Class.forName("android.app.ActivityManagerNative")
            activityManagerNative.getMethod("getDefault").invoke(activityManagerNative) as Any
        }

    private fun sendStickyBroadcast(activityManager: Any, intent: Intent) {
        val activityManagerClass = Class.forName("android.app.IActivityManager")
        val broadcastMethod = runCatching {
            activityManagerClass.getDeclaredMethod(
                "broadcastIntent",
                IApplicationThread::class.java,
                Intent::class.java,
                String::class.java,
                IIntentReceiver::class.java,
                intType,
                String::class.java,
                Bundle::class.java,
                Array<String>::class.java,
                intType,
                Bundle::class.java,
                booleanType,
                booleanType,
                intType,
            )
        }.getOrElse {
            activityManagerClass.getDeclaredMethod(
                "broadcastIntentWithFeature",
                IApplicationThread::class.java,
                String::class.java,
                Intent::class.java,
                String::class.java,
                IIntentReceiver::class.java,
                intType,
                String::class.java,
                Bundle::class.java,
                Array<String>::class.java,
                Array<String>::class.java,
                Array<String>::class.java,
                intType,
                Bundle::class.java,
                booleanType,
                booleanType,
                intType,
            )
        }

        if (broadcastMethod.parameterTypes.size == 13) {
            broadcastMethod.invoke(
                activityManager,
                null,
                intent,
                null,
                null,
                -1,
                null,
                null,
                null,
                0,
                null,
                false,
                true,
                -1,
            )
        } else {
            broadcastMethod.invoke(
                activityManager,
                null,
                null,
                intent,
                null,
                null,
                -1,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                false,
                true,
                -1,
            )
        }
    }

    private fun removeStickyBroadcast(): Boolean {
        val intent = stickyIntent ?: return true
        val manager = activityManager ?: return true
        return runCatching {
            Class.forName("android.app.IActivityManager")
                .getDeclaredMethod(
                    "unbroadcastIntent",
                    IApplicationThread::class.java,
                    Intent::class.java,
                    intType,
                )
                .invoke(manager, null, intent, -1)
            stickyIntent = null
            activityManager = null
            true
        }.getOrDefault(false)
    }

    private fun removeBroadcastIfSent() {
        if (isBroadcastSent) {
            isBroadcastSent = !removeStickyBroadcast()
        }
    }
}
