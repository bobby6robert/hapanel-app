package dev.hapanel

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class Response(val code: Int, val body: ByteArray, val type: String = "application/json")

/** Minimalny serwer GET bez zależności. handler(path, params) -> Response (JSON albo obraz). */
class HttpServer(private val port: Int, private val handler: (String, Map<String, String>) -> Response) {
    private var ss: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        thread(isDaemon = true) {
            try {
                ss = ServerSocket(port)
                while (true) {
                    val s = ss!!.accept()
                    pool.execute { serve(s) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        try { ss?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 5000
            val r = BufferedReader(InputStreamReader(s.getInputStream()))
            val first = r.readLine() ?: return
            val target = first.split(" ").getOrNull(1) ?: "/"
            while (true) {
                val h = r.readLine()
                if (h.isNullOrEmpty()) break
            }
            val path = target.substringBefore('?')
            val params = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val result = handler(path, params)
            val head = "HTTP/1.1 ${result.code} OK\r\nContent-Type: ${result.type}\r\nContent-Length: ${result.body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            val out = s.getOutputStream()
            out.write(head.toByteArray())
            out.write(result.body)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }
}
