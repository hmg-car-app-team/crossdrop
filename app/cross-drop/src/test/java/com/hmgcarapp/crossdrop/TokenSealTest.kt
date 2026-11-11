package com.hmgcarapp.crossdrop

import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.crossdrop.app.GcmTokenSealer
import com.hmgcarapp.crossdrop.app.Persisted
import com.hmgcarapp.crossdrop.app.StateCodec
import com.hmgcarapp.crossdrop.app.StateStore
import com.hmgcarapp.crossdrop.app.TokenFormat
import com.hmgcarapp.crossdrop.app.TokenSealer
import com.hmgcarapp.crossdrop.domain.Destination
import com.hmgcarapp.crossdrop.domain.InboxItem
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.domain.PairingInfo
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class TokenSealTest {
    @get:Rule val tmp = TemporaryFolder()
    private val tok = "a1".repeat(32)
    private val pairing = PairingInfo("ABCDEFGH", tok, 1, 2, 3, true)
    private val link = InboxItem("1123456789abcdef", ItemKind.LINK, 222, dest = Destination(37.5, 127.0, "강남역"), used = true)
    private val state = Persisted(consent = true, pairing = pairing, items = listOf(link), savedAtWallMs = 9)

    private fun key(): java.security.Key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun real(k: java.security.Key = key()) = GcmTokenSealer({ k }, { k })
    private class Xor : TokenSealer {
        override fun seal(plain: String) = TokenFormat.PREFIX + plain.reversed()
        override fun open(sealed: String) = sealed.removePrefix(TokenFormat.PREFIX).reversed()
    }
    private class Failing : TokenSealer {
        override fun seal(plain: String): String = throw IllegalStateException("x")
        override fun open(sealed: String): String? = throw IllegalStateException("x")
    }

    @After fun reset() { StateCodec.clearSealedCache(); StateCodec.sealer = null; StateCodec.onSealFailure = null; StateCodec.onTokenDropped = null }

    private fun tokenField(o: JSONObject) = o.getJSONObject("pairing").getString("token")

    @Test fun roundTripWithFakeAndRealGcm() {
        for (s in listOf<TokenSealer>(Xor(), real())) {
            StateCodec.sealer = s
            val o = StateCodec.encode(state)
            assertTrue(tokenField(o).startsWith(TokenFormat.PREFIX)); assertFalse(o.toString().contains(tok))
            val back = StateCodec.parse(o.toString())
            assertFalse(back.corrupt); assertEquals(state, back.p)
        }
    }

    @Test fun gcmUsesFreshIvAndFormat() {
        val s = real()
        val a = s.seal(tok); val b = s.seal(tok)
        assertTrue(a != b)
        val raw = java.util.Base64.getDecoder().decode(a.removePrefix(TokenFormat.PREFIX))
        assertEquals(12 + tok.length + 16, raw.size)
        assertEquals(tok, s.open(a))
    }

    @Test fun otherFieldsUnchangedByEncryption() {
        val plain = StateCodec.encode(state); StateCodec.sealer = Xor(); val enc = StateCodec.encode(state)
        fun strip(o: JSONObject) = JSONObject(o.toString()).also { it.getJSONObject("pairing").remove("token") }.toString()
        assertEquals(strip(plain), strip(enc))
    }

    @Test fun legacyPlainTokenReadableAndReencryptedOnNextSave() {
        val dir = tmp.newFolder("m"); StateCodec.sealer = null
        File(dir, "t.json").writeText(StateCodec.encode(state).toString()) // 구형식 평문
        StateCodec.sealer = real()
        val st = StateStore(LocalJsonStore(dir), "t")
        val r = st.restore(0)
        assertEquals(state, r.p); assertFalse(r.corrupt)
        assertEquals(com.hmgcarapp.crossdrop.app.WriteResult.WROTE, st.writeLatest { r.p })
        val text = File(dir, "t.json").readText()
        assertFalse(text.contains(tok)); assertTrue(text.contains(TokenFormat.PREFIX))
        assertEquals(state, StateStore(LocalJsonStore(dir), "t").restore(0).p)
    }

    @Test fun openFailureDropsOnlyPairingAndNeverQuarantines() {
        val dir = tmp.newFolder("d")
        StateCodec.sealer = real(); File(dir, "t.json").writeText(StateCodec.encode(state).toString())
        var dropped = 0; StateCodec.onTokenDropped = { dropped++ }
        for (s in listOf<TokenSealer?>(real(), Failing(), null)) { // 다른 키 / 예외 / 도구 없음
            StateCodec.sealer = s
            val r = StateStore(LocalJsonStore(dir), "t").restore(0)
            assertNull(r.p.pairing); assertFalse(r.corrupt); assertFalse(r.quarantined)
            assertEquals(listOf(link), r.p.items); assertTrue(r.p.consent)
            assertTrue(File(dir, "t.json").exists())
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".corrupt") })
        }
        assertEquals(3, dropped)
    }

    @Test fun sealFailureDoesNotWritePlaintextAndKeepsOthers() {
        val dir = tmp.newFolder("f"); StateCodec.sealer = Failing()
        var failed = 0; StateCodec.onSealFailure = { failed++ }
        val st = StateStore(LocalJsonStore(dir), "t")
        st.restore(0)
        assertEquals(com.hmgcarapp.crossdrop.app.WriteResult.WROTE, st.writeLatest { state })
        assertEquals(1, failed)
        val text = File(dir, "t.json").readText()
        assertFalse(text.contains(tok)); assertFalse(text.contains("pairing"))
        StateCodec.sealer = null
        val back = StateCodec.parse(text).p
        assertNull(back.pairing); assertEquals(listOf(link), back.items); assertTrue(back.consent)
        assertNotNull(state.pairing) // 메모리 상태는 그대로
    }

    @Test fun sealerReturningNoPrefixIsTreatedAsFailure() {
        StateCodec.sealer = object : TokenSealer { override fun seal(plain: String) = plain; override fun open(sealed: String) = sealed }
        assertFalse(StateCodec.encode(state).has("pairing"))
    }

    @Test fun malformedSealedValuesAreRejected() {
        val s = real(); val good = s.seal(tok)
        val b64 = good.removePrefix(TokenFormat.PREFIX)
        val bad = listOf(
            "", TokenFormat.PREFIX, TokenFormat.PREFIX + "!!!", TokenFormat.PREFIX + b64.dropLast(8), TokenFormat.PREFIX + b64.take(10),
            "enc:v2:$b64", "ENC:V1:$b64", b64, TokenFormat.PREFIX + " " + b64,
            TokenFormat.PREFIX + java.util.Base64.getEncoder().encodeToString(ByteArray(12)),
            TokenFormat.PREFIX + java.util.Base64.getEncoder().encodeToString(java.util.Base64.getDecoder().decode(b64).also { it[20] = (it[20] + 1).toByte() }),
        )
        for (b in bad) assertNull(b, s.open(b))
        assertEquals(tok, s.open(good))
        // 잘못된 값이 들어간 파일은 pairing 만 버린다
        StateCodec.sealer = s
        val o = StateCodec.encode(state); o.getJSONObject("pairing").put("token", TokenFormat.PREFIX + b64.dropLast(8))
        val r = StateCodec.parse(o.toString()); assertFalse(r.corrupt); assertNull(r.p.pairing); assertEquals(listOf(link), r.p.items)
        o.getJSONObject("pairing").put("token", "")
        assertNull(StateCodec.parse(o.toString()).p.pairing)
    }

    @Test fun killSwitchStillReadsEncryptedValues() {
        // 스위치는 const 라 직접 끌 수 없다: 도구 없음(null) 경로가 평문 쓰기와 같은 분기. 암호화 값 읽기는 sealer 가 있으면 항상 동작.
        StateCodec.sealer = Xor()
        val text = StateCodec.encode(state).toString()
        assertEquals(state, StateCodec.parse(text).p)
        assertTrue(StateCodec.TOKEN_ENCRYPTION_ENABLED)
    }

    private class Flaky(val inner: TokenSealer) : TokenSealer {
        var fail = false
        override fun seal(plain: String) = if (fail) throw IllegalStateException("x") else inner.seal(plain)
        override fun open(sealed: String) = inner.open(sealed)
    }

    @Test fun sealFailureKeepsExistingCiphertextForSameTokenOnly() {
        StateCodec.clearSealedCache()
        val f = Flaky(real()); StateCodec.sealer = f
        var failed = 0; StateCodec.onSealFailure = { failed++ }
        val first = tokenField(StateCodec.encode(state))
        f.fail = true
        val same = StateCodec.encode(state)
        assertEquals(first, tokenField(same)); assertFalse(same.toString().contains(tok)); assertEquals(0, failed)
        val other = state.copy(pairing = pairing.copy(deviceToken = "b2".repeat(32)))
        val o = StateCodec.encode(other)
        assertFalse(o.has("pairing")); assertFalse(o.toString().contains("b2".repeat(32))); assertEquals(1, failed)
    }

    @Test fun cachedCiphertextFromRestoredFileIsReusedOnSealFailure() {
        val f = Flaky(real()); StateCodec.sealer = f
        val text = StateCodec.encode(state).toString()
        StateCodec.clearSealedCache()
        StateCodec.parse(text) // 파일에서 열면서 쌍을 보관
        f.fail = true
        assertEquals(tokenField(JSONObject(text)), tokenField(StateCodec.encode(state)))
    }

    @Test fun savingWithoutPairingClearsSealedCache() {
        val f = Flaky(real()); StateCodec.sealer = f
        StateCodec.clearSealedCache()
        StateCodec.encode(state) // 쌍 보관
        StateCodec.encode(state.copy(pairing = null)) // 연결 해제 저장 -> 캐시 비움
        f.fail = true
        var failed = 0; StateCodec.onSealFailure = { failed++ }
        val o = StateCodec.encode(state)
        assertFalse(o.has("pairing")); assertFalse(o.toString().contains(tok)); assertEquals(1, failed)
    }

    @Test fun sealFailureIsReportedOncePerSession() {
        StateCodec.clearSealedCache(); StateCodec.sealer = Failing()
        var failed = 0; StateCodec.onSealFailure = { failed++ }
        repeat(3) { StateCodec.encode(state) }
        assertEquals(1, failed)
        StateCodec.onSealFailure = { failed++ } // 새 세션
        StateCodec.encode(state)
        assertEquals(2, failed)
    }
}
