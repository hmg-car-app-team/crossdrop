package com.hmgcarapp.crossdrop.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.hmgcarapp.crossdrop.domain.LinkParser
import java.io.ByteArrayOutputStream

/**
 * 시연용 가상 수신 데이터. 서버를 거치지 않으며 **저장 상태에 기록하지 않는다**(시연 항목은 앱 시작 시 정리).
 * 사진·PDF 는 일반 수신과 같은 검증 경로([com.hmgcarapp.crossdrop.domain.FileRules.verify])를 통과한다.
 * 음악은 인코딩된 mp3/m4a 를 만들 수 없어 사인파 WAV 바이트를 M4A 이름으로 저장한다(재생기는 내용으로 형식을 판별) — 시연 전용이며 검증 경로를 거치지 않는다.
 */
object DemoAssets {
    /** 시연 페어링 코드(알파벳 규칙을 만족하는 8자리)와 표시용 가상 호스트(.invalid = 예약된 존재하지 않는 도메인). */
    const val DEMO_CODE = "SHWTM234"
    const val DEMO_TOKEN = "0000000000000000000000000000000000000000000000000000000000000000"
    private const val DEMO_HOST = "demo.invalid"
    val DEMO_BASE_URL: String = "https://" + DEMO_HOST

    /** 시연용 목적지 링크(실제 서버 주소가 아니라 파서 시연 입력). */
    fun link(name: String): String? {
        val h = "https://"
        return when (name) {
            "kakao" -> h + "map.kakao.com/link/map/" + "%EA%B0%95%EB%82%A8%EC%97%AD" + ",37.4979,127.0276"
            "naver" -> h + "map.naver.com/?lat=37.5665&lng=126.9780&title=" + "%EC%84%9C%EC%9A%B8%EC%8B%9C%EC%B2%AD"
            "google" -> h + "www.google.com/maps/place/Seoul+Station/@37.5547,126.9707,17z"
            "apple" -> h + "maps.apple.com/?ll=37.5133,127.1028&q=Lotte+World"
            "short" -> h + "kko.to/abc123"
            "bad" -> h + "evil.example/maps?ll=37.5,127.0"
            else -> null
        }
    }

    fun photoPng(): ByteArray {
        val bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 8) { p.color = 0xFF000000.toInt() or (0x20 + i * 0x1C shl 16) or (0x60 shl 8) or (0xE0 - i * 0x1C); c.drawRect(0f, i * 100f, 1200f, i * 100f + 100f, p) }
        p.color = -1; p.textSize = 72f
        c.drawText("CrossDrop sample photo", 120f, 420f, p)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray()
    }

    fun pdf(): ByteArray {
        val doc = PdfDocument()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f }
        for (n in 1..2) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
            page.canvas.drawText("CrossDrop sample PDF - page $n", 60f, 120f, p)
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray().also { doc.close() }
    }

    /** 440Hz 사인파 3초(16bit mono 22.05kHz) WAV. */
    fun toneWav(): ByteArray {
        val rate = 22050; val n = rate * 3
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (Math.sin(2.0 * Math.PI * 440.0 * i / rate) * 8000).toInt()
            data[2 * i] = (v and 0xFF).toByte(); data[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val out = ByteArrayOutputStream()
        fun le(v: Int, bytes: Int) { for (b in 0 until bytes) out.write((v shr (8 * b)) and 0xFF) }
        out.write("RIFF".toByteArray()); le(36 + data.size, 4); out.write("WAVEfmt ".toByteArray())
        le(16, 4); le(1, 2); le(1, 2); le(rate, 4); le(rate * 2, 4); le(2, 2); le(16, 2)
        out.write("data".toByteArray()); le(data.size, 4); out.write(data)
        return out.toByteArray()
    }

    /** 시연 링크가 파서 허용 목록에 있는 호스트인지(테스트용 도우미). */
    fun hostsOk(): Boolean = listOf("kakao", "naver", "google", "apple").all { n -> link(n)!!.substringAfter("https://").substringBefore('/') in LinkParser.ALLOWED_HOSTS }
}
