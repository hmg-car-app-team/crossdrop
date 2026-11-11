package com.hmgcarapp.crossdrop.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.hmgcarapp.crossdrop.domain.ExifTransform
import com.hmgcarapp.crossdrop.domain.ViewMath
import java.io.File

/** 사진 디코드(백그라운드 스레드에서 호출). 너무 큰 이미지는 거부하고, 화면 크기에 맞게 줄여서 읽는다. 실패하면 null. */
object ImageLoader {
    fun load(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (ViewMath.rejectImage(bounds.outWidth, bounds.outHeight)) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = ViewMath.sampleSize(bounds.outWidth, bounds.outHeight) }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        // 휴대폰 세로 사진은 픽셀이 눕혀진 채 EXIF 방향만 달려 있다: 프레임워크 ExifInterface(의존성 추가 없음)로 읽어 바로 세운다
        val t = ExifTransform.of(runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1))
        if (t.isIdentity) return bmp
        val m = Matrix()
        if (t.mirror) m.postScale(-1f, 1f)
        m.postRotate(t.rotateDeg.toFloat())
        return runCatching { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }.getOrDefault(bmp)
    }
}

/**
 * PDF 열람(플랫폼 [PdfRenderer]; 스크립트·외부 링크를 실행하지 않는다). 한 번에 한 쪽만 비트맵으로 그린다. 백그라운드 스레드에서 호출.
 * 사용 후 [close] 필수.
 */
class PdfPages(file: File) : AutoCloseable {
    private val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try { PdfRenderer(fd) } catch (t: Throwable) { fd.close(); throw t }

    val pageCount: Int get() = renderer.pageCount

    @Synchronized
    fun render(index: Int): Bitmap {
        require(index in 0 until renderer.pageCount)
        renderer.openPage(index).use { page ->
            val (w, h) = ViewMath.pdfSize(page.width, page.height)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(WHITE)    // PDF 쪽은 투명 배경이라 흰 바탕을 깐다
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    @Synchronized
    override fun close() { runCatching { renderer.close() }; runCatching { fd.close() } }

    private companion object { const val WHITE = -1 }
}
