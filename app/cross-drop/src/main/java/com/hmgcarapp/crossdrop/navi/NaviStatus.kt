package com.hmgcarapp.crossdrop.navi

import com.hmgcarapp.core.connection.ConnectionPolicy

/** 내비 요청 진행 상태(화면·음성에 그대로 표시: 성공한 척 금지). */
sealed interface NaviStatus {
    data object Idle : NaviStatus
    data class Pending(val what: String, val id: Long = 0L) : NaviStatus
    data class Success(val what: String, val id: Long = 0L) : NaviStatus
    /** [code] = SDK 오류 코드 이름(진단용; 화면 문구에는 쓰지 않는다). */
    data class Failed(val reason: String, val id: Long = 0L, val code: String? = null) : NaviStatus
}

/** 내비 이벤트 -> 상태 전이(순수). 요청이 Pending 일 때만 상태를 바꾼다. */
object NaviReducer {
    const val IGNORED = 0L

    fun onSuccess(cur: NaviStatus, msg: String): NaviStatus = if (cur is NaviStatus.Pending) NaviStatus.Success(msg, cur.id) else cur
    fun onError(cur: NaviStatus, codeName: String): NaviStatus =
        if (cur is NaviStatus.Pending) NaviStatus.Failed(NaviText.errorMessage(codeName), cur.id, codeName) else cur
    fun onTimeout(cur: NaviStatus, id: Long? = null): NaviStatus =
        if (cur is NaviStatus.Pending && (id == null || cur.id == id)) NaviStatus.Failed(NaviText.NO_RESPONSE, cur.id, "TIMEOUT") else cur
    fun failUnlessPending(cur: NaviStatus, msg: String, id: Long, code: String? = null): NaviStatus =
        if (cur is NaviStatus.Pending) cur else NaviStatus.Failed(msg, id, code)
    fun settle(cur: NaviStatus): NaviStatus = if (cur is NaviStatus.Pending) cur else NaviStatus.Idle
    fun canStart(cur: NaviStatus): Boolean = cur !is NaviStatus.Pending

    fun resultFor(cur: NaviStatus, id: Long): NaviStatus? = when {
        id == IGNORED -> null
        cur is NaviStatus.Success && cur.id == id -> cur
        cur is NaviStatus.Failed && cur.id == id -> cur
        else -> null
    }

    class Sequencer {
        private var last = 0L
        @Synchronized fun next(): Long = ++last
    }
}

object NaviText {
    const val NOT_CONNECTED = "요청에 실패했어요"
    /** 무응답은 사용자 문구가 없다(빈 문자열 = 표시·발화 안 함). 확정 실패만 [REQUEST_FAILED]/[NOT_CONNECTED]. */
    const val NO_RESPONSE = ""
    const val REQUEST_FAILED = "요청에 실패했어요"
    const val ROUTE_STARTED = "경로 안내를 시작했어요"
    const val BUSY = "이전 요청을 처리 중이에요"
    const val PENDING = "경로 요청 중"

    /** SDK 오류 코드는 사용자 문구에 노출하지 않고, 원인과 무관하게 한 문장으로 알린다(코드는 Failed.code 로 로그에만). */
    fun errorMessage(codeName: String): String = REQUEST_FAILED

    /** 결과를 받지 못한 무응답(시간 초과·결과 없음)인지. 내비가 실제로 안내를 시작했을 수 있어 실패로 표시하지 않는다. */
    fun isNoResponse(result: NaviStatus?): Boolean = result == null || (result is NaviStatus.Failed && result.reason.isBlank())

    fun spoken(result: NaviStatus?): String = when (result) {
        is NaviStatus.Success -> result.what
        is NaviStatus.Failed -> result.reason
        else -> NO_RESPONSE
    }
}

object NaviConnectRules {
    const val STALL_MS = 10_000L
    const val TIMEOUTS_TO_DISCARD = 2
    val POLICY = ConnectionPolicy(
        backoffStartMs = 0L, backoffMaxMs = 0L, deathWaitsBackoff = false,
        readyTimeoutMs = STALL_MS, silenceCycles = TIMEOUTS_TO_DISCARD,
    )
}
