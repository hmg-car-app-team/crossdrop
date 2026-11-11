package com.hmgcarapp.crossdrop

import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.crossdrop.app.DemoAssets
import com.hmgcarapp.crossdrop.app.FileVault
import com.hmgcarapp.crossdrop.app.Persisted
import com.hmgcarapp.crossdrop.app.StateCodec
import com.hmgcarapp.crossdrop.app.StateStore
import com.hmgcarapp.crossdrop.app.WriteResult
import com.hmgcarapp.crossdrop.domain.Destination
import com.hmgcarapp.crossdrop.domain.FileFormat
import com.hmgcarapp.crossdrop.domain.InboxItem
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.domain.LinkParser
import com.hmgcarapp.crossdrop.domain.LinkProblem
import com.hmgcarapp.crossdrop.domain.LinkResult
import com.hmgcarapp.crossdrop.domain.Pairing
import com.hmgcarapp.crossdrop.domain.PairingInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersistTest {
    @get:Rule val tmp = TemporaryFolder()
    private val tok = "c".repeat(64)

    private val file = InboxItem("0123456789abcdef", ItemKind.IMAGE, 111, ext = "jpg", sizeBytes = 2048, localName = "f_0123456789abcdef.jpg", used = false)
    private val link = InboxItem("1123456789abcdef", ItemKind.LINK, 222, dest = Destination(37.5, 127.0, "강남역"), used = true)
    private val badLink = InboxItem("2123456789abcdef", ItemKind.LINK, 333, note = "단축 링크는 열 수 없어요. 전체 주소 링크를 보내 주세요")
    private val full = Persisted(true, PairingInfo("ABCD2345", tok, 1, 601_000, 86_400_000, true), listOf(file, link, badLink), 999)

    @Test fun roundTrip() {
        val back = StateCodec.parse(StateCodec.encode(full).toString())
        assertFalse(back.corrupt); assertEquals(full, back.p)
    }

    @Test fun defaultsAreNoConsentAndEmpty() {
        val p = StateCodec.parse("{}").p
        assertFalse("동의 기본값은 꺼짐", p.consent); assertNull(p.pairing); assertTrue(p.items.isEmpty())
        assertFalse(Persisted().consent)
    }

    @Test fun demoItemsAreNeverEncoded_andNoUrlOrNameOfFileIsStored() {
        val text = StateCodec.encode(full.copy(items = full.items + file.copy(id = "3123456789abcdef", demo = true))).toString()
        assertEquals(3, JSONObject(text).getJSONArray("items").length())
        assertFalse(text.contains("http")); assertFalse(text.contains("evil"))
        assertEquals(setOf("id", "k", "at", "used", "ext", "sz", "ln"), JSONObject(text).getJSONArray("items").getJSONObject(0).keys().asSequence().toSet())
        assertEquals(setOf("v", "consent", "savedAt", "pairing", "items"), JSONObject(text).keys().asSequence().toSet())
    }

    @Test fun corruptJsonIsFlagged() {
        for (t in listOf("", "not json", "[1,2", "{\"items\": 5")) assertTrue(t, StateCodec.parse(t).corrupt)
    }

    @Test fun parseSanitizesTamperedFields() {
        val json = """{"consent":true,"pairing":{"code":"abcd2345","token":"$tok","issued":1,"codeExp":2,"sessionExp":3},"items":[
          {"id":"0123456789abcdef","k":"image","at":1,"ext":"png","ln":"../../x.png"},
          {"id":"1123456789abcdef","k":"image","at":1,"ext":"png","ln":"f_0123456789abcdef.jpg"},
          {"id":"2123456789abcdef","k":"image","at":1,"ext":"png","ln":"f_0123456789abcdef.png","sz":99999999999},
          {"id":"3123456789abcdef","k":"pdf","at":1,"ext":"png","ln":"f_0123456789abcdef.png"},
          {"id":"4123456789abcdef","k":"video","at":1},
          {"id":"bad","k":"link","at":1},
          {"id":"5123456789abcdef","k":"link","at":-1},
          {"id":"6123456789abcdef","k":"link","at":1,"lat":95.0,"lon":127.0,"nm":"x"},
          {"id":"7123456789abcdef","k":"link","at":1,"lat":0.0,"lon":0.0},
          {"id":"8123456789abcdef","k":"link","at":1,"lat":37.5,"lon":127.0,"nm":"ok"},
          {"id":"8123456789abcdef","k":"link","at":1,"lat":10.5,"lon":127.0,"nm":"dup"}]}"""
        val p = StateCodec.parse(json).p
        assertNull("대문자·소문자 규칙 위반 코드는 연결 정보 전체를 버린다", p.pairing)
        assertEquals(listOf("2123456789abcdef", "6123456789abcdef", "7123456789abcdef", "8123456789abcdef"), p.items.map { it.id })
        assertEquals("크기는 종류별 상한으로 보정", 10_485_760L, p.items[0].sizeBytes)
        assertNull("범위 밖 좌표는 목적지 없음", p.items[1].dest); assertNotNull(p.items[1].note)
        assertNull("(0,0) 거부", p.items[2].dest)
        assertEquals(Destination(37.5, 127.0, "ok"), p.items[3].dest)
    }

    @Test fun vaultStoresUnderAppGeneratedNamesOnly() {
        val v = FileVault(tmp.newFolder("vault"), random = { byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) })
        val name = v.save(FileFormat.PNG, byteArrayOf(9, 9, 9))
        assertEquals("f_0102030405060708.png", name)
        assertEquals(listOf<Byte>(9, 9, 9), v.resolve(name)!!.readBytes().toList())
        assertNull(v.resolve("../vault/$name")); assertNull(v.resolve("f_zz.png")); assertNull(v.resolve(null)); assertNull(v.resolve("f_0000000000000000.png"))
        assertTrue(v.delete(name)); assertNull(v.resolve(name)); assertTrue("없는 파일 삭제도 성공", v.delete(name)); assertTrue("안전하지 않은 이름은 건드리지 않고 통과", v.delete("../x"))
    }

    @Test fun vaultPruneAndPurge() {
        val dir = tmp.newFolder("v2")
        var n = 0
        val v = FileVault(dir, random = { ByteArray(8) { (n++).toByte() } })
        val a = v.save(FileFormat.PDF, byteArrayOf(1)); val b = v.save(FileFormat.JPG, byteArrayOf(2)); File(dir, "f_leftover.tmp").writeText("x")
        assertEquals(2, v.pruneOrphans(setOf(a))); assertEquals(setOf(a), v.names())
        assertTrue(v.purgeAll()); assertTrue(v.names().isEmpty()); assertNull(v.resolve(b))
    }

    @Test fun stateStoreRoundTripAndMissing() {
        val store = StateStore(LocalJsonStore(tmp.newFolder("s1")), "t")
        assertEquals(Persisted(), store.restore(0).p)
        assertEquals(WriteResult.WROTE, store.writeLatest { full })
        assertEquals(WriteResult.UNCHANGED, store.writeLatest { full })
        val back = StateStore(LocalJsonStore(File(tmp.root, "s1")), "t").restore(0)
        assertEquals(full, back.p); assertFalse(back.corrupt)
    }

    @Test fun stateStoreQuarantinesCorruptFileAndClearAllDeletes() {
        val dir = tmp.newFolder("s2")
        File(dir, "t.json").writeText("garbage{")
        val store = StateStore(LocalJsonStore(dir), "t")
        val r = store.restore(0)
        assertTrue(r.corrupt && r.quarantined); assertEquals(Persisted(), r.p); assertFalse(File(dir, "t.json").exists())
        store.writeLatest { full }
        assertTrue(store.clearAll { Persisted() })
        assertEquals(Persisted(), StateCodec.parse(File(dir, "t.json").readText()).p)
    }

    // ---------------- 시연 데이터 ----------------
    @Test fun demoLinksParseThroughTheRealParser() {
        fun dest(n: String) = (LinkParser.parse(DemoAssets.link(n)) as LinkResult.Ok).dest
        assertEquals(Destination(37.4979, 127.0276, "강남역"), dest("kakao"))
        assertEquals(Destination(37.5665, 126.978, "서울시청"), dest("naver"))
        assertEquals(Destination(37.5547, 126.9707, "Seoul Station"), dest("google"))
        assertEquals(Destination(37.5133, 127.1028, "Lotte World"), dest("apple"))
        assertEquals(LinkProblem.SHORT_LINK, (LinkParser.parse(DemoAssets.link("short")) as LinkResult.Rejected).problem)
        assertEquals(LinkProblem.UNSUPPORTED_HOST, (LinkParser.parse(DemoAssets.link("bad")) as LinkResult.Rejected).problem)
        assertNull(DemoAssets.link("nope")); assertTrue(DemoAssets.hostsOk())
    }

    @Test fun demoPairingConstantsAreValid() {
        assertTrue(Pairing.CODE_REGEX.matches(DemoAssets.DEMO_CODE)); assertTrue(Pairing.TOKEN_REGEX.matches(DemoAssets.DEMO_TOKEN))
        assertEquals("https://demo.invalid/u#SHWTM234", Pairing.qrPayload(DemoAssets.DEMO_BASE_URL, DemoAssets.DEMO_CODE))
    }
}
