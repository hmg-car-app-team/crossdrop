package com.hmgcarapp.crossdrop.domain

/** 받은 항목의 종류. [code] 는 서버 응답·저장 파일에 쓰는 이름. */
enum class ItemKind(val code: String, val label: String) {
    IMAGE("image", "사진"), PDF("pdf", "문서"), AUDIO("audio", "음악"), LINK("link", "목적지");

    companion object { fun fromCode(c: String?): ItemKind? = entries.firstOrNull { it.code == c } }
}

/** 파일 형식(정규 확장자). 서버 `filecheck.py` 와 같은 규칙. */
enum class FileFormat(val ext: String, val kind: ItemKind, val mime: String) {
    JPG("jpg", ItemKind.IMAGE, "image/jpeg"), PNG("png", ItemKind.IMAGE, "image/png"), WEBP("webp", ItemKind.IMAGE, "image/webp"),
    PDF("pdf", ItemKind.PDF, "application/pdf"),
    MP3("mp3", ItemKind.AUDIO, "audio/mpeg"), M4A("m4a", ItemKind.AUDIO, "audio/mp4"), AAC("aac", ItemKind.AUDIO, "audio/aac"), OGG("ogg", ItemKind.AUDIO, "audio/ogg");

    companion object { fun fromExt(e: String?): FileFormat? = entries.firstOrNull { it.ext == e } }
}

sealed interface FileVerdict {
    data class Ok(val format: FileFormat) : FileVerdict
    data class Rejected(val reason: String) : FileVerdict
}

/**
 * 받은 파일의 최종 검증(순수). 서버가 이미 검사했더라도 차량이 한 번 더 한다(서버를 믿지 않는다):
 * 매직 바이트로 형식을 정하고, 서버가 알린 종류와 같은지·크기 상한·마크업/스크립트/PDF 활성 콘텐츠를 확인한다.
 */
object FileRules {
    const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    const val MAX_PDF_BYTES = 10 * 1024 * 1024
    const val MAX_AUDIO_BYTES = 20 * 1024 * 1024

    fun maxBytes(kind: ItemKind): Int = when (kind) {
        ItemKind.IMAGE -> MAX_IMAGE_BYTES
        ItemKind.PDF -> MAX_PDF_BYTES
        ItemKind.AUDIO -> MAX_AUDIO_BYTES
        ItemKind.LINK -> 0
    }

    private val PDF_ACTIVE = Regex("/(JavaScript|JS|Launch|EmbeddedFile|OpenAction|RichMedia|SubmitForm|ImportData|GoToR)(?![A-Za-z0-9])")
    private val HEX_ESC = Regex("#([0-9A-Fa-f]{2})")
    private val MARKUP_HEAD = Regex("<\\s*(svg|html|script|\\?xml|!doctype)", RegexOption.IGNORE_CASE)
    private val M4A_BRANDS = listOf("M4A ", "M4B ")

    private fun ByteArray.at(i: Int): Int = if (i < size) this[i].toInt() and 0xFF else -1
    private fun ByteArray.hasPrefix(vararg b: Int) = b.indices.all { at(it) == b[it] }
    private fun ByteArray.ascii(from: Int, len: Int): String = if (from + len <= size) String(this, from, len, Charsets.ISO_8859_1) else ""

