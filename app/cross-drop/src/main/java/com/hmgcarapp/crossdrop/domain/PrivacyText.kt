package com.hmgcarapp.crossdrop.domain

/** 첫 연결 동의 팝업·개인정보 탭의 고지 문구 단일 출처(앱·서버 README 의 고지와 같은 내용). */
object PrivacyText {
    /** 받은 항목 화면의 반납 전 삭제 안내(정차 중에만 보이는 화면). */
    const val RETURN_NOTICE = "공유차·렌터카를 반납하기 전에 받은 항목을 모두 삭제하세요."
    const val FLOW = "휴대폰이 보낸 사진·PDF·음악·지도 링크가 릴레이 서버를 거쳐 차량으로 와요."
    const val SERVER_HOLDS = "서버에는 연결 번호표와 휴대폰이 보낸 파일·링크가 차량이 받을 때까지(받으면 즉시 삭제, 늦어도 24시간) 있어요."
    const val OPERATOR_CAN_VIEW = "서버 운영자는 보관 중인 파일을 볼 수 있어요(종단 간 암호화 없음)."
    const val NOT_COLLECTED = "이름·계정·기기 번호·위치는 수집하지 않아요."
    const val REVOKE = "설정에서 언제든 끊고 지울 수 있어요. 동의를 철회해도 이미 받은 항목은 그대로 남아요."
    /** 전송 대상 고지(동의 팝업·설정): 현재 서버 주소의 호스트:포트와 개발·시연용 한계. */
    fun destination(hostPort: String?): String = if (hostPort == null) "이 빌드에는 서버 주소가 없어 실제 연결은 하지 않아요." else "전송 대상: $hostPort (개발·시연용 로컬 서버이며 공개 배포용이 아니에요)."
    val CONSENT_PARAGRAPHS = listOf(FLOW, SERVER_HOLDS, OPERATOR_CAN_VIEW, NOT_COLLECTED, REVOKE)

    /** 개인정보 탭 '서버가 보관하는 것' 항목 설명. */
    const val PRIVACY_TAB_HOLDS = "휴대폰이 보낸 파일·링크를 차량이 받을 때까지(받으면 즉시 삭제, 늦어도 24시간). 서버 운영자는 보관 중인 파일을 볼 수 있어요"
}
