package ka.tile.scrnoff

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class Md3eSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackBounds = RectF()
    private val thumbBounds = RectF()
    private val trackPath = Path()
    private val density = resources.displayMetrics.density
    private val trackHeight = 16.dp
    private val trackRadius = 8.dp
    private val innerTrackRadius = 2.dp
    private val thumbWidth = 6.dp
    private val thumbHeight = 36.dp
    private val thumbRadius = 3.dp
    private val thumbTrackGap = 4.dp
    private val stopIndicatorRadius = 2.dp
    private var primary = context.colorCompat(R.color.md3e_primary_soft)
    private var inactiveTrack = context.colorCompat(R.color.md3e_slider_inactive)

    var valueFrom = 0
        set(value) {
            field = value
            this.value = this.value.coerceIn(valueFrom, valueTo)
        }

    var valueTo = 100
        set(value) {
            field = max(value, valueFrom + 1)
            this.value = this.value.coerceIn(valueFrom, valueTo)
        }

    var value = 0
        set(value) {
            val next = value.coerceIn(valueFrom, valueTo)
            if (field == next) return
            field = next
            invalidate()
        }

    var onValueChanged: ((value: Int, fromUser: Boolean) -> Unit)? = null
    var onStopTracking: (() -> Unit)? = null

    init {
        minimumHeight = 48.dpInt
    }

    fun setRange(range: IntRange) {
        valueFrom = range.first
        valueTo = range.last
    }

    fun setColors(primary: Int, inactiveTrack: Int) {
        this.primary = primary
        this.inactiveTrack = inactiveTrack
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val trackStart = paddingLeft + thumbWidth / 2f
        val trackEnd = width - paddingRight - thumbWidth / 2f
        if (trackEnd <= trackStart) return

        val centerY = height / 2f
        val fraction = ((value - valueFrom).toFloat() / (valueTo - valueFrom)).coerceIn(0f, 1f)
        val thumbCenterX = trackStart + (trackEnd - trackStart) * fraction
        val thumbLeft = thumbCenterX - thumbWidth / 2f
        val thumbRight = thumbCenterX + thumbWidth / 2f
        val activeEnd = max(trackStart, thumbLeft - thumbTrackGap)
        val inactiveStart = min(trackEnd, thumbRight + thumbTrackGap)

        drawTrack(
            canvas = canvas,
            start = trackStart,
            end = activeEnd,
            centerY = centerY,
            color = primary,
            startRadius = trackRadius,
            endRadius = innerTrackRadius,
        )
        drawTrack(
            canvas = canvas,
            start = inactiveStart,
            end = trackEnd,
            centerY = centerY,
            color = inactiveTrack,
            startRadius = innerTrackRadius,
            endRadius = trackRadius,
        )

        val stopIndicatorCenterX = trackEnd - trackRadius
        val stopIndicatorCovered = thumbRight + thumbTrackGap >= stopIndicatorCenterX - stopIndicatorRadius
        if (!stopIndicatorCovered) {
            paint.color = primary
            canvas.drawCircle(stopIndicatorCenterX, centerY, stopIndicatorRadius, paint)
        }

        thumbBounds.set(
            thumbLeft,
            centerY - thumbHeight / 2f,
            thumbRight,
            centerY + thumbHeight / 2f,
        )
        paint.color = primary
        canvas.drawRoundRect(thumbBounds, thumbRadius, thumbRadius, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                updateValue(event.x, fromUser = true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                updateValue(event.x, fromUser = true)
                true
            }
            MotionEvent.ACTION_UP -> {
                updateValue(event.x, fromUser = true)
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                onStopTracking?.invoke()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onStopTracking?.invoke()
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateValue(x: Float, fromUser: Boolean) {
        val trackStart = paddingLeft + thumbWidth / 2f
        val trackEnd = width - paddingRight - thumbWidth / 2f
        val fraction = if (trackEnd <= trackStart) {
            0f
        } else {
            ((x - trackStart) / (trackEnd - trackStart)).coerceIn(0f, 1f)
        }
        val next = (valueFrom + (valueTo - valueFrom) * fraction).toInt().coerceIn(valueFrom, valueTo)
        if (value == next) return
        value = next
        onValueChanged?.invoke(next, fromUser)
    }

    private fun drawTrack(
        canvas: Canvas,
        start: Float,
        end: Float,
        centerY: Float,
        color: Int,
        startRadius: Float,
        endRadius: Float,
    ) {
        if (end <= start) return
        trackBounds.set(start, centerY - trackHeight / 2f, end, centerY + trackHeight / 2f)
        paint.color = color
        trackPath.reset()
        trackPath.addRoundRect(
            trackBounds,
            floatArrayOf(
                startRadius,
                startRadius,
                endRadius,
                endRadius,
                endRadius,
                endRadius,
                startRadius,
                startRadius,
            ),
            Path.Direction.CW,
        )
        canvas.drawPath(trackPath, paint)
    }

    private val Int.dp: Float
        get() = this * density

    private val Int.dpInt: Int
        get() = (this * density).toInt()
}
