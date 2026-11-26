package com.chodang.ledsign

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 패널과 블루투스로 연결해 프레임을 보냅니다.
 * 한 번 보낼 때마다 연결 → 전송 → 연결 해제합니다.
 * (계속 붙잡고 있으면 CoolLED1248 앱으로 패널에 연결할 수 없어서입니다.)
 *
 * 모든 함수는 백그라운드 스레드에서 호출해야 합니다.
 */
@SuppressLint("MissingPermission")
class CoolLedBle(private val context: Context) {

    data class SignInfo(val address: String, val name: String, val width: Int, val height: Int)

    companion object {
        private const val TAG = "CoolLedBle"
        const val NAME_PREFIX = "CoolLEDX"
        val CHAR_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val DEFAULT_WIDTH = 96
        const val DEFAULT_HEIGHT = 16
    }

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** 마지막으로 찾은 패널 (주소와 크기) */
    @Volatile var lastSign: SignInfo? = null
        private set

    /** 이름이 CoolLEDX 로 시작하는 패널을 찾습니다. */
    fun scan(timeoutMs: Long = 10_000): SignInfo {
        val ad = adapter ?: throw IllegalStateException("이 기기에 블루투스가 없습니다")
        if (!ad.isEnabled) throw IllegalStateException("블루투스가 꺼져 있습니다")
        val scanner = ad.bluetoothLeScanner ?: throw IllegalStateException("블루투스 스캔을 쓸 수 없습니다")

        var found: SignInfo? = null
        val latch = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (!name.startsWith(NAME_PREFIX) || found != null) return
                // 제조사 데이터: [0..5] MAC, [6] 높이, [8] 폭
                var w = DEFAULT_WIDTH
                var h = DEFAULT_HEIGHT
                val md = result.scanRecord?.manufacturerSpecificData
                if (md != null && md.size() > 0) {
                    val v = md.valueAt(0)
                    if (v != null && v.size > 8) {
                        val mh = v[6].toInt() and 0xFF
                        val mw = v[8].toInt() and 0xFF
                        if (mh > 0 && mh % 8 == 0) h = mh
                        if (mw > 0) w = mw
                    }
                }
                found = SignInfo(result.device.address, name, w, h)
                latch.countDown()
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed $errorCode")
                latch.countDown()
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            try { scanner.stopScan(cb) } catch (_: Exception) {}
        }
        val sign = found ?: throw IllegalStateException("주변에서 CoolLEDX 패널을 찾지 못했습니다")
        lastSign = sign
        return sign
    }

    /** 패널 크기만 필요할 때: 이전에 찾은 값이 있으면 그대로 씁니다. */
    fun signInfo(): SignInfo = lastSign ?: scan()

    /** 프레임 목록을 차례로 보냅니다. 실패하면 몇 번 다시 시도합니다. */
    fun send(frames: List<ByteArray>, retries: Int = 3) {
        var lastError: Exception? = null
        repeat(retries) { attempt ->
            try {
                val sign = if (attempt == 0) signInfo() else scan()
                sendOnce(sign, frames)
                return
            } catch (e: Exception) {
                Log.w(TAG, "send attempt ${attempt + 1} failed", e)
                lastError = e
                Thread.sleep(1000)
            }
        }
        throw lastError ?: IllegalStateException("전송 실패")
    }

    private fun sendOnce(sign: SignInfo, frames: List<ByteArray>) {
        val device: BluetoothDevice = adapter!!.getRemoteDevice(sign.address)
        val session = GattSession()
        val gatt = device.connectGatt(context, false, session, BluetoothDevice.TRANSPORT_LE)
            ?: throw IllegalStateException("블루투스 연결을 시작하지 못했습니다")
        session.gatt = gatt
        try {
            session.await(session.connected, 10_000, "연결")
            if (!gatt.discoverServices()) throw IllegalStateException("서비스 검색 시작 실패")
            session.await(session.discovered, 10_000, "서비스 검색")

            // 한 번에 큰 조각을 보내려고 MTU 를 늘려 봅니다. 안 되면 기본값(23)으로 잘게 보냅니다.
            session.mtuLatch = CountDownLatch(1)
            if (gatt.requestMtu(247)) session.mtuLatch.await(3, TimeUnit.SECONDS)

            val ch = gatt.services.firstNotNullOfOrNull { it.getCharacteristic(CHAR_UUID) }
                ?: throw IllegalStateException("패널의 통신 채널(fff1)을 찾지 못했습니다")

            enableNotify(gatt, ch, session)

            val withResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            ch.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val piece = maxOf(20, session.mtu - 3)

            for (frame in frames) {
                var off = 0
                while (off < frame.size) {
                    val end = minOf(frame.size, off + piece)
                    write(gatt, ch, frame.copyOfRange(off, end), session)
                    off = end
                }
                Thread.sleep(30)
            }
            Thread.sleep(300)
        } finally {
            try { gatt.disconnect() } catch (_: Exception) {}
            Thread.sleep(200)
            try { gatt.close() } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, s: GattSession) {
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        s.descLatch = CountDownLatch(1)
        if (gatt.writeDescriptor(cccd)) s.descLatch.await(3, TimeUnit.SECONDS)
    }

    @Suppress("DEPRECATION")
    private fun write(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray, s: GattSession) {
        repeat(5) {
            s.writeLatch = CountDownLatch(1)
            ch.value = data
            if (gatt.writeCharacteristic(ch)) {
                if (!s.writeLatch.await(3, TimeUnit.SECONDS)) throw IllegalStateException("전송 응답 없음")
                if (s.writeStatus != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("전송 오류 ${s.writeStatus}")
                return
            }
            Thread.sleep(50) // 이전 전송이 아직 안 끝났으면 잠깐 기다렸다 다시
        }
        throw IllegalStateException("전송 시작 실패")
    }

    private class GattSession : BluetoothGattCallback() {
        var gatt: BluetoothGatt? = null
        val connected = CountDownLatch(1)
        val discovered = CountDownLatch(1)
        @Volatile var mtuLatch = CountDownLatch(1)
        @Volatile var descLatch = CountDownLatch(1)
        @Volatile var writeLatch = CountDownLatch(1)
        @Volatile var writeStatus = BluetoothGatt.GATT_SUCCESS
        @Volatile var mtu = 23
        @Volatile var failure: String? = null

        fun await(latch: CountDownLatch, ms: Long, what: String) {
            if (!latch.await(ms, TimeUnit.MILLISECONDS)) throw IllegalStateException("$what 시간 초과")
            failure?.let { throw IllegalStateException(it) }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.countDown()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                failure = "연결이 끊어졌습니다 (상태 $status)"
                connected.countDown(); discovered.countDown()
                mtuLatch.countDown(); descLatch.countDown()
                writeStatus = -1; writeLatch.countDown()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) failure = "서비스 검색 실패 ($status)"
            discovered.countDown()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            mtuLatch.countDown()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descLatch.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeStatus = status
            writeLatch.countDown()
        }
    }
}
