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
 *  GET  /api/announce?since= → 직원 폰: 새 방송 문장 (없으면 25초까지 기다림)
 *  GET  /say           → 사장님 폰용 직접 방송 화면
 *  POST /api/say?text= → 직접 방송 (홈미니 + 직원 폰)
 */
class KdsServer(context: Context, private val kitchen: Kitchen, private val announcer: Announcer, private val onChange: () -> Unit) {

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

        fun q(name: String) = Regex("[?&]$name=([^&]*)").find(target)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        when {
            method == "GET" && (path == "/" || path == "/index.html") ->
                send(sock, 200, "text/html; charset=utf-8", RemoteConfig.kdsHtml())
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
            method == "GET" && path == "/api/announce" -> {
                sock.soTimeout = 40_000
                val since = q("since")?.toLongOrNull() ?: -1L
                val ip = sock.inetAddress?.hostAddress ?: "?"
                send(sock, 200, "application/json; charset=utf-8", announcer.poll(since, ip, 25_000).toString().toByteArray())
            }
            method == "GET" && path == "/say" ->
                send(sock, 200, "text/html; charset=utf-8", sayPage().toByteArray())
            method == "POST" && path == "/api/say" -> {
                val t = q("text")?.trim().orEmpty().take(200)
                if (t.isNotEmpty()) announcer.say(t)
                send(sock, 200, "application/json", "{\"ok\":${t.isNotEmpty()}}".toByteArray())
            }
            else -> send(sock, 404, "text/plain", "not found".toByteArray())
        }
    }

    /** 사장님 폰 크롬용: 문구 입력 + 자주 쓰는 문구 버튼 */
    private fun sayPage(): String {
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;") }
        val presets = RemoteConfig.sayPresets.joinToString("") { "<button class=p>" + esc(it) + "</button>" }
        return """<!doctype html><html lang=ko><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>직접 방송</title><style>
body{margin:0;font-family:-apple-system,"Noto Sans KR",sans-serif;background:#0F1117;color:#E8EAF0;padding:18px}
h1{font-size:20px;margin:0 0 4px}p{color:#888780;font-size:13px;margin:0 0 14px}
textarea{width:100%;box-sizing:border-box;height:110px;font-size:20px;border-radius:12px;border:1px solid #262A35;background:#181B23;color:#E8EAF0;padding:12px}
#go{width:100%;height:58px;margin-top:10px;border:none;border-radius:12px;background:#1D9E75;color:#fff;font-size:20px;font-weight:700}
.p{display:block;width:100%;margin-top:8px;height:50px;border-radius:10px;border:1px solid #262A35;background:#181B23;color:#E8EAF0;font-size:17px;text-align:left;padding:0 14px}
#m{margin-top:12px;color:#5DCAA5;min-height:20px}</style></head><body>
<h1>직접 방송</h1><p>매장주방홈미니와 주방 직원 폰으로 보냅니다. 매장 와이파이에서만 됩니다.</p>
<textarea id=t placeholder="보낼 문구"></textarea><button id=go>보내기</button><div id=m></div>
<p style="margin-top:18px">자주 쓰는 문구 (누르면 바로 보냄)</p>$presets
<script>
async function send(x){x=x.trim();if(!x)return;m.textContent='보내는 중…';
try{const r=await fetch('/api/say?text='+encodeURIComponent(x),{method:'POST'});m.textContent=r.ok?'보냄: '+x:'실패'}catch(e){m.textContent='실패 (매장 와이파이인지 확인)'}}
go.onclick=()=>{send(t.value);t.value=''};
document.querySelectorAll('.p').forEach(b=>b.onclick=()=>send(b.textContent));
</script></body></html>"""
    }

    private fun send(sock: Socket, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        sock.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
    }
}
