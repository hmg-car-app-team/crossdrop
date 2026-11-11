package com.hmgcarapp.crossdrop.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.crossdrop.domain.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 목적지 안내가 쓰는 내비 포트(실제 구현은 [NaviController]; 테스트는 가짜). */
interface NaviPort {
    /** 요청을 보낸 시점에 번호를 돌려준다(결과는 [awaitResult]). 처리 중이면 [NaviReducer.IGNORED]. */
    suspend fun request(dest: Destination): Long
    suspend fun awaitResult(id: Long, timeoutMs: Long = 8000L): NaviStatus?
    fun clearFinished()
    fun release() {}
}

/**
 * NaviHelper 3.0.5 래퍼([ManagedConnection] 위). 사용 API 는 `requestRoute` 하나뿐이다(경로를 **사용자가 수락한 뒤에만** 호출 — 호출부 책임).
 * 결과는 이벤트 콜백으로만 오므로 6초 안에 확인 이벤트가 없으면 '응답 없음'으로 기록한다. 요청 처리 중에는 새 요청을 받지 않는다.
 * 로그에는 좌표·장소 이름을 남기지 않는다. 에뮬레이터에서는 내비 엔진이 경로를 만들지 못해 이벤트(오류)까지만 확인된다.
 */
class NaviController(
    private val context: Context,
    private val scope: CoroutineScope,
) : NaviPort {
    private val _status = MutableStateFlow<NaviStatus>(NaviStatus.Idle)
    val status: StateFlow<NaviStatus> = _status

    @Volatile private var timeoutJob: Job? = null
    @Volatile private var released = false
    private val requests = NaviReducer.Sequencer()

    private fun note(s: String) { HmgLog.i("Navi", s) }

    private val listener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() { note("onRoutingCompleted"); conn.onResponseNow(); transition { NaviReducer.onSuccess(it, NaviText.ROUTE_STARTED) } }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) { note("onWaypointChanged") }
        override fun onRouteOptionChanged(routeOption: RouteOption) { note("onRouteOptionChanged") }
        override fun onReRouteEnded() { note("onReRouteEnded") }
        override fun onDestinationChanged() { note("onDestinationChanged") }
        override fun onError(errorCode: NaviErrorCode) { note("onError ${errorCode.name}"); conn.onResponseNow(); transition { NaviReducer.onError(it, errorCode.name) } }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { note("onDestinationArrived") }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) { note("onRouteStateInfo") }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { note("onDestinationInfo") }
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) { note("onBookmarkInfo") }
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) { note("onRecentDestinationInfo") }
    }

    private fun transition(f: (NaviStatus) -> NaviStatus) {
        var leftPending = false
        _status.update { before -> val after = f(before); leftPending = after !== before && after !is NaviStatus.Pending; after }
        if (leftPending) timeoutJob?.cancel()
    }

    private fun fail(msg: String, id: Long, code: String) { timeoutJob?.cancel(); _status.value = NaviStatus.Failed(msg, id, code) }

    private val conn: ManagedConnection<NaviHelper> = ManagedConnection(
        tag = "Navi",
        create = { NaviHelper(context.applicationContext) },
        open = { h, gen ->
            h.initialize(
                {
                    conn.ifCurrent(gen, stale = { note("늦은 연결 콜백 무시") }) {
                        try { h.addListener(listener); conn.markReady(gen); note("NaviHelper 연결됨") }
                        catch (t: Throwable) { conn.fail(gen); note("리스너 등록 실패: ${t.javaClass.simpleName}") }
                    }
                    Unit
                },
                { t -> conn.ifCurrent(gen) { conn.fail(gen); note("NaviHelper 연결 실패: ${t.javaClass.simpleName}") }; Unit },
            )
        },
        dispose = { h ->
            runCatching { h.removeListener(listener) }
            runCatching { h.release() }
        },
        isConnectionFailure = { true },
        clock = android.os.SystemClock::elapsedRealtime,
        policy = NaviConnectRules.POLICY,
        onCreateFailed = { t -> note("NaviHelper 생성 실패: ${t.javaClass.simpleName}") },
        log = { note(it) },
    )

    fun connect() { if (!released) conn.connect() }
    private fun discardAndReconnect() { conn.invalidate(); connect() }
    override fun release() { released = true; conn.shutdown() }
    override fun clearFinished() { _status.update { NaviReducer.settle(it) } }

    private suspend fun ensureConnected(waitMs: Long = 2000L): Boolean {
        if (released) return false
        if (conn.readyConn() != null) return true
        connect()
        var waited = 0L
        while (conn.current() != null && !conn.isReady() && !conn.isLost() && waited < waitMs) { delay(100); waited += 100 }
        return conn.readyConn() != null
    }

    override suspend fun request(dest: Destination): Long {
        if (!NaviReducer.canStart(_status.value)) { note("이전 요청 처리 중 - 새 요청 무시"); return NaviReducer.IGNORED }
        val id = requests.next()
        if (!ensureConnected()) {
            note("미연결: 연결 대기 중/서비스 없음")
            if (!NaviReducer.canStart(_status.value)) return NaviReducer.IGNORED
            _status.update { NaviReducer.failUnlessPending(it, NaviText.NOT_CONNECTED, id, "NOT_CONNECTED") }
            return id
        }
        if (!NaviReducer.canStart(_status.value)) { note("이전 요청 처리 중 - 새 요청 무시"); return NaviReducer.IGNORED }
        val h = conn.readyConn()?.value ?: run { fail(NaviText.NOT_CONNECTED, id, "NOT_CONNECTED"); return id }
        try {
            note("요청 REQUEST_ROUTE")
            _status.update { NaviStatus.Pending(NaviText.PENDING, id) }
            val name = dest.name ?: "목적지"
            h.requestRoute(RouteInfo(
                latitude = dest.lat, longitude = dest.lon, poiName = name, poiId = "", poiSubId = "0",
                address = name, routeOption = RouteOption.RECOMMENDED,
            ))
            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(6000)
                if ((_status.value as? NaviStatus.Pending)?.id == id) {
                    note("6초 내 응답 이벤트 없음"); transition { NaviReducer.onTimeout(it, id) }
                    if (conn.onSilentCycle()) { note("연속 타임아웃 - helper 재생성"); discardAndReconnect() }
                }
            }
        } catch (t: Throwable) {
            note("요청 예외: ${t.javaClass.simpleName}"); discardAndReconnect(); fail(NaviText.REQUEST_FAILED, id, "EXCEPTION_${t.javaClass.simpleName}")
        }
        return id
    }

    override suspend fun awaitResult(id: Long, timeoutMs: Long): NaviStatus? {
        if (id == NaviReducer.IGNORED) return NaviStatus.Failed(NaviText.BUSY, code = "BUSY")
        return withTimeoutOrNull(timeoutMs) { _status.first { NaviReducer.resultFor(it, id) != null } }
    }
}
