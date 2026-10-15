package kr.jptalk.app

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class Side { THEM, ME, PHOTO }
enum class MsgState { PENDING, DONE, ERROR }
enum class Mode { THEM, ME, ADD }

data class Msg(
    val id: Long,
    val side: Side,
    val src: String,
    val ja: String = "",
    val ko: String = "",
    val pron: String = "",
    val state: MsgState = MsgState.PENDING,
    val error: String = "",
    val thumb: androidx.compose.ui.graphics.ImageBitmap? = null
)

data class Phrase(val label: String, val ko: String, val ja: String, val custom: Boolean = false)

val RATES = listOf(0.75f, 1.0f, 1.25f)
val RATE_LABELS = listOf("느리게", "보통", "빠르게")

data class Settings(
    val btMic: Boolean = false,
    val outThem: Out = Out.EARPHONE,
    val outMe: Out = Out.SPEAKER,
    val rateKo: Int = 1,
    val rateJa: Int = 1,
    val autoRead: Boolean = true,
    val phraseRead: Boolean = true
) {
    fun save(p: SharedPreferences) {
        p.edit()
            .putBoolean("btMic", btMic)
            .putString("outThem", outThem.name)
            .putString("outMe", outMe.name)
            .putInt("rateKo", rateKo)
            .putInt("rateJa", rateJa)
            .putBoolean("autoRead", autoRead)
            .putBoolean("phraseRead", phraseRead)
            .apply()
    }

    companion object {
        fun load(p: SharedPreferences): Settings {
            fun out(k: String, d: Out) = runCatching { Out.valueOf(p.getString(k, d.name)!!) }.getOrDefault(d)
            return Settings(
                btMic = p.getBoolean("btMic", false),
                outThem = out("outThem", Out.EARPHONE),
                outMe = out("outMe", Out.SPEAKER),
                rateKo = p.getInt("rateKo", 1).coerceIn(0, 2),
                rateJa = p.getInt("rateJa", 1).coerceIn(0, 2),
                autoRead = p.getBoolean("autoRead", true),
                phraseRead = p.getBoolean("phraseRead", true)
            )
        }
    }
}

val BASE_PHRASES = listOf(
    Phrase("번역 앱 안내", "번역 앱을 쓰고 있어요. 여기에 대고 말씀해 주세요.", "翻訳アプリを使っています。ここに向かって話してください。"),
    Phrase("안녕하세요", "안녕하세요.", "こんにちは。"),
    Phrase("잠시만요", "잠시만 기다려 주세요.", "少々お待ちください。"),
    Phrase("천천히 말해 주세요", "천천히 말씀해 주시겠어요?", "ゆっくり話していただけますか。"),
    Phrase("다시 한번요", "다시 한 번 부탁드려요.", "もう一度お願いします。"),
    Phrase("이쪽으로 오세요", "이쪽으로 오세요.", "こちらへどうぞ。"),
    Phrase("감사합니다", "감사합니다.", "ありがとうございます。"),
    Phrase("죄송합니다", "죄송합니다.", "申し訳ございません。")
)

