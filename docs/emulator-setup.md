# Pleos Connect Emulator 세팅

공식 가이드 3개를 순서대로 요약했습니다 (2026-11-07 기준). 화면 캡처가 필요하면 원문을 보세요.

- [Pleos Connect Emulator 설치 및 실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Pleos Connect Emulator 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)
- [Pleos Connect Virtual Console 셋업 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)

## 개발 환경 요구사항

| 구분 | 버전·사양 |
|---|---|
| Minimum Android SDK | API 28 이상 (차량 사이버 보안 요건인 사이닝 키 로테이션·리니지 지원 때문) |
| Gradle | 8.0 이상 |
| 개발 언어 | Java, Kotlin |
| Pleos Connect Emulator | 2.1.0 |
| Pleos Connect Virtual Console | Emulator 2.1.0 이상에서 지원 |
| Android Studio | 최신 버전, Android Emulator 시스템 요구사항 충족 |
| 그래픽 카드 | 필수. 최소 RTX 3060 Laptop 6GB 또는 RX 6600M 8GB·RX 6700S 8GB 이상, 권장 RTX 4060/5060 Laptop 8GB 또는 RX 7700S·RX 7600M XT 8GB급 이상 |

## 1. Emulator 이미지 설치

이전 버전의 Pleos Connect Emulator가 있으면 먼저 삭제합니다.

1. Android Studio에서 **Tools > SDK Manager**를 엽니다.
2. **SDK Update Sites** 탭에서 **+**를 누르고 아래 사이트를 추가한 뒤 OK를 누릅니다.
   - Name: `Pleos Connect System Image`
   - URL (Mac): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - URL (Windows/Linux): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
3. **SDK Platforms** 탭에서 **Show Package Details**를 켜고, Android API Level 34 아래의 **Pleos Connect system Image**를 체크한 뒤 OK를 누릅니다.
4. 설치가 끝나면 Finish를 누르고 Android Studio를 재시작합니다.

## 2. AVD 만들고 실행하기

1. **Tools > Device Manager**에서 **Create device**를 누릅니다. (예전 버전: AVD Manager > Create Virtual Device)
2. Form Factor에서 **Automotive**, 기기에서 **Pleos Connect v2.1.0**을 고르고 Next를 누릅니다.
3. **Advanced Settings** 탭에서 Startup > Default boot를 **Cold**로, Emulated Performance > RAM Size를 **4 GB**로 설정합니다. (예전 버전: Show Advanced Settings에서 Graphics를 Hardware, Boot option을 Cold boot로)
4. **Device** 탭으로 돌아가 Select system image > API에서 **API 34**를 고르고, **Pleos Connect v2.1.0 APIs**를 선택한 뒤 Finish를 누릅니다.
5. Device Manager에서 만든 AVD를 실행합니다.

**주의:** Boot는 반드시 Cold여야 합니다. Quick boot로 두면 VHAL(차량 하드웨어 추상화 계층)을 쓸 수 없어 차량 데이터가 동작하지 않습니다.

## 3. 실행 후 설정

| 설정 | 방법 |
|---|---|
| 언어 | 기본은 한국어. 영어는 차량 설정 > 일반 설정 > 언어/Language에서 English |
| 마이크 (STT 테스트용) | 에뮬레이터 패널의 Extended Controls > Microphone에서 오디오 입력 수신 켜기 |
| Bluetooth 동글 | 현재 버전은 지원하지 않음 |

## 4. Virtual Console로 차량 상태 시뮬레이션

Virtual Console은 실제 차량처럼 속도, 도어, 조명 같은 상태와 주행 이벤트를 바꿔 가며 앱 반응을 확인하는 브라우저 도구입니다. Emulator 2.1.0 이상이 필요합니다.

1. 에뮬레이터를 실행한 상태에서 터미널로 포트를 포워딩합니다.
   ```bash
   adb forward tcp:8088 tcp:8088
   ```
2. PC 브라우저에서 http://127.0.0.1:8088/ 을 엽니다.
3. 왼쪽 Emulation Controls의 Domain 목록에서 영역을 고르고 기능 카드 값을 바꿉니다.

| Domain | 시뮬레이션할 수 있는 것 |
|---|---|
| Vehicle | 차량 알림 표시·숨김 |
| Motion | 기어, 브레이크, 스티어링 휠 각도, 주행 거리, 스티어링 휠 열선 단계 |
| ADAS | 크루즈 제어 준비·활성 상태, 목표 속도 |
| Convenience | 도어·창문·트렁크, 시트, 조명, 와이퍼, ETCS 시나리오, 글로브 박스 |
| Safety | 주차 브레이크, 안전벨트, ABS 등 경고등의 꺼짐·켜짐·점멸 |
| Energy | 주행 가능 거리, 배터리 용량·충전 상태, 충전 전류, 목표 충전량, 충전 포트 |
| Climate | 공조 전원, 온도, 풍량, 송풍 방향, 내기순환, 제상, 좌석 열선·통풍, 공기 청정 |

Virtual Console이 동작하지 않으면 먼저 Emulator 버전이 2.1.0 이상인지 확인하세요.
