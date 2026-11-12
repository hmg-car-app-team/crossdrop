package com.hmgcarapp.crossdrop.domain

import java.net.URI

/** 받은 항목 한 건(메타데이터). 파일 내용은 앱 전용 저장소의 [localName] 파일에만 있다. 원래 파일명은 저장하지 않는다. */
data class InboxItem(
    val id: String,
    val kind: ItemKind,
    val receivedWallMs: Long,
    val ext: String? = null,
    val sizeBytes: Long = 0L,
    val localName: String? = null,
    /** 링크 항목에서 읽은 목적지(해석 실패면 null). 원본 URL 은 저장하지 않는다. */
    val dest: Destination? = null,
    /** 링크를 열 수 없을 때의 사용자용 안내(원본 URL 미포함). */
    val note: String? = null,
    val used: Boolean = false,
    /** 시연으로 만든 항목(서버를 거치지 않음). */
    val demo: Boolean = false,
)

/** 24시간 보관 규칙(벽시계; 프로세스가 죽어도 이어지려면 저장된 시각이 필요하다). */
object Retention {
    const val TTL_MS = 24L * 3_600_000L
    /** 미래 시각 허용 한도: 시계가 크게 되감기면 보관 기간을 믿을 수 없어 삭제한다(프라이버시 우선). */
    const val FUTURE_SLACK_MS = 3_600_000L

    fun expired(receivedMs: Long, nowMs: Long): Boolean = nowMs - receivedMs >= TTL_MS || receivedMs - nowMs > FUTURE_SLACK_MS

    fun remainingMs(receivedMs: Long, nowMs: Long): Long = (TTL_MS - (nowMs - receivedMs)).coerceIn(0L, TTL_MS)

    /** (남길 항목, 지울 항목). */
    fun split(items: List<InboxItem>, nowMs: Long): Pair<List<InboxItem>, List<InboxItem>> =
        items.partition { !expired(it.receivedWallMs, nowMs) }
}

/** 릴레이 서버 주소 검증. https 는 허용. http 는 루프백(127.0.0.1·localhost; `adb reverse` 대상)만 모든 빌드에서, 에뮬레이터용 10.0.2.2 는 debuggable 빌드에서만 허용한다. */
object ServerUrlPolicy {
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1")
    private const val EMULATOR_HOST = "10.0.2.2"

    /** 정상이면 끝 슬래시를 뗀 기본 주소, 아니면 null(= 통신 안 함). */
    fun validate(raw: String?, debuggable: Boolean): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.length > 200) return null
        val u = try { URI(s) } catch (t: Throwable) { return null }
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (u.userInfo != null || u.query != null || u.fragment != null || u.rawPath.orEmpty().let { it.isNotEmpty() && it != "/" }) return null
        val ok = when (scheme) {
            "https" -> true
            "http" -> host in LOOPBACK_HOSTS || (debuggable && host == EMULATOR_HOST)
            else -> false
        }
        if (!ok) return null
        if (host.endsWith(".")) return null // 끝 점 호스트 거부
        if (host.all { it.isDigit() || it == '.' } && !Regex("(\\d{1,3}\\.){3}\\d{1,3}").matches(host)) return null // 정수형·비정형 숫자 호스트 거부
        if (u.rawAuthority.orEmpty().endsWith(":")) return null // 빈 포트 거부
        if (u.port != -1 && u.port !in 1..65535) return null
        return scheme + "://" + host + (if (u.port != -1) ":" + u.port else "") // 소문자 정규화
    }

    /** 화면 고지용 '호스트:포트'(검증을 통과한 주소에서). 포트가 없으면 호스트만. */
    fun hostPort(validated: String?): String? = validated?.let { runCatching { URI(it) }.getOrNull() }?.let { u -> u.host?.let { it + (if (u.port != -1) ":" + u.port else "") } }
}

/** 서버가 발급한 페어링 세션(휴대폰 연결 한 번). [deviceToken] 은 이 세션의 수신함 접근 비밀값이며 앱 전용 저장소에만 둔다. */
data class PairingInfo(
    val code: String,
    val deviceToken: String,
    val issuedWallMs: Long,
    val codeExpiresWallMs: Long,
    val sessionExpiresWallMs: Long,
    val claimed: Boolean = false,
)

