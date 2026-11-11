package com.hmgcarapp.crossdrop

import com.hmgcarapp.crossdrop.domain.PairStatus
import com.hmgcarapp.crossdrop.domain.Pairing
import com.hmgcarapp.crossdrop.domain.PrivacyText
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
        val popup = ui.substring(start, ui.indexOf("HelpPanel()", start))
        assertTrue(popup.contains("PrivacyText.CONSENT_PARAGRAPHS.forEach"))
        assertTrue(popup.contains("content = {") && popup.contains("verticalScroll(") && popup.contains("popupMaxHeight"))
        assertFalse("description(3줄 말줄임) 사용 금지", popup.contains("description ="))
    }

    @Test fun consentTextCoversServerRetentionAndOperatorVisibility() {
        val all = PrivacyText.CONSENT_PARAGRAPHS.joinToString(" ")
        assertEquals(5, PrivacyText.CONSENT_PARAGRAPHS.size)
        assertTrue(all.contains("서버 운영자는 보관 중인 파일을 볼 수 있어요"))
        assertTrue(all.contains("늦어도 24시간"))
        assertTrue(all.contains("언제든 끊고 지울 수 있어요"))
        assertTrue(all.contains("이미 받은 항목은 그대로"))
    }

    @Test fun privacyTabListsWhatTheServerHolds() {
        assertTrue(ui.contains("서버가 보관하는 것") && ui.contains("Txt(PrivacyText.PRIVACY_TAB_HOLDS"))
        // PleosListItem 설명은 한 줄 말줄임이라 운영자 열람 고지가 잘림 — 쓰지 않는다
        assertFalse(ui.contains("description = PrivacyText.PRIVACY_TAB_HOLDS"))
        assertTrue(PrivacyText.PRIVACY_TAB_HOLDS.contains("24시간") && PrivacyText.PRIVACY_TAB_HOLDS.contains("서버 운영자"))
    }

    @Test fun naviTimeoutTextDoesNotClaimNoResponse() {
        assertFalse(NaviText.NO_RESPONSE.contains("응답이 없"))
        assertTrue(NaviText.NO_RESPONSE.contains("확인하지 못했어요") && NaviText.NO_RESPONSE.contains("경로를 확인해 주세요"))
    }

    @Test fun connectedBadgeIsNotShownWhileServerErrors() {
        assertEquals("휴대폰 연결됨", Pairing.badgeLabel(PairStatus.CONNECTED, false))
        assertEquals("연결 상태를 확인하지 못했어요", Pairing.badgeLabel(PairStatus.CONNECTED, true))
        assertEquals("휴대폰 연결을 기다리는 중", Pairing.badgeLabel(PairStatus.WAITING, true))
        assertTrue(ui.contains("Pairing.badgeLabel(status, s.sync == SyncStatus.ERROR)"))
    }

    @Test fun expiredQrShowsAHint() { assertTrue(ui.contains("qr_expired") && ui.contains("QR 이 만료됐어요. ")) }
}
