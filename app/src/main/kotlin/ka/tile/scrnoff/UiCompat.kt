package ka.tile.scrnoff

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build

@Suppress("DEPRECATION")
fun Context.colorCompat(resId: Int): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        getColor(resId)
    } else {
        resources.getColor(resId)
    }

fun Context.averageSystemColor(firstName: String, secondName: String): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val firstId = resources.getIdentifier(firstName, "color", "android")
    val secondId = resources.getIdentifier(secondName, "color", "android")
    if (firstId == 0 || secondId == 0) return null

    val first = resources.getColor(firstId, theme)
    val second = resources.getColor(secondId, theme)
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
