package com.hmgcarapp.crossdrop

import com.hmgcarapp.crossdrop.domain.PairStatus
import com.hmgcarapp.crossdrop.domain.PairSteps
import com.hmgcarapp.crossdrop.domain.PrivacyText
import com.hmgcarapp.crossdrop.domain.StepState
import com.hmgcarapp.crossdrop.domain.StepState.CURRENT
import com.hmgcarapp.crossdrop.domain.StepState.DONE
import com.hmgcarapp.crossdrop.domain.StepState.FAILED
import com.hmgcarapp.crossdrop.domain.StepState.PENDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 연결 단계 표시(기존 상태 값 조합)와 받은 항목 화면의 반납 전 삭제 안내. */
class PairStepsAndReturnNoticeTest {
    private fun states(status: PairStatus, failed: Boolean = false, busy: Boolean = false): List<StepState> = PairSteps.of(status, failed, busy).map { it.state }
    private val ui = File("src/main/java/com/hmgcarapp/crossdrop/ui/Screens.kt").readText()

    @Test fun fourStepsInOrder() {
        assertEquals(listOf("서버 확인", "QR 표시", "폰 연결 대기", "연결됨"), PairSteps.LABELS)
        assertEquals(listOf(1, 2, 3, 4), PairSteps.of(PairStatus.NONE, false, false).map { it.no })
    }

    @Test fun progressFollowsExistingStatus() {
        assertEquals(listOf(PENDING, PENDING, PENDING, PENDING), states(PairStatus.NONE))
        assertEquals(listOf(CURRENT, PENDING, PENDING, PENDING), states(PairStatus.NONE, busy = true))
        assertEquals(listOf(DONE, DONE, CURRENT, PENDING), states(PairStatus.WAITING))
        assertEquals(listOf(DONE, DONE, DONE, DONE), states(PairStatus.CONNECTED))
        assertEquals(listOf(DONE, FAILED, PENDING, PENDING), states(PairStatus.CODE_EXPIRED))
        assertEquals(listOf(PENDING, PENDING, PENDING, PENDING), states(PairStatus.ENDED))
    }

    @Test fun failureMarksTheCurrentStepOnly() {
        assertEquals(listOf(FAILED, PENDING, PENDING, PENDING), states(PairStatus.NONE, failed = true))
        assertEquals(listOf(DONE, DONE, FAILED, PENDING), states(PairStatus.WAITING, failed = true))
        assertEquals(listOf(DONE, DONE, DONE, FAILED), states(PairStatus.CONNECTED, failed = true))
        assertEquals("만료는 서버 오류와 별개로 2단계 실패", listOf(DONE, FAILED, PENDING, PENDING), states(PairStatus.CODE_EXPIRED, failed = true))
    }

    @Test fun textTellsStateWithoutRelyingOnColor() {
        val w = PairSteps.of(PairStatus.WAITING, false, false).map { it.text }
        assertEquals(listOf("1 서버 확인 완료", "2 QR 표시 완료", "3 폰 연결 대기 중", "4 연결됨"), w)
        assertEquals("4 연결됨", PairSteps.of(PairStatus.CONNECTED, false, false)[3].text)
        assertEquals("3 폰 연결 확인 실패", PairSteps.of(PairStatus.WAITING, true, false)[2].text)
        assertEquals("2 QR 만료", PairSteps.of(PairStatus.CODE_EXPIRED, false, false)[1].text)
    }

    @Test fun screenShowsStepsOnlyForRealOrDemoConnectionAndFailureMatchesGuidance() {
        assertTrue(ui.contains("PairSteps.of(status, failed, s.busy && pairing == null)"))
        assertTrue(ui.contains("if (s.demo || s.serverConfigured)"))
        // 실패는 한국어 실패 안내(pair_error / sync_error)가 켜지는 조건과 같다
        assertTrue(ui.contains("val failed = !s.demo && (s.pairError != null || s.sync == SyncStatus.ERROR)"))
        assertTrue(ui.contains("testTag(\"pair_error\")") && ui.contains("testTag(\"sync_error\")"))
    }

    @Test fun expiredStateIsKeptUntilNextAttempt() {
        val root = java.io.File("src/main/java/com/hmgcarapp/crossdrop")
        val c = java.io.File(root, "app/DropController.kt").readText(); val scr = java.io.File(root, "ui/Screens.kt").readText()
        assertTrue("미연결 세션 종료(만료)에서 켠다", c.contains("val unclaimed = !s.p.pairing.claimed; endPairingInternal(); if (unclaimed) _state.update { it.copy(pairExpired = true) }"))
        assertTrue("폴링 종료·401 에서도 켠다", c.split("pairExpired = unclaimed || it.pairExpired").size - 1 == 2)
        assertTrue("새 시도·연결 끊기·시연에서 끈다", c.contains("busy = true, pairError = null, pairExpired = false") && c.contains("pairError = null, pairExpired = false) }") && c.contains("pairError = null, pairExpired = false, demo = true"))
        assertTrue("세션 정보가 없어도 만료 단계·안내를 유지한다", scr.contains("s.pairExpired && pairing == null && rawStatus == PairStatus.NONE) PairStatus.CODE_EXPIRED"))
        assertEquals("만료 유지 시 단계 2 실패 표시", "2 QR 만료", PairSteps.of(PairStatus.CODE_EXPIRED, false, false)[1].text)
    }

    @Test fun initialStepsAreNeutralPendingNotFalseProgress() {
        // 연결 시도 전(NONE, 실패·진행 중 아님)에는 4개 모두 '대기' 라 진행처럼 읽히지 않는다
        assertEquals(List(4) { PENDING }, PairSteps.of(PairStatus.NONE, false, false).map { it.state })
        assertEquals(listOf("1 서버 확인", "2 QR 표시", "3 폰 연결 대기", "4 연결됨"), PairSteps.of(PairStatus.NONE, false, false).map { it.text })   // 완료·진행 중·실패 접미사 없음
    }

    @Test fun returnNoticeAndClearButtonOnInboxUseTheSameDeleteFlow() {
        assertEquals("공유차·렌터카를 반납하기 전에 받은 항목을 모두 삭제하세요.", PrivacyText.RETURN_NOTICE)
        val inbox = ui.substring(ui.indexOf("private fun InboxPage"), ui.indexOf("private fun ItemRow"))
        assertTrue(inbox.contains("PrivacyText.RETURN_NOTICE") && inbox.contains("받은 항목 모두 삭제") && inbox.contains("ClearAllPopup(c)"))
        assertTrue("항목이 있을 때만", inbox.indexOf("s.p.items.isNotEmpty()") in 0 until inbox.indexOf("RETURN_NOTICE"))
        // 삭제 확인 팝업은 설정 화면과 하나를 공유하고, 확인 시 기존 deleteAll(정차 가드) 만 호출한다
        assertEquals(2, Regex("ClearAllPopup\\(c\\)").findAll(ui).count())
        assertEquals(1, Regex("c\\.deleteAll\\(\\)").findAll(ui).count())
        // 받은 항목 페이지는 정차 화면(Parked) 안에서만 그려진다
        assertTrue(ui.indexOf("Page.INBOX -> InboxPage(c, s)") > ui.indexOf("private fun Parked"))
        assertFalse(ui.substring(ui.indexOf("private fun DrivingView"), ui.indexOf("private fun Parked")).contains("deleteAll"))
    }
}
