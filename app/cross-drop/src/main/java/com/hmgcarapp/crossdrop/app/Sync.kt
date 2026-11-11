package com.hmgcarapp.crossdrop.app

import com.hmgcarapp.crossdrop.domain.FileFormat
import com.hmgcarapp.crossdrop.domain.FileRules
import com.hmgcarapp.crossdrop.domain.FileVerdict
import com.hmgcarapp.crossdrop.domain.InboxItem
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.domain.LinkParser
import com.hmgcarapp.crossdrop.domain.LinkResult
import com.hmgcarapp.crossdrop.net.PairGrant
import com.hmgcarapp.crossdrop.net.RelayApi
import com.hmgcarapp.crossdrop.net.RelayException
import com.hmgcarapp.crossdrop.net.RemoteInbox
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class SyncStatus(val label: String) {
    NO_CONSENT("연결 동의 전: 서버와 통신하지 않아요"),
    NO_SERVER("서버 주소 미설정: 서버와 통신하지 않아요"),
    DEMO("시연 중: 서버와 통신하지 않아요"),
    NO_SESSION("휴대폰 연결 전"),
    ACTIVE("연결됨: 새 항목을 확인하는 중"),
    ERROR("서버에 연결하지 못했어요"),
}

/** 서버 통신 허용 판정(순수). 이 중 하나라도 어긋나면 서버와 통신하지 않는다. */
object SyncGate {
    fun status(consent: Boolean, serverUrl: String?, demo: Boolean, hasSession: Boolean, lastFailed: Boolean): SyncStatus = when {
        demo -> SyncStatus.DEMO   // 시연은 동의 여부와 무관하게 서버로 가지 않는다
        !consent -> SyncStatus.NO_CONSENT
        serverUrl == null -> SyncStatus.NO_SERVER
        !hasSession -> SyncStatus.NO_SESSION
        lastFailed -> SyncStatus.ERROR
        else -> SyncStatus.ACTIVE
    }

    fun mayPoll(s: SyncStatus) = s == SyncStatus.ACTIVE || s == SyncStatus.ERROR
}

/**
 * 서버 통신의 유일한 관문(코루틴·SDK 없이 테스트 가능). 동의·서버 주소·비시연이 맞아야 API 를 내주고,
 * 폴링은 5초 간격(실패 뒤 30초)·동시 1건으로 제한한다. 호출은 모두 메인 스레드(컨트롤러)에서 한다.
 */
class SyncPump(
    private val api: RelayApi?,
    private val serverUrl: String?,
    private val everyMs: Long = EVERY_MS,
    private val errorEveryMs: Long = ERROR_EVERY_MS,
    private val longErrorEveryMs: Long = LONG_ERROR_EVERY_MS,
) {
    var failed: Boolean = false; private set
    /** 연속 실패 횟수: [BACKOFF_AFTER] 회 이상이면 재시도 간격을 [longErrorEveryMs] 로 늘린다. 성공하면 0. */
    var consecutiveFailures: Int = 0; private set
    private var inFlight = false
    private var lastAt: Long? = null

    fun status(consent: Boolean, demo: Boolean, hasSession: Boolean): SyncStatus = SyncGate.status(consent, serverUrl, demo, hasSession, failed)

    /** 새 페어링 세션을 만들 수 있으면 API, 아니면 null. */
    fun apiForPairing(consent: Boolean, demo: Boolean): RelayApi? = if (consent && !demo && serverUrl != null) api else null

    /** 세션·항목 삭제 요청용(동의 철회·연결 끊기·새 QR). 서버 주소만 있으면 시연 여부와 무관하게 허용(실제 세션이 서버에 남아 있을 수 있다). */
    fun apiForCleanup(): RelayApi? = if (serverUrl != null) api else null

    /** 지금 폴링해야 하면 API(전송 중 상태가 됨 -> [finish] 필수), 아니면 null. */
    fun prepare(nowMs: Long, consent: Boolean, demo: Boolean, hasSession: Boolean): RelayApi? {
        val a = api ?: return null
        if (inFlight || !SyncGate.mayPoll(status(consent, demo, hasSession))) return null
        lastAt?.let { if (nowMs - it < (if (!failed) everyMs else if (consecutiveFailures >= BACKOFF_AFTER) longErrorEveryMs else errorEveryMs)) return null }
        lastAt = nowMs; inFlight = true
        return a
    }

    fun finish(ok: Boolean) { failed = !ok; consecutiveFailures = if (ok) 0 else consecutiveFailures + 1; inFlight = false }

    companion object { const val EVERY_MS = 5_000L; const val ERROR_EVERY_MS = 30_000L
        const val BACKOFF_AFTER = 5
        const val LONG_ERROR_EVERY_MS = 60_000L }
}

