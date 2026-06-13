package ka.tile.scrnoff

import android.annotation.SuppressLint
import android.os.IBinder
import java.lang.reflect.Method

@SuppressLint("PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
object DisplayControl {
    private val booleanType = Boolean::class.javaPrimitiveType!!
    private val intType = Int::class.javaPrimitiveType!!
    private val longType = Long::class.javaPrimitiveType!!

    private val displayControlClass: Class<*>? = runCatching {
        val classLoaderFactory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val createClassLoader = classLoaderFactory.getDeclaredMethod(
            "createClassLoader",
            String::class.java,
            String::class.java,
            String::class.java,
            ClassLoader::class.java,
            intType,
            booleanType,
            String::class.java,
        )
        val classLoader = createClassLoader.invoke(
            null,
            "/system/framework/services.jar",
            null,
            null,
            ClassLoader.getSystemClassLoader(),
            0,
            true,
            null,
        ) as ClassLoader

        classLoader.loadClass("com.android.server.display.DisplayControl").also { displayControl ->
            val loadLibrary = Runtime::class.java.getDeclaredMethod(
                "loadLibrary0",
                Class::class.java,
                String::class.java,
            )
            loadLibrary.isAccessible = true
            loadLibrary.invoke(Runtime.getRuntime(), displayControl, "android_servers")
        }
    }.getOrNull()

    private var getPhysicalDisplayTokenMethod: Method? = null
    private var getPhysicalDisplayIdsMethod: Method? = null

    fun getPhysicalDisplayToken(physicalDisplayId: Long): IBinder? =
        runCatching {
            physicalDisplayTokenMethod()?.invoke(null, physicalDisplayId) as? IBinder
        }.getOrNull()

    fun getPhysicalDisplayIds(): LongArray? =
        runCatching {
            physicalDisplayIdsMethod()?.invoke(null) as? LongArray
        }.getOrNull()

    private fun physicalDisplayTokenMethod(): Method? {
        val clazz = displayControlClass ?: return null
        return getPhysicalDisplayTokenMethod ?: clazz
            .getMethod("getPhysicalDisplayToken", longType)
            .also { getPhysicalDisplayTokenMethod = it }
    }

    private fun physicalDisplayIdsMethod(): Method? {
        val clazz = displayControlClass ?: return null
        return getPhysicalDisplayIdsMethod ?: clazz
            .getMethod("getPhysicalDisplayIds")
            .also { getPhysicalDisplayIdsMethod = it }
    }
}
