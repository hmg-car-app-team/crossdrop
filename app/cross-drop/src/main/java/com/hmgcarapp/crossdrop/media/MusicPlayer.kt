package com.hmgcarapp.crossdrop.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.crossdrop.domain.PlayerState
import java.io.File

/** 재생기 콜백: 컨트롤러가 [com.hmgcarapp.crossdrop.domain.PlayerFlow] 이벤트로 바꾼다. 모두 메인 스레드. */
interface MusicEvents {
    fun onFocusLost(transient: Boolean)
    fun onFocusGained()
    fun onCompleted()
    fun onError()
    /** 하드웨어 미디어 키(주행 중에도 허용). */
    fun onMediaKey(play: Boolean?)
}

/**
 * 음악 재생(오디오만; 영상은 재생하지 않는다). 오디오 포커스를 요청하고, MediaSession 으로 하드웨어 미디어 키(재생/일시정지/정지)를 받는다.
 * 상태의 단일 출처는 컨트롤러의 [PlayerState] 이며 이 클래스는 [sync] 로 그 상태를 따라가기만 한다.
 * 한계: MediaBrowserService 가 없어 차량 미디어 템플릿/앨범 화면에는 노출되지 않는다. 포그라운드 서비스가 없어 앱이 종료되면 재생도 끝난다.
 */
class MusicPlayer(private val context: Context, private val events: MusicEvents) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private var focus: AudioFocusRequest? = null
    private var session: MediaSession? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> events.onFocusLost(transient = false)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> events.onFocusLost(transient = true)
            AudioManager.AUDIOFOCUS_GAIN -> events.onFocusGained()
        }
    }

    private var prepared = false
    private var wantPaused = false

    /** [state] 를 재생기에 반영한다. [file] 은 재생할 파일(재생 중 항목의 저장 파일). 준비(prepareAsync)가 끝나기 전에는 원하는 상태만 기억한다. */
    fun sync(state: PlayerState, file: File?) {
        if (state.playingId == null || file == null) { stop(); return }
        wantPaused = state.paused
        if (currentFile != file || player == null) { if (!start(file)) { events.onError(); return } }
        val p = player ?: return
        if (!prepared) return
        try {
            if (wantPaused && p.isPlaying) { p.pause(); publish(PlaybackState.STATE_PAUSED) }
            else if (!wantPaused && !p.isPlaying) { if (requestFocus()) { p.start(); publish(PlaybackState.STATE_PLAYING) } else events.onFocusLost(transient = false) }
        } catch (t: Throwable) { HmgLog.w("Music", "재생 제어 실패: ${t.javaClass.simpleName}"); events.onError() }
    }

    private fun start(file: File): Boolean {
        stopPlayer()
        if (!requestFocus()) return false
        var p: MediaPlayer? = null
        return try {
            val mp = MediaPlayer().also { p = it }
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            java.io.FileInputStream(file).use { mp.setDataSource(it.fd) }
            mp.setOnCompletionListener { events.onCompleted() }
            mp.setOnErrorListener { _, _, _ -> events.onError(); true }
            mp.setOnPreparedListener { m ->
                if (player !== m) return@setOnPreparedListener
                prepared = true
                try {
                    if (!wantPaused) { m.start(); ensureSession(); publish(PlaybackState.STATE_PLAYING) }
                } catch (t: Throwable) { HmgLog.w("Music", "재생 시작 실패: ${t.javaClass.simpleName}"); events.onError() }
            }
            player = mp; currentFile = file; prepared = false
            mp.prepareAsync()      // 메인 스레드에서 파일을 읽어 준비하지 않는다
            true
        } catch (t: Throwable) {
            HmgLog.w("Music", "재생 시작 실패: ${t.javaClass.simpleName}")
            p?.let { runCatching { it.release() } }     // 예외 경로에서 지역 MediaPlayer 해제
            stopPlayer(); false
        }
    }

    private fun requestFocus(): Boolean {
        if (focus != null) return true
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener).build()
        val ok = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (ok) focus = req
        return ok
    }

    private fun ensureSession() {
        if (session != null) return
        session = runCatching {
            MediaSession(context, "CrossDrop").also { s ->
                s.setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { events.onMediaKey(true) }
                    override fun onPause() { events.onMediaKey(false) }
                    override fun onStop() { events.onMediaKey(null) }
                })
                s.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "CrossDrop").build())
                s.isActive = true
            }
        }.getOrNull()
    }

    private fun publish(st: Int) {
        runCatching {
            session?.setPlaybackState(
                PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                    .setState(st, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build(),
            )
        }
    }

    private fun stopPlayer() {
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null; currentFile = null; prepared = false
    }

    fun stop() {
        stopPlayer()
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
        session?.let { runCatching { it.isActive = false; it.release() } }
        session = null
    }

    fun release() = stop()
}
