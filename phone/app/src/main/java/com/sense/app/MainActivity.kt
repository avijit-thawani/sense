package com.sense.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.Manifest
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
    private lateinit var describeButton: Button
    private lateinit var answerView: TextView
    private lateinit var tts: TextToSpeech
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 50) }
    @Volatile private var looking = false
    private var momentPromptFile = "moment_v4.txt"
    private var momentPrompt = ""
    private var onceSize = 'g'
    @Volatile private var boardSize = ' '

    // Hold to ask: photo at the start of the hold, speech-to-text while held, answer on release.
    private class Shot(val jpeg: ByteArray, val t0: Long, val camS: Double, val photo: String, val link: String)
    private var askPromptFile = "ask_app_v1.txt"
    private var askPrompt = ""
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var askCapture = false
    @Volatile private var listening = false
    private var askShot: Shot? = null
    private var askCameraFailed = false
    private var question: String? = null
    private var partial = ""
    private var heldByKey = false
    private var holdStarted = false
    private val holdToAsk = Runnable { holdStarted = true; startAsk() }

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
        recognizer?.destroy()
        tts.shutdown()
        runCatching { port?.close() }
        super.onDestroy()
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    /** One big Describe button (also the volume-down key), the last answer in large type, and small
     *  Live / Reconnect controls. High contrast and TalkBack labels, for low-vision and blind use. */
    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val bg = Color.rgb(12, 12, 14)
        val accent = Color.rgb(255, 204, 0)
        window.statusBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding((18 * dp).toInt(), (44 * dp).toInt(), (18 * dp).toInt(), (18 * dp).toInt())
        }
        status = TextView(this).apply {
            textSize = 14f; setTextColor(Color.rgb(160, 160, 168)); text = "Starting…"
        }
        describeButton = Button(this).apply {
            text = "Describe"
            textSize = 40f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            background = rounded(accent, 36 * dp)
            contentDescription = "Describe what is in front of me. Hold to ask a question. Volume down works the same way."
            // Tap = describe. Hold (over 0.4 s) = ask: speak while holding, release to send.
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { holdStarted = false; ui.postDelayed(holdToAsk, 400) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        ui.removeCallbacks(holdToAsk)
                        if (holdStarted) endAsk()
                        else if (e.actionMasked == MotionEvent.ACTION_UP) { v.performClick() }
                    }
                }
                true
            }
            setOnClickListener { requestDescribe() }
        }
        answerView = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
            text = "Tap to describe. Hold to ask a question. Volume down works too."
        }
        photoView = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = (150 * dp).toInt()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fun small(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; textSize = 16f; isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(44, 44, 50), 18 * dp)
            setOnClickListener { onClick() }
        }
        liveButton = small("Live: off") { toggleLive() }
        val reconnect = small("Reconnect") { board = null; connect() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * dp).toInt(), 0, 0)
            addView(liveButton, LinearLayout.LayoutParams(0, (52 * dp).toInt(), 1f).apply { marginEnd = (10 * dp).toInt() })
            addView(reconnect, LinearLayout.LayoutParams(0, (52 * dp).toInt(), 1f))
        }
        log = TextView(this).apply { textSize = 11f; setTextColor(Color.rgb(110, 110, 118)) }
        root.addView(status)
        root.addView(describeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.6f)
            .apply { topMargin = (14 * dp).toInt() })
        root.addView(answerView)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(photoView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ScrollView(this@MainActivity).apply { addView(log) },
                LinearLayout.LayoutParams(0, (150 * dp).toInt(), 1f).apply { marginStart = (10 * dp).toInt() })
        })
        root.addView(row)
        setContentView(root)
    }

    // Volume-down: press = describe, hold = ask (same as the big button).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) { heldByKey = false; event.startTracking() }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyLongPress(keyCode, event)
        heldByKey = true
        startAsk()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyUp(keyCode, event)
        if (heldByKey) endAsk() else if (!event.isCanceled) requestDescribe()
        heldByKey = false
        return true
    }

    // --- ask ---------------------------------------------------------------------------------

    private fun startAsk() {
        if (looking) return
        if (board == null) { say("Camera not connected.", "status"); connect(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            say("Allow the microphone, then hold again to ask.", "status")
            return
        }
        looking = true
        tts.stop()
        synchronized(this) { pendingSpeech = null; speaking = false }
        askShot = null; askCameraFailed = false; question = null; partial = ""
        askCapture = true                       // the camera loop takes the photo right away
        buzz(60); tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
        ui.post { describeButton.text = "Listening…"; describeButton.alpha = 0.6f; answerView.text = "…" }
        listening = true
        val r = recognizer ?: newRecognizer().also { recognizer = it }
        r.setRecognitionListener(listener)
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L))
    }

    private fun endAsk() {
        if (!listening) return
        listening = false
        tone.startTone(ToneGenerator.TONE_PROP_PROMPT, 80)
        ui.post { describeButton.text = "Thinking…" }
        recognizer?.stopListening()            // the final result arrives in onResults
    }

    private fun newRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)

    private val listener = object : RecognitionListener {
        override fun onResults(b: Bundle?) {
            val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            question = text.ifBlank { partial }
            listening = false
            tryAnswer()
        }
        override fun onPartialResults(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                if (it.isNotBlank()) { partial = it; ui.post { answerView.text = "“$it”" } }
            }
        }
        override fun onError(error: Int) {
            show("speech error $error")
            question = partial          // whatever was heard before the error, maybe nothing
            listening = false
            tryAnswer()
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    /** Runs when the question or the photo arrives; acts once both are in. Main thread only. */
    private fun tryAnswer() = ui.post {
        if (!looking || listening) return@post
        val q = question ?: return@post
        if (askCameraFailed) { say("Camera didn't answer. Try again.", "status"); doneLooking("Camera didn't answer."); return@post }
        val shot = askShot ?: return@post
        question = null
        if (q.isBlank()) { say("Didn't catch the question.", "status"); doneLooking("Didn't catch the question."); return@post }
        answerView.text = "“$q”"
        net.execute { answer(q, shot) }
    }

    private fun answer(q: String, s: Shot) {
        var heard = ""
        try {
            val jpeg = Brain.brighten(s.jpeg)
            ui.post { photoView.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) }
            val a = Brain.describe(jpeg, key, model, askPrompt.replace("{q}", q.replace("\"", "'")), maxTokens = 200)
            addSpent(a.costUsd)
            heard = a.text
            buzz(20)
            tts.speak(heard, TextToSpeech.QUEUE_FLUSH, null, "${s.t0}|${s.photo}")
            logRow(JSONObject().put("photo", s.photo).put("mode", "ask").put("question", q)
                .put("size", sizeOf(onceSize)).put("captured_at", iso.format(Date(s.t0)))
                .put("answered_at", iso.format(Date())).put("camera_s", s.camS).put("enhance", "auto")
                .put("model_s", a.seconds).put("cost_usd", a.costUsd).put("status", "spoken")
                .put("text", a.text).put("spoken_text", heard).put("prompt_file", askPromptFile)
                .put("device", "android").put("link", s.link))
            show("[ask: cam ${"%.2f".format(s.camS)}s + model ${"%.2f".format(a.seconds)}s] Q: $q → $heard")
        } catch (e: Exception) {
            heard = "Couldn't answer: ${e.message?.take(60)}"
            say("Couldn't reach the describer. Try again.", "status")
            show("model: ${e.message}")
        } finally {
            doneLooking(if (heard.isEmpty()) "" else "Q: $q\n$heard")
        }
    }

    private fun requestDescribe() {
        if (looking) return
        if (board == null) { say("Camera not connected.", "status"); connect(); return }
        looking = true
        buzz(30); tone.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
        ui.post { describeButton.text = "Looking…"; describeButton.alpha = 0.6f }
        once = true
    }

    private fun buzz(ms: Long) {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun doneLooking(answer: String) = ui.post {
        looking = false
        describeButton.text = "Describe"; describeButton.alpha = 1f
        answerView.text = answer
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
            momentPromptFile = c.optString("moment_prompt", momentPromptFile)
            onceSize = c.optString("once_size", "g").firstOrNull() ?: 'g'
            askPromptFile = c.optString("ask_prompt", askPromptFile)
        }
        momentPrompt = assets.open(momentPromptFile).bufferedReader().readText().trim()
        askPrompt = assets.open(askPromptFile).bufferedReader().readText().trim()
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
                boardSize = size
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
                w.setSize(wifiSizeName(size))
                w.grab()
                boardSize = size
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
        liveButton.text = if (live) "Live: on" else "Live: off"
        say(if (live) "Live on" else "Live off", "status")
        if (live && sessionDir == null) newSession()
    }

    private fun wifiSizeName(c: Char) = when (c) { 's' -> "qvga"; 'g' -> "xga"; 'x' -> "svga"; 'u' -> "uxga"; else -> "vga" }

    /** Switch the board's resolution only when it differs (one-shot uses a larger frame than live). */
    private fun ensureSize(b: Camera, want: Char) {
        if (boardSize == want) return
        when (b) {
            is WifiBoard -> b.setSize(wifiSizeName(want))
            is Board -> { b.send(want); Thread.sleep(300); b.drain() }
        }
        boardSize = want
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
            if (askCapture && b != null) {        // hold-to-ask: photo first, while the user speaks
                askCapture = false
                try {
                    ensureSize(b, onceSize)
                    val t0 = System.currentTimeMillis()
                    val jpeg = b.grab()
                    val camS = (System.currentTimeMillis() - t0) / 1000.0
                    if (sessionDir == null) newSession()
                    val photo = stamp.format(Date(t0)) + "-${(t0 / 100) % 10}.jpg"
                    File(sessionDir, photo).writeBytes(jpeg)
                    askShot = Shot(jpeg, t0, camS, photo, b.link)
                } catch (e: Exception) {
                    show("camera: ${e.message}")
                    askCameraFailed = true
                    if (b is WifiBoard) board = null
                }
                tryAnswer()
                continue
            }
            val single = once
            val go = single || (live && !looking && readyForNext())
            if (b == null || !go || key.isEmpty()) {
                if (single && key.isEmpty()) { once = false; doneLooking("No API key on the phone.") }
                Thread.sleep(30); continue
            }
            once = false
            if (sessionDir == null) newSession()
            if (spent() >= budgetUsd) {
                setStatus("Spend cap $$budgetUsd reached (config.json)"); live = false
                if (single) { say("Spend cap reached.", "status"); doneLooking("Spend cap reached.") }
                continue
            }
            val t0 = System.currentTimeMillis()
            val jpeg = try {
                ensureSize(b, if (single) onceSize else size)
                b.grab()
            } catch (e: Exception) {
                show("camera: ${e.message}")
                if (single) { say("Camera didn't answer. Try again.", "status"); doneLooking("Camera didn't answer.") }
                if (b is WifiBoard) { board = null; Thread.sleep(1000) }  // reconnect
                continue
            }
            val camS = (System.currentTimeMillis() - t0) / 1000.0
            val photo = stamp.format(Date(t0)) + "-${(t0 / 100) % 10}.jpg"
            File(sessionDir, photo).writeBytes(jpeg)
            inFlight = true
            val link = b.link
            if (single) net.execute { askMoment(jpeg, t0, camS, photo, link) }
            else net.execute { ask(jpeg, t0, camS, photo, link) }
        }
    }

    /** One-shot: the hill-climbed moment prompt, the whole answer spoken at once (interrupting). */
    private fun askMoment(raw: ByteArray, capturedAt: Long, camS: Double, photo: String, link: String) {
        var heard = ""
        try {
            val jpeg = Brain.brighten(raw)
            ui.post { photoView.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) }
            val a = Brain.describe(jpeg, key, model, momentPrompt, maxTokens = 150)
            addSpent(a.costUsd)
            val now = System.currentTimeMillis()
            heard = if (a.text.trim(' ', '.', '"', '\'').uppercase() == "SILENT") "Nothing notable." else a.text
            buzz(20)
            synchronized(this) { pendingSpeech = null; speaking = false }
            tts.speak(heard, TextToSpeech.QUEUE_FLUSH, null, "$capturedAt|$photo")
            logRow(JSONObject().put("photo", photo).put("mode", "moment").put("size", sizeOf(onceSize))
                .put("captured_at", iso.format(Date(capturedAt))).put("answered_at", iso.format(Date(now)))
                .put("camera_s", camS).put("enhance", "auto").put("model_s", a.seconds)
                .put("cost_usd", a.costUsd).put("status", "spoken").put("text", a.text)
                .put("spoken_text", heard).put("prompt_file", momentPromptFile)
                .put("device", "android").put("link", link))
            show("[once: cam ${"%.2f".format(camS)}s + model ${"%.2f".format(a.seconds)}s] $heard")
            setStatus("${if (link == "wifi") "Camera on Wi-Fi" else "Camera on USB"} · ${"%.3f".format(spent())} of $$budgetUsd spent")
        } catch (e: Exception) {
            heard = "Couldn't describe: ${e.message?.take(60)}"
            say("Couldn't reach the describer. Try again.", "status")
            show("model: ${e.message}")
        } finally {
            inFlight = false
            doneLooking(heard)
        }
    }

    private fun sizeOf(c: Char) = when (c) { 's' -> "320x240"; 'g' -> "1024x768"; 'x' -> "800x600"; 'u' -> "1600x1200"; else -> "640x480" }

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
