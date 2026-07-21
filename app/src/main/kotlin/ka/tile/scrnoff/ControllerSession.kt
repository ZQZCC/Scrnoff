package ka.tile.scrnoff

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.FileOutputStream
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

object ControllerSession {
    const val ROOT = 1
    const val SHIZUKU = 2

    private const val NONE = 0
    private const val PREFERENCES = "s"
    private const val BACKEND_KEY = "activation_backend"
    private const val START_TIMEOUT_MS = 8_000L
    private const val BINDER_POLL_INTERVAL_MS = 250L

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(IScreenOff?) -> Unit>()
    private val requests = mutableListOf<(IScreenOff?) -> Unit>()
    private var appContext: Context? = null
    private var receiverRegistered = false
    private var shizukuListenerRegistered = false
    private var remote: IScreenOff? = null
    private var remoteBinder: IBinder? = null
    private var startingBackend = NONE
    private var pendingBackend = NONE
    private var startToken = 0
    private var startDeadline = 0L

    private val binderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (AppBroadcasts.isTrustedControllerSender(this, context)) connect(intent)
        }
    }

    private val binderDeathRecipient = IBinder.DeathRecipient {
        handler.post {
            val binder = remoteBinder
            if (binder != null && !binder.pingBinder()) disconnect(binder)
        }
    }

    private val pollController = Runnable { awaitController() }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        handler.post {
            val context = appContext ?: return@post
            if (savedBackend(context) == SHIZUKU && liveRemote() == null) ensureReady(context)
        }
    }

    fun addListener(context: Context, listener: (IScreenOff?) -> Unit) {
        initialize(context)
        if (!listeners.contains(listener)) listeners += listener
        listener(liveRemote())
    }

    fun removeListener(listener: (IScreenOff?) -> Unit) {
        listeners -= listener
    }

    fun ensureReady(
        context: Context,
        callback: ((IScreenOff?) -> Unit)? = null,
    ) {
        initialize(context)
        liveRemote()?.let {
            callback?.invoke(it)
            return
        }

        if (callback != null) requests += callback
        if (startingBackend != NONE) return

        val backend = savedBackend(context)
        if (backend == NONE || backend == SHIZUKU && !canUseShizuku()) {
            completeRequests(null)
        } else {
            start(requireNotNull(appContext), backend)
        }
    }

    fun activate(
        context: Context,
        backend: Int,
        callback: (IScreenOff?) -> Unit,
    ) {
        initialize(context)
        liveRemote()?.let {
            callback(it)
            return
        }

        requests += callback
        if (startingBackend == NONE) {
            if (backend == SHIZUKU && !canUseShizuku()) {
                completeRequests(null)
            } else {
                start(requireNotNull(appContext), backend)
            }
        }
    }

    fun stop(context: Context) {
        initialize(context)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(BACKEND_KEY)
            .apply()

        cancelStart()
        completeRequests(null)
        val current = remote
        disconnect(remoteBinder)
        thread(name = "ScreenOffStop", isDaemon = true) {
            runCatching { current?.closeAndExit() }
        }
    }

    fun invalidate(controller: IScreenOff) {
        if (controller.asBinder() === remoteBinder) disconnect(remoteBinder)
    }

    @Synchronized
    fun activationCommand(context: Context): String? = runCatching {
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val script = directory.resolve("starter.sh")
        context.assets.open("starter.sh").use { input ->
            FileOutputStream(script).use(input::copyTo)
        }
        "sh ${script.absolutePath}"
    }.getOrNull()

    private fun initialize(context: Context) {
        val application = appContext ?: context.applicationContext.also { appContext = it }
        if (!receiverRegistered) {
            receiverRegistered = true
            connect(
                AppBroadcasts.registerReceiver(
                    application,
                    binderReceiver,
                    AppBroadcasts.binderFilter(),
                    exported = true,
                ),
            )
        }
        if (!shizukuListenerRegistered) {
            shizukuListenerRegistered = true
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        }
    }

    private fun start(context: Context, backend: Int) {
        startingBackend = backend
        pendingBackend = backend
        val token = ++startToken
        handler.removeCallbacks(pollController)

        thread(name = "ScreenOffActivation", isDaemon = true) {
            val succeeded = runCatching {
                val command = requireNotNull(activationCommand(context))
                if (backend == ROOT) runRoot(command) else ShizukuCompat.runShell(command)
            }.isSuccess
            handler.post { finishStart(token, succeeded) }
        }
    }

    private fun finishStart(token: Int, succeeded: Boolean) {
        if (token != startToken) return
        if (!succeeded) {
            failStart(token)
            return
        }
        startDeadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        awaitController()
    }

    private fun awaitController() {
        if (startingBackend == NONE) return
        connect(AppBroadcasts.stickyBinderIntent(requireNotNull(appContext)))
        if (liveRemote() != null) return

        if (SystemClock.elapsedRealtime() >= startDeadline) {
            failStart(startToken)
        } else {
            handler.postDelayed(pollController, BINDER_POLL_INTERVAL_MS)
        }
    }

    private fun runRoot(command: String) {
        val process = Runtime.getRuntime().exec("su")
        process.outputStream.bufferedWriter().use {
            it.appendLine(command)
            it.appendLine("exit")
        }
        check(process.waitFor() == 0)
    }

    private fun connect(intent: Intent?) {
        val binder = AppBroadcasts.aliveBinderFrom(intent) ?: return
        if (binder === remoteBinder && binder.pingBinder()) return

        disconnect(remoteBinder, notify = false)
        if (runCatching { binder.linkToDeath(binderDeathRecipient, 0) }.isFailure) return

        remoteBinder = binder
        remote = IScreenOff.Stub.asInterface(binder)
        if (pendingBackend != NONE) saveBackend(requireNotNull(appContext), pendingBackend)
        cancelStart()

        val current = requireNotNull(remote)
        listeners.toList().forEach { runCatching { it(current) } }
        completeRequests(current)
    }

    private fun liveRemote(): IScreenOff? {
        val binder = remoteBinder ?: return null
        if (binder.pingBinder()) return remote
        disconnect(binder)
        return null
    }

    private fun disconnect(binder: IBinder?, notify: Boolean = true) {
        if (binder == null || binder !== remoteBinder) return
        runCatching { binder.unlinkToDeath(binderDeathRecipient, 0) }
        remote = null
        remoteBinder = null
        if (notify) listeners.toList().forEach { runCatching { it(null) } }
    }

    private fun cancelStart() {
        startToken += 1
        startingBackend = NONE
        pendingBackend = NONE
        handler.removeCallbacks(pollController)
    }

    private fun failStart(token: Int) {
        if (token != startToken) return
        cancelStart()
        completeRequests(null)
    }

    private fun completeRequests(controller: IScreenOff?) {
        val pending = requests.toList()
        requests.clear()
        pending.forEach { runCatching { it(controller) } }
    }

    private fun canUseShizuku(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun savedBackend(context: Context): Int =
        when (context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getInt(BACKEND_KEY, NONE)) {
            ROOT -> ROOT
            SHIZUKU -> SHIZUKU
            else -> NONE
        }

    private fun saveBackend(context: Context, backend: Int) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(BACKEND_KEY, backend)
            .apply()
    }
}
