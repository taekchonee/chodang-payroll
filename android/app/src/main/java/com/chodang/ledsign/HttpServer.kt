package com.chodang.ledsign

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * HA 가 보내는 명령을 받는 아주 작은 웹 서버 (같은 매장 네트워크 안에서만 씀).
 *
 *   GET  /status
 *   GET  /show?key=..&text=영업중&color=green&mode=auto
 *   POST /show  {"key": "..", "text": "영업중", "color": "green", "mode": "auto"}
 *   GET  /on?key=..   /off?key=..   /brightness?key=..&value=200
 */
class HttpServer(
    private val port: Int,
    private val accessKey: () -> String,
    private val controller: SignController,
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    fun start() {
        if (server != null) return
        val s = ServerSocket(port)
        server = s
        Thread({
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (e: Exception) {
                    if (!s.isClosed) Log.w("HttpServer", "accept failed", e)
                }
            }
        }, "led-http").start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 10_000
            val input = BufferedInputStream(sock.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val target = parts.getOrElse(1) { "/" }
            val path = target.substringBefore('?')
            val params = parseQuery(target.substringAfter('?', "")).toMutableMap()

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (method == "POST" && length > 0) {
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val text = String(body, 0, read, Charsets.UTF_8)
                if (headers["content-type"]?.contains("json") == true || text.trimStart().startsWith("{")) {
                    try {
                        val json = JSONObject(text)
                        json.keys().forEach { k -> params[k] = json.get(k).toString() }
                    } catch (_: Exception) {}
                } else {
                    params.putAll(parseQuery(text))
                }
            }
            headers["x-key"]?.let { params.putIfAbsent("key", it) }

            val (code, result) = route(path, params)
            val bytes = result.toString().toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 $code\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(bytes)
            out.flush()
        }
    }

    private fun route(path: String, p: Map<String, String>): Pair<String, JSONObject> {
        if (path == "/status") {
            val (w, h) = controller.signSize
            return "200 OK" to JSONObject()
                .put("ok", true)
                .put("last_text", controller.lastText ?: JSONObject.NULL)
                .put("last_status", controller.lastStatus)
                .put("sign", controller.signName ?: JSONObject.NULL)
                .put("width", w).put("height", h)
        }
        if (p["key"] != accessKey()) {
            return "403 Forbidden" to JSONObject().put("ok", false).put("message", "접속 키가 틀립니다")
        }
        val message = when (path) {
            "/show" -> {
                val text = p["text"]?.takeIf { it.isNotBlank() }
                    ?: return "400 Bad Request" to JSONObject().put("ok", false).put("message", "text 가 없습니다")
                controller.show(
                    ShowRequest(
                        text = text,
                        color = LedColor.parse(p["color"]),
                        mode = p["mode"] ?: "auto",
                        speed = p["speed"]?.toIntOrNull(),
                        brightness = p["brightness"]?.toIntOrNull(),
                    )
                )
            }
            "/on" -> controller.power(true)
            "/off" -> controller.power(false)
            "/brightness" -> controller.brightness(p["value"]?.toIntOrNull() ?: 255)
            else -> return "404 Not Found" to JSONObject().put("ok", false).put("message", "없는 주소: $path")
        }
        val ok = message.contains("성공")
        return (if (ok) "200 OK" else "500 Internal Server Error") to
            JSONObject().put("ok", ok).put("message", message)
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            val k = URLDecoder.decode(it.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(it.substringAfter('='), "UTF-8")
            k to v
        }

    /** CRLF 로 끝나는 한 줄을 UTF-8 로 읽습니다. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (c == '\n'.code) break
            if (c != '\r'.code) buf.write(c)
            if (buf.size() > 16_384) break
        }
        return buf.toString("UTF-8")
    }
}
