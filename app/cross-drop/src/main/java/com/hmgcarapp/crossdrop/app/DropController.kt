package com.hmgcarapp.crossdrop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.os.SystemClock
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.sim.SimulationHub
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.core.voice.Listener
import com.hmgcarapp.core.voice.PleosListener
import com.hmgcarapp.core.voice.PleosSpeaker
import com.hmgcarapp.core.voice.Speaker
import com.hmgcarapp.crossdrop.BuildConfig
import com.hmgcarapp.crossdrop.domain.DestEffect
import com.hmgcarapp.crossdrop.domain.DestEvent
import com.hmgcarapp.crossdrop.domain.DestFlow
import com.hmgcarapp.crossdrop.domain.DestOffer
import com.hmgcarapp.crossdrop.domain.DestPhase
import com.hmgcarapp.crossdrop.domain.FileFormat
import com.hmgcarapp.crossdrop.domain.FileRules
import com.hmgcarapp.crossdrop.domain.FileVerdict
import com.hmgcarapp.crossdrop.domain.Heard
import com.hmgcarapp.crossdrop.domain.InboxItem
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.domain.LinkParser
import com.hmgcarapp.crossdrop.domain.LinkResult
import com.hmgcarapp.crossdrop.domain.Page
import com.hmgcarapp.crossdrop.domain.Pairing
import com.hmgcarapp.crossdrop.domain.PairStatus
import com.hmgcarapp.crossdrop.domain.PairingInfo
import com.hmgcarapp.crossdrop.domain.PlayerEvent
import com.hmgcarapp.crossdrop.domain.PlayerFlow
import com.hmgcarapp.crossdrop.domain.Retention
import com.hmgcarapp.crossdrop.domain.ScreenEvent
import com.hmgcarapp.crossdrop.domain.ScreenFlow
import com.hmgcarapp.crossdrop.domain.ServerUrlPolicy
import com.hmgcarapp.crossdrop.media.MusicEvents
import com.hmgcarapp.crossdrop.media.MusicPlayer
import com.hmgcarapp.crossdrop.navi.NaviController
import com.hmgcarapp.crossdrop.navi.NaviPort
import com.hmgcarapp.crossdrop.net.HttpRelayApi
import com.hmgcarapp.crossdrop.net.RelayApi
import com.hmgcarapp.crossdrop.net.RelayException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * 화면 상태의 단일 소유자이자 SDK/IO 배선. 모든 상태 변경은 메인 스레드(Dispatchers.Main.immediate).
 * 화면 열림·목적지 안내·재생 판단은 순수 reducer([ScreenFlow]·[DestFlow]·[PlayerFlow])가 하고 여기서는 배선만 한다.
 * 시계: 폴링 간격·배너는 [monoClock](단조), 보관 만료·페어링 만료만 [wallClock](저장된 시각이 필요).
 *
 * 주행 중 규제: 사진·PDF·QR·개인정보 화면·모든 조작(`parkedOnly`)은 정차일 때만. 주행이 시작되면 [ScreenFlow] 가 자동으로 닫는다.
 * 개인정보: 서버와 통신은 [SyncPump] 한 곳(동의·서버 주소·비시연 필요). 로그에는 URL·좌표·파일명·코드·토큰을 남기지 않는다.
 */
