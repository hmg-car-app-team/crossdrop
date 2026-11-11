package com.hmgcarapp.crossdrop

import com.hmgcarapp.crossdrop.app.NewQrPolicy
import com.hmgcarapp.crossdrop.app.Persisted
import com.hmgcarapp.crossdrop.app.SessionEndText
import com.hmgcarapp.crossdrop.app.StateStore
import com.hmgcarapp.crossdrop.app.WriteResult
import com.hmgcarapp.crossdrop.app.durable
import kotlinx.coroutines.cancelAndJoin
import com.hmgcarapp.crossdrop.app.SaveGate
import com.hmgcarapp.crossdrop.app.pollReceive
import org.junit.Assert.assertNotNull
import kotlinx.coroutines.launch
import com.hmgcarapp.crossdrop.app.PollGuard
import com.hmgcarapp.crossdrop.app.Receiver
import com.hmgcarapp.crossdrop.app.ReceiptText
import com.hmgcarapp.crossdrop.app.commitThenDelete
import com.hmgcarapp.crossdrop.domain.InboxItem
import com.hmgcarapp.crossdrop.domain.PairingInfo
import com.hmgcarapp.crossdrop.app.SyncGate
import com.hmgcarapp.crossdrop.app.SyncPump
import com.hmgcarapp.crossdrop.app.SyncStatus
import com.hmgcarapp.crossdrop.app.toPairing
import com.hmgcarapp.crossdrop.domain.Destination
import com.hmgcarapp.crossdrop.domain.FileFormat
import com.hmgcarapp.crossdrop.domain.ItemKind
import com.hmgcarapp.crossdrop.net.PairGrant
import com.hmgcarapp.crossdrop.net.RelayApi
import com.hmgcarapp.crossdrop.net.RelayException
import com.hmgcarapp.crossdrop.net.RemoteInbox
import com.hmgcarapp.crossdrop.net.RemoteItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class FakeApi(val files: MutableMap<String, ByteArray> = HashMap(), val failing: MutableMap<String, Throwable> = HashMap()) : RelayApi {
    val downloaded = ArrayList<String>(); val deleted = ArrayList<String>(); var sessionDeleted = false
    override suspend fun createSession() = PairGrant("ABCD2345", "a".repeat(64), 600, 86400)
    override suspend fun inbox(token: String) = RemoteInbox(true, 0, 100, emptyList())
    override suspend fun download(token: String, id: String, maxBytes: Int): ByteArray { downloaded += id; failing[id]?.let { throw it }; return files.getValue(id) }
    override suspend fun deleteItem(token: String, id: String) { deleted += id }
    override suspend fun deleteSession(token: String) { sessionDeleted = true }
}

class SyncGateTest {
    @Test fun statusPrecedence() {
        assertEquals(SyncStatus.DEMO, SyncGate.status(consent = true, serverUrl = "https://x", demo = true, hasSession = true, lastFailed = false))
        assertEquals("시연은 동의가 없어도 시연", SyncStatus.DEMO, SyncGate.status(false, null, true, false, false))
        assertEquals(SyncStatus.NO_CONSENT, SyncGate.status(false, "https://x", false, true, false))
        assertEquals(SyncStatus.NO_SERVER, SyncGate.status(true, null, false, true, false))
        assertEquals(SyncStatus.NO_SESSION, SyncGate.status(true, "https://x", false, false, false))
        assertEquals(SyncStatus.ERROR, SyncGate.status(true, "https://x", false, true, true))
        assertEquals(SyncStatus.ACTIVE, SyncGate.status(true, "https://x", false, true, false))
    }
    @Test fun onlyActiveAndErrorMayPoll() {
        assertEquals(setOf(SyncStatus.ACTIVE, SyncStatus.ERROR), SyncStatus.entries.filter { SyncGate.mayPoll(it) }.toSet())
    }
}

class SyncPumpTest {
    private val api = FakeApi()
    private fun pump() = SyncPump(api, "https://relay.example")

