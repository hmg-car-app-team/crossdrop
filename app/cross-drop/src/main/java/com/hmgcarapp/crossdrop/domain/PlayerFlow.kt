package com.hmgcarapp.crossdrop.domain

/** 음악 재생 상태(순수). 오디오 포커스를 잃으면 멈추거나 일시정지하고, 일시적 상실이면 돌려받을 때 이어서 재생한다. */
data class PlayerState(val playingId: String? = null, val paused: Boolean = false, val resumeOnGain: Boolean = false)

sealed interface PlayerEvent {
    data class Play(val itemId: String) : PlayerEvent
    data object Pause : PlayerEvent
    data object Resume : PlayerEvent
    data object Stop : PlayerEvent
    /** [transient] = AUDIOFOCUS_LOSS_TRANSIENT 계열(곧 돌아옴), false = 영구 상실. */
    data class FocusLost(val transient: Boolean) : PlayerEvent
    data object FocusGained : PlayerEvent
    data object Completed : PlayerEvent
    data class ItemRemoved(val itemId: String) : PlayerEvent
}

object PlayerFlow {
    fun reduce(s: PlayerState, e: PlayerEvent): PlayerState = when (e) {
        is PlayerEvent.Play -> PlayerState(playingId = e.itemId)
        PlayerEvent.Pause -> if (s.playingId != null) s.copy(paused = true, resumeOnGain = false) else s
        PlayerEvent.Resume -> if (s.playingId != null) s.copy(paused = false, resumeOnGain = false) else s
        PlayerEvent.Stop, PlayerEvent.Completed -> PlayerState()
        is PlayerEvent.FocusLost ->
            if (s.playingId == null) s
            else if (e.transient) s.copy(paused = true, resumeOnGain = !s.paused || s.resumeOnGain)
            else PlayerState()
        PlayerEvent.FocusGained -> if (s.playingId != null && s.resumeOnGain) s.copy(paused = false, resumeOnGain = false) else s
        is PlayerEvent.ItemRemoved -> if (s.playingId == e.itemId) PlayerState() else s
    }
}
