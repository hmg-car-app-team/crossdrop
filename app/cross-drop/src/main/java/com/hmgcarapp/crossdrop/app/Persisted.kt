package com.hmgcarapp.crossdrop.app

import com.hmgcarapp.crossdrop.domain.Destination
import com.hmgcarapp.crossdrop.domain.FileFormat
import com.hmgcarapp.crossdrop.domain.FileRules
import com.hmgcarapp.crossdrop.domain.InboxItem
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.domain.LinkParser
import com.hmgcarapp.crossdrop.domain.Pairing
import com.hmgcarapp.crossdrop.domain.PairingInfo
import org.json.JSONArray
import org.json.JSONObject

/** 디스크에 쓰는 전부. 기기 식별자·계정·원본 URL·원래 파일명은 없다. 시연 항목은 저장하지 않는다. */
data class Persisted(
    /** 서버 연결(휴대폰 연결)에 동의했는가. 기본 꺼짐. */
    val consent: Boolean = false,
    val pairing: PairingInfo? = null,
    val items: List<InboxItem> = emptyList(),
    val savedAtWallMs: Long = 0L,
)

object StateCodec {
    /** 연결 토큰 암호화 킬 스위치. 꺼도 이미 암호화된 값은 [sealer] 가 있으면 읽는다. */
    const val TOKEN_ENCRYPTION_ENABLED = true

    /** null 이면 암호화 도구가 없는 상태(단위 시험 기본): 평문으로 쓰고 'enc:v1:' 값은 열 수 없어 pairing 만 버린다. */
    @Volatile var sealer: TokenSealer? = null
    /** 암호화 저장 실패(pairing 은 저장하지 않음) / 읽은 토큰을 풀 수 없어 pairing 을 버림. 값은 넘기지 않는다. */
    @Volatile private var sealFailureReported = false
    /** 대입할 때(컨트롤러 시작) 알림 횟수를 다시 센다: 세션당 한 번만 알린다. */
    @Volatile var onSealFailure: (() -> Unit)? = null
        set(v) { sealFailureReported = false; field = v }
    @Volatile var onTokenDropped: (() -> Unit)? = null

    /** 마지막으로 봉인에 성공했거나 파일에서 열어 본 (평문, 봉인 문자열) 한 쌍. 메모리에만 두며 로그·알림에 쓰지 않는다. */
    private class SealedPair(val plain: String, val sealed: String)
    @Volatile private var lastPair: SealedPair? = null

    /** 시험용: 보관한 쌍을 비운다. */
    fun clearSealedCache() { lastPair = null }

    const val VERSION = 1
    const val MAX_ITEMS = 100
    const val MAX_NOTE = 100
    private val ID = Regex("^[0-9a-f]{16}$")

    data class Parsed(val p: Persisted, val corrupt: Boolean)

    fun encode(p: Persisted): JSONObject = JSONObject()
        .put("v", VERSION).put("consent", p.consent).put("savedAt", p.savedAtWallMs)
        .also { o ->
            if (p.pairing == null) lastPair = null // 연결 해제·전체 삭제 후 해제된 토큰 평문을 메모리에 남기지 않는다
            p.pairing?.let { it -> sealToken(it.deviceToken)?.let { tok -> it to tok } }?.let { (it, tokenText) ->
                o.put("pairing", JSONObject().put("code", it.code).put("token", tokenText).put("issued", it.issuedWallMs)
                    .put("codeExp", it.codeExpiresWallMs).put("sessionExp", it.sessionExpiresWallMs).put("claimed", it.claimed))
            }
        }
        .put("items", JSONArray().also { a ->
            p.items.filter { !it.demo }.takeLast(MAX_ITEMS).forEach { i ->
                val j = JSONObject().put("id", i.id).put("k", i.kind.code).put("at", i.receivedWallMs).put("used", i.used)
                i.ext?.let { j.put("ext", it) }
                if (i.sizeBytes > 0) j.put("sz", i.sizeBytes)
                i.localName?.let { j.put("ln", it) }
                i.note?.let { j.put("note", it) }
                i.dest?.let { d -> j.put("lat", d.lat).put("lon", d.lon); d.name?.let { n -> j.put("nm", n) } }
                a.put(j)
            }
        })

