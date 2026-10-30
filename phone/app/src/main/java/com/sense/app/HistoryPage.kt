package com.sense.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Rupees per dollar for showing costs; approximate, update when it drifts. */
const val INR_PER_USD = 88.0

/** Costs are shown in one currency, chosen in Settings (rupees by default). */
object Currency {
    var rupees = true
    fun load(ctx: Context) { rupees = ctx.getSharedPreferences("sense", Context.MODE_PRIVATE).getString("currency", "inr") == "inr" }
}

fun money(usd: Double): String {
    val v = if (Currency.rupees) usd * INR_PER_USD else usd
    val sym = if (Currency.rupees) "₹" else "$"
    return sym + when { v >= 10 -> "%.0f"; v >= 1 -> "%.2f"; v >= 0.01 -> "%.3f"; v >= 0.001 -> "%.4f"; else -> "%.5f" }.format(v)
}

/** Fixed decimals for columns of prices (per 1000 moments): $ to the cent, ₹ to one decimal. */
fun moneyColumn(usd: Double) =
    if (Currency.rupees) "₹%.1f".format(usd * INR_PER_USD) else "$%.2f".format(usd)

/**
 * The History tab (a debugging aid): past moments, Describe taps and hold-to-ask questions, newest
 * first. Opening one replays it like tools/moment_timeline.py (lap timer, milestones, latency badge,
 * the two timelines, the recorded question if record_question_audio was on, the answer spoken when it
 * was spoken). The step and event tables start collapsed; developer details show only when the
 * Settings switch is on. Drag on the replay to scrub.
 */
