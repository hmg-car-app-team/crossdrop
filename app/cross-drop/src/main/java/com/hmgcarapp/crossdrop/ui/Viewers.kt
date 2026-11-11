package com.hmgcarapp.crossdrop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import com.hmgcarapp.crossdrop.app.ImageLoader
import com.hmgcarapp.crossdrop.app.PdfPages
import com.hmgcarapp.crossdrop.domain.QrCode
import com.hmgcarapp.pleos.designsystem.component.PleosButton
import com.hmgcarapp.pleos.designsystem.component.PleosButtonHierarchy
import com.hmgcarapp.pleos.designsystem.component.PleosPanel
import com.hmgcarapp.pleos.designsystem.theme.PleosSpacing
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 디자인시스템에 QR·이미지·PDF 뷰어가 없어 비-Material 기본 요소(Canvas·Image)로 최소 구현했다(docs/apps/cross-drop.md 기록). */
@Composable
fun QrView(payload: String, modifier: Modifier = Modifier) {
    val m = remember(payload) { runCatching { QrCode.encode(payload) }.getOrNull() }
    if (m == null) {
        Text("QR 을 만들 수 없어요", style = PleosTheme.typography.bodyMedium, color = PleosTheme.colors.textDarkSecondary, modifier = modifier)
        return
    }
    val light = PleosTheme.colors.basicStaticLight100
    val dark = PleosTheme.colors.basicStaticDark100
    Canvas(modifier.background(light).testTag("qr_view")) {
        val quiet = 4
        val total = m.size + quiet * 2
        val unit = Math.floor(size.minDimension / total.toDouble()).toFloat().coerceAtLeast(1f)   // 정수 픽셀 단위(모듈 사이 이음새 방지)
        val origin = Offset(Math.floor(((size.width - unit * total) / 2f).toDouble()).toFloat(), Math.floor(((size.height - unit * total) / 2f).toDouble()).toFloat())
        for (y in 0 until m.size) for (x in 0 until m.size) {
            if (m[x, y]) drawRect(dark, topLeft = Offset(origin.x + (x + quiet) * unit, origin.y + (y + quiet) * unit), size = Size(unit, unit))
        }
    }
}

private sealed interface Loaded {
    data object Loading : Loaded
    data object Failed : Loaded
    data class Ok(val img: ImageBitmap) : Loaded
}

@Composable
private fun Shown(loaded: Loaded, tag: String) {
    when (loaded) {
        Loaded.Loading -> Text("불러오는 중…", style = PleosTheme.typography.bodyMedium, color = PleosTheme.colors.textDarkSecondary, modifier = Modifier.testTag("viewer_loading"))
        Loaded.Failed -> Text("열 수 없는 파일이에요", style = PleosTheme.typography.bodyMedium, color = PleosTheme.colors.textDarkSecondary, modifier = Modifier.testTag("viewer_failed"))
        is Loaded.Ok -> Image(loaded.img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().testTag(tag))
    }
}

/** 사진 열람(정차 중에만 composition 에 들어온다: [com.hmgcarapp.crossdrop.domain.ScreenFlow] 가 주행 시작 시 viewing 을 비운다). */
@Composable
fun ImageViewer(file: File, modifier: Modifier = Modifier) {
    val loaded by produceState<Loaded>(Loaded.Loading, file) {
        value = withContext(Dispatchers.IO) { runCatching { ImageLoader.load(file) }.getOrNull() }?.let { Loaded.Ok(it.asImageBitmap()) } ?: Loaded.Failed
    }
    Column(modifier.fillMaxSize()) { Shown(loaded, "image_view") }
}

private sealed interface Opened {
    data object Loading : Opened
    data object Failed : Opened
    data class Ok(val pages: PdfPages) : Opened
}

/** PDF 열람: 한 번에 한 쪽, 이전/다음 쪽 버튼(정차 중에만 보인다). PdfRenderer 열기·렌더링은 모두 메인 스레드 밖에서 한다. */
@Composable
fun PdfViewer(file: File, modifier: Modifier = Modifier) {
    val opened by produceState<Opened>(Opened.Loading, file) {
        // 만든 PdfPages 는 withContext 밖 보관함에 즉시 넣는다: 생성 직후·화면 이탈 등 어떤 취소 경로에서도 finally 가 닫는다
        val holder = java.util.concurrent.atomic.AtomicReference<PdfPages?>(null)
        try {
            withContext(Dispatchers.IO) { holder.set(runCatching { PdfPages(file) }.getOrNull()) }
            value = holder.get()?.let { Opened.Ok(it) } ?: Opened.Failed
            awaitDispose { }
        } finally {
            // 닫기는 진행 중인 렌더가 끝날 때까지 기다릴 수 있으므로 메인 스레드가 아니라 IO 에서
            holder.getAndSet(null)?.let { p -> CoroutineScope(Dispatchers.IO).launch { p.close() } }
        }
    }
    val pages = (opened as? Opened.Ok)?.pages
    var page by remember(file) { mutableIntStateOf(0) }
    val count = pages?.pageCount ?: 0
    val loaded by produceState<Loaded>(Loaded.Loading, opened, page) {
        value = when (opened) {
            Opened.Loading -> Loaded.Loading
            Opened.Failed -> Loaded.Failed
            is Opened.Ok -> withContext(Dispatchers.IO) { runCatching { (opened as Opened.Ok).pages.render(page) }.getOrNull() }?.let { Loaded.Ok(it.asImageBitmap()) } ?: Loaded.Failed
        }
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
        if (count > 1) PleosPanel(Modifier.fillMaxWidth()) {
            Text("${page + 1} / $count 쪽", style = PleosTheme.typography.bodyMedium, color = PleosTheme.colors.textDarkPrimary, modifier = Modifier.testTag("pdf_page_label"))
            PleosButton("이전 쪽", onClick = { if (page > 0) page-- }, enabled = page > 0, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("pdf_prev"))
            PleosButton("다음 쪽", onClick = { if (page < count - 1) page++ }, enabled = page < count - 1, modifier = Modifier.testTag("pdf_next"))
        }
        Shown(loaded, "pdf_view")
    }
}
