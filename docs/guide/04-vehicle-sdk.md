# Vehicle SDK 사용 가이드

기준: Vehicle SDK v2.3.2, Vehicle Service v2.3.21. 속성 전체는 [05-vehicle-data-dictionary.md](05-vehicle-data-dictionary.md)를 보세요.

## 설정

```kotlin
// settings.gradle.kts
maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
// app/build.gradle.kts
implementation("ai.pleos.playground:Vehicle:2.3.2")
```

매니페스트에는 쓰는 도메인 권한만 `<uses-permission android:name="pleos.car.permission.CAR_ENERGY" />` 형식으로 선언합니다. 조회는 `CAR_*`, 제어는 `CONTROL_CAR_*`입니다.

| 도메인 | 조회 | 제어 |
|---|---|---|
| Brake, DrivingMode, Odometer | `CAR_DRIVE` | - |
| CarInfo, Display | `CAR_INFO` | - |
| Door | `CAR_DOORS` | `CONTROL_CAR_DOORS` |
| EvBattery, Fuel | `CAR_ENERGY` | `CONTROL_CAR_ENERGY` (EvBattery) |
| HVAC, SteeringWheel 열선 | `CAR_CLIMATE` | `CONTROL_CAR_CLIMATE` |
| Light, TurnSignal | `CAR_LIGHTS` | `CONTROL_CAR_LIGHTS` (Light) |
| Seat | `CAR_SEATS` | `CONTROL_CAR_SEATS` |
| SideMirror | `CAR_MIRRORS`, `CAR_CLIMATE` | `CONTROL_CAR_MIRRORS` |
| Safety, Tire | `CAR_SAFETY` (+ 일부 `PRIVATE_CAR_SAFETY`) | - |
| Toll | `CAR_TOLL` | `CONTROL_CAR_TOLL` |
| Window, Wiper | `CAR_WINDOWS` | `CONTROL_CAR_WINDOWS` |

## 사용 흐름

```kotlin
val vehicle = Vehicle(context)
vehicle.initialize()
val door = vehicle.getDoor()
door.checkDoorPositionCapabilities(
    onComplete = { config, areaMap -> /* AccessType: NONE/READ/WRITE/READ_WRITE, AreaBounds(min,max) */ },
    onFailed = { e -> }
)
door.getCurrentDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), onComplete = { pos -> }, onFailed = { e -> })
door.setDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), 1, onFailed = { e -> })
door.registerDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), listener)   // 해제: unregisterDoorPosition
vehicle.release()   // 비동기 콜백이 끝난 뒤 호출
```

- 차량 통신은 IPC라서 모든 결과가 `onComplete`·`onFailed` 콜백으로 비동기 전달됩니다.
- set 완료 콜백의 값은 요청한 값입니다. 실제 값은 다시 조회하거나 이벤트로 확인하세요.
- 값 범위는 차량마다 다릅니다. 에뮬레이터의 Door Position 범위는 0..1입니다.

## 오류 코드 (`VehicleException.errorCode`)

| 코드 | 의미 | 처리 |
|---|---|---|
| `ERROR_NOT_SUPPORTED_PROPERTY` / `_AREA` / `_VALUE_TYPE` | 미지원 속성·영역·타입 | UI 비활성, 영역·타입 재확인 |
| `ERROR_SECURITY_PERMISSION` | 권한 부족 | 매니페스트 확인 |
| `ERROR_SERVICE_CONNECTION_FAILURE`, `ERROR_INTERNAL_SERVICE` | 서비스 연결·내부 오류 | initialize·재시도 |
| `ERROR_DRIVING_RESTRICTED` | 주행 중 제한 | 주행 제한 안내 |
| `ERROR_EMPTY_RESULT`, `ERROR_UNDEFINED` | 결과 없음, 미정의 | 빈 상태 UI, 로그 |

## 샘플 앱

[pleos-sample-vehicle-app](https://github.com/Pleos-Playground/pleos-sample-vehicle-app) 기준 환경: AVD Pleos_Connect_v2.1(Android 14), JDK 17, Gradle 8.10.2, AGP 8.8.2, Kotlin 2.0.0, compile·targetSdk 35. Door Position 화면이 지원 확인 → 조회 → 구독 → 변경 → 해제의 기준 예제입니다.