enum class PairStatus(val label: String) {
    NONE("연결 안 됨"), WAITING("휴대폰 연결을 기다리는 중"), CODE_EXPIRED("QR 이 만료됐어요"), CONNECTED("휴대폰 연결됨"), ENDED("연결이 끝났어요"),
}

/** 연결 단계 표시용 상태. */
enum class StepState { PENDING, CURRENT, DONE, FAILED }

/** 연결 화면의 한 단계. [text] 는 색에 의존하지 않고 상태를 글로도 알려 준다. */
data class PairStep(val no: Int, val state: StepState) {
    val text: String get() = when (state) {
        StepState.PENDING -> "$no ${PairSteps.LABELS[no - 1]}"
        StepState.CURRENT -> "$no ${PairSteps.ACTIVE[no - 1]}"
        StepState.DONE -> if (no == PairSteps.COUNT) "$no ${PairSteps.LABELS[no - 1]}" else "$no ${PairSteps.LABELS[no - 1]} 완료"
        StepState.FAILED -> "$no ${PairSteps.FAILED[no - 1]}"
    }
}

/**
 * 연결 단계(1 서버 확인 → 2 QR 표시 → 3 폰 연결 대기 → 4 연결됨)를 기존 상태 값만으로 조합한다(순수; 서버·저장 변경 없음).
 * [failed] 는 서버 오류(동기화 실패) 또는 QR 만들기 실패. 실패는 해당 단계에 표시하며 화면의 한국어 실패 안내와 같은 시점에만 켜진다.
 */
object PairSteps {
    const val COUNT = 4
    val LABELS = listOf("서버 확인", "QR 표시", "폰 연결 대기", "연결됨")
    val ACTIVE = listOf("서버 확인 중", "QR 표시 중", "폰 연결 대기 중", "연결됨")
    val FAILED = listOf("서버 확인 실패", "QR 만료", "폰 연결 확인 실패", "연결 확인 실패")

    fun of(status: PairStatus, failed: Boolean, busy: Boolean): List<PairStep> {
        fun build(done: Int, cur: StepState?) = (1..COUNT).map { n ->
            PairStep(n, when {
                n <= done -> StepState.DONE
                n == done + 1 && cur != null -> cur
                else -> StepState.PENDING
            })
        }
        return when (status) {
            PairStatus.NONE, PairStatus.ENDED -> build(0, if (failed) StepState.FAILED else if (busy) StepState.CURRENT else null)
            PairStatus.CODE_EXPIRED -> build(1, StepState.FAILED)
            PairStatus.WAITING -> build(2, if (failed) StepState.FAILED else StepState.CURRENT)
            PairStatus.CONNECTED -> if (failed) build(3, StepState.FAILED) else build(COUNT, null)
        }
    }
}

object Pairing {
    val CODE_REGEX = Regex("^[A-HJ-NP-Z2-9]{8}$")
    val TOKEN_REGEX = Regex("^[0-9a-f]{64}$")

    fun status(p: PairingInfo?, nowWallMs: Long): PairStatus = when {
        p == null -> PairStatus.NONE
        nowWallMs >= p.sessionExpiresWallMs -> PairStatus.ENDED
        p.claimed -> PairStatus.CONNECTED
        nowWallMs >= p.codeExpiresWallMs -> PairStatus.CODE_EXPIRED
        else -> PairStatus.WAITING
    }

    /** 서버 오류 중에는 '연결됨' 이라고 단정하지 않는다(표시만; 상태 판정·저장·동기화는 그대로). */
    fun connectionUnconfirmed(status: PairStatus, syncError: Boolean) = status == PairStatus.CONNECTED && syncError
    fun badgeLabel(status: PairStatus, syncError: Boolean): String = if (connectionUnconfirmed(status, syncError)) "연결 상태를 확인하지 못했어요" else status.label

    /** QR 에 담는 주소. 코드는 `#` 뒤(프래그먼트)에 둬서 서버 요청·접근 기록에 남지 않게 한다. */
    fun qrPayload(serverUrl: String, code: String): String = "$serverUrl/u#$code"

    /** 사용자에게 보여 줄 코드(4자리씩 끊음). */
    fun display(code: String): String = code.chunked(4).joinToString("-")
}
