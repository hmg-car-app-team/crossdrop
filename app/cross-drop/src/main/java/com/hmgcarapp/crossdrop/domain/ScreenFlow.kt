package com.hmgcarapp.crossdrop.domain

enum class Page(val label: String) { INBOX("받은 항목"), PAIR("휴대폰 연결"), PRIVACY("개인정보·삭제") }

/**
 * 화면 상태(순수). 사진·PDF 열람, 페어링 QR, 개인정보/삭제 화면은 **정차일 때만** 열 수 있고,
 * 주행이 시작되면(또는 주행 여부를 알 수 없으면) 자동으로 닫힌다. 초기값은 fail-safe 로 주행 중.
 */
data class ScreenState(
    val driving: Boolean = true,
    val page: Page = Page.INBOX,
    /** 열람 중인 항목 id(사진·PDF). 주행 중에는 항상 null. */
    val viewing: String? = null,
)

sealed interface ScreenEvent {
    data class DriveChanged(val driving: Boolean) : ScreenEvent
    data class SetPage(val page: Page) : ScreenEvent
    data class Open(val itemId: String) : ScreenEvent
    data object CloseViewer : ScreenEvent
    /** 열람 중인 항목이 삭제됨(만료·사용자 삭제). */
    data class ItemRemoved(val itemId: String) : ScreenEvent
}

object ScreenFlow {
    fun reduce(s: ScreenState, e: ScreenEvent): ScreenState = when (e) {
        is ScreenEvent.DriveChanged ->
            if (e.driving) ScreenState(driving = true, page = Page.INBOX, viewing = null) else s.copy(driving = false)
        is ScreenEvent.SetPage -> if (s.driving) s else s.copy(page = e.page, viewing = null)
        is ScreenEvent.Open -> if (s.driving) s else s.copy(viewing = e.itemId)
        ScreenEvent.CloseViewer -> s.copy(viewing = null)
        is ScreenEvent.ItemRemoved -> if (s.viewing == e.itemId) s.copy(viewing = null) else s
    }
}
