package com.hmgcarapp.crossdrop.app

import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.core.store.StoreLoad

/**
 * 상태 파일 입출력(순수 파일 로직; 임시 디렉터리로 테스트).
 * - 복원(앱 시작 시 1회): tmp 전부 청소(시작 시점에는 이 프로세스가 쓰는 tmp 가 없다)·`.corrupt` 7일 경과 정리(저장이 없을 때의 상한; 보통은 다음 정상 저장 때 삭제) 후 [LocalJsonStore.loadResult] 로 읽는다.
 *   Missing -> 기본값 / Ok -> 파싱(손상이면 격리 보존) / **ReadError -> 격리하지 않고 이번 세션 저장을 보류**하고 기본값으로 동작
 *   (일시적 I/O 오류가 정상 원본을 기본값으로 덮어쓰지 않게). 세션 중 재읽기·병합은 없다: 회복은 앱 재시작 또는 [clearAll].
 * - 저장: 락 안에서 **쓰기 직전의 최신 상태**를 인코딩한다. 오래된 스냅샷이 최신을 덮어쓰거나 삭제한 기록이 되살아나지 않는다.
 * - 전체 삭제: [LocalJsonStore.purge] (json·`.corrupt`·tmp 모두) 뒤 최신 상태(설정만 남은 상태)를 다시 기록한다.
 */
/** [StateStore.writeLatest] 결과: Boolean 하나로는 '변경 없음'과 '실패'를 구분할 수 없어 나눈다. */
enum class WriteResult { WROTE, UNCHANGED, HELD, FAILED }

/** 디스크에 안전히 있다고 말할 수 있는 결과(서버 사본 삭제 조건). HELD(저장 보류)·FAILED 는 아니다. */
fun WriteResult.durable(): Boolean = this == WriteResult.WROTE || this == WriteResult.UNCHANGED

class StateStore(private val store: LocalJsonStore, private val name: String = "crossdrop_state") {
    data class Restored(val p: Persisted, val corrupt: Boolean, val quarantined: Boolean, val readError: Boolean)

    private val lock = Any()
    /** false = 읽기 오류 또는 쓰기 실패로 이번 실행 저장 보류. 기록 모두 삭제([clearAll]) 성공 시 다시 true. */
    @Volatile var writable: Boolean = true
        private set

    /**
     * 마지막으로 **기록한** 내용. 인코딩에 savedAt 이 들어가 운영에서는 거의 매번 달라지므로 UNCHANGED 는 같은 밀리초 재저장 등 드문 경우에만 나온다
     * (5초 주기 쓰기는 이 값이 아니라 handled 가 비면 saveNow 를 부르지 않는 방식으로 막는다). [restore] 는 이 값을 설정하지 않으므로
     * 복원 뒤 첫 저장은 내용이 같아도 한 번 쓴다([clearAll] 은 null 로 되돌려 다음 저장을 강제한다).
     */
    private var lastWritten: String? = null

    /** 복원 중 예상 못한 오류: 원본을 덮어쓰지 않도록 이번 세션 저장을 보류한다. */
    fun holdWrites() { synchronized(lock) { writable = false } }

    /** 앱 시작 때 한 번만 읽는다(세션 중 재읽기·병합 없음). */
    fun restore(nowMs: Long): Restored {
        // 시작 시점에는 이 프로세스가 쓰는 tmp 가 없어 tmp 최소 나이를 0 으로(r10R4-1)
        runCatching { store.cleanupStale(name, nowMs, maxAgeMs = 0L); store.pruneCorrupt(name, nowMs) }
            .onFailure { HmgLog.w("Store", "정리 실패 $it") }
        return when (val l = store.loadResult(name)) {
            StoreLoad.Missing -> Restored(Persisted(), corrupt = false, quarantined = false, readError = false)
            is StoreLoad.Ok -> {
                val r = StateCodec.parse(l.text)
                if (r.corrupt) Restored(r.p, corrupt = true, quarantined = store.quarantineCorrupt(name), readError = false)
                else Restored(r.p, corrupt = false, quarantined = false, readError = false)
            }
            is StoreLoad.ReadError -> { writable = false; Restored(Persisted(), corrupt = false, quarantined = false, readError = true) }
        }
    }

    /**
     * 쓰기 직전에 [latest] 로 최신 상태를 읽어 저장한다. 결과: WROTE 저장함 / UNCHANGED 변경 없어 쓰지 않음 /
     * HELD 저장 보류 중이라 쓰지 않음 / **FAILED 쓰기 실패 -> 이번 실행 저장을 보류**([writable]=false; 이후 쓰기 시도 없음.
     * 디스크 가득 등으로 원본 일부만 남는 상황에서 계속 시도해 상태를 더 어지럽히지 않는다. 회복은 재시작 또는 [clearAll]).
     */
    fun writeLatest(latest: () -> Persisted): WriteResult = synchronized(lock) {
        if (!writable) return WriteResult.HELD
        try {
            val text = StateCodec.encode(latest()).toString()
            if (text == lastWritten) return WriteResult.UNCHANGED // 변경 없음: 쓰지 않는다
            store.saveText(name, text)
            lastWritten = text
            WriteResult.WROTE
        } catch (t: Throwable) {
            HmgLog.w("Store", "저장 실패 -> 이번 실행 저장 보류 ${t.javaClass.simpleName}")
            writable = false
            WriteResult.FAILED
        }
    }

    /**
     * 상태 파일·`.corrupt`·tmp 를 모두 지운 뒤 [latest] (기록이 비워진 최신 상태)를 다시 기록한다.
     * 삭제에 실패하면(예외 또는 json·.corrupt·tmp 중 하나라도 잔존) false 를 돌려주고 [writable]·기준 내용은 바꾸지 않는다(보류·손상 표시 유지).
     */
    fun clearAll(latest: () -> Persisted): Boolean {
        val ok = synchronized(lock) {
            val purged = runCatching { store.purge(name) }.onFailure { HmgLog.w("Store", "삭제 실패 ${it.javaClass.simpleName}") }.getOrDefault(false) // json·.corrupt·tmp 모두 사라졌을 때만 true
            if (purged) writable = true
            lastWritten = null // 성공: 새로 쓴다 / 실패: 파일이 일부 지워졌을 수 있어 같은 내용이어도 다시 쓰게 한다
            purged
        }
        if (ok) writeLatest(latest) // 쓰기가 실패하면 writable=false 로 남고 호출부가 writable 로 표시를 동기화한다
        return ok
    }
}

/**
 * 저장 관문: 복원이 끝나기 전에는 쓰지 않고(HELD), 끝난 뒤에는 [StateStore.writeLatest] 결과를 그대로 돌려준다.
 * 서버 사본 삭제 조건([durable])이 이 한 곳을 거치므로 테스트가 실제 StateStore 로 배선을 확인한다.
 */
class SaveGate(private val store: StateStore, private val restored: () -> Boolean, private val latest: () -> Persisted) {
    fun save(): WriteResult = if (!restored()) WriteResult.HELD else store.writeLatest(latest)
    fun saveDurably(): Boolean = save().durable()
}
