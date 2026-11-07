# FusedLocation · SpeechToText · TextToSpeech

## FusedLocation v1.1.3

`implementation("ai.pleos.playground:FusedLocation:1.1.3")`, 권한 `pleos.car.permission.FUSED_LOCATION` (보호 수준 dangerous, 런타임 요청 필요).

| API | 내용 |
|---|---|
| `isSupported()` | 차종 지원 여부. 먼저 확인 |
| `initialize()` / `release()` | 생명주기 |
| `getLastKnownLocation(listener)` | 마지막 위치 1회 (없으면 null) |
| `registerFusedLocationCallback` / `unregister…` | 위치 업데이트 `onChanged(LocationData?)`, `onError` |
| `addProximityAlert(request, listener)` / `removeProximityAlert(id)` | 지오펜스: 중심 좌표·반경(m)·ENTER/EXIT 감지 |
| `showLocationAccessBlockedPopup()` | 개인정보 설정으로 위치가 막혔을 때 안내 팝업 |

`LocationData`: 위도, 경도, 고도, 진행 방향, 신뢰도, 타임스탬프(ms). **속도 필드는 없습니다.**

## SpeechToText · TextToSpeech v3.3.1

`ai.pleos.playground:SpeechToText:3.3.1`, `ai.pleos.playground:TextToSpeech:3.3.1`. 권한 `STT_SERVICE`, `TTS_SERVICE`.

| 모드 | 동작 | 앱 등록 |
|---|---|---|
| `HYBRID` (기본) | 서버 우선, 네트워크 실패 시 온디바이스 | 서버 사용 시 필요 |
| `SERVER` | 서버만 | 필요 |
| `ON_DEVICE` | 차량 엔진만, 네트워크 불필요 | 불필요 |

앱 등록은 Playground My Project의 Client ID·Secret으로 `registerApp()`을 호출하고 `onConnected()` 이후 요청합니다. 언어는 ko-KR, en-US, fr-CA, es-US, es-MX입니다.

| | STT | TTS |
|---|---|---|
| 시작·중지 | `request()` / `stop()` | `speak(text, isFinal)` / `stop()` |
| 콜백 | `onReady`, `onStartedRecognition`, `onUpdated(stt, completed)`, `onUpdatedEpdData`, `onUpdatedRms`, `onEndedRecognition`, `onError` | `onReady`, `onStart`, `onUpdatedRms`, `onDone`, `onStop`, `onError` |
| 특이사항 | 명령 실행은 안 함(텍스트만 반환). `sendAudio()`로 PCM 직접 전달은 ON_DEVICE만 | 텍스트를 `isFinal=false`로 나눠 스트리밍 가능 |

공통 오류: `PERMISSION_DENIED`, `UNAUTHENTICATED`, `NETWORK_ERROR`, `TIMEOUT`, `MIC_ERROR`, `UNAVAILABLE_LANGUAGE` 등. 에뮬레이터에서 STT를 테스트하려면 마이크 접근 설정이 필요합니다.
