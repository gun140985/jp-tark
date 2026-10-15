package kr.jptalk.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max

enum class BlockKind { LIST, PROSE }

/** 한 문장(또는 한 줄)의 결과 */
data class Sentence(val ja: String, val pron: String, val ko: String)

/** 사진 속 글자 덩어리 하나 */
data class PBlock(
    val no: Int,
    val rect: Rect?,
    val text: String,            // 읽은 글자 (줄은 \n 으로 구분)
    val kind: BlockKind,         // 지금 보여주는 방식
    val autoKind: BlockKind,     // 자동 판단 결과
    val rows: List<Sentence>,    // 줄별 번역
    val proseKo: String,         // 문단 번역 (자연스럽게 이어진 한국어)
    val sentences: List<Sentence>, // 문단을 문장별로 나눈 원문·발음·번역
    val expanded: Boolean = false
)

class PhotoSession(val image: ImageBitmap) {
    val blocks = mutableStateListOf<PBlock>()
    var processing by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
}

object PhotoText {
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    /** 사진을 읽어 오면서 긴 변을 maxSide 이하로 줄이고, 찍은 방향(EXIF)대로 돌린다 */
    fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int = 2000): Bitmap {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("사진을 열 수 없습니다")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = cr.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("사진을 열 수 없습니다")

        val longSide = max(bmp.width, bmp.height)
        if (longSide > maxSide) {
            val k = maxSide.toFloat() / longSide
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }

        val orientation = runCatching {
            cr.openInputStream(uri).use { s -> s?.let { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val m = Matrix().apply { postRotate(deg) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    /** 사진 속 글자 덩어리(문단)와 줄을 읽는다 */
    suspend fun recognize(bmp: Bitmap): List<Pair<Rect?, List<String>>> {
        val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        return result.textBlocks
            .map { b -> b.boundingBox to b.lines.map { it.text.trim() }.filter { it.isNotEmpty() } }
            .filter { it.second.isNotEmpty() && it.second.joinToString("").any { c -> isJapaneseOrDigit(c) } }
    }

    private fun isJapaneseOrDigit(c: Char): Boolean =
        c in '぀'..'ヿ' || c in '一'..'鿿' || c in '０'..'９' || c.isDigit()

    private fun isCjk(c: Char) = c in '　'..'鿿' || c in '＀'..'￯'

    /** 목록형(메뉴·라벨)인지 문장형(안내문·설명서)인지 판단 */
    fun classify(lines: List<String>): BlockKind {
        val joined = lines.joinToString("")
        if (joined.length < 20) return BlockKind.LIST
        val avg = joined.length / lines.size.toFloat()
        val hasPeriod = joined.contains('。') || joined.contains('！') || joined.contains('？')
        // 줄 끝이 문장부호나 히라가나(조사·어미)면 문장이 다음 줄로 이어지는 글
        val flowing = lines.count { l ->
            val c = l.trim().lastOrNull()
            c != null && ("。、，．！？」』".indexOf(c) >= 0 || c in 'ぁ'..'ゖ')
        }
        return if ((hasPeriod && avg >= 8f) || (flowing >= lines.size * 0.5f && avg >= 10f)) BlockKind.PROSE else BlockKind.LIST
    }

    /** 사진에서 줄이 바뀐 곳을 지워 한 글로 이어 붙인다 (일본어는 띄어쓰기 없이) */
    fun joinLines(text: String): String {
        val one = text.lines().joinToString("") { it.trim() }
        val sb = StringBuilder()
        for (i in one.indices) {
            val c = one[i]
            if (c.isWhitespace() && i > 0 && i < one.length - 1 && isCjk(one[i - 1]) && isCjk(one[i + 1])) continue
            sb.append(c)
        }
        return sb.toString()
    }

    fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[。！？!?])")).map { it.trim() }.filter { it.isNotEmpty() }

    /** 번역 엔진이 남긴 어색한 부분 정리 */
    fun polishKo(s: String): String = s
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+([.,!?])"), "$1")
        .replace(Regex(",{2,}"), ",")
        .replace(Regex("\\.{2,}"), ".")
        .replace(Regex("(\\S+)( \\1)+"), "$1")
        .replace("円", "엔")
        .replace(Regex("(이에요|예요)\\."), "입니다.")
        .replace(Regex("해요\\."), "합니다.")
        .replace(Regex("\\.(?=[가-힣])"), ". ")
        .trim()

    private fun priceFix(s: String) = s.replace("円", "엔")

    /** 덩어리 하나를 줄별·문단 두 방식으로 모두 번역해 둔다 */
    suspend fun analyze(no: Int, rect: Rect?, text: String, tr: Translator, kind: BlockKind? = null): PBlock {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val auto = classify(lines)

        val rows = lines.map { l ->
            Sentence(l, Pron.hangul(l), priceFix(tr.jaToKo(l)).trim())
        }

        val joined = joinLines(text)
        val sents = splitSentences(joined)
        val sentResults = sents.map { s -> Sentence(s, Pron.hangul(s), polishKo(tr.jaToKo(s))) }
        val prose = if (joined.length < 500) polishKo(tr.jaToKo(joined))
        else polishKo(sentResults.joinToString(" ") { it.ko })

        return PBlock(no, rect, lines.joinToString("\n"), kind ?: auto, auto, rows, prose, sentResults)
    }

    /** 대화에 넣을 작은 미리보기 */
    fun thumbnail(bmp: Bitmap): ImageBitmap {
        val k = 320f / max(bmp.width, bmp.height)
        val t = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        return t.asImageBitmap()
    }
}