    /** 저장용 토큰 문자열. 암호화가 켜져 있는데 실패하면 null(평문으로 쓰지 않고 pairing 을 저장하지 않는다). */
    private fun sealToken(plain: String): String? {
        val s = sealer
        if (!TOKEN_ENCRYPTION_ENABLED || s == null) return plain
        return try {
            val sealed = s.seal(plain).takeIf { it.startsWith(TokenFormat.PREFIX) } ?: throw IllegalStateException("bad seal")
            lastPair = SealedPair(plain, sealed)
            sealed
        } catch (t: Throwable) {
            // 같은 토큰이면 이미 있던 암호문을 그대로 다시 쓴다(새 암호화 없음, 평문 저장 없음).
            lastPair?.takeIf { it.plain == plain }?.let { return it.sealed }
            if (!sealFailureReported) { sealFailureReported = true; runCatching { onSealFailure?.invoke() } }
            null
        }
    }

    /** 평문 구형식은 그대로, 'enc:v1:' 는 복호화. 실패는 null(평문 시도 없음). */
    private fun openToken(text: String): String? {
        if (!text.startsWith(TokenFormat.PREFIX)) return text
        val s = sealer ?: return null
        val opened = try { s.open(text) } catch (t: Throwable) { null }
        if (opened != null) lastPair = SealedPair(opened, text)
        return opened
    }

    /** 읽을 수 없는 JSON 이면 corrupt=true + 기본값. 값은 범위·형식으로 보정(sanitize)한다. */
    fun parse(text: String): Parsed = try {
        val o = JSONObject(text)
        val pairing = o.optJSONObject("pairing")?.let { p ->
            val code = p.optString("code", ""); val rawToken = p.optString("token", "")
            val token = openToken(rawToken) ?: ""
            if (rawToken.startsWith(TokenFormat.PREFIX) && token.isEmpty()) runCatching { onTokenDropped?.invoke() }
            if (Pairing.CODE_REGEX.matches(code) && Pairing.TOKEN_REGEX.matches(token))
                PairingInfo(code, token, p.optLong("issued", 0L).coerceAtLeast(0L), p.optLong("codeExp", 0L).coerceAtLeast(0L), p.optLong("sessionExp", 0L).coerceAtLeast(0L), p.optBoolean("claimed", false))
            else null
        }
        val items = ArrayList<InboxItem>()
        val seen = HashSet<String>()
        o.optJSONArray("items")?.let { a ->
            for (i in 0 until minOf(a.length(), MAX_ITEMS)) a.optJSONObject(i)?.let { j -> parseItem(j)?.let { if (seen.add(it.id)) items += it } }
        }
        Parsed(Persisted(o.optBoolean("consent", false), pairing, items, o.optLong("savedAt", 0L).coerceAtLeast(0L)), corrupt = false)
    } catch (t: Throwable) { Parsed(Persisted(), corrupt = true) }

    private fun parseItem(j: JSONObject): InboxItem? {
        val id = j.optString("id", "")
        val kind = ItemKind.fromCode(j.optString("k", "")) ?: return null
        if (!ID.matches(id)) return null
        val at = j.optLong("at", -1L).takeIf { it >= 0 } ?: return null
        val used = j.optBoolean("used", false)
        if (kind == ItemKind.LINK) {
            val dest = if (j.has("lat") && j.has("lon")) {
                val lat = j.optDouble("lat", Double.NaN); val lon = j.optDouble("lon", Double.NaN)
                if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0))
                    Destination(lat, lon, j.optString("nm", "").let { LinkParser.cleanName(it) }.takeIf { it.isNotEmpty() && it.length <= LinkParser.MAX_NAME_CHARS })
                else null
            } else null
            val note = j.optString("note", "").let { LinkParser.cleanName(it) }.take(MAX_NOTE).takeIf { it.isNotEmpty() }
            return InboxItem(id, kind, at, dest = dest, note = if (dest == null) note ?: "열 수 없는 링크" else null, used = used)
        }
        val ext = FileFormat.fromExt(j.optString("ext", ""))?.takeIf { it.kind == kind } ?: return null
        val ln = j.optString("ln", "").takeIf { FileRules.isSafeLocalName(it) && it.endsWith("." + ext.ext) } ?: return null
        return InboxItem(id, kind, at, ext = ext.ext, sizeBytes = j.optLong("sz", 0L).coerceIn(0L, FileRules.maxBytes(kind).toLong()), localName = ln, used = used)
    }
}
