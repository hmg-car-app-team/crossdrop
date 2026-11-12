package com.hmgcarapp.crossdrop.app

import com.hmgcarapp.crossdrop.domain.DestState
import com.hmgcarapp.crossdrop.domain.PairStatus
import com.hmgcarapp.crossdrop.domain.PairingInfo
import com.hmgcarapp.crossdrop.domain.PlayerState
import com.hmgcarapp.crossdrop.domain.ScreenState

/** 화면에 보이는 모든 상태(컨트롤러가 단일 소유). 시연이 켜져 있으면 [demo]. */
data class UiState(
    val p: Persisted = Persisted(),
    val loading: Boolean = true,
    val screen: ScreenState = ScreenState(),
    val dest: DestState = DestState(),
    val player: PlayerState = PlayerState(),
    val sync: SyncStatus = SyncStatus.NO_CONSENT,
    val pairStatus: PairStatus = PairStatus.NONE,
    val serverConfigured: Boolean = false,
    /** 상단 짧은 안내(새 항목 도착 등). 몇 초 뒤 사라진다. */
    val banner: String? = null,
    /** 시연 페어링(저장하지 않음). */
    val demoPairing: PairingInfo? = null,
    val demo: Boolean = false,
    val debuggable: Boolean = false,
    val busy: Boolean = false,
    val saveHeld: Boolean = false,
    val corruptKept: Boolean = false,
    val message: String? = null,
    /** 마지막 QR 만들기 실패 안내. 토스트(message)와 달리 다음 시도·연결 끊기·시연 전까지 연결 화면에 남는다. */
    val pairError: String? = null,
    /** 연결되지 않은 채 QR/세션이 끝났다(만료). 세션 정보가 사라져도 다음 시도(새 QR·시연·연결 끊기)까지 '2 QR 만료' 표시를 유지한다. */
    val pairExpired: Boolean = false,
) {
    /** 화면에 보여 줄 페어링(시연 중이면 시연용). */
    val pairing: PairingInfo? get() = if (demo) demoPairing else p.pairing
}
