package com.hmgcarapp.crossdrop

import com.hmgcarapp.crossdrop.domain.QrCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class QrCodeTest {
    private fun ints(b: ByteArray) = b.map { it.toInt() and 0xFF }

    @Test fun capacityPerVersionForEccM() {
        assertEquals(listOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213), (1..10).map { QrCode.capacityBytes(it) })
    }

    @Test fun sizeGrowsWithLengthAndTooLongIsRejected() {
        fun size(n: Int) = QrCode.encode("a".repeat(n)).size
        assertEquals(21, size(14)); assertEquals(25, size(15)); assertEquals(25, size(26)); assertEquals(29, size(27)); assertEquals(57, size(213))
        try { QrCode.encode("a".repeat(214)); fail("214바이트는 담을 수 없다") } catch (e: IllegalArgumentException) { }
        assertEquals("멀티바이트는 바이트 수로 센다: 한글 4자 = 12바이트", 21, QrCode.encode("한글한글").size)
    }

    @Test fun formatBitsForEccM() {
        val expected = listOf("101010000010010", "101000100100101", "101111001111100", "101101101001011", "100010111111001", "100000011001110", "100111110010111", "100101010100000")
        assertEquals(expected, (0..7).map { Integer.toBinaryString(QrCode.formatBits(it)).padStart(15, '0') })
        assertEquals(0x5412, QrCode.formatBits(0))
    }

    @Test fun versionInfoBits() {
        assertEquals(0x07C94, QrCode.versionBits(7)); assertEquals(0x085BC, QrCode.versionBits(8)); assertEquals(0x0A4D3, QrCode.versionBits(10))
    }

    @Test fun galoisFieldBasics() {
        assertEquals("x^8 = x^4+x^3+x^2+1", 0x1D, QrCode.gfMul(0x02, 0x80)); assertEquals(0x1D, QrCode.gfMul(0x80, 0x02))
        assertEquals(0, QrCode.gfMul(0, 0xAB)); assertEquals(0xAB, QrCode.gfMul(1, 0xAB))
        for (a in listOf(3, 29, 77, 200, 255)) for (b in listOf(2, 19, 128, 254)) assertEquals(QrCode.gfMul(a, b), QrCode.gfMul(b, a))
    }

    @Test fun reedSolomonGeneratorDegree7() {
        assertEquals(listOf(127, 122, 154, 164, 11, 68, 117), ints(QrCode.rsDivisor(7)))
        // 독립 계산: 지수 표(α^e)에서 (x-α^0)…(x-α^6) 전개
        val exp = IntArray(256); var v = 1
        for (i in 0 until 255) { exp[i] = v; v = (v shl 1) xor (if (v >= 128) 0x11D else 0) }
        assertEquals(listOf(87, 229, 146, 149, 238, 102, 21).map { exp[it] }, ints(QrCode.rsDivisor(7)))
    }

    @Test fun reedSolomonIsoExample() {
        // ISO/IEC 18004 부록 예제: 버전 1-M, 숫자 "01234567"
        val data = intArrayOf(0x10, 0x20, 0x0C, 0x56, 0x61, 0x80, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11).map { it.toByte() }.toByteArray()
        val ecc = QrCode.rsRemainder(data, QrCode.rsDivisor(10))
        assertEquals(listOf(0xA5, 0x24, 0xD4, 0xC1, 0xED, 0x36, 0xC7, 0x87, 0x2C, 0x55), ints(ecc))
    }

    @Test fun codewordCountsMatchSpec() {
        for (v in 1..10) assertEquals("버전 $v 총 코드워드", QrCode.numRawDataModules(v) / 8, QrCode.buildCodewords(v, ByteArray(1)).size)
        assertEquals(listOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346), (1..10).map { QrCode.numRawDataModules(it) / 8 })
    }

    @Test fun structuralPatternsVersion1() {
        val m = QrCode.encode("HELLO")
        assertEquals(21, m.size)
        for (x in 0..6) { assertTrue(m[x, 0]); assertTrue(m[x, 6]) }
        for (y in 0..6) { assertTrue(m[0, y]); assertTrue(m[6, y]) }
        assertFalse(m[1, 1]); assertTrue(m[2, 2]); assertTrue(m[3, 3]); assertFalse("분리선", m[7, 0]); assertFalse(m[7, 7])
        assertTrue("오른쪽 위 finder", m[20, 0]); assertTrue("왼쪽 아래 finder", m[0, 20])
        assertTrue("타이밍 짝수", m[8, 6]); assertFalse("타이밍 홀수", m[9, 6]); assertTrue(m[10, 6]); assertTrue(m[6, 8]); assertFalse(m[6, 9])
        assertTrue("항상 어두운 모듈", m[8, 13])
    }

    @Test fun alignmentPatternVersion2() {
        val m = QrCode.encode("a".repeat(15))
        assertEquals(25, m.size)
        assertTrue(m[18, 18]); assertFalse(m[17, 18]); assertTrue(m[16, 18]); assertTrue(m[16, 16]); assertFalse(m[17, 17])
    }

    @Test fun deterministicAndContentDependent() {
        fun dump(t: String) = QrCode.encode(t).let { m -> (0 until m.size).joinToString("") { y -> (0 until m.size).joinToString("") { x -> if (m[x, y]) "1" else "0" } } }
        assertEquals(dump("https://relay.example/u#ABCD2345"), dump("https://relay.example/u#ABCD2345"))
        assertNotEquals(dump("https://relay.example/u#ABCD2345"), dump("https://relay.example/u#ABCD2346"))
    }

    // ---------------- 행렬을 역으로 읽는 독립 검증기 ----------------
    // 생성기 코드를 쓰지 않고 표준만으로: 형식 정보 BCH -> 지그재그로 코드워드 복원 -> 디마스크 -> 블록 분해 -> 블록별 RS 신드롬 0 -> 페이로드 복원.
    private class Spec(val ecc: Int, val blocks: Int, val align: IntArray)
    private val specs = mapOf(
        1 to Spec(10, 1, intArrayOf()), 5 to Spec(24, 2, intArrayOf(6, 30)), 7 to Spec(18, 4, intArrayOf(6, 22, 38)), 10 to Spec(26, 5, intArrayOf(6, 28, 50)),
    )
    private val exp = IntArray(512).also { var v = 1; for (i in 0 until 255) { it[i] = v; v = (v shl 1) xor (if (v >= 128) 0x11D else 0) }; for (i in 255 until 512) it[i] = it[i - 255] }
    private fun mul(a: Int, b: Int): Int { if (a == 0 || b == 0) return 0; val log = IntArray(256).also { for (i in 0 until 255) it[exp[i]] = i }; return exp[log[a] + log[b]] }

    private fun isFunction(ver: Int, size: Int, x: Int, y: Int): Boolean {
        if (x <= 8 && y <= 8) return true
        if (x >= size - 8 && y <= 8) return true
        if (x <= 8 && y >= size - 8) return true
        if (x == 6 || y == 6) return true
        if (ver >= 7 && ((x >= size - 11 && x <= size - 9 && y <= 5) || (y >= size - 11 && y <= size - 9 && x <= 5))) return true
        val a = specs.getValue(ver).align
        for (i in a.indices) for (j in a.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == a.size - 1) || (i == a.size - 1 && j == 0)) continue
            if (Math.abs(x - a[i]) <= 2 && Math.abs(y - a[j]) <= 2) return true
        }
        return false
    }

    private fun maskBit(mask: Int, x: Int, y: Int) = when (mask) {
        0 -> (x + y) % 2 == 0; 1 -> y % 2 == 0; 2 -> x % 3 == 0; 3 -> (x + y) % 3 == 0; 4 -> (x / 3 + y / 2) % 2 == 0
        5 -> x * y % 2 + x * y % 3 == 0; 6 -> (x * y % 2 + x * y % 3) % 2 == 0; else -> ((x + y) % 2 + x * y % 3) % 2 == 0
    }

    /** 행렬에서 읽어 낸 (마스크 번호, 페이로드 문자열). 구조가 어긋나면 실패. */
    private fun decode(m: com.hmgcarapp.crossdrop.domain.QrMatrix, ver: Int): String {
        val n = m.size; val sp = specs.getValue(ver)
        assertEquals(ver * 4 + 17, n)
        // 형식 정보: 두 사본이 같고, 15비트가 BCH(0x537) 로 나누어떨어지며, 오류 정정 수준 M(00)
        fun bit(x: Int, y: Int) = if (m[x, y]) 1 else 0
        var f1 = 0; for (i in 0..5) f1 = f1 or (bit(8, i) shl i); f1 = f1 or (bit(8, 7) shl 6) or (bit(8, 8) shl 7) or (bit(7, 8) shl 8); for (i in 9..14) f1 = f1 or (bit(14 - i, 8) shl i)
        var f2 = 0; for (i in 0..7) f2 = f2 or (bit(n - 1 - i, 8) shl i); for (i in 8..14) f2 = f2 or (bit(8, n - 15 + i) shl i)
        assertEquals("형식 정보 사본 두 개가 같아야 한다", f1, f2)
        val fmt = f1 xor 0x5412
        var r = fmt; for (i in 14 downTo 10) if ((r ushr i) and 1 == 1) r = r xor (0x537 shl (i - 10))
        assertEquals("형식 정보 BCH 나머지", 0, r)
        assertEquals("오류 정정 수준 M", 0, fmt ushr 13)
        val mask = (fmt ushr 10) and 7
        assertTrue(m[8, n - 8])   // 항상 어두운 모듈
        // 지그재그로 비기능 모듈 읽기 + 디마스크
        val bits = ArrayList<Int>()
        var right = n - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until n) for (j in 0..1) {
                val x = right - j; val up = ((right + 1) and 2) == 0; val y = if (up) n - 1 - vert else vert
                if (!isFunction(ver, n, x, y)) bits += bit(x, y) xor (if (maskBit(mask, x, y)) 1 else 0)
            }
            right -= 2
        }
        val raw = bits.size / 8
        assertEquals("비기능 모듈 수 = 코드워드 x 8 + 나머지 비트(버전 ${ver})", raw, QrCode.numRawDataModules(ver) / 8)
        val cw = IntArray(raw) { i -> var b = 0; for (k in 0 until 8) b = (b shl 1) or bits[i * 8 + k]; b }
        // 블록 분해(데이터 먼저 열 단위로 끼워 넣고, 그다음 ECC)
        val shortLen = raw / sp.blocks; val numShort = sp.blocks - raw % sp.blocks
        val dataLens = IntArray(sp.blocks) { shortLen - sp.ecc + (if (it < numShort) 0 else 1) }
        val blocks = Array(sp.blocks) { ArrayList<Int>() }
        var k = 0
        for (i in 0 until dataLens.max()) for (b in 0 until sp.blocks) if (i < dataLens[b]) blocks[b] += cw[k++]
        for (i in 0 until sp.ecc) for (b in 0 until sp.blocks) blocks[b] += cw[k++]
        assertEquals(raw, k)
        // 블록별 신드롬: 데이터+ECC 다항식이 α^0..α^(ecc-1) 에서 0
        for ((bi, blk) in blocks.withIndex()) for (e in 0 until sp.ecc) {
            var acc = 0
            for (c in blk) acc = mul(acc, exp[e]) xor c
            assertEquals("버전 $ver 블록 $bi 신드롬 $e", 0, acc)
        }
        // 페이로드 복원: 모드 0100, 글자 수, 바이트
        val all = ArrayList<Int>(); for (b in 0 until sp.blocks) all += blocks[b].take(dataLens[b])
        val stream = all.flatMap { c -> (7 downTo 0).map { (c ushr it) and 1 } }
        fun take(from: Int, len: Int) = stream.subList(from, from + len).fold(0) { a, v -> (a shl 1) or v }
        assertEquals("모드 지시자(바이트)", 4, take(0, 4))
        val cb = if (ver <= 9) 8 else 16
        val count = take(4, cb)
        val bytes = ByteArray(count) { take(4 + cb + it * 8, 8).toByte() }
        return String(bytes, Charsets.UTF_8)
    }

    @Test fun matrixDecodesBackToTheTextWithValidReedSolomon_v1_v5_v7_v10() {
        val cases = mapOf(
            1 to "HELLO QR",
            5 to "https://relay.example/u#" + "ABCD2345".repeat(6),
            7 to "x1".repeat(55),
            10 to "https://relay.example/" + "z".repeat(190),
        )
        for ((ver, text) in cases) {
            val m = QrCode.encode(text)
            assertEquals("테스트 입력이 의도한 버전이어야 한다", ver * 4 + 17, m.size)
            assertEquals(text, decode(m, ver))
        }
    }

    @Test fun verifierDetectsCorruptedMatrices() {
        // 검증기 자체가 의미 있는지: 데이터 모듈 하나만 뒤집어도 신드롬/페이로드 검사가 실패해야 한다
        val text = "https://relay.example/u#" + "ABCD2345".repeat(6)
        val good = QrCode.encode(text)
        val n = good.size
        val flat = BooleanArray(n * n) { good[it % n, it / n] }
        flat[(n - 1) * n + (n - 1)] = !flat[(n - 1) * n + (n - 1)]     // 오른쪽 아래 모서리는 데이터 모듈
        try { decode(com.hmgcarapp.crossdrop.domain.QrMatrix(n, flat), 5); fail("깨진 행렬을 통과시킴") } catch (e: AssertionError) { }
    }
}
