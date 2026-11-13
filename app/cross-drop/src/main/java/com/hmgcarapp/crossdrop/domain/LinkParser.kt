package com.hmgcarapp.crossdrop.domain

import java.net.URLDecoder

/** 링크에서 읽어 낸 목적지. 좌표는 범위 검증을 통과한 값, 이름은 정리·길이 검증을 통과한 값(없으면 null). */
data class Destination(val lat: Double, val lon: Double, val name: String?)

/** 링크를 목적지로 쓸 수 없는 이유. [message] 는 사용자에게 그대로 보여 주는 한 줄. */
enum class LinkProblem(val message: String) {
    INVALID("올바른 링크가 아니에요"),
    TOO_LONG("링크가 너무 길어요"),
    NOT_HTTPS("https 링크만 열 수 있어요"),
    UNSUPPORTED_HOST("지원하지 않는 지도 서비스예요"),
    SHORT_LINK("단축 링크는 열 수 없어요"),
    NO_COORDINATES("링크에서 목적지 좌표를 찾지 못했어요"),
    OUT_OF_RANGE("목적지 좌표가 올바르지 않아요"),
    NAME_TOO_LONG("장소 이름이 너무 길어요"),
}

sealed interface LinkResult {
    data class Ok(val dest: Destination) : LinkResult
    data class Rejected(val problem: LinkProblem) : LinkResult
}

/**
 * 지도 공유 링크 -> 목적지 (순수 문자열 파싱; **링크를 따라가 확인(HTTP 요청)하지 않는다**: 추적·SSRF 위험).
 *
 * 허용: `https` + 정확히 일치하는 허용 호스트(서브도메인 와일드카드·포트·사용자 정보 없음). 단축 링크 호스트는 [LinkProblem.SHORT_LINK].
 * 지원 형식(좌표가 링크 안에 있는 것만):
 *  - 카카오맵 `/link/map|to/이름,위도,경도`
 *  - 네이버지도 `?lat=&lng=(또는 lon=)[&title=|name=]`
 *  - Apple 지도 `?ll=` · `?coordinate=` · `?daddr=위도,경도` (+ `q=`/`name=`)
 *  - 구글지도 `/maps/place/이름/@위도,경도,줌`, `!3d위도!4d경도`, `?q=|query=|ll=|daddr=|destination=위도,경도`
 * 검증: 길이·문자(공백·제어문자·역슬래시 거부), 좌표 범위와 (0,0) 거부, 이름 길이·제어/서식 문자 제거.
 */
object LinkParser {
    const val MAX_LINK_CHARS = 2048
    const val MAX_NAME_CHARS = 80
    private const val MAX_PARAMS = 50

    val SHORT_HOSTS = setOf("kko.to", "kko.kakao.com", "naver.me", "maps.app.goo.gl", "goo.gl")
    private val KAKAO = setOf("map.kakao.com", "m.map.kakao.com", "place.map.kakao.com")
    private val NAVER = setOf("map.naver.com", "m.map.naver.com")
    private val APPLE = setOf("maps.apple.com")
    private val GOOGLE = setOf("www.google.com", "google.com", "maps.google.com", "www.google.co.kr", "maps.google.co.kr")

    /** 서버(`server/crossdrop/config.py` LINK_HOSTS)와 같아야 하는 전체 허용 호스트. */
    val ALLOWED_HOSTS: Set<String> = SHORT_HOSTS + KAKAO + NAVER + APPLE + GOOGLE

    private val NUM = Regex("""-?\d{1,3}(?:\.\d{1,15})?""")
    private val PAIR = Regex("""^\s*(-?\d{1,3}(?:\.\d{1,15})?)\s*,\s*(-?\d{1,3}(?:\.\d{1,15})?)\s*$""")
    private val GOOGLE_PIN = Regex("""!3d(-?\d{1,3}(?:\.\d{1,15})?)!4d(-?\d{1,3}(?:\.\d{1,15})?)""")
    private val GOOGLE_AT = Regex("""@(-?\d{1,3}(?:\.\d{1,15})?),(-?\d{1,3}(?:\.\d{1,15})?)""")