    @Test fun pollsEveryFiveSecondsAndOnlyOneAtATime() {
        val p = pump()
        assertSame(api, p.prepare(0, true, false, true))
        assertNull("전송 중에는 다음 폴링 없음", p.prepare(10_000, true, false, true))
        p.finish(true)
        assertNull(p.prepare(4_999, true, false, true))
        assertNotNull(p.prepare(5_000, true, false, true))
    }
    @Test fun backsOffTo30SecondsAfterFailure() {
        val p = pump()
        p.prepare(1000, true, false, true); p.finish(false)
        assertTrue(p.failed)
        assertNull(p.prepare(30_999, true, false, true))
        assertNotNull(p.prepare(31_000, true, false, true))
        p.finish(true); assertFalse(p.failed)
        assertNull(p.prepare(35_999, true, false, true)); assertNotNull(p.prepare(36_000, true, false, true))
    }
    @Test fun neverPollsWithoutConsentSessionOrServerOrInDemo() {
        assertNull(pump().prepare(0, false, false, true))
        assertNull(pump().prepare(0, true, true, true))
        assertNull(pump().prepare(0, true, false, false))
        assertNull(SyncPump(api, null).prepare(0, true, false, true))
        assertNull(SyncPump(null, "https://relay.example").prepare(0, true, false, true))
    }
    @Test fun pairingAndCleanupGates() {
        val p = pump()
        assertSame(api, p.apiForPairing(consent = true, demo = false))
        assertNull("동의 전에는 세션을 만들지 않는다", p.apiForPairing(false, false)); assertNull(p.apiForPairing(true, true)); assertNull(SyncPump(api, null).apiForPairing(true, false))
        assertSame("삭제 요청은 동의 철회·시연과 무관하게 서버 주소만 있으면 보낼 수 있다", api, p.apiForCleanup()); assertNull(SyncPump(api, null).apiForCleanup())
    }
    @Test fun grantBecomesPairingWithWallClockExpiries() {
        val info = PairGrant("ABCD2345", "b".repeat(64), 600, 86400).toPairing(1_000_000)
        assertEquals(1_600_000L, info.codeExpiresWallMs); assertEquals(1_000_000L + 86_400_000L, info.sessionExpiresWallMs); assertFalse(info.claimed)
    }
}

class ReceiverTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
    private val exe = "MZ".toByteArray() + ByteArray(32)
    private val pdf = "%PDF-1.4\n%%EOF\n".toByteArray()
    private val saved = ArrayList<Pair<FileFormat, Int>>()
    private var idSeq = 0
    private fun receiver(api: RelayApi) = Receiver(api, save = { f, b -> saved += f to b.size; "f_%016x.${f.ext}".format(saved.size) }, newId = { "id${++idSeq}" })
    private fun img(id: String) = RemoteItem(id, ItemKind.IMAGE, 0, "png", 40, null)
    private fun link(id: String, url: String) = RemoteItem(id, ItemKind.LINK, 0, null, 0, url)
    private fun inbox(vararg i: RemoteItem) = RemoteInbox(true, 0, 100, i.toList())
    private val token = "a".repeat(64)

    @Test fun validFileIsVerifiedSavedAndDeletedFromServer() = runBlocking {
        val api = FakeApi(files = hashMapOf("i1" to png))
        val r = receiver(api).receive(token, inbox(img("i1")), emptySet(), 5_000, 10)
        assertEquals(1, r.accepted.size); val it = r.accepted[0]
        assertEquals(ItemKind.IMAGE, it.kind); assertEquals("png", it.ext); assertEquals(40L, it.sizeBytes); assertEquals(5_000L, it.receivedWallMs); assertEquals("f_0000000000000001.png", it.localName)
        assertEquals(listOf(FileFormat.PNG to 40), saved); assertTrue("서버 삭제는 receive 가 하지 않는다(저장 뒤 호출부가)", api.deleted.isEmpty()); assertEquals(setOf("i1"), r.handled)
    }
    @Test fun spoofedFilesAreRejectedNotSavedButDeletedFromServer() = runBlocking {
        val api = FakeApi(files = hashMapOf("i1" to exe, "i2" to pdf))
        val r = receiver(api).receive(token, inbox(img("i1"), img("i2")), emptySet(), 0, 10)
        assertTrue(r.accepted.isEmpty()); assertEquals(2, r.rejected); assertTrue(saved.isEmpty())
        assertEquals(setOf("i1", "i2"), r.handled); assertTrue(api.deleted.isEmpty())
    }
    @Test fun linksAreParsedAndUrlIsNotKept() = runBlocking {
        val api = FakeApi()
        val r = receiver(api).receive(token, inbox(
            link("l1", "https://map.kakao.com/link/map/%EA%B0%95%EB%82%A8%EC%97%AD,37.4979,127.0276"),
            link("l2", "https://kko.to/abc"), link("l3", "https://evil.example/x"),
        ), emptySet(), 7, 10)
        assertEquals(Destination(37.4979, 127.0276, "강남역"), r.accepted[0].dest); assertNull(r.accepted[0].note)
        assertNull(r.accepted[1].dest); assertEquals("단축 링크는 열 수 없어요. 전체 주소 링크를 보내 주세요", r.accepted[1].note)
        assertEquals("지원하지 않는 지도 서비스예요", r.accepted[2].note)
        assertTrue("링크 항목은 다운로드하지 않는다", api.downloaded.isEmpty()); assertEquals(setOf("l1", "l2", "l3"), r.handled)
        assertTrue(r.accepted.all { it.kind == ItemKind.LINK && it.localName == null && it.ext == null })
    }
    @Test fun knownIdsAreSkippedAndRoomLimitsIntake() = runBlocking {
        val api = FakeApi(files = hashMapOf("i1" to png, "i2" to png, "i3" to png))
        val r = receiver(api).receive(token, inbox(img("i1"), img("i2"), img("i3")), setOf("i1"), 0, room = 1)
        assertEquals(1, r.accepted.size); assertEquals(listOf("i2"), api.downloaded); assertEquals(setOf("i2"), r.handled)
    }
    @Test fun transientFailureIsRetriedAndNotDeleted() = runBlocking {
        val api = FakeApi(files = hashMapOf("i2" to png), failing = hashMapOf("i1" to IOException("boom")))
        val r = receiver(api).receive(token, inbox(img("i1"), img("i2")), emptySet(), 0, 10)
        assertEquals(setOf("i1"), r.retry); assertEquals(setOf("i2"), r.handled); assertEquals(1, r.accepted.size)
    }
    @Test fun unauthorizedEndsSessionAndStops() = runBlocking {
        val api = FakeApi(files = hashMapOf("i2" to png), failing = hashMapOf("i1" to RelayException(401, "HTTP 401")))
        val r = receiver(api).receive(token, inbox(img("i1"), img("i2")), emptySet(), 0, 10)
        assertTrue(r.sessionEnded); assertEquals(listOf("i1"), api.downloaded); assertTrue(r.accepted.isEmpty())
    }
    @Test fun otherHttpErrorsAreRetriedNotFatal() = runBlocking {
        val api = FakeApi(failing = hashMapOf("i1" to RelayException(500, "HTTP 500")))
        val r = receiver(api).receive(token, inbox(img("i1")), emptySet(), 0, 10)
        assertFalse(r.sessionEnded); assertEquals(setOf("i1"), r.retry)
    }
}

class DeleteOrderAndGuardTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
    private val token = "a".repeat(64)

    @Test fun serverCopiesAreDeletedOnlyAfterPersistSucceeds() = runBlocking {
        val api = FakeApi(files = hashMapOf("i1" to png, "i2" to png))
        val events = ArrayList<String>()
        val res = Receiver(api, save = { _, _ -> events += "save"; "f_0000000000000001.png" }, newId = { "id" })
            .receive(token, RemoteInbox(true, 0, 1, listOf(RemoteItem("i1", ItemKind.IMAGE, 0, "png", 40, null), RemoteItem("i2", ItemKind.IMAGE, 0, "png", 40, null))), emptySet(), 0, 10)
        assertTrue("받는 동안(저장 전)에는 서버 삭제 없음 — 이 시점에 프로세스가 죽어도 서버에 사본이 남는다", api.deleted.isEmpty())
        commitThenDelete(api, token, res.handled) { events += "persist"; true }
        assertEquals(listOf("save", "save", "persist"), events)
        assertEquals(setOf("i1", "i2"), api.deleted.toSet())
    }

    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    private fun deletesAfter(store: StateStore): Pair<Boolean, List<String>> {
        val api = FakeApi()
        val ok = runBlocking { commitThenDelete(api, token, listOf("i1", "i2")) { store.writeLatest { Persisted(consent = true) }.durable() } }
        return ok to api.deleted
    }

    @Test fun realStateStoreHeldWritesMeanNoServerDeletion() {
        val store = StateStore(com.hmgcarapp.core.store.LocalJsonStore(tmp.newFolder("held")), "t")
        store.holdWrites()                                  // 읽기 오류 등으로 이번 세션 저장 보류
        assertEquals(WriteResult.HELD, store.writeLatest { Persisted(consent = true) })
        assertEquals(false to emptyList<String>(), deletesAfter(store))
    }

    @Test fun realStateStoreWriteFailureMeansNoServerDeletion() {
        val dir = tmp.newFolder("gone")
        val store = StateStore(com.hmgcarapp.core.store.LocalJsonStore(dir), "t")
        dir.deleteRecursively(); dir.writeText("파일이 디렉터리 자리를 차지")      // 이후 쓰기는 실제로 실패한다
        assertEquals(WriteResult.FAILED, store.writeLatest { Persisted(consent = true) })
        assertEquals(false to emptyList<String>(), deletesAfter(store))
    }

    @Test fun realStateStoreSuccessDeletesServerCopies() {
        val store = StateStore(com.hmgcarapp.core.store.LocalJsonStore(tmp.newFolder("ok")), "t")
        assertEquals(true to listOf("i1", "i2"), deletesAfter(store))
        assertEquals("같은 내용 재저장(UNCHANGED)도 디스크에 있다", true to listOf("i1", "i2", "i1", "i2").takeLast(2), deletesAfter(store).let { it.first to it.second })
    }

    @Test fun persistExceptionStillMeansNoServerDeletion() = runBlocking {
        val api = FakeApi()
        try { commitThenDelete(api, token, listOf("i1", "i2")) { throw IOException("disk full") }; org.junit.Assert.fail("예외가 전파돼야 함") } catch (e: IOException) { }
        assertTrue(api.deleted.isEmpty())
    }

    @Test fun cancellationDuringServerDeletionIsNotSwallowed() = runBlocking {
        val api = object : RelayApi by FakeApi() {
            override suspend fun deleteItem(token: String, id: String) { throw kotlinx.coroutines.CancellationException("cancel") }
        }
        try { commitThenDelete(api, token, listOf("i1")) { true }; org.junit.Assert.fail("취소가 전파돼야 함") } catch (e: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun cancelledPollRemovesAlreadySavedFiles() {
        val vault = com.hmgcarapp.crossdrop.app.FileVault(tmp.newFolder("vault"))
        val api = object : RelayApi by FakeApi() {
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            override suspend fun download(token: String, id: String, maxBytes: Int): ByteArray {
                if (id == "i3") { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                return png
            }
        }
        val inbox = RemoteInbox(true, 0, 1, listOf("i1", "i2", "i3").map { RemoteItem(it, ItemKind.IMAGE, 0, "png", 40, null) })
        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                Receiver(api, save = { f, b -> vault.save(f, b) }, newId = { "id" }, discard = { vault.delete(it) }).receive(token, inbox, emptySet(), 0, 10)
            }
            api.started.await()                         // 1·2번째는 이미 저장됐고 3번째를 받는 중
            assertEquals(2, vault.names().size)
            job.cancelAndJoin()
            assertTrue("취소 뒤 저장된 파일이 남지 않는다", vault.names().isEmpty())
        }
    }

    @Test fun completedReceiveKeepsItsFiles() = runBlocking {
        val vault = com.hmgcarapp.crossdrop.app.FileVault(tmp.newFolder("vault2"))
        val api = FakeApi(files = hashMapOf("i1" to png))
        val r = Receiver(api, save = { f, b -> vault.save(f, b) }, newId = { "id" }, discard = { vault.delete(it) }).receive(token, RemoteInbox(true, 0, 1, listOf(RemoteItem("i1", ItemKind.IMAGE, 0, "png", 40, null))), emptySet(), 0, 10)
        assertEquals(1, vault.names().size); assertEquals(vault.names(), r.accepted.mapNotNull { it.localName }.toSet())
    }

    @Test fun deleteFailuresDoNotAbortTheRest() = runBlocking {
        val api = object : RelayApi by FakeApi() {
            val seen = ArrayList<String>()
            override suspend fun deleteItem(token: String, id: String) { seen += id; if (id == "i1") throw IOException("x") }
        }
        commitThenDelete(api, token, listOf("i1", "i2")) { true }
        assertEquals(listOf("i1", "i2"), api.seen)
    }

    @Test fun pollResultsApplyOnlyToTheSameLiveConnection() {
        val p = Persisted(consent = true, pairing = PairingInfo("ABCD2345", token, 1, 2, 3))
        assertTrue(PollGuard.stillCurrent(p, token))
        assertFalse("재페어링으로 토큰이 바뀜", PollGuard.stillCurrent(p.copy(pairing = p.pairing!!.copy(deviceToken = "b".repeat(64))), token))
        assertFalse("연결 끊기/삭제로 pairing 없음", PollGuard.stillCurrent(p.copy(pairing = null), token))
        assertFalse("동의 철회", PollGuard.stillCurrent(p.copy(consent = false), token))
    }

    @Test fun receiptTextDistinguishesUnopenableLinks() {
        fun link(ok: Boolean) = InboxItem("0123456789abcdef", ItemKind.LINK, 0, dest = if (ok) Destination(37.5, 127.0, "a") else null, note = if (ok) null else "단축 링크는 열 수 없어요. 전체 주소 링크를 보내 주세요")
        fun img() = InboxItem("0123456789abcdef", ItemKind.IMAGE, 0, ext = "png", localName = "f_0123456789abcdef.png")
        assertEquals("사진 2개, 목적지 1개, 열 수 없는 링크 2개가 도착했어요", ReceiptText.of(listOf(img(), img(), link(true), link(false), link(false))).banner)
        assertEquals("단축 링크는 열 수 없어요. 전체 주소 링크를 보내 주세요", ReceiptText.of(listOf(link(false))).message)
        assertEquals("열 수 없는 링크 1개가 도착했어요", ReceiptText.of(listOf(link(false))).banner)
        assertNull(ReceiptText.of(listOf(img(), link(true))).message)
        assertEquals("목적지 1개가 도착했어요", ReceiptText.of(listOf(link(true))).banner)
    }
}

