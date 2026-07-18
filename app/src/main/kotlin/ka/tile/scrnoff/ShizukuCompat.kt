package ka.tile.scrnoff

import rikka.shizuku.Shizuku

object ShizukuCompat {
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply {
            isAccessible = true
        }
    }

    fun runShell(command: String) {
        val process = newProcess.invoke(null, arrayOf("sh"), null, null) as Process
        process.outputStream.bufferedWriter().use { writer ->
            writer.appendLine(command)
            writer.appendLine("exit")
        }
        check(process.waitFor() == 0)
    }
}
