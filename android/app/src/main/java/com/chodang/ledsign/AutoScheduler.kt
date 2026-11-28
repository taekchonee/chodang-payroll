package com.chodang.ledsign

import android.util.Log

/**
 * 30초마다 시간표를 확인해서, 띄워야 할 상태가 바뀌었으면 간판에 보냅니다.
 * - 앱(포스기)이 켜질 때 바로 한 번 맞춥니다.
 * - 전송에 실패하면 2분 뒤 다시 시도합니다.
 * - 직접 보낸 문구는 다음 시간표 전환 때까지 그대로 둡니다.
 */
class AutoScheduler(
    private val schedule: StoreSchedule,
    private val controller: SignController,
) {
    @Volatile private var applied: StoreStatus? = null
    @Volatile private var lastFailAt = 0L
    @Volatile private var running = false
    private var thread: Thread? = null
    private val lock = Object()

    @Volatile var lastMessage: String = "-"
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "led-auto").also { it.start() }
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
    }

    /** 설정을 바꿨을 때 바로 다시 계산합니다. */
    fun kick(forceResend: Boolean = false) {
        if (forceResend) { applied = null; lastFailAt = 0 }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun loop() {
        while (running) {
            try {
                tick()
            } catch (e: Exception) {
                Log.w("AutoScheduler", "tick failed", e)
            }
            synchronized(lock) { lock.wait(30_000) }
        }
    }

    private fun tick() {
        if (!schedule.autoEnabled) return
        val target = schedule.target()
        if (target == applied) return
        val now = System.currentTimeMillis()
        if (lastFailAt > 0 && now - lastFailAt < 120_000) return

        val result = controller.show(schedule.request(target))
        lastMessage = "자동 전환(${target.label}) → $result"
        controller.sendLog(lastMessage)
        if (result.contains("성공")) {
            applied = target
            lastFailAt = 0
        } else {
            lastFailAt = now
        }
    }
}