class EndTextAndNewQrTest {
    @Test fun unclaimedSessionEndingIsAnExpiredQr() {
        assertEquals("QR 이 만료됐어요", SessionEndText.of(false)); assertEquals("휴대폰 연결이 끝났어요", SessionEndText.of(true)); assertEquals("휴대폰 연결이 끝났어요", SessionEndText.of(null))
    }
    @Test fun newQrAsksOnlyWhileConnected() {
        for (st in com.hmgcarapp.crossdrop.domain.PairStatus.entries) assertEquals(st.name, st == com.hmgcarapp.crossdrop.domain.PairStatus.CONNECTED, NewQrPolicy.needsConfirm(st))
        assertEquals("아직 받지 못한 항목은 사라질 수 있어요", NewQrPolicy.WARNING)
    }
}

class PollSavePathTest {
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
    private val token = "a".repeat(64)
    private fun inbox(vararg ids: String) = RemoteInbox(true, 0, 1, ids.map { RemoteItem(it, ItemKind.IMAGE, 0, "png", 40, null) })

    @Test fun pollWithoutNewItemsNeverSaves() = runBlocking {
        var saves = 0
        val api = FakeApi()
        assertTrue(commitThenDelete(api, token, emptySet()) { saves++; true })
        assertEquals("새 항목이 없으면 저장(fsync)을 하지 않는다", 0, saves)
        assertTrue(api.deleted.isEmpty())
        // 반대 방향: 항목이 있으면 저장하고, 저장한 뒤에만 서버 삭제
        val order = ArrayList<String>()
        val api2 = object : RelayApi by FakeApi() { override suspend fun deleteItem(token: String, id: String) { order += "delete" } }
        assertTrue(commitThenDelete(api2, token, listOf("i1")) { order += "save"; true })
        assertEquals(listOf("save", "delete"), order)
    }

    @Test fun saveGateBeforeRestoreIsHeldAndNeverDeletes() {
        val store = StateStore(com.hmgcarapp.core.store.LocalJsonStore(tmp.newFolder("g1")), "t")
        var restored = false
        val gate = SaveGate(store, { restored }, { Persisted(consent = true) })
        assertEquals(WriteResult.HELD, gate.save()); assertFalse(gate.saveDurably())
        assertEquals("복원 전에는 디스크에 아무것도 쓰지 않았다", false, java.io.File(tmp.root, "g1/t.json").exists())
        val api = FakeApi()
        assertFalse(runBlocking { commitThenDelete(api, token, listOf("i1")) { gate.saveDurably() } }); assertTrue(api.deleted.isEmpty())
        restored = true
        assertEquals(WriteResult.WROTE, gate.save()); assertTrue(gate.saveDurably())
        runBlocking { commitThenDelete(api, token, listOf("i1")) { gate.saveDurably() } }; assertEquals(listOf("i1"), api.deleted)
    }

