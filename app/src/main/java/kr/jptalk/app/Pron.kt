package kr.jptalk.app

import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * 일본어 문장 → 한글 발음.
 * 1) 형태소 분석기(Kuromoji)로 실제 발음(가타카나)을 뽑는다. 조사 は→ワ, を→オ, 장음 ー 처리가 여기서 된다.
 * 2) 단어 단위로 묶어 띄어 쓴다.
 * 3) 가타카나를 한글로 바꾼다 (장음 '-', 촉음·ん 받침 규칙).
 */
object Pron {
    @Volatile
    private var tokenizer: Tokenizer? = null

    fun init() {
        if (tokenizer == null) synchronized(this) {
            if (tokenizer == null) tokenizer = Tokenizer()
        }
    }

    fun hangul(ja: String): String {
        init()
        val tokens = tokenizer!!.tokenize(ja)
        val chunks = mutableListOf<StringBuilder>()
        var prevPos = ""
        for (t in tokens) {
            val pos1 = t.partOfSpeechLevel1 ?: ""
            val pos2 = t.partOfSpeechLevel2 ?: ""
            var kana = t.pronunciation
            if (kana.isNullOrBlank() || kana == "*") kana = toKatakana(t.surface)
            val attach = chunks.isNotEmpty() && (
                pos1 == "助詞" || pos1 == "助動詞" || pos1 == "記号" ||
                    pos2 == "接尾" || pos2 == "非自立" || prevPos == "接頭詞"
                )
            if (attach) chunks.last().append(kana) else chunks.add(StringBuilder(kana))
            prevPos = pos1
        }
        return chunks.joinToString(" ") { convert(it.toString()) }
            .replace(Regex("\\s+([.,?!])"), "$1")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun toKatakana(s: String): String {
        val sb = StringBuilder()
        for (c in s) sb.append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
        return sb.toString()
    }

    private val PUNCT = mapOf('。' to ".", '、' to ",", '？' to "?", '！' to "!", '・' to " ", '「' to "", '」' to "", '　' to " ")

    private val TWO = mapOf(
        "キャ" to "캬", "キュ" to "큐", "キョ" to "쿄", "ギャ" to "갸", "ギュ" to "규", "ギョ" to "교",
        "シャ" to "샤", "シュ" to "슈", "ショ" to "쇼", "ジャ" to "자", "ジュ" to "주", "ジョ" to "조",
        "チャ" to "차", "チュ" to "추", "チョ" to "초", "ヂャ" to "자", "ヂュ" to "주", "ヂョ" to "조",
        "ニャ" to "냐", "ニュ" to "뉴", "ニョ" to "뇨", "ヒャ" to "햐", "ヒュ" to "휴", "ヒョ" to "효",
        "ビャ" to "뱌", "ビュ" to "뷰", "ビョ" to "뵤", "ピャ" to "퍄", "ピュ" to "퓨", "ピョ" to "표",
        "ミャ" to "먀", "ミュ" to "뮤", "ミョ" to "묘", "リャ" to "랴", "リュ" to "류", "リョ" to "료",
        "ティ" to "티", "ディ" to "디", "トゥ" to "투", "ドゥ" to "두", "テュ" to "튜", "デュ" to "듀",
        "ファ" to "화", "フィ" to "휘", "フェ" to "페", "フォ" to "포", "フュ" to "휴",
        "ウィ" to "위", "ウェ" to "웨", "ウォ" to "워", "シェ" to "셰", "ジェ" to "제", "チェ" to "체",
        "ツァ" to "차", "ヴァ" to "바", "ヴィ" to "비", "ヴェ" to "베", "ヴォ" to "보"
    )

    private val ONE = mapOf(
        'ア' to "아", 'イ' to "이", 'ウ' to "우", 'エ' to "에", 'オ' to "오",
        'カ' to "카", 'キ' to "키", 'ク' to "쿠", 'ケ' to "케", 'コ' to "코",
        'ガ' to "가", 'ギ' to "기", 'グ' to "구", 'ゲ' to "게", 'ゴ' to "고",
        'サ' to "사", 'シ' to "시", 'ス' to "스", 'セ' to "세", 'ソ' to "소",
        'ザ' to "자", 'ジ' to "지", 'ズ' to "즈", 'ゼ' to "제", 'ゾ' to "조",
        'タ' to "타", 'チ' to "치", 'ツ' to "츠", 'テ' to "테", 'ト' to "토",
        'ダ' to "다", 'ヂ' to "지", 'ヅ' to "즈", 'デ' to "데", 'ド' to "도",
        'ナ' to "나", 'ニ' to "니", 'ヌ' to "누", 'ネ' to "네", 'ノ' to "노",
        'ハ' to "하", 'ヒ' to "히", 'フ' to "후", 'ヘ' to "헤", 'ホ' to "호",
        'バ' to "바", 'ビ' to "비", 'ブ' to "부", 'ベ' to "베", 'ボ' to "보",
        'パ' to "파", 'ピ' to "피", 'プ' to "푸", 'ペ' to "페", 'ポ' to "포",
        'マ' to "마", 'ミ' to "미", 'ム' to "무", 'メ' to "메", 'モ' to "모",
        'ヤ' to "야", 'ユ' to "유", 'ヨ' to "요",
        'ラ' to "라", 'リ' to "리", 'ル' to "루", 'レ' to "레", 'ロ' to "로",
        'ワ' to "와", 'ヲ' to "오", 'ヴ' to "부",
        'ァ' to "아", 'ィ' to "이", 'ゥ' to "우", 'ェ' to "에", 'ォ' to "오",
        'ャ' to "야", 'ュ' to "유", 'ョ' to "요", 'ヮ' to "와"
    )

    private const val K_ROW = "カキクケコガギグゲゴ"
    private const val P_ROW = "パピプペポバビブベボマミムメモ"

    private const val J_K = 1   // ㄱ
    private const val J_N = 4   // ㄴ
    private const val J_M = 16  // ㅁ
    private const val J_P = 17  // ㅂ
    private const val J_S = 19  // ㅅ
    private const val J_NG = 21 // ㅇ

    private fun addJong(sb: StringBuilder, jong: Int): Boolean {
        if (sb.isEmpty()) return false
        val c = sb[sb.length - 1]
        if (c in '가'..'힣' && (c - '가') % 28 == 0) {
            sb.setCharAt(sb.length - 1, c + jong)
            return true
        }
        return false
    }

    /** 다음 글자의 자음 종류: 'K' / 'P' / 'O'(그 밖의 가나) / 'E'(끝 또는 가나 아님) */
    private fun nextClass(s: String, i: Int): Char {
        var j = i + 1
        while (j < s.length && (s[j] == 'ー')) j++
        if (j >= s.length) return 'E'
        val c = s[j]
        return when {
            K_ROW.indexOf(c) >= 0 -> 'K'
            P_ROW.indexOf(c) >= 0 -> 'P'
            c in 'ァ'..'ヺ' -> 'O'
            else -> 'E'
        }
    }

    fun convert(kana: String): String {
        val s = toKatakana(kana)
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == 'ー' -> { sb.append('-'); i++ }
                c == 'ッ' -> {
                    val jong = when (nextClass(s, i)) { 'K' -> J_K; 'P' -> J_P; else -> J_S }
                    addJong(sb, jong)
                    i++
                }
                c == 'ン' -> {
                    val cls = nextClass(s, i)
                    val jong = when (cls) {
                        'K' -> J_NG
                        'P' -> J_M
                        'E' -> if (sb.isNotEmpty() && sb[sb.length - 1] == '세') J_NG else J_N
                        else -> J_N
                    }
                    if (!addJong(sb, jong)) sb.append("응")
                    i++
                }
                i + 1 < s.length && TWO.containsKey(s.substring(i, i + 2)) -> {
                    sb.append(TWO[s.substring(i, i + 2)]); i += 2
                }
                ONE.containsKey(c) -> { sb.append(ONE[c]); i++ }
                PUNCT.containsKey(c) -> { sb.append(PUNCT[c]); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
