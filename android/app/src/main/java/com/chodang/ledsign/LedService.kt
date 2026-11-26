package com.chodang.ledsign

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/** 화면이 꺼져 있어도 HA 명령을 받을 수 있게 계속 돌아가는 서비스 */
class LedService : Service() {

    companion object {
        const val PORT = 8765
        private const val CHANNEL_ID = "led_sign"

        @Volatile var instance: LedService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, LedService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /** 앱 화면에 표시할 접속 키. 처음 실행할 때 한 번 만들어 저장합니다. */
        fun accessKey(context: Context): String {
            val prefs = context.getSharedPreferences("sign", Context.MODE_PRIVATE)
            prefs.getString("access_key", null)?.let { return it }
            val key = (1..6).map { SecureRandom().nextInt(10) }.joinToString("")
            prefs.edit().putString("access_key", key).apply()
            return key
        }

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    lateinit var controller: SignController
        private set
    private var server: HttpServer? = null
    @Volatile var serverError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        controller = SignController(this)
        startAsForeground()
        try {
            server = HttpServer(PORT, { accessKey(this) }, controller).also { it.start() }
        } catch (e: Exception) {
            serverError = e.message
        }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        server?.stop()
        controller.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LED 간판", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notification = builder
            .setContentTitle("LED 간판 연결 대기 중")
            .setContentText("HA 명령을 받으면 입구 간판 문구를 바꿉니다")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }
}
