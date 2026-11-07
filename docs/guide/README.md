# Pleos 앱 개발 가이드

공모전 사이트·Pleos 개발 문서 원문, 설명회 자막, SDK 라이브러리(소스·javadoc)를 문서별로 정리했습니다(2026-11-07 수집). 원본은 [hmg-car-app-contest](https://github.com/hmg-car-app-team/hmg-car-app-contest)에 있습니다.

| 문서 | 내용 |
|---|---|
| [01-contest.md](01-contest.md) | 일정, 심사 기준, 시상, GitHub 제출 규칙 |
| [02-design-constraints.md](02-design-constraints.md) | 주행 중 제한, SDK 사용 조건, SDK로 얻을 수 없는 데이터 |
| [03-emulator-setup.md](03-emulator-setup.md) | 에뮬레이터·Virtual Console 세팅 |
| [04-vehicle-sdk.md](04-vehicle-sdk.md) | Vehicle SDK 사용법, 권한, 오류 코드 |
| [05-vehicle-data-dictionary.md](05-vehicle-data-dictionary.md) | 20개 도메인 속성별 조회·제어·이벤트 메서드, 값 타입, 권한 |
| [06-navihelper-sdk.md](06-navihelper-sdk.md) | 내비 API, 이벤트, ETA·교통·충전 계획 데이터 |
| [07-location-speech-sdk.md](07-location-speech-sdk.md) | FusedLocation, STT, TTS |

## 기획 전에 알아야 할 사실

1. 2차 PT는 에뮬레이터로 시연합니다. 실차 시연은 지원하지 않습니다(설명회 21:10).
2. 공모전에서는 SDK 권한 신청·승인 없이 매니페스트 선언만으로 SDK를 쓸 수 있고, 아직 공개되지 않은 SDK도 제공될 예정입니다(설명회 26:01, 32:29).
3. 주의분산 최적화(DO)를 선언하지 않은 앱은 주행 중 쓸 수 없습니다. 선언해도 주행 중 도어·시트·차량 설정 화면과 동영상은 막힙니다.
4. SDK는 내 차 데이터만 제공합니다. 주변 차량, 카메라, 운전자 모니터링, 운전자 식별 데이터는 없습니다.
5. 차량 속도는 Vehicle SDK·FusedLocation에 없고 Virtual Console에도 속도 카드가 없습니다.
6. 계기판 경고등과 타이어 공기압 값은 시스템 앱 전용 권한(`PRIVATE_CAR_SAFETY`)이 필요합니다.
7. NaviHelper는 ETA, 남은 거리·시간, 통행료, 회전 안내, 정체 구간, 사고 정보, 과속 카메라, 제한속도, EV 충전 계획까지 이벤트로 줍니다.