class AppState(private val ctx: Context, private val scope: CoroutineScope) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("jptalk", Context.MODE_PRIVATE)

    val messages = mutableStateListOf<Msg>()
    val custom = mutableStateListOf<Phrase>()
    var settings by mutableStateOf(Settings.load(prefs))
        private set

    var listening by mutableStateOf<Mode?>(null)
        private set
    var partial by mutableStateOf("")
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var notice by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var headset by mutableStateOf("")
        private set
    var jaVoiceMissing by mutableStateOf(false)
        private set
    var addText by mutableStateOf("")
    var saving by mutableStateOf(false)
        private set

    private var nextId = 1L
    private var btMicOn = false

    val router = AudioRouter(ctx) { hs, change ->
        headset = hs
        if (change != null) toast = change
    }
    val tr = Translator()
    private val voice = Voice(ctx, router) { ja -> jaVoiceMissing = !ja }
    private val listener = Listener(
        ctx,
        onPartial = { t -> partial = t; if (listening == Mode.ADD) addText = t },
        onLevel = { level = it },
        onFinal = { t -> handleFinal(t) },
        onError = { e -> notice = e },
        onEnd = {
            listening = null
            level = 0f
            if (btMicOn) { router.clearComm(); btMicOn = false }
        }
    )
    private var lastMode: Mode? = null

    var firstRun: Boolean
        get() = !prefs.getBoolean("seenHelp", false)
        set(v) { prefs.edit().putBoolean("seenHelp", !v).apply() }

    init {
        loadCustom()
        router.refresh()
        // 무거운 준비는 미리 뒤에서
        scope.launch(Dispatchers.Default) { runCatching { Pron.init() } }
        scope.launch { runCatching { tr.ensure() } }
    }

    fun routeText(): String {
        val mic = if (settings.btMic && headset.isNotEmpty()) "이어폰 마이크" else "휴대폰 마이크"
        val out = if (headset.isNotEmpty()) "소리: $headset" else "소리: 휴대폰 스피커"
        return "$mic · $out"
    }

    fun updateSettings(s: Settings) {
        settings = s
        s.save(prefs)
    }

    // ---------- 듣기 ----------
    fun tap(mode: Mode) {
        if (listening == mode) { listener.finish(); return }
        if (listening != null) listener.destroy()
        notice = null
        voice.stop()
        partial = ""
        lastMode = mode
        listening = mode
        btMicOn = router.prepareMic(settings.btMic)
        listener.start(if (mode == Mode.THEM) "ja-JP" else "ko-KR")
    }

    fun cancelListening() {
        listener.destroy()
        listening = null
        level = 0f
        if (btMicOn) { router.clearComm(); btMicOn = false }
    }

    private fun handleFinal(text: String) {
        when (lastMode) {
            Mode.THEM -> translate(Side.THEM, text)
            Mode.ME -> translate(Side.ME, text)
            Mode.ADD -> addText = text
            null -> {}
        }
    }

    // ---------- 번역 ----------
    private fun upsert(m: Msg) {
        val i = messages.indexOfFirst { it.id == m.id }
        if (i >= 0) messages[i] = m else messages.add(m)
    }

    fun translate(side: Side, text: String, existingId: Long? = null) {
        val id = existingId ?: nextId++
        upsert(Msg(id, side, text))
        scope.launch {
            try {
                val ja: String
                val ko: String
                if (side == Side.THEM) {
                    ja = text
                    ko = tr.jaToKo(text)
                } else {
                    ko = text
                    ja = tr.koToJa(text)
                }
                val pron = withContext(Dispatchers.Default) { Pron.hangul(ja) }
                upsert(Msg(id, side, text, ja = ja, ko = ko, pron = pron, state = MsgState.DONE))
                if (side == Side.THEM && settings.autoRead) speakKo(ko)
            } catch (e: Exception) {
                val msg = tr.error ?: "번역하지 못했습니다. 다시 번역을 눌러 주세요."
                upsert(Msg(id, side, text, state = MsgState.ERROR, error = msg))
            }
        }
    }

    fun retry(m: Msg) = translate(m.side, m.src, m.id)

    fun retryDownload() {
        scope.launch { runCatching { tr.ensure() } }
    }

    fun speakKo(text: String) = voice.speak(text, Locale.KOREA, RATES[settings.rateKo], settings.outThem)
    fun speakJa(text: String) = voice.speak(text, Locale.JAPAN, RATES[settings.rateJa], settings.outMe)
    fun installVoices() = voice.installVoices()

    fun newConversation() {
        cancelListening()
        voice.stop()
        messages.clear()
        notice = null
    }

    // ---------- 자주 쓰는 말 ----------
    fun usePhrase(p: Phrase) {
        val id = nextId++
        scope.launch {
            val pron = withContext(Dispatchers.Default) { runCatching { Pron.hangul(p.ja) }.getOrDefault("") }
            upsert(Msg(id, Side.ME, p.ko, ja = p.ja, ko = p.ko, pron = pron, state = MsgState.DONE))
            if (settings.phraseRead) speakJa(p.ja)
        }
    }

    fun savePhrase(text: String, onDone: () -> Unit) {
        val ko = text.trim()
        if (ko.isEmpty() || saving) return
        saving = true
        scope.launch {
            try {
                val ja = tr.koToJa(ko)
                val base = ko.trimEnd('.', '?', '!', '。')
                val label = if (base.length > 14) base.take(13) + "…" else base
                custom.add(0, Phrase(label, ko, ja, true))
                saveCustom()
                toast = "자주 쓰는 말에 저장했습니다"
                addText = ""
                onDone()
            } catch (e: Exception) {
                toast = tr.error ?: "저장하지 못했습니다. 다시 시도해 주세요."
            } finally {
                saving = false
            }
        }
    }

    fun removePhrase(p: Phrase) {
        custom.remove(p)
        saveCustom()
    }

    private fun saveCustom() {
        val arr = JSONArray()
        custom.forEach { arr.put(JSONObject().put("label", it.label).put("ko", it.ko).put("ja", it.ja)) }
        prefs.edit().putString("phrases", arr.toString()).apply()
    }

    private fun loadCustom() {
        runCatching {
            val arr = JSONArray(prefs.getString("phrases", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                custom.add(Phrase(o.getString("label"), o.getString("ko"), o.getString("ja"), true))
            }
        }
    }

    // ---------- 사진 번역 ----------
    var photo by mutableStateOf<PhotoSession?>(null)
        private set
    private var photoBitmap: android.graphics.Bitmap? = null

    fun openPhoto(uri: Uri, deleteAfter: java.io.File? = null) {
        cancelListening()
        voice.stop()
        scope.launch {
            val bmp = try {
                withContext(Dispatchers.IO) { PhotoText.loadBitmap(ctx, uri) }
            } catch (e: Exception) {
                toast = "사진을 열지 못했습니다. 다른 사진으로 다시 시도해 주세요."
                return@launch
            } finally {
                deleteAfter?.delete()   // 찍은 사진은 저장하지 않음
            }
            photoBitmap = bmp
            val session = PhotoSession(bmp.asImageBitmap())
            photo = session
            try {
                val found = PhotoText.recognize(bmp)
                if (found.isEmpty()) {
                    session.error = "글자를 읽지 못했습니다. 밝은 곳에서 글자가 화면에 꽉 차게 다시 찍어 주세요."
                } else {
                    found.forEachIndexed { i, (rect, lines) ->
                        val b = withContext(Dispatchers.Default) { PhotoText.analyze(i + 1, rect, lines.joinToString("\n"), tr) }
                        session.blocks.add(b)
                    }
                }
            } catch (e: Exception) {
                session.error = tr.error ?: "글자를 읽는 중 문제가 생겼습니다. 다시 찍어 주세요."
            } finally {
                session.processing = false
            }
        }
    }

    fun closePhoto() {
        voice.stop()
        photo = null
        photoBitmap = null
    }

    private fun updateBlock(no: Int, f: (PBlock) -> PBlock) {
        val sess = photo ?: return
        val i = sess.blocks.indexOfFirst { it.no == no }
        if (i >= 0) sess.blocks[i] = f(sess.blocks[i])
    }

    fun toggleKind(b: PBlock) = updateBlock(b.no) {
        it.copy(kind = if (it.kind == BlockKind.LIST) BlockKind.PROSE else BlockKind.LIST)
    }

    fun toggleExpand(b: PBlock) = updateBlock(b.no) { it.copy(expanded = !it.expanded) }

    fun editBlock(b: PBlock, newText: String) {
        val t = newText.trim()
        if (t.isEmpty()) return
        scope.launch {
            try {
                val nb = withContext(Dispatchers.Default) { PhotoText.analyze(b.no, b.rect, t, tr, b.kind) }
                updateBlock(b.no) { nb }
            } catch (e: Exception) {
                toast = "다시 번역하지 못했습니다."
            }
        }
    }

    fun blockKo(b: PBlock) = if (b.kind == BlockKind.PROSE) b.proseKo else b.rows.joinToString("\n") { it.ko }
    fun blockJa(b: PBlock) = if (b.kind == BlockKind.PROSE) PhotoText.joinLines(b.text) else b.text

    fun photoToChat() {
        val sess = photo ?: return
        if (sess.blocks.isEmpty()) return
        val ko = sess.blocks.joinToString("\n\n") { blockKo(it) }
        val ja = sess.blocks.joinToString("\n") { blockJa(it) }
        val thumb = photoBitmap?.let { PhotoText.thumbnail(it) }
        upsert(Msg(nextId++, Side.PHOTO, "사진", ja = ja, ko = ko, state = MsgState.DONE, thumb = thumb))
        closePhoto()
        toast = "사진 번역을 대화에 넣었습니다"
    }

    fun shutdown() {
        listener.destroy()
        voice.shutdown()
        router.clearComm()
    }
}
