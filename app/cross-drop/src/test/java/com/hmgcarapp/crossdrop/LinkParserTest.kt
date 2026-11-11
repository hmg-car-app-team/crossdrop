package com.hmgcarapp.crossdrop

import com.hmgcarapp.crossdrop.domain.Destination
import com.hmgcarapp.crossdrop.domain.LinkParser
import com.hmgcarapp.crossdrop.domain.LinkProblem
import com.hmgcarapp.crossdrop.domain.LinkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {
    private fun ok(url: String?): Destination = (LinkParser.parse(url) as? LinkResult.Ok)?.dest ?: error("Ok 가 아님: ${LinkParser.parse(url)}")
    private fun bad(url: String?): LinkProblem = (LinkParser.parse(url) as? LinkResult.Rejected)?.problem ?: error("거부돼야 함: $url")

    // ---------------- 카카오맵 ----------------
    @Test fun kakao_linkMap_withKoreanName() {
        assertEquals(Destination(37.4979, 127.0276, "강남역"), ok("https://map.kakao.com/link/map/%EA%B0%95%EB%82%A8%EC%97%AD,37.4979,127.0276"))
    }
    @Test fun kakao_linkTo_and_commaInName_and_fragment() {
        assertEquals(Destination(37.5, 127.0, "Starbucks"), ok("https://map.kakao.com/link/to/Starbucks,37.5,127.0"))
        assertEquals("이름 안의 쉼표는 이름에 남는다", "A, B", ok("https://map.kakao.com/link/map/A%2C%20B,37.5,127.0").name)
        assertEquals(Destination(37.5, 127.0, "a"), ok("https://map.kakao.com/link/map/a,37.5,127.0#ignored"))
    }
    @Test fun kakao_withoutCoordinatesIsRejected() {
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://place.map.kakao.com/12345"))
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://map.kakao.com/?q=cafe"))
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://map.kakao.com/link/map/name,abc,127.0"))
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://map.kakao.com/link/roadview/a,37.5,127.0"))
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://map.kakao.com/link/map/37.5,127.0"))
        assertEquals("소수 15자리 초과는 좌표로 보지 않는다", LinkProblem.NO_COORDINATES, bad("https://map.kakao.com/link/map/a,37.1234567890123456,127.0"))
        assertEquals("지수 표기 거부", LinkProblem.NO_COORDINATES, bad("https://map.kakao.com/link/map/a,3e1,127.0"))
    }

    // ---------------- 네이버지도 ----------------
    @Test fun naver_latLngQuery() {
        assertEquals(Destination(37.5665, 126.978, "서울"), ok("https://map.naver.com/?lat=37.5665&lng=126.978&title=%EC%84%9C%EC%9A%B8"))
        assertEquals(Destination(37.5, 127.0, "x"), ok("https://m.map.naver.com/?lat=37.5&lon=127.0&name=x"))
        assertNull(ok("https://map.naver.com/?lat=37.5&lng=127.0").name)
    }
    @Test fun naver_withoutLatLngIsRejected() {
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://map.naver.com/p/search/%EC%84%9C%EC%9A%B8?c=14134246.7,4518010.7,15,0,0,0,dh"))
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://map.naver.com/?lat=37.5"))
    }

    // ---------------- Apple 지도 ----------------
    @Test fun apple_formats() {
        assertEquals(Destination(37.5133, 127.1028, "Lotte World"), ok("https://maps.apple.com/?ll=37.5133,127.1028&q=Lotte+World"))
        assertEquals(Destination(37.5, 127.0, "X"), ok("https://maps.apple.com/place?coordinate=37.5,127.0&name=X"))
        assertEquals(Destination(37.5, 127.0, null), ok("https://maps.apple.com/?daddr=37.5,127.0"))
        assertEquals("인코딩된 쉼표", Destination(37.5133, 127.1028, null), ok("https://maps.apple.com/?ll=37.5133%2C127.1028"))
        assertEquals(Destination(37.0, 127.0, null), ok("https://maps.apple.com/?ll=37,127"))
        assertEquals("ll 이 없으면 coordinate", Destination(1.5, 2.5, null), ok("https://maps.apple.com/?coordinate=1.5,2.5&daddr=3,4"))
    }
    @Test fun apple_addressOnlyIsRejected() {
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://maps.apple.com/?daddr=Seoul+Station"))
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://maps.apple.com/?q=cafe"))
    }

    // ---------------- 구글지도 ----------------
    @Test fun google_placeUrl_pinBeatsViewport() {
        val d = ok("https://www.google.com/maps/place/Seoul+Station/@37.5547,126.9707,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!7e2!8m2!3d37.5559!4d126.9723")
        assertEquals(Destination(37.5559, 126.9723, "Seoul Station"), d)
    }
    @Test fun google_viewportQueryAndDirections() {
        assertEquals(Destination(37.5547, 126.9707, "Seoul Station"), ok("https://www.google.com/maps/place/Seoul+Station/@37.5547,126.9707,17z"))
        assertEquals(Destination(37.5, 127.0, null), ok("https://www.google.com/maps?q=37.5,127.0"))
        assertEquals(Destination(37.5, 127.0, null), ok("https://maps.google.com/?q=37.5,127.0"))
        assertEquals(Destination(37.5, 127.0, null), ok("https://www.google.com/maps/dir/?api=1&destination=37.5,127.0"))
        assertEquals(Destination(37.5, 127.0, null), ok("https://www.google.com/maps/search/?api=1&query=37.5%2C127.0"))
        assertEquals(Destination(37.5, 127.0, null), ok("https://www.google.com/maps/@37.5,127.0,15z"))
        assertEquals(Destination(-33.8688, 151.2093, null), ok("https://www.google.co.kr/maps?ll=-33.8688,151.2093"))
    }
    @Test fun google_atViewportOnlyOnPlaceOrAtPaths() {
        // 길찾기·검색 화면의 '@위도,경도,줌' 은 지도 중심일 뿐 목적지가 아니다 -> 거부(확신이 없으면 거부)
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://www.google.com/maps/dir/Seoul+Station/Busan/@36.5,127.9,7z"))
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://www.google.com/maps/search/cafe/@37.5,127.0,15z"))
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://www.google.com/maps/dir//@37.5,127.0,15z"))
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://www.google.com/maps/contrib/123/@37.5,127.0,15z"))
        assertEquals(Destination(37.5, 127.0, "Cafe"), ok("https://www.google.com/maps/place/Cafe/@37.5,127.0,15z"))
        assertEquals(Destination(37.5, 127.0, null), ok("https://www.google.com/maps/@37.5,127.0,15z"))
        // 같은 경로라도 핀(!3d!4d)이나 destination/query 가 있으면 그 값
        assertEquals(Destination(10.5, 20.5, null), ok("https://www.google.com/maps/dir/A/B/@36.5,127.9,7z/data=!3d10.5!4d20.5"))
        assertEquals(Destination(37.1, 127.1, null), ok("https://www.google.com/maps/dir/?api=1&destination=37.1,127.1"))
    }

    @Test fun google_nonMapPathsAndTextQueriesRejected() {
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://www.google.com/search?q=37.5,127.0"))
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://www.google.com/maps?q=Seoul+Station"))
        assertEquals(LinkProblem.NO_COORDINATES, bad("https://www.google.com/maps/place/Foo"))
    }

    // ---------------- 단축 링크 ----------------
    @Test fun shortLinksAreNotFollowed() {
        for (u in listOf("https://kko.to/abc123", "https://naver.me/xYz", "https://maps.app.goo.gl/Ab12", "https://goo.gl/maps/Ab12", "https://kko.kakao.com/x")) {
            assertEquals(u, LinkProblem.SHORT_LINK, bad(u))
        }
    }

    // ---------------- 악성·경계 입력 ----------------
    @Test fun schemeMustBeHttps() {
        assertEquals(LinkProblem.NOT_HTTPS, bad("http://map.kakao.com/link/map/a,37.5,127.0"))
        assertEquals(LinkProblem.NOT_HTTPS, bad("javascript:alert(1)"))
        assertEquals(LinkProblem.NOT_HTTPS, bad("file:///etc/passwd"))
        assertEquals(LinkProblem.NOT_HTTPS, bad("intent://map.kakao.com#Intent;scheme=https;end"))
        assertEquals(LinkProblem.NOT_HTTPS, bad("kakaomap://look?p=37.5,127.0"))
        assertEquals(LinkProblem.NOT_HTTPS, bad("map.kakao.com/link/map/a,37.5,127.0"))
        assertEquals(Destination(37.5, 127.0, "a"), ok("HTTPS://MAP.KAKAO.COM/link/map/a,37.5,127.0"))
    }
    @Test fun hostMustMatchAllowListExactly() {
        assertEquals(LinkProblem.UNSUPPORTED_HOST, bad("https://map.kakao.com.evil.com/link/map/a,37.5,127.0"))
        assertEquals(LinkProblem.UNSUPPORTED_HOST, bad("https://evil.com/?u=https://map.kakao.com/link/map/a,37.5,127.0"))
        assertEquals(LinkProblem.UNSUPPORTED_HOST, bad("https://notmap.kakao.com/link/map/a,37.5,127.0"))
        assertEquals(LinkProblem.UNSUPPORTED_HOST, bad("https://sub.maps.apple.com/?ll=37.5,127.0"))
        assertEquals(LinkProblem.UNSUPPORTED_HOST, bad("https://bit.ly/abc"))
        assertEquals(LinkProblem.UNSUPPORTED_HOST, bad("https://127.0.0.1/maps?ll=37.5,127.0"))
        assertEquals(LinkProblem.UNSUPPORTED_HOST, bad("https://localhost/maps?ll=37.5,127.0"))
        assertEquals(LinkProblem.UNSUPPORTED_HOST, bad("https://xn--map-kakao.com/x"))
    }
    @Test fun authorityTricksAreInvalid() {
        assertEquals(LinkProblem.INVALID, bad("https://user:pw@map.kakao.com/link/map/a,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com@" + "evil.com/link/map/a,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com:8443/link/map/a,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com:443/link/map/a,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com\\@evil.com/"))
        assertEquals(LinkProblem.INVALID, bad("https:///map.kakao.com/link/map/a,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://map..kakao.com/x"))
        assertEquals(LinkProblem.INVALID, bad("https://.map.kakao.com/x"))
        assertEquals("키릴 문자 'а' 가 섞인 호스트", LinkProblem.INVALID, bad("https://mаp.kakao.com/link/map/a,37.5,127.0"))
    }
    @Test fun whitespaceAndControlCharactersAreInvalid() {
        assertEquals(LinkProblem.INVALID, bad(null))
        assertEquals(LinkProblem.INVALID, bad(""))
        assertEquals(LinkProblem.INVALID, bad("   "))
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com/link/map/a b,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com/link/map/a\nb,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com/link/map/a\u0000,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com/link/map/a\u007F,37.5,127.0"))
        assertEquals("앞뒤 공백은 정리", Destination(37.5, 127.0, "a"), ok("  https://map.kakao.com/link/map/a,37.5,127.0\n"))
    }
    @Test fun badPercentEncodingIsInvalid() {
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com/link/map/%ZZ,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com/link/map/a%,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://maps.apple.com/?ll=37.5,127.0&q=%E0%A4%A"))
        assertEquals("유효하지 않은 UTF-8 은 대체 문자로 읽히며 거부하지는 않는다", 37.5, ok("https://map.kakao.com/link/map/%FF,37.5,127.0").lat, 0.0)
    }
    @Test fun lengthBoundaryIsExactly2048() {
        val prefix = "https://map.kakao.com/link/map/"
        val suffix = ",37.5,127.0"
        fun url(total: Int) = prefix + "a".repeat(total - prefix.length - suffix.length) + suffix
        assertEquals("2048자는 길이 검사를 통과(이름이 길어 다른 이유로 거부)", LinkProblem.NAME_TOO_LONG, bad(url(2048)))
        assertEquals(LinkProblem.TOO_LONG, bad(url(2049)))
    }

    // ---------------- 좌표·이름 검증 ----------------
    @Test fun coordinateRangeBoundaries() {
        assertEquals(Destination(90.0, 180.0, "a"), ok("https://map.kakao.com/link/map/a,90,180"))
        assertEquals(Destination(-90.0, -180.0, "a"), ok("https://map.kakao.com/link/map/a,-90,-180"))
        assertEquals(LinkProblem.OUT_OF_RANGE, bad("https://map.kakao.com/link/map/a,90.0001,127.0"))
        assertEquals(LinkProblem.OUT_OF_RANGE, bad("https://map.kakao.com/link/map/a,37.5,180.0001"))
        assertEquals(LinkProblem.OUT_OF_RANGE, bad("https://map.kakao.com/link/map/a,-91,127.0"))
        assertEquals(LinkProblem.OUT_OF_RANGE, bad("https://map.kakao.com/link/map/a,37.5,-181"))
        assertEquals("(0,0) 은 잘못된 좌표", LinkProblem.OUT_OF_RANGE, bad("https://map.kakao.com/link/map/a,0,0"))
        assertEquals(Destination(0.0, 127.0, "a"), ok("https://map.kakao.com/link/map/a,0,127"))
        assertEquals("위경도가 바뀐 흔한 실수(경도 127 이 위도 자리)", LinkProblem.OUT_OF_RANGE, bad("https://map.kakao.com/link/map/a,127.0,37.5"))
    }
    @Test fun nameBoundariesAndSanitizing() {
        fun kakao(name: String) = "https://map.kakao.com/link/map/$name,37.5,127.0"
        assertEquals(80, ok(kakao("a".repeat(80))).name!!.length)
        assertEquals(LinkProblem.NAME_TOO_LONG, bad(kakao("a".repeat(81))))
        assertEquals("제어·방향 전환(RLO) 문자 제거", "AB", ok(kakao("%00A%E2%80%AEB")).name)
        assertNull("제어 문자뿐이면 이름 없음", ok(kakao("%00%01")).name)
        assertEquals("공백 정리", "a b", ok(kakao("%20%20a%20%20%20b%20")).name)
        assertEquals("마크업은 실행되지 않는 일반 글자", "<script>alert(1)</script>", ok(kakao("%3Cscript%3Ealert(1)%3C%2Fscript%3E")).name)
    }
    @Test fun firstDuplicateParamWins_andParamCountIsCapped() {
        assertEquals(37.5, ok("https://maps.apple.com/?ll=37.5,127.0&ll=10,10").lat, 0.0)
        val many = (1..60).joinToString("&") { "p$it=1" }
        assertEquals("50개 뒤의 매개변수는 읽지 않는다", LinkProblem.NO_COORDINATES, bad("https://maps.apple.com/?$many&ll=37.5,127.0"))
    }
    @Test fun allowedHostsMatchServerList() {
        val expected = setOf(
            "map.kakao.com", "m.map.kakao.com", "place.map.kakao.com", "kko.to", "kko.kakao.com", "map.naver.com", "m.map.naver.com", "naver.me",
            "maps.apple.com", "www.google.com", "google.com", "maps.google.com", "www.google.co.kr", "maps.google.co.kr", "maps.app.goo.gl", "goo.gl",
        )
        assertEquals(expected, LinkParser.ALLOWED_HOSTS)
        assertTrue(LinkParser.SHORT_HOSTS.all { it in LinkParser.ALLOWED_HOSTS })
    }

    @Test fun loneSurrogatesAreInvalid_butKoreanAndEmojiPass() {
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com/link/map/x\uD800,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://map.kakao.com/link/map/x\uDFFF,37.5,127.0"))
        assertEquals(LinkProblem.INVALID, bad("https://maps.apple.com/?ll=37.5,127.0&q=\uD83D"))
        assertEquals("강남역😀", ok("https://map.kakao.com/link/map/강남역\uD83D\uDE00,37.5,127.0").name)
    }
}

