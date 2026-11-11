package com.hmgcarapp.crossdrop.net

import com.hmgcarapp.crossdrop.domain.FileFormat
import com.hmgcarapp.crossdrop.domain.FileRules
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.domain.LinkParser
import com.hmgcarapp.crossdrop.domain.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 서버가 발급한 페어링 정보(원본 응답을 검증한 값). */
data class PairGrant(val code: String, val deviceToken: String, val codeExpiresInS: Long, val sessionExpiresInS: Long)

/** 수신함 목록의 항목 한 건(검증된 값). [url] 은 링크 항목만. */
data class RemoteItem(val id: String, val kind: ItemKind, val ageS: Long, val ext: String?, val size: Long, val url: String?)

data class RemoteInbox(val claimed: Boolean, val codeExpiresInS: Long, val sessionExpiresInS: Long, val items: List<RemoteItem>)

/** [status] 401 = 세션이 끝났거나 토큰이 틀림(재연결 필요). 0 = 응답 형식 오류·네트워크 오류. */
class RelayException(val status: Int, message: String) : IOException(message)

/** 릴레이 서버 클라이언트 포트. 구현은 예외를 던져도 되며 호출부가 실패로 처리한다. 로그에는 URL·토큰·ID 를 남기지 않는다. */
interface RelayApi {
    suspend fun createSession(): PairGrant
    suspend fun inbox(token: String): RemoteInbox
    suspend fun download(token: String, id: String, maxBytes: Int): ByteArray
    suspend fun deleteItem(token: String, id: String)
    suspend fun deleteSession(token: String)
}

/** 서버 응답 검증(순수). 형식이 어긋난 항목은 버리고, 전체가 어긋나면 [RelayException]. */
object RelayWire {
    const val MAX_RESPONSE_BYTES = 64 * 1024
    /**
     * 수신함 응답 상한. 서버는 세션당 항목 30개 · 링크 2048자(UTF-8 최대 4바이트, 이모지)까지 허용하므로 최악 30 x (2048 x 4 + 필드 약 100) = 약 249KB.
     * 256KB 는 이를 넘으면서도 비정상적으로 큰 응답은 막는 값(서버 config.MAX_INBOX_RESPONSE_BYTES 와 같다).
     */
    const val MAX_INBOX_BYTES = 256 * 1024
    const val MAX_ITEMS = 50
    private val ID_REGEX = Regex("^[0-9a-f]{16}$")
    const val MAX_TTL_S = 7L * 24 * 3600

    fun isItemId(s: String) = ID_REGEX.matches(s)

    fun parseGrant(text: String): PairGrant = try {
        val o = JSONObject(text)
        val code = o.getString("code"); val token = o.getString("device_token")
        if (!Pairing.CODE_REGEX.matches(code) || !Pairing.TOKEN_REGEX.matches(token)) throw RelayException(0, "형식 오류")
        PairGrant(code, token, o.getLong("code_expires_in").coerceIn(0L, MAX_TTL_S), o.getLong("session_expires_in").coerceIn(0L, MAX_TTL_S))
    } catch (e: RelayException) { throw e } catch (t: Throwable) { throw RelayException(0, "형식 오류") }

    fun parseInbox(text: String): RemoteInbox = try {
        val o = JSONObject(text)
        val arr = o.getJSONArray("items")
        val items = ArrayList<RemoteItem>()
        for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
            val it = arr.optJSONObject(i) ?: continue
            val id = it.optString("id", "")
            val kind = ItemKind.fromCode(it.optString("kind", "")) ?: continue
            if (!ID_REGEX.matches(id)) continue
            val age = it.optLong("age_s", 0L).coerceAtLeast(0L)
            if (kind == ItemKind.LINK) {
                val url = it.optString("url", "")
                if (url.isEmpty() || url.length > LinkParser.MAX_LINK_CHARS) continue
                items += RemoteItem(id, kind, age, null, 0L, url)
            } else {
                val fmt = FileFormat.fromExt(it.optString("ext", "")) ?: continue
                val size = it.optLong("size", -1L)
                if (fmt.kind != kind || size <= 0L || size > FileRules.maxBytes(kind)) continue
                items += RemoteItem(id, kind, age, fmt.ext, size, null)
            }
        }
        RemoteInbox(o.getBoolean("claimed"), o.optLong("code_expires_in", 0L).coerceIn(0L, MAX_TTL_S), o.optLong("session_expires_in", 0L).coerceIn(0L, MAX_TTL_S), items)
    } catch (e: RelayException) { throw e } catch (t: Throwable) { throw RelayException(0, "형식 오류") }
}

/**
 * HTTP(S) 구현. 주소는 [com.hmgcarapp.crossdrop.domain.ServerUrlPolicy] 를 통과한 값만 받는다. 리다이렉트를 따라가지 않고,
 * 응답 크기를 제한하며, 인증 헤더(세션 토큰) 외에는 어떤 식별 헤더·쿠키도 보내지 않는다.
 */
class HttpRelayApi(private val baseUrl: String, private val timeoutMs: Int = 5_000) : RelayApi {
    override suspend fun createSession(): PairGrant = RelayWire.parseGrant(text("POST", "/v1/pair", null))

    override suspend fun inbox(token: String): RemoteInbox = RelayWire.parseInbox(String(call("GET", "/v1/inbox", token, RelayWire.MAX_INBOX_BYTES), Charsets.UTF_8))

    override suspend fun download(token: String, id: String, maxBytes: Int): ByteArray {
        require(RelayWire.isItemId(id))
        return call("GET", "/v1/item/$id", token, maxBytes)
    }

    override suspend fun deleteItem(token: String, id: String) { require(RelayWire.isItemId(id)); text("DELETE", "/v1/item/$id", token) }

    override suspend fun deleteSession(token: String) { text("DELETE", "/v1/session", token) }

    private suspend fun text(method: String, path: String, token: String?): String =
        String(call(method, path, token, RelayWire.MAX_RESPONSE_BYTES), Charsets.UTF_8)

    private suspend fun call(method: String, path: String, token: String?, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.useCaches = false
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (method == "POST") { c.setRequestProperty("Content-Type", "application/json"); c.doOutput = true; c.setFixedLengthStreamingMode(0) }   // 서버는 교차 출처 simple request 를 막으려 JSON 헤더를 요구한다
            val code = c.responseCode
            if (code !in 200..299) throw RelayException(code, "HTTP $code")
            if (c.contentLengthLong > maxBytes) throw RelayException(0, "응답이 너무 큼")
            readCapped(c.inputStream, maxBytes)
        } finally { c.disconnect() }
    }

    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        input.use { s ->
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                if (out.size() + n > max) throw RelayException(0, "응답이 너무 큼")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
