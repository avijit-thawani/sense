package com.sense.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.util.UUID
import android.bluetooth.BluetoothStatusCodes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** The XIAO (firmware/xiao) over Bluetooth LE: write a command letter, photos come back as
 *  notifications, "JPG <length>" first and then the JPEG in MTU-sized chunks. */
@SuppressLint("MissingPermission")  // MainActivity asks for BLUETOOTH_SCAN/CONNECT before connecting
class BleBoard private constructor(private val gatt: BluetoothGatt, private val cb: Events) : Camera {
    override val link = "bluetooth"

    companion object {
        private val SERVICE = UUID.fromString("5e0e0001-7a2b-4c1e-9a8f-2b6d3c4e5f60")
        private val CMD = UUID.fromString("5e0e0002-7a2b-4c1e-9a8f-2b6d3c4e5f60")
        private val DATA = UUID.fromString("5e0e0003-7a2b-4c1e-9a8f-2b6d3c4e5f60")
        private val AUDIO = UUID.fromString("5e0e0004-7a2b-4c1e-9a8f-2b6d3c4e5f60")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Connects straight to the board seen last time (~1 s), else scans for one (up to scanMs);
         *  throws with a readable reason if it can't. */
        fun connect(ctx: Context, lastAddress: String?, scanMs: Long = 8000): BleBoard {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null || !adapter.isEnabled) throw IllegalStateException("Bluetooth is off")
            if (lastAddress != null) runCatching { return open(ctx, adapter.getRemoteDevice(lastAddress), 4) }
            val device = scan(adapter.bluetoothLeScanner ?: throw IllegalStateException("Bluetooth is off"), scanMs)
                ?: throw IllegalStateException("no sense-xiao board advertising nearby")
            return open(ctx, device, 10)
        }

