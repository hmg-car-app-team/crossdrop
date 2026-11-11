package com.hmgcarapp.crossdrop

import com.hmgcarapp.crossdrop.app.Receiver
import com.hmgcarapp.crossdrop.domain.FileRules
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.net.HttpRelayApi
import com.hmgcarapp.crossdrop.net.RelayException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder

/**
 * 앱의 [HttpRelayApi]·[Receiver] 를 실제 `server/crossdrop` 로컬 서버(파이썬 프로세스)에 붙여 보는 통합 확인.
 * 파이썬이 필요하므로 기본은 건너뛰고, `CROSSDROP_E2E=1` 일 때만 실행한다: `CROSSDROP_E2E=1 ./gradlew :cross-drop:testDebugUnitTest --tests '*E2eLocalServerTest'`
 */
class E2eLocalServerTest {
    private fun raw(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).readBytes().toString(Charsets.UTF_8)
        return code to text
    }

    @Test fun appClientAgainstRealServer() {
        Assume.assumeTrue("CROSSDROP_E2E=1 일 때만", System.getenv("CROSSDROP_E2E") == "1")
        val serverDir = File("../../server").canonicalFile
        val port = ServerSocket(0).use { it.localPort }
        val proc = ProcessBuilder("python3", "-m", "crossdrop.server", "--port", port.toString()).directory(serverDir).redirectErrorStream(true).start()
        try {
            val base = "http://127.0.0.1:$port"
            var up = false
            for (i in 0 until 50) { try { if (raw("GET", "$base/healthz", emptyMap(), null).first == 200) { up = true; break } } catch (e: Exception) { Thread.sleep(100) } }
            assertTrue("서버가 뜨지 않음", up)
            runBlocking {
                val api = HttpRelayApi(base)
                val grant = api.createSession()
                // 휴대폰(웹 페이지) 쪽: 코드로 연결 -> 업로드
                val claim = JSONObject(raw("POST", "$base/v1/claim", mapOf("Content-Type" to "application/json"), """{"code":"${grant.code}"}""".toByteArray()).second)
                val phone = mapOf("Authorization" to "Bearer " + claim.getString("upload_token"))
                val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
                assertEquals(200, raw("POST", "$base/v1/upload", phone + ("X-File-Name" to URLEncoder.encode("내 사진.png", "UTF-8")), png).first)
                assertEquals("확장자 위장", 415, raw("POST", "$base/v1/upload", phone + ("X-File-Name" to "a.png"), "MZ".toByteArray() + ByteArray(32)).first)
                assertEquals(200, raw("POST", "$base/v1/link", phone + ("Content-Type" to "application/json"), """{"url":"https://map.kakao.com/link/map/a,37.5,127.0"}""".toByteArray()).first)
                assertEquals("코드는 한 번만", 400, raw("POST", "$base/v1/claim", mapOf("Content-Type" to "application/json"), """{"code":"${grant.code}"}""".toByteArray()).first)
                // 차량(앱) 쪽
                val inbox = api.inbox(grant.deviceToken)
                assertTrue(inbox.claimed); assertEquals(listOf(ItemKind.IMAGE, ItemKind.LINK), inbox.items.map { it.kind })
                val saved = ArrayList<ByteArray>()
                var n = 0
                val res = Receiver(api, save = { _, b -> saved += b; "f_%016x.png".format(++n) }, newId = { "id$n" }).receive(grant.deviceToken, inbox, emptySet(), 1L, 10)
                assertEquals(2, res.accepted.size); assertEquals(1, saved.size); assertEquals(png.toList(), saved[0].toList())
                assertEquals(37.5, res.accepted[1].dest!!.lat, 0.0)
                com.hmgcarapp.crossdrop.app.commitThenDelete(api, grant.deviceToken, res.handled) { true }   // 저장(여기선 생략) 뒤 서버 사본 삭제
                assertTrue("받은 항목은 서버에서 지워졌다", api.inbox(grant.deviceToken).items.isEmpty())
                api.deleteSession(grant.deviceToken)
                try { api.inbox(grant.deviceToken); fail("세션 삭제 뒤에는 401") } catch (e: RelayException) { assertEquals(401, e.status) }
                assertNull(FileRules.sniff(ByteArray(0)))
            }
        } finally { proc.destroy() }
    }
}
