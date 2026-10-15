package kr.jptalk.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

enum class Out { AUTO, SPEAKER, EARPHONE }

/** 마이크·스피커 경로 관리 */
class AudioRouter(ctx: Context, private val onChange: (String, String?) -> Unit) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var lastHeadset: String? = null

    private val headsetTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        26 // TYPE_BLE_HEADSET
    )

    init {
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh(true)
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh(true)
        }, Handler(Looper.getMainLooper()))
    }

    fun headset(): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in headsetTypes }

    fun headsetName(): String? = headset()?.let {
        val n = it.productName?.toString()?.trim()
        if (n.isNullOrEmpty()) "이어폰" else n
    }

    private fun btMicAvailable(): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
        }

    /** 화면 위 상태 문구를 다시 계산. 이어폰 연결이 바뀌었으면 안내 문구도 함께 */
    fun refresh(notify: Boolean = false) {
        val hs = headsetName()
        val change: String? = if (notify && hs != lastHeadset) {
            if (hs != null) "$hs 연결됨" else "이어폰 연결 해제됨: 소리는 휴대폰 스피커로 나옵니다"
        } else null
        lastHeadset = hs
        onChange(hs ?: "", change)
    }

    private val mediaAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val commAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 소리를 낼 경로를 정하고, TTS에 쓸 오디오 속성을 돌려준다 */
    fun prepareOutput(out: Out): AudioAttributes {
        // 이어폰이 연결된 상태에서 '스피커'를 고르면 통화 경로로 강제로 스피커에 보낸다
        if (out == Out.SPEAKER && headset() != null) {
            forceSpeaker()
            return commAttrs
        }
        // 자동/이어폰: 일반 미디어 소리 → 이어폰이 있으면 이어폰, 없으면 스피커
        clearComm()
        return mediaAttrs
    }

    private fun forceSpeaker() {
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } catch (_: Exception) {
        }
    }

    fun clearComm() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    /** 듣기 전에 마이크 선택. 기본은 휴대폰 본체 마이크 */
    fun prepareMic(useBluetooth: Boolean): Boolean {
        if (!useBluetooth || !btMicAvailable()) {
            clearComm()
            return false
        }
        return try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                }
                dev != null && am.setCommunicationDevice(dev)
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}

/** 음성 출력 (안드로이드 내장 TTS) */
class Voice(private val ctx: Context, private val router: AudioRouter, private val onStatus: (Boolean) -> Unit) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            onStatus(hasJapanese())
        } else {
            onStatus(false)
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = router.clearComm()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = router.clearComm()
        })
    }

    fun hasJapanese(): Boolean = try {
        tts.isLanguageAvailable(Locale.JAPAN) >= TextToSpeech.LANG_AVAILABLE
    } catch (_: Exception) {
        false
    }

    fun installVoices() {
        try {
            ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    fun speak(text: String, locale: Locale, rate: Float, out: Out) {
        if (!ready || text.isBlank()) return
        tts.stop()
        tts.language = locale
        tts.setSpeechRate(rate)
        tts.setAudioAttributes(router.prepareOutput(out))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    fun stop() {
        try { tts.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        try { tts.shutdown() } catch (_: Exception) {}
    }
}

/** 음성 인식 (안드로이드 내장 SpeechRecognizer) */
class Listener(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onEnd: () -> Unit
) {
    private var sr: SpeechRecognizer? = null

    fun start(lang: String) {
        destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError("이 폰에서 음성인식을 쓸 수 없습니다. 구글 앱을 설치하거나 업데이트해 주세요.")
            onEnd()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    onError(errorText(error))
                    onEnd()
                }
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (t.isNotEmpty()) onFinal(t) else onError("말소리를 알아듣지 못했습니다. 다시 눌러 주세요.")
                    onEnd()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (t.isNotBlank()) onPartial(t)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(intent)
        }
    }

    /** 듣기를 마치고 지금까지 들은 내용으로 결과를 받는다 */
    fun finish() {
        try { sr?.stopListening() } catch (_: Exception) {}
    }

    fun destroy() {
        try { sr?.destroy() } catch (_: Exception) {}
        sr = null
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "말소리를 못 들었습니다. 휴대폰을 말하는 사람 쪽으로 가까이 대고 다시 눌러 주세요."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "음성인식에 인터넷이 필요합니다. 연결을 확인해 주세요."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "마이크 권한이 없습니다. 휴대폰 설정 → 앱 → 한일 통역 → 권한에서 마이크를 허용해 주세요."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성인식이 아직 바쁩니다. 잠시 후 다시 눌러 주세요."
        SpeechRecognizer.ERROR_AUDIO -> "마이크 소리를 받지 못했습니다. 다른 앱이 마이크를 쓰고 있는지 확인해 주세요."
        12, 13 -> "이 언어의 음성인식이 설치되어 있지 않습니다. 구글 앱 → 설정 → 음성에서 일본어·한국어를 추가해 주세요."
        else -> "음성인식 오류가 났습니다(코드 $code). 다시 눌러 주세요."
    }
}
