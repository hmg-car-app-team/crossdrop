package com.hmgcarapp.crossdrop

import com.hmgcarapp.crossdrop.domain.Destination
import com.hmgcarapp.crossdrop.domain.ExifTransform
import com.hmgcarapp.crossdrop.domain.InboxItem
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.domain.PairStatus
import com.hmgcarapp.crossdrop.domain.Pairing
import com.hmgcarapp.crossdrop.domain.PairingInfo
import com.hmgcarapp.crossdrop.domain.Retention
import com.hmgcarapp.crossdrop.domain.ServerUrlPolicy
import com.hmgcarapp.crossdrop.domain.ViewMath
import com.hmgcarapp.crossdrop.net.RelayException
import com.hmgcarapp.crossdrop.net.RelayWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RetentionTest {
    private val h = 3_600_000L
    @Test fun ttlIsExactly24Hours() {
        assertEquals(86_400_000L, Retention.TTL_MS)
        assertFalse(Retention.expired(1000L, 1000L + 86_400_000L - 1))
        assertTrue(Retention.expired(1000L, 1000L + 86_400_000L))
        assertTrue(Retention.expired(1000L, 1000L + 86_400_001L))
    }
    @Test fun futureTimestampsBeyondOneHourAreExpired() {
        assertFalse("시계가 1시간까지 뒤로 간 것은 허용", Retention.expired(10 * h + h, 10 * h))
        assertTrue("1시간을 넘는 미래는 신뢰할 수 없어 삭제", Retention.expired(10 * h + h + 1, 10 * h))
    }
    @Test fun remainingTimeIsClamped() {
        assertEquals(86_400_000L, Retention.remainingMs(5000, 5000)); assertEquals(0L, Retention.remainingMs(0, 90_000_000L)); assertEquals(86_400_000L, Retention.remainingMs(9000, 5000))
        assertEquals(3_600_000L, Retention.remainingMs(0, 86_400_000L - 3_600_000L))
    }
    @Test fun splitKeepsFreshItems() {
        val now = 100 * h
        fun item(id: String, at: Long) = InboxItem(id, ItemKind.LINK, at)
        val (keep, gone) = Retention.split(listOf(item("a", now - 24 * h), item("b", now - 24 * h + 1), item("c", now)), now)
        assertEquals(listOf("b", "c"), keep.map { it.id }); assertEquals(listOf("a"), gone.map { it.id })
    }
}

class ServerUrlPolicyTest {
    @Test fun httpsAlwaysAllowed() {
        assertEquals("https://relay.example", ServerUrlPolicy.validate("https://relay.example/", false))
        assertEquals("https://relay.example:8443", ServerUrlPolicy.validate(" https://relay.example:8443 ", true))
    }
    @Test fun cleartextOnlyForLocalHostsInDebug() {
        assertEquals("http://10.0.2.2:8780", ServerUrlPolicy.validate("http://10.0.2.2:8780", true))
        assertEquals("http://localhost:1", ServerUrlPolicy.validate("http://localhost:1", true)); assertEquals("http://127.0.0.1:2", ServerUrlPolicy.validate("http://127.0.0.1:2", true))
        assertNull("release 에서는 로컬 http 도 거부", ServerUrlPolicy.validate("http://10.0.2.2:8780", false))
        assertNull(ServerUrlPolicy.validate("http://relay.example", true)); assertNull(ServerUrlPolicy.validate("http://192.168.0.5:8780", true))
    }
    @Test fun malformedAreRejected() {
        for (u in listOf(null, "", "  ", "ftp://relay.example", "relay.example", "https://", "https://relay.example/path", "https://relay.example/?q=1", "https://relay.example/#f", "https://u:p@relay.example", "https://relay.example/" + "a".repeat(200), "javascript:1", "https://rel ay.example"))
            assertNull(u.toString(), ServerUrlPolicy.validate(u, true))
    }
}

