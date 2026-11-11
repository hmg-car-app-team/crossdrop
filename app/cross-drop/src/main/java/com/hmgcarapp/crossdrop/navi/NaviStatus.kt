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
    const val NOT_CONNECTED = "내비 서비스에 연결되지 않았어요"
    const val NO_RESPONSE = "내비에서 안내 시작을 확인하지 못했어요. 내비 화면에서 경로를 확인해 주세요"
    const val REQUEST_FAILED = "요청에 실패했어요"
    const val ROUTE_STARTED = "경로 안내를 시작했어요"
    const val BUSY = "이전 요청을 처리 중이에요"
    const val PENDING = "경로 요청 중"

    /** SDK 오류 코드 이름 -> 한국어. 원시 코드는 사용자 문구에 노출하지 않는다. */
    fun errorMessage(codeName: String): String = when (codeName) {
        "CAUSE_SEARCH_ROUTE" -> "경로를 찾지 못했어요"
        "CAUSE_SEARCH_ROUTE_OVER_DISTANCE" -> "경로가 너무 멀어요"
        "CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE" -> "목적지가 너무 가까워요"
        "CAUSE_LOW_BATTERY" -> "배터리가 부족해 경로를 만들 수 없어요"
        else -> "경로 요청에 실패했어요"
    }

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
