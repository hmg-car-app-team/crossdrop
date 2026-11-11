package com.hmgcarapp.crossdrop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.domain.Page

/**
 * adb 시연 주입 수신기(debuggable 전용, DUMP 권한 보유자=adb shell 만 전송 가능). 서버를 거치지 않으며 저장하지 않는다.
 * 사용자 동작(열기·터치 수락 등)은 컨트롤러의 정차 가드(`parkedOnly`)를 그대로 통과해야 하므로 주행 중에는 무시된다.
 *  가상 연결/수신  --ez demo true|false  --es recv photo|pdf|music  --es link kakao|naver|google|apple|short|bad
 *  음성 결과 주입  --es heard "응"   (질문 직후 청취 단계일 때만)
 *  조작            --es touch accept|dismiss  --es page INBOX|PAIR|PRIVACY  --es open image|pdf  --es play music  --ez stop true
 *  신호            --es drive PARKED|DRIVING|REAL
 *  보관 시간 앞당김 --ei age_h 25   (시연 항목만; 24시간 지난 항목 자동 삭제 확인)
 */
internal object SimReceiver {
    const val ACTION = "com.hmgcarapp.crossdrop.SIM"
    const val PERMISSION = "android.permission.DUMP"
    const val MIN_INTERVAL_MS = 300L

    fun register(context: Context, c: DropController, monoClock: () -> Long): BroadcastReceiver {
        var lastKey: String? = null; var lastAt = 0L
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val ex = i.extras
                val key = ex?.keySet()?.sorted()?.joinToString("|") { k -> "$k=${ex.getString(k) ?: ex.get(k)}" }.orEmpty()
                val now = monoClock()
                if (key == lastKey && now - lastAt in 0 until MIN_INTERVAL_MS) { HmgLog.i("CrossDrop", "SIM 무시: ${MIN_INTERVAL_MS}ms 이내 재수신"); return }
                lastKey = key; lastAt = now
                if (i.hasExtra("demo")) { if (i.getBooleanExtra("demo", false)) c.beginDemo() else c.endDemo() }
                i.getStringExtra("recv")?.let { c.demoReceive(it) }
                i.getStringExtra("link")?.let { c.demoReceive(it) }
                i.getStringExtra("drive")?.let { c.simDrive(when (it.uppercase()) { "PARKED" -> true; "DRIVING" -> false; else -> null }) }
                i.getStringExtra("page")?.let { p -> Page.entries.firstOrNull { it.name == p.uppercase() }?.let(c::setPage) }
                i.getStringExtra("open")?.let { k -> when (k.lowercase()) { "image" -> c.simOpenNewest(ItemKind.IMAGE); "pdf" -> c.simOpenNewest(ItemKind.PDF) } }
                i.getStringExtra("play")?.let { if (it.lowercase() == "music") c.simOpenNewest(ItemKind.AUDIO) }
                if (i.getBooleanExtra("stop", false)) c.stopMusic()
                i.getStringExtra("heard")?.let { c.simHeard(it.take(40)) }
                i.getStringExtra("touch")?.let { when (it.lowercase()) { "accept" -> c.touchAccept(); "dismiss" -> c.touchDismiss() } }
                if (i.hasExtra("age_h")) c.simAge(i.getIntExtra("age_h", 0))
                HmgLog.i("CrossDrop", "SIM 수신: ${ex?.keySet()}")   // 키 이름만 남긴다(값·링크 내용은 남기지 않는다)
            }
        }
        val f = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, PERMISSION, null, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, f, PERMISSION, null)
        return r
    }
}
