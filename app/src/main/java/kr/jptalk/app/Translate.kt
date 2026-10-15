package kr.jptalk.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** 무료 온디바이스 번역 (구글 ML Kit). 처음 한 번 번역 데이터를 내려받으면 인터넷 없이도 동작 */
class Translator {
    private val jaKo = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
    )
    private val koJa = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.KOREAN)
            .setTargetLanguage(TranslateLanguage.JAPANESE)
            .build()
    )
    private val lock = Mutex()

    var ready by mutableStateOf(false)
        private set
    var downloading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    suspend fun ensure() {
        if (ready) return
        lock.withLock {
            if (ready) return
            downloading = true
            error = null
            try {
                val cond = DownloadConditions.Builder().build()
                jaKo.downloadModelIfNeeded(cond).await()
                koJa.downloadModelIfNeeded(cond).await()
                ready = true
            } catch (e: Exception) {
                error = "번역 데이터를 받지 못했습니다. 처음 한 번은 인터넷(와이파이 권장)이 필요합니다."
                throw e
            } finally {
                downloading = false
            }
        }
    }

    suspend fun jaToKo(text: String): String {
        ensure()
        return jaKo.translate(text).await()
    }

    suspend fun koToJa(text: String): String {
        ensure()
        return koJa.translate(text).await()
    }
}
