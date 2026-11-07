# NaviHelper SDK 가이드

기준: NaviHelper v3.0.5 (`implementation("ai.pleos.playground:NaviHelper:3.0.5")`). 차량 기본 내비게이션에 목적지·경유지를 넘기고 경로·교통 정보를 받습니다. **요청·조회 API는 값을 바로 돌려주지 않고, 결과는 모두 `NaviHelperEventListener` 콜백으로 옵니다.**

```kotlin
val naviHelper = NaviHelper(applicationContext)
naviHelper.initialize(onConnected = { naviHelper.addListener(listener) }, onFailed = { t -> })
// 종료: removeListener(listener) → release()
```

## API와 결과 이벤트

| API | 결과 이벤트 | 권한 |
|---|---|---|
| `requestRoute(RouteInfo)` 목적지 설정 | `onRouteStarted`, `onRoutingCompleted`, `onDestinationChanged` | `NAVI_ROUTE_SEARCH` |
| `requestRouteWithWaypoints(...)` 목적지+경유지 | 같음 | `NAVI_ROUTE_SEARCH` |
| `addWaypoint` / `removeWaypoint` (최대 2개, ALL로 전체 삭제) | `onWaypointChanged` | `NAVI_ROUTE_SEARCH` |
| `changeRouteOption`, `requestReRoute`, `cancelRoute` | `onRouteOptionChanged`, `onReRouteEnded`, `onRouteCancelled` | `NAVI_ROUTE_SEARCH` |
| `getDestinationInfo`, `getWaypointInfo`, `getRouteStateInfo`, `getCurrentLocationInfo`, `getTBTInfo`, `getBookmarkInfo`, `getRecentDestinationInfo`, `getChargerOperatorInfo` | 같은 이름의 `on…Info` | `NAVI_ROUTE` |
| `requestTrip(TripRequest)` 안내 없이 거리·ETA·도착 SOC 계산(추정) | `onTripInfo` | `NAVI_ROUTE` |
| `requestNavigationScreen(...)` 내비 화면 열기 (장소 검색, 주변 충전소, 경로 전체 보기 등 13종) | - | `NAVI_CUSTOM_MAP` |

요청 없이 오는 이벤트: `onDestinationArrived`, `onWayPointArrived`, `onCurrentRoadSpeedLimit(Int)`, `onCameraAlert`, `onCautionPointInfo`, `onDrivingInfo`, `onNavigationStatus`, `onError(NaviErrorCode)`.

RouteOption: `RECOMMENDED`, `SHORT_DISTANCE`, `SHORT_TIME`, `FREE`, `HIGHWAY`, `COMFORTABLE`, `SCHOOL_ZONE`. 오류: `CAUSE_LOW_BATTERY`, `CAUSE_SEARCH_ROUTE_OVER_DISTANCE`, `CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE`, `CAUSE_SEARCH_ROUTE`, `CAUSE_SEARCH_TRIP`.

## 받을 수 있는 데이터

| 클래스 | 주요 필드 |
|---|---|
| `RouteStartInfo` | 예상 소요 시간(초), 거리(m), 통행료, 도착 시 잔여 배터리·연료 |
| `DestinationInfo`, `WaypointInfo` | 좌표, 이름, 주소, 남은 거리(m)·시간(초), 통행료(원), POI ID |
| `TripInfo` | 거리(m), ETA(초), 도착 시 예상 SOC(%), 예상 소비 전력(Wh), 경로 구간별 정체·경유 지점 |
| `RouteStateInfo` | 안내 모드, 안내 중 여부, 경로 옵션, 도착·경유지별 잔여 배터리, 교통 상황(`TrafficStatus`) |
| `TrafficStatus` | 정체 요약(총 거리·시간·평균 속도), 정체 구간(도로명, 교차로명, 길이, 평균 속도), 사고·공사·재난 이벤트 |
| `TBTInfo` | 회전 종류(`TurnType` 43종), 남은 거리(m), 안내 문구 |
| `DrivingInfo` / `DrivingState` | 목적지·경유지별 소요 시간, 거리, 잔여 배터리, 충전소 여부·추천 여부, 최대 충전 출력(kW), 충전기 수, 충전 계획(충전 시간, 시작·목표 SOC) |
| `CameraAlert` | 카메라 종류(과속·구간·신호 등 10종), 제한속도, 남은 거리, 구간 평균 속도, 과속 여부 |
| `CautionPoint` | 주의 구간 종류(과속방지턱, 스쿨존, 낙석 등 23종), 남은 거리 |
| `CurrentLocationInfo` | 좌표, 진행 방향, 주소 |
| `BookmarkInfo`, `RecentDestinationInfo` | 즐겨찾기(HOME, OFFICE, EV_CHARGING_STATION, DEFAULT), 최근 목적지 |
