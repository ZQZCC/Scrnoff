package ka.tile.scrnoff

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable

class Md3eSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), Checkable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackBounds = RectF()
    private val thumbBounds = RectF()
    private val density = resources.displayMetrics.density
    private val trackWidth = 52.dp
    private val trackHeight = 32.dp
    private val trackRadius = 16.dp
    private val uncheckedThumbSize = 16.dp
    private val checkedThumbSize = 24.dp
    private val strokeWidth = 2.dp
    private var checked = false
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var broadcasting = false
    private var listener: ((Md3eSwitchView, Boolean) -> Unit)? = null
    private val checkedTrackColor = context.colorCompat(R.color.md3e_switch_track_checked)
    private val uncheckedTrackColor = context.colorCompat(R.color.md3e_switch_track_unchecked)
    private val uncheckedThumbColor = context.colorCompat(R.color.md3e_switch_thumb_unchecked)
    private val uncheckedBorderColor = context.colorCompat(R.color.md3e_switch_border_unchecked)
    private val disabledTrackColor = context.colorCompat(R.color.md3e_switch_track_disabled)
    private val disabledContentColor = context.colorCompat(R.color.md3e_outline_variant)
    private val checkedThumbColor by lazy {
        if (context.isNightTheme) {
            context.colorCompat(R.color.md3e_switch_thumb_checked)
        } else {
            context.averageSystemColor(
                android.R.color.system_accent2_50,
                android.R.color.system_accent2_10,
            )
                ?: context.colorCompat(R.color.md3e_switch_thumb_checked)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        minimumWidth = trackWidth.toInt()
        minimumHeight = 48.dpInt

        if (attrs != null) {
            val typedArray =
                context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.checked))
            try {
                checked = typedArray.getBoolean(0, false)
                progress = if (checked) 1f else 0f
            } finally {
                typedArray.recycle()
            }
        }
    }

    fun setOnCheckedChangeListener(listener: ((Md3eSwitchView, Boolean) -> Unit)?) {
        this.listener = listener
    }

    override fun isChecked(): Boolean = checked

    override fun setChecked(checked: Boolean) {
        if (this.checked == checked) return
        this.checked = checked
        refreshDrawableState()
        animateProgress(if (checked) 1f else 0f)

        if (broadcasting) return
        broadcasting = true
        listener?.invoke(this, checked)
        broadcasting = false
    }

    fun setCheckedSilently(checked: Boolean) {
        val wasBroadcasting = broadcasting
        broadcasting = true
        try {
            isChecked = checked
        } finally {
            broadcasting = wasBroadcasting
        }
    }

    override fun toggle() {
        if (isEnabled) {
            isChecked = !checked
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        toggle()
        return true
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(trackWidth.toInt(), widthMeasureSpec),
            resolveSize(48.dpInt, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = (width - trackWidth) / 2f
        val top = (height - trackHeight) / 2f
        trackBounds.set(left, top, left + trackWidth, top + trackHeight)

        val trackColor: Int
        val thumbColor: Int
        val borderColor: Int?
        if (isEnabled) {
            if (checked) {
                trackColor = checkedTrackColor
                thumbColor = checkedThumbColor
                borderColor = null
            } else {
                trackColor = uncheckedTrackColor
                thumbColor = uncheckedThumbColor
                borderColor = uncheckedBorderColor
            }
        } else {
            trackColor = disabledTrackColor
            thumbColor = disabledContentColor
            borderColor = if (checked) null else disabledContentColor
        }

        paint.style = Paint.Style.FILL
        paint.color = trackColor
        canvas.drawRoundRect(trackBounds, trackRadius, trackRadius, paint)

        borderColor?.let {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = it
            val inset = strokeWidth / 2f
            trackBounds.inset(inset, inset)
            canvas.drawRoundRect(trackBounds, trackRadius - inset, trackRadius - inset, paint)
            trackBounds.inset(-inset, -inset)
        }

        val thumbSize = uncheckedThumbSize + (checkedThumbSize - uncheckedThumbSize) * progress
        val centerY = height / 2f
        val startX = trackBounds.left + trackRadius
        val endX = trackBounds.right - trackRadius
        val centerX = startX + (endX - startX) * progress
        thumbBounds.set(
            centerX - thumbSize / 2f,
            centerY - thumbSize / 2f,
            centerX + thumbSize / 2f,
            centerY + thumbSize / 2f,
        )
        paint.style = Paint.Style.FILL
        paint.color = thumbColor
        canvas.drawOval(thumbBounds, paint)
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        animator?.cancel()
        progress = if (checked) 1f else 0f
        invalidate()
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)
        if (checked) {
            mergeDrawableStates(drawableState, CHECKED_STATE)
        }
        return drawableState
    }

    @Suppress("DEPRECATION")
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = checked
    }

    private fun animateProgress(target: Float) {
        animator?.cancel()
        if (!isLaidOut) {
            progress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 160L
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private val Int.dp: Float
        get() = this * density

    private val Int.dpInt: Int
        get() = (this * density).toInt()

    companion object {
        private val CHECKED_STATE = intArrayOf(android.R.attr.state_checked)
    }
}
