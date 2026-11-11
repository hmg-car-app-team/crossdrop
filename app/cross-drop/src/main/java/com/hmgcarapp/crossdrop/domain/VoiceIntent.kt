package com.hmgcarapp.crossdrop.domain

/**
 * 음성 응답 해석(순수). 부분 문자열 매칭 금지: 정규화(소문자·문장부호 제거·공백 정리) 후 거절 단어를 먼저 보고,
 * **전체가 수락 표현(들)의 연속으로만 이루어졌는지** 확인한다('예약'·'응급'·'네비' 같은 문장 속 일부는 수락이 아니다).
 * 머뭇거림('어')과 의문형('네?')은 수락이 아니다. 공백 제외 14자 초과는 수락하지 않는다.
 */
object VoiceIntent {
    const val MAX_COMPACT = 14

    private val acceptPhrases = listOf(
        "응", "네", "넵", "예", "그래", "그래요", "좋아", "좋아요", "좋습니다", "오케이", "ok", "okay", "yes",
        "알겠어", "알겠어요", "알겠습니다", "안내해줘", "안내해주세요", "안내해", "안내시작", "출발", "출발해줘", "출발해", "출발해요",
        "시작해줘", "시작해", "그렇게해줘", "그렇게해", "해줘", "해주세요", "부탁해", "부탁해요",
    )
    private val rejectWords = setOf(
        "아니", "아니요", "아뇨", "아냐", "싫어", "싫어요", "취소", "괜찮아", "괜찮아요", "괜찮습니다", "됐어", "됐어요",
        "말고", "나중에", "그만", "안돼", "안돼요",
    )
    private val rejectCompact = listOf("안해", "하지마", "안할래")

    fun normalize(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("").trim().replace(Regex("\\s+"), " ")

    fun isReject(text: String): Boolean {
        val n = normalize(text)
        if (n.isEmpty()) return false
        if (n.split(' ').any { it in rejectWords }) return true
        val compact = n.replace(" ", "")
        return rejectCompact.any { compact.contains(it) }
    }

    fun isAccept(text: String): Boolean {
        val n = normalize(text)
        if (n.isEmpty() || isReject(text)) return false
        if (text.contains('?') || text.contains('？')) return false
        val compact = n.replace(" ", "")
        if (compact.length > MAX_COMPACT) return false
        val ok = BooleanArray(compact.length + 1).also { it[0] = true }
        for (i in 1..compact.length) {
            ok[i] = acceptPhrases.any { ph -> i >= ph.length && ok[i - ph.length] && compact.regionMatches(i - ph.length, ph, 0, ph.length) }
        }
        return ok[compact.length]
    }
}
