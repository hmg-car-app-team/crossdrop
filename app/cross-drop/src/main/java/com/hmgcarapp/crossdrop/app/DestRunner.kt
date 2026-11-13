package com.hmgcarapp.crossdrop.app

import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.voice.ListenResult
import com.hmgcarapp.core.voice.Listener
import com.hmgcarapp.core.voice.Speaker
import com.hmgcarapp.crossdrop.domain.DestEffect
import com.hmgcarapp.crossdrop.domain.DestEvent
import com.hmgcarapp.crossdrop.domain.Heard
import com.hmgcarapp.crossdrop.navi.NaviPort
import com.hmgcarapp.crossdrop.navi.NaviStatus
import com.hmgcarapp.crossdrop.navi.NaviText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [com.hmgcarapp.crossdrop.domain.DestFlow] 의 효과를 SDK(TTS·STT·내비)로 실행하고 결과를 이벤트로 되먹인다. 상태 전이는 하지 않는다.
 * 청취는 질문을 읽은 직후 **1회**([listenOnce], 8초)뿐이며 상시 청취는 없다. 모든 호출은 메인 스레드.
 */
class DestRunner(
    private val scope: CoroutineScope,
    private val speaker: Speaker,
    private val listener: Listener,
    private val navi: NaviPort,
    private val debuggable: Boolean,
    private val onMarkUsed: (String) -> Unit,
    private val dispatch: (DestEvent) -> Unit,
) {
    private var flowJob: Job? = null
    private var expiryJob: Job? = null

    fun run(fx: DestEffect) {
        when (fx) {
            DestEffect.CancelVoice -> cancelVoice()
            is DestEffect.Speak -> flow(fx.id) { speaker.speakAndAwait(fx.text); dispatch(DestEvent.Spoken(fx.id)) }
            is DestEffect.Listen -> flow(fx.id) {
                val heard = when (val r = listener.listenOnce(LISTEN_MS)) {
                    is ListenResult.Text -> {
                        if (debuggable) HmgLog.i("Voice", "STT 수신 길이=${r.text.length}")   // 내용은 남기지 않는다
                        Heard.Text(r.text)
                    }
                    is ListenResult.Failure -> { HmgLog.w("Voice", "STT 실패: ${r.reason}"); Heard.Failure }
                    ListenResult.Timeout -> Heard.Timeout
                }
                dispatch(DestEvent.Heard(fx.id, heard))
            }
            is DestEffect.StartNavi -> flow(fx.id) {
                val reqId = navi.request(fx.offer.dest)
                val res: NaviStatus? = navi.awaitResult(reqId)
                dispatch(DestEvent.NaviResult(fx.id, if (res == null) null else res is NaviStatus.Success, NaviText.spoken(res)))
            }
            is DestEffect.SpeakResult -> flow(fx.id) { if (fx.text.isNotBlank()) speaker.speakAndAwait(fx.text); dispatch(DestEvent.ResultSpoken(fx.id)) }
            is DestEffect.ScheduleExpiry -> { expiryJob?.cancel(); expiryJob = scope.launch { delay(fx.delayMs); dispatch(DestEvent.Expired(fx.id)) } }
            is DestEffect.MarkUsed -> onMarkUsed(fx.itemId)
        }
    }

    fun cancelVoice() { flowJob?.cancel(); flowJob = null; expiryJob?.cancel(); expiryJob = null; speaker.stop() }

    /** 진행 중인 발화·청취·요청 코루틴만 멈춘다(시연에서 음성 결과를 주입하기 전에 사용). */
    fun cancelFlow() { flowJob?.cancel(); flowJob = null; speaker.stop() }

    private fun flow(id: Long, block: suspend () -> Unit) {
        val j = scope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (e: CancellationException) { speaker.stop(); throw e }
            catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                HmgLog.w("Dest", "안내 흐름 예외: ${e.javaClass.simpleName}")
                dispatch(DestEvent.Failed(id))
            }
        }
        flowJob = j; j.start()
    }

    companion object { const val LISTEN_MS = 8000L }
}
