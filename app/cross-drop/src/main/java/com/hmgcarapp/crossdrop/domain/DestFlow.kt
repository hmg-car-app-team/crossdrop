package com.hmgcarapp.crossdrop.domain

sealed interface Heard {
    data class Text(val text: String) : Heard
    data object Failure : Heard
    data object Timeout : Heard
}

enum class DestPhase { SPEAKING, LISTENING, REQUESTING, RESULT, DONE }

/** 사용자 확인을 기다리는 목적지 안내 제안. [voice]=true 면 음성 질문(+1회 청취), false 면 정차 중 터치 선택만. */
data class DestOffer(val itemId: String, val dest: Destination, val voice: Boolean) {
    val label: String get() = dest.name ?: "공유받은 목적지"
}

data class DestState(
    /** 기본 true(fail-closed): 화면 조작은 정차가 확인될 때만. */
    val driving: Boolean = true,
    val seq: Long = 0L,
    /** 진행 중인 흐름 번호(발화·청취·요청 중). null = 없음. */
    val inFlightId: Long? = null,
    /** 화면에 떠 있는 제안이 속한 흐름 번호(자동 소거 대조용). */
    val offerId: Long? = null,
    val offer: DestOffer? = null,
    val phase: DestPhase? = null,
    val accepted: Boolean = false,
    val resultLine: String? = null,
)

sealed interface DestEvent {
    data class DriveChanged(val driving: Boolean) : DestEvent
    /** 새 목적지 제안. 이미 다른 제안이 있으면 무시하고, 음성 없는 제안은 주행 중이면 무시한다. */
    data class Propose(val offer: DestOffer) : DestEvent
    data class Spoken(val id: Long) : DestEvent
    data class Heard(val id: Long, val heard: com.hmgcarapp.crossdrop.domain.Heard) : DestEvent
    /** [success] null = 응답 없음. [line] 은 사용자에게 보여 주고 읽어 주는 결과 한 줄. */
    data class NaviResult(val id: Long, val success: Boolean?, val line: String) : DestEvent
    data class ResultSpoken(val id: Long) : DestEvent
    data class Failed(val id: Long) : DestEvent
    data class Expired(val id: Long) : DestEvent
    data object TouchAccept : DestEvent
    data object TouchDismiss : DestEvent
    data object Cleared : DestEvent
}

sealed interface DestEffect {
    data object CancelVoice : DestEffect
    data class Speak(val id: Long, val text: String) : DestEffect
    data class Listen(val id: Long) : DestEffect
    data class StartNavi(val id: Long, val offer: DestOffer) : DestEffect
    data class SpeakResult(val id: Long, val text: String) : DestEffect
    data class ScheduleExpiry(val id: Long, val delayMs: Long) : DestEffect
    /** 항목을 '사용함'으로 표시(수락해 경로를 요청한 뒤). */
    data class MarkUsed(val itemId: String) : DestEffect
}

/**
 * 목적지 안내 흐름(순수, Android 의존 없음). 컨트롤러는 효과를 실행하고 결과를 이벤트로 되먹일 뿐 상태를 바꾸지 않는다.
 * 규칙: 경로(requestRoute)는 **사용자가 수락(음성 '응' 또는 정차 중 터치)한 뒤에만** 요청한다. 링크를 받았다는 사실만으로는 절대 요청하지 않는다.
 * 터치는 정차 중에만 받고, 주행이 시작되면 수락 대기 중이던 제안은 닫힌다(항목은 받은 목록에 남는다).
 */
object DestFlow {
    const val BANNER_TTL_MS = 60_000L
    private val NONE: List<DestEffect> = emptyList()

    fun question(o: DestOffer) = "${o.label}${Josa.eulReul(o.label)} 목적지로 안내할까요?"

