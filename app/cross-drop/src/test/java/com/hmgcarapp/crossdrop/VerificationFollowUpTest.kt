package com.hmgcarapp.crossdrop

import com.hmgcarapp.crossdrop.domain.PairStatus
import com.hmgcarapp.crossdrop.domain.Pairing
import com.hmgcarapp.crossdrop.domain.PrivacyText
import com.hmgcarapp.crossdrop.domain.DestEvent
import com.hmgcarapp.crossdrop.domain.DestFlow
import com.hmgcarapp.crossdrop.domain.DestOffer
import com.hmgcarapp.crossdrop.domain.DestState
import com.hmgcarapp.crossdrop.domain.Destination
import com.hmgcarapp.crossdrop.navi.NaviReducer
import com.hmgcarapp.crossdrop.navi.NaviStatus
import com.hmgcarapp.crossdrop.navi.NaviText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 독립 런타임 검증 후속 수정(D-1·D-2·D-3·D-5·D-8) 고정. */
class VerificationFollowUpTest {
    private val ui = File("src/main/java/com/hmgcarapp/crossdrop/ui/Screens.kt").readText()

    @Test fun consentPopupRendersEveryParagraphInScrollableContentNotThreeLineDescription() {
        val start = ui.indexOf("if (ask) PleosPopup(")
        val popup = ui.substring(start, ui.indexOf("DemoPanel(c, s)", start))
        assertTrue(popup.contains("PrivacyText.CONSENT_PARAGRAPHS.forEach"))
        assertTrue(popup.contains("content = {") && popup.contains("verticalScroll(") && popup.contains("popupMaxHeight"))
        assertFalse("description(3줄 말줄임) 사용 금지", popup.contains("description ="))
    }

    @Test fun consentTextIsTwoShortFacts() {
        val all = PrivacyText.CONSENT_PARAGRAPHS.joinToString(" ")
        assertEquals(2, PrivacyText.CONSENT_PARAGRAPHS.size)
        assertTrue(all.contains("릴레이 서버를 거쳐 차량으로") && all.contains("연결을 끊으면 전달이 멈춰요"))
        assertFalse(all.contains("운영자") || all.contains("암호화"))
        assertFalse("개인정보 탭은 제거됨", ui.contains("서버가 보관하는 것") || ui.contains("PRIVACY_TAB_HOLDS"))
    }

    @Test fun naviTimeoutTextDoesNotClaimNoResponse() {
        // 무응답은 사용자 문구가 없다(내비가 실제로 안내를 시작했을 수 있으므로 실패로 표시하지 않음)
        assertEquals("", NaviText.NO_RESPONSE)
        val timeout = NaviReducer.onTimeout(NaviStatus.Pending("경로", 3L), 3L)
        assertTrue(NaviText.isNoResponse(timeout))
        assertTrue(NaviText.isNoResponse(null))
        assertEquals("", NaviText.spoken(timeout))
        assertEquals("", NaviText.spoken(null))
        assertFalse(NaviText.spoken(timeout).contains("실패"))
        // 확정 실패만 '요청에 실패했어요'
        val err = NaviReducer.onError(NaviStatus.Pending("경로", 4L), "CAUSE_SEARCH_ROUTE")
        assertFalse(NaviText.isNoResponse(err))
        assertEquals("요청에 실패했어요", NaviText.spoken(err))
        assertEquals("요청에 실패했어요", NaviText.REQUEST_FAILED)
        assertEquals("원인별 사유 표 없음: 모두 한 문장", NaviText.REQUEST_FAILED, NaviText.NOT_CONNECTED)
        assertEquals(NaviText.REQUEST_FAILED, NaviText.errorMessage("CAUSE_LOW_BATTERY"))
        val notConn = NaviReducer.failUnlessPending(NaviStatus.Idle, NaviText.NOT_CONNECTED, 5L, "NOT_CONNECTED")
        assertEquals("요청에 실패했어요", NaviText.spoken(notConn))
        assertFalse(NaviText.isNoResponse(notConn))
    }

    @Test fun noResponseLeavesNoResultLineInDestCard() {
        val offer = DestOffer("i1", Destination(37.5, 127.0, "강남역"), voice = true)
        val (s0, _) = DestFlow.reduce(DestState(), DestEvent.Propose(offer))
        val id = s0.inFlightId!!
        val (s1, fx) = DestFlow.reduce(s0, DestEvent.NaviResult(id, null, NaviText.spoken(null)))
        assertEquals(null, s1.resultLine)
        val (s2, _) = DestFlow.reduce(s0, DestEvent.NaviResult(id, false, NaviText.spoken(NaviStatus.Failed(NaviText.REQUEST_FAILED))))
        assertEquals("요청에 실패했어요", s2.resultLine)
        assertTrue(fx.size == 1)
    }

    @Test fun connectedBadgeIsNotShownWhileServerErrors() {
        assertEquals("휴대폰 연결됨", Pairing.badgeLabel(PairStatus.CONNECTED, false))
        assertEquals("연결 상태를 확인하지 못했어요", Pairing.badgeLabel(PairStatus.CONNECTED, true))
        assertEquals("휴대폰 연결을 기다리는 중", Pairing.badgeLabel(PairStatus.WAITING, true))
        assertTrue(ui.contains("Pairing.badgeLabel(status, s.sync == SyncStatus.ERROR)"))
    }

    @Test fun expiredStateIsShownByStepBadgeOnly() { assertTrue(ui.contains("PairStatus.CODE_EXPIRED") && !ui.contains("qr_expired")) }

    @Test fun sideExplanationsAreRemovedFromTheUi() {
        for (gone in listOf("HelpPanel", "help_panel", "no_server", "sync_error", "qr_local_note", "qr_hint_text", "sim_badge", "offer_demo",
            "실제 폰에서는 열리지 않아요", "연결이 안 될 때", "adb: am broadcast", "알아두세요", "시연 중: 가상 휴대폰", "서버 없이 가상 휴대폰으로",
            "아이폰·안드로이드", "휴대폰 카메라로 QR", "qr_hint", "누르면", "(가상 QR)", "(카카오맵)", "(가상 항목 삭제)", "되돌릴 수 없어요", "음악을 재생하고 있어요", "안내를 요청한 목적지"))
            assertFalse("제거된 부수 문구: $gone", ui.contains(gone))
        assertEquals("전송 대상: 127.0.0.1:8780", PrivacyText.destination("127.0.0.1:8780"))
        assertFalse(PrivacyText.destination("127.0.0.1:8780").contains("공개 배포용"))
        assertTrue("동의 팝업의 사실 고지는 유지", ui.contains("consent_dest") && PrivacyText.CONSENT_PARAGRAPHS.size == 2)
    }
}