class HistoryPage(
    private val activity: Activity,
    private val speak: (String) -> Unit,
    private val stopSpeaking: () -> Unit,
    private val devDetails: () -> Boolean,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null
    val view = FrameLayout(activity)
    var inDetail = false
        private set

    private fun px(v: Int) = Ui.dp(activity, v)
    private val p get() = Ui.p

    fun show() {
        stop(); inDetail = false
        view.setBackgroundColor(p.bg)
        view.removeAllViews(); view.addView(buildList())
    }

    /** Back from a moment to the list. False when already on the list. */
    fun back(): Boolean { if (!inDetail) return false; show(); return true }

    fun stop() {
        ui.removeCallbacksAndMessages(null)
        stopSpeaking()
        player?.release(); player = null
    }

    // --- list --------------------------------------------------------------------------------

    private fun buildList(): View {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, px(16), px(16)) }
        val moments = Moments.load(activity.getExternalFilesDir("sessions"))
        if (moments.isEmpty()) col.addView(Ui.text(activity, "Nothing yet. Tap Describe or hold to ask.", 16f, p.muted))
        var day = ""
        for (m in moments) {
            val d = DAY.format(Date(m.at))
            if (d != day) { day = d; col.addView(Ui.section(activity, d)) }
            col.addView(row(m))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.header(activity, "History", "What you asked and what it said"))
            addView(ScrollView(activity).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun row(m: Moment): View {
        val thumb = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = Ui.rounded(p.line, px(10).toFloat())
        }
        io.execute {
            val b = BitmapFactory.decodeFile(m.photo.path, BitmapFactory.Options().apply { inSampleSize = 8 })
            ui.post { thumb.setImageBitmap(b) }
        }
        val meta = "${CLOCK.format(Date(m.at))}  ·  " +
            (m.latency?.let { "${if (m.est) "~" else ""}${"%.1f".format(it)} s" } ?: "") +
            (if (m.audio != null) "  ·  🎙" else "")
        val words = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, 0, 0)
            addView(Ui.text(activity, m.question?.let { "“$it”" } ?: "Described", 15f, if (m.ask) p.primaryText else p.muted, true))
            addView(Ui.text(activity, m.answer, 14f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(Ui.text(activity, meta, 12f, p.muted).apply { setPadding(0, px(2), 0, 0) })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(10), px(10), px(10))
            background = Ui.rounded(p.card, px(14).toFloat(), if (p.dark) null else p.line, 1)
            addView(thumb, LinearLayout.LayoutParams(px(72), px(54)))
            addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { open(m) }
            contentDescription = (m.question?.let { "Asked $it. " } ?: "Described. ") + m.answer
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) }
        }
    }

    // --- one moment --------------------------------------------------------------------------

    private fun open(m: Moment) {
        stop()
        inDetail = true
        val timeline = TimelineView(activity, m)
        io.execute {
            val jpeg = Brain.brighten(m.photo.readBytes())     // what the model saw
            val b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ui.post { timeline.photo = b; timeline.invalidate() }
        }
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(10), px(16), px(10))
            addView(Ui.button(activity, "▶  Replay") { replay(m, timeline) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.text(activity, "or drag on\nthe picture", 12f, p.muted).apply {
                setPadding(px(14), 0, 0, 0); gravity = Gravity.CENTER
            })
        }

        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, px(16), px(24)) }
        m.question?.let {
            body.addView(Ui.section(activity, "You asked"))
            body.addView(Ui.text(activity, "“$it”", 18f, p.primaryText, true))
        }
        body.addView(Ui.section(activity, if (m.ask) "It answered" else "It said"))
        body.addView(Ui.text(activity, m.answer, 18f))

        body.addView(Ui.section(activity, "At a glance"))
        body.addView(Ui.card(activity).apply { table(this, m.summary.map { listOf(it.first, it.second) }, floatArrayOf(1f, 1f)) })

        body.addView(Ui.section(activity, "More"))
        body.addView(Ui.collapsible(activity, "Where the time went") { c ->
            table(c, listOf(listOf("Step", "Start", "End", "Took")) + m.steps.map { s ->
                listOf(STEP_NAMES[s.row] ?: s.row, secs(s.a), s.b?.let { secs(it) } ?: "?",
                    (if (s.est) "~" else "") + (s.b?.let { secs(it - s.a) } ?: "?"))
            }, floatArrayOf(1.5f, 1f, 1f, 1f), header = true, dots = m.steps.map { COLORS[it.row] })
        })
        body.addView(View(activity), LinearLayout.LayoutParams(-1, px(8)))
        body.addView(Ui.collapsible(activity, "Every event, in order") { c ->
            table(c, m.events.map { listOf(it.first, secs(it.second)) }, floatArrayOf(2f, 1f))
        })
        if (devDetails()) {
            body.addView(View(activity), LinearLayout.LayoutParams(-1, px(8)))
            body.addView(Ui.collapsible(activity, "Developer details") { c ->
                table(c, m.dev.map { listOf(it.first, it.second) }, floatArrayOf(1f, 1.6f))
            })
        }

        view.removeAllViews()
        view.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            addView(Ui.header(activity, if (m.ask) "Question" else "Description",
                "${DAY.format(Date(m.at))}, ${CLOCK.format(Date(m.at))}") { show() })
            addView(timeline, LinearLayout.LayoutParams(-1, -2))       // pinned with its controls
            addView(controls)
            addView(Ui.divider(activity), LinearLayout.LayoutParams(-1, 1))
            addView(ScrollView(activity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        timeline.t = timeline.end                   // the finished moment, until Replay
    }

    private fun secs(v: Double) = "%.2f s".format(v)

    /** Rows of cells; the first column is a label. Optional coloured dots before the labels. */
    private fun table(parent: LinearLayout, rows: List<List<String>>, weights: FloatArray,
                      header: Boolean = false, dots: List<Int?> = emptyList()) {
        rows.forEachIndexed { i, cells ->
            val isHead = header && i == 0
            val dot = if (header && i > 0) dots.getOrNull(i - 1) else null
            parent.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(10), 0, px(10))
                cells.forEachIndexed { j, c ->
                    val label: CharSequence = if (j == 0 && dot != null)
                        SpannableString("●  $c").apply { setSpan(ForegroundColorSpan(dot), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    else c
                    addView(Ui.text(activity, label, if (isHead) 12f else 15f,
                        if (isHead || j == 0) p.muted else p.text, isHead).apply {
                        if (j > 0) gravity = Gravity.END
                    }, LinearLayout.LayoutParams(0, -2, weights[j]))
                }
            })
            if (i < rows.size - 1) parent.addView(Ui.divider(activity), LinearLayout.LayoutParams(-1, 1))
        }
    }

    /** Plays the moment in real time: the picture's clock, the question audio and the answer voice. */
    private fun replay(m: Moment, timeline: TimelineView) {
        stop()
        val start = SystemClock.uptimeMillis() + 300
        m.audio?.let { (file, offset) ->
            ui.postDelayed({
                player = MediaPlayer().apply { setDataSource(file.path); prepare(); start() }
            }, 300 + (offset * 1000).toLong().coerceAtLeast(0))
        }
        ui.postDelayed({ speak(m.answer) }, 300 + (m.steps.last().a * 1000).toLong())
        val tick = object : Runnable {
            override fun run() {
                timeline.t = maxOf(0.0, (SystemClock.uptimeMillis() - start) / 1000.0)
                timeline.invalidate()
                if (timeline.t < timeline.end) ui.postDelayed(this, 33)
            }
        }
        timeline.t = 0.0; ui.post(tick)
    }

    companion object {
        val COLORS = mapOf(
            "you" to Color.rgb(255, 204, 0), "camera" to Color.rgb(90, 200, 250),
            "speech-to-text" to Color.rgb(190, 140, 255), "model" to Color.rgb(255, 140, 60),
            "voice" to Color.rgb(90, 220, 120))
        val STEP_NAMES = mapOf("you" to "You", "camera" to "Camera", "speech-to-text" to "Speech to text",
            "model" to "Model", "voice" to "Voice")
        val MARK_NAMES = mapOf(
            "photo_requested" to "Photo requested", "photo_in_hand" to "Photo received",
            "audio_capture_start" to "Recording started", "mic_ready" to "Microphone ready",
            "speech_begin" to "Recognizer heard speech", "first_partial" to "First words recognized",
            "last_partial" to "Last words recognized", "speech_end" to "Recognizer heard silence",
            "request_sent" to "Sent to model", "answer_received" to "Answer received",
            "voice_requested" to "Voice requested")
        private val CLOCK = SimpleDateFormat("h:mm a", Locale.US)
        private val DAY = SimpleDateFormat("EEE, d MMM", Locale.US)
    }
}

