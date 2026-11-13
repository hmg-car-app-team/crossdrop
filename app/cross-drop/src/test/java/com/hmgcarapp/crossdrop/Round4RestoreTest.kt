package com.hmgcarapp.crossdrop

import com.hmgcarapp.crossdrop.app.SyncGate
import com.hmgcarapp.crossdrop.app.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 4차: '연결 끊기' = 동의 철회. 철회 뒤에는 관문이 닫히고(NO_CONSENT) 새 연결은 동의 팝업부터. */
class Round4RestoreTest {
    private val base = "src/main/java/com/hmgcarapp/crossdrop"

    @Test fun unpairButtonRevokesConsent_andNextPairAsksAgain() {
        val ui = File("$base/ui/Screens.kt").readText()
        assertTrue(Regex("""btn_unpair""").containsMatchIn(ui) && ui.contains("c.revokeConsent()"))
        assertTrue("동의 없으면 팝업", ui.contains("if (!s.p.consent) ask = true else c.startPairing(true)"))
        val dc = File("$base/app/DropController.kt").readText()
        val rev = dc.substringAfter("fun revokeConsent()").substringBefore("// ---------- 삭제")
        assertTrue(rev.contains("endPairingInternal()") && rev.contains("consent = false"))
    }

    @Test fun gateStillConsentAndServerAndNotDemo() {
        assertEquals(SyncStatus.NO_CONSENT, SyncGate.status(false, "https://x", false, true, false))
        assertEquals(SyncStatus.NO_SERVER, SyncGate.status(true, null, false, true, false))
        assertEquals(SyncStatus.ACTIVE, SyncGate.status(true, "https://x", false, true, false))
    }
}
