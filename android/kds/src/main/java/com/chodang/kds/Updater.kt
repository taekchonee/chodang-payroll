package com.chodang.kds

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 앱 안에서 업데이트: GitHub 릴리스의 version.json 으로 새 판이 있는지 보고,
 * 받아서 안드로이드 설치 창을 띄웁니다(사람이 '설치'를 한 번 눌러야 함).
 * 세 앱(LED 간판·서빙 표시·주방 화면)이 같은 코드를 씁니다.
 */
class Updater(private val act: Activity, private val apkName: String) {

    companion object {
        const val BASE = "https://github.com/taekchonee/chodang-payroll/releases/download/led-sign-latest/"
        private var registered = false

        fun get(url: String): ByteArray = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 60000; instanceFollowRedirects = true; useCaches = false
            try { inputStream.use { it.readBytes() } } finally { disconnect() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val action = act.packageName + ".INSTALL_RESULT"

    fun currentCode(): Long {
        val p = act.packageManager.getPackageInfo(act.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    }

    /** (최신 번호 또는 null, 지금 번호) */
    fun check(done: (Long?, Long) -> Unit) = thread {
        val latest = runCatching {
            JSONObject(get(BASE + "version.json?t=" + System.currentTimeMillis()).toString(Charsets.UTF_8)).optLong(apkName.removeSuffix(".apk"), -1L)
        }.getOrNull()?.takeIf { it > 0 }
        main.post { done(latest, currentCode()) }
    }

    fun install(msg: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            msg("처음 한 번: 이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 다시 눌러 주세요")
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
            return
        }
        msg("새 버전 받는 중…")
        thread {
            try {
                val apk = get(BASE + apkName + "?t=" + System.currentTimeMillis())
                val pi = act.packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(id).use { s ->
                    s.openWrite("app.apk", 0, apk.size.toLong()).use { o -> o.write(apk); s.fsync(o) }
                    listen()
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val p = PendingIntent.getBroadcast(act, id, Intent(action).setPackage(act.packageName), flags)
                    s.commit(p.intentSender)
                }
                main.post { msg("설치 창에서 '설치' 또는 '업데이트'를 눌러 주세요") }
            } catch (e: Exception) {
                main.post { msg("업데이트 실패: ${e.message}") }
            }
        }
    }

    /** 설치 확인 창 띄우기 */
    private fun listen() {
        if (registered) return
        registered = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -99) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    @Suppress("DEPRECATION")
                    val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
        val f = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) act.applicationContext.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else act.applicationContext.registerReceiver(r, f)
    }
}
