package ka.tile.scrnoff

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class GlobalService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var floatingView: ImageView? = null
    private var preferences: SharedPreferences? = null
    private var canMove = true
    private var doubleTap = false
    private var shake = false
    private var volume = false
    private var netControl = false
    private var size = 0
    private var sensitivity = 10
    private var scrOnKey = KeyEvent.KEYCODE_VOLUME_UP
    private var scrOffKey = KeyEvent.KEYCODE_VOLUME_DOWN
    private var screenWidth = 0
    private var screenHeight = 0
    private var systemScreenInteractive = true
    private var orientationListener: OrientationEventListener? = null
    private var iScreenOff: IScreenOff? = null
    private var receiversRegistered = false
    private var server: SimpleTcpServer? = null
    private val indexTemplate by lazy { loadHtml("index.html").orEmpty() }
    private val notFoundTemplate by lazy { loadHtml("404.html").orEmpty() }
    private val favicon by lazy { loadBinary("favicon.png") }

    private val binderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (!AppBroadcasts.isTrustedControllerSender(this, context)) return
            connectController(intent)
        }
    }

    private fun connectController(intent: Intent?) {
        val binder: IBinder = AppBroadcasts.aliveBinderFrom(intent) ?: return
        iScreenOff = IScreenOff.Stub.asInterface(binder)
        runCatching { iScreenOff?.updateNowScreenState(systemScreenInteractive) }
        refreshFloatingWindow()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) = handleEvent(intent)
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) = handleEvent(intent)
    }

    private fun handleEvent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> {
                systemScreenInteractive = false
                runCatching { iScreenOff?.updateNowScreenState(false) }
                floatingView?.keepScreenOn = false
                orientationListener?.disable()
                val manager = windowManager
                val view = floatingView
                val layoutParams = params
                if (manager != null && view?.isAttachedToWindow == true && layoutParams != null) {
                    manager.updateViewLayout(view, layoutParams)
                }
            }

            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT,
                -> {
                systemScreenInteractive = true
                orientationListener?.disable()
                runCatching { iScreenOff?.updateNowScreenState(true) }
            }

            AppBroadcasts.ACTION_SET_SCREEN_OFF ->
                screenOff(intent.getBooleanExtra(AppBroadcasts.EXTRA_STATE, true))

            AppBroadcasts.ACTION_EXIT ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) disableSelf() else stopSelf()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            "size", "tran", "land", "float" -> refreshFloatingWindow()
            "canmove" -> canMove = sharedPreferences.getBoolean(key, true)
            "doubleTap" -> doubleTap = sharedPreferences.getBoolean(key, false)
            "shake" -> {
                shake = sharedPreferences.getBoolean(key, false)
                if (!shake) orientationListener?.disable()
            }

            "sensity" -> sensitivity = sharedPreferences.getInt(key, 10)
            "volume" -> updateKeyFiltering(sharedPreferences.getBoolean(key, false))
            "scrOnKey" -> scrOnKey = sharedPreferences.getInt(key, KeyEvent.KEYCODE_VOLUME_UP)
            "scrOffKey" -> scrOffKey = sharedPreferences.getInt(key, KeyEvent.KEYCODE_VOLUME_DOWN)
            "net" -> {
                netControl = sharedPreferences.getBoolean(key, false)
                if (netControl) startServer() else stopServer()
            }
        }
    }

    private fun readPreferences(sharedPreferences: SharedPreferences) {
        size = sharedPreferences.dp("size", 50)
        canMove = sharedPreferences.getBoolean("canmove", true)
        doubleTap = sharedPreferences.getBoolean("doubleTap", false)
        shake = sharedPreferences.getBoolean("shake", false)
        sensitivity = sharedPreferences.getInt("sensity", 10)
        updateKeyFiltering(sharedPreferences.getBoolean("volume", false))
        scrOnKey = sharedPreferences.getInt("scrOnKey", KeyEvent.KEYCODE_VOLUME_UP)
        scrOffKey = sharedPreferences.getInt("scrOffKey", KeyEvent.KEYCODE_VOLUME_DOWN)
        netControl = sharedPreferences.getBoolean("net", false)
        if (netControl) startServer() else stopServer()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val sp = getSharedPreferences("s", MODE_PRIVATE).also { preferences = it }
        sp.registerOnSharedPreferenceChangeListener(this)

        readPreferences(sp)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val wake =
                    orientation >= 360 - sensitivity || orientation <= sensitivity ||
                            orientation in (90 - sensitivity)..(90 + sensitivity) ||
                            orientation in (180 - sensitivity)..(180 + sensitivity) ||
                            orientation in (270 - sensitivity)..(270 + sensitivity)
                if (wake) {
                    screenOff(false)
                    disable()
                }
            }
        }

        windowManager = getSystemService(Service.WINDOW_SERVICE) as WindowManager
        updateScreenBounds()

        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.RGBA_8888,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            alpha = sp.getInt("tran", 90) * 0.01f
            gravity = Gravity.CENTER
            x = sp.getInt("x${if (isPortrait) "1" else "2"}", 0)
            y = sp.getInt("y${if (isPortrait) "1" else "2"}", 0)
        }

        floatingView = ImageView(this).apply {
            applyFloatingStyle()
            setOnTouchListener(FloatingTouchHandler())
            visibility = if (isPortrait && sp.getBoolean("land", false)) View.GONE else View.VISIBLE
        }

        refreshFloatingWindow()
        AppBroadcasts.registerReceiver(
            this,
            screenReceiver,
            AppBroadcasts.systemScreenFilter(),
            exported = true,
        )
        systemScreenInteractive =
            (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        AppBroadcasts.registerReceiver(
            this,
            commandReceiver,
            AppBroadcasts.commandFilter(),
            exported = false,
        )
        val stickyBinderIntent = AppBroadcasts.registerReceiver(
            this,
            binderReceiver,
            AppBroadcasts.binderFilter(),
            exported = true,
        )
        receiversRegistered = true
        connectController(stickyBinderIntent)
    }

    private fun screenOff(turnOff: Boolean) {
        if (!systemScreenInteractive) return
        val remote = iScreenOff ?: return
        val view = floatingView ?: return
        runCatching {
            remote.setPowerMode(turnOff)
            view.keepScreenOn = turnOff
            if (shake && turnOff) orientationListener?.enable() else orientationListener?.disable()
        }.onFailure {
            if (it is RemoteException) iScreenOff = null
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!volume || !systemScreenInteractive || event.action == KeyEvent.ACTION_UP) {
            return super.onKeyEvent(event)
        }
        if (iScreenOff == null) return super.onKeyEvent(event)

        return when (event.keyCode) {
            scrOffKey -> {
                screenOff(true)
                true
            }

            scrOnKey -> {
                screenOff(false)
                true
            }

            else -> super.onKeyEvent(event)
        }
    }

    private fun updateKeyFiltering(enabled: Boolean) {
        volume = enabled
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val flags = if (enabled) info.flags or flag else info.flags and flag.inv()
        if (flags != info.flags) {
            info.flags = flags
            serviceInfo = info
        }
    }

    private fun refreshFloatingWindow() {
        val sp = preferences ?: return
        val manager = windowManager ?: return
        val view = floatingView ?: return
        val layoutParams = params ?: return
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        size = sp.dp("size", 50)
        layoutParams.width = size
        layoutParams.height = size
        layoutParams.alpha = sp.getInt("tran", 90) * 0.01f
        updateScreenBounds()
        constrainFloatingPosition(layoutParams)
        view.visibility =
            if (isPortrait && sp.getBoolean("land", false)) View.GONE else View.VISIBLE

        if (sp.getBoolean("float", false)) {
            if (!view.isAttachedToWindow) {
                runCatching {
                    manager.addView(view, layoutParams)
                }
            } else {
                runCatching {
                    manager.updateViewLayout(view, layoutParams)
                }
            }
        } else {
            removeFloatingWindow(manager, view)
        }
    }

    private fun removeFloatingWindow(manager: WindowManager, view: View) {
        if (!view.isAttachedToWindow) return
        runCatching { manager.removeViewImmediate(view) }
            .recoverCatching {
                if (view.isAttachedToWindow) manager.removeView(view)
            }
    }

    private fun constrainFloatingPosition(layoutParams: WindowManager.LayoutParams) {
        val maxX = max((screenWidth - size) / 2, 0)
        val maxY = max((screenHeight - size) / 2, 0)
        layoutParams.x = layoutParams.x.coerceIn(-maxX, maxX)
        layoutParams.y = layoutParams.y.coerceIn(-maxY, maxY)
    }

    private fun updateScreenBounds() {
        val manager = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val sp = preferences ?: return
        val view = floatingView ?: return
        val layoutParams = params ?: return
        val isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT

        if (sp.getBoolean("land", false)) {
            view.visibility = if (isPortrait) View.GONE else View.VISIBLE
        }
        updateScreenBounds()
        layoutParams.x = sp.getInt("x${if (isPortrait) "1" else "2"}", 0)
        layoutParams.y = sp.getInt("y${if (isPortrait) "1" else "2"}", 0)
        constrainFloatingPosition(layoutParams)
        view.applyFloatingStyle()
        if (view.isAttachedToWindow) windowManager?.updateViewLayout(view, layoutParams)
    }

    override fun onDestroy() {
        if (receiversRegistered) {
            unregisterReceiver(binderReceiver)
            unregisterReceiver(screenReceiver)
            unregisterReceiver(commandReceiver)
            receiversRegistered = false
        }
        runCatching {
            val manager = windowManager
            val view = floatingView
            if (manager != null && view != null) manager.removeViewImmediate(view)
        }
        orientationListener?.disable()
        preferences?.unregisterOnSharedPreferenceChangeListener(this)
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        if (server != null) return
        server = SimpleTcpServer(
            object : SimpleTcpServer.TcpConnectionListener {
                private val parser = HttpRequestParser()

                override fun onReceive(data: ByteArray, size: Int) {
                    parser.add(data, size)
                    val request = parser.parse() ?: return
                    output(request)
                    parser.clear()
                }
            },
            port,
        ).also(SimpleTcpServer::start)
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun output(request: HttpRequest) {
        val target = request.target
        when {
            target == "/" || target == "/index.html" -> outputHtml(buildIndexHtml(), "200 OK")
            target == "/favicon.ico" -> favicon?.let(::outputPng)
            target.startsWith("/1?") -> runCatching {
                iScreenOff?.setPowerMode(false)
                outputHtml("", "200 OK")
            }

            target.startsWith("/2?") -> runCatching {
                iScreenOff?.setPowerMode(true)
                outputHtml("", "200 OK")
            }

            else -> outputHtml(notFoundTemplate, "404 Not Found")
        }
    }

    private fun buildIndexHtml(): String {
        val nowState = runCatching {
            when (iScreenOff?.nowScreenState) {
                IScreenOff.STATE_OFF -> "息屏"
                IScreenOff.STATE_ON -> "亮屏"
                IScreenOff.STATE_SPECIAL -> "息屏运行"
                else -> "未知"
            }
        }.getOrElse { "未知" }

        return indexTemplate
            .replace("{{brand}}", Build.BRAND)
            .replace("{{device}}", "${Build.MODEL} Android ${Build.VERSION.RELEASE}")
            .replace("{{state}}", nowState)
    }

    private fun outputHtml(html: String, responseCode: String) {
        val bytes = html.toByteArray()
        server?.output(
            buildHttpHeader(
                "HTTP/1.1 $responseCode",
                "text/html; charset=UTF-8",
                bytes.size,
            ) + html,
        )
    }

    private fun outputPng(png: ByteArray) {
        val header = buildHttpHeader("HTTP/1.1 200 OK", "image/png", png.size)
        server?.output(header.toByteArray() + png)
    }

    private fun buildHttpHeader(startLine: String, contentType: String, contentLength: Int): String =
        buildString {
            append(startLine).append(CRLF)
            append("Content-Type: ").append(contentType).append(CRLF)
            append("Content-Length: ").append(contentLength).append(CRLF)
            append("Connection: close").append(CRLF).append(CRLF)
        }

    private fun loadHtml(fileName: String): String? = loadBinary(fileName)?.decodeToString()

    private fun loadBinary(fileName: String): ByteArray? =
        runCatching { assets.open(fileName).use { it.readBytes() } }.getOrNull()

    private fun ImageView.applyFloatingStyle() {
        background = ShapeDrawable(OvalShape()).apply {
            paint.color = colorCompat(R.color.bg)
        }
        setImageResource(R.drawable.fw)
    }

    private fun SharedPreferences.dp(key: String, defaultValue: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            getInt(key, defaultValue).toFloat(),
            resources.displayMetrics,
        ).toInt()

    private inner class FloatingTouchHandler : View.OnTouchListener {
        private val handler = Handler(Looper.getMainLooper())
        private var lastX = 0
        private var lastY = 0
        private var paramX = 0
        private var paramY = 0
        private var lastDown = 0L
        private var lastUp = 0L
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val layoutParams = params ?: return false
            val manager = windowManager ?: return false
            val sp = preferences ?: return false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                    paramX = layoutParams.x
                    paramY = layoutParams.y
                    layoutParams.alpha = 1f
                    handler.postDelayed({
                        if (System.currentTimeMillis() - lastUp >= 400 && !moved) {
                            screenOff(true)
                        }
                    }, 400)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - lastX
                    val dy = event.rawY.toInt() - lastY
                    if (abs(dx) > 4 || abs(dy) > 4) moved = true
                    if (!canMove) return true
                    layoutParams.x = paramX + dx
                    layoutParams.y = paramY + dy
                    manager.updateViewLayout(view, layoutParams)
                }

                MotionEvent.ACTION_UP -> {
                    lastUp = System.currentTimeMillis()
                    layoutParams.alpha = sp.getInt("tran", 90) * 0.01f
                    layoutParams.x = when {
                        layoutParams.x > (screenWidth - size) * 0.43 -> (screenWidth - size) / 2
                        layoutParams.x < (screenWidth - size) * -0.43 -> -(screenWidth - size) / 2
                        else -> layoutParams.x
                    }
                    layoutParams.y = min(
                        max(layoutParams.y, -(screenHeight - size) / 2),
                        (screenHeight - size) / 2,
                    )
                    manager.updateViewLayout(view, layoutParams)
                    moved = false
                    val isPortrait =
                        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                    sp.edit()
                        .putInt("x${if (isPortrait) "1" else "2"}", layoutParams.x)
                        .putInt("y${if (isPortrait) "1" else "2"}", layoutParams.y)
                        .apply()
                }
            }

            if (!doubleTap) return false
            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_OUTSIDE,
                    -> {
                    if (System.currentTimeMillis() - lastDown <= 400) screenOff(false)
                    lastDown = System.currentTimeMillis()
                }
            }
            return false
        }
    }

    companion object {
        @JvmField
        var port: Int = 20_000

        private const val CRLF = "\r\n"
    }
}
