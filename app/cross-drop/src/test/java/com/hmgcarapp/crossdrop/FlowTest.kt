package com.hmgcarapp.crossdrop

import com.hmgcarapp.crossdrop.domain.DestEffect
import com.hmgcarapp.crossdrop.domain.DestEvent
import com.hmgcarapp.crossdrop.domain.DestFlow
import com.hmgcarapp.crossdrop.domain.DestOffer
import com.hmgcarapp.crossdrop.domain.DestPhase
import com.hmgcarapp.crossdrop.domain.DestState
import com.hmgcarapp.crossdrop.domain.Destination
import com.hmgcarapp.crossdrop.domain.Heard
import com.hmgcarapp.crossdrop.domain.Josa
import com.hmgcarapp.crossdrop.domain.Page
import com.hmgcarapp.crossdrop.domain.PlayerEvent
import com.hmgcarapp.crossdrop.domain.PlayerFlow
import com.hmgcarapp.crossdrop.domain.PlayerState
import com.hmgcarapp.crossdrop.domain.ScreenEvent
import com.hmgcarapp.crossdrop.domain.ScreenFlow
import com.hmgcarapp.crossdrop.domain.ScreenState
import com.hmgcarapp.crossdrop.domain.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFlowTest {
    private fun parked(s: ScreenState = ScreenState()) = ScreenFlow.reduce(s, ScreenEvent.DriveChanged(false))

    @Test fun startsDrivingFailSafe_andIgnoresEverything() {
        val s = ScreenState()
        assertTrue(s.driving)
        assertEquals(s, ScreenFlow.reduce(s, ScreenEvent.SetPage(Page.PAIR)))
        assertEquals(s, ScreenFlow.reduce(s, ScreenEvent.Open("a")))
    }

    @Test fun parkedAllowsPagesAndViewer() {
        var s = parked()
        s = ScreenFlow.reduce(s, ScreenEvent.SetPage(Page.PAIR)); assertEquals(Page.PAIR, s.page)
        s = ScreenFlow.reduce(s, ScreenEvent.Open("img1")); assertEquals("img1", s.viewing)
        s = ScreenFlow.reduce(s, ScreenEvent.SetPage(Page.PRIVACY)); assertNull("페이지를 바꾸면 열람은 닫힌다", s.viewing)
    }

    @Test fun drivingStartClosesViewerQrAndSettings() {
        var s = parked()
        s = ScreenFlow.reduce(s, ScreenEvent.SetPage(Page.PAIR))
        s = ScreenFlow.reduce(s, ScreenEvent.DriveChanged(true))
        assertEquals(ScreenState(driving = true, page = Page.INBOX, viewing = null), s)
        s = parked(); s = ScreenFlow.reduce(s, ScreenEvent.Open("pdf1"))
        s = ScreenFlow.reduce(s, ScreenEvent.DriveChanged(true))
        assertNull(s.viewing); assertEquals(Page.INBOX, s.page)
        assertEquals("주행 중에는 다시 열 수 없다", s, ScreenFlow.reduce(s, ScreenEvent.Open("pdf1")))
    }

    @Test fun removedItemClosesOnlyItsViewer() {
        var s = parked(); s = ScreenFlow.reduce(s, ScreenEvent.Open("a"))
        assertEquals("a", ScreenFlow.reduce(s, ScreenEvent.ItemRemoved("b")).viewing)
        assertNull(ScreenFlow.reduce(s, ScreenEvent.ItemRemoved("a")).viewing)
        assertNull(ScreenFlow.reduce(s, ScreenEvent.CloseViewer).viewing)
    }
}

class DestFlowTest {
    private val dest = Destination(37.4979, 127.0276, "강남역")
    private val voiceOffer = DestOffer("i1", dest, voice = true)
    private val touchOffer = DestOffer("i1", dest, voice = false)
    private fun s(driving: Boolean) = DestState(driving = driving)
    private fun step(st: DestState, e: DestEvent): DestState = DestFlow.reduce(st, e).first
    private fun fx(st: DestState, e: DestEvent): List<DestEffect> = DestFlow.reduce(st, e).second

