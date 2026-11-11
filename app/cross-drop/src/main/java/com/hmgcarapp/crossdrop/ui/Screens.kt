package com.hmgcarapp.crossdrop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.ui.rememberDriveSnapshot
import com.hmgcarapp.crossdrop.app.DropController
import com.hmgcarapp.crossdrop.app.NewQrPolicy
import com.hmgcarapp.crossdrop.app.SyncStatus
import com.hmgcarapp.crossdrop.app.UiState
import com.hmgcarapp.crossdrop.domain.DestPhase
import com.hmgcarapp.crossdrop.domain.InboxItem
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.domain.Page
import com.hmgcarapp.crossdrop.domain.PairStatus
import com.hmgcarapp.crossdrop.domain.Pairing
import com.hmgcarapp.crossdrop.domain.PrivacyText
import com.hmgcarapp.crossdrop.domain.Retention
import com.hmgcarapp.pleos.designsystem.component.PleosButton
import com.hmgcarapp.pleos.designsystem.component.PleosButtonHierarchy
import com.hmgcarapp.pleos.designsystem.component.PleosListItem
import com.hmgcarapp.pleos.designsystem.component.PleosPanel
import com.hmgcarapp.pleos.designsystem.component.PleosPopup
import com.hmgcarapp.pleos.designsystem.component.PleosScaffold
import com.hmgcarapp.pleos.designsystem.component.PleosSegmentedTabs
import com.hmgcarapp.pleos.designsystem.component.PleosStatus
import com.hmgcarapp.pleos.designsystem.component.PleosStatusBadge
import com.hmgcarapp.pleos.designsystem.component.rememberPleosToastState
import com.hmgcarapp.pleos.designsystem.theme.PleosSpacing
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun Txt(t: String, style: TextStyle = PleosTheme.typography.bodyMedium, secondary: Boolean = false, modifier: Modifier = Modifier) =
    Text(t, style = style, color = if (secondary) PleosTheme.colors.textDarkSecondary else PleosTheme.colors.textDarkPrimary, modifier = modifier)

private fun pairKind(s: PairStatus) = when (s) {
    PairStatus.CONNECTED -> PleosStatus.Positive
    PairStatus.WAITING -> PleosStatus.Active
    PairStatus.CODE_EXPIRED, PairStatus.ENDED -> PleosStatus.Negative
    PairStatus.NONE -> PleosStatus.Neutral
}

private fun sizeText(b: Long) = if (b >= 1_048_576) String.format(Locale.KOREA, "%.1fMB", b / 1_048_576.0) else "${maxOf(1L, b / 1024)}KB"

@Composable
fun CrossDropApp(c: DropController) {
    val s by c.state.collectAsState()
    val snap = rememberDriveSnapshot(c.signals)
    LaunchedEffect(snap.state) { c.onUiDriveState(snap.state) }
    // 첫 프레임에 정차 UI 가 잘못 보이지 않게 화면 쪽 스냅샷(초기값 DRIVING)과 컨트롤러 판정 중 하나라도 주행이면 주행 화면
    val driving = snap.state == DriveState.DRIVING || s.screen.driving
    val toast = rememberPleosToastState()
    // 안내 토스트는 정차 시에만. 새 항목 도착 안내는 아래 상단 패널(주행 중에도 표시)이 맡는다.
    LaunchedEffect(s.message, driving) { if (!driving) s.message?.let { toast.show(it); c.clearMessage() } }
    LaunchedEffect(driving) { if (driving) toast.dismiss() }
    val viewing = if (driving) null else s.screen.viewing?.let { id -> s.p.items.firstOrNull { it.id == id } }

    PleosScaffold(
        title = "크로스드롭",
        subtitle = if (driving) "주행 중" else "정차 중",
        onBack = if (viewing != null) ({ c.closeViewer() }) else null,
        toastState = toast,
        actions = { if (s.demo) PleosStatusBadge("시연", PleosStatus.Autonomous, Modifier.testTag("sim_badge")) },
    ) { _ ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
            TopNotices(s, c, driving)
            when {
                driving -> DrivingView(s)
                s.loading -> PleosPanel(Modifier.fillMaxWidth()) { Txt("불러오는 중…", secondary = true, modifier = Modifier.testTag("loading")) }
                viewing != null -> ViewerScreen(c, viewing)
                else -> Parked(c, s)
            }
        }
    }
}

