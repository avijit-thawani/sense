package com.sense.app

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

interface Camera {
    val link: String
    /** Set by cameras whose photo sizes aren't the boards' size letters (the glasses). */
    val sizeLabel: String? get() = null
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

/** A board over USB serial: the ESP32-CAM through its CH340 (firmware/snap, snapwifi), or the
 *  XIAO ESP32S3 on its native USB (firmware/xiao). Same letters for both; the XIAO's USB doesn't
 *  lose bytes, so its photos come in one piece instead of checked blocks. */
class Board(private val port: UsbSerialPort, val xiao: Boolean) : Camera {
    override val link = "usb"
    val name get() = if (xiao) "XIAO" else "ESP32-CAM"
    private var pending = ByteArray(0)

    // Reads must be queued back to back: the CH340 holds only a few bytes, and at 460800 baud
    // the gaps between blocking port.read() calls dropped most of each photo on the phone.
    private val inbox = LinkedBlockingQueue<ByteArray>()
    @Volatile private var readError: Exception? = null
    private val reader = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) { inbox.put(data) }
        override fun onRunError(e: Exception) { readError = e }
    }).apply { readTimeout = 0; readBufferSize = 16384; start() }

    private fun next(timeoutMs: Int): ByteArray? {
        readError?.let { throw IllegalStateException("USB read failed: ${it.message}") }
        return inbox.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    }

    fun close() = reader.stop()

    fun send(cmd: Char) = port.write(byteArrayOf(cmd.code.toByte()), 500)

    /** Restart the board (RTS drives EN on the MB adapter) and return what it prints while booting.
     *  The XIAO isn't restarted: that would drop its USB connection. It reports its state instead. */
    fun restart(): String {
        if (xiao) {
            port.dtr = true
            port.rts = false
            drain(100)
            send('i')
            val end = System.currentTimeMillis() + 1500
            val lines = generateSequence { readLine(end) }.filter { "CAMERA" in it || "IP " in it }.take(2).toList()
            return "XIAO: " + lines.joinToString(" | ").ifEmpty { "no reply (is firmware/xiao flashed?)" }
        }
        port.dtr = false
        port.rts = true
        Thread.sleep(150)
        port.rts = false
        heard = 0
        lastHeard = ByteArray(0)
        pending = ByteArray(0)
        val end = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < end && !String(pending).contains("READY")) fill(200)
        val text = String(pending).lines().filter { "READY" in it || "CAMERA" in it || "ERR" in it }
        return "boot ${heardText()}; " + text.joinToString(" | ").take(200)
    }

    /** The board's address on Wi-Fi (firmware/snapwifi 'i'), or null if it hasn't joined within the time. */
    fun wifiIp(timeoutMs: Long): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            drain(10)
            send('i')
            val line = readLine(System.currentTimeMillis() + 600)
            if (line != null && line.startsWith("IP ") && !line.startsWith("IP none"))
                return line.split(" ")[1]
            Thread.sleep(400)
        }
        return null
    }

    fun drain(timeoutMs: Int = 50) {
        while (next(timeoutMs) != null) Unit
        pending = ByteArray(0)
    }

    private var heard = 0
    private var lastHeard = ByteArray(0)

    private fun fill(timeoutMs: Int): Boolean {
        val data = next(timeoutMs) ?: return false
        pending += data
        heard += data.size
        lastHeard = (lastHeard + data).takeLast(24).toByteArray()
        return true
    }

    /** What the board sent since the last photo; tells a wrong baud rate (garbage) from silence. */
    private fun heardText(): String = if (heard == 0) "heard nothing" else
        "heard $heard bytes, last: " + lastHeard.joinToString("") {
            val c = it.toInt() and 0xFF
            if (c in 32..126) c.toChar().toString() else "\\x%02x".format(c)
        }

    private fun readLine(deadline: Long): String? {
        while (System.currentTimeMillis() < deadline) {
            val i = pending.indexOf('\n'.code.toByte())
            if (i >= 0) {
                val line = String(pending, 0, i, Charsets.ISO_8859_1).trim()
                pending = pending.copyOfRange(i + 1, pending.size)
                return line
            }
            fill(200)
        }
        return null
    }

    /** One current JPEG (the board drops its stale frame itself), sent in checked 1 KB blocks:
     *  the phone loses a few bytes per photo at 460800 baud, so a bad block is sent again. */
    override fun grab(timeoutMs: Long): ByteArray = try {
        grabOnce(timeoutMs)
    } catch (e: LostHeader) {
        android.util.Log.i("sense", "retrying: ${e.message}")
        grabOnce(timeoutMs)
    }

    private class LostHeader(msg: String) : IllegalStateException(msg)

    private fun grabOnce(timeoutMs: Long): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        drain(10)  // leftovers (boot messages, a timed-out photo) would hide the reply's header
        heard = 0
        lastHeard = ByteArray(0)
        send(if (xiao) 'p' else 'P')
        val headerBy = minOf(deadline, System.currentTimeMillis() + 2500)
        var line = readLine(headerBy)
        while (line != null && "JPG " !in line) {
            if ("ERR " in line) throw IllegalStateException(line.substringAfterLast("ERR "))
            line = readLine(headerBy)
        }
        val size = line?.substringAfterLast("JPG ")?.trim()?.toIntOrNull()
        if (size == null) {
            if (heard == 0) throw IllegalStateException("no reply from board (heard nothing)")
            drain(30)
            throw LostHeader("no photo header (${heardText()})")
        }
        if (xiao) {
            while (pending.size < size) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("photo timed out (${pending.size}/$size bytes)")
                fill(50)
            }
            val jpeg = pending.copyOfRange(0, size)
            pending = pending.copyOfRange(size, pending.size)
            if (jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) throw IllegalStateException("not a JPEG")
            return jpeg
        }
        val out = ByteArrayOutputStream(size)
        var resends = 0
        while (out.size() < size) {
            val n = minOf(1024, size - out.size())
            var lastData = System.currentTimeMillis()
            while (pending.size < n + 2) {
                val now = System.currentTimeMillis()
                if (now > deadline) throw IllegalStateException("photo timed out (${out.size()}/$size bytes)")
                if (now - lastData > 150) break  // bytes were lost: the rest of the block won't come
                if (fill(50)) lastData = System.currentTimeMillis()
            }
            val ok = pending.size == n + 2 &&
                crc16(pending, n) == ((pending[n].toInt() and 0xFF) or ((pending[n + 1].toInt() and 0xFF) shl 8))
            if (ok) {
                out.write(pending, 0, n)
                pending = ByteArray(0)
                send('k')
            } else {
                if (++resends > 20) throw IllegalStateException("too many bad blocks (${out.size()}/$size bytes)")
                drain(20)
                send('r')
            }
        }
        if (resends > 0) android.util.Log.i("sense", "photo $size bytes, $resends blocks resent")
        val jpeg = out.toByteArray()
        if (jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) throw IllegalStateException("not a JPEG")
        return jpeg
    }

    private fun crc16(data: ByteArray, n: Int): Int {
        var c = 0xFFFF
        for (i in 0 until n) {
            c = c xor (data[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
        }
        return c
    }
}
