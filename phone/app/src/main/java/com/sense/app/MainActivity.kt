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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import android.widget.FrameLayout
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
    private var pressAt = 0L                    // hold started (photo requested)
    private var releaseAt = 0L                  // hold released (speech-to-text asked to finish)
    private var sttAt = 0L                      // final transcript arrived
    // Debug: keep the user's own question audio (config "record_question_audio"). Android 13+:
    // we record the mic and pipe the same PCM into the recognizer, so nothing waits on it.
    private var recordQuestionAudio = false
    @Volatile private var capturing = false
    private var capture: Thread? = null
    private var pcm = ByteArrayOutputStream()
    private var captureStartAt = 0L
    private val AUDIO_RATE = 16000

    /** Starts recording and points the recognizer at our pipe. False = use the recognizer's own mic. */
    private fun startCapture(intent: Intent): ParcelFileDescriptor? {
        if (!recordQuestionAudio || Build.VERSION.SDK_INT < 33) return null
        return try {
            val min = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AUDIO_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, AUDIO_RATE))
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
            val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AUDIO_RATE)
            pcm = ByteArrayOutputStream()
            capturing = true
            rec.startRecording()
            captureStartAt = System.currentTimeMillis()
            mark("audio_capture_start")
            capture = Thread({
                val buf = ByteArray(1600)               // 50 ms
                val out = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
                while (capturing) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        synchronized(pcm) { pcm.write(buf, 0, n) }
                        runCatching { out.write(buf, 0, n) }
                    }
                }
                rec.stop(); rec.release()
                runCatching { out.close() }               // end of stream: the recognizer finalizes
            }, "question-audio").also { it.start() }
            readEnd
        } catch (e: Exception) {
            show("audio capture off: ${e.message}")
            capturing = false
            null
        }
    }

    private fun stopCapture() { capturing = false }

    /** Writes the captured question as a 16 kHz mono WAV next to the photo. */
    private fun saveQuestionAudio(photo: String): String? {
        capture?.join(1000)
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.isEmpty()) return null
        val dir = sessionDir ?: return null
        val name = photo.removeSuffix(".jpg") + ".wav"
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(AUDIO_RATE)
            .putInt(AUDIO_RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        File(dir, name).outputStream().use { it.write(h.array()); it.write(data) }
        return name
    }

    // Finer marks from the recognizer, to see where the question-to-answer gap goes.
    private val marks = HashMap<String, Long>()
    private fun mark(name: String, overwrite: Boolean = true) {
        synchronized(marks) { if (overwrite || name !in marks) marks[name] = System.currentTimeMillis() }
    }
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
    private var reasoning = "none"
    /** The thinking level actually used for a model (some can't think "none"). */
    private fun effort(id: String = model) = Models.effort(this, id, reasoning)
    private var budgetUsd = 1.0
    private var size = 'v'

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private val stamp = SimpleDateFormat("HHmmss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        loadConfig()
        Brain.onThinkingRequired = { Models.rememberThinkingRequired(this, it) }
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

    private lateinit var home: View
    private lateinit var history: HistoryPage
    private lateinit var content: FrameLayout
    private var tab = "home"
    private var statusText = "Starting…"
    private var logText = ""
    private var answerText: CharSequence = "Tap to describe. Hold to ask a question. Volume down works too."
    private var lastPhoto: android.graphics.Bitmap? = null
    private val prefs by lazy { getSharedPreferences("sense", MODE_PRIVATE) }
    private fun devDetails() = prefs.getBoolean("dev_details", false)

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Ui.mode(this) == "system") buildUi()          // follow the phone's dark mode without a restart
    }

    /** Three frozen tabs (Home, History, Settings), themed light or dark (Settings > Appearance).
     *  Home is one big Describe button (also the volume-down key) and the last answer in large type. */
    private fun buildUi() {
        Ui.apply(this)
        Currency.load(this)
        val p = Ui.p
        fun px(v: Int) = Ui.dp(this, v)
        window.statusBarColor = p.bg
        window.navigationBarColor = p.card
        if (Build.VERSION.SDK_INT >= 30) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else light, light)
        }

        // Home
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(px(18), px(44), px(18), px(14))
        }
        status = Ui.text(this, homeStatus(), 13f, p.muted).apply { maxLines = 3 }
        describeButton = Button(this).apply {
            text = "Describe"
            textSize = 40f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.onPrimary)
            background = Ui.rounded(p.primary, 36f * resources.displayMetrics.density)
            stateListAnimator = null
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
        answerView = Ui.text(this, answerText, 24f).apply { setPadding(0, px(16), 0, px(8)) }
        photoView = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = px(140)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            lastPhoto?.let { setImageBitmap(it) }
        }
        log = Ui.text(this, logText, 11f, p.muted)
        root.addView(status)
        root.addView(describeButton, LinearLayout.LayoutParams(-1, 0, 1.6f).apply { topMargin = px(12) })
        root.addView(answerView)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(photoView, LinearLayout.LayoutParams(0, -2, 1f))
            if (devDetails()) addView(ScrollView(this@MainActivity).apply { addView(log) },
                LinearLayout.LayoutParams(0, px(140), 1f).apply { marginStart = px(10) })
        })
        home = root
        history = HistoryPage(this, { tts.speak(it, TextToSpeech.QUEUE_FLUSH, null, "replay") },
            { if (::tts.isInitialized) tts.stop() }, ::devDetails)

        // Tabs: icon above label; the active one in the accent colour, bold, on a tinted pill.
        content = FrameLayout(this)
        val tabs = listOf(Triple("home", "Home", R.drawable.ic_home),
            Triple("history", "History", R.drawable.ic_history), Triple("settings", "Settings", R.drawable.ic_settings))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(p.card)
            setPadding(px(8), px(6), px(8), px(6))
        }
        for ((id, label, icon) in tabs) {
            val on = id == tab
            val color = if (on) p.primaryText else p.muted
            bar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, px(6), 0, px(6))
                if (on) background = Ui.rounded(if (p.dark) Color.rgb(44, 44, 50) else Color.rgb(255, 244, 204), px(14).toFloat())
                addView(Ui.icon(this@MainActivity, icon, color), LinearLayout.LayoutParams(px(24), px(24)))
                addView(Ui.text(this@MainActivity, label, 12f, color, on).apply { setPadding(0, px(2), 0, 0) },
                    LinearLayout.LayoutParams(-2, -2))
                contentDescription = "$label tab" + if (on) ", selected" else ""
                setOnClickListener { if (id != tab) { tab = id; buildUi() } }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(4); marginEnd = px(4) })
        }
        history.stop()
        when (tab) {
            "home" -> content.addView(home)
            "history" -> { history.show(); content.addView(history.view) }
            else -> content.addView(buildSettings())
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(Ui.divider(this@MainActivity), LinearLayout.LayoutParams(-1, 1))
            addView(bar)
            setOnApplyWindowInsetsListener { _, insets ->      // keep the tabs above the gesture bar
                @Suppress("DEPRECATION") bar.setPadding(px(8), px(6), px(8), px(6) + insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(outer)
    }

    /** Settings: appearance, camera, spending and debugging; cards of title, one-line detail, control. */
    private fun buildSettings(): View {
        val p = Ui.p
        fun px(v: Int) = Ui.dp(this, v)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, px(16), px(24)) }
        fun group(title: String, vararg rows: View) {
            col.addView(Ui.section(this, title))
            col.addView(Ui.card(this).apply {
                rows.forEachIndexed { i, r ->
                    if (i > 0) addView(Ui.divider(this@MainActivity), LinearLayout.LayoutParams(-1, 1))
                    addView(r)
                }
            })
        }
        group("Appearance", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(12), 0, px(14))
            addView(Ui.text(this@MainActivity, "Theme", 16f, bold = true))
            addView(Ui.text(this@MainActivity, "Light, dark, or follow the phone", 13f, p.muted).apply { setPadding(0, 0, 0, px(10)) })
            addView(Ui.segmented(this@MainActivity, listOf("system" to "System", "light" to "Light", "dark" to "Dark"), Ui.mode(this@MainActivity)) {
                prefs.edit().putString("theme", it).apply(); buildUi()
            })
        })
        group("Camera",
            Ui.row(this, if (board != null) "Connected" else "Not connected", cameraLine() +
                    (if (board == null) "\n$statusText" else ""),
                Ui.quietButton(this, "Reconnect") { board = null; connect(); say("Reconnecting", "status") }),
            Ui.row(this, "Live mode", "Keep describing every few seconds, the older mode",
                Ui.toggle(this, live) { if (it != live) toggleLive() }))
        val m = Models.find(this, model)
        val est = Models.estimateUsd(m, effort())
        val measured = measuredCost(model, effort())
        val thinking: View = if (Models.thinkingRequired(this, model)) Ui.row(this, "Thinking", "Always a little for this model; it can't be turned off", null)
        else if (m.reasons) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(12), 0, px(14))
            addView(Ui.text(this@MainActivity, "Thinking", 16f, bold = true))
            addView(Ui.text(this@MainActivity, "Thinking a little first: sometimes better, usually slower", 13f, p.muted)
                .apply { setPadding(0, 0, 0, px(10)) })
            addView(Ui.segmented(this@MainActivity, listOf("none" to "None", "low" to "Low"), effort()) {
                reasoning = it; prefs.edit().putString("reasoning", it).apply(); buildUi()
            })
        } else Ui.row(this, "Thinking", "This model answers without thinking first", null)
        group("Model",
            Ui.row(this, m.name, null, Ui.quietButton(this, "Change") { pickModel() }).apply {
                addView(Ui.icon(this@MainActivity, m.logo, logoTint(m)),
                    0, LinearLayout.LayoutParams(px(24), px(24)).apply { marginEnd = px(12) })
            },
            thinking,
            Ui.row(this, "Cost per 1000 moments", measured?.let { (_, n) -> "Measured over your last $n" } ?: "Estimated",
                Ui.text(this, (if (measured == null) "≈ " else "") + moneyColumn((measured?.first ?: est) * 1000), 17f, bold = true)))
        group("Spending",
            Ui.row(this, "Spent so far", "${money(spent())} of ${money(budgetUsd)}", null),
            Ui.row(this, "Currency", null, Ui.segmented(this, listOf("inr" to "₹", "usd" to "$"),
                if (Currency.rupees) "inr" else "usd") {
                prefs.edit().putString("currency", it).apply(); Currency.load(this); buildUi()
            }.apply { layoutParams = LinearLayout.LayoutParams(px(130), -2) }))
        maybeRefreshPrices()
        group("Debugging",
            Ui.row(this, "Record my questions", "Keep your voice with each question so History can replay it",
                Ui.toggle(this, recordQuestionAudio) { recordQuestionAudio = it; prefs.edit().putBoolean("record_question_audio", it).apply() }),
            Ui.row(this, "Developer details", "Activity log on Home; prompt, file and model details in History",
                Ui.toggle(this, devDetails()) { prefs.edit().putBoolean("dev_details", it).apply() }))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            addView(Ui.header(this@MainActivity, "Settings", "Camera, voice and look"))
            addView(ScrollView(this@MainActivity).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun logoTint(m: Models.Model) = if (m.logo == R.drawable.logo_openai) Ui.p.text else null

    /** One line per model: logo, name, estimated cost per moment at the current thinking level. */
    private fun pickModel() {
        val p = Ui.p
        fun px(v: Int) = Ui.dp(this, v)
        val theme = if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
            else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        val dialog = android.app.AlertDialog.Builder(this, theme).create()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(8), px(12), px(8)) }
        // Tap "wait" or "per 1000" to sort by it (kept in preferences); the active column is marked.
        fun fill() {
            col.removeAllViews()
            val sort = prefs.getString("model_sort", "wait")!!
            fun head(label: String, key: String, w: Int) = Ui.text(this@MainActivity,
                label + if (sort == key) " ↑" else "", 13f, if (sort == key) p.text else p.muted, sort == key).apply {
                gravity = Gravity.END
                setPadding(0, px(8), 0, px(8))
                contentDescription = "Sort by $label" + if (sort == key) ", sorted" else ""
                setOnClickListener { prefs.edit().putString("model_sort", key).apply(); fill() }
            }.also { it.layoutParams = LinearLayout.LayoutParams(px(w), -2) }
            col.addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(px(12), px(12), px(12), px(4))
                addView(Ui.text(this@MainActivity, "Model", 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(head("wait", "wait", 64))
                addView(head("per 1000", "cost", 84))
            })
            val models = Models.all(this@MainActivity).sortedBy { m ->
                if (sort == "cost") Models.estimateUsd(m, effort(m.id))
                else Models.latency(m, effort(m.id)) ?: Double.MAX_VALUE
            }
            for (m in models) {
                val on = m.id == model
                col.addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(12), px(14), px(12), px(14))
                    if (on) background = Ui.rounded(if (p.dark) Color.rgb(44, 44, 50) else Color.rgb(255, 244, 204), px(12).toFloat())
                    addView(Ui.icon(this@MainActivity, m.logo, logoTint(m)), LinearLayout.LayoutParams(px(22), px(22)).apply { marginEnd = px(14) })
                    addView(Ui.text(this@MainActivity, m.name, 16f, p.text, on), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Ui.text(this@MainActivity, Models.latency(m, effort(m.id))?.let { "%.1f s".format(it) } ?: "", 15f, p.muted).apply {
                        fontFeatureSettings = "tnum"; gravity = Gravity.END
                    }, LinearLayout.LayoutParams(px(64), -2))
                    addView(Ui.text(this@MainActivity, moneyColumn(Models.estimateUsd(m, effort(m.id)) * 1000), 15f, p.muted).apply {
                        fontFeatureSettings = "tnum"; gravity = Gravity.END
                    }, LinearLayout.LayoutParams(px(84), -2))
                    contentDescription = "${m.name}, about ${moneyColumn(Models.estimateUsd(m, effort(m.id)) * 1000)} per 1000 moments" + if (on) ", selected" else ""
                    setOnClickListener {
                        model = m.id
                        prefs.edit().putString("model", model).apply()
                        dialog.dismiss(); buildUi()
                    }
                })
            }
        }
        fill()
        dialog.setView(ScrollView(this).apply { addView(col) })
        dialog.window?.setBackgroundDrawable(Ui.rounded(p.card, px(20).toFloat()))
        dialog.show()
    }

    /** Median real cost per moment for this model and thinking level, from the session logs.
     *  Rows from before model logging were all Gemini 3.1 Flash Lite without thinking. */
    private fun measuredCost(id: String, effort: String): Pair<Double, Int>? {
        val costs = ArrayList<Double>()
        getExternalFilesDir("sessions")?.listFiles()?.forEach { dir ->
            File(dir, "session.jsonl").takeIf { it.exists() }?.forEachLine { line ->
                val r = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (r.optString("mode") !in setOf("ask", "moment")) return@forEachLine
                if (r.optString("model", "google/gemini-3.1-flash-lite") != id) return@forEachLine
                if (r.optString("reasoning", "none") != effort) return@forEachLine
                r.optDouble("cost_usd").takeIf { !it.isNaN() && it > 0 }?.let { costs += it }
            }
        }
        if (costs.isEmpty()) return null
        costs.sort()
        return costs[costs.size / 2] to costs.size
    }

    /** Live OpenRouter prices, at most once a day; redraws Settings when they arrive. */
    private fun maybeRefreshPrices() {
        if (System.currentTimeMillis() - prefs.getLong("prices_at", 0) < 24 * 3600_000L) return
        prefs.edit().putLong("prices_at", System.currentTimeMillis()).apply()
        Thread { if (Models.refresh(this)) ui.post { if (tab == "settings") buildUi() } }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            tab == "history" && history.back() -> {}
            tab != "home" -> { tab = "home"; buildUi() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
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
        pressAt = System.currentTimeMillis(); releaseAt = 0L; sttAt = 0L
        synchronized(marks) { marks.clear() }
        tts.stop()
        synchronized(this) { pendingSpeech = null; speaking = false }
        askShot = null; askCameraFailed = false; question = null; partial = ""
        askCapture = true                       // the camera loop takes the photo right away
        buzz(60); tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
        ui.post { describeButton.text = "Listening…"; describeButton.alpha = 0.6f; setAnswer("…") }
        listening = true
        val r = recognizer ?: newRecognizer().also { recognizer = it }
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        captureReadEnd = startCapture(intent)
        r.startListening(intent)
    }

    // startListening() parcels the intent later on another thread: keep our read end open until the ask ends.
    private var captureReadEnd: ParcelFileDescriptor? = null
    private fun closeReadEnd() { runCatching { captureReadEnd?.close() }; captureReadEnd = null }

    private fun endAsk() {
        if (!listening) return
        listening = false
        releaseAt = System.currentTimeMillis()
        tone.startTone(ToneGenerator.TONE_PROP_PROMPT, 80)
        ui.post { describeButton.text = "Thinking…" }
        if (capturing) {
            // Piped audio: end the stream and let the recognizer finish what it has buffered.
            // stopListening() here would finalize early and drop the last words.
            stopCapture()
            ui.postDelayed({ if (sttAt == 0L) recognizer?.stopListening() }, 2500)
        } else recognizer?.stopListening()     // the final result arrives in onResults
    }

    private fun newRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)

    private val listener = object : RecognitionListener {
        override fun onResults(b: Bundle?) {
            val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            question = text.ifBlank { partial }
            sttAt = System.currentTimeMillis()
            closeReadEnd()
            if (releaseAt == 0L) releaseAt = sttAt     // recognizer ended on its own before release
            listening = false
            tryAnswer()
        }
        override fun onPartialResults(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                if (it.isNotBlank()) {
                    if (it != partial) { mark("first_partial", overwrite = false); mark("last_partial") }
                    partial = it; ui.post { setAnswer("“$it”") }
                }
            }
        }
        override fun onError(error: Int) {
            show("speech error $error")
            stopCapture(); closeReadEnd()
            question = partial          // whatever was heard before the error, maybe nothing
            sttAt = System.currentTimeMillis()
            if (releaseAt == 0L) releaseAt = sttAt
            listening = false
            tryAnswer()
        }
        override fun onReadyForSpeech(p: Bundle?) { mark("mic_ready") }
        override fun onBeginningOfSpeech() { mark("speech_begin", overwrite = false) }
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() { mark("speech_end") }
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
        setAnswer("“$q”")
        net.execute { answer(q, shot) }
    }

    private fun answer(q: String, s: Shot) {
        var heard = ""
        try {
            val jpeg = Brain.brighten(s.jpeg)
            ui.post { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size).let { lastPhoto = it; photoView.setImageBitmap(it) } }
            mark("request_sent")
            val a = Brain.describe(jpeg, key, model, askPrompt.replace("{q}", q.replace("\"", "'")),
                maxTokens = Models.maxTokens(200, effort()), reasoning = effort())
            mark("answer_received")
            addSpent(a.costUsd)
            heard = a.text
            buzz(20)
            mark("voice_requested")
            tts.speak(heard, TextToSpeech.QUEUE_FLUSH, null, "${s.t0}|${s.photo}")
            fun at(ms: Long): Any = if (ms > 0) iso.format(Date(ms)) else JSONObject.NULL
            val m = JSONObject()
            synchronized(marks) { marks.forEach { (k, v) -> m.put(k, iso.format(Date(v))) } }
            val wav = if (recordQuestionAudio) saveQuestionAudio(s.photo) else null
            logRow(JSONObject().put("photo", s.photo).put("mode", "ask").put("question", q)
                .put("marks", m)
                .put("question_audio", wav ?: JSONObject.NULL)
                .put("question_audio_start_at", if (wav != null) iso.format(Date(captureStartAt)) else JSONObject.NULL)
                .put("press_at", at(pressAt)).put("release_at", at(releaseAt)).put("stt_at", at(sttAt))
                .put("size", sizeOf(onceSize)).put("captured_at", iso.format(Date(s.t0)))
                .put("answered_at", iso.format(Date())).put("camera_s", s.camS).put("enhance", "auto")
                .put("model_s", a.seconds).put("cost_usd", a.costUsd).put("status", "spoken")
                .put("model", model).put("reasoning", effort()).put("tokens_in", a.tokensIn)
                .put("tokens_out", a.tokensOut).put("reasoning_tokens", a.reasoningTokens)
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
        setAnswer(answer)
    }

    private fun show(msg: String) = ui.post {
        logText = (msg + "\n" + logText).take(4000)
        log.text = logText
    }

    private fun setAnswer(t: CharSequence) { answerText = t; answerView.text = t }

    private fun setStatus(msg: String) = ui.post { statusText = msg; status.text = homeStatus() }

    private fun cameraLine() = when (val b = board) {
        is WifiBoard -> "Camera on Wi-Fi"
        null -> "Camera not connected"
        else -> "Camera on USB"
    }

    /** Short by default; the full last status message with developer details on. */
    private fun homeStatus() = (if (board != null) "●  " else "○  ") + cameraLine() +
        (if (devDetails() || board == null) "\n$statusText" else "")

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
            recordQuestionAudio = c.optBoolean("record_question_audio", false)
        }
        prefs.getString("model", null)?.let { model = it }
        reasoning = prefs.getString("reasoning", "none")!!.takeIf { it == "none" || it == "low" } ?: "low"
        if (prefs.contains("record_question_audio")) recordQuestionAudio = prefs.getBoolean("record_question_audio", false)
        momentPrompt = assets.open(momentPromptFile).bufferedReader().readText().trim()
        askPrompt = assets.open(askPromptFile).bufferedReader().readText().trim()
        if (key.isEmpty()) setStatus("No API key: push openrouter_key.txt over adb (phone/README.md)")
    }

    private fun spent(): Double = getSharedPreferences("sense", MODE_PRIVATE).getFloat("spent", 0f).toDouble()
    private fun spentText() = "Spent ${money(spent())} of ${money(budgetUsd)}"
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
                setStatus("Board connected. ${spentText()}. Model: $model")
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
                    "${spentText()}. Model: $model")
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
                    mark("photo_requested")
                    val jpeg = b.grab()
                    mark("photo_in_hand")
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
            ui.post { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size).let { lastPhoto = it; photoView.setImageBitmap(it) } }
            val a = Brain.describe(jpeg, key, model, momentPrompt, maxTokens = Models.maxTokens(150, effort()), reasoning = effort())
            addSpent(a.costUsd)
            val now = System.currentTimeMillis()
            heard = if (a.text.trim(' ', '.', '"', '\'').uppercase() == "SILENT") "Nothing notable." else a.text
            buzz(20)
            synchronized(this) { pendingSpeech = null; speaking = false }
            tts.speak(heard, TextToSpeech.QUEUE_FLUSH, null, "$capturedAt|$photo")
            logRow(JSONObject().put("photo", photo).put("mode", "moment").put("size", sizeOf(onceSize))
                .put("captured_at", iso.format(Date(capturedAt))).put("answered_at", iso.format(Date(now)))
                .put("camera_s", camS).put("enhance", "auto").put("model_s", a.seconds)
                .put("model", model).put("reasoning", effort()).put("tokens_in", a.tokensIn)
                .put("tokens_out", a.tokensOut).put("reasoning_tokens", a.reasoningTokens)
                .put("cost_usd", a.costUsd).put("status", "spoken").put("text", a.text)
                .put("spoken_text", heard).put("prompt_file", momentPromptFile)
                .put("device", "android").put("link", link))
            show("[once: cam ${"%.2f".format(camS)}s + model ${"%.2f".format(a.seconds)}s] $heard")
            setStatus("${if (link == "wifi") "Camera on Wi-Fi" else "Camera on USB"} · ${spentText()}")
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
            ui.post { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size).let { lastPhoto = it; photoView.setImageBitmap(it) } }
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
        override fun onDone(id: String) {
            val photo = id.substringAfter("|", "")
            if (photo.isNotEmpty())
                logRow(JSONObject().put("photo", photo).put("event", "spoke_done").put("done_at", iso.format(Date())))
            next()
        }
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