// ---------------- 상단 안내: 새 항목 도착 + 목적지 안내 제안 (주행 중에도 표시되는 짧은 안내) ----------------
@Composable
private fun TopNotices(s: UiState, c: DropController, driving: Boolean) {
    s.banner?.let { b ->
        PleosPanel(Modifier.fillMaxWidth().testTag("banner"), title = "새 항목") { Txt(b, PleosTheme.typography.titleSmall, modifier = Modifier.testTag("banner_msg")) }
    }
    val o = s.dest.offer ?: return
    PleosPanel(Modifier.fillMaxWidth().testTag("offer_panel"), title = "목적지 안내") {
        Txt(o.label, PleosTheme.typography.titleMedium, modifier = Modifier.testTag("offer_label"))
        if (s.p.items.firstOrNull { it.id == o.itemId }?.demo == true) Txt("시연 목적지 (가상 링크, 경로 요청은 실제로 전송돼요)", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("offer_demo"))
        val line = when (s.dest.phase) {
            DestPhase.SPEAKING -> "안내할까요? 음성으로 질문하는 중이에요"
            DestPhase.LISTENING -> "‘응’ 또는 ‘아니’라고 말해 주세요"
            DestPhase.REQUESTING -> "경로를 요청하는 중…"
            DestPhase.RESULT -> s.dest.resultLine
            else -> if (s.dest.accepted) s.dest.resultLine else if (driving) null else "이 목적지로 안내할까요?"
        }
        line?.let { Txt(it, secondary = true, modifier = Modifier.testTag("offer_line")) }
        // 터치 선택은 정차 중에만(주행 중에는 음성만)
        if (!driving && !s.dest.accepted && s.dest.phase == DestPhase.DONE) {
            PleosButton("안내 시작", onClick = { c.touchAccept() }, modifier = Modifier.testTag("offer_accept"))
            PleosButton("취소", onClick = { c.touchDismiss() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("offer_dismiss"))
        }
    }
}

// ---------------- 주행 중: 읽기 전용 한 화면(사진·PDF·QR·설정·조작 요소 없음) ----------------
@Composable
private fun DrivingView(s: UiState) {
    PleosPanel(Modifier.fillMaxWidth().testTag("driving_view")) {
        Txt("정차(P단)하면 받은 사진·문서를 볼 수 있어요", PleosTheme.typography.titleMedium, modifier = Modifier.testTag("driving_msg"))
        Txt("받은 항목 ${s.p.items.size}개 · 목적지는 음성으로 안내해 드려요", PleosTheme.typography.bodyLarge, secondary = true, modifier = Modifier.testTag("driving_count"))
        if (s.player.playingId != null) Txt(if (s.player.paused) "음악 일시정지" else "음악 재생 중", PleosTheme.typography.bodyLarge, secondary = true, modifier = Modifier.testTag("driving_music"))
        Txt(s.sync.label, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("sync_status"))
        if (s.demo) Txt("시연 중: 가상 휴대폰과 가상 데이터예요", PleosTheme.typography.labelMedium, secondary = true)
    }
}

// ---------------- 정차 ----------------
@Composable
private fun Parked(c: DropController, s: UiState) {
    PleosSegmentedTabs(
        items = Page.entries.map { it.label }, selectedIndex = s.screen.page.ordinal,
        onSelect = { c.setPage(Page.entries[it]) }, modifier = Modifier.fillMaxWidth().testTag("page_tabs"),
    )
    when (s.screen.page) {
        Page.INBOX -> InboxPage(c, s)
        Page.PAIR -> PairPage(c, s)
        Page.PRIVACY -> PrivacyPage(c, s)
    }
}

@Composable
private fun InboxPage(c: DropController, s: UiState) {
    val fmt = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }
    val now = c.nowWall()
    if (s.player.playingId != null) PleosPanel(Modifier.fillMaxWidth().testTag("player_panel"), title = "재생 중") {
        Txt(if (s.player.paused) "일시정지" else "음악을 재생하고 있어요", secondary = true)
        if (s.player.paused) PleosButton("이어서 재생", onClick = { c.resumeMusic() }, modifier = Modifier.testTag("music_resume"))
        else PleosButton("일시정지", onClick = { c.pauseMusic() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("music_pause"))
        PleosButton("정지", onClick = { c.stopMusic() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("music_stop"))
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "받은 항목 ${s.p.items.size}개") {
        if (s.p.items.isEmpty()) {
            // 디자인시스템에 빈 상태 컴포넌트가 없어 글줄로 대체(docs/apps/cross-drop.md 기록)
            Txt("아직 받은 항목이 없어요. ‘휴대폰 연결’에서 QR 을 스캔해 보내 보세요.", secondary = true, modifier = Modifier.testTag("inbox_empty"))
        }
        s.p.items.asReversed().forEach { item -> ItemRow(c, s, item, fmt, now) }
        if (s.p.items.isNotEmpty()) Txt("받은 항목은 24시간 뒤 이 기기에서 자동으로 삭제돼요.", PleosTheme.typography.labelMedium, secondary = true)
    }
}

@Composable
private fun ItemRow(c: DropController, s: UiState, item: InboxItem, fmt: SimpleDateFormat, now: Long) {
    val remainH = ((Retention.remainingMs(item.receivedWallMs, now) + 3_599_999L) / 3_600_000L).toInt()
    val title = when (item.kind) {
        ItemKind.LINK -> item.dest?.name ?: if (item.dest != null) "공유받은 목적지" else "열 수 없는 링크"
        else -> "${item.kind.label} · ${fmt.format(Date(item.receivedWallMs))}"
    }
    val description = when (item.kind) {
        ItemKind.LINK -> item.note ?: if (item.used) "안내를 요청한 목적지" else "누르면 목적지 안내를 확인해요"
        else -> "${sizeText(item.sizeBytes)} · ${remainH}시간 후 삭제" + if (item.kind == ItemKind.AUDIO) " · 누르면 재생" else " · 누르면 열기"
    }
    PleosListItem(
        title = title, description = description, modifier = Modifier.testTag("item_${item.kind.code}"),
        trailing = {
            when {
                s.player.playingId == item.id -> PleosStatusBadge("재생 중", PleosStatus.Active)
                item.demo -> PleosStatusBadge("시연", PleosStatus.Autonomous)
                item.kind == ItemKind.LINK && item.dest == null -> PleosStatusBadge("사용 불가", PleosStatus.Negative)
                item.used -> PleosStatusBadge("사용함", PleosStatus.Neutral)
            }
        },
        onClick = { c.open(item.id) },
    )
}

@Composable
private fun ViewerScreen(c: DropController, item: InboxItem) {
    val file = remember(item.id) { c.fileOf(item) }
    PleosPanel(Modifier.fillMaxWidth().testTag("viewer_panel"), title = if (item.kind == ItemKind.PDF) "문서" else "사진") {
        if (file == null) Txt("파일을 찾을 수 없어요", secondary = true)
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val h = PleosTheme.window.appHeight * 0.7f
            val m = Modifier.size(width = maxWidth, height = h)
            if (item.kind == ItemKind.PDF) PdfViewer(file, m) else ImageViewer(file, m)
        }
        PleosButton("닫기", onClick = { c.closeViewer() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("viewer_close"))
    }
}

@Composable
private fun PairPage(c: DropController, s: UiState) {
    val pairing = s.pairing
    val status = if (s.demo) Pairing.status(pairing, c.nowWall()) else s.pairStatus
    var ask by remember { mutableStateOf(false) }
    var askNew by remember { mutableStateOf(false) }
    PleosPanel(Modifier.fillMaxWidth(), title = "휴대폰 연결 (아이폰·안드로이드 모두)") {
        PleosStatusBadge(Pairing.badgeLabel(status, s.sync == SyncStatus.ERROR), if (Pairing.connectionUnconfirmed(status, s.sync == SyncStatus.ERROR)) PleosStatus.Neutral else pairKind(status), Modifier.testTag("pair_status"))
        Txt(s.sync.label, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("sync_label"))
        when {
            s.demo -> Txt("시연 중이라 서버와 통신하지 않아요. 아래 QR 은 가상 주소예요.", PleosTheme.typography.labelMedium, secondary = true)
            !s.serverConfigured -> Txt("이 빌드에는 서버 주소가 없어 실제 연결은 하지 않아요. 아래 시연으로 전체 흐름을 볼 수 있어요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("no_server"))
            s.pairError != null -> Txt(s.pairError, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("pair_error"))
            s.sync == SyncStatus.ERROR -> Txt("서버에 연결하지 못했어요. 30초마다 다시 확인하고, 계속 안 되면 아래 시연으로 전체 흐름을 볼 수 있어요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("sync_error"))
            !s.p.consent -> Txt("연결을 시작하면 휴대폰이 보내는 파일·링크가 서버를 거쳐 이 차량으로 와요. 시작 전에는 서버와 통신하지 않아요.", PleosTheme.typography.labelMedium, secondary = true)
        }
        if (status == PairStatus.CODE_EXPIRED) Txt("QR 이 만료됐어요. ‘새 QR 만들기’로 다시 만들어 주세요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("qr_expired"))
        if (pairing != null && (status == PairStatus.WAITING || status == PairStatus.CONNECTED)) {
            if (status == PairStatus.WAITING) {
                val payload = Pairing.qrPayload(if (s.demo) com.hmgcarapp.crossdrop.app.DemoAssets.DEMO_BASE_URL else c.serverBase().orEmpty(), pairing.code)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val side = minOf(PleosTheme.window.appHeight * 0.5f, maxWidth)
                    QrView(payload, Modifier.size(side))
                }
                Txt("휴대폰 카메라로 QR 을 스캔하세요", PleosTheme.typography.titleSmall, modifier = Modifier.testTag("qr_hint"))
                if (!s.demo && c.serverBase()?.startsWith("https:") == false) Txt("이 QR 은 이 기기 자신(루프백) 주소라 실제 폰에서는 열리지 않아요. 폰 연결에는 https 서버 주소가 필요해요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("qr_local_note"))
            Txt("QR 을 읽지 못하면 휴대폰을 화면에 더 가까이 대고 화면 밝기를 확인해 주세요. 이 QR 은 10분 안에 한 번만 쓸 수 있어요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("qr_hint_text"))
            } else Txt("휴대폰에서 파일·링크를 보내면 여기에 도착해요. 휴대폰이 30분 넘게 연결돼 있었다면 ‘새 QR 만들기’로 다시 연결해 주세요.", secondary = true)
        }
        if (!s.demo && s.serverConfigured) {
            // 연결된 뒤에도 새 QR 을 만들 수 있다(휴대폰의 업로드 권한은 연결 후 30분만 유효)
            if (pairing == null || status == PairStatus.CODE_EXPIRED || status == PairStatus.ENDED || status == PairStatus.CONNECTED)
                PleosButton(if (s.p.consent) "새 QR 만들기" else "휴대폰 연결 시작", onClick = { if (!s.p.consent) ask = true else if (NewQrPolicy.needsConfirm(status)) askNew = true else c.startPairing(true) }, enabled = !s.busy, modifier = Modifier.testTag("btn_pair"))
            if (pairing != null) PleosButton("연결 끊기", onClick = { c.endPairing() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("btn_unpair"))
        }
    }
    if (askNew) PleosPopup(
        title = "새 QR 을 만들까요?",
        description = "이전 연결은 서버에서 지워져요. ${NewQrPolicy.WARNING}.",
        confirmLabel = "새 QR 만들기", onConfirm = { askNew = false; c.startPairing(true) }, onDismissRequest = { askNew = false }, cancelLabel = "취소",
    )
    if (ask) PleosPopup(
        title = "휴대폰 연결을 시작할까요?",
        confirmLabel = "동의하고 시작", onConfirm = { ask = false; c.startPairing(true) },
        onDismissRequest = { ask = false }, cancelLabel = "취소",
        // description 은 maxLines=3 으로 말줄임되므로 content 슬롯에 전체 고지를 문단별로 넣는다(높이 제한+본문만 스크롤)
        content = {
            Column(Modifier.heightIn(max = PleosTheme.dimensions.popupMaxHeight * 0.5f).verticalScroll(rememberScrollState()).testTag("consent_body"), verticalArrangement = Arrangement.spacedBy(PleosSpacing.s)) {
                PrivacyText.CONSENT_PARAGRAPHS.forEach { Txt(it, PleosTheme.typography.bodyMedium, secondary = true) }
                Txt(PrivacyText.destination(c.serverHostPort()), PleosTheme.typography.bodyMedium, secondary = true, modifier = Modifier.testTag("consent_dest"))
            }
        },
    )
    HelpPanel()
    DemoPanel(c, s)
}

/** 스마트폰 연결 도우미(블루투스·프로젝션 진단)는 SDK 가 없어 만들지 않고 정적 안내로 대신한다. */
@Composable
private fun HelpPanel() {
    PleosPanel(Modifier.fillMaxWidth().testTag("help_panel"), title = "연결이 안 될 때") {
        Txt("이 앱은 휴대폰과 블루투스·화면 미러링으로 연결하지 않아요. 휴대폰과 차량이 모두 인터넷에 연결돼 있으면 돼요.", PleosTheme.typography.labelMedium, secondary = true)
        Txt("QR 은 10분 안에 한 번만 쓸 수 있어요. 휴대폰 기본 카메라로 스캔하고, 안 되면 ‘새 QR 만들기’를 눌러 주세요.", PleosTheme.typography.labelMedium, secondary = true)
        Txt("지도 링크는 단축 주소(kko.to, naver.me, goo.gl 등)가 아니라 전체 주소만 보낼 수 있어요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("help_full_url"))
        Txt("아이폰·안드로이드 모두 브라우저에서 사진·PDF·음악 파일과 지도 공유 링크(카카오맵·네이버지도·구글지도·Apple 지도)를 보낼 수 있어요. 별도 앱 설치는 필요 없어요.", PleosTheme.typography.labelMedium, secondary = true)
    }
}

@Composable
private fun DemoPanel(c: DropController, s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "시연 모드") {
        Txt("서버 없이 가상 휴대폰으로 받은 사진·PDF·음악·목적지 흐름을 보여줘요. 가상 데이터는 저장하지 않고 서버로 보내지 않아요.", PleosTheme.typography.labelMedium, secondary = true)
        if (!s.demo) PleosButton("시연 시작 (가상 QR)", onClick = { c.startDemo() }, modifier = Modifier.testTag("btn_demo"))
        else {
            PleosButton("사진 받기", onClick = { c.demoReceive("photo") }, modifier = Modifier.testTag("demo_photo"))
            PleosButton("PDF 받기", onClick = { c.demoReceive("pdf") }, modifier = Modifier.testTag("demo_pdf"))
            PleosButton("음악 받기", onClick = { c.demoReceive("music") }, modifier = Modifier.testTag("demo_music"))
            PleosButton("목적지 링크 받기 (카카오맵)", onClick = { c.demoReceive("kakao") }, modifier = Modifier.testTag("demo_kakao"))
            PleosButton("단축 링크 받기 (열 수 없음)", onClick = { c.demoReceive("short") }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("demo_short"))
            PleosButton("시연 끝내기 (가상 항목 삭제)", onClick = { c.stopDemo() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("demo_stop"))
        }
        if (c.debuggable) Txt("adb: am broadcast -a com.hmgcarapp.crossdrop.SIM -p com.hmgcarapp.crossdrop --ez demo true", PleosTheme.typography.labelSmall, secondary = true)
    }
}

@Composable
private fun PrivacyPage(c: DropController, s: UiState) {
    var askAll by remember { mutableStateOf(false) }
    var askRevoke by remember { mutableStateOf(false) }
    PleosPanel(Modifier.fillMaxWidth(), title = "서버로 보내는 것 (연결에 동의한 뒤에만)") {
        PleosListItem(title = "연결 요청", description = "연결 번호표(1회용 코드)를 만들어 달라는 요청. 이름·계정·기기 번호는 보내지 않아요")
        PleosListItem(title = "수신함 확인", description = "5초마다 새 항목이 있는지 묻는 요청(연결용 비밀 토큰 포함)")
        PleosListItem(title = "받은 뒤 삭제 요청", description = "차량이 받은 항목을 서버에서 바로 지우라는 요청")
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "서버가 보관하는 것") {
        // PleosListItem 의 설명은 한 줄 말줄임이라 운영자 열람 고지가 잘린다 → 줄바꿈되는 Txt 로 전부 표시
        Txt(PrivacyText.PRIVACY_TAB_HOLDS, secondary = true)
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "이 기기에 저장하는 것") {
        PleosListItem(title = "받은 사진·PDF·음악", description = "앱 전용 저장소에만, 원래 파일 이름 없이 저장. 24시간 뒤 자동 삭제")
        PleosListItem(title = "목적지", description = "링크에서 읽은 장소 이름과 좌표만(링크 주소는 저장 안 함). 24시간 뒤 삭제")
        PleosListItem(title = "연결 토큰", description = "서버 연결을 유지하는 비밀값. 연결을 끊으면 삭제")
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "삭제") {
        if (s.corruptKept) Txt("저장 파일이 손상돼 처음 상태로 시작했어요.", PleosTheme.typography.labelMedium, secondary = true)
        if (s.saveHeld) Txt("저장하지 못해 이번 실행에서는 저장하지 않아요. 앱을 다시 시작하면 다시 저장해요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("save_held"))
        PleosButton("받은 항목 모두 삭제", onClick = { askAll = true }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("clear_all"))
        PleosButton("서버 연결 끊고 동의 철회", onClick = { askRevoke = true }, hierarchy = PleosButtonHierarchy.Secondary, enabled = s.p.consent || s.p.pairing != null, modifier = Modifier.testTag("revoke"))
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "알아두세요") {
        Txt("사진·PDF·QR 은 정차(P단) 중에만 볼 수 있고 주행을 시작하면 자동으로 닫혀요. 주행 중에는 음성 안내와 음악 재생만 해요. 모르는 사람이 보낸 파일을 열지 마세요. 이 앱은 영상을 재생하지 않아요.", PleosTheme.typography.labelMedium, secondary = true)
    }
    if (askAll) PleosPopup(
        title = "받은 항목을 모두 삭제할까요?",
        description = "받은 사진·PDF·음악·목적지가 이 기기에서 삭제되고, 서버에 남은 내 연결도 지우도록 요청해요.\n되돌릴 수 없어요.",
        confirmLabel = "삭제", onConfirm = { askAll = false; c.deleteAll() }, onDismissRequest = { askAll = false }, cancelLabel = "취소",
    )
    if (askRevoke) PleosPopup(
        title = "서버 연결을 끊을까요?",
        description = "휴대폰 연결을 끊고 서버에 남은 파일을 지우도록 요청한 뒤, 다시 연결할 때 동의를 새로 받아요. 받은 항목은 그대로예요.",
        confirmLabel = "끊기", onConfirm = { askRevoke = false; c.revokeConsent() }, onDismissRequest = { askRevoke = false }, cancelLabel = "취소",
    )
}