    @Test fun josaByFinalConsonant() {
        assertEquals("을", Josa.eulReul("강남역")); assertEquals("을", Josa.eulReul("서울시청")); assertEquals("를", Josa.eulReul("스타벅스"))
        assertEquals("를", Josa.eulReul("코엑스")); assertEquals("을(를)", Josa.eulReul("Seoul Station")); assertEquals("을(를)", Josa.eulReul(""))
        assertEquals("강남역을 목적지로 안내할까요?", DestFlow.question(voiceOffer))
        assertEquals("공유받은 목적지를 목적지로 안내할까요?", DestFlow.question(DestOffer("x", Destination(1.0, 2.0, null), true)))
    }

    @Test fun voiceOfferSpeaksThenListensOnce_andAcceptStartsNaviAfterwards() {
        var st = s(false)
        var (n, e) = DestFlow.reduce(st, DestEvent.Propose(voiceOffer)); st = n
        assertEquals(listOf<DestEffect>(DestEffect.Speak(1, "강남역을 목적지로 안내할까요?")), e)
        assertEquals(DestPhase.SPEAKING, st.phase)
        DestFlow.reduce(st, DestEvent.Spoken(1)).let { st = it.first; assertEquals(listOf<DestEffect>(DestEffect.Listen(1)), it.second) }
        assertEquals(DestPhase.LISTENING, st.phase)
        DestFlow.reduce(st, DestEvent.Heard(1, Heard.Text("응"))).let {
            st = it.first
            assertEquals(listOf(DestEffect.MarkUsed("i1"), DestEffect.StartNavi(1, voiceOffer)), it.second)
        }
        assertTrue(st.accepted); assertEquals(DestPhase.REQUESTING, st.phase)
        DestFlow.reduce(st, DestEvent.NaviResult(1, false, "경로를 찾지 못했어요")).let { st = it.first; assertEquals(listOf<DestEffect>(DestEffect.SpeakResult(1, "경로를 찾지 못했어요")), it.second) }
        assertEquals("경로를 찾지 못했어요", st.resultLine)
        DestFlow.reduce(st, DestEvent.ResultSpoken(1)).let { st = it.first; assertEquals(listOf<DestEffect>(DestEffect.ScheduleExpiry(1, 60_000)), it.second) }
        st = step(st, DestEvent.Expired(1)); assertNull(st.offer)
    }

    @Test fun whileDrivingAnUnansweredOrFinishedOfferClosesInsteadOfWaitingForTouch() {
        var st = step(step(s(true), DestEvent.Propose(voiceOffer)), DestEvent.Spoken(1))
        val (timeout, e1) = DestFlow.reduce(st, DestEvent.Heard(1, Heard.Timeout))
        assertNull("주행 중 무응답: 터치로 고를 수 없으니 닫힌다", timeout.offer); assertTrue(e1.isEmpty()); assertTrue(timeout.driving)
        st = step(st, DestEvent.Heard(1, Heard.Text("응")))
        st = step(st, DestEvent.NaviResult(1, true, "경로 안내를 시작했어요"))
        val (done, e2) = DestFlow.reduce(st, DestEvent.ResultSpoken(1))
        assertNull(done.offer); assertTrue(e2.isEmpty())
        assertEquals("시퀀스 번호는 유지", 1L, done.seq)
    }

    @Test fun naviIsNeverRequestedWithoutExplicitAccept() {
        val nonAccepting = listOf(
            DestEvent.Propose(voiceOffer), DestEvent.Spoken(1), DestEvent.Heard(1, Heard.Timeout), DestEvent.Heard(1, Heard.Failure),
            DestEvent.Heard(1, Heard.Text("예약")), DestEvent.Heard(1, Heard.Text("네?")), DestEvent.Heard(1, Heard.Text("아니")), DestEvent.Heard(1, Heard.Text("")),
            DestEvent.Heard(1, Heard.Text("어")), DestEvent.Heard(1, Heard.Text("응 근데 아니")),
            DestEvent.TouchDismiss, DestEvent.Expired(1), DestEvent.Failed(1), DestEvent.DriveChanged(true), DestEvent.DriveChanged(false), DestEvent.Cleared,
        )
        for (driving in listOf(true, false)) {
            var st = s(driving)
            for (e in nonAccepting) {
                val (n, fxs) = DestFlow.reduce(st, e); st = n
                assertTrue("$e: StartNavi 금지", fxs.none { it is DestEffect.StartNavi })
                assertFalse(st.accepted)
            }
            // 링크를 받았다는 사실(Propose)만으로도 StartNavi 는 없다
            assertTrue(fx(s(driving), DestEvent.Propose(voiceOffer)).none { it is DestEffect.StartNavi })
            assertTrue(fx(s(false), DestEvent.Propose(touchOffer)).none { it is DestEffect.StartNavi })
        }
    }

