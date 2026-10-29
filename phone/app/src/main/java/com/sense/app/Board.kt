package com.sense.app

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayOutputStream

interface Camera {
    val link: String
    fun grab(timeoutMs: Long = 5000): ByteArray
}

/** firmware/snapwifi over HTTP, e.g. on the phone's hotspot. Used when USB carries power only. */
class WifiBoard(private val ip: String) : Camera {
    override val link = "wifi"

    private fun get(path: String, timeoutMs: Int): ByteArray {
        val c = java.net.URL("http://$ip$path").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.readBytes()
        } finally {
            c.disconnect()
        }
    }

    fun setSize(name: String) { get("/size?f=$name", 3000) }

    override fun grab(timeoutMs: Long): ByteArray {
        val jpeg = get("/capture", timeoutMs.toInt())
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte())
            throw IllegalStateException("not a JPEG (${jpeg.size} bytes)")
        return jpeg
    }
}

/** The ESP32-CAM over USB serial, same protocol as tools/snap.py and firmware/snap(wifi). */
class Board(private val port: UsbSerialPort) : Camera {
    override val link = "usb"
    private var pending = ByteArray(0)
    private val chunk = ByteArray(16384)

    fun send(cmd: Char) = port.write(byteArrayOf(cmd.code.toByte()), 500)

    fun drain() {
        while (port.read(chunk, 50) > 0) Unit
        pending = ByteArray(0)
    }

    private fun fill(timeoutMs: Int): Boolean {
        val n = port.read(chunk, timeoutMs)
        if (n <= 0) return false
        pending += chunk.copyOf(n)
        return true
    }

    private fun readLine(deadline: Long): String? {
        while (System.currentTimeMillis() < deadline) {
            val i = pending.indexOf('\n'.code.toByte())
            if (i >= 0) {
                val line = String(pending, 0, i).trim()
                pending = pending.copyOfRange(i + 1, pending.size)
                return line
            }
            fill(200)
        }
        return null
    }

    /** One current JPEG (the board drops its stale frame itself). */
    override fun grab(timeoutMs: Long): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        send('p')
        var line = readLine(deadline)
        while (line != null && !line.startsWith("JPG ")) line = readLine(deadline)
        val size = line?.removePrefix("JPG ")?.trim()?.toIntOrNull()
            ?: throw IllegalStateException("no reply from board")
        val out = ByteArrayOutputStream(size)
        val take = minOf(size, pending.size)
        out.write(pending, 0, take)
        pending = pending.copyOfRange(take, pending.size)
        while (out.size() < size && System.currentTimeMillis() < deadline) {
            val n = port.read(chunk, 200)
            if (n > 0) {
                val need = minOf(n, size - out.size())
                out.write(chunk, 0, need)
                if (need < n) pending += chunk.copyOfRange(need, n)
            }
        }
        val jpeg = out.toByteArray()
        if (jpeg.size != size || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            drain()
            throw IllegalStateException("corrupt JPEG (${jpeg.size}/$size bytes)")
        }
        return jpeg
    }
}