class PairingTest {
    private val p = PairingInfo("ABCD2345", "a".repeat(64), issuedWallMs = 1000, codeExpiresWallMs = 601_000, sessionExpiresWallMs = 86_401_000)
    @Test fun statusTransitionsAtExactBoundaries() {
        assertEquals(PairStatus.NONE, Pairing.status(null, 0))
        assertEquals(PairStatus.WAITING, Pairing.status(p, 600_999)); assertEquals(PairStatus.CODE_EXPIRED, Pairing.status(p, 601_000))
        assertEquals(PairStatus.CONNECTED, Pairing.status(p.copy(claimed = true), 700_000)); assertEquals("연결된 뒤에는 코드 만료와 무관", PairStatus.CONNECTED, Pairing.status(p.copy(claimed = true), 86_400_999))
        assertEquals(PairStatus.ENDED, Pairing.status(p.copy(claimed = true), 86_401_000)); assertEquals(PairStatus.ENDED, Pairing.status(p, 86_401_000))
    }
    @Test fun codeFormat() {
        assertTrue(Pairing.CODE_REGEX.matches("ABCD2345")); assertTrue(Pairing.CODE_REGEX.matches("HJKLMNPQ"))
        for (bad in listOf("ABCD234", "ABCD23456", "abcd2345", "ABCD234O", "ABCD2341", "ABCD234I", "ABCD-234", "ABCD2340", "", "ABCD 345")) assertFalse(bad, Pairing.CODE_REGEX.matches(bad))
        assertTrue(Pairing.TOKEN_REGEX.matches("0".repeat(64))); assertFalse(Pairing.TOKEN_REGEX.matches("0".repeat(63))); assertFalse(Pairing.TOKEN_REGEX.matches("G".repeat(64)))
    }
    @Test fun qrPayloadKeepsCodeInFragment() {
        assertEquals("https://relay.example/u#ABCD2345", Pairing.qrPayload("https://relay.example", "ABCD2345"))
        assertEquals("ABCD-2345", Pairing.display("ABCD2345"))
    }
}

class ViewMathTest {
    @Test fun sampleSizeIsSmallestPowerOfTwoUnderLimit() {
        assertEquals(1, ViewMath.sampleSize(2048, 1000)); assertEquals(2, ViewMath.sampleSize(2049, 100)); assertEquals(2, ViewMath.sampleSize(4096, 100))
        assertEquals(4, ViewMath.sampleSize(4097, 3000)); assertEquals(4, ViewMath.sampleSize(8000, 100)); assertEquals(8, ViewMath.sampleSize(16384, 100)); assertEquals(1, ViewMath.sampleSize(10, 10))
    }
    @Test fun hugeOrInvalidImagesAreRejected() {
        assertFalse(ViewMath.rejectImage(5000, 10000)); assertTrue(ViewMath.rejectImage(5000, 10001)); assertTrue(ViewMath.rejectImage(0, 100)); assertTrue(ViewMath.rejectImage(100, -1))
        assertTrue("정수 곱 오버플로가 아니라 Long 으로 계산", ViewMath.rejectImage(100_000, 100_000))
    }
    @Test fun pdfSizeKeepsAspect() {
        assertEquals(1272 to 1800, ViewMath.pdfSize(595, 842)); assertEquals(1800 to 1272, ViewMath.pdfSize(842, 595)); assertEquals(1 to 1, ViewMath.pdfSize(0, 10))
        assertEquals("아주 가는 쪽도 높이는 최소 1", 1800 to 1, ViewMath.pdfSize(100000, 1))
    }
}

class RelayWireTest {
    private val tok = "a".repeat(64)
    private fun fails(f: () -> Unit) { try { f(); fail("RelayException 이어야 함") } catch (e: RelayException) { assertEquals(0, e.status) } }

    @Test fun grantParsed() {
        val g = RelayWire.parseGrant("""{"code":"ABCD2345","device_token":"$tok","code_expires_in":600,"session_expires_in":86400}""")
        assertEquals("ABCD2345", g.code); assertEquals(600L, g.codeExpiresInS); assertEquals(86400L, g.sessionExpiresInS)
    }
    @Test fun grantRejectsBadShapes() {
        fails { RelayWire.parseGrant("not json") }
        fails { RelayWire.parseGrant("""{"code":"abcd2345","device_token":"$tok","code_expires_in":600,"session_expires_in":1}""") }
        fails { RelayWire.parseGrant("""{"code":"ABCD2345","device_token":"short","code_expires_in":600,"session_expires_in":1}""") }
        fails { RelayWire.parseGrant("""{"code":"ABCD2345","device_token":"$tok","session_expires_in":1}""") }
    }
    @Test fun grantClampsTtl() {
        val g = RelayWire.parseGrant("""{"code":"ABCD2345","device_token":"$tok","code_expires_in":99999999999,"session_expires_in":-5}""")
        assertEquals(RelayWire.MAX_TTL_S, g.codeExpiresInS); assertEquals(0L, g.sessionExpiresInS)
    }
    @Test fun inboxKeepsOnlyWellFormedItems() {
        val good = "0123456789abcdef"
        val json = """{"claimed":true,"code_expires_in":0,"session_expires_in":100,"items":[
          {"id":"$good","kind":"image","ext":"jpg","size":100,"age_s":5},
          {"id":"1123456789abcdef","kind":"link","url":"https://map.kakao.com/link/map/a,37.5,127.0","age_s":1},
          {"id":"zz","kind":"image","ext":"jpg","size":100},
          {"id":"2123456789abcdef","kind":"video","ext":"mp4","size":100},
          {"id":"3123456789abcdef","kind":"image","ext":"jpg","size":0},
          {"id":"4123456789abcdef","kind":"image","ext":"jpg","size":10485761},
          {"id":"5123456789abcdef","kind":"image","ext":"mp3","size":100},
          {"id":"6123456789abcdef","kind":"audio","ext":"wav","size":100},
          {"id":"7123456789abcdef","kind":"link"},
          {"id":"8123456789abcdef","kind":"audio","ext":"ogg","size":20971520},
          5, null]}"""
        val inbox = RelayWire.parseInbox(json)
        assertTrue(inbox.claimed)
        assertEquals(listOf(good, "1123456789abcdef", "8123456789abcdef"), inbox.items.map { it.id })
        assertEquals(listOf(ItemKind.IMAGE, ItemKind.LINK, ItemKind.AUDIO), inbox.items.map { it.kind })
        assertEquals("https://map.kakao.com/link/map/a,37.5,127.0", inbox.items[1].url)
    }
    @Test fun inboxLimitsAndMalformed() {
        val many = (1..80).joinToString(",") { """{"id":"%016x","kind":"link","url":"https://map.kakao.com/x"}""".format(it) }
        assertEquals(RelayWire.MAX_ITEMS, RelayWire.parseInbox("""{"claimed":false,"items":[$many]}""").items.size)
        fails { RelayWire.parseInbox("{}") }
        fails { RelayWire.parseInbox("""{"items":[]}""") }
        fails { RelayWire.parseInbox("<html>") }
        val longUrl = "https://map.kakao.com/" + "a".repeat(2040)
        assertTrue(RelayWire.parseInbox("""{"claimed":false,"items":[{"id":"0123456789abcdef","kind":"link","url":"$longUrl"}]}""").items.isEmpty())
    }
    @Test fun itemIdFormat() {
        assertTrue(RelayWire.isItemId("0123456789abcdef")); assertFalse(RelayWire.isItemId("../../etc/passwd")); assertFalse(RelayWire.isItemId("0123456789ABCDEF")); assertFalse(RelayWire.isItemId("0123456789abcde"))
    }