class DropController(
    private val context: Context,
    val signals: CarSignals,
    private val speaker: Speaker = PleosSpeaker(context),
    private val listener: Listener = PleosListener(context),
    naviFactory: (CoroutineScope) -> NaviPort = { NaviController(context, it) },
    private val apiFactory: (String) -> RelayApi = { HttpRelayApi(it) },
    serverUrlRaw: String = BuildConfig.RELAY_SERVER_URL,
    private val vault: FileVault = FileVault(File(context.filesDir, "crossdrop-inbox")),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monoClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> HmgLog.w("App", "코루틴 예외(무시): ${t.javaClass.simpleName}") })
    private val stateStore = StateStore(LocalJsonStore.forApp(context))
    val debuggable: Boolean = SimulationHub.isDebuggable(context)
    private val serverUrl: String? = ServerUrlPolicy.validate(serverUrlRaw, debuggable)
    private val pump = SyncPump(serverUrl?.let(apiFactory), serverUrl)
    private val saveGate = SaveGate(stateStore, { restored }, ::persistedCopy)
    private val navi: NaviPort = naviFactory(scope)
    private val random = SecureRandom()
    init {
        StateCodec.sealer = AndroidKeystoreSealer()
        StateCodec.onSealFailure = { _state.update { it.copy(message = "저장하지 못했어요") } }
            }

    private val _state = MutableStateFlow(UiState(debuggable = debuggable, serverConfigured = serverUrl != null))
    val state: StateFlow<UiState> = _state

    private val runner = DestRunner(scope, speaker, listener, navi, debuggable, onMarkUsed = ::markUsed, dispatch = ::dispatchDest)
    private val player = MusicPlayer(context, object : MusicEvents {
        override fun onFocusLost(transient: Boolean) = dispatchPlayer(PlayerEvent.FocusLost(transient))
        override fun onFocusGained() = dispatchPlayer(PlayerEvent.FocusGained)
        override fun onCompleted() = dispatchPlayer(PlayerEvent.Completed)
        override fun onError() = dispatchPlayer(PlayerEvent.Stop)
        override fun onMediaKey(play: Boolean?) = dispatchPlayer(when (play) { true -> PlayerEvent.Resume; false -> PlayerEvent.Pause; null -> PlayerEvent.Stop })
    })

    private var coreDrive = DriveState.DRIVING
    private var uiDrive: DriveState? = null
    private var saveJob: Job? = null
    private var polling = false
    private var pollJob: Job? = null
    private var simReceiver: BroadcastReceiver? = null
    private var bannerUntil = 0L
    private var lastPrune = 0L
    private val handledRemote = HashSet<String>()
    private val retryCounts = HashMap<String, Int>()
    private val restored: Boolean get() = !_state.value.loading

    init {
        // 시연 상태는 저장하지 않는다: 앱 시작은 항상 실신호.
        signals.hub.setDriveOverride(null); signals.hub.setEnabled(false)
        scope.launch { restore() }
        scope.launch {
            signals.driveState.catch { HmgLog.w("Drive", "flow 실패 ${it.javaClass.simpleName}"); coreDrive = DriveState.DRIVING; applyDrive() }.collect { coreDrive = it.state; applyDrive() }
        }
        scope.launch { while (true) { delay(TICK_MS); tick() } }
        if (debuggable) simReceiver = SimReceiver.register(context, this, monoClock)
    }

    // ---------- 주행 판정 ----------
    private fun applyDrive() {
        val driving = coreDrive == DriveState.DRIVING || uiDrive == DriveState.DRIVING
        if (_state.value.screen.driving == driving && _state.value.dest.driving == driving) return
        dispatchScreen(ScreenEvent.DriveChanged(driving))
        dispatchDest(DestEvent.DriveChanged(driving))
    }

    /** 화면 쪽 UX 제한(provider)까지 반영한 주행 판정. 사용자 조작이 아닌 시스템 상태 반영. */
    fun onUiDriveState(d: DriveState) { uiDrive = d; applyDrive() }

    private inline fun parkedOnly(block: () -> Unit) { if (_state.value.screen.driving) return; block() }

    // ---------- reducer 연결 ----------
    private fun dispatchScreen(e: ScreenEvent) { _state.update { it.copy(screen = ScreenFlow.reduce(it.screen, e)) } }

    private fun dispatchDest(e: DestEvent) {
        val (n, fx) = DestFlow.reduce(_state.value.dest, e)
        _state.update { it.copy(dest = n) }
        fx.forEach(runner::run)
    }

    private fun dispatchPlayer(e: PlayerEvent) {
        val n = PlayerFlow.reduce(_state.value.player, e)
        _state.update { it.copy(player = n) }
        val file = n.playingId?.let { id -> _state.value.p.items.firstOrNull { it.id == id }?.localName }?.let(vault::resolve)
        player.sync(n, file)
    }

    // ---------- 사용자 동작(모두 정차 중에만) ----------
    fun setPage(p: Page) = parkedOnly { dispatchScreen(ScreenEvent.SetPage(p)) }
    fun closeViewer() { dispatchScreen(ScreenEvent.CloseViewer) }
    fun clearMessage() { _state.update { it.copy(message = null) } }

    fun open(id: String) = parkedOnly {
        val item = _state.value.p.items.firstOrNull { it.id == id } ?: return@parkedOnly
        when (item.kind) {
            ItemKind.IMAGE, ItemKind.PDF -> dispatchScreen(ScreenEvent.Open(id))
            ItemKind.AUDIO -> dispatchPlayer(PlayerEvent.Play(id))
            ItemKind.LINK -> proposeFromItem(item, voice = false)
        }
    }

    fun pauseMusic() = parkedOnly { dispatchPlayer(PlayerEvent.Pause) }
    fun resumeMusic() = parkedOnly { dispatchPlayer(PlayerEvent.Resume) }
    fun stopMusic() = parkedOnly { dispatchPlayer(PlayerEvent.Stop) }
    fun touchAccept() = parkedOnly { dispatchDest(DestEvent.TouchAccept) }
    fun touchDismiss() = parkedOnly { dispatchDest(DestEvent.TouchDismiss) }

    private fun proposeFromItem(item: InboxItem, voice: Boolean) {
        val d = item.dest
        if (d == null) { _state.update { it.copy(message = item.note ?: "열 수 없는 링크예요") }; return }
        if (_state.value.dest.offer != null) { _state.update { it.copy(message = "다른 목적지 안내가 진행 중이에요") }; return }
        dispatchDest(DestEvent.Propose(DestOffer(item.id, d, voice)))
    }

    fun fileOf(item: InboxItem): File? = vault.resolve(item.localName)

    /** 화면 표시용 현재 시각(보관 남은 시간 계산). */
    fun nowWall(): Long = wallClock()

    /** QR 에 쓰는 검증된 서버 기본 주소(없으면 null). */
    fun serverBase(): String? = serverUrl
    /** 고지용 '호스트:포트'(서버 주소가 없으면 null). */
    fun serverHostPort(): String? = ServerUrlPolicy.hostPort(serverUrl)

    // ---------- 연결(페어링) ----------
    /** 서버 연결에 동의하고 새 QR 을 만든다. [agree] 는 동의 팝업에서 사용자가 누른 결과. */
    fun startPairing(agree: Boolean) = parkedOnly {
        if (!_state.value.p.consent) {
            if (!agree) return@parkedOnly
            updateP { it.copy(consent = true) }
        }
        val api = pump.apiForPairing(_state.value.p.consent, _state.value.demo)
        if (api == null) { _state.update { it.copy(message = "서버에 연결할 수 없어요") }; return@parkedOnly }
        if (_state.value.busy) return@parkedOnly
        // 새 QR 을 만들기 전에 이전 세션을 서버에서 지운다(코드 만료 세션이 서버 슬롯을 차지하지 않게)
        _state.value.p.pairing?.let { old -> pollJob?.cancel(); deleteRemoteSession(old.deviceToken) }
        _state.update { it.copy(busy = true, pairError = null, pairExpired = false) }
        scope.launch {
            try {
                val grant = api.createSession()
                val info = grant.toPairing(wallClock())
                handledRemote.clear(); retryCounts.clear()
                updateP { it.copy(pairing = info) }
                _state.update { it.copy(message = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                HmgLog.w("Pair", "세션 만들기 실패: ${t.javaClass.simpleName}")
                _state.update { it.copy(message = PAIR_FAIL_NOTE, pairError = PAIR_FAIL_NOTE) }
            } finally { _state.update { it.copy(busy = false) } }
        }
    }

    /** 휴대폰 연결을 끊고 서버에 남은 내 세션·파일을 지우도록 요청한다(받은 항목은 그대로). */
    fun endPairing() = parkedOnly { endPairingInternal() }

    private fun endPairingInternal() {
        val info = _state.value.p.pairing
        pollJob?.cancel()      // 진행 중인 폴링이 삭제·철회 뒤에 결과를 반영하지 못하게(pollOnce 의 PollGuard 가 한 번 더 막는다)
        updateP { it.copy(pairing = null) }
        _state.update { it.copy(pairError = null, pairExpired = false) }
        handledRemote.clear(); retryCounts.clear()
        if (info != null) deleteRemoteSession(info.deviceToken)
    }

    private fun deleteRemoteSession(token: String) {
        val api = pump.apiForCleanup() ?: return
        scope.launch { try { api.deleteSession(token) } catch (e: CancellationException) { throw e } catch (t: Throwable) { HmgLog.w("Pair", "서버 세션 삭제 실패(24시간 뒤 자동 삭제): ${t.javaClass.simpleName}") } }
    }

    /** 동의 철회: 연결을 끊고 서버 사본 삭제를 요청한다. */
    fun revokeConsent() = parkedOnly {
        endPairingInternal()
        updateP { it.copy(consent = false) }
    }

    // ---------- 삭제 ----------
    fun deleteItem(id: String) = parkedOnly { removeItems(setOf(id)) }

    /** 받은 항목과 파일을 모두 지우고, 서버 세션도 끊는다. */
    fun deleteAll() = parkedOnly {
        // 먼저 동기로 연결을 끊어(토큰·pollJob 무효화) tick 이 지우는 동안 새 폴링을 띄우지 못하게 한다. 서버 세션 삭제는 끊기 전의 토큰으로.
        val polling = pollJob
        endPairingInternal()
        scope.launch {
            polling?.join()      // 취소된 폴링이 저장해 둔 파일을 정리한 뒤에 지운다
            removeItems(_state.value.p.items.map { it.id }.toSet())
            withContext(Dispatchers.IO) { runCatching { vault.purgeAll() } }
            dispatchDest(DestEvent.Cleared)
        }
    }

    private fun removeItems(ids: Set<String>) {
        if (ids.isEmpty()) return
        val removed = _state.value.p.items.filter { it.id in ids }
        updateP { p -> p.copy(items = p.items.filter { it.id !in ids }) }
        removed.forEach { item ->
            vault.delete(item.localName)
            dispatchPlayer(PlayerEvent.ItemRemoved(item.id))
            dispatchScreen(ScreenEvent.ItemRemoved(item.id))
            if (_state.value.dest.offer?.itemId == item.id) dispatchDest(DestEvent.Cleared)
        }
    }

    private fun markUsed(itemId: String) = updateP { p -> p.copy(items = p.items.map { if (it.id == itemId) it.copy(used = true) else it }) }

    // ---------- 1틱: 만료 정리 -> 배너 -> 폴링 ----------
    private fun tick() {
        if (!restored) return
        val mono = monoClock()
        if (_state.value.banner != null && mono >= bannerUntil) _state.update { it.copy(banner = null) }
        if (mono - lastPrune >= PRUNE_EVERY_MS) { lastPrune = mono; pruneExpired() }
        val wall = wallClock()
        val ps = Pairing.status(_state.value.pairing, wall)
        val s = _state.value
        val sync = pump.status(s.p.consent, s.demo, s.p.pairing != null && ps != PairStatus.ENDED)
        if (ps != s.pairStatus || sync != s.sync) _state.update { it.copy(pairStatus = ps, sync = sync) }
        if (!s.demo && s.p.pairing != null && ps == PairStatus.ENDED) { val unclaimed = !s.p.pairing.claimed; endPairingInternal(); if (unclaimed) _state.update { it.copy(pairExpired = true) }; return }
        val info = s.p.pairing ?: return
        if (polling) return
        val api = pump.prepare(mono, s.p.consent, s.demo, ps != PairStatus.ENDED) ?: return
        polling = true
        pollJob = scope.launch { try { pollOnce(api, info.deviceToken) } finally { polling = false } }
    }

    private fun pruneExpired() {
        val (_, gone) = Retention.split(_state.value.p.items, wallClock())
        if (gone.isNotEmpty()) { HmgLog.i("Retention", "24시간 지난 항목 ${gone.size}건 삭제"); removeItems(gone.map { it.id }.toSet()) }
    }

    private suspend fun pollOnce(api: RelayApi, token: String) {
        try {
            val inbox = api.inbox(token)
            if (!PollGuard.stillCurrent(_state.value.p, token)) { pump.finish(true); return }
            val now = wallClock()
            if (inbox.claimed && _state.value.p.pairing?.claimed == false) updateP { p -> p.copy(pairing = p.pairing?.copy(claimed = true)) }
            val receiver = Receiver(api, save = { f, b -> vault.save(f, b) }, newId = ::newId)
            val room = (StateCodec.MAX_ITEMS - _state.value.p.items.size).coerceAtLeast(0)
            // 수신하는 동안 삭제·철회·연결 끊기·재페어링이 있었거나 반영 전에 취소되면 방금 저장한 파일을 지우고 아무것도 반영하지 않는다
            val res = pollReceive(receiver, token, inbox, handledRemote.toSet(), now, room, discard = { vault.delete(it) }, isCurrent = { PollGuard.stillCurrent(_state.value.p, token) }) { r ->
                // 반영(상태에 넣기)은 취소 가능 지점(아래 suspend deleteItem) 앞에: 그 대기 중 취소돼도 받은 파일이 고아가 되지 않게
                if (r.accepted.isNotEmpty()) onReceived(r.accepted)
                handledRemote += r.handled
                r.retry.forEach { id ->
                    val n = (retryCounts[id] ?: 0) + 1
                    retryCounts[id] = n
                    if (n >= MAX_RETRIES) { handledRemote += id; try { api.deleteItem(token, id) } catch (e: CancellationException) { throw e } catch (t: Throwable) { } }
                }
                // 서버 사본 삭제는 항목을 반영하고 디스크에 쓴 뒤에(중간에 죽어도 유실이 아니라 중복 수신)
                commitThenDelete(api, token, r.handled) { saveNow().durable() }
            }
            if (res == null) { pump.finish(true); return }
            if (res.rejected > 0) _state.update { it.copy(message = "지원하지 않는 파일 ${res.rejected}개는 받지 않았어요") }
            if (res.sessionEnded) {
                val msg = SessionEndText.of(_state.value.p.pairing?.claimed)
                val unclaimed = _state.value.p.pairing?.claimed == false
                updateP { it.copy(pairing = null) }
                _state.update { it.copy(message = msg, pairExpired = unclaimed || it.pairExpired) }
            }
            pump.finish(true)
        } catch (e: CancellationException) {
            pump.finish(true); throw e
        } catch (e: RelayException) {
            HmgLog.w("Sync", "폴링 실패: HTTP ${e.status}")
            if (e.status == 401) {
                if (PollGuard.stillCurrent(_state.value.p, token)) {
                    val msg = SessionEndText.of(_state.value.p.pairing?.claimed)    // claim 안 된 세션이면 'QR 이 만료됐어요'
                    val unclaimed = _state.value.p.pairing?.claimed == false
                    updateP { it.copy(pairing = null) }; _state.update { it.copy(message = msg, pairExpired = unclaimed || it.pairExpired) }
                }
                pump.finish(true)
            } else pump.finish(false)
        } catch (t: Throwable) {
            HmgLog.w("Sync", "폴링 실패: ${t.javaClass.simpleName}")
            pump.finish(false)
        }
    }

    private fun newId(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ---------- 새 항목 도착: 상단 짧은 안내 + TTS (+ 목적지는 음성 질문) ----------
    private fun onReceived(items: List<InboxItem>) {
        updateP { p -> p.copy(items = (p.items + items).takeLast(StateCodec.MAX_ITEMS)) }
        val files = items.filter { it.kind != ItemKind.LINK }
        val links = items.filter { it.kind == ItemKind.LINK }
        val text = ReceiptText.of(items)
        showBanner(text.banner)
        val link = links.firstOrNull { it.dest != null }
        val offerActive = _state.value.dest.offer != null
        if (link != null && !offerActive) {
            proposeFromItem(link, voice = true)     // 질문(TTS) + 직후 STT 1회
        } else if (!offerActive) {
            speaker.speak(if (_state.value.screen.driving && files.isNotEmpty()) "${text.banner}. 정차 후 확인하세요." else text.banner)
        }
        text.message?.let { n -> _state.update { it.copy(message = n) } }
    }

    private fun showBanner(text: String) {
        bannerUntil = monoClock() + BANNER_MS
        _state.update { it.copy(banner = text) }
    }

    // ---------- 시연(서버 없이 전체 흐름) ----------
    fun startDemo() = parkedOnly { beginDemo() }

    internal fun beginDemo() {
        _state.update { s ->
            if (s.demo) s else {
                val now = wallClock()
                s.copy(pairError = null, pairExpired = false, demo = true, demoPairing = PairingInfo(DemoAssets.DEMO_CODE, DemoAssets.DEMO_TOKEN, now, now + 600_000L, now + Retention.TTL_MS))
            }
        }
    }

    fun stopDemo() = parkedOnly { endDemo() }

    internal fun endDemo() {
        val demoIds = _state.value.p.items.filter { it.demo }.map { it.id }.toSet()
        if (demoIds.isNotEmpty()) removeItems(demoIds)
        _state.update { it.copy(demo = false, demoPairing = null) }
    }

    /** 가상 휴대폰 수신. [what] = photo | pdf | music | kakao | naver | google | apple | short | bad. 시연 모드를 켜고 항목을 만든다. */
    fun demoReceive(what: String) {
        if (!restored) return
        beginDemo()
        scope.launch {
            val item: InboxItem? = try {
                withContext(Dispatchers.IO) { buildDemoItem(what) }
            } catch (e: CancellationException) { throw e } catch (t: Throwable) { HmgLog.w("Demo", "시연 항목 만들기 실패: ${t.javaClass.simpleName}"); null }
            if (item == null) { _state.update { it.copy(message = "알 수 없는 시연 항목이에요") }; return@launch }
            _state.update { s -> s.copy(demoPairing = s.demoPairing?.copy(claimed = true)) }
            onReceived(listOf(item))
        }
    }

    private fun buildDemoItem(what: String): InboxItem? {
        val now = wallClock()
        return when (what) {
            "photo" -> fileItem(ItemKind.IMAGE, DemoAssets.photoPng(), now)
            "pdf" -> fileItem(ItemKind.PDF, DemoAssets.pdf(), now)
            "music" -> {
                val wav = DemoAssets.toneWav()   // 시연 전용: 검증 경로를 거치지 않는다(DemoAssets 주석 참고)
                InboxItem(newId(), ItemKind.AUDIO, now, ext = FileFormat.M4A.ext, sizeBytes = wav.size.toLong(), localName = vault.save(FileFormat.M4A, wav), demo = true)
            }
            else -> DemoAssets.link(what)?.let { url ->
                when (val r = LinkParser.parse(url)) {
                    is LinkResult.Ok -> InboxItem(newId(), ItemKind.LINK, now, dest = r.dest, demo = true)
                    is LinkResult.Rejected -> InboxItem(newId(), ItemKind.LINK, now, note = r.problem.message, demo = true)
                }
            }
        }
    }

    private fun fileItem(kind: ItemKind, bytes: ByteArray, now: Long): InboxItem? =
        (FileRules.verify(kind, bytes) as? FileVerdict.Ok)?.let { v ->
            InboxItem(newId(), kind, now, ext = v.format.ext, sizeBytes = bytes.size.toLong(), localName = vault.save(v.format, bytes), demo = true)
        }

    /** 시연: 음성 인식 결과를 주입한다(질문 직후 청취 단계일 때만). 에뮬레이터는 STT 결과가 오지 않아 이 방법으로 확인한다. */
    internal fun simHeard(text: String) {
        val d = _state.value.dest
        val id = d.inFlightId ?: return
        if (d.phase != DestPhase.LISTENING) return
        runner.cancelFlow()
        dispatchDest(DestEvent.Heard(id, Heard.Text(text)))
    }

    /** 시연: 보관 시간을 [hours] 시간 앞당긴다(24시간 뒤 자동 삭제 시연). 시연 항목만 대상. */
    internal fun simAge(hours: Int) {
        val shift = hours.coerceIn(0, 72) * 3_600_000L
        updateP { p -> p.copy(items = p.items.map { if (it.demo) it.copy(receivedWallMs = it.receivedWallMs - shift) else it }) }
        pruneExpired()
    }

    internal fun simDrive(parked: Boolean?) {
        val h = signals.hub
        when (parked) {
            true -> { h.setEnabled(true); h.setGear(Gear.PARK); h.setSpeedKmh(0f) }
            false -> { h.setEnabled(true); h.setGear(Gear.DRIVE); h.setSpeedKmh(50f) }
            null -> { h.setGear(null); h.setSpeedKmh(null); h.setEnabled(false) }
        }
    }

    internal fun simOpenNewest(kind: ItemKind) {
        _state.value.p.items.lastOrNull { it.kind == kind }?.let { open(it.id) }
    }

    // ---------- 저장/복원 ----------
    private fun updateP(f: (Persisted) -> Persisted) {
        _state.update { it.copy(p = f(it.p)) }
        scheduleSave()
    }

    private suspend fun restore() {
        var res = StateStore.Restored(Persisted(), corrupt = false, quarantined = false, readError = false)
        try {
            res = withContext(Dispatchers.IO) { StateCodec.sealer?.warmUp(); stateStore.restore(wallClock()) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            HmgLog.w("Store", "복원 실패 -> 기본값, 이번 세션 저장 보류: ${t.javaClass.simpleName}")
            stateStore.holdWrites()
            res = res.copy(readError = true)
        } finally {
            applyRestored(res)
        }
    }

    private suspend fun applyRestored(res: StateStore.Restored) {
        if (res.corrupt) HmgLog.w("Store", "저장 파일 손상 -> 기본값으로 시작(보존=${res.quarantined})")
        if (res.readError) HmgLog.w("Store", "저장 파일 읽기 오류 -> 기본값으로 동작, 이번 세션 저장 보류(원본 유지)")
        val now = wallClock()
        var p = res.p
        val (keep, gone) = Retention.split(p.items, now)
        p = p.copy(items = keep)
        if (Pairing.status(p.pairing, now) == PairStatus.ENDED) p = p.copy(pairing = null)
        withContext(Dispatchers.IO) {
            gone.forEach { vault.delete(it.localName) }
            // 읽기 오류일 때는 어떤 파일이 유효한지 모르므로 건드리지 않는다. 그 밖에는 저장 상태에 없는 파일(시연 잔재·비정상 종료)을 지운다.
            if (!res.readError) vault.pruneOrphans(keep.mapNotNull { it.localName }.toSet())
        }
        _state.update { it.copy(p = p, loading = false, saveHeld = res.readError, corruptKept = res.corrupt && res.quarantined) }
        // 구형식 평문 토큰은 다음 저장 때 암호화 형식으로 바뀐다(마이그레이션).
        if (gone.isNotEmpty() || res.p.pairing != p.pairing || p.pairing != null) scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            if (!restored) return@launch
            onWriteResult(withContext(Dispatchers.IO + NonCancellable) { stateStore.writeLatest { persistedCopy() } })
        }
    }

    private fun onWriteResult(r: WriteResult) {
        when (r) {
            WriteResult.WROTE -> _state.update { if (it.corruptKept) it.copy(corruptKept = false) else it }
            WriteResult.FAILED -> _state.update { it.copy(saveHeld = true, message = null) }
            WriteResult.UNCHANGED, WriteResult.HELD -> Unit
        }
    }

    /** 디스크에 쓸 사본. 시연 항목은 쓰지 않는다. */
    private fun persistedCopy(): Persisted = _state.value.p.let { it.copy(items = it.items.filter { i -> !i.demo }, savedAtWallMs = wallClock()) }

    /** 저장하고 결과를 돌려준다(WROTE/UNCHANGED 만 디스크에 안전히 있다는 뜻). 복원 전이면 HELD. */
    fun saveNow(): WriteResult {
        if (!restored) return WriteResult.HELD
        saveJob?.cancel()
        return saveGate.save().also { onWriteResult(it) }
    }

    fun release() {
        saveNow()
        simReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        runner.cancelVoice()
        player.release()
        navi.release()
        signals.hub.reset()
        speaker.release()
        listener.release()
        scope.cancel()
    }

    companion object {
        const val PAIR_FAIL_NOTE = "서버에 연결하지 못했어요"
        const val TICK_MS = 1_000L
        const val PRUNE_EVERY_MS = 30_000L
        const val BANNER_MS = 8_000L
        const val MAX_RETRIES = 3
    }
}
