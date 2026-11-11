package com.hmgcarapp.crossdrop.domain

/**
 * QR 코드 생성기(순수 Kotlin, 외부 라이브러리 없음). 바이트 모드·오류 정정 M·버전 1~10(최대 213바이트)만 지원한다.
 * ISO/IEC 18004 알고리즘(Reed-Solomon GF(256), 마스크 8종 중 최소 페널티 선택). 화면 표시 전용 → 디코더는 없다.
 */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && dark[y * size + x]
}

object QrCode {
    const val MAX_VERSION = 10
    // 오류 정정 M: 블록당 ECC 코드워드 수, 블록 수 (인덱스 = 버전, 0 은 사용 안 함)
    private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val NUM_BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

    fun numRawDataModules(ver: Int): Int {
        var r = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val a = ver / 7 + 2
            r -= (25 * a - 10) * a - 55
            if (ver >= 7) r -= 36
        }
        return r
    }

    fun dataCodewords(ver: Int): Int = numRawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver]

    /** 버전 [ver] 에서 담을 수 있는 최대 바이트 수. */
    fun capacityBytes(ver: Int): Int = (dataCodewords(ver) * 8 - 4 - countBits(ver)) / 8

    private fun countBits(ver: Int) = if (ver <= 9) 8 else 16

    fun encode(text: String): QrMatrix {
        val data = text.toByteArray(Charsets.UTF_8)
        val ver = (1..MAX_VERSION).firstOrNull { data.size <= capacityBytes(it) }
            ?: throw IllegalArgumentException("QR 로 담기에는 너무 길다: ${data.size}바이트")
        return Builder(ver, buildCodewords(ver, data)).build()
    }

    // ---------------- 데이터 코드워드 + ECC ----------------
    internal fun buildCodewords(ver: Int, data: ByteArray): ByteArray {
        val bits = ArrayList<Int>()
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits += (v ushr i) and 1 }
        put(0b0100, 4)
        put(data.size, countBits(ver))
        data.forEach { put(it.toInt() and 0xFF, 8) }
        val cap = dataCodewords(ver) * 8
        repeat(minOf(4, cap - bits.size)) { bits += 0 }
        while (bits.size % 8 != 0) bits += 0
        val bytes = ArrayList<Int>()
        for (i in bits.indices step 8) { var b = 0; for (j in 0 until 8) b = (b shl 1) or bits[i + j]; bytes += b }
        var pad = 0xEC
        while (bytes.size < dataCodewords(ver)) { bytes += pad; pad = pad xor 0xEC xor 0x11 }
        return addEccAndInterleave(ver, ByteArray(bytes.size) { bytes[it].toByte() })
    }

    private fun addEccAndInterleave(ver: Int, data: ByteArray): ByteArray {
        val numBlocks = NUM_BLOCKS[ver]; val eccLen = ECC_PER_BLOCK[ver]
        val raw = numRawDataModules(ver) / 8
        val numShort = numBlocks - raw % numBlocks
        val shortLen = raw / numBlocks
        val blocks = ArrayList<ByteArray>()
        val div = rsDivisor(eccLen)
        var k = 0
        for (i in 0 until numBlocks) {
            val dat = data.copyOfRange(k, k + shortLen - eccLen + (if (i < numShort) 0 else 1))
            k += dat.size
            val block = dat.copyOf(shortLen + 1)
            rsRemainder(dat, div).copyInto(block, block.size - eccLen)
            blocks += block
        }
        val out = ByteArray(raw)
        var o = 0
        for (i in 0..shortLen) for (j in 0 until numBlocks) {
            if (i != shortLen - eccLen || j >= numShort) out[o++] = blocks[j][i]
        }
        check(o == raw)
        return out
    }

    // ---------------- Reed-Solomon (GF(256), 다항식 0x11D) ----------------
    internal fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    /** 최고차항 계수(1)를 뺀 [degree] 개 계수. */
    internal fun rsDivisor(degree: Int): ByteArray {
        val r = IntArray(degree); r[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in r.indices) {
                r[j] = gfMul(r[j], root)
                if (j + 1 < r.size) r[j] = r[j] xor r[j + 1]
            }
            root = gfMul(root, 0x02)
        }
        return ByteArray(degree) { r[it].toByte() }
    }

    internal fun rsRemainder(data: ByteArray, div: ByteArray): ByteArray {
        val r = IntArray(div.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor r[0]
            for (i in 0 until r.size - 1) r[i] = r[i + 1]
            r[r.size - 1] = 0
            for (i in r.indices) r[i] = r[i] xor gfMul(div[i].toInt() and 0xFF, factor)
        }
        return ByteArray(r.size) { r[it].toByte() }
    }

    /** 오류 정정 M 의 형식 정보 15비트(마스크 번호 0~7). */
    internal fun formatBits(mask: Int): Int {
        val data = (0 shl 3) or mask     // ECC M = 00
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        return ((data shl 10) or rem) xor 0x5412
    }

    internal fun versionBits(ver: Int): Int {
        var rem = ver
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        return (ver shl 12) or rem
    }

    // ---------------- 모듈 배치 ----------------
    private class Builder(val ver: Int, val codewords: ByteArray) {
        val size = ver * 4 + 17
        val m = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }

        fun build(): QrMatrix {
            drawFunctionPatterns()
            drawCodewords()
            var best = 0; var bestPenalty = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask); drawFormat(mask)
                val p = penalty()
                if (p < bestPenalty) { bestPenalty = p; best = mask }
                applyMask(mask)   // 되돌리기(XOR)
            }
            applyMask(best); drawFormat(best)
            val flat = BooleanArray(size * size) { m[it / size][it % size] }
            return QrMatrix(size, flat)
        }

        fun set(x: Int, y: Int, dark: Boolean) { m[y][x] = dark; fn[y][x] = true }

        fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val pos = alignPositions()
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                align(pos[i], pos[j])
            }
            drawFormat(0)
            drawVersion()
        }

        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(Math.abs(dx), Math.abs(dy)); val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) set(x, y, d != 2 && d != 4)
            }
        }

        fun align(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }

        fun alignPositions(): IntArray {
            if (ver == 1) return IntArray(0)
            val n = ver / 7 + 2
            val step = if (ver == 32) 26 else (ver * 4 + n * 2 + 1) / (n * 2 - 2) * 2
            val r = IntArray(n); r[0] = 6
            var i = n - 1; var p = size - 7
            while (i >= 1) { r[i] = p; i--; p -= step }
            return r
        }

        fun bit(v: Int, i: Int) = ((v ushr i) and 1) != 0

        fun drawFormat(mask: Int) {
            val bits = formatBits(mask)
            for (i in 0..5) set(8, i, bit(bits, i))
            set(8, 7, bit(bits, 6)); set(8, 8, bit(bits, 7)); set(7, 8, bit(bits, 8))
            for (i in 9..14) set(14 - i, 8, bit(bits, i))
            for (i in 0..7) set(size - 1 - i, 8, bit(bits, i))
            for (i in 8..14) set(8, size - 15 + i, bit(bits, i))
            set(8, size - 8, true)
        }

        fun drawVersion() {
            if (ver < 7) return
            val bits = versionBits(ver)
            for (i in 0..17) {
                val b = bit(bits, i); val a = size - 11 + i % 3; val c = i / 3
                set(a, c, b); set(c, a, b)
            }
        }

        fun drawCodewords() {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!fn[y][x] && i < codewords.size * 8) {
                        m[y][x] = ((codewords[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
            check(i == codewords.size * 8)
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (!fn[y][x] && invert) m[y][x] = !m[y][x]
            }
        }

        /** N1(같은 색 연속) + N2(2x2 블록) + N3(finder 유사 패턴) + N4(어두운 비율). */
        fun penalty(): Int {
            var p = 0
            for (pass in 0..1) {
                for (a in 0 until size) {
                    var run = 1
                    for (b in 1 until size) {
                        val cur = if (pass == 0) m[a][b] else m[b][a]
                        val prev = if (pass == 0) m[a][b - 1] else m[b - 1][a]
                        if (cur == prev) { run++; if (run == 5) p += 3 else if (run > 5) p++ } else run = 1
                    }
                    val line = BooleanArray(size) { if (pass == 0) m[a][it] else m[it][a] }
                    for (s in 0..size - 11) {
                        if (matches(line, s, P1) || matches(line, s, P2)) p += 40
                    }
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3
            }
            var dark = 0
            for (row in m) for (c in row) if (c) dark++
            val total = size * size
            val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            return p + k * 10
        }

        fun matches(line: BooleanArray, s: Int, pat: BooleanArray): Boolean {
            for (i in pat.indices) if (line[s + i] != pat[i]) return false
            return true
        }
    }

    private val P1 = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
    private val P2 = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
}
