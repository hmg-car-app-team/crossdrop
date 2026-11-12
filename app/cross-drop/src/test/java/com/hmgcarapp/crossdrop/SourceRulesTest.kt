package com.hmgcarapp.crossdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 제작 규칙(디자인시스템·개인정보·네트워크·모듈 의존·키 문자열·주행 가드)이 소스에서 깨지지 않게 막는 정적 점검. */
class SourceRulesTest {
    private val root = File("src/main/java/com/hmgcarapp/crossdrop")
    private fun kt(sub: String? = null): List<File> = (if (sub == null) root else File(root, sub)).walkTopDown().filter { it.extension == "kt" }.toList()
    private fun code(f: File) = f.readLines().filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/*") }.joinToString("\n")
    private fun main(rel: String) = code(File(root, rel))

    @Test fun sourcesExist() { assertTrue(kt().size >= 25) }

    @Test fun ui_usesOnlyPleosComponentsAndTokens() {
        kt("ui").forEach { f ->
            val c = code(f)
            val material = Regex("import androidx\\.compose\\.material3\\.(\\w+)").findAll(c).map { it.groupValues[1] }.toSet()
            assertEquals("${f.name}: Material 위젯 금지(Text 만 허용)", emptySet<String>(), material - "Text")
            assertFalse("${f.name}: 하드코딩 색", Regex("\\bColor\\(|0x[0-9A-Fa-f]{6,8}|Color\\.(Red|Blue|Green|White|Black|Gray)").containsMatchIn(c))
            assertFalse("${f.name}: sp/fontSize 직접 지정", Regex("\\d\\.sp\\b|fontSize\\s*=").containsMatchIn(c))
            assertFalse("${f.name}: dp 직접 지정(토큰 사용)", Regex("\\d\\.dp\\b").containsMatchIn(c))
            assertFalse("${f.name}: weight 와 고정폭 컨트롤 혼용 방지(ui 에서 weight 금지)", c.contains(".weight("))
            assertFalse("${f.name}: 키보드 입력 위젯 금지", Regex("PleosTextField|PleosNumberField|PleosDropdown|BasicTextField|TextField\\(").containsMatchIn(c))
            assertFalse("${f.name}: 웹뷰·링크 열기 금지", Regex("WebView|ACTION_VIEW|startActivity|Uri\\.parse|LocalUriHandler").containsMatchIn(c))
        }
    }

    @Test fun noDependencyOnOtherAppModules() {
        kt().forEach { f ->
            Regex("import (com\\.hmgcarapp\\.[a-z]+)").findAll(code(f)).map { it.groupValues[1] }.forEach {
                assertTrue("${f.name}: 허용되지 않은 의존 $it", it in setOf("com.hmgcarapp.core", "com.hmgcarapp.pleos", "com.hmgcarapp.crossdrop"))
            }
        }
    }

    @Test fun noPlaintextHttpNoKeysNoHardcodedServer() {
        kt().forEach { f ->
            val c = code(f)
            assertFalse("${f.name}: 평문 http 리터럴", Regex("\"http://").containsMatchIn(c))
            assertFalse("${f.name}: API 키·비밀 사용", Regex("API_KEY|apiKey|api_key|secret|Bearer\\s*[A-Za-z0-9]|Authorization:", RegexOption.IGNORE_CASE).containsMatchIn(c.replace("\"Authorization\"", "").replace("\"Bearer \$token\"", "")))
            assertFalse("${f.name}: 고정 서버 주소 하드코딩", Regex("https?://[A-Za-z0-9]").containsMatchIn(c))
            assertFalse("${f.name}: 사설/로컬 주소 하드코딩", Regex("10\\.0\\.2\\.2|192\\.168\\.|localhost|127\\.0\\.0\\.1").containsMatchIn(c.replace(Regex("\"10\\.0\\.2\\.2\".*"), "").replace(Regex("\"localhost\".*"), "").replace(Regex("\"127\\.0\\.0\\.1\".*"), "")))
        }
        val gradle = File("build.gradle.kts").readText()
        assertFalse("빌드 스크립트에 키 변수", gradle.contains("API_KEY") || gradle.contains("apiKey"))
        val rel = gradle.substringAfter("release {").substringBefore("}")
        assertTrue(gradle.contains("val releaseDefaultUrl = \"http://127.0.0.1:8780\""))
        assertTrue(rel.contains("releaseDefaultUrl") && rel.contains("configuredServerUrl"))
        assertFalse("release 에 10.0.2.2 기본값 금지(LAN 사설 주소 위험)", rel.contains("10.0.2.2") || gradle.substringBefore("android {").contains("10.0.2.2:"))
        assertTrue(gradle.substringAfter("debug {").substringBefore("}").contains("http://10.0.2.2:8780"))
    }

    @Test fun cleartextOnlyInDebugSourceSet() {
        val main = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(main.contains("cleartextTrafficPermitted=\"false\""))
        val mainDomains = Regex("<domain[^>]*>([^<]+)</domain>").findAll(main).map { it.groupValues[1] }.toSet()
        assertEquals("main(release 포함)에서 평문을 허용하는 주소는 루프백뿐(10.0.2.2 아님)", setOf("127.0.0.1", "localhost"), mainDomains)
        assertEquals("평문 허용 domain-config 는 하나", 1, Regex("cleartextTrafficPermitted=\"true\"").findAll(main).count())
        assertFalse(main.contains("includeSubdomains=\"true\""))
        val debug = File("src/debug/res/xml/network_security_config.xml").readText()
        val domains = Regex("<domain[^>]*>([^<]+)</domain>").findAll(debug).map { it.groupValues[1] }.toSet()
        assertEquals("debug 에서 평문을 허용하는 주소는 로컬 개발 주소뿐", setOf("10.0.2.2", "localhost", "127.0.0.1"), domains)
        assertFalse(File("src/release").exists())
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse(m.contains("usesCleartextTraffic"))
    }

    @Test fun manifestPermissionsAreMinimal_andNoExportedShareTargets() {
        val m = File("src/main/AndroidManifest.xml").readText()
        listOf(
            "RECORD_AUDIO", "CAMERA", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "FUSED_LOCATION", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA", "MANAGE_EXTERNAL_STORAGE",
            "READ_CONTACTS", "READ_PHONE_STATE", "GET_ACCOUNTS", "RECEIVE_BOOT_COMPLETED", "FOREGROUND_SERVICE", "POST_NOTIFICATIONS", "REQUEST_INSTALL_PACKAGES", "BLUETOOTH", "ACCESS_NETWORK_STATE", "CAR_CLIMATE", "CAR_ENERGY",
        ).forEach { assertFalse("불필요 권한 $it", m.contains(it)) }
        listOf("android.car.permission.CAR_SPEED", "pleos.car.permission.CAR_DRIVE", "TTS_SERVICE", "STT_SERVICE", "NAVI_ROUTE\"", "NAVI_ROUTE_SEARCH", "android.permission.INTERNET")
            .forEach { assertTrue("필요 권한 $it", m.contains(it)) }
        assertTrue(m.contains("android:name=\"distractionOptimized\" android:value=\"true\""))
        assertTrue(m.contains("android:allowBackup=\"false\""))
        assertFalse("다른 앱에서 파일·링크를 받는 인텐트 필터 금지(외부 입력은 릴레이 검증 경로로만)", Regex("android.intent.action.(SEND|SEND_MULTIPLE|VIEW)|android.intent.category.BROWSABLE").containsMatchIn(m))
        assertFalse("서비스·리시버·프로바이더 선언 없음", Regex("<(service|receiver|provider)").containsMatchIn(m))
    }

    @Test fun logsNeverPrintLinksCoordinatesNamesCodesTokens() {
        kt().forEach { f ->
            code(f).lines().filter { it.contains("HmgLog.") }.forEach { raw ->
                val line = raw.substringAfter("HmgLog.").substringBefore("//").replace(Regex("\\$\\{[\\w.?]*\\.(length|size)\\}"), "")   // 길이·개수만 찍는 보간은 허용
                assertFalse("${f.name}: 개인정보를 로그에 남김: $raw", Regex("\\burl\\b|\\.url\\b|\\bdest\\b|\\.dest\\b|\\.lat\\b|\\.lon\\b|latitude|longitude|localName|\\.code\\b|\\bcode\\b|deviceToken|\\btoken\\b|\\.text\\b|\\.note\\b|\\.label|\\bname\\b|\\.name\\b|filename|serverUrl|baseUrl").containsMatchIn(line.replace("errorCode.name", "")))
            }
        }
    }

    @Test fun sdkCallbacksAreManagedConnectionBased() {
        val navi = main("navi/NaviController.kt")
        assertTrue(navi.contains("ManagedConnection"))
        assertFalse("직접 재연결 루프 금지", navi.contains("while (true)"))
        assertEquals("requestRoute 는 내비 컨트롤러에서 한 번만 호출", 1, Regex("\\.requestRoute\\(").findAll(kt().joinToString("\n") { code(it) }).count())
    }

    @Test fun userActionsAreParkedOnlyGuarded() {
        val c = main("app/DropController.kt")
        listOf("setPage", "open", "pauseMusic", "resumeMusic", "stopMusic", "touchAccept", "touchDismiss", "startPairing", "endPairing", "revokeConsent", "deleteItem", "deleteAll", "startDemo", "stopDemo").forEach {
            assertTrue("$it 에 parkedOnly 가드", Regex("fun $it\\([^)]*\\)\\s*=\\s*parkedOnly").containsMatchIn(c))
        }
    }

    @Test fun routeIsRequestedOnlyByTheAcceptedDestFlowEffect() {
        val all = kt().associate { it.name to code(it) }
        assertEquals("navi.request 호출은 DestRunner 의 StartNavi 효과 한 곳", listOf("DestRunner.kt"), all.filter { it.value.contains("navi.request(") }.keys.toList())
        assertEquals(listOf("DestFlow.kt"), all.filter { it.value.contains("DestEffect.StartNavi(") && it.key != "DestRunner.kt" }.keys.toList())
        val flow = all.getValue("DestFlow.kt")
        // StartNavi 효과는 accept() 에서만 만들어진다(수락 표현 또는 정차 중 터치)
        assertEquals(1, Regex("DestEffect\\.StartNavi\\(").findAll(flow).count())
        assertTrue(flow.substringAfter("private fun accept(").substringBefore("private fun touchAccept").contains("StartNavi"))
    }

    @Test fun networkOnlyThroughRelayApiAndGatedBySyncPump() {
        val net = Regex("HttpURLConnection|java\\.net\\.URL\\b|openConnection|URL\\(|Socket\\(|okhttp", RegexOption.IGNORE_CASE)
        kt().filter { it.name != "RelayApi.kt" }.forEach { assertFalse("${it.name}: 네트워크 코드는 RelayApi.kt 에만", net.containsMatchIn(code(it).replace("android.net.", ""))) }
        val c = main("app/DropController.kt")
        assertEquals("세션 생성 호출은 한 곳", 1, Regex("\\.createSession\\(").findAll(c).count())
        assertTrue("세션 생성은 SyncPump 관문을 거친다", c.indexOf("pump.apiForPairing(") in 0 until c.indexOf(".createSession("))
        assertTrue("폴링은 SyncPump.prepare 뒤에만", c.indexOf("pump.prepare(") in 0 until c.indexOf("pollOnce(api,"))
        assertFalse("컨트롤러가 직접 HTTP 클라이언트를 만들지 않는다(기본 팩토리 제외)", Regex("HttpRelayApi\\(").findAll(c).count() > 1)
        val api = main("net/RelayApi.kt")
        assertTrue(api.contains("instanceFollowRedirects = false")); assertTrue(api.contains("RelayWire.MAX_RESPONSE_BYTES"))
    }

    @Test fun controllerUsesServerUrlPolicy() {
        assertTrue(main("app/DropController.kt").contains("ServerUrlPolicy.validate(serverUrlRaw, debuggable)"))
    }

    @Test fun listeningIsASingleShotAfterTheQuestionOnly() {
        val users = kt().filter { Regex("listenOnce\\(").containsMatchIn(code(it)) }.map { it.name }.filter { it != "Voice.kt" }
        assertEquals("STT 는 DestRunner 의 Listen 효과 한 곳(질문 직후 1회)", listOf("DestRunner.kt"), users)
        assertEquals(1, Regex("listenOnce\\(").findAll(main("app/DestRunner.kt")).count())
        assertTrue(main("app/DestRunner.kt").contains("LISTEN_MS = 8000L"))
    }

    @Test fun receivedFilesStayInAppPrivateStorage() {
        val all = kt().joinToString("\n") { code(it) }
        assertFalse("외부 저장소·미디어 저장소 사용 금지", Regex("getExternalFilesDir|getExternalStorage|MediaStore|DownloadManager|externalCacheDir|externalMediaDirs").containsMatchIn(all))
        assertTrue(main("app/DropController.kt").contains("File(context.filesDir, \"crossdrop-inbox\")"))
        assertFalse("영상 재생 금지", Regex("VideoView|ExoPlayer|PlayerView|setVideoSurface").containsMatchIn(all))
    }

    @Test fun deviceIdentifiersAreNeverCollected() {
        val all = kt().joinToString("\n") { code(it) }
        assertFalse(Regex("ANDROID_ID|getDeviceId|IMEI|getSerial|Build\\.SERIAL|AccountManager|getAccounts|Settings\\.Secure|InstanceId|advertising", RegexOption.IGNORE_CASE).containsMatchIn(all))
    }

    @Test fun pollResultsAreGuardedAndServerCopiesDeletedAfterPersisting() {
        val c = main("app/DropController.kt")
        val poll = c.substring(c.indexOf("private suspend fun pollOnce"), c.indexOf("private fun newId"))
        assertTrue("수신은 pollReceive(취소·경합 시 저장한 파일 삭제)를 거친다", poll.contains("pollReceive(receiver") && poll.contains("discard = { vault.delete(it) }"))
        assertTrue("반영 직전에 토큰·동의 재확인", poll.contains("isCurrent = { PollGuard.stillCurrent(_state.value.p, token) }"))
        assertTrue("서버 사본 삭제는 항목 반영·저장 뒤", poll.indexOf("commitThenDelete(api, token, r.handled) { saveNow().durable() }") > poll.indexOf("onReceived(r.accepted)"))
        assertFalse("Receiver 는 서버 삭제를 하지 않는다", main("app/Sync.kt").substringAfter("class Receiver(").substringBefore("/**\n * [Receiver.receive]").contains("deleteItem"))
        val end = c.substring(c.indexOf("private fun endPairingInternal"), c.indexOf("private fun deleteRemoteSession"))
        assertTrue(end.contains("pollJob?.cancel()"))
        assertTrue("401 도 토큰이 일치할 때만 연결 정보를 지운다", poll.substringAfter("e.status == 401").contains("PollGuard.stillCurrent"))
    }

    @Test fun acceptedItemsAreAppliedBeforeAnyCancellableRetryDelete() {
        val c = main("app/DropController.kt")
        val poll = c.substring(c.indexOf("private suspend fun pollOnce"), c.indexOf("private fun newId"))
        val apply = poll.substring(poll.indexOf("pollReceive(receiver"))
        assertTrue("onReceived 가 재시도 초과 deleteItem(suspend)보다 먼저", apply.indexOf("onReceived(r.accepted)") in 0 until apply.indexOf("api.deleteItem"))
        assertTrue("서버 삭제는 여전히 반영·저장 뒤", apply.indexOf("api.deleteItem") < apply.indexOf("commitThenDelete") )
    }

    @Test fun deleteAllDisconnectsSynchronouslyBeforeJoiningAndPurging() {
        val c = main("app/DropController.kt")
        val d = c.substring(c.indexOf("fun deleteAll()"), c.indexOf("private fun removeItems"))
        assertTrue("연결 끊기(토큰·pollJob 무효화)가 join·삭제보다 먼저, 동기로", d.indexOf("endPairingInternal()") in 0 until d.indexOf("scope.launch"))
        assertTrue(d.indexOf("polling?.join()") < d.indexOf("removeItems(") && d.indexOf("removeItems(") < d.indexOf("vault.purgeAll()"))
    }

    @Test fun newQrDeletesThePreviousServerSessionFirst() {
        val c = main("app/DropController.kt")
        val start = c.substring(c.indexOf("fun startPairing"), c.indexOf("fun endPairing"))
        assertTrue(start.indexOf("deleteRemoteSession(old.deviceToken)") in 0 until start.indexOf(".createSession("))
        assertTrue("서버 정리는 시연과 무관", main("app/Sync.kt").contains("fun apiForCleanup(): RelayApi?"))
    }

    @Test fun driveStateFailureFallsBackToDriving() {
        val c = main("app/DropController.kt")
        assertTrue(c.contains("coreDrive = DriveState.DRIVING; applyDrive() }.collect"))
    }

    @Test fun mediaAndPdfWorkStaysOffTheMainThread() {
        assertTrue(main("media/MusicPlayer.kt").contains("prepareAsync()")); assertFalse(Regex("\\.prepare\\(\\)").containsMatchIn(main("media/MusicPlayer.kt")))
        val v = main("ui/Viewers.kt")
        assertFalse("PdfPages 를 composition(메인)에서 직접 만들지 않는다", Regex("remember\\([^)]*\\)\\s*\\{\\s*runCatching\\s*\\{\\s*PdfPages").containsMatchIn(v))
        assertTrue(v.indexOf("withContext(Dispatchers.IO) {") in 0 until v.indexOf("runCatching { PdfPages(file) }"))
        assertTrue("EXIF 방향 적용", main("app/ViewerSupport.kt").contains("ExifTransform.of("))
    }

    @Test fun noManualCodeEntryClaimsInTheUi() {
        val ui = kt("ui").joinToString("\n") { code(it) }
        assertFalse("대체 입력 경로가 없는 안내 문구 금지", Regex("코드.{0,12}입력|주소를 열").containsMatchIn(ui))
        // 서버 웹 페이지는 모노레포에만 있다(단독 fork 에는 없음)
        File("../../server/crossdrop/web/index.html").takeIf { it.exists() }?.readText()
            ?.let { assertTrue(!it.contains("name=\"code\"") && !it.contains("id=\"code\"")) }
    }

    @Test fun pairRequestsCarryTheJsonContentTypeAndAppCancelsAreRethrown() {
        val api = main("net/RelayApi.kt")
        assertTrue(api.contains("c.setRequestProperty(\"Content-Type\", \"application/json\")"))
        assertFalse("취소를 삼키는 runCatching 으로 서버 삭제 금지", Regex("runCatching\\s*\\{\\s*api\\.deleteItem").containsMatchIn(main("app/DropController.kt") + main("app/Sync.kt")))
        assertTrue(main("app/Sync.kt").contains("catch (e: kotlinx.coroutines.CancellationException) { throw e }"))
    }

    @Test fun newQrConfirmationAndPdfCloseOffMainThread() {
        assertTrue(main("ui/Screens.kt").contains("NewQrPolicy.needsConfirm(status)"))
        val v = main("ui/Viewers.kt")
        assertTrue("생성한 PdfPages 는 withContext 밖 보관함에 넣고 finally 에서 닫는다", v.contains("holder.set(runCatching { PdfPages(file) }.getOrNull())") && v.contains("holder.getAndSet(null)?.let { p -> CoroutineScope(Dispatchers.IO).launch { p.close() } }") && v.indexOf("} finally {") > v.indexOf("holder.set("))
    }

    @Test fun pairFailureNoticeStaysUntilNextAttemptOrUserAction() {
        val c = main("app/DropController.kt"); val ui = main("ui/Screens.kt")
        assertTrue("실패 시 토스트와 별개로 pairError 를 남긴다", c.contains("it.copy(message = PAIR_FAIL_NOTE, pairError = PAIR_FAIL_NOTE)"))
        assertTrue("다음 시도 시작에서 지운다", c.contains("it.copy(busy = true, pairError = null, pairExpired = false)"))
        assertTrue("연결 끊기·시연 시작에서 지운다", c.contains("_state.update { it.copy(pairError = null, pairExpired = false) }") && c.contains("s.copy(pairError = null, pairExpired = false, demo = true"))
        assertFalse("clearMessage 는 pairError 를 건드리지 않는다", c.substringAfter("fun clearMessage()").substringBefore("\n").contains("pairError"))
        assertTrue("연결 화면이 pairError 를 표시한다", ui.contains("s.pairError != null ->") && ui.contains("\"pair_error\""))
    }
}
