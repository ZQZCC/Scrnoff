package ka.tile.scrnoff

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import ka.tile.scrnoff.databinding.MainBinding
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import rikka.shizuku.Shizuku

private const val SURFACE_CONTAINER_TAG = "surface_container"

@SuppressLint("GestureBackNavigation")
class MainActivity : Activity() {
    private lateinit var binding: MainBinding
    private var isServiceOk = false
    private var isPermissionResultListenerRegistered = false
    private var isBroadcastReceiverRegistered = false
    private var scrOffKey = KeyEvent.KEYCODE_VOLUME_DOWN
    private var scrOnKey = KeyEvent.KEYCODE_VOLUME_UP
    private var surfaceContainerColor = Color.TRANSPARENT
    private var systemTopInset = 0
    private var collapsedTitleTopPadding = 0
    private var isNetworkTitle = false
    private var networkTitleExpandedText = ""
    private var networkTitleCollapsedText = ""
    private var iScreenOff: IScreenOff? = null
    private val activationHandler = Handler(Looper.getMainLooper())
    private var activationRefreshToken = 0

    private val binderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (!AppBroadcasts.isTrustedControllerSender(this, context)) return
            connectController(intent)
        }
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) activateWithShizuku()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val window = window
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes.dimAmount = 0.5f
        binding = MainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val isNight = isNightTheme
        applyAveragedSurfaces(isNight)
        applySystemBars(isNight)

        val sp = getSharedPreferences("s", MODE_PRIVATE)
        setButtonsOnClick(sp)
        val stickyBinderIntent = AppBroadcasts.registerReceiver(
            this,
            binderReceiver,
            AppBroadcasts.binderFilter(),
            exported = true,
        )
        isBroadcastReceiverRegistered = true
        connectController(stickyBinderIntent)
    }

    private fun connectController(intent: Intent?) {
        val binder = AppBroadcasts.aliveBinderFrom(intent) ?: return
        iScreenOff = IScreenOff.Stub.asInterface(binder)
        enableScreenOffFunctions()
    }

    private fun applyAveragedSurfaces(isNight: Boolean) {
        val backgroundColor =
            if (isNight) {
                colorCompat(R.color.md3e_surface)
            } else {
                averageSystemColor(
                    android.R.color.system_neutral1_10,
                    android.R.color.system_neutral2_10,
                )
                    ?: colorCompat(R.color.md3e_surface)
            }
        val containerColor =
            if (isNight) {
                colorCompat(R.color.md3e_surface_container)
            } else {
                averageSystemColor(
                    android.R.color.system_neutral1_50,
                    android.R.color.system_neutral2_50,
                )
                    ?: colorCompat(R.color.md3e_surface_container)
            }
        surfaceContainerColor = containerColor
        val radius = 36f * resources.displayMetrics.density

        val root = binding.root
        root.setBackgroundColor(backgroundColor)
        binding.ll.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        binding.titleScrim.setBackgroundColor(backgroundColor)
        tintSurfaceContainers(root, containerColor)
    }

    private fun tintSurfaceContainers(view: View, color: Int) {
        if (view.tag == SURFACE_CONTAINER_TAG) {
            view.backgroundTintList = ColorStateList.valueOf(color)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                tintSurfaceContainers(view.getChildAt(index), color)
            }
        }
    }

    private fun applySystemBars(isNight: Boolean) {
        val root = binding.root
        val sheet = binding.ll
        val scrollContent = binding.lll
        val sheetBottomPadding = sheet.paddingBottom
        val contentBottomPadding = scrollContent.paddingBottom
        collapsedTitleTopPadding = (8f * resources.displayMetrics.density).roundToInt()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Color.TRANSPARENT else Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        val lightBars = !isNight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lightAppearance =
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.apply {
                setSystemBarsAppearance(
                    if (lightBars) lightAppearance else 0,
                    lightAppearance,
                )
            }
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
            if (lightBars) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            if (lightBars && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }

        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarsCompat()
            systemTopInset = bars.top
            view.setPadding(bars.left, 0, bars.right, 0)
            sheet.setPadding(
                sheet.paddingLeft,
                collapsedTitleTop(),
                sheet.paddingRight,
                sheetBottomPadding,
            )
            scrollContent.setPadding(
                scrollContent.paddingLeft,
                scrollContent.paddingTop,
                scrollContent.paddingRight,
                contentBottomPadding + bars.bottom,
            )
            insets
        }
        root.requestApplyInsets()
    }

    private data class InsetsCompat(val left: Int, val top: Int, val right: Int, val bottom: Int)

    @Suppress("DEPRECATION")
    private fun WindowInsets.systemBarsCompat(): InsetsCompat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars =
                getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            InsetsCompat(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
        } else {
            InsetsCompat(
                systemWindowInsetLeft,
                systemWindowInsetTop,
                systemWindowInsetRight,
                systemWindowInsetBottom
            )
        }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val bounds = Rect()
                focused.getGlobalVisibleRect(bounds)
                if (!bounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    focused.clearFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(focused.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun showNet() {
        val labels = arrayOf("WLAN", "eth", "usb", "p2p", "lo", "unknown")
        val interfaces = Array(labels.size) { StringBuilder() }
        var available = false
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().forEach { network ->
                val name = network.name.lowercase(Locale.US)
                val index = when {
                    "wlan" in name -> 0
                    "eth" in name -> 1
                    "usb" in name -> 2
                    "p2p" in name -> 3
                    "lo" in name -> 4
                    else -> 5
                }
                network.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filterNot(Inet4Address::isLoopbackAddress)
                    .forEach { address ->
                        if (interfaces[index].isNotEmpty()) {
                            interfaces[index].append(' ')
                        }
                        interfaces[index].append(address.hostAddress).append(':')
                            .append(GlobalService.port)
                        available = true
                    }
            }
        }

        val expandedText = if (interfaces[0].isNotEmpty()) {
            "WLAN:\n${interfaces[0]}"
        } else {
            buildString {
                for (index in 1 until 5) {
                    if (interfaces[index].isNotEmpty()) {
                        if (isNotEmpty()) append('\n')
                        append(labels[index]).append(": ").append(interfaces[index])
                    }
                }
            }
        }
        val collapsedText = if (interfaces[0].isNotEmpty()) {
            "WLAN: ${interfaces[0]}"
        } else {
            expandedText.replace('\n', ' ')
        }
        binding.titleText.setOnClickListener(null)
        isNetworkTitle = true
        networkTitleExpandedText = if (available) expandedText else "no network available"
        networkTitleCollapsedText = if (available) collapsedText else "no network available"
        applyTitleContent()
    }

    private fun showDefaultTitle() {
        isNetworkTitle = false
        networkTitleExpandedText = ""
        networkTitleCollapsedText = ""
        applyTitleContent()
    }

    private fun setButtonsOnClick(sp: SharedPreferences) {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.left.visibility = View.VISIBLE
            binding.right.visibility = View.VISIBLE
        }

        val sheet = binding.ll
        val e1 = binding.e1
        val e2 = binding.e2
        val s1 = binding.s1
        val s2 = binding.s2
        val s3 = binding.s3
        val s4 = binding.s4
        val s5 = binding.s5
        val s6 = binding.s6
        val s7 = binding.s7
        val s8 = binding.s8

        val accessibilityEnabled = isGlobalAccessibilityServiceEnabled()
        val floatEnabled = sp.getBoolean("float", false) && accessibilityEnabled
        if (!accessibilityEnabled && sp.getBoolean("float", false)) {
            sp.edit().putBoolean("float", false).apply()
        }
        s1.isChecked = accessibilityEnabled
        s2.isChecked = floatEnabled
        s3.isChecked = sp.getBoolean("land", false)
        s4.isChecked = !sp.getBoolean("canmove", true)
        s5.isChecked = sp.getBoolean("doubleTap", false)
        s6.isChecked = sp.getBoolean("shake", false)
        s7.isChecked = sp.getBoolean("volume", false)
        s8.isChecked = sp.getBoolean("net", false)

        val sb = binding.sb
        val eb = binding.eb
        val sc = binding.sc
        val ec = binding.ec
        val sd = binding.sd
        val ed = binding.ed
        val sliderActive = colorCompat(R.color.md3e_primary_soft)
        val sliderInactive = colorCompat(R.color.md3e_slider_inactive)
        listOf(sb, sc, sd).forEach { it.setColors(sliderActive, sliderInactive) }
        sb.value = sp.getInt("size", 50)
        eb.setText(sp.getInt("size", 50).asPercentText())
        sc.value = sp.getInt("tran", 90)
        ec.setText(sp.getInt("tran", 90).asPercentText())
        sd.value = sp.getInt("sensity", 10)
        ed.setText(sp.getInt("sensity", 10).asPercentText())
        val floatDependentViews = listOf(
            s3.parent as View,
            s4.parent as View,
            s5.parent as View,
            sb.parent as View,
            sc.parent as View,
            sd.parent as View,
        )
        val floatSwitchRow = s2.parent as View
        fun updateFloatDependentLayouts(enabled: Boolean) {
            val visibility = if (enabled) View.VISIBLE else View.GONE
            floatDependentViews.forEach { it.visibility = visibility }
            floatSwitchRow.setBackgroundResource(
                if (enabled) R.drawable.md3e_segment_top else R.drawable.md3e_segment_single,
            )
            floatSwitchRow.backgroundTintList = ColorStateList.valueOf(surfaceContainerColor)
            listOf(s3, s4, s5).forEach { it.isEnabled = enabled }
            listOf(sb, sc, sd).forEach { it.isEnabled = enabled }
        }
        updateFloatDependentLayouts(s2.isChecked)

        fun applyFloatEnabled(enabled: Boolean) {
            if (!enabled) {
                sp.edit().putBoolean("float", false).apply()
                updateFloatDependentLayouts(false)
                return
            }

            if (!isGlobalAccessibilityServiceEnabled()) {
                if (!s1.isChecked) {
                    s1.isChecked = true
                }
                if (!isGlobalAccessibilityServiceEnabled()) {
                    sp.edit().putBoolean("float", false).apply()
                    s2.setCheckedSilently(false)
                    updateFloatDependentLayouts(false)
                    return
                }
            } else if (!s1.isChecked) {
                s1.setCheckedSilently(true)
                if (s8.isChecked) showNet()
            }

            sp.edit().putBoolean("float", true).apply()
            updateFloatDependentLayouts(true)
        }

        s1.setOnCheckedChangeListener { button, isChecked ->
            val powerManager = getSystemService(Service.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }

            if (!isServiceOk) {
                button.isChecked = false
                Toast.makeText(this, R.string.active_first, Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                openAccessibilityService(button)
                if (s8.isChecked) showNet()
            } else {
                showDefaultTitle()
                sendBroadcast(AppBroadcasts.exitIntent(this))
            }
        }

        s2.setOnCheckedChangeListener { _, enabled ->
            applyFloatEnabled(enabled)
        }
        floatSwitchRow.setOnClickListener { s2.toggle() }
        s3.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("land", checked).apply()
        }
        s4.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("canmove", !checked).apply()
        }
        s5.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("doubleTap", checked).apply()
        }
        s6.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("shake", checked).apply()
        }
        s7.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("volume", checked).apply()
            e1.isEnabled = checked
            e2.isEnabled = checked
        }
        s8.setOnCheckedChangeListener { _, checked ->
            if (s1.isChecked) {
                if (checked) showNet() else showDefaultTitle()
            }
            sp.edit().putBoolean("net", checked).apply()
        }

        if (s1.isChecked && s8.isChecked) showNet()
        bindSlider(sb, eb, sp, "size", 0..100)
        bindSlider(sc, ec, sp, "tran", 0..100)
        bindSlider(sd, ed, sp, "sensity", 0..30) {
            if (sd.value < 1) {
                sd.value = 1
                ed.setText(1.asPercentText())
                sp.edit().putInt("sensity", 1).apply()
                Toast.makeText(this, R.string.toosmall, Toast.LENGTH_SHORT).show()
            }
        }

        e1.isEnabled = s7.isChecked
        e2.isEnabled = s7.isChecked
        scrOffKey = sp.getInt("scrOffKey", KeyEvent.KEYCODE_VOLUME_DOWN)
        scrOnKey = sp.getInt("scrOnKey", KeyEvent.KEYCODE_VOLUME_UP)
        e1.setText(scrOffKey.toString())
        e2.setText(scrOnKey.toString())
        e1.storeIntOnTextChange { value ->
            scrOffKey = value
            sp.edit().putInt("scrOffKey", value).apply()
        }
        e2.storeIntOnTextChange { value ->
            scrOnKey = value
            sp.edit().putInt("scrOnKey", value).apply()
        }

        binding.activateButton.setOnClickListener { showActivate() }
        binding.settingsContent.visibility =
            if (isServiceOk) View.VISIBLE else View.GONE

        val density = resources.displayMetrics.density
        applyMd3eAppearance(
            editTexts = listOf(e1, e2, eb, ec, ed),
        )
        bindCollapsingTitle()
        sheet.elevation = 20 * density

        binding.screenoffSwitch.setOnCheckedChangeListener { _, checked ->
            if (!isServiceOk) return@setOnCheckedChangeListener
            iScreenOff?.setPowerMode(!checked)
        }
    }

    private fun bindCollapsingTitle() {
        val scrollView = binding.sv
        scrollView.viewTreeObserver.addOnScrollChangedListener { updateTitleFade() }
        scrollView.post {
            applyTitleContent()
            updateTitleFade()
        }
    }

    private fun collapsedTitleTop(): Int = systemTopInset + collapsedTitleTopPadding

    private fun applyTitleContent() {
        val density = resources.displayMetrics.density
        val smallTitle = binding.titleText
        val largeTitle = binding.largeTitleText
        val largeText =
            if (isNetworkTitle) networkTitleExpandedText else getString(R.string.shortcutoff)
        val smallText =
            if (isNetworkTitle) networkTitleCollapsedText else getString(R.string.shortcutoff)
        val largeLineCount = if (isNetworkTitle && largeText.contains('\n')) 2 else 1
        val largeHeight = ((if (largeLineCount > 1) 96f else 64f) * density).roundToInt()

        smallTitle.apply {
            text = smallText
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }
        largeTitle.apply {
            text = largeText
            maxLines = largeLineCount
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            if (layoutParams.height != largeHeight) {
                layoutParams = layoutParams.apply {
                    height = largeHeight
                }
            }
        }
        updateTitleFade()
    }

    private fun updateTitleFade() {
        val scrollView = binding.sv
        val smallTitle = binding.titleText
        val titleScrim = binding.titleScrim
        val largeTitle = binding.largeTitleText
        val largeHeight = largeTitle.height.toFloat().takeIf { it > 0f } ?: return
        val largeTop = (largeTitle.top - scrollView.scrollY).toFloat()
        val coveredHeight = (titleScrim.height.toFloat() - largeTop).coerceIn(0f, largeHeight)
        val halfCovered = largeHeight / 2f
        val raw = ((coveredHeight - halfCovered) / halfCovered).coerceIn(0f, 1f)
        val fraction = raw * raw * (3f - 2f * raw)

        smallTitle.alpha = fraction
        titleScrim.alpha = 1f
    }

    private fun applyMd3eAppearance(
        editTexts: List<EditText>,
    ) {
        val primaryContainer = colorCompat(R.color.md3e_primary_container)
        val onSurface = colorCompat(R.color.md3e_on_surface)
        val onSurfaceVariant = colorCompat(R.color.md3e_on_surface_variant)
        editTexts.forEach { item ->
            item.setTextColor(onSurface)
            item.setHintTextColor(onSurfaceVariant)
            item.highlightColor = primaryContainer
        }
        binding.activateButton.setTextColor(colorCompat(R.color.md3e_on_error))
    }

    private fun openAccessibilityService(button: Md3eSwitchView) {
        if (isGlobalAccessibilityServiceEnabled()) {
            button.setCheckedSilently(true)
            return
        }

        if (enableGlobalAccessibilityService()) {
            button.setCheckedSilently(true)
        } else {
            button.isChecked = false
            Toast.makeText(this, R.string.mannually_open, Toast.LENGTH_SHORT).show()
            startActivity(globalAccessibilitySettingsIntent())
        }
    }

    private fun bindSlider(
        slider: Md3eSliderView,
        editText: EditText,
        sp: SharedPreferences,
        key: String,
        range: IntRange,
        onStop: () -> Unit = {},
    ) {
        slider.setRange(range)
        slider.onValueChanged = { value, _ ->
            val progress = value.coerceIn(range.first, range.last)
            sp.edit().putInt(key, progress).apply()
            val text = progress.asPercentText()
            if (editText.text.toString() != text) {
                editText.setText(text)
            }
        }
        slider.onStopTracking = onStop
        fun applyTypedValue() {
            editText.text.sliderPercentValue()
                ?.takeIf(range::contains)
                ?.let { value ->
                    sp.edit().putInt(key, value).apply()
                    slider.value = value
                    editText.setText(value.asPercentText())
                }
        }
        editText.setOnKeyListener { _, _, event ->
            if (event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN &&
                editText.text.isNotEmpty()
            ) {
                applyTypedValue()
            }
            false
        }
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && editText.text.isNotEmpty()) {
                applyTypedValue()
            }
        }
    }

    private fun Int.asPercentText(): String = "$this%"

    private fun CharSequence.sliderPercentValue(): Int? =
        toString().filter { it.isDigit() }.toIntOrNull()

    private fun EditText.storeIntOnTextChange(onValue: (Int) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun afterTextChanged(s: Editable?) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                s?.toString()?.toIntOrNull()?.let(onValue)
            }
        })
    }

    fun enableScreenOffFunctions() {
        isServiceOk = true
        binding.settingsContent.visibility = View.VISIBLE
        binding.sv.apply {
            post { scrollTo(0, 0) }
        }
        binding.activateButton.apply {
            setText(R.string.all_ok)
            stateListAnimator = null
            elevation = 0f
            background = getDrawable(R.drawable.md3e_primary_container_pill)
            setTextColor(colorCompat(R.color.md3e_on_service_container))
            setOnClickListener(null)
            setOnLongClickListener {
                closeScreenOffService()
                Toast.makeText(this@MainActivity, R.string.service_closed, Toast.LENGTH_SHORT)
                    .show()
                finish()
                true
            }
        }
        binding.screenoffSwitch.isEnabled = true
    }

    private fun closeScreenOffService() {
        getSharedPreferences("s", MODE_PRIVATE)
            .edit()
            .putBoolean("float", false)
            .apply()
        sendBroadcast(AppBroadcasts.exitIntent(this))
        runCatching {
            iScreenOff?.closeAndExit()
        }.onFailure { error ->
            if (error !is RemoteException) {
                throw error
            }
        }
        iScreenOff = null
        isServiceOk = false
    }

    private fun showKeyPressedHint(keyCode: Int) {
        Toast.makeText(
            this,
            String.format(
                Locale.getDefault(),
                getString(R.string.key_pressed),
                KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", ""),
                keyCode,
            ),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun finishWithBackKeyHint(): Boolean {
        showKeyPressedHint(KeyEvent.KEYCODE_BACK)
        finish()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return finishWithBackKeyHint()
        }
        super.onKeyDown(keyCode, event)
        if (binding.settingsContent.visibility == View.VISIBLE) {
            showKeyPressedHint(keyCode)
            return true
        }
        if (!isServiceOk) return true
        val switch = binding.screenoffSwitch
        if (keyCode == scrOffKey) switch.isChecked = true
        if (keyCode == scrOnKey) switch.isChecked = false
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithBackKeyHint()
    }

    private fun checkShizuku() {
        unzipFiles()

        if (!isPermissionResultListenerRegistered) {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            isPermissionResultListenerRegistered = true
        }

        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0)
            } else {
                activateWithShizuku()
            }
        } catch (e: Exception) {
            if (e is IllegalStateException) {
                Toast.makeText(this, R.string.shizuku_notrun, Toast.LENGTH_SHORT).show()
            } else if (checkSelfPermission("moe.shizuku.manager.permission.API_V23") == PackageManager.PERMISSION_GRANTED) {
                activateWithShizuku()
            }
        }
    }

    private fun activateWithShizuku() {
        val command = activationCommand() ?: return
        runActivation { ShizukuCompat.runShell(command) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        binding.left.visibility =
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) View.VISIBLE else View.GONE
        binding.right.visibility =
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) View.VISIBLE else View.GONE
        binding.root.requestApplyInsets()
        super.onConfigurationChanged(newConfig)
    }

    override fun onResume() {
        super.onResume()
        syncAccessibilityState()
        if (!isServiceOk) refreshActivatedState()
    }

    private fun syncAccessibilityState() {
        val enabled = isGlobalAccessibilityServiceEnabled()
        binding.s1.setCheckedSilently(enabled)
        if (!enabled && binding.s2.isChecked) {
            binding.s2.isChecked = false
        } else if (enabled && binding.s8.isChecked) {
            showNet()
        }
    }

    override fun onDestroy() {
        activationRefreshToken += 1
        activationHandler.removeCallbacksAndMessages(null)
        if (isPermissionResultListenerRegistered) {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
        if (isBroadcastReceiverRegistered) {
            unregisterReceiver(binderReceiver)
            isBroadcastReceiverRegistered = false
        }
        super.onDestroy()
    }

    fun finish(view: View) {
        finish()
    }

    fun showActivate() {
        unzipFiles()
        val command = activationCommand() ?: return
        val dialogTheme =
            if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES == Configuration.UI_MODE_NIGHT_YES) {
                android.R.style.Theme_DeviceDefault_Dialog_Alert
            } else {
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            }
        val builder = AlertDialog.Builder(this, dialogTheme)
            .setMessage(String.format(getString(R.string.active_steps), command))
            .setTitle(R.string.need_active)
            .setNeutralButton(R.string.copy_cmd) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("c", "adb shell $command"))
                Toast.makeText(
                    this,
                    String.format(getString(R.string.cmd_copy_finish), command),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(R.string.by_root) { _, _ ->
                runActivation {
                    val process = Runtime.getRuntime().exec("su")
                    DataOutputStream(process.outputStream).use { output ->
                        output.writeBytes(command)
                        output.writeBytes("\nexit\n")
                        output.flush()
                    }
                    check(process.waitFor() == 0)
                }
            }
        builder.setPositiveButton(R.string.by_shizuku) { _, _ -> checkShizuku() }
        builder.show()
    }

    private fun runActivation(action: () -> Unit) {
        thread(name = "ScreenOffActivation", isDaemon = true) {
            val succeeded = runCatching(action).isSuccess
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (succeeded) {
                    refreshActivatedState()
                } else {
                    Toast.makeText(this, R.string.active_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun activationCommand(): String? =
        getExternalFilesDir(null)
            ?.resolve("starter.sh")
            ?.path
            ?.let { "sh $it" }

    private fun refreshActivatedState() {
        activationRefreshToken += 1
        pollActivatedState(activationRefreshToken, 0)
    }

    private fun pollActivatedState(token: Int, attempt: Int) {
        if (token != activationRefreshToken || isServiceOk || isDestroyed) return

        connectController(AppBroadcasts.stickyBinderIntent(this))
        if (!isServiceOk && attempt < ACTIVATION_REFRESH_ATTEMPTS) {
            activationHandler.postDelayed(
                { pollActivatedState(token, attempt + 1) },
                ACTIVATION_REFRESH_INTERVAL_MS,
            )
        }
    }

    private fun unzipFiles() {
        val externalDir = getExternalFilesDir(null) ?: return

        runCatching {
            assets.open("starter.sh").use { input ->
                FileOutputStream(externalDir.resolve("starter.sh")).use(input::copyTo)
            }
        }
    }

    private companion object {
        const val ACTIVATION_REFRESH_ATTEMPTS = 16
        const val ACTIVATION_REFRESH_INTERVAL_MS = 500L
    }
}
