package ka.tile.scrnoff

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import kotlin.math.roundToInt

fun Context.colorCompat(resId: Int): Int = getColor(resId)

fun surfaceColorAtOneDp(surface: Int, tint: Int): Int {
    fun channel(surfaceChannel: Int, tintChannel: Int) =
        (surfaceChannel + (tintChannel - surfaceChannel) * SURFACE_TINT_ALPHA_1_DP).roundToInt()

    return Color.rgb(
        channel(Color.red(surface), Color.red(tint)),
        channel(Color.green(surface), Color.green(tint)),
        channel(Color.blue(surface), Color.blue(tint)),
    )
}

val Context.isNightTheme: Boolean
    get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES ==
            Configuration.UI_MODE_NIGHT_YES

private const val SURFACE_TINT_ALPHA_1_DP = 0.05119162f