    @Test fun rejectClearsAndTimeoutKeepsTouchOptionOnlyWhenParked() {
        var st = step(step(s(false), DestEvent.Propose(voiceOffer)), DestEvent.Spoken(1))
        val rejected = step(st, DestEvent.Heard(1, Heard.Text("아니요")))
        assertNull(rejected.offer); assertNull(rejected.inFlightId)
        st = step(st, DestEvent.Heard(1, Heard.Timeout))
        assertEquals(DestPhase.DONE, st.phase); assertNull(st.inFlightId); assertEquals(voiceOffer, st.offer)
        assertEquals("스케줄", listOf<DestEffect>(DestEffect.ScheduleExpiry(1, 60_000)), fx(step(step(s(false), DestEvent.Propose(voiceOffer)), DestEvent.Spoken(1)), DestEvent.Heard(1, Heard.Timeout)))
    }

    @Test fun touchAcceptOnlyWhenParked() {
        val waiting = step(s(false), DestEvent.Propose(touchOffer))
        assertEquals(DestPhase.DONE, waiting.phase)
        assertEquals(listOf<DestEffect>(DestEffect.CancelVoice, DestEffect.MarkUsed("i1"), DestEffect.StartNavi(2, touchOffer)), fx(waiting, DestEvent.TouchAccept))
        val drivingNow = waiting.copy(driving = true)
        assertEquals(waiting.copy(driving = true), step(drivingNow, DestEvent.TouchAccept))
        assertTrue(fx(drivingNow, DestEvent.TouchAccept).isEmpty())
        assertTrue("주행 중 터치 취소도 무시", fx(drivingNow, DestEvent.TouchDismiss).isEmpty())
        assertTrue("수락 뒤 다시 눌러도 무시", fx(step(waiting, DestEvent.TouchAccept), DestEvent.TouchAccept).isEmpty())
    }

    @Test fun touchOnlyOfferIsIgnoredWhileDriving_andDrivingStartClosesWaitingOffer() {
        assertEquals(s(true), step(s(true), DestEvent.Propose(touchOffer)))
        val waiting = step(s(false), DestEvent.Propose(touchOffer))
        val (n, e) = DestFlow.reduce(waiting, DestEvent.DriveChanged(true))
        assertNull(n.offer); assertTrue(n.driving); assertEquals(listOf<DestEffect>(DestEffect.CancelVoice), e)
    }

    @Test fun voiceQuestionContinuesWhenDrivingStarts_butTouchStaysClosed() {
        val asking = step(s(false), DestEvent.Propose(voiceOffer))
        val n = step(asking, DestEvent.DriveChanged(true))
        assertEquals(voiceOffer, n.offer); assertEquals(DestPhase.SPEAKING, n.phase); assertTrue(n.driving)
        assertTrue(fx(n, DestEvent.TouchAccept).isEmpty())
    }

    @Test fun onlyOneOfferAtATime_andStaleEventsAreIgnored() {
        val a = step(s(true), DestEvent.Propose(voiceOffer))
        assertEquals(a, step(a, DestEvent.Propose(DestOffer("i2", dest, true))))
        assertEquals(a, step(a, DestEvent.Spoken(99)))
        assertEquals(a, step(a, DestEvent.Heard(99, Heard.Text("응"))))
        assertEquals(a, step(a, DestEvent.NaviResult(99, true, "x")))
        assertEquals(a, step(a, DestEvent.Expired(99)))
        assertEquals("진행 중에는 만료 이벤트로 닫히지 않는다", a, step(a, DestEvent.Expired(1)))
    }

