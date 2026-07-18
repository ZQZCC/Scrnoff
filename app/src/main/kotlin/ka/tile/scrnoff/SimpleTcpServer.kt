package ka.tile.scrnoff

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class SimpleTcpServer(
    private val listener: TcpConnectionListener,
    port: Int,
) {
    interface TcpConnectionListener {
        fun onReceive(data: ByteArray, size: Int)
    }

    private val serverSocket: ServerSocket? = runCatching {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
    }.getOrNull()

    @Volatile
    private var running = false
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var output: BufferedOutputStream? = null

    @Synchronized
    fun start() {
        val server = serverSocket ?: return
        if (running) return
        running = true
        thread(name = "ScreenOffTcpAccept", isDaemon = true) {
            acceptLoop(server)
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        val buffer = ByteArray(CAPACITY)
        while (running) {
            val accepted = runCatching { server.accept() }.getOrNull() ?: break
            socket = accepted
            runCatching {
                accepted.soTimeout = READ_TIMEOUT_MS
                BufferedInputStream(accepted.getInputStream()).use { input ->
                    output = BufferedOutputStream(accepted.getOutputStream())
                    while (running && socket === accepted) {
                        val size = input.read(buffer)
                        if (size <= 0) break
                        listener.onReceive(buffer, size)
                    }
                }
            }
            closeClient(accepted)
        }
        running = false
        closeClient()
    }

    fun output(data: String) {
        output(data.toByteArray())
    }

    @Synchronized
    fun output(data: ByteArray) {
        val stream = output ?: return
        runCatching {
            stream.write(data)
            stream.flush()
        }
        closeClient()
    }

    @Synchronized
    fun stop() {
        running = false
        closeClient()
        serverSocket.closeQuietly()
    }

    @Synchronized
    private fun closeClient(expected: Socket? = null) {
        if (expected != null && socket !== expected) return
        output.closeQuietly()
        socket.closeQuietly()
        output = null
        socket = null
    }

    private fun Closeable?.closeQuietly() {
        runCatching { this?.close() }
    }

    private companion object {
        const val CAPACITY = 8 * 1024
        const val READ_TIMEOUT_MS = 5_000
    }
}
