package com.chodang.servemark

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * 주방 태블릿용 아주 작은 웹 서버 (매장 와이파이 안에서만, 포트 8080).
 *  GET  /             → 주방 화면 (assets/kds.html)
 *  GET  /api/state    → 주문표 JSON
 *  POST /api/cook?id= → 조리완료
 *  POST /api/undo?id= → 되돌리기
 */
class KdsServer(context: Context, private val kitchen: Kitchen, private val onChange: () -> Unit) {

    companion object { const val PORT = 8080 }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        thread(name = "kds-server", isDaemon = true) {
            try {
                val s = ServerSocket(PORT).also { server = it }
                Diag.add("주방 화면 서버 시작 (포트 $PORT)")
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: SocketException) { break }
                    thread(isDaemon = true) { handle(c) }
                }
            } catch (e: Exception) {
                Diag.add("주방 화면 서버 오류: ${e.message}")
                server = null
            }
        }
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun handle(c: Socket) = c.use { sock ->
        sock.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        val first = reader.readLine() ?: return@use
        // 헤더는 읽고 버립니다(본문 없음).
        while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
        val parts = first.split(" ")
        val method = parts.getOrNull(0) ?: ""
        val target = parts.getOrNull(1) ?: "/"
        val path = target.substringBefore('?')
        val id = Regex("id=(\\d+)").find(target)?.groupValues?.get(1)?.toLongOrNull()

        when {
            method == "GET" && (path == "/" || path == "/index.html") ->
                send(sock, 200, "text/html; charset=utf-8", app.assets.open("kds.html").use { it.readBytes() })
            method == "GET" && path == "/api/state" ->
                send(sock, 200, "application/json; charset=utf-8", kitchen.json().toByteArray())
            method == "POST" && path == "/api/cook" && id != null -> {
                main.post { kitchen.cook(id, System.currentTimeMillis()); Diag.add("주방 조리완료: $id"); onChange() }
                send(sock, 200, "application/json", "{\"ok\":true}".toByteArray())
            }
            method == "POST" && path == "/api/undo" && id != null -> {
                main.post { kitchen.undo(id); Diag.add("주방 되돌리기: $id"); onChange() }
                send(sock, 200, "application/json", "{\"ok\":true}".toByteArray())
            }
            else -> send(sock, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun send(sock: Socket, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        sock.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
    }
}
