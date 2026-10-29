package com.sense.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Board on USB-C (power + photos), Gemini over the phone's internet, Android TTS to whatever
 * audio output is active (Bluetooth earphones). Same loop and logs as tools/describe.py:
 * sessions/<time>/ in the app's external files dir, pulled with `adb pull`.
 *
 * Config lives in the app's private storage, pushed over adb (see phone/README.md):
 * openrouter_key.txt (required), config.json {"model", "budget_usd", "size"} (optional).
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var photoView: ImageView
    private lateinit var liveButton: Button
    private lateinit var tts: TextToSpeech

    private var board: Camera? = null
    private var boardIp = ""
    private var port: UsbSerialPort? = null
    @Volatile private var live = false
    @Volatile private var once = false
    @Volatile private var inFlight = false
    @Volatile private var speaking = false
    @Volatile private var speechEnds = 0L
    @Volatile private var leadMs = 1500.0
    private var pendingSpeech: Triple<Long, String, String>? = null
    private var sessionDir: File? = null
    private var key = ""
    private var model = "google/gemini-3.1-flash-lite"
    private var budgetUsd = 1.0
    private var size = 'v'

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private val stamp = SimpleDateFormat("HHmmss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        loadConfig()
        tts = TextToSpeech(this) { ok ->
            if (ok == TextToSpeech.SUCCESS) {
                tts.setSpeechRate(1.5f)
                tts.setOnUtteranceProgressListener(speechEvents)
                say("ready", "warmup")
            }
        }
        registerReceiver(usbPermission, IntentFilter(ACTION_USB),
            if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0)
        connect()
        Thread(::loop, "camera").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (board == null) connect()
    }

    override fun onDestroy() {
        live = false
        unregisterReceiver(usbPermission)
        tts.shutdown()
        runCatching { port?.close() }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 160, 24, 24) }
        status = TextView(this).apply { textSize = 16f }
        val row = LinearLayout(this)
        liveButton = Button(this).apply { text = "Live"; setOnClickListener { toggleLive() } }
        val onceButton = Button(this).apply { text = "Once"; setOnClickListener { once = true } }
        val connectButton = Button(this).apply { text = "Connect"; setOnClickListener { connect() } }
        row.addView(liveButton); row.addView(onceButton); row.addView(connectButton)
        photoView = ImageView(this).apply { adjustViewBounds = true }
        log = TextView(this).apply { textSize = 13f }
        root.addView(status); root.addView(row); root.addView(photoView)
        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)
    }

    private fun show(msg: String) = ui.post {
        log.text = (msg + "\n" + log.text).take(4000)
    }

    private fun setStatus(msg: String) = ui.post { status.text = msg }

    private fun loadConfig() {
        key = File(filesDir, "openrouter_key.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        File(filesDir, "config.json").takeIf { it.exists() }?.let {
            val c = JSONObject(it.readText())
            model = c.optString("model", model)
            budgetUsd = c.optDouble("budget_usd", budgetUsd)
            size = c.optString("size", "v").firstOrNull() ?: 'v'
            boardIp = c.optString("board_ip", "")
        }
        if (key.isEmpty()) setStatus("No API key: push openrouter_key.txt over adb (phone/README.md)")
    }

    private fun spent(): Double = getSharedPreferences("sense", MODE_PRIVATE).getFloat("spent", 0f).toDouble()
    private fun addSpent(usd: Double) {
        val p = getSharedPreferences("sense", MODE_PRIVATE)
        p.edit().putFloat("spent", (spent() + usd).toFloat()).apply()
    }

    // --- USB -------------------------------------------------------------------------------

    private val usbPermission = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = connect()
    }

    @Volatile private var connecting = false

    private fun connect() {
        if (connecting || board != null) return
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
        if (driver == null) {
            if (boardIp.isNotEmpty()) { connectWifi(); return }
            val any = manager.deviceList.values.joinToString { "%04x:%04x".format(it.vendorId, it.productId) }
            setStatus("Board not found (USB devices seen: ${any.ifEmpty { "none" }}). " +
                "Needs a USB-C to micro-USB data cable or OTG adapter. Retrying…")
            return
        }
        if (!manager.hasPermission(driver.device)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(driver.device,
                PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB).setPackage(packageName), flags))
            return
        }
        connecting = true
        Thread {
            try {
                val p = driver.ports[0]
                p.open(manager.openDevice(driver.device))
                p.setParameters(460800, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                p.dtr = false; p.rts = false
                Thread.sleep(2500)  // opening the port can reset the board
                val b = Board(p)
                b.drain()
                b.send(size); Thread.sleep(300); b.drain()
                runCatching { b.grab() }
                port = p; board = b
                setStatus("Board connected. ${"%.3f".format(spent())} of $$budgetUsd spent. Model: $model")
            } catch (e: Exception) {
                setStatus("USB error: ${e.message}")
            } finally {
                connecting = false
            }
        }.start()
    }

    /** No USB data (power-only cable): fetch photos from the board over the hotspot instead. */
    private fun connectWifi() {
        connecting = true
        Thread {
            try {
                val w = WifiBoard(boardIp)
                w.setSize(sizeName().let { mapOf("320x240" to "qvga", "1024x768" to "xga",
                    "800x600" to "svga", "1600x1200" to "uxga")[it] ?: "vga" })
                w.grab()
                board = w
                setStatus("Board on Wi-Fi ($boardIp); USB is power only. " +
                    "${"%.3f".format(spent())} of $$budgetUsd spent. Model: $model")
            } catch (e: Exception) {
                setStatus("No USB data and board not reachable at $boardIp (${e.message}). Retrying…")
            } finally {
                connecting = false
            }
        }.start()
    }

    // --- loop ------------------------------------------------------------------------------

    private fun toggleLive() {
        live = !live
        liveButton.text = if (live) "Stop" else "Live"
        if (live && sessionDir == null) newSession()
    }

    private fun newSession() {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        sessionDir = File(getExternalFilesDir("sessions"), name).apply { mkdirs() }
        show("Recording to sessions/$name")
    }

    private fun readyForNext(): Boolean {
        val now = System.currentTimeMillis()
        return !inFlight && pendingSpeech == null && now >= speechEnds - leadMs
    }

    private fun loop() {
        var lastTry = 0L
        while (true) {
            if (board == null && System.currentTimeMillis() - lastTry > 4000) {
                lastTry = System.currentTimeMillis()
                ui.post { connect() }
            }
            val b = board
            val go = once || (live && readyForNext())
            if (b == null || !go || key.isEmpty()) { Thread.sleep(30); continue }
            once = false
            if (sessionDir == null) newSession()
            if (spent() >= budgetUsd) { setStatus("Spend cap $$budgetUsd reached (config.json)"); live = false; continue }
            val t0 = System.currentTimeMillis()
            val jpeg = try { b.grab() } catch (e: Exception) {
                show("camera: ${e.message}")
                if (b is WifiBoard) { board = null; Thread.sleep(1000) }  // reconnect
                continue
            }
            val camS = (System.currentTimeMillis() - t0) / 1000.0
            val photo = stamp.format(Date(t0)) + "-${(t0 / 100) % 10}.jpg"
            File(sessionDir, photo).writeBytes(jpeg)
            inFlight = true
            val link = b.link
            net.execute { ask(jpeg, t0, camS, photo, link) }
        }
    }

    private fun ask(raw: ByteArray, capturedAt: Long, camS: Double, photo: String, link: String) {
        try {
            val e0 = System.nanoTime()
            val jpeg = Brain.brighten(raw)
            val enhS = (System.nanoTime() - e0) / 1e9
            ui.post { photoView.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) }
            val a = Brain.describe(jpeg, key, model)
            addSpent(a.costUsd)
            val now = System.currentTimeMillis()
            leadMs = 0.7 * leadMs + 0.3 * (now - capturedAt)
            val (st, spoken) = Brain.decide(a.text, now)
            if (spoken != null) speak(capturedAt, spoken, photo)
            logRow(JSONObject().put("photo", photo).put("mode", "items").put("size", sizeName())
                .put("captured_at", iso.format(Date(capturedAt))).put("answered_at", iso.format(Date(now)))
                .put("camera_s", camS).put("enhance", "auto").put("enhance_s", enhS)
                .put("model_s", a.seconds).put("cost_usd", a.costUsd).put("status", st)
                .put("text", a.text).put("spoken_text", spoken ?: JSONObject.NULL)
                .put("prompt", Brain.PROMPT).put("device", "android").put("link", link))
            show("[cam ${"%.2f".format(camS)}s + model ${"%.2f".format(a.seconds)}s] " +
                (if (st == "spoken") spoken else "($st) ${a.text}"))
        } catch (e: Exception) {
            show("model: ${e.message}")
        } finally {
            inFlight = false
        }
    }

    private fun sizeName() = when (size) { 's' -> "320x240"; 'g' -> "1024x768"; 'x' -> "800x600"; 'u' -> "1600x1200"; else -> "640x480" }

    // --- speech ----------------------------------------------------------------------------

    private val launched = HashMap<String, Long>()

    private val speechEvents = object : UtteranceProgressListener() {
        override fun onStart(id: String) {
            val photo = id.substringAfter("|", "")
            if (photo.isEmpty()) return
            logRow(JSONObject().put("photo", photo).put("event", "spoke")
                .put("spoken_at", iso.format(Date(launched.remove(id) ?: System.currentTimeMillis())))
                .put("audio_at", iso.format(Date())).put("voice", "android-tts"))
        }
        override fun onDone(id: String) = next()
        @Deprecated("Deprecated in Java")
        override fun onError(id: String) = next()
        private fun next() {
            synchronized(this@MainActivity) {
                speaking = false
                pendingSpeech?.let { (t, text, photo) -> pendingSpeech = null; start(t, text, photo) }
            }
        }
    }

    /** Never cut a phrase off; while one plays, only the newest answer waits. */
    @Synchronized
    private fun speak(capturedAt: Long, text: String, photo: String) {
        if (speaking) pendingSpeech = Triple(capturedAt, text, photo) else start(capturedAt, text, photo)
    }

    private fun start(capturedAt: Long, text: String, photo: String) {
        speaking = true
        val id = "$capturedAt|$photo"
        launched[id] = System.currentTimeMillis()
        speechEnds = System.currentTimeMillis() + 100 + (text.split(" ").size * 60_000L / 230)
        say(text, id)
    }

    private fun say(text: String, id: String) = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)

    @Synchronized
    private fun logRow(row: JSONObject) {
        val dir = sessionDir ?: return
        File(dir, "session.jsonl").appendText(row.toString() + "\n")
    }

    companion object {
        private const val ACTION_USB = "com.sense.app.USB_PERMISSION"
    }
}
