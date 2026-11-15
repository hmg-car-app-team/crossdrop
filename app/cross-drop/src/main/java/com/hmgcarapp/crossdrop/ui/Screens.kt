package com.hmgcarapp.crossdrop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.hmgcarapp.crossdrop.app.SyncStatus
import com.hmgcarapp.crossdrop.app.UiState
import com.hmgcarapp.crossdrop.domain.DestPhase
import com.hmgcarapp.crossdrop.domain.InboxItem
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.domain.Page
import com.hmgcarapp.crossdrop.domain.PairStatus
import com.hmgcarapp.crossdrop.domain.PairStep
import com.hmgcarapp.crossdrop.domain.PairSteps
import com.hmgcarapp.crossdrop.domain.StepState
import com.hmgcarapp.crossdrop.domain.Pairing
import com.hmgcarapp.crossdrop.domain.PrivacyText
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

private fun stepKind(st: PairStep) = when (st.state) {
    StepState.DONE -> PleosStatus.Positive
    StepState.CURRENT -> PleosStatus.Active
    StepState.FAILED -> PleosStatus.Negative
    StepState.PENDING -> PleosStatus.Neutral
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
    }
}

@Composable
private fun InboxPage(c: DropController, s: UiState) {
    val fmt = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }
    val now = c.nowWall()
    if (s.player.playingId != null) PleosPanel(Modifier.fillMaxWidth().testTag("player_panel"), title = "재생 중") {
        if (s.player.paused) PleosButton("이어서 재생", onClick = { c.resumeMusic() }, modifier = Modifier.testTag("music_resume"))
        else PleosButton("일시정지", onClick = { c.pauseMusic() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("music_pause"))
        PleosButton("정지", onClick = { c.stopMusic() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("music_stop"))
    }
    var askAll by remember { mutableStateOf(false) }
    PleosPanel(Modifier.fillMaxWidth(), title = "받은 항목 ${s.p.items.size}개") {
        if (s.p.items.isEmpty()) {
            // 디자인시스템에 빈 상태 컴포넌트가 없어 글줄로 대체(docs/apps/cross-drop.md 기록)
            Txt("아직 받은 항목이 없어요", secondary = true, modifier = Modifier.testTag("inbox_empty"))
        }
        s.p.items.asReversed().forEach { item -> ItemRow(c, s, item, fmt, now) }
        if (s.p.items.isNotEmpty()) {
            // 정차 중에만 보이는 화면이다(주행 중에는 DrivingView). 데이터 삭제 진입점은 여기뿐이다.
            PleosButton("받은 항목 모두 삭제", onClick = { askAll = true }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("inbox_clear_all"))
        }
    }
    if (askAll) ClearAllPopup(c) { askAll = false }
}

@Composable
private fun ClearAllPopup(c: DropController, onClose: () -> Unit) = PleosPopup(
    title = "받은 항목을 모두 삭제할까요?",
    description = "받은 사진·PDF·음악·목적지가 이 기기에서 삭제되고, 서버에 남은 내 연결도 지우도록 요청해요.",
    confirmLabel = "삭제", onConfirm = { onClose(); c.deleteAll() }, onDismissRequest = onClose, cancelLabel = "취소",
)

@Composable
private fun ItemRow(c: DropController, s: UiState, item: InboxItem, fmt: SimpleDateFormat, now: Long) {
    val title = when (item.kind) {
        ItemKind.LINK -> item.dest?.name ?: if (item.dest != null) "공유받은 목적지" else "열 수 없는 링크"
        else -> "${item.kind.label} · ${fmt.format(Date(item.receivedWallMs))}"
    }
    val description = when (item.kind) {
        ItemKind.LINK -> item.note
        else -> sizeText(item.sizeBytes)
    }
    val trailing: @Composable () -> Unit = { if (item.used) PleosStatusBadge("사용함", PleosStatus.Neutral) }
    if (description.isNullOrBlank()) {
        // 보조 줄이 없는 행(목적지)은 표준 목록 행의 최소 높이(보조 줄 자리)를 두지 않고 한 줄 높이로 그린다
        Row(
            Modifier.fillMaxWidth().testTag("item_${item.kind.code}").clickable(role = Role.Button) { c.open(item.id) }.padding(PleosSpacing.m),
            horizontalArrangement = Arrangement.spacedBy(PleosSpacing.s), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = PleosTheme.typography.bodyMedium, color = PleosTheme.colors.textDarkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing()
        }
    } else PleosListItem(
        title = title, description = description, modifier = Modifier.testTag("item_${item.kind.code}"),
        trailing = trailing,
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
    val rawStatus = if (s.demo) Pairing.status(pairing, c.nowWall()) else s.pairStatus
    // 세션이 끝나 정보가 사라져도 만료 표시는 다음 시도까지 유지한다(pairError 와 같은 설계)
    val status = if (!s.demo && s.pairExpired && pairing == null && rawStatus == PairStatus.NONE) PairStatus.CODE_EXPIRED else rawStatus
    var ask by remember { mutableStateOf(false) }
    PleosPanel(Modifier.fillMaxWidth(), title = "휴대폰 연결") {
        PleosStatusBadge(Pairing.badgeLabel(status, s.sync == SyncStatus.ERROR), if (Pairing.connectionUnconfirmed(status, s.sync == SyncStatus.ERROR)) PleosStatus.Neutral else pairKind(status), Modifier.testTag("pair_status"))
        if (s.demo || s.serverConfigured) {
            val failed = !s.demo && (s.pairError != null || s.sync == SyncStatus.ERROR)
            Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.s), modifier = Modifier.testTag("pair_steps")) {
                PairSteps.of(status, failed, s.busy && pairing == null).forEach { PleosStatusBadge(it.text, stepKind(it), Modifier.testTag("pair_step_${it.no}")) }
            }
        }
        if (pairing != null && (status == PairStatus.WAITING || status == PairStatus.CONNECTED)) {
            if (status == PairStatus.WAITING) {
                val payload = Pairing.qrPayload(if (s.demo) com.hmgcarapp.crossdrop.app.DemoAssets.DEMO_BASE_URL else c.serverBase().orEmpty(), pairing.code)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val side = minOf(PleosTheme.window.appHeight * 0.5f, maxWidth)
                    QrView(payload, Modifier.size(side))
                }
            }
        }
        if (!s.demo && s.serverConfigured) {
            // 연결된 뒤에도 새 QR 을 만들 수 있다(휴대폰의 업로드 권한은 연결 후 30분만 유효)
            if (pairing == null || status == PairStatus.CODE_EXPIRED || status == PairStatus.ENDED || status == PairStatus.CONNECTED)
                PleosButton(if (s.p.consent) "새 QR 만들기" else "휴대폰 연결 시작", onClick = { if (!s.p.consent) ask = true else c.startPairing(true) }, enabled = !s.busy, modifier = Modifier.testTag("btn_pair"))
            if (pairing != null || s.p.consent) PleosButton("연결 끊기", onClick = { c.revokeConsent() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("btn_unpair"))
        }
    }
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
    DemoPanel(c, s)
}

@Composable
private fun DemoPanel(c: DropController, s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "시연 모드") {
        if (!s.demo) PleosButton("시연 시작", onClick = { c.startDemo() }, modifier = Modifier.testTag("btn_demo"))
        else {
            PleosButton("사진", onClick = { c.demoReceive("photo") }, modifier = Modifier.testTag("demo_photo"))
            PleosButton("PDF", onClick = { c.demoReceive("pdf") }, modifier = Modifier.testTag("demo_pdf"))
            PleosButton("음악", onClick = { c.demoReceive("music") }, modifier = Modifier.testTag("demo_music"))
            PleosButton("목적지 링크", onClick = { c.demoReceive("kakao") }, modifier = Modifier.testTag("demo_kakao"))
            PleosButton("시연 끝내기", onClick = { c.stopDemo() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("demo_stop"))
        }
    }
}
