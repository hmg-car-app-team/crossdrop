package com.hmgcarapp.crossdrop

import com.hmgcarapp.crossdrop.domain.FileFormat
import com.hmgcarapp.crossdrop.domain.FileRules
import com.hmgcarapp.crossdrop.domain.FileVerdict
import com.hmgcarapp.crossdrop.domain.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRulesTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun pad(h: ByteArray, n: Int = 40) = h + ByteArray(n)
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    val jpeg = pad(b(0xFF, 0xD8, 0xFF, 0xE0))
    val png = pad(b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    val webp = pad(ascii("RIFF") + b(0x24, 0, 0, 0) + ascii("WEBPVP8 "))
    val pdf = ascii("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n")
    val mp3 = pad(ascii("ID3") + b(4, 0, 0, 0, 0, 0, 0))
    val mp3Frame = pad(b(0xFF, 0xFB, 0x90, 0x00))
    val aac = pad(b(0xFF, 0xF1, 0x50, 0x80))
    val m4a = pad(b(0, 0, 0, 0x20) + ascii("ftypM4A ") + b(0, 0, 0, 0))
    val mp4Video = pad(b(0, 0, 0, 0x20) + ascii("ftypisom") + b(0, 0, 2, 0))
    val ogg = pad(ascii("OggS") + b(0, 2) + ByteArray(20) + b(1) + ascii("vorbis"))
    val oggTheora = pad(ascii("OggS") + b(0, 2) + ByteArray(20) + b(0x80) + ascii("theora"))

    @Test fun sniffAcceptedFormats() {
        assertEquals(FileFormat.JPG, FileRules.sniff(jpeg)); assertEquals(FileFormat.PNG, FileRules.sniff(png)); assertEquals(FileFormat.WEBP, FileRules.sniff(webp))
        assertEquals(FileFormat.PDF, FileRules.sniff(pdf)); assertEquals(FileFormat.MP3, FileRules.sniff(mp3)); assertEquals(FileFormat.MP3, FileRules.sniff(mp3Frame))
        assertEquals(FileFormat.AAC, FileRules.sniff(aac)); assertEquals(FileFormat.M4A, FileRules.sniff(m4a)); assertEquals(FileFormat.OGG, FileRules.sniff(ogg))
    }

    @Test fun sniffRejectsEverythingElse() {
        val svg = ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>")
        val html = ascii("<!doctype html><script>alert(1)</script>")
        val exe = pad(ascii("MZ") + b(0x90, 0))
        val elf = pad(b(0x7F) + ascii("ELF"))
        val apk = pad(ascii("PK") + b(3, 4))
        val sh = ascii("#!/bin/sh\nrm -rf /\n")
        val wav = pad(ascii("RIFF") + b(0x24, 0, 0, 0) + ascii("WAVEfmt "))
        for ((n, d) in mapOf("svg" to svg, "html" to html, "exe" to exe, "elf" to elf, "apk/zip" to apk, "sh" to sh, "wav" to wav, "mp4 video" to mp4Video, "ogg theora" to oggTheora, "empty" to ByteArray(0), "1byte" to b(0xFF))) {
            assertNull(n, FileRules.sniff(d))
        }
        assertNull("PDF 는 맨 앞에서 시작해야 한다", FileRules.sniff(ascii("\n%PDF-1.4") + ByteArray(20)))
        assertNull("예약된 mp3 비트레이트 인덱스 15", FileRules.sniff(pad(b(0xFF, 0xFB, 0xF0, 0x00))))
        assertNull("예약된 mp3 샘플레이트 인덱스 3", FileRules.sniff(pad(b(0xFF, 0xFB, 0x9C, 0x00))))
        assertNull("ID3 버전 9", FileRules.sniff(pad(ascii("ID3") + b(9))))
    }

    @Test fun declaredKindMustMatchContent() {
        assertEquals(FileVerdict.Ok(FileFormat.PNG), FileRules.verify(ItemKind.IMAGE, png))
        assertEquals(FileVerdict.Ok(FileFormat.PDF), FileRules.verify(ItemKind.PDF, pdf))
        assertEquals(FileVerdict.Ok(FileFormat.M4A), FileRules.verify(ItemKind.AUDIO, m4a))
        assertEquals("서버가 image 라고 했지만 PDF", FileVerdict.Rejected("종류 불일치"), FileRules.verify(ItemKind.IMAGE, pdf))
        assertEquals(FileVerdict.Rejected("종류 불일치"), FileRules.verify(ItemKind.AUDIO, png))
        assertEquals(FileVerdict.Rejected("지원하지 않는 형식"), FileRules.verify(ItemKind.IMAGE, pad(ascii("MZ"))))
        assertEquals(FileVerdict.Rejected("빈 파일"), FileRules.verify(ItemKind.IMAGE, ByteArray(0)))
        assertEquals(FileVerdict.Rejected("링크는 파일이 아님"), FileRules.verify(ItemKind.LINK, png))
    }

    @Test fun sizeLimitsAreExact() {
        assertEquals(10_485_760, FileRules.MAX_IMAGE_BYTES); assertEquals(10_485_760, FileRules.MAX_PDF_BYTES); assertEquals(20_971_520, FileRules.MAX_AUDIO_BYTES)
        assertTrue(FileRules.verify(ItemKind.IMAGE, jpeg + ByteArray(10_485_760 - jpeg.size)) is FileVerdict.Ok)
        assertEquals(FileVerdict.Rejected("크기 초과"), FileRules.verify(ItemKind.IMAGE, jpeg + ByteArray(10_485_760 - jpeg.size + 1)))
        assertEquals(FileVerdict.Rejected("크기 초과"), FileRules.verify(ItemKind.PDF, pdf + ByteArray(10_485_760 - pdf.size + 1)))
        assertTrue(FileRules.verify(ItemKind.AUDIO, mp3 + ByteArray(20_971_520 - mp3.size)) is FileVerdict.Ok)
        assertEquals(FileVerdict.Rejected("크기 초과"), FileRules.verify(ItemKind.AUDIO, mp3 + ByteArray(20_971_520 - mp3.size + 1)))
    }

    @Test fun markupInImageHeadAndPdfActiveContentAreRejected() {
        assertEquals(FileVerdict.Rejected("마크업 포함"), FileRules.verify(ItemKind.IMAGE, png + ascii("<SVG onload=1>")))
        assertEquals(FileVerdict.Rejected("마크업 포함"), FileRules.verify(ItemKind.IMAGE, jpeg.copyOf(20) + ascii("<script>x</script>")))
        for (bad in listOf("/JavaScript", "/JS ", "/Launch", "/OpenAction", "/EmbeddedFile", "/RichMedia", "/SubmitForm")) {
            assertEquals(bad, FileVerdict.Rejected("PDF 활성 콘텐츠"), FileRules.verify(ItemKind.PDF, pdf + ascii("\n<< $bad >>")))
        }
        for (esc in listOf("/J#61vaScript", "/#4A#53 ", "/La#75nch", "/Open#41ction", "/#4a#53>>")) assertEquals(esc, FileVerdict.Rejected("PDF 활성 콘텐츠"), FileRules.verify(ItemKind.PDF, pdf + ascii("\n<< $esc >>")))
        assertTrue("무해한 #xx 이스케이프는 통과", FileRules.verify(ItemKind.PDF, pdf + ascii("\n<< /Title (#41#42) /Name /A#20B >>")) is FileVerdict.Ok)
        assertTrue("접두만 같은 일반 키는 통과", FileRules.verify(ItemKind.PDF, pdf + ascii("\n<< /JSONData 1 >>")) is FileVerdict.Ok)
    }

    @Test fun localNamesAreAppGenerated() {
        for (ok in listOf("f_0123456789abcdef.jpg", "f_0123456789abcdef.png", "f_0123456789abcdef.webp", "f_0123456789abcdef.pdf", "f_0123456789abcdef.mp3", "f_0123456789abcdef.m4a", "f_0123456789abcdef.aac", "f_0123456789abcdef.ogg")) assertTrue(ok, FileRules.isSafeLocalName(ok))
        for (bad in listOf(null, "", "../f_0123456789abcdef.jpg", "f_0123456789abcdef.jpg/../x", "f_0123456789abcdef.exe", "f_0123456789ABCDEF.jpg", "f_0123456789abcde.jpg", "f_0123456789abcdef.JPG", "/etc/passwd", "f_0123456789abcdef.jpg\n", "x_0123456789abcdef.jpg", "f_0123456789abcdef.svg"))
            assertFalse(bad.toString(), FileRules.isSafeLocalName(bad))
    }

    @Test fun formatTable() {
        assertEquals(ItemKind.AUDIO, FileFormat.fromExt("ogg")?.kind); assertNull(FileFormat.fromExt("svg")); assertNull(FileFormat.fromExt("wav")); assertNull(FileFormat.fromExt("mp4"))
        assertEquals("image/jpeg", FileFormat.JPG.mime)
        assertEquals(ItemKind.PDF, ItemKind.fromCode("pdf")); assertNull(ItemKind.fromCode("video"))
    }

    private fun randomPdf(seed: Long, size: Int = 3_000_000): ByteArray {
        val body = ByteArray(size).also { java.util.Random(seed).nextBytes(it) }
        return pdf + ascii("\n5 0 obj\n<< /Length $size /Filter /FlateDecode >>\nstream\n") + body + ascii("\nendstream\nendobj\n")
    }

    @Test fun randomCompressedStreamsNeverTriggerPdfFalsePositives() {
        for (seed in 0L until 12L) assertTrue("seed $seed", FileRules.verify(ItemKind.PDF, randomPdf(seed)) is FileVerdict.Ok)
    }

    @Test fun activeKeysOutsideStreamsStillRejectedIncludingEscapes() {
        val base = randomPdf(1, 1000)
        for (bad in listOf("/JavaScript", "/JS ", "/OpenAction", "/Launch", "/J#61vaScript", "/#4A#53 ", "/Open#41ction")) {
            assertEquals(bad, FileVerdict.Rejected("PDF 활성 콘텐츠"), FileRules.verify(ItemKind.PDF, base + ascii("\n<< $bad >>")))
            assertEquals(bad, FileVerdict.Rejected("PDF 활성 콘텐츠"), FileRules.verify(ItemKind.PDF, pdf + ascii("\n<< $bad >>\n") + base))
        }
        assertEquals("닫히지 않은 stream 은 그대로 검사", FileVerdict.Rejected("PDF 활성 콘텐츠"), FileRules.verify(ItemKind.PDF, pdf + ascii("\nstream\n/JavaScript")))
        assertTrue("스트림 안쪽은 검사하지 않는다(설계)", FileRules.verify(ItemKind.PDF, pdf + ascii("\n<< /Length 14 >>\nstream\nxx/JavaScriptxx\nendstream\n")) is FileVerdict.Ok)
        assertTrue(">> 와 stream 사이 공백·CRLF 허용", FileRules.verify(ItemKind.PDF, pdf + ascii("\n<</Length 14>>  \r\nstream\r\nxx/JavaScriptxx\r\nendstream\n")) is FileVerdict.Ok)
    }

    @Test fun fakeStreamStartsInCommentsAndStringsDoNotHideActiveKeys() {
        val comment = ascii("%PDF-1.4\n% stream\n1 0 obj<</Type/Catalog/OpenAction<</S/JavaScript/JS(x)>>>>endobj\n% endstream\n")
        val string = pdf + ascii("(stream\n)1 0 obj<</OpenAction 1 0 R>>endobj\n(endstream)")
        assertEquals(FileVerdict.Rejected("PDF 활성 콘텐츠"), FileRules.verify(ItemKind.PDF, comment))
        assertEquals(FileVerdict.Rejected("PDF 활성 콘텐츠"), FileRules.verify(ItemKind.PDF, string))
        assertEquals("건너뛴 'stream' 앞의 텍스트도 검사 대상", FileVerdict.Rejected("PDF 활성 콘텐츠"), FileRules.verify(ItemKind.PDF, pdf + ascii("\n/OpenAction 1 0 R\n% stream\n")))
    }

    @Test fun hexEscapeDecodingIsLinear() {
        val t0 = System.nanoTime()
        assertTrue(FileRules.verify(ItemKind.PDF, pdf + ascii("/A#41 ".repeat(1_700_000))) is FileVerdict.Ok)
        assertTrue("10MB 에서 3초 이내", (System.nanoTime() - t0) / 1e9 < 3.0)
    }
}

