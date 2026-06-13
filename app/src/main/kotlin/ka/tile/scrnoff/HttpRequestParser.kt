package ka.tile.scrnoff

import java.io.ByteArrayOutputStream

class HttpRequestParser {
    private val rawData = ByteArrayOutputStream()

    fun add(data: ByteArray) {
        if (rawData.size() + data.size > MAX_REQUEST_BYTES) {
            rawData.reset()
        }
        rawData.write(data)
    }

    fun clear() {
        rawData.reset()
    }

    fun parse(): HttpRequest? {
        val firstLine = firstLine() ?: return null
        return parseTarget(firstLine)?.let(::HttpRequest)
    }

    private fun parseTarget(line: String): String? {
        val methodEnd = line.indexOf(' ')
        if (methodEnd <= 0) return null
        val targetEnd = line.indexOf(' ', methodEnd + 1)
        if (targetEnd <= methodEnd + 1) return null
        return line.substring(methodEnd + 1, targetEnd)
    }

    private fun firstLine(): String? {
        val bytes = rawData.toByteArray()
        if (bytes.isEmpty() || bytes.first() == CR || bytes.first() == LF) return null
        val end = bytes.indexOfFirst { it == CR || it == LF }
        if (end < 0) return null
        return bytes.copyOfRange(0, end).toString(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 8 * 1024
        const val LF: Byte = 0x0a
        const val CR: Byte = 0x0d
    }
}