    @Test fun saveGateWithHeldStoreIsNotDurable() {
        val store = StateStore(com.hmgcarapp.core.store.LocalJsonStore(tmp.newFolder("g2")), "t")
        store.holdWrites()
        val gate = SaveGate(store, { true }, { Persisted() })
        assertEquals(WriteResult.HELD, gate.save()); assertFalse(gate.saveDurably())
    }

    @Test fun cancelRightAfterReceiveReturnedRemovesTheFiles() {
        val vault = com.hmgcarapp.crossdrop.app.FileVault(tmp.newFolder("v1"))
        val api = FakeApi(files = hashMapOf("i1" to png, "i2" to png))
        var applied = 0
        runBlocking {
            lateinit var job: kotlinx.coroutines.Job
            var n = 0
            // 마지막 항목을 저장하는 순간 취소한다: receive 는 중단점 없이 정상 반환하고, withContext 가 그 결과를 버리는 틈
            val receiver = Receiver(api, save = { f, b -> vault.save(f, b).also { if (++n == 2) job.cancel() } }, newId = { "id" })
            job = launch { pollReceive(receiver, token, inbox("i1", "i2"), emptySet(), 0, 10, discard = { vault.delete(it) }, isCurrent = { true }) { applied++ } }
            job.join()
        }
        assertEquals(0, applied); assertTrue("파일이 남지 않는다", vault.names().isEmpty())
    }

    @Test fun normalCompletionAppliesAndKeepsFiles() = runBlocking {
        val vault = com.hmgcarapp.crossdrop.app.FileVault(tmp.newFolder("v2"))
        val api = FakeApi(files = hashMapOf("i1" to png))
        var got: List<InboxItem> = emptyList()
        val r = pollReceive(Receiver(api, save = { f, b -> vault.save(f, b) }, newId = { "id" }), token, inbox("i1"), emptySet(), 0, 10, discard = { vault.delete(it) }, isCurrent = { true }) { got = it.accepted }
        assertNotNull(r); assertEquals(1, got.size); assertEquals(vault.names(), got.mapNotNull { it.localName }.toSet())
    }

    @Test fun staleConnectionDiscardsFilesAndSkipsApply() = runBlocking {
        val vault = com.hmgcarapp.crossdrop.app.FileVault(tmp.newFolder("v3"))
        val api = FakeApi(files = hashMapOf("i1" to png))
        var applied = false
        val r = pollReceive(Receiver(api, save = { f, b -> vault.save(f, b) }, newId = { "id" }), token, inbox("i1"), emptySet(), 0, 10, discard = { vault.delete(it) }, isCurrent = { false }) { applied = true }
        assertNull(r); assertFalse(applied); assertTrue(vault.names().isEmpty())
    }

    @Test fun applyFailureAfterApplyKeepsAlreadyAppliedFiles() = runBlocking {
        val vault = com.hmgcarapp.crossdrop.app.FileVault(tmp.newFolder("v4"))
        val api = FakeApi(files = hashMapOf("i1" to png))
        try {
            pollReceive(Receiver(api, save = { f, b -> vault.save(f, b) }, newId = { "id" }), token, inbox("i1"), emptySet(), 0, 10, discard = { vault.delete(it) }, isCurrent = { true }) { throw IOException("late") }
            org.junit.Assert.fail()
        } catch (e: IOException) { }
        assertEquals("이미 상태에 반영된 항목의 파일은 지우지 않는다", 1, vault.names().size)
    }

    @Test fun afterDisconnectThePollGuardRefusesNewPolls() {
        val p = Persisted(consent = true, pairing = com.hmgcarapp.crossdrop.domain.PairingInfo("ABCD2345", token, 1, 2, 3))
        assertTrue(PollGuard.stillCurrent(p, token))
        assertFalse("deleteAll 이 동기로 pairing 을 비운 뒤에는 어떤 폴링 결과도 반영되지 않는다", PollGuard.stillCurrent(p.copy(pairing = null), token))
    }
}

