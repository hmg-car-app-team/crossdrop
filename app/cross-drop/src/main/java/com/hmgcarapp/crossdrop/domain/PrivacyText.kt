package com.hmgcarapp.crossdrop.domain

/** 첫 연결 동의 팝업 문구(사실 두 문장). */
object PrivacyText {
    const val FLOW = "휴대폰이 보낸 사진·PDF·음악·지도 링크가 릴레이 서버를 거쳐 차량으로 와요."
    const val STOP = "연결을 끊으면 전달이 멈춰요."
    /** 전송 대상 고지(동의 팝업): 현재 서버 주소의 호스트:포트. */
    fun destination(hostPort: String?): String = if (hostPort == null) "전송 대상: 없음" else "전송 대상: $hostPort"
    val CONSENT_PARAGRAPHS = listOf(FLOW, STOP)
}
