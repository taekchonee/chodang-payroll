package com.chodang.ledsign

import java.io.ByteArrayOutputStream

/**
 * CoolLEDX(CoolLED1248 앱용) 패널의 블루투스 명령 형식.
 *
 * 공개 드라이버 coolledx(MIT, https://github.com/UpDryTwist/coolledx-driver)의
 * commands.py / render.py 를 코틀린으로 옮긴 것입니다.
 */
object CoolLedProtocol {
    const val CMD_TEXT = 0x02
    const val CMD_MODE = 0x06
    const val CMD_SPEED = 0x07
    const val CMD_BRIGHTNESS = 0x08
    const val CMD_ON_OFF = 0x09

    /** 흐름 방식 */
    enum class Mode(val code: Int) {
        STATIC(0x01), LEFT(0x02), RIGHT(0x03), UP(0x04), DOWN(0x05), SNOW(0x06), LASER(0x08)
    }

    /** 0x01, 0x02, 0x03 은 시작/이스케이프/끝 표시라서 0x02 + (값+4) 로 바꿔 보냅니다. */
    fun escape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        for (b in data) {
            when (val v = b.toInt() and 0xFF) {
                0x01, 0x02, 0x03 -> {
                    out.write(0x02); out.write(v + 0x04)
                }
                else -> out.write(v)
            }
        }
        return out.toByteArray()
    }

    /** 0x01 + escape(길이 2바이트 + 데이터) + 0x03 */
    fun frame(raw: ByteArray): ByteArray {
        val withLen = ByteArray(raw.size + 2)
        withLen[0] = ((raw.size shr 8) and 0xFF).toByte()
        withLen[1] = (raw.size and 0xFF).toByte()
        System.arraycopy(raw, 0, withLen, 2, raw.size)
        val escaped = escape(withLen)
        val out = ByteArray(escaped.size + 2)
        out[0] = 0x01
        System.arraycopy(escaped, 0, out, 1, escaped.size)
        out[out.size - 1] = 0x03
        return out
    }

    private fun simple(cmd: Int, value: Int) = listOf(frame(byteArrayOf(cmd.toByte(), value.toByte())))

    fun setMode(mode: Mode) = simple(CMD_MODE, mode.code)
    fun setSpeed(speed: Int) = simple(CMD_SPEED, speed.coerceIn(0, 255))
    fun setBrightness(brightness: Int) = simple(CMD_BRIGHTNESS, brightness.coerceIn(0, 255))
    fun setOn(on: Boolean) = simple(CMD_ON_OFF, if (on) 1 else 0)

    /** 큰 데이터를 128바이트 조각으로 나누고, 조각마다 헤더와 XOR 체크섬을 붙여 프레임으로 만듭니다. */
    fun chopUp(data: ByteArray, command: Int): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        var chunkId = 0
        do {
            val len = minOf(128, data.size - offset)
            val body = ByteArrayOutputStream()
            body.write(0x00)
            body.write((data.size shr 8) and 0xFF); body.write(data.size and 0xFF)
            body.write((chunkId shr 8) and 0xFF); body.write(chunkId and 0xFF)
            body.write(len)
            body.write(data, offset, len)
            val bytes = body.toByteArray()
            var checksum = 0
            for (b in bytes) checksum = checksum xor (b.toInt() and 0xFF)
            val raw = ByteArray(bytes.size + 2)
            raw[0] = command.toByte()
            System.arraycopy(bytes, 0, raw, 1, bytes.size)
            raw[raw.size - 1] = checksum.toByte()
            frames.add(frame(raw))
            offset += len
            chunkId++
        } while (offset < data.size)
        return frames
    }

    /**
     * 글자 이미지 데이터.
     * 24바이트 0 + 글자 수(1) + 글자 메타(80) + 픽셀 데이터 길이(2) + R/G/B 비트 묶음
     */
    fun textPayload(textLength: Int, pixelBits: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(24))
        out.write(textLength.coerceIn(0, 255))
        val meta = ByteArray(80)
        for (i in 0 until minOf(textLength, 80)) meta[i] = 0x30
        out.write(meta)
        out.write((pixelBits.size shr 8) and 0xFF); out.write(pixelBits.size and 0xFF)
        out.write(pixelBits)
        return out.toByteArray()
    }
}