/** One step of a moment, in seconds since the press (or tap). */
data class Step(val row: String, val label: String, val a: Double, val b: Double?, val est: Boolean)

class Moment(
    val session: String, val photo: File, val ask: Boolean, val question: String?, val answer: String,
    val modelSaid: String, val steps: List<Step>, val est: Boolean, val audio: Pair<File, Double>?,
    val at: Long, val summary: List<Pair<String, String>>, val dev: List<Pair<String, String>>,
    val events: List<Pair<String, Double>>,
) {
    /** Let go (ask) or tap (describe) to the first word. */
    val latency: Double? get() = steps[0].b?.let { steps.last().a - it }
}

object Moments {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private fun ts(s: String) = iso.parse(s)!!.time / 1000.0
    private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k).ifEmpty { null }

    fun load(root: File?): List<Moment> {
        val out = ArrayList<Moment>()
        val dirs = root?.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: return out
        for (dir in dirs) {
            val f = File(dir, "session.jsonl").takeIf { it.exists() } ?: continue
            val rows = f.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            val spoke = rows.filter { it.optString("event") == "spoke" }.associateBy { it.optString("photo") }
            val done = rows.filter { it.optString("event") == "spoke_done" }.associateBy { it.optString("photo") }
            for (r in rows.reversed()) {
                val mode = r.optString("mode")
                if (mode != "ask" && mode != "moment") continue
                runCatching { out.add(build(dir, r, spoke[r.optString("photo")], done[r.optString("photo")])) }
            }
        }
        return out
    }

    private fun build(dir: File, r: JSONObject, spoke: JSONObject?, done: JSONObject?): Moment {
        val ask = r.optString("mode") == "ask"
        val answer = r.str("spoken_text") ?: r.optString("text")
        val voiceGuess = answer.split(" ").size * 60.0 / (180 * 1.5)   // the app speaks at 1.5x
        val marks = r.optJSONObject("marks")
        val steps: List<Step>
        val events = ArrayList<Pair<String, Double>>()
        var est = false
        var audio: Pair<File, Double>? = null
        val zero: Double
        if (ask && marks != null && r.str("release_at") != null && spoke?.str("audio_at") != null) {
            zero = ts(r.getString("press_at"))
            fun mk(k: String) = ts(marks.getString(k)) - zero
            val rel = ts(r.getString("release_at")) - zero
            val stt = ts(r.getString("stt_at")) - zero
            val voiceAt = ts(spoke.getString("audio_at")) - zero
            val voiceEnd = done?.str("done_at")?.let { ts(it) - zero }
            steps = listOf(
                Step("you", "holding, speaking", 0.0, rel, false),
                Step("camera", "photo over Wi-Fi", mk("photo_requested"), mk("photo_in_hand"), false),
                Step("speech-to-text", "final words", rel, stt, false),
                Step("model", "request to answer", mk("request_sent"), mk("answer_received"), false),
                Step("voice", "speaking answer", voiceAt, voiceEnd ?: (voiceAt + voiceGuess), voiceEnd == null))
            r.str("question_audio")?.let { name ->
                val wav = File(dir, name)
                if (wav.exists()) audio = wav to (ts(r.getString("question_audio_start_at")) - zero)
            }
            events += "Button pressed" to 0.0
            marks.keys().forEach { k -> events += (HistoryPage.MARK_NAMES[k] ?: k.replace('_', ' ')) to mk(k) }
            events += "Let go" to rel
            events += "Question final" to stt
            events += "Voice started" to voiceAt
            voiceEnd?.let { events += "Voice finished" to it }
        } else if (ask) {
            zero = ts(r.str("press_at") ?: r.getString("captured_at"))
            val answered = ts(r.getString("answered_at")) - zero
            val model = r.getDouble("model_s")
            est = r.str("release_at") == null || r.str("stt_at") == null
            val sttEnd = if (est) answered - model else ts(r.getString("stt_at")) - zero
            val release = if (est) sttEnd - 0.3 else ts(r.getString("release_at")) - zero
            val voiceAt = spoke?.str("audio_at")?.let { ts(it) - zero } ?: (answered + 0.1)
            val voiceEnd = done?.str("done_at")?.let { ts(it) - zero }
            steps = listOf(
                Step("you", "speaking", 0.0, release, est),
                Step("camera", "photo over Wi-Fi", 0.0, r.getDouble("camera_s"), false),
                Step("speech-to-text", "final words", release, sttEnd, est),
                Step("model", "request to answer", sttEnd, answered, false),
                Step("voice", "speaking answer", voiceAt, voiceEnd ?: (voiceAt + voiceGuess), voiceEnd == null))
            events += "Button pressed" to 0.0
            events += "Photo received" to r.getDouble("camera_s")
            events += (if (est) "Let go (estimated)" else "Let go") to release
            events += "Answer received" to answered
            events += "Voice started" to voiceAt
            voiceEnd?.let { events += "Voice finished" to it }
        } else {
            zero = ts(r.getString("captured_at"))
            val answered = ts(r.getString("answered_at")) - zero
            val voiceAt = spoke?.str("audio_at")?.let { ts(it) - zero } ?: (answered + 0.1)
            val voiceEnd = done?.str("done_at")?.let { ts(it) - zero }
            steps = listOf(
                Step("you", "tap", 0.0, 0.0, false),
                Step("camera", "photo over Wi-Fi", 0.0, r.getDouble("camera_s"), false),
                Step("model", "request to answer", answered - r.getDouble("model_s"), answered, false),
                Step("voice", "speaking answer", voiceAt, voiceEnd ?: (voiceAt + voiceGuess), voiceEnd == null))
            events += "Tapped" to 0.0
            events += "Photo received" to r.getDouble("camera_s")
            events += "Sent to model" to answered - r.getDouble("model_s")
            events += "Answer received" to answered
            events += "Voice started" to voiceAt
            voiceEnd?.let { events += "Voice finished" to it }
        }
        events.sortBy { it.second }
        val latency = steps[0].b?.let { steps.last().a - it }
        val summary = ArrayList<Pair<String, String>>()
        latency?.let { summary += "Wait for answer" to "${if (est) "~" else ""}${"%.2f".format(it)} s" }
        summary += "Cost" to money(r.optDouble("cost_usd", 0.0))
        val dev = ArrayList<Pair<String, String>>()
        dev += "Model time" to "%.2f s".format(r.optDouble("model_s"))
        dev += "Camera time" to "%.2f s".format(r.optDouble("camera_s"))
        dev += "Prompt" to r.optString("prompt_file").removeSuffix(".txt")
        dev += "Photo" to "${r.optString("size").replace("x", " × ")} over ${if (r.optString("link") == "wifi") "Wi-Fi" else "USB"}"
        dev += "Your voice" to if (audio != null) "recorded" else "not recorded"
        dev += "File" to "${dir.name} / ${r.optString("photo")}"
        if (r.optString("text") != answer) dev += "Model said" to r.optString("text")
        return Moment(dir.name, File(dir, r.getString("photo")), ask, if (ask) r.optString("question") else null,
            answer, r.optString("text"), steps, est, audio, (zero * 1000).toLong(), summary, dev, events)
    }
}

