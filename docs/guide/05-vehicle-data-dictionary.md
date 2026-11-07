# Vehicle SDK 데이터 사전

Vehicle SDK v2.3.2의 도메인별 속성을 조회·제어·이벤트 메서드 단위로 정리한 표입니다. [04-vehicle-sdk.md](04-vehicle-sdk.md)의 사용법과 함께 보세요.

출처는 공식 Maven 저장소의 `Vehicle-2.3.2-sources.jar`에 있는 인터페이스 소스(`api/*.kt`)의 KDoc과 `@RequiresPermission` 어노테이션입니다. 스크립트로 추출했고 원본은 [hmg-car-app-contest](https://github.com/hmg-car-app-team/hmg-car-app-contest)의 `reference/artifacts/Vehicle-2.3.2/`에 있습니다.

표 읽는 법:

- **조회**는 현재 값을 한 번 읽는 메서드, **제어**는 값을 바꾸는 메서드, **이벤트**는 값이 바뀔 때마다 알려 주는 리스너 등록 메서드입니다. 이벤트를 등록했다면 같은 이름의 `unregister…`로 해제합니다.
- 거의 모든 속성에 `check…Capabilities` 메서드가 있습니다. 차량이 그 속성을 지원하는지, 읽기·쓰기 중 무엇이 가능한지(`AccessType`), 허용 값 범위(`AreaBounds`)가 얼마인지 먼저 확인하세요. 값 범위는 차량마다 달라서 이 표에 고정값으로 적지 않았습니다.
- **영역**이 있는 속성은 `SeatArea(SeatValue.ROW_1_LEFT)`처럼 위치를 지정해 호출합니다. 영역 값은 맨 아래 부록에 있습니다.
- **값** 열의 타입이 enum이면 가능한 값은 부록에 있습니다. 모든 조회 결과는 nullable입니다.
- **비고**의 `폐기 예정`은 `@Deprecated`, `문서 비공개`는 `@hide`가 붙은 API입니다. 새로 만드는 앱에서는 쓰지 마세요.

## 권한 등급 요약

매니페스트에 `<uses-permission android:name="pleos.car.permission.{상수명}" />` 형식으로 선언합니다. `CAR_*`는 조회, `CONTROL_CAR_*`는 제어용입니다.

| 권한 | 등급 | 서드파티 앱 사용 |
|---|---|---|
| `CAR_CLIMATE` | normal | 가능 |
| `CAR_DOORS` | normal | 가능 |
| `CAR_DRIVE` | normal | 가능 |
| `CAR_ENERGY` | normal | 가능 |
| `CAR_INFO` | normal | 가능 |
| `CAR_LIGHTS` | normal | 가능 |
| `CAR_MIRRORS` | normal | 가능 |
| `CAR_SAFETY` | normal | 가능 |
| `CAR_SEATS` | normal | 가능 |
| `CAR_TOLL` | normal | 가능 |
| `CAR_WINDOWS` | normal | 가능 |
| `CONTROL_CAR_CLIMATE` | normal | 가능 |
| `CONTROL_CAR_DOORS` | normal | 가능 |
| `CONTROL_CAR_ENERGY` | normal | 가능 |
| `CONTROL_CAR_LIGHTS` | normal | 가능 |
| `CONTROL_CAR_MIRRORS` | normal | 가능 |
| `CONTROL_CAR_SEATS` | normal | 가능 |
| `CONTROL_CAR_TOLL` | normal | 가능 |
| `CONTROL_CAR_WINDOWS` | normal, signature\|privileged | 일부 API만 시스템 권한 |
| `PRIVATE_CAR_SAFETY` | signature\|privileged | 어려움 (시스템·사전 탑재 앱 권한) |

`CONTROL_CAR_WINDOWS`는 대부분 normal이지만 `setSmartGlassState`의 KDoc에만 `signature|privileged`로 적혀 있습니다. `PRIVATE_CAR_SAFETY`가 필요한 Safety·Tire API는 일반 앱에서 쓸 수 없다고 보고 설계하세요.

## 도메인 목록

| 도메인 | 진입 메서드 | 내용 | 속성 수 |
|---|---|---|---|
| [Brake](#brake) | `vehicle.getBrake()` | 브레이크 페달 상태 | 1 |
| [CarInfo](#carinfo) | `vehicle.getCarInfo()` | 차량 기본 정보 | 3 |
| [Display](#display) | `vehicle.getDisplay()` | 인포테인먼트 표시 단위 | 1 |
| [Door](#door) | `vehicle.getDoor()` | 도어·글로브 박스 잠금과 열림 | 3 |
| [DrivingMode](#drivingmode) | `vehicle.getDrivingMode()` | 주행 모드와 크루즈 컨트롤 | 3 |
| [EvBattery](#evbattery) | `vehicle.getEvBattery()` | EV 배터리·충전 상태와 전비 | 11 |
| [Fuel](#fuel) | `vehicle.getFuel()` | 연료 잔량과 연비 | 5 |
| [HVAC](#hvac) | `vehicle.getHVAC()` | 공조(온도, 풍량, 내기 순환 등) | 14 |
| [Light](#light) | `vehicle.getLight()` | 외부·실내 조명과 앰비언트 라이트 | 19 |
| [Odometer](#odometer) | `vehicle.getOdometer()` | 주행 거리, 트립 시간·거리 | 11 |
| [Safety](#safety) | `vehicle.getSafety()` | 에어백, 안전벨트 경고, 오일·워셔액, 계기판 경고등 | 9 |
| [Seat](#seat) | `vehicle.getSeat()` | 시트 위치·착석·안전벨트·열선·통풍 | 16 |
| [SideMirror](#sidemirror) | `vehicle.getSideMirror()` | 사이드미러 열선 | 1 |
| [SteeringWheel](#steeringwheel) | `vehicle.getSteeringWheel()` | 조향각과 스티어링휠 열선 | 2 |
| [Tire](#tire) | `vehicle.getTire()` | 타이어 공기압 | 2 |
| [Toll](#toll) | `vehicle.getToll()` | 하이패스(ETCS) 음성 안내 | 1 |
| [TurnSignal](#turnsignal) | `vehicle.getTurnSignal()` | 방향 지시등 | 1 |
| [Window](#window) | `vehicle.getWindow()` | 창문·블라인드·스마트 글래스 | 6 |
| [Wiper](#wiper) | `vehicle.getWiper()` | 와이퍼 | 2 |
| [Gear](#gear) | `vehicle.getGear()` | 기어 선택 상태 (문서 비공개, 폐기 예정) | 1 |

## Brake

브레이크 페달 상태. 진입: `vehicle.getBrake()`

권한: 조회 `CAR_DRIVE` · 제어 -

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| BrakePedalStatus | 브레이크 페달 밟힘 여부 | `Boolean` | - | `getCurrentBrakePedalStatus` | - | `registerBrakePedalStatus` | - |

## CarInfo

차량 기본 정보. 진입: `vehicle.getCarInfo()`

권한: 조회 `CAR_INFO` · 제어 -

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| CRNInfo | CRN(Connected Resource Name) 정보 | `String` | - | `getCRNInfo` | - | - | 폐기 예정 |
| VINInfo | 차대번호(VIN) | `String` | - | `getVINInfo` | - | - | - |
| CapabilityInfo | 전체 기능 지원 정보 | `Map<String, CapabilityConfig<Any>>` | - | `getCapabilityInfo` | - | - | 문서 비공개(@hide)<br>지원 확인 메서드 없음 |

## Display

인포테인먼트 표시 단위. 진입: `vehicle.getDisplay()`

권한: 조회 `CAR_INFO` · 제어 -

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| TemperatureDisplayUnit | 온도 표시 단위 | `TemperatureUnit` | - | `getCurrentTemperatureDisplayUnit` | - | `registerTemperatureDisplayUnit` | - |

## Door

도어·글로브 박스 잠금과 열림. 진입: `vehicle.getDoor()`

권한: 조회 `CAR_DOORS` · 제어 `CONTROL_CAR_DOORS`

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| GloveBoxLock | 글로브 박스 잠금 | `Boolean` | - | `getCurrentGloveBoxLockState` | `setGloveBoxLock` | `registerGloveBoxLockState` | - |
| DoorLock | 도어 잠금 | `Boolean` | Door | `getDoorLockState` | `setDoorLock` | `registerDoorLockState` | - |
| DoorPosition | 도어 열림 위치 | `Int` | Door | `getCurrentDoorPosition` | `setDoorPosition` | `registerDoorPosition` | - |

## DrivingMode

주행 모드와 크루즈 컨트롤. 진입: `vehicle.getDrivingMode()`

권한: 조회 `CAR_DRIVE` · 제어 -

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| CruiseControlTargetSpeed | 크루즈 목표 속도 | `Float` (m/s) | - | `getCruiseControlTargetSpeed` | - | `registerCruiseControlTargetSpeed` | - |
| CruiseControl | 크루즈 컨트롤 상태 | `CruiseControl` | - | `getCruiseControlState` | - | `registerCruiseControlState` | - |
| DrivingMode | 주행 모드 | `DrivingModeType` | - | `getCurrentDrivingModeState` | - | `registerDrivingModeState` | - |

## EvBattery

EV 배터리·충전 상태와 전비. 진입: `vehicle.getEvBattery()`

권한: 조회 `CAR_ENERGY` · 제어 `CONTROL_CAR_ENERGY`

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| InstantaneousEvEfficiency | 순간 전비 | `Float` (단위 미기재) | - | `getInstantaneousEvEfficiency` | - | `registerInstantaneousEvEfficiency` | - |
| SinceChargeEvEfficiency | 충전 후 전비 | `Float` (단위 미기재) | - | `getSinceChargeEvEfficiency` | - | `registerSinceChargeEvEfficiency` | - |
| CumulativeEvEfficiency | 누적 전비 | `Float` (단위 미기재) | - | `getCumulativeEvEfficiency` | - | `registerCumulativeEvEfficiency` | - |
| EvEfficiency | 현재 전비 | `Float` (단위 미기재) | - | `getCurrentEvEfficiency` | - | `registerCurrentEvEfficiency` | - |
| EvBatteryStateOfCharge | 배터리 충전량(SOC) | `Int` (%) | - | `getEvBatteryStateOfCharge` | - | `registerEvBatteryStateOfCharge` | - |
| EvChargeCurrentDrawLimit | 충전 전류 제한 | `Float` (A) | - | `getEvChargeCurrentDrawLimit` | `setEvChargeCurrentDrawLimit` | - | - |
| EvChargePercentLimit | 목표 충전량 | `Float` (%) | - | - | `setEvChargePercentLimit` | - | - |
| EvChargePortOpen | 충전 포트 열림 | `Boolean` | - | `isEvChargePortOpen` | `setEvChargePortOpen` | `registerEvChargePortOpen` | 폐기 예정 메서드: `isEvChargePortOpen`, `registerEvChargePortOpen`, `unregisterEvChargePortOpen`<br>문서 비공개(@hide) |
| EvChargeSwitch | 충전 시작·중지 | `Boolean` | - | `getEvChargeSwitchState` | `setEvChargeSwitch` | - | - |
| EvBatteryLevel | 배터리 잔량 | `Float` (Wh) | - | `getEvBatteryLevel` | - | `registerEvBatteryLevel` | 폐기 예정: Android 표준 VHAL 속성 `EV_BATTERY_LEVEL` 사용 권장<br>문서 비공개(@hide)<br>지원 확인 메서드 없음 |
| EvBatteryCapacity | 배터리 용량 | `Float` (Wh) | - | `getEvBatteryCapacity` | - | - | 폐기 예정: Android 표준 VHAL 속성 `INFO_EV_BATTERY_CAPACITY` 사용 권장<br>문서 비공개(@hide)<br>지원 확인 메서드 없음 |

## Fuel

연료 잔량과 연비. 진입: `vehicle.getFuel()`

권한: 조회 `CAR_ENERGY` · 제어 -

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| InstantaneousFuelEconomy | 순간 연비 | `Float` (단위 미기재) | - | `getInstantaneousFuelEconomy` | - | `registerInstantaneousFuelEconomy` | - |
| SinceChargeFuelEconomy | 주유 후 연비 | `Float` (단위 미기재) | - | `getSinceChargeFuelEconomy` | - | `registerSinceChargeFuelEconomy` | - |
| CumulativeFuelEconomy | 누적 연비 | `Float` (단위 미기재) | - | `getCumulativeFuelEconomy` | - | `registerCumulativeFuelEconomy` | - |
| FuelEconomy | 현재 연비 | `Float` (단위 미기재) | - | `getCurrentFuelEconomy` | - | `registerCurrentFuelEconomy` | - |
| FuelPercent | 연료 잔량 | `Int` (%) | - | `getFuelPercent` | - | `registerFuelPercent` | - |

## HVAC

공조(온도, 풍량, 내기 순환 등). 진입: `vehicle.getHVAC()`

권한: 조회 `CAR_CLIMATE` · 제어 `CONTROL_CAR_CLIMATE`

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| HvacAc | 에어컨 | `Boolean` | Seat | `getHvacAcState` | `setHvacAc` | `registerHvacAcState` | - |
| HvacAuto | 자동 모드 | `Boolean` | Seat | `getHvacAutoState` | `setHvacAuto` | `registerHvacAutoState` | - |
| HvacRecirculation | 내기 순환 | `Boolean` | Seat | `getHvacRecirculationState` | `setHvacRecirculation` | `registerHvacRecirculationState` | - |
| HvacDefroster | 성에 제거 | `Boolean` | Window | `getHvacDefrosterState` | `setHvacDefroster` | `registerHvacDefrosterState` | - |
| HvacDual | 좌우 온도 연동(듀얼) | `Boolean` | Seat | `getHvacDualState` | `setHvacDual` | `registerHvacDualState` | - |
| HvacFanDirection | 송풍 방향 | `FanDirectionType` | Seat | `getHvacFanDirection` | `setHvacFanDirection` | - | - |
| HvacFanSpeed | 풍량 | `Int` | Seat | `getHvacFanSpeed` | `setHvacFanSpeed` | `registerHvacFanSpeed` | - |
| HvacMaxAc | 최대 냉방 | `Boolean` | Seat | `getHvacMaxAcState` | `setHvacMaxAc` | `registerHvacMaxAcState` | - |
| HvacMaxDefrost | 최대 성에 제거 | `Boolean` | Seat | `getHvacMaxDefrostState` | `setHvacMaxDefrost` | `registerHvacMaxDefrostState` | - |
| HvacPower | 공조 전원 | `Boolean` | Seat | `getHvacPowerState` | `setHvacPower` | `registerHvacPowerState` | - |
| HvacAutoRecirculation | 자동 내기 순환 | `Boolean` | Seat | `getHvacAutoRecirculationState` | `setHvacAutoRecirculation` | `registerHvacAutoRecirculationState` | - |
| HvacAirCleaner | 공기 청정 | `Boolean` | Seat | `getHvacAirCleanerState` | `setHvacAirCleaner` | `registerHvacAirCleanerState` | - |
| HvacTemperature | 설정 온도 | `Float` (단위는 `TemperatureUnit` 인자로 지정) | Seat | `getHvacTemperature` | `setHvacTemperature` | `registerHvacTemperature` | - |
| HvacTemperatureCurrent | 실내 현재 온도 | `Float` (°C) | Seat | `getHvacTemperatureCurrent` | - | `registerHvacTemperatureCurrent` | - |

## Light

외부·실내 조명과 앰비언트 라이트. 진입: `vehicle.getLight()`

권한: 조회 `CAR_LIGHTS` · 제어 `CAR_LIGHTS`, `CONTROL_CAR_LIGHTS`

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| ReadingLightSwitch | 독서등 스위치 | `LightSwitch` | Seat | `getReadingLightSwitch` | `setReadingLightSwitch` | - | - |
| CabinLightSwitch | 실내등 스위치 | `LightSwitch` | - | `getCabinLightSwitch` | `setCabinLightSwitch` | - | - |
| HighBeamLightSwitch | 상향등 스위치 | `LightSwitch` | - | `getHighBeamLightSwitch` | `setHighBeamLightSwitch` | - | - |
| LowBeamLightSwitch | 하향등 스위치 | `LightSwitch` | - | `getLowBeamLightSwitch` | `setLowBeamLightSwitch` | - | - |
| TailLampLightSwitch | 미등 스위치 | `LightSwitch` | - | `getTailLampLightSwitch` | `setTailLampLightSwitch` | - | - |
| FrontFogLightSwitch | 전방 안개등 스위치 | `LightSwitch` | - | `getFrontFogLightSwitch` | `setFrontFogLightSwitch` | - | - |
| RearFogLightSwitch | 후방 안개등 스위치 | `LightSwitch` | - | `getRearFogLightSwitch` | `setRearFogLightSwitch` | - | - |
| ReadingLight | 독서등 점등 상태 | `LightState` | Seat | `getCurrentReadingLightState` | - | `registerReadingLightState` | - |
| CabinLight | 실내등 점등 상태 | `LightState` | - | `getCurrentCabinLightState` | - | `registerCabinLightState` | - |
| HighBeamLight | 상향등 점등 상태 | `LightState` | - | `getCurrentHighBeamLightState` | - | `registerHighBeamLightState` | - |
| LowBeamLight | 하향등 점등 상태 | `LightState` | - | `getCurrentLowBeamLightState` | - | `registerLowBeamLightState` | - |
| TailLampLight | 미등 점등 상태 | `LightState` | - | `getCurrentTailLampLightState` | - | `registerTailLampLightState` | - |
| FrontFogLight | 전방 안개등 점등 상태 | `LightState` | - | `getCurrentFrontFogLightState` | - | `registerFrontFogLightState` | - |
| RearFogLight | 후방 안개등 점등 상태 | `LightState` | - | `getCurrentRearFogLightState` | - | `registerRearFogLightState` | - |
| AmbientLightBrightness | 앰비언트 라이트 밝기 | `Int` | Seat | `getAmbientLightBrightness` | `setAmbientLightBrightness` | `registerAmbientLightBrightnessListener` | - |
| AmbientLightBrightnessColor | 앰비언트 라이트 색상(32-bit ARGB) | `Int` | Seat | `getAmbientLightBrightnessColor` | `setAmbientLightBrightnessColor` | `registerAmbientLightBrightnessColorListener` | - |
| AmbientLightSwitch | 앰비언트 라이트 스위치 | `LightSwitch` | Seat | `getAmbientLightSwitch` | `setAmbientLightSwitch` | - | - |
| AmbientLight | 앰비언트 라이트 점등 상태 | `LightState` | Seat | `getAmbientLightState` | - | `registerAmbientLightStateListener` | - |
| AmbientColorAutoByDriveModeEnabled | 주행 모드에 따른 앰비언트 색상 자동 변경 | `Boolean` | - | `getAmbientColorAutoByDriveModeEnabled` | `setAmbientColorAutoByDriveModeEnabled` | `registerAmbientColorAutoByDriveModeEnabled` | - |

## Odometer

주행 거리, 트립 시간·거리. 진입: `vehicle.getOdometer()`

권한: 조회 `CAR_DRIVE` · 제어 -

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| DistanceDriven | 누적 주행 거리(오도미터) | `Float` (km) | - | `getCurrentDistanceDriven` | - | `registerDistanceDriven` | - |
| TripDrivingTime(공통) | 트립 주행 시간 지원 확인(공통) | - | - | - | - | - | 지원 확인 전용: `checkTripDrivingTimeCapabilities` |
| TripCurrentDrivingTime | 이번 주행 시간 | `Int` (분) | - | `getTripCurrentDrivingTime` | - | `registerTripCurrentDrivingTime` | - |
| TripCumulativeDrivingTime | 누적 트립 주행 시간 | `Int` (분) | - | `getTripCumulativeDrivingTime` | - | `registerTripCumulativeDrivingTime` | - |
| TripSinceRefillDrivingTime | 주유·충전 후 주행 시간 | `Int` (분) | - | `getTripSinceRefillDrivingTime` | - | `registerTripSinceRefillDrivingTime` | - |
| TripDrivingDistance(공통) | 트립 주행 거리 지원 확인(공통) | - | - | - | - | - | 지원 확인 전용: `checkTripDrivingDistanceCapabilities` |
| TripCurrentDrivingDistance | 이번 주행 거리 | `Float` (m) | - | `getTripCurrentDrivingDistance` | - | `registerTripCurrentDrivingDistance` | - |
| TripInstantaneousDrivingDistance | 순간 트립 거리 | `Float` (m) | - | `getTripInstantaneousDrivingDistance` | - | `registerTripInstantaneousDrivingDistance` | 폐기 예정: TripStatisticType.INSTANTANEOUS 기반이라 폐기 |
| TripCumulativeDrivingDistance | 누적 트립 거리 | `Float` (m) | - | `getTripCumulativeDrivingDistance` | - | `registerTripCumulativeDrivingDistance` | - |
| TripSinceRefillDrivingDistance | 주유·충전 후 주행 거리 | `Float` (m) | - | `getTripSinceRefillDrivingDistance` | - | `registerTripSinceRefillDrivingDistance` | - |
| RangeRemainingDistance | 주행 가능 거리 | `Float` (m) | - | `getRangeRemainingDistance` | `setRangeRemainingDistance` | `registerRangeRemainingListener` | 폐기 예정<br>문서 비공개(@hide)<br>지원 확인 메서드 없음 |

## Safety

에어백, 안전벨트 경고, 오일·워셔액, 계기판 경고등. 진입: `vehicle.getSafety()`

권한: 조회 `CAR_SAFETY`, `PRIVATE_CAR_SAFETY` · 제어 -

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| SeatAirbagEnabled | 좌석 에어백 활성 여부 | `Boolean` | Seat | `hasSeatAirbagEnabled` | - | `registerSeatAirbagEnabled` | - |
| SeatAirbagDeployed | 에어백 전개 여부 | `Boolean` | Seat | `hasSeatAirbagBeenDeployed` | - | `registerSeatAirbagDeployed` | - |
| SeatBeltNotBuckledWarning | 안전벨트 미착용 경고 | `Boolean` | Seat | `hasSeatBeltNotBuckledWarning` | - | `registerSeatBeltNotBuckledWarning` | - |
| EngineOilLevel | 엔진 오일 수준 | `OilLevelState` | - | `getEngineOilLevelState` | - | `registerEngineOilLevelState` | - |
| BrakeFluidLevel | 브레이크액 수준 | `OilLevelState` | - | `getBrakeFluidLevelState` | - | `registerBrakeFluidLevelState` | - |
| TransmissionOilLevel | 변속기 오일 수준 | `OilLevelState` | - | `getTransmissionOilLevelState` | - | `registerTransmissionOilLevelState` | - |
| LowWasherFluidWarning | 워셔액 부족 경고 | `Boolean` | - | `hasLowWasherFluidWarning` | - | `registerLowWasherFluidWaringState` | - |
| PowerDownWarningIndicator | 파워 다운 경고등 | `Boolean` | - | `hasPowerDownWarningIndicator` | - | `registerPowerDownWarningIndicator` | - |
| Telltales | 계기판 경고등 목록 | `Telltales` | - | `getTelltalesInfo` | - | `registerTelltales` | 시스템 권한 필요: `PRIVATE_CAR_SAFETY` (signature\|privileged)<br>`getTelltalesInfo`: 어노테이션 `CAR_SAFETY`, KDoc `PRIVATE_CAR_SAFETY` 불일치 |

## Seat

시트 위치·착석·안전벨트·열선·통풍. 진입: `vehicle.getSeat()`

권한: 조회 `CAR_SEATS` · 제어 `CONTROL_CAR_SEATS`

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| SeatOccupancy | 좌석 착석 여부 | `Boolean` | Seat | `getCurrentSeatOccupancy` | - | `registerSeatOccupancy` | - |
| SeatBeltBuckled | 안전벨트 체결 여부 | `Boolean` | Seat | `getCurrentSeatBeltBuckledState` | - | `registerSeatBeltBuckledState` | - |
| SeatBackrestAngle | 등받이 각도 | `Int` | Seat | `getCurrentSeatBackrestAnglePosition` | `moveSeatBackrestAngle` | `registerSeatBackrestAnglePosition` | 폐기 예정 메서드: `checkSeatBackrestAngleCapabilities` |
| SeatHeadrestAngle | 헤드레스트 각도 | `Int` | Seat | `getCurrentSeatHeadrestAnglePosition` | `moveSeatHeadrestAngle` | `registerSeatHeadrestAnglePosition` | 폐기 예정 메서드: `checkSeatHeadrestAngleCapabilities` |
| SeatHeadrestForeAft | 헤드레스트 앞뒤 위치 | `Int` | Seat | `getCurrentSeatHeadrestForeAftPosition` | `moveSeatHeadrestForeAft` | `registerSeatHeadrestForeAftPosition` | 폐기 예정 메서드: `checkSeatHeadrestForeAftCapabilities` |
| SeatHeadrestHeight | 헤드레스트 높이 | `Int` | Seat | `getCurrentSeatHeadrestHeightAftPosition` | `moveSeatHeadrestHeight` | `registerSeatHeadrestHeightAftPosition` | 폐기 예정 메서드: `checkSeatHeadrestHeightCapabilities` |
| SeatHeight | 시트 높이 | `Int` | Seat | `getCurrentSeatHeightPosition` | `moveSeatHeight` | `registerSeatHeightPosition` | 폐기 예정 메서드: `checkSeatHeightCapabilities` |
| SeatLumbarForeAft | 허리 지지대 앞뒤 | `Int` | Seat | `getCurrentSeatLumbarForeAftPosition` | `moveSeatLumbarForeAft` | `registerSeatLumbarForeAftPosition` | 폐기 예정 메서드: `checkSeatLumbarForeAftCapabilities` |
| SeatLumbarSideSupport | 허리 측면 지지 | `Int` | Seat | `getCurrentSeatLumbarSideSupportPosition` | `moveSeatLumbarSideSupport` | `registerSeatLumbarSideSupportPosition` | 폐기 예정 메서드: `checkSeatLumbarSideSupportCapabilities` |
| SeatTilt | 쿠션 기울기 | `Int` | Seat | `getCurrentSeatTiltPosition` | `moveSeatTilt` | `registerSeatTiltPosition` | 폐기 예정 메서드: `checkSeatTiltCapabilities` |
| SeatDepth | 쿠션 길이 | `Int` | Seat | `getCurrentSeatDepthPosition` | `moveSeatDepth` | `registerSeatDepthPosition` | 폐기 예정 메서드: `checkSeatDepthCapabilities` |
| SeatForeAft | 시트 앞뒤 위치 | `Int` | Seat | `getCurrentSeatForeAftPosition` | `moveSeatForeAft` | `registerSeatForeAftPosition` | 폐기 예정 메서드: `checkSeatForeAftCapabilities` |
| SeatBeltHeight | 안전벨트 높이 | `Int` | Seat | `getCurrentSeatBeltHeightPosition` | `moveSeatBeltHeight` | `registerSeatBeltHeightPosition` | 폐기 예정 메서드: `checkSeatBeltHeightCapabilities` |
| SeatTemperature | 시트 열선·냉방 단계 | `Int` | Seat | `getHvacSeatTemperature` | `setHvacSeatTemperature` | `registerHvacSeatTemperature` | - |
| SeatVentilation | 시트 통풍 단계 | `Int` | Seat | `getHvacSeatVentilationState` | `setHvacSeatVentilation` | `registerHvacSeatVentilation` | - |
| SeatMemory | 시트 메모리 호출 | `Int` | Seat | - | `selectSeatMemory` | - | - |

## SideMirror

사이드미러 열선. 진입: `vehicle.getSideMirror()`

권한: 조회 `CAR_CLIMATE`, `CAR_MIRRORS` · 제어 `CONTROL_CAR_MIRRORS`

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| HvacSideMirrorHeat | 사이드미러 열선 | `Int` | Mirror | `getHvacSideMirrorHeatState` | `setHvacSideMirrorHeat` | `registerHvacSideMirrorHeatState` | - |

## SteeringWheel

조향각과 스티어링휠 열선. 진입: `vehicle.getSteeringWheel()`

권한: 조회 `CAR_CLIMATE`, `CAR_DRIVE` · 제어 `CONTROL_CAR_CLIMATE`

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| HvacSteeringWheelHeat | 스티어링휠 열선 | `Int` | - | `getHvacSteeringWheelHeatState` | `setHvacSteeringWheelHeat` | `registerHvacSteeringWheelHeatState` | - |
| SteeringWheelAngle | 앞바퀴 조향각 | `Float` (도) | - | `getCurrentSteeringWheelAngle` | - | `registerSteeringWheelAngle` | - |

## Tire

타이어 공기압. 진입: `vehicle.getTire()`

권한: 조회 `CAR_SAFETY` · 제어 -

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| LowTirePressure | 저압 경고 기준 공기압 | `Float` (kPa) | Wheel | `getLowTirePressure` | - | - | 폐기 예정 메서드: `checkWheelAreaCapabilities` |
| TirePressure | 타이어 공기압 | `Float` (kPa) | Wheel | `getTirePressure` | - | `registerTirePressure` | 문서 비공개(@hide)<br>시스템 권한 필요: `PRIVATE_CAR_SAFETY` (signature\|privileged) |

## Toll

하이패스(ETCS) 음성 안내. 진입: `vehicle.getToll()`

권한: 조회 `CAR_TOLL` · 제어 `CONTROL_CAR_TOLL`

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| EtcsVoiceGuidanceVolume | 하이패스 음성 안내 음량 | `Int` | - | `getCurrentEtcsVoiceGuidanceVolume` | `setEtcsVoiceGuidanceVolume` | `registerEtcsVoiceGuidanceVolume` | - |

## TurnSignal

방향 지시등. 진입: `vehicle.getTurnSignal()`

권한: 조회 `CAR_LIGHTS` · 제어 -

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| TurnSignal | 방향 지시등 상태 | `TurnSignalValue` | - | `getCurrentTurnSignalState` | - | `registerTurnSignalState` | - |

## Window

창문·블라인드·스마트 글래스. 진입: `vehicle.getWindow()`

권한: 조회 `CAR_WINDOWS` · 제어 `CONTROL_CAR_WINDOWS`

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| WindowBlind | 윈도우 블라인드 위치 | `Int` | Window | `getCurrentWindowBlindPosition` | `moveWindowBlind` | `registerWindowBlindPosition` | - |
| SmartGlass | 스마트 글래스 켜짐 | `Boolean` | Window | `getCurrentSmartGlassState`<br>`getSmartGlassConfig` | `setSmartGlassState` | `registerSmartGlassState` | 시스템 권한 필요(signature\|privileged): `setSmartGlassState` |
| SmartGlassZone | 스마트 글래스 구역별 제어 | `List<SmartGlassZoneCommand>` | Window | - | `setSmartGlassZoneState` | - | - |
| SmartGlassLevel | 스마트 글래스 단계 | `Int` | Window | `getCurrentSmartGlassLevel` | `setSmartGlassLevel` | `registerSmartGlassLevel` | - |
| WindowLock | 창문 잠금 | `Boolean` | Window | `getCurrentWindowLockState` | `setWindowLock` | `registerWindowLockState` | - |
| WindowPosition | 창문 열림 위치 | `Int` | Window | `getCurrentWindowPosition` | `setWindowPosition` | `registerWindowPosition` | - |

## Wiper

와이퍼. 진입: `vehicle.getWiper()`

권한: 조회 `CAR_WINDOWS` · 제어 `CONTROL_CAR_WINDOWS`

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| Wiper | 와이퍼 작동 상태 | `WiperState` | Window | `getCurrentWiperState` | - | `registerWiperState` | - |
| WiperSwitch | 와이퍼 스위치 | `WiperSwitch` | Window | `getCurrentWiperSwitch` | `setWiperSwitch` | - | - |

## Gear

기어 선택 상태. 진입: `vehicle.getGear()`

권한: 조회 `CAR_DRIVE` · 제어 -

| 속성 | 설명 | 값 | 영역 | 조회 | 제어 | 이벤트 | 비고 |
|---|---|---|---|---|---|---|---|
| GearSelection | 기어 선택 상태 | `VehicleGear` | - | `getCurrentGearSelectionState` | - | `registerGearSelection` | 폐기 예정: Android 표준 VHAL 속성 `GEAR_SELECTION` 사용 권장<br>문서 비공개(@hide)<br>지원 확인 메서드 없음 |

## 부록 A. 영역 값

| 영역 클래스 | 값 enum | 값 |
|---|---|---|
| `SeatArea` | `SeatValue` | `ROW_1_LEFT`, `ROW_1_CENTER`, `ROW_1_RIGHT`, `ROW_2_LEFT`, `ROW_2_CENTER`, `ROW_2_RIGHT`, `ROW_3_LEFT`, `ROW_3_CENTER`, `ROW_3_RIGHT` |
| `DoorArea` | `DoorValue` | `ROW_1_LEFT`, `ROW_1_RIGHT`, `ROW_2_LEFT`, `ROW_2_RIGHT`, `ROW_3_LEFT`, `ROW_3_RIGHT`, `HOOD`, `REAR` |
| `WindowArea` | `WindowValue` | `FRONT_WINDSHIELD`, `REAR_WINDSHIELD`, `ROW_1_LEFT`, `ROW_1_RIGHT`, `ROW_2_LEFT`, `ROW_2_RIGHT`, `ROOF_TOP_1`, `ROOF_TOP_2` |
| `MirrorArea` | `MirrorValue` | `DRIVER_LEFT`, `DRIVER_RIGHT`, `DRIVER_CENTER` |
| `WheelArea` | `WheelValue` | `UNKNOWN`, `LEFT_FRONT`, `RIGHT_FRONT`, `LEFT_REAR`, `RIGHT_REAR` |
| `TurnSignalArea` | `TurnSignalValue` | `OFF`, `RIGHT`, `LEFT` |

## 부록 B. 값 enum

| enum | 값 |
|---|---|
| `AccessType` | `NONE`, `READ`, `WRITE`, `READ_WRITE` |
| `CruiseControl` | `OTHER`, `ENABLED`, `ACTIVATED`, `USER_OVERRIDE`, `SUSPENDED`, `FORCED_DEACTIVATION_WARNING` |
| `DistanceDisplayUnit` | `KILOMETER`, `MILE` |
| `DrivingModeType` | `NORMAL`, `ECO`, `SPORT` |
| `EvBatteryDisplayUnit` | `WATT_HOUR`, `AMPERE_HOURS`, `KILOWATT_HOUR` |
| `EvChargeState` | `UNKNOWN`, `CHARGING`, `FULLY_CHARGED`, `NOT_CHARGING`, `ERROR` |
| `EvConnectorType` | `UNKNOWN`, `IEC_TYPE_1_AC`, `IEC_TYPE_2_AC`, `IEC_TYPE_3_AC`, `IEC_TYPE_4_DC`, `IEC_TYPE_1_CCS_DC`, `IEC_TYPE_2_CCS_DC`, `TESLA_ROADSTER`, `TESLA_HPWC`, `TESLA_SUPERCHARGER`, `GBT_AC`, `GBT_DC`, `OTHER` |
| `EvRegenerativeBrakingState` | `UNKNOWN`, `DISABLED`, `PARTIALLY_ENABLED`, `FULLY_ENABLED` |
| `FanDirectionType` | `UNKNOWN`, `FACE`, `FLOOR`, `FACE_AND_FLOOR`, `DEFROST`, `FACE_AND_DEFROST`, `DEFROST_AND_FLOOR`, `FACE_AND_FLOOR_AND_DEFROST` |
| `FuelType` | `BIODIESEL`, `CNG`, `DIESEL_1`, `DIESEL_2`, `E85`, `ELECTRIC`, `HYDROGEN`, `LEADED`, `LNG`, `LPG`, `OTHER`, `UNKNOWN`, `UNLEADED` |
| `IgnitionState` | `UNDEFINED`, `LOCK`, `OFF`, `ACC`, `ON`, `START` |
| `LightState` | `OFF`, `ON`, `DAYTIME_RUNNING` |
| `LightSwitch` | `OFF`, `ON`, `DAYTIME_RUNNING`, `AUTOMATIC` |
| `MoveControl` | `BACKWARD`, `STOP`, `FORWARD` |
| `OilLevelState` | `LOW`, `HIGH`, `NORMAL`, `ERROR`, `CRITICALLY_LOW` |
| `PortLocationType` | `UNKNOWN`, `FRONT_LEFT`, `FRONT_RIGHT`, `REAR_RIGHT`, `REAR_LEFT`, `FRONT`, `REAR` |
| `SmartGlassZoneCommand` | `SKIP`, `OFF`, `ON` |
| `SpeedDisplayUnit` | `METER_PER_SEC`, `MILES_PER_HOUR`, `KILOMETERS_PER_HOUR` |
| `SteeringControl` | `OFF`, `READY`, `ACTIVE` |
| `TelltaleColor` | `WHITE`, `GREEN`, `BLUE`, `YELLOW`, `RED`, `GRAY` |
| `TelltaleState` | `OFF`, `ON`, `BLINK` |
| `TemperatureUnit` | `CELSIUS`, `FAHRENHEIT` |
| `TripStatisticType` | `CURRENT`, `INSTANTANEOUS`, `CUMULATIVE`, `SINCE_REFILL` |
| `TurnSignalValue` | `OFF`, `RIGHT`, `LEFT` |
| `VehicleGear` | `GEAR_UNKNOWN`, `GEAR_NEUTRAL`, `GEAR_REVERSE`, `GEAR_PARK`, `GEAR_DRIVE`, `GEAR_1`, `GEAR_2`, `GEAR_3`, `GEAR_4`, `GEAR_5`, `GEAR_6`, `GEAR_7`, `GEAR_8`, `GEAR_9` |
| `WiperState` | `OTHER`, `OFF`, `ON`, `SERVICE` |
| `WiperSwitch` | `OTHER`, `OFF`, `MIST`, `INTERMITTENT_LEVEL_1`, `INTERMITTENT_LEVEL_2`, `INTERMITTENT_LEVEL_3`, `INTERMITTENT_LEVEL_4`, `INTERMITTENT_LEVEL_5`, `CONTINUOUS_LEVEL_1`, `CONTINUOUS_LEVEL_2`, `CONTINUOUS_LEVEL_3`, `CONTINUOUS_LEVEL_4`, `CONTINUOUS_LEVEL_5`, `AUTO`, `SERVICE` |

<details>
<summary><code>TelltaleType</code> 계기판 경고등 종류 95개</summary>

`PARKING_BRAKE`, `ELECTRONIC_PARKING_BRAKE`, `SEAT_BELT`, `DOOR_OPEN`, `ABS`, `MDPS`, `TPMS`, `LOW_VOLT_BATTERY`, `ESC`, `ESC_OFF`, `AIRBAG`, `AFS`, `MASTER_WARNING`, `AUTO_HOLD`, `AUTO_HIGH_BEAM`, `HEADLAMP_LOW`, `HEADLAMP_HIGH`, `POSITION_LAMP`, `FRONT_FOG_LAMP`, `REAR_FOG_LAMP`, `ENGINE_OIL_PRESSURE`, `ENGINE_TEMPERATURE`, `EMERGENCY_CALL`, `EV_SYSTEM`, `POWER_DOWN`, `RBS`, `IMMOBILIZER`, `ENGINE`, `LED_HEADLAMP`, `PREHEAT`, `PARTICLE_FILTER`, `H2_SENSOR`, `H2_LEAK`, `PASSENGER_AIRBAG_OFF`, `PASSENGER_AIRBAG_ON`, `TURN_SIGNAL_LEFT`, `TURN_SIGNAL_RIGHT`, `ICY_ROAD`, `OVER_SPEED`, `WATER_SEPARATOR`, `SCR`, `LOW_EV_BATTERY`, `HEV_SYSTEM`, `FCEV_SYSTEM`, `EREV_SYSTEM`, `LPI`, `EV_CHARGING_CONNECTOR`, `AWD_LOCK`, `HEV_MODE`, `ISG`, `DBC`, `REV`, `XEV_READY`, `I_PEDAL`, `LOW_FUEL`, `LOW_FUEL_FCEV`, `EV_BATTERY_CONDITIONING`, `TRUNK_OPEN`, `CHILD_PRESENCE_DETECTION_OFF`, `DRIVE_MODE`, `DRIVE_MODE_SPORT`, `DRIVE_MODE_ECO`, `DRIVE_MODE_COMFORT`, `DRIVE_MODE_TERRAIN`, `FORCED_ALL_WHEEL_DRIVE`, `FOUR_WHEEL_DRIVE_MODE_2H`, `FOUR_WHEEL_DRIVE_MODE_4A`, `FOUR_WHEEL_DRIVE_MODE_4H`, `FOUR_WHEEL_DRIVE_MODE_4L`, `TOW_MODE`, `ESC_SPORT`, `CREEP_OFF`, `DRIFT_MODE`, `TRACK_MODE`, `UTILITY_MODE`, `GREEN_ZONE`, `PHEV_MODE`, `PHEV_MODE_HEV`, `PHEV_MODE_AUTO`, `PHEV_MODE_EV_PLUS`, `EREV_MODE`, `LAUNCH_MODE`, `ACCEL_RESPONSE_DASH`, `ACCEL_RESPONSE_ACCEL`, `N_BATTERY_DRAG`, `N_BATTERY_SPRINT`, `N_BATTERY_ENDURANCE`, `FCA`, `LKA`, `LFA`, `LCA_LEFT`, `LCA_RIGHT`, `ELK`, `ICC`, `DRIVER_ATTENTION`

</details>
