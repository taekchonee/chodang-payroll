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

/** 화면이 꺼져 있어도 시간표대로 간판을 바꾸도록 계속 돌아가는 서비스 */
class LedService : Service() {

    companion object {
        private const val CHANNEL_ID = "led_sign"

        @Volatile var instance: LedService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, LedService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

    }

    lateinit var controller: SignController
        private set
    lateinit var schedule: StoreSchedule
        private set
    lateinit var scheduler: AutoScheduler
        private set

    override fun onCreate() {
        super.onCreate()
        controller = SignController(this)
        schedule = StoreSchedule(this)
        scheduler = AutoScheduler(schedule, controller)
        startAsForeground()
        instance = this
        scheduler.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        scheduler.stop()
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
            .setContentTitle("LED 간판 자동 전환 중")
            .setContentText("영업 시간표에 맞춰 입구 간판 문구를 바꿉니다")
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