    fun reduce(s: DestState, e: DestEvent): Pair<DestState, List<DestEffect>> = when (e) {
        is DestEvent.DriveChanged ->
            if (e.driving && s.offer != null && s.inFlightId == null && !s.accepted && s.phase == DestPhase.DONE) cleared(s).copy(driving = true) to listOf(DestEffect.CancelVoice)
            else s.copy(driving = e.driving) to NONE
        is DestEvent.Propose -> propose(s, e.offer)
        is DestEvent.Spoken ->
            if (s.inFlightId != e.id) s to NONE
            else s.copy(phase = DestPhase.LISTENING) to listOf(DestEffect.Listen(e.id))
        is DestEvent.Heard -> heard(s, e)
        is DestEvent.NaviResult ->
            if (s.inFlightId != e.id || s.offer == null) s to NONE
            else s.copy(phase = DestPhase.RESULT, resultLine = e.line) to listOf(DestEffect.SpeakResult(e.id, e.line))
        is DestEvent.ResultSpoken -> if (s.inFlightId == e.id) finish(s, e.id) else s to NONE
        is DestEvent.Failed -> if (s.inFlightId == e.id) finish(s, e.id) else s to NONE
        is DestEvent.Expired ->
            if (s.offerId == e.id && s.inFlightId == null) cleared(s) to NONE else s to NONE
        DestEvent.TouchAccept -> touchAccept(s)
        DestEvent.TouchDismiss ->
            if (s.driving || s.offer == null) s to NONE else cleared(s) to listOf(DestEffect.CancelVoice)
        DestEvent.Cleared -> cleared(s) to listOf(DestEffect.CancelVoice)
    }

    private fun cleared(s: DestState) = DestState(driving = s.driving, seq = s.seq)

    private fun propose(s: DestState, o: DestOffer): Pair<DestState, List<DestEffect>> {
        if (s.inFlightId != null || s.offer != null) return s to NONE
        val id = s.seq + 1
        return if (o.voice) {
            s.copy(seq = id, inFlightId = id, offerId = id, offer = o, phase = DestPhase.SPEAKING) to listOf(DestEffect.Speak(id, question(o)))
        } else {
            if (s.driving) return s to NONE
            s.copy(seq = id, offerId = id, offer = o, phase = DestPhase.DONE) to listOf(DestEffect.ScheduleExpiry(id, BANNER_TTL_MS))
        }
    }

    private fun heard(s: DestState, e: DestEvent.Heard): Pair<DestState, List<DestEffect>> {
        val o = s.offer
        if (s.inFlightId != e.id || o == null) return s to NONE
        return when (val h = e.heard) {
            Heard.Failure, Heard.Timeout -> finish(s, e.id)
            is Heard.Text -> when {
                VoiceIntent.isAccept(h.text) -> accept(s, e.id, o)
                VoiceIntent.isReject(h.text) -> cleared(s) to NONE
                else -> finish(s, e.id)   // 알아듣지 못함: 수락도 거절도 아님(정차 중이면 터치로 선택 가능)
            }
        }
    }

    private fun accept(s: DestState, id: Long, o: DestOffer): Pair<DestState, List<DestEffect>> =
        s.copy(phase = DestPhase.REQUESTING, accepted = true, inFlightId = id, offerId = id) to listOf(DestEffect.MarkUsed(o.itemId), DestEffect.StartNavi(id, o))

    private fun touchAccept(s: DestState): Pair<DestState, List<DestEffect>> {
        val o = s.offer
        if (s.driving || o == null || s.accepted) return s to NONE
        val id = s.seq + 1
        val (n, fx) = accept(s.copy(seq = id), id, o)
        return n to (listOf<DestEffect>(DestEffect.CancelVoice) + fx)
    }

    /** 흐름이 끝났을 때: 정차 중이면 터치 선택용으로 남기고(60초), 주행 중이면 터치를 받을 수 없으니 제안을 닫는다(항목은 받은 목록에 남는다). */
    private fun finish(s: DestState, id: Long): Pair<DestState, List<DestEffect>> =
        if (s.driving) cleared(s) to NONE
        else s.copy(inFlightId = null, phase = DestPhase.DONE) to listOf(DestEffect.ScheduleExpiry(id, BANNER_TTL_MS))
}

/** 목적지 이름 뒤 조사(을/를). 마지막 글자가 받침 있는 한글이면 '을', 받침 없는 한글이면 '를', 그 밖에는 '을(를)'. */
object Josa {
    fun eulReul(word: String): String {
        val c = word.trimEnd().lastOrNull() ?: return "을(를)"
        if (c !in '\uAC00'..'\uD7A3') return "을(를)"
        return if ((c.code - 0xAC00) % 28 != 0) "을" else "를"
    }
}