/** The moment_timeline.py frame, drawn in its 960×720 space and scaled to the view's width. */
class TimelineView(context: Context, private val m: Moment) : View(context) {
    var photo: Bitmap? = null
    var t = 0.0
    val end = m.steps.maxOf { it.b ?: it.a } + 0.4
    private val W = 960f
    private val H = 720f
    private val colors = HistoryPage.COLORS
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val since = if (m.ask) "since press" else "since tap"

    init {
        contentDescription = "Replay of the moment. Drag to scrub."
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, width * 3 / 4)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            parent.requestDisallowInterceptTouchEvent(true)
            val x = e.x * W / width
            t = ((x - 96f) / (W - 24f - 96f) * end).coerceIn(0.0, end)
            invalidate()
        }
        return true
    }

    private fun font(size: Float, bold: Boolean = false) = p.apply {
        textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; style = Paint.Style.FILL
    }

    /** PIL-style text: y is the top of the text, not the baseline. */
    private fun Canvas.text(s: String, x: Float, y: Float, color: Int) {
        p.color = color; drawText(s, x, y - p.ascent() * 0.86f, p)
    }

    private fun Canvas.box(l: Float, top: Float, r: Float, b: Float, radius: Float, color: Int) {
        p.style = Paint.Style.FILL; p.color = color; drawRoundRect(RectF(l, top, r, b), radius, radius, p)
    }

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(c: Canvas) {
        c.save()
        c.scale(width / W, height / H)
        photo?.let { c.drawBitmap(it, null, RectF(0f, 0f, W, H), null) } ?: c.drawColor(Color.DKGRAY)
        val s = m.steps
        val voiceAt = s.last().a
        val released = s[0].b!!
        val right = W - 14

        // question (top right)
        m.question?.let {
            val q = "“$it”"
            val qw = font(24f, true).measureText(q)
            c.box(W - qw - 40, 14f, right, 54f, 10f, Color.argb(235, 255, 204, 0))
            c.text(q, W - qw - 27, 20f, Color.BLACK)
        }
        // latency badge, once the voice starts
        val badgeTop = if (m.question != null) 62f else 14f
        if (t >= voiceAt) {
            val lat = "latency ${if (m.est) "~" else ""}${"%.2f".format(voiceAt - released)} s"
            val sub = if (m.ask) "from letting go to first word" else "from tap to first word"
            val lw = font(22f, true).measureText(lat)
            val sw = font(14f).measureText(sub)
            val bw = maxOf(lw, sw)
            c.box(W - bw - 42, badgeTop, right, badgeTop + 56, 12f, alpha(colors.getValue("voice"), 240))
            font(22f, true); c.text(lat, W - lw - 28, badgeTop + 4, Color.BLACK)
            font(14f); c.text(sub, W - sw - 28, badgeTop + 34, Color.rgb(20, 40, 20))
        }
        // milestone pop-up
        val events = ArrayList<Pair<Double, String>>()
        val cam = s.first { it.row == "camera" }
        val model = s.first { it.row == "model" }
        events += cam.b!! to "photo received  ${"%.2f".format(cam.b - cam.a)} s"
        if (m.ask) events += released to "${if (s[0].est) "~" else ""}you let go"
        s.firstOrNull { it.row == "speech-to-text" }?.let { events += it.b!! to "${if (it.est) "~" else ""}words final" }
        events += model.a to "request sent"
        events += model.b!! to "answer received"
        events += voiceAt to "voice starts"
        events.filter { t >= it.first && t < it.first + 1.2 }.maxByOrNull { it.first }?.let { (_, msg) ->
            val mw = font(20f, true).measureText(msg)
            val y = if (t >= voiceAt) badgeTop + 66 else badgeTop + 4
            c.box(W - mw - 42, y, right, y + 34, 16f, Color.argb(230, 255, 255, 255))
            font(20f, true); c.text(msg, W - mw - 28, y + 6, Color.rgb(20, 20, 20))
        }

        // lap timer (top left)
        val started = s.filter { it.a <= t }
        val lapH = 36 + 26 * started.size + (if (m.est) 18 else 0)
        c.box(14f, 14f, 330f, 14f + lapH + 8, 10f, Color.argb(185, 0, 0, 0))
        font(30f, true); c.text("%5.2f s".format(minOf(t, end)), 26f, 18f, Color.WHITE)
        font(16f); c.text(since, 150f, 30f, Color.rgb(190, 190, 190))
        if (m.est) { font(12f); c.text("~ = estimated (not yet logged)", 26f, 14f + lapH - 14, Color.rgb(170, 170, 170)) }
        val order = started.sortedWith(compareBy({ if (it.b != null && t >= it.b) it.b else 1e9 }, { it.a }))
        order.forEachIndexed { i, st ->
            val y = 58f + 26 * i
            val done = st.b != null && t >= st.b
            val dur = if (done) st.b!! - st.a else t - st.a
            val col = colors.getValue(st.row)
            p.color = col; p.strokeWidth = 2f
            p.style = if (done) Paint.Style.FILL else Paint.Style.STROKE
            c.drawOval(RectF(26f, y + 5, 38f, y + 17), p)
            font(17f, true); c.text(st.row, 46f, y, col)
            font(17f, done); c.text("${if (st.est) "~" else " "}${"%4.2f".format(dur)} s", 180f, y,
                if (done) Color.WHITE else Color.rgb(200, 200, 200))
            if (!done) { font(17f); c.text("…", 262f, y, Color.rgb(200, 200, 200)) }
        }

        // answer caption, once the voice starts
        if (t >= voiceAt) {
            font(26f, true)
            val lines = ArrayList<String>()
            var line = ""
            for (w in m.answer.split(" ")) {
                val next = "$line $w".trim()
                if (p.measureText(next) > W - 60 && line.isNotEmpty()) { lines += line; line = w } else line = next
            }
            lines += line
            val top = H - 128 - 34 * lines.size
            p.style = Paint.Style.FILL; p.color = Color.argb(200, 0, 0, 0)
            c.drawRect(0f, top - 10, W, top + 34 * lines.size, p)
            font(26f, true)
            lines.forEachIndexed { i, l -> c.text(l, 30f, top + 34 * i, Color.WHITE) }
        }

        // two timelines: you, device
        val bandTop = H - 106
        val rowH = 40f
        val x0 = 96f
        val x1 = W - 24
        fun sx(v: Double) = (x0 + (x1 - x0) * v / end).toFloat()
        p.style = Paint.Style.FILL; p.color = Color.argb(228, 10, 10, 14)
        c.drawRect(0f, bandTop - 8, W, H, p)
        for (k in 0..end.toInt()) {
            p.color = Color.rgb(70, 70, 80); p.strokeWidth = 1f
            c.drawLine(sx(k.toDouble()), bandTop, sx(k.toDouble()), H - 14, p)
            font(11f); c.text("${k}s", sx(k.toDouble()) - 6, H - 16, Color.rgb(130, 130, 140))
        }
        val lanes = listOf(Triple("you", colors.getValue("you"), s.take(1)), Triple("device", Color.rgb(220, 220, 225), s.drop(1)))
        lanes.forEachIndexed { i, (name, nameColor, lane) ->
            val y = bandTop + 4 + rowH * i
            font(16f, true); c.text(name, 14f, y + 9, nameColor)
            for (st in lane) {
                if (t < st.a) continue
                val col = colors.getValue(st.row)
                val r = if (st.b != null) minOf(t, st.b) else t
                val box = RectF(sx(st.a), y + 4, maxOf(sx(r), sx(st.a) + 3), y + rowH - 6)
                p.style = Paint.Style.FILL; p.pathEffect = null
                p.color = alpha(col, if (st.est) 90 else 230); c.drawRect(box, p)
                if (st.est) {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = col
                    p.pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f); c.drawRect(box, p); p.pathEffect = null
                }
                val txt = "${if (st.est) "~" else ""}${"%.2f".format(r - st.a)}s"
                font(13f, true)
                if (box.width() > p.measureText(txt) + 8) c.text(txt, box.left + 4, y + 11, if (st.est) Color.WHITE else Color.BLACK)
            }
        }
        p.color = Color.WHITE; p.strokeWidth = 2f; p.style = Paint.Style.STROKE
        val cx = sx(minOf(t, end))
        c.drawLine(cx, bandTop - 4, cx, H - 18, p)
        c.restore()
    }
}
