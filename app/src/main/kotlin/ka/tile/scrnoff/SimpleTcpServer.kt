package ka.tile.scrnoff

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class SimpleTcpServer(
    private val listener: TcpConnectionListener,
    port: Int,
) {
    interface TcpConnectionListener {
        fun onReceive(data: ByteArray)
        fun onResponseSent()
    }

    private val serverSocket: ServerSocket? = runCatching {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
    }.getOrNull()

    @Volatile private var socket: Socket? = null
    @Volatile private var input: BufferedInputStream? = null
    @Volatile private var output: OutputStream? = null

    fun start() {
        val server = serverSocket ?: return
        thread(name = "ScreenOffTcpAccept", isDaemon = true) {
            runCatching {
                val accepted = server.accept()
                socket = accepted
                input = BufferedInputStream(accepted.getInputStream())
                output = BufferedOutputStream(accepted.getOutputStream())
                startInputThread()
            }
        }
    }

    private fun startInputThread() {
        thread(name = "ScreenOffTcpInput", isDaemon = true) {
            val buffer = ByteArray(CAPACITY)
            while (true) {
                val stream = input ?: break
                val size = runCatching { stream.read(buffer) }.getOrElse {
                    restart()
                    break
                }
                if (size > 0) {
                    listener.onReceive(buffer.copyOf(size))
                } else {
                    restart()
                    break
                }
            }
        }
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
            listener.onResponseSent()
        }.onFailure {
            restart()
        }
    }

    fun restart() {
        closeClient()
        start()
    }

    fun stop() {
        closeClient()
        serverSocket.closeQuietly()
    }

    private fun closeClient() {
        input.closeQuietly()
        output.closeQuietly()
        socket.closeQuietly()
        input = null
        output = null
        socket = null
    }

    private fun Closeable?.closeQuietly() {
        runCatching { this?.close() }
    }

    private companion object {
        const val CAPACITY = 8 * 1024
    }
}