    /** server/crossdrop 의 실제 응답(2026-11-11 로컬 실행에서 복사)을 그대로 파싱한다: 서버·앱 사이 계약 고정. */
    @Test fun parsesRealServerResponses() {
        val g = RelayWire.parseGrant("""{"code":"H79XBWSE","device_token":"9c5e62421232db72224f6ffe740e84df3b3c1a94b1881648392650fb43b3654c","code_expires_in":600,"session_expires_in":86400}""")
        assertEquals("H79XBWSE", g.code); assertEquals(600L, g.codeExpiresInS)
        val inbox = RelayWire.parseInbox("""{"claimed":true,"code_expires_in":0,"session_expires_in":86393,"items":[{"id":"66c57765e0e87cab","kind":"image","age_s":7,"ext":"png","size":40},{"id":"d208f8b6ec3e2876","kind":"link","age_s":7,"url":"https://map.kakao.com/link/map/a,37.5,127.0"}]}""")
        assertTrue(inbox.claimed); assertEquals(2, inbox.items.size)
        assertEquals("png", inbox.items[0].ext); assertEquals(40L, inbox.items[0].size); assertEquals(ItemKind.LINK, inbox.items[1].kind)
    }
}

class ExifTransformTest {
    @Test fun allEightOrientations() {
        val expected = mapOf(1 to (0 to false), 2 to (0 to true), 3 to (180 to false), 4 to (180 to true), 5 to (270 to true), 6 to (90 to false), 7 to (90 to true), 8 to (270 to false))
        for ((o, e) in expected) assertEquals("방향 $o", ExifTransform(e.first, e.second), ExifTransform.of(o))
        assertTrue(ExifTransform.of(1).isIdentity)
        for (o in 2..8) assertFalse("방향 $o", ExifTransform.of(o).isIdentity)
    }
    @Test fun unknownValuesAreIdentity() {
        for (o in listOf(0, 9, -1, 255)) assertTrue(ExifTransform.of(o).isIdentity)
    }
}

class KoreanInboxWireTest {
    @Test fun thirtyMaxLengthKoreanLinksParseWithinTheInboxCap() {
        val base = "https://map.kakao.com/link/map/"
        val url = base + "가".repeat(2048 - base.length)
        val items = (1..30).joinToString(",") { """{"id":"%016x","kind":"link","age_s":1,"url":"$url"}""".format(it) }
        val json = """{"claimed":true,"code_expires_in":0,"session_expires_in":9,"items":[$items]}"""
        val bytes = json.toByteArray(Charsets.UTF_8).size
        assertTrue("예전 64KB 상한이었다면 실패했을 크기: $bytes", bytes > RelayWire.MAX_RESPONSE_BYTES)
        assertTrue("새 상한 안: $bytes", bytes < RelayWire.MAX_INBOX_BYTES)
        assertEquals(30, RelayWire.parseInbox(json).items.size)
    }
}

