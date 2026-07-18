package ka.tile.scrnoff

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build

fun Context.colorCompat(resId: Int): Int = getColor(resId)

fun Context.averageSystemColor(firstResId: Int, secondResId: Int): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val first = getColor(firstResId)
    val second = getColor(secondResId)
    return Color.argb(
        (Color.alpha(first) + Color.alpha(second) + 1) / 2,
        (Color.red(first) + Color.red(second) + 1) / 2,
        (Color.green(first) + Color.green(second) + 1) / 2,
        (Color.blue(first) + Color.blue(second) + 1) / 2,
    )
}

val Context.isNightTheme: Boolean
    get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES ==
            Configuration.UI_MODE_NIGHT_YES