    fun parse(raw: String?): LinkResult {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return no(LinkProblem.INVALID)
        if (s.length > MAX_LINK_CHARS) return no(LinkProblem.TOO_LONG)
        if (s.any { it.code < 0x21 || it.code == 0x7F || it == '\\' }) return no(LinkProblem.INVALID)
        if (hasLoneSurrogate(s)) return no(LinkProblem.INVALID)     // 짝 없는 서로게이트는 UTF-8 로 저장·직렬화할 수 없다(서버와 같은 규칙)
        if (!s.startsWith("https://", ignoreCase = true)) return no(LinkProblem.NOT_HTTPS)
        val rest = s.substring(8)
        val authEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authEnd)
        if (authority.isEmpty() || '@' in authority || ':' in authority) return no(LinkProblem.INVALID)
        val host = authority.lowercase()
        if (host.any { !(it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-') } || host.startsWith(".") || host.endsWith(".") || ".." in host) return no(LinkProblem.INVALID)
        if (host in SHORT_HOSTS) return no(LinkProblem.SHORT_LINK)
        if (host !in ALLOWED_HOSTS) return no(LinkProblem.UNSUPPORTED_HOST)

        val tail = rest.substring(authEnd).substringBefore('#')
        val path = tail.substringBefore('?')
        val query = if ('?' in tail) tail.substringAfter('?') else ""
        val params = try { parseQuery(query) } catch (e: IllegalArgumentException) { return no(LinkProblem.INVALID) }
        return try {
            when (host) {
                in KAKAO -> kakao(path)
                in NAVER -> naver(params)
                in APPLE -> apple(params)
                else -> google(host, path, params)
            }
        } catch (e: IllegalArgumentException) {
            no(LinkProblem.INVALID)
        }
    }

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) { if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) i++ else return true }
            else if (Character.isLowSurrogate(c)) return true
            i++
        }
        return false
    }

    // ---------------- 서비스별 ----------------
    private fun kakao(path: String): LinkResult {
        val seg = path.split('/')
        // ["", "link", "map"|"to", "이름,위도,경도"]
        if (seg.size != 4 || seg[0] != "" || seg[1] != "link" || (seg[2] != "map" && seg[2] != "to")) return no(LinkProblem.NO_COORDINATES)
        val parts = decode(seg[3], plusIsSpace = false).split(',')
        if (parts.size < 3) return no(LinkProblem.NO_COORDINATES)
        val lat = number(parts[parts.size - 2]) ?: return no(LinkProblem.NO_COORDINATES)
        val lon = number(parts[parts.size - 1]) ?: return no(LinkProblem.NO_COORDINATES)
        return finish(lat, lon, parts.dropLast(2).joinToString(","))
    }

    private fun naver(p: Map<String, String>): LinkResult {
        val lat = p["lat"]?.let(::number) ?: return no(LinkProblem.NO_COORDINATES)
        val lon = (p["lng"] ?: p["lon"])?.let(::number) ?: return no(LinkProblem.NO_COORDINATES)
        return finish(lat, lon, p["title"] ?: p["name"])
    }

    private fun apple(p: Map<String, String>): LinkResult {
        val pair = listOf("ll", "coordinate", "daddr").firstNotNullOfOrNull { k -> p[k]?.let(::pair) } ?: return no(LinkProblem.NO_COORDINATES)
        return finish(pair.first, pair.second, p["q"] ?: p["name"])
    }

    private fun google(host: String, path: String, p: Map<String, String>): LinkResult {
        // maps.google.* 는 경로와 무관하게 지도, 그 밖의 google 호스트는 /maps 아래만 지도
        if (!host.startsWith("maps.") && path != "/maps" && !path.startsWith("/maps/")) return no(LinkProblem.NO_COORDINATES)
        val decodedPath = decode(path, plusIsSpace = false)
        val pin = GOOGLE_PIN.find(decodedPath)?.let { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
        val fromQuery = listOf("q", "query", "ll", "daddr", "destination").firstNotNullOfOrNull { k -> p[k]?.let(::pair) }
        // '@위도,경도,줌' 은 지도 화면 중심일 뿐 목적지가 아니다: 장소 페이지(/maps/place/…)나 지도 위치(/maps/@…)일 때만 쓰고,
        // 길찾기(/maps/dir)·검색(/maps/search) 등에서는 확신할 수 없으므로 거부한다.
        val atAllowed = decodedPath.startsWith("/maps/place/") || decodedPath.startsWith("/maps/@")
        val at = if (atAllowed) GOOGLE_AT.find(decodedPath)?.let { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() } else null
        val (lat, lon) = pin ?: fromQuery ?: at ?: return no(LinkProblem.NO_COORDINATES)
        val seg = path.split('/')
        val name = if (seg.size >= 4 && seg[2] == "place") decode(seg[3], plusIsSpace = true) else null
        return finish(lat, lon, name)
    }

    // ---------------- 공통 ----------------
    private fun finish(lat: Double, lon: Double, rawName: String?): LinkResult {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return no(LinkProblem.OUT_OF_RANGE)
        val name = rawName?.let(::cleanName)
        if (name != null && name.length > MAX_NAME_CHARS) return no(LinkProblem.NAME_TOO_LONG)
        return LinkResult.Ok(Destination(lat, lon, name?.takeIf { it.isNotEmpty() }))
    }

    /** 제어·서식(양방향 제어 포함) 문자를 없애고 공백을 정리한다. */
    fun cleanName(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            val t = Character.getType(ch)
            if (t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt()) continue
            sb.append(if (Character.isWhitespace(ch)) ' ' else ch)
        }
        return sb.toString().trim().replace(Regex(" {2,}"), " ")
    }

    private fun number(s: String): Double? = if (NUM.matches(s.trim())) s.trim().toDouble() else null

    private fun pair(s: String): Pair<Double, Double>? {
        val m = PAIR.matchEntire(s) ?: return null
        return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in q.split('&').take(MAX_PARAMS)) {
            if (part.isEmpty()) continue
            val k = decode(part.substringBefore('='), plusIsSpace = true).lowercase()
            val v = if ('=' in part) decode(part.substringAfter('='), plusIsSpace = true) else ""
            if (k !in out) out[k] = v
        }
        return out
    }

    private fun decode(s: String, plusIsSpace: Boolean): String =
        URLDecoder.decode(if (plusIsSpace) s else s.replace("+", "%2B"), "UTF-8")

    private fun no(p: LinkProblem): LinkResult = LinkResult.Rejected(p)
}