    @Test fun clearedDropsEverythingButKeepsSequenceAndDriving() {
        val a = step(s(false), DestEvent.Propose(voiceOffer))
        val (n, e) = DestFlow.reduce(a, DestEvent.Cleared)
        assertEquals(DestState(driving = false, seq = 1), n); assertEquals(listOf<DestEffect>(DestEffect.CancelVoice), e)
    }
}

class PlayerFlowTest {
    @Test fun playPauseResumeStop() {
        var s = PlayerFlow.reduce(PlayerState(), PlayerEvent.Play("a"))
        assertEquals(PlayerState("a", false, false), s)
        s = PlayerFlow.reduce(s, PlayerEvent.Pause); assertTrue(s.paused)
        s = PlayerFlow.reduce(s, PlayerEvent.Resume); assertFalse(s.paused)
        assertEquals(PlayerState(), PlayerFlow.reduce(s, PlayerEvent.Stop))
        assertEquals(PlayerState(), PlayerFlow.reduce(s, PlayerEvent.Completed))
        assertEquals("재생 중이 아니면 Pause 무시", PlayerState(), PlayerFlow.reduce(PlayerState(), PlayerEvent.Pause))
    }

    @Test fun permanentFocusLossStops_transientPausesAndResumes() {
        val playing = PlayerState("a")
        assertEquals(PlayerState(), PlayerFlow.reduce(playing, PlayerEvent.FocusLost(transient = false)))
        val paused = PlayerFlow.reduce(playing, PlayerEvent.FocusLost(transient = true))
        assertEquals(PlayerState("a", paused = true, resumeOnGain = true), paused)
        assertEquals(PlayerState("a", paused = false, resumeOnGain = false), PlayerFlow.reduce(paused, PlayerEvent.FocusGained))
    }

    @Test fun userPausedStaysPausedAfterFocusGain() {
        val userPaused = PlayerFlow.reduce(PlayerState("a"), PlayerEvent.Pause)
        val lost = PlayerFlow.reduce(userPaused, PlayerEvent.FocusLost(transient = true))
        assertFalse("사용자가 멈춘 것은 포커스가 돌아와도 재생하지 않는다", lost.resumeOnGain)
        assertTrue(PlayerFlow.reduce(lost, PlayerEvent.FocusGained).paused)
    }

    @Test fun removedItemStopsOnlyItsPlayback() {
        assertEquals(PlayerState(), PlayerFlow.reduce(PlayerState("a"), PlayerEvent.ItemRemoved("a")))
        assertEquals(PlayerState("a"), PlayerFlow.reduce(PlayerState("a"), PlayerEvent.ItemRemoved("b")))
        assertEquals("다른 곡 재생은 새로 시작", PlayerState("b"), PlayerFlow.reduce(PlayerState("a", paused = true), PlayerEvent.Play("b")))
    }
}

class VoiceIntentTest {
    @Test fun acceptances() {
        for (t in listOf("응", "네", "예", "네 네", "그래요", "좋아요", "응 안내해줘", "안내 시작", "OK", "  응!  ", "출발해줘", "알겠습니다", "응응응응응응응")) assertTrue(t, VoiceIntent.isAccept(t))
    }

    @Test fun nonAcceptances() {
        for (t in listOf("", "어", "어어", "네?", "응?", "예약", "응급", "네비게이션", "아니", "아니요", "취소", "안 해", "싫어", "응 근데 아니", "나중에", "안내해줘 그리고 음악 틀어줘 그리고 에어컨 켜줘", "응".repeat(15))) assertFalse(t, VoiceIntent.isAccept(t))
    }

    @Test fun compactLengthBoundary() {
        assertTrue("14자 허용", VoiceIntent.isAccept("응".repeat(14)))
        assertFalse("15자 거부", VoiceIntent.isAccept("응".repeat(15)))
    }

    @Test fun rejections() {
        for (t in listOf("아니", "아니요", "싫어", "취소", "괜찮아", "나중에", "안 할래", "하지마", "안해")) assertTrue(t, VoiceIntent.isReject(t))
        for (t in listOf("응", "", "아니스트", "네")) assertFalse(t, VoiceIntent.isReject(t))
    }
}