    fun sniff(d: ByteArray): FileFormat? {
        if (d.hasPrefix(0xFF, 0xD8, 0xFF)) return FileFormat.JPG
        if (d.hasPrefix(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return FileFormat.PNG
        if (d.ascii(0, 4) == "RIFF" && d.ascii(8, 4) == "WEBP") return FileFormat.WEBP
        if (d.ascii(0, 5) == "%PDF-") return FileFormat.PDF
        if (d.ascii(0, 4) == "OggS") {
            val head = String(d, 0, minOf(d.size, 200), Charsets.ISO_8859_1)
            return if (head.contains("\u0001vorbis") || head.contains("OpusHead") || head.contains("\u007FFLAC")) FileFormat.OGG else null
        }
        if (d.ascii(4, 4) == "ftyp") return if (d.ascii(8, 4) in M4A_BRANDS) FileFormat.M4A else null
        if (d.ascii(0, 3) == "ID3" && d.at(3) in 2..4) return FileFormat.MP3
        if (d.at(0) == 0xFF && d.size >= 3) {
            val b1 = d.at(1); val b2 = d.at(2)
            if ((b1 and 0xF6) == 0xF0 && ((b2 shr 2) and 0x0F) < 13) return FileFormat.AAC
            if ((b1 and 0xE0) == 0xE0 && ((b1 shr 1) and 3) == 1 && ((b1 shr 3) and 3) != 1 && (b2 shr 4) != 0 && (b2 shr 4) != 15 && ((b2 shr 2) and 3) != 3) return FileFormat.MP3
        }
        return null
    }

    fun verify(declared: ItemKind, d: ByteArray): FileVerdict {
        if (declared == ItemKind.LINK) return FileVerdict.Rejected("링크는 파일이 아님")
        if (d.isEmpty()) return FileVerdict.Rejected("빈 파일")
        val f = sniff(d) ?: return FileVerdict.Rejected("지원하지 않는 형식")
        if (f.kind != declared) return FileVerdict.Rejected("종류 불일치")
        if (d.size > maxBytes(f.kind)) return FileVerdict.Rejected("크기 초과")
        if (f.kind == ItemKind.IMAGE && MARKUP_HEAD.containsMatchIn(String(d, 0, minOf(d.size, 512), Charsets.ISO_8859_1))) return FileVerdict.Rejected("마크업 포함")
        if (f.kind == ItemKind.PDF && pdfHasActiveContent(String(d, Charsets.ISO_8859_1))) return FileVerdict.Rejected("PDF 활성 콘텐츠")
        return FileVerdict.Ok(f)
    }

    /** `>>` 직후의 `stream`…`endstream` 안쪽(압축된 임의 바이트)을 뺀 나머지. 닫히지 않은 stream 은 빼지 않는다. 선형 시간(토크나이저 아님). 서버 filecheck._outside_streams 와 같은 규칙. */
    internal fun outsideStreams(t: String): String {
        val out = StringBuilder(); var pos = 0; var scan = 0
        while (true) {
            val i = t.indexOf("stream", scan)
            if (i < 0) break
            var j = i + 6
            scan = j
            val afterEnd = i >= 3 && t.startsWith("end", i - 3)
            // 진짜 스트림 시작만: `>>`(앞쪽 공백 허용) 바로 뒤의 stream + EOL. 주석(% stream)·문자열((stream)) 속 가짜 시작은 건너뛴다
            var b = i
            while (b > 0 && (t[b - 1] == ' ' || t[b - 1] == '\t' || t[b - 1] == '\r' || t[b - 1] == '\n')) b--
            val afterDict = b >= 2 && t[b - 1] == '>' && t[b - 2] == '>'
            val nl = if (t.startsWith("\r\n", j)) 2 else if (t.startsWith("\n", j)) 1 else 0
            if (afterEnd || !afterDict || nl == 0) continue
            j += nl
            val k = t.indexOf("endstream", j)
            if (k < 0) break
            out.append(t, pos, j); pos = k; scan = k + 9
        }
        out.append(t, pos, t.length)
        return out.toString()
    }

    private val NAME_WITH_HEX = Regex("/[^\\s/<>\\[\\]()%{}]*#[0-9A-Fa-f]{2}[^\\s/<>\\[\\]()%{}]*")

    /** 스트림 밖에서 활성 콘텐츠 키를 찾는다. `#xx` 이스케이프는 이름 토큰 단위로만 디코딩(선형). */
    internal fun pdfHasActiveContent(t: String): Boolean {
        val region = outsideStreams(t)
        if (PDF_ACTIVE.containsMatchIn(region)) return true
        if ('#' in region) {
            for (m in NAME_WITH_HEX.findAll(region)) {
                if (PDF_ACTIVE.containsMatchIn(HEX_ESC.replace(m.value) { it.groupValues[1].toInt(16).toChar().toString() })) return true
            }
        }
        return false
    }

    /** 앱이 만드는 저장 파일 이름 형식. 저장 상태(JSON)에서 읽은 이름은 이 형식일 때만 경로로 쓴다(경로 조작 차단). */
    val LOCAL_NAME = Regex("^f_[0-9a-f]{16}\\.(jpg|png|webp|pdf|mp3|m4a|aac|ogg)$")
    fun isSafeLocalName(n: String?): Boolean = n != null && LOCAL_NAME.matches(n)
}