data class ReceiveResult(
    val accepted: List<InboxItem>,
    val rejected: Int,
    /** 처리를 마쳐 다시 받지 않을 서버 항목 id(서버 사본은 호출부가 저장 뒤에 지운다). */
    val handled: Set<String>,
    /** 일시 오류로 다음 폴링에 다시 시도할 항목 id. */
    val retry: Set<String>,
    val sessionEnded: Boolean,
)

/** 폴링 결과를 반영해도 되는지: 폴링을 시작한 연결(토큰)이 아직 현재 연결이고 동의가 유지되고 있어야 한다(삭제·철회·재페어링과의 경합 방지). */
object PollGuard {
    fun stillCurrent(p: Persisted, token: String): Boolean = p.consent && p.pairing?.deviceToken == token
}

/**
 * 받은 항목을 로컬 상태에 반영하고 **디스크에 쓴 뒤에만** 서버 사본을 지운다. [persist] 가 예외를 던지면 서버 삭제는 하지 않는다
 * (프로세스가 중간에 죽으면 재시작 뒤 같은 항목을 다시 받을 수는 있어도 잃지는 않는다).
 */
suspend fun commitThenDelete(api: RelayApi, token: String, handled: Collection<String>, persist: () -> Boolean): Boolean {
    if (handled.isEmpty()) return true    // 새 항목이 없는 폴링은 저장(fsync)도 하지 않는다: 5초마다 메인 스레드에서 디스크를 쓰지 않게
    if (!persist()) return false      // 저장 실패·보류(WriteResult FAILED/HELD)면 서버 사본을 지우지 않는다
    for (id in handled) {
        try { api.deleteItem(token, id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }   // 취소는 삼키지 않는다
        catch (t: Throwable) { }                                          // 실패해도 서버가 24시간 뒤 지운다
    }
    return true
}

/** 폴링 401 처리 문구: 아직 휴대폰이 연결되지 않은(claim 안 된) 세션이면 '연결이 끊김' 이 아니라 QR 만료다. */
object SessionEndText {
    fun of(claimed: Boolean?): String = if (claimed == false) "QR 이 만료됐어요" else "휴대폰 연결이 끝났어요"
}

/** '새 QR 만들기' 전에 확인이 필요한가: 연결된 휴대폰이 아직 못 보낸/차량이 못 받은 항목이 서버 세션과 함께 사라질 수 있다. */
object NewQrPolicy {
    const val WARNING = "아직 받지 못한 항목은 사라질 수 있어요"
    fun needsConfirm(status: com.hmgcarapp.crossdrop.domain.PairStatus): Boolean = status == com.hmgcarapp.crossdrop.domain.PairStatus.CONNECTED
}

/** 도착 안내 문구(순수). banner = 상단 안내, message = 정차 중 토스트(해석할 수 없는 링크가 있을 때). */
data class ReceiptText(val banner: String, val message: String?) {
    companion object {
        fun of(items: List<InboxItem>): ReceiptText {
            val parts = ArrayList<String>()
            ItemKind.entries.filter { it != ItemKind.LINK }.forEach { k -> items.count { it.kind == k }.takeIf { it > 0 }?.let { parts += "${k.label} ${it}개" } }
            val ok = items.count { it.kind == ItemKind.LINK && it.dest != null }
            val bad = items.filter { it.kind == ItemKind.LINK && it.dest == null }
            if (ok > 0) parts += "목적지 ${ok}개"
            if (bad.isNotEmpty()) parts += "열 수 없는 링크 ${bad.size}개"
            return ReceiptText(parts.joinToString(", ") + "가 도착했어요", bad.firstOrNull()?.note)
        }
    }
}

/**
 * 서버 수신함 -> 받은 항목. 파일은 크기를 먼저 보고 받아서 **차량에서 다시 검증**(매직 바이트·종류 일치·활성 콘텐츠)한 뒤에만 저장한다.
 * 서버 사본 삭제는 여기서 하지 않는다: 호출부가 항목을 반영·저장한 뒤 [commitThenDelete] 로 지운다. 링크는 문자열만 해석하며 URL 자체는 저장하지 않는다.
 */
class Receiver(
    private val api: RelayApi,
    private val save: (FileFormat, ByteArray) -> String,
    private val newId: () -> String,
    /** 비정상 종료(취소 포함) 때 이미 저장한 파일을 지우는 함수(저장 이름 -> 삭제). */
    private val discard: (String) -> Unit = {},
) {
    /** [accepted] 는 호출부가 소유한 목록: 저장할 때마다 여기에 더해지므로, 이 함수가 정상 반환한 뒤 호출부가 취소돼도 어떤 파일이 생겼는지 호출부가 안다. */
    suspend fun receive(token: String, inbox: RemoteInbox, known: Set<String>, nowWallMs: Long, room: Int, accepted: MutableList<InboxItem> = ArrayList()): ReceiveResult {
        val handled = HashSet<String>(); val retry = HashSet<String>()
        var rejected = 0
        var done = false
        try {
        for (r in inbox.items) {
            if (r.id in known) continue
            if (accepted.size >= room) break      // 자리가 없으면 서버에 둔다(24시간 뒤 서버가 지움)
            try {
                if (r.kind == ItemKind.LINK) {
                    val item = when (val res = LinkParser.parse(r.url)) {
                        is LinkResult.Ok -> InboxItem(newId(), ItemKind.LINK, nowWallMs, dest = res.dest)
                        is LinkResult.Rejected -> InboxItem(newId(), ItemKind.LINK, nowWallMs, note = res.problem.message)
                    }
                    accepted += item
                } else {
                    val bytes = api.download(token, r.id, FileRules.maxBytes(r.kind))
                    when (val v = FileRules.verify(r.kind, bytes)) {
                        is FileVerdict.Ok -> accepted += InboxItem(newId(), r.kind, nowWallMs, ext = v.format.ext, sizeBytes = bytes.size.toLong(), localName = save(v.format, bytes))
                        is FileVerdict.Rejected -> rejected++
                    }
                }
                handled += r.id
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: RelayException) {
                if (e.status == 401) { done = true; return ReceiveResult(accepted, rejected, handled, retry, sessionEnded = true) }
                retry += r.id
            } catch (t: Throwable) {
                retry += r.id
            }
        }
        done = true
        return ReceiveResult(accepted, rejected, handled, retry, sessionEnded = false)
        } finally {
            // 취소 등으로 호출부가 결과를 받지 못하면 이미 저장한 파일은 아무도 모르는 고아가 된다: 취소 중에도 끝까지 지운다
            if (!done) withContext(NonCancellable) { accepted.forEach { a -> a.localName?.let(discard) } }
        }
    }
}

/**
 * [Receiver.receive] 를 IO 에서 실행하고 결과를 [apply] 로 반영한다. 결과가 반영되기 전에 취소되거나(withContext 가 정상 반환한 결과를 버리는 틈 포함)
 * [isCurrent] 가 false(삭제·철회·재페어링과 경합)면 방금 저장한 파일을 모두 지운다. 이미 [apply] 된 항목은 지우지 않는다. 반영하지 않았으면 null.
 */
suspend fun pollReceive(
    receiver: Receiver, token: String, inbox: RemoteInbox, known: Set<String>, nowWallMs: Long, room: Int,
    discard: (String) -> Unit, isCurrent: () -> Boolean, apply: suspend (ReceiveResult) -> Unit,
): ReceiveResult? {
    val received = ArrayList<InboxItem>()
    var applied = false
    try {
        val res = withContext(kotlinx.coroutines.Dispatchers.IO) { receiver.receive(token, inbox, known, nowWallMs, room, received) }
        if (!isCurrent()) return null
        applied = true
        apply(res)
        return res
    } finally {
        if (!applied) withContext(NonCancellable) { received.forEach { r -> r.localName?.let(discard) } }
    }
}

/** 서버 응답 [PairGrant] -> 저장할 세션 정보(벽시계 기준 만료 시각). */
fun PairGrant.toPairing(nowWallMs: Long) = com.hmgcarapp.crossdrop.domain.PairingInfo(
    code = code, deviceToken = deviceToken, issuedWallMs = nowWallMs,
    codeExpiresWallMs = nowWallMs + codeExpiresInS * 1000L, sessionExpiresWallMs = nowWallMs + sessionExpiresInS * 1000L,
)
