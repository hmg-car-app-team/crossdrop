package com.hmgcarapp.crossdrop.domain

/** 이미지·PDF 표시 계산(순수). */
object ViewMath {
    const val MAX_DECODE_DIM = 2048
    const val MAX_PIXELS = 50_000_000L
    const val MAX_PDF_RENDER_PX = 1800

    /** 긴 변이 [maxDim] 이하가 되는 가장 작은 2의 거듭제곱 축소 비율(BitmapFactory.inSampleSize). */
    fun sampleSize(width: Int, height: Int, maxDim: Int = MAX_DECODE_DIM): Int {
        var s = 1
        val longest = maxOf(width, height)
        while ((longest + s - 1) / s > maxDim) s *= 2
        return s
    }

    /** 디코드하기에 너무 큰(또는 잘못된) 이미지인가. */
    fun rejectImage(width: Int, height: Int): Boolean = width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS

    /** PDF 페이지(포인트)를 긴 변 [maxPx] 이하 비트맵 크기로(비율 유지, 최소 1). */
    fun pdfSize(pageW: Int, pageH: Int, maxPx: Int = MAX_PDF_RENDER_PX): Pair<Int, Int> {
        if (pageW <= 0 || pageH <= 0) return 1 to 1
        val scale = maxPx.toDouble() / maxOf(pageW, pageH)
        return maxOf(1, Math.round(pageW * scale).toInt()) to maxOf(1, Math.round(pageH * scale).toInt())
    }
}

/** EXIF 방향(1~8) -> 화면에 바로 보이게 하는 변환: 먼저 좌우 반전([mirror])한 뒤 시계 방향 [rotateDeg] 회전. */
data class ExifTransform(val rotateDeg: Int, val mirror: Boolean) {
    val isIdentity: Boolean get() = rotateDeg == 0 && !mirror

    companion object {
        /** 알 수 없는 값(0·9 이상)은 변환 없음. */
        fun of(orientation: Int): ExifTransform = when (orientation) {
            2 -> ExifTransform(0, true)
            3 -> ExifTransform(180, false)
            4 -> ExifTransform(180, true)     // 상하 반전 = 좌우 반전 + 180도
            5 -> ExifTransform(270, true)     // transpose
            6 -> ExifTransform(90, false)
            7 -> ExifTransform(90, true)      // transverse
            8 -> ExifTransform(270, false)
            else -> ExifTransform(0, false)
        }
    }
}