        private fun open(ctx: Context, device: BluetoothDevice, timeoutS: Long): BleBoard {
            val cb = Events()
            val gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_2M_MASK)
            if (!cb.ready.await(timeoutS, TimeUnit.SECONDS) || cb.error != null) {
                gatt.disconnect(); gatt.close()
                throw IllegalStateException("Bluetooth setup failed: ${cb.error ?: "timed out"}")
            }
            return BleBoard(gatt, cb)
        }

        private fun scan(scanner: android.bluetooth.le.BluetoothLeScanner, timeoutMs: Long): BluetoothDevice? {
            val found = LinkedBlockingQueue<BluetoothDevice>()
            val callback = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) { found.offer(r.device) }
            }
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            try { return found.poll(timeoutMs, TimeUnit.MILLISECONDS) } finally { scanner.stopScan(callback) }
        }
    }

    /** Receives the board's mic: 16 kHz mono 16-bit little-endian PCM, then onEnd once. */
    interface AudioSink {
        fun onPcm(pcm: ByteArray)
        fun onEnd()
    }

    class Events : BluetoothGattCallback() {
        val ready = CountDownLatch(1)
        val inbox = LinkedBlockingQueue<ByteArray>()
        val writeDone = Semaphore(0)
        @Volatile var error: String? = null
        @Volatile var connected = false
        @Volatile var mtu = 23
        @Volatile var hasMic = false
        @Volatile var audio: AudioSink? = null
        private val toEnable = ArrayDeque<BluetoothGattCharacteristic>()

        private fun fail(why: String) { error = why; ready.countDown() }

        private fun endAudio() { audio?.let { audio = null; it.onEnd() } }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            connected = state == BluetoothProfile.STATE_CONNECTED
            if (connected) g.requestMtu(517)
            else { fail("disconnected (status $status)"); inbox.offer(ByteArray(0)); writeDone.release(); endAudio() }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this.mtu = mtu
            // No requestConnectionPriority(HIGH): the board itself asks for 7.5 ms and the Pixel
            // grants it, while HIGH would move it back to 15 ms (half the slots per second).
            if (!g.discoverServices()) fail("service discovery didn't start")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE) ?: return fail("not a sense-xiao board")
            toEnable += svc.getCharacteristic(DATA) ?: return fail("not a sense-xiao board")
            // Older firmware has no mic stream; the app then uses the phone's mic.
            svc.getCharacteristic(AUDIO)?.let { toEnable += it; hasMic = true }
            enableNext(g)
        }

        /** Android runs one descriptor write at a time: switch notifications on one by one. */
        private fun enableNext(g: BluetoothGatt) {
            val c = toEnable.removeFirstOrNull() ?: return ready.countDown()
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD)
            val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, on)
            else @Suppress("DEPRECATION") run { d.value = on; g.writeDescriptor(d) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) fail("notifications refused ($status)") else enableNext(g)
        }

        private fun received(c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != AUDIO) { inbox.offer(value); return }
            val sink = audio ?: return
            if (value.size == 1 && value[0] == 0xEE.toByte()) endAudio()
            else if (value.size > 4) sink.onPcm(Adpcm.decode(value))
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
    val hasMic get() = cb.hasMic

    /** Mic start/stop go through their own thread so a press never waits on the camera thread. */
    private val micWriter = Executors.newSingleThreadExecutor()

    /** Streams the board's mic into sink until stopAudio() (then the board's end marker), or a drop. */
    fun startAudio(sink: AudioSink) {
        cb.audio = sink
        runCatching { micWriter.execute { runCatching { send('a') } } }
    }

    fun stopAudio() { runCatching { micWriter.execute { runCatching { send('q') } } } }

    /** Sends one command letter; waits until Android has handed it to the radio. Android allows
     *  one write in flight, and the camera thread and the mic both send. */
    @Synchronized
    fun send(cmd: Char) {
        if (!cb.connected) throw IllegalStateException("Bluetooth disconnected")
        val c = gatt.getService(SERVICE).getCharacteristic(CMD)
        val v = byteArrayOf(cmd.code.toByte())
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        repeat(5) {
            cb.writeDone.drainPermits()
            val ok = if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(c, v, type) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { c.writeType = type; c.value = v; gatt.writeCharacteristic(c) }
            if (ok) { cb.writeDone.tryAcquire(1, TimeUnit.SECONDS); return }
            Thread.sleep(10)  // a write from another app or the stack is still in flight
        }
        throw IllegalStateException("Bluetooth write of '$cmd' refused")
    }

    override fun grab(timeoutMs: Long): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        cb.inbox.clear()
        send('p')
        fun next(): ByteArray {
            val left = deadline - System.currentTimeMillis()
            val p = if (left > 0) cb.inbox.poll(left, TimeUnit.MILLISECONDS) else null
            if (p == null || !cb.connected) throw IllegalStateException(if (!cb.connected) "Bluetooth disconnected" else "photo timed out")
            return p
        }
        // Chunks of an earlier photo that timed out can still be arriving; skip to the header.
        var head = String(next(), Charsets.ISO_8859_1)
        while (!head.startsWith("JPG ") && !head.startsWith("ERR ")) head = String(next(), Charsets.ISO_8859_1)
        if (!head.startsWith("JPG ")) throw IllegalStateException(head.removePrefix("ERR ").ifEmpty { "no photo header" })
        val size = head.substring(4).trim().toInt()
        val out = ByteArrayOutputStream(size)
        while (out.size() < size) out.write(next())
        val jpeg = out.toByteArray()
        if (jpeg.size != size || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte())
            throw IllegalStateException("bad photo (${jpeg.size}/$size bytes)")
        return jpeg
    }

    fun close() {
        runCatching { gatt.disconnect(); gatt.close() }
        micWriter.shutdown()
    }
}

/** IMA ADPCM, the decoder for firmware/xiao's mic packets:
 *  [seq][predictor lo][predictor hi][step index] + nibbles, low first. */
private object Adpcm {
    private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
        73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408,
        449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630,
        9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767)

    /** Returns little-endian 16-bit PCM. */
    fun decode(p: ByteArray): ByteArray {
        var pred = ((p[1].toInt() and 0xFF) or (p[2].toInt() shl 8)).toShort().toInt()
        var index = (p[3].toInt() and 0xFF).coerceIn(0, 88)
        val out = ByteArray((p.size - 4) * 4)
        var o = 0
        for (i in 4 until p.size) {
            val b = p[i].toInt() and 0xFF
            for (code in intArrayOf(b and 15, b shr 4)) {
                val step = STEP[index]
                var delta = step shr 3
                if (code and 4 != 0) delta += step
                if (code and 2 != 0) delta += step shr 1
                if (code and 1 != 0) delta += step shr 2
                pred = (if (code and 8 != 0) pred - delta else pred + delta).coerceIn(-32768, 32767)
                index = (index + INDEX[code]).coerceIn(0, 88)
                out[o++] = pred.toByte()
                out[o++] = (pred shr 8).toByte()
            }
        }
        return out
    }
}
