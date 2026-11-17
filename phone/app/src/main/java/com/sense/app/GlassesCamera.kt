package com.sense.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** The camera glasses (Bapick M08T, HeyCyan family) over Bluetooth LE: the protocol of
 *  tools/glasses.py. A photo is "take a preview of this size", wait for "photo taken", then the
 *  JPEG in 1 KB chunks, each its own request and reply. Timings in docs/glasses.md. */
@SuppressLint("MissingPermission")  // MainActivity asks for BLUETOOTH_SCAN/CONNECT before connecting
class GlassesCamera private constructor(private val gatt: BluetoothGatt, private val cb: Events,
                                        val highPriority: Boolean) : Camera {
    override val link = "glasses"

    /** Preview size byte: width 256 + 64·size at 16:9 (1 = 320×180, 4 = 512×288); 0 gives 160×90. */
    @Volatile var size = 1
    override val sizeLabel get() = if (size == 0) "160x90" else "${256 + 64 * size}x${(256 + 64 * size) * 9 / 16}"

    /** Timings of the last photo, for the log: when it was ready, each chunk, chunks asked again. */
    @Volatile var lastStats = ""

    companion object {
        private val SERVICE = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
        private val TX = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
        private val RX = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
        private val UART_RX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CONTROL = 0x41
        private const val EVENT = 0x73
        private const val THUMB = 0xFD

        private fun looksLikeGlasses(name: String?) =
            name != null && listOf("M0", "E0", "A0", "W6").any { name.startsWith(it) }

        /** Connects to the glasses seen last time, else the paired ones, else scans (up to scanMs);
         *  throws with a readable reason if it can't. */
        fun connect(ctx: Context, lastAddress: String?, highPriority: Boolean, scanMs: Long = 8000): GlassesCamera {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null || !adapter.isEnabled) throw IllegalStateException("Bluetooth is off")
            val known = listOfNotNull(lastAddress?.let { adapter.getRemoteDevice(it) }) +
                adapter.bondedDevices.filter { looksLikeGlasses(it.name) && it.address != lastAddress }
            for (d in known) runCatching { return open(ctx, d, 5, highPriority) }
            val device = scan(adapter.bluetoothLeScanner ?: throw IllegalStateException("Bluetooth is off"), scanMs)
                ?: throw IllegalStateException("glasses not found: are they on, and free of the vendor app?")
            return open(ctx, device, 10, highPriority)
        }

        private fun open(ctx: Context, device: BluetoothDevice, timeoutS: Long, highPriority: Boolean): GlassesCamera {
            val cb = Events(highPriority)
            val gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            if (!cb.ready.await(timeoutS, TimeUnit.SECONDS) || cb.error != null) {
                gatt.disconnect(); gatt.close()
                throw IllegalStateException("glasses setup failed: ${cb.error ?: "timed out"}")
            }
            return GlassesCamera(gatt, cb, highPriority)
        }

        private fun scan(scanner: android.bluetooth.le.BluetoothLeScanner, timeoutMs: Long): BluetoothDevice? {
            val found = LinkedBlockingQueue<BluetoothDevice>()
            val callback = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    val uuids = r.scanRecord?.serviceUuids.orEmpty()
                    if (ParcelUuid(SERVICE) in uuids || looksLikeGlasses(r.scanRecord?.deviceName ?: r.device.name))
                        found.offer(r.device)
                }
            }
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            try { return found.poll(timeoutMs, TimeUnit.MILLISECONDS) } finally { scanner.stopScan(callback) }
        }

        private fun crc16(data: ByteArray): Int {
            var c = 0xFFFF
            for (b in data) {
                c = c xor (b.toInt() and 0xFF)
                repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
            }
            return c
        }

        /** bc <cmd> <len LE16> <crc16/modbus of payload LE16> <payload> */
        private fun packet(cmd: Int, payload: ByteArray): ByteArray {
            val crc = crc16(payload)
            return byteArrayOf(0xBC.toByte(), cmd.toByte(), payload.size.toByte(), (payload.size shr 8).toByte(),
                crc.toByte(), (crc shr 8).toByte()) + payload
        }

        /** End of the JPEG: the first ffd9 after start-of-scan. The glasses serve a fixed buffer;
         *  what follows is padding or leftovers of an earlier, larger photo. */
        private fun jpegEnd(d: ByteArray): Int? {
            var sos = -1
            for (i in 0 until d.size - 1) if (d[i] == 0xFF.toByte() && d[i + 1] == 0xDA.toByte()) { sos = i; break }
            if (sos < 0) return null
            for (i in sos until d.size - 1) if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD9.toByte()) return i + 2
            return null
        }
    }

    class Reply(val cmd: Int, val payload: ByteArray)

    class Events(private val highPriority: Boolean) : BluetoothGattCallback() {
        val ready = CountDownLatch(1)
        val inbox = LinkedBlockingQueue<Reply>()
        val writeDone = Semaphore(0)
        @Volatile var error: String? = null
        @Volatile var connected = false
        @Volatile var mtu = 23
        private val toEnable = ArrayDeque<BluetoothGattCharacteristic>()
        private var buf = ByteArray(0)

        private fun fail(why: String) { error = why; ready.countDown() }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            connected = state == BluetoothProfile.STATE_CONNECTED
            if (connected) g.requestMtu(517)
            else { fail("disconnected (status $status)"); inbox.offer(Reply(-1, ByteArray(0))); writeDone.release() }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this.mtu = mtu
            // Each chunk is a request and a ~1 KB reply: more radio slots per second should cut
            // the ~170 ms per chunk measured from the Mac. Switchable so the benchmark can compare.
            if (highPriority) g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            if (!g.discoverServices()) fail("service discovery didn't start")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE) ?: return fail("not the camera glasses")
            toEnable += svc.getCharacteristic(RX) ?: return fail("not the camera glasses")
            g.services.firstNotNullOfOrNull { it.getCharacteristic(UART_RX) }?.let { toEnable += it }
            enableNext(g)
        }

        /** Android runs one descriptor write at a time: switch notifications on one by one. */
        private fun enableNext(g: BluetoothGatt) {
            val c = toEnable.removeFirstOrNull() ?: return ready.countDown()
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD) ?: return enableNext(g)
            val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, on)
            else @Suppress("DEPRECATION") run { d.value = on; g.writeDescriptor(d) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) fail("notifications refused ($status)") else enableNext(g)
        }

        /** Packets span several notifications: collect bytes, hand over each whole packet. */
        private fun received(c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != RX) return
            buf += value
            while (buf.size >= 6) {
                if (buf[0] != 0xBC.toByte()) { buf = buf.copyOfRange(1, buf.size); continue }
                val n = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
                if (buf.size < 6 + n) return
                inbox.offer(Reply(buf[1].toInt() and 0xFF, buf.copyOfRange(6, 6 + n)))
                buf = buf.copyOfRange(6 + n, buf.size)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.release()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            received(c, value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) received(c, c.value.copyOf())
        }
    }

    val mtu get() = cb.mtu
    val address: String get() = gatt.device.address
    val error get() = cb.error
    val connected get() = cb.connected

    @Synchronized
    private fun send(cmd: Int, payload: ByteArray) {
        if (!cb.connected) throw IllegalStateException("glasses disconnected")
        val c = gatt.getService(SERVICE).getCharacteristic(TX)
        val v = packet(cmd, payload)
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        repeat(5) {
            cb.writeDone.drainPermits()
            val ok = if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(c, v, type) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { c.writeType = type; c.value = v; gatt.writeCharacteristic(c) }
            if (ok) { cb.writeDone.tryAcquire(1, TimeUnit.SECONDS); return }
            Thread.sleep(10)  // a write from the stack is still in flight
        }
        throw IllegalStateException("glasses refused a write")
    }

    /** Next reply with this command id that matches; others (battery, button events) are dropped. */
    private fun wait(cmd: Int, timeoutMs: Long, match: (ByteArray) -> Boolean = { true }): ByteArray? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = end - System.currentTimeMillis()
            val r = (if (left > 0) cb.inbox.poll(left, TimeUnit.MILLISECONDS) else null) ?: return null
            if (!cb.connected) throw IllegalStateException("glasses disconnected")
            if (r.cmd == cmd && match(r.payload)) return r.payload
        }
    }

    override fun grab(timeoutMs: Long): ByteArray {
        val t0 = System.currentTimeMillis()
        cb.inbox.clear()
        val s = size.toByte()
        send(CONTROL, byteArrayOf(2, 1, 6, s, s))
        wait(CONTROL, 3000) { it.size >= 3 && it[0] == 2.toByte() && it[1] == 1.toByte() && it[2] == 6.toByte() }
            ?: throw IllegalStateException("glasses didn't accept the photo command")
        wait(EVENT, 6000) { it.isNotEmpty() && it[0] == 2.toByte() }
            ?: throw IllegalStateException("glasses took no photo")
        val readyMs = System.currentTimeMillis() - t0

        val out = ByteArrayOutputStream()
        val chunkMs = ArrayList<Long>()
        var i = 0
        var total = -1
        var retries = 0
        while (total < 0 || i < total) {
            val t = System.currentTimeMillis()
            send(THUMB, byteArrayOf(1, i.toByte(), (i shr 8).toByte()))
            // A reply sometimes never comes (seen from the Mac too): ask once more before giving up.
            val p = wait(THUMB, 700) { it.size >= 5 && ((it[3].toInt() and 0xFF) or ((it[4].toInt() and 0xFF) shl 8)) == i }
            if (p == null) {
                if (++retries > 4) throw IllegalStateException("photo stalled at chunk $i (${stats(readyMs, chunkMs, retries)})")
                continue
            }
            total = (p[1].toInt() and 0xFF) or ((p[2].toInt() and 0xFF) shl 8)
            if (total == 0) throw IllegalStateException("glasses have no preview")
            out.write(p, 5, p.size - 5)
            chunkMs += System.currentTimeMillis() - t
            i++
            val soFar = out.toByteArray()
            jpegEnd(soFar)?.let { end ->
                lastStats = stats(readyMs, chunkMs, retries)
                return soFar.copyOf(end)
            }
            if (System.currentTimeMillis() - t0 > timeoutMs + 6000) throw IllegalStateException("photo timed out")
        }
        lastStats = stats(readyMs, chunkMs, retries)
        throw IllegalStateException("photo had no end marker (${out.size()} bytes)")
    }

    private fun stats(readyMs: Long, chunkMs: List<Long>, retries: Int) =
        "ready ${readyMs}ms, ${chunkMs.size} chunks ${chunkMs.joinToString(",")}ms, asked again $retries, " +
            "mtu ${cb.mtu}, priority ${if (highPriority) "high" else "default"}"

    fun close() {
        runCatching { gatt.disconnect(); gatt.close() }
    }
}
