# 크로스드롭 CrossDrop

아이폰, 안드로이드 구분 없이 사진, 음악, 문서, 목적지를 차량 화면으로 한 번에 보낼 수 있는 기기 간 공유 앱입니다.

> 현대자동차그룹 차량용 앱 개발 공모전 출품작. Pleos Connect(Android Automotive) 환경용 Android 앱입니다.

## 핵심 기능

- **휴대폰 연결(QR)**: 정차 중 `휴대폰 연결` 탭 → 동의 팝업 → 릴레이 서버에서 1회용 코드(10분)를 받아 QR 표시(코드를 직접 입력하는 대체 경로는 없다; 새 QR 은 연결된 뒤에도 만들 수 있다). 휴대폰 기본 카메라로 스캔하면 업로드 웹 페이지(`/u#코드`)가 열리고, 파일(사진 JPG/PNG/WebP, PDF, 음악 MP3/M4A/AAC/OGG)이나 지도 링크를 보낼 수 있다. 차량은 5초마다 새 항목을 확인한다(앱이 켜져 있을 때만).
- **받은 항목(정차)**: 목록(최신순)에서 사진·PDF 는 열람(PDF 는 쪽 이동), 음악은 재생/일시정지/정지, 목적지는 안내 확인. 항목마다 `N시간 후 삭제` 표시.
- **새 항목 알림(주행 중 포함)**: 상단 짧은 안내 패널 + TTS 한 줄("사진 1개가 도착했어요. 정차 후 확인하세요."). 사진·PDF 는 주행 중 열 수 없다.
- **목적지 링크 → 길 안내**: 카카오맵(`/link/map|to/이름,위도,경도`), 네이버지도(`?lat=&lng=`), Apple 지도(`?ll=`·`coordinate=`·`daddr=`), 구글지도(`@위도,경도`·`!3d!4d`·`q=|query=|ll=|destination=`) 링크의 **문자열만** 해석한다. 단축 링크(kko.to·naver.me·maps.app.goo.gl·goo.gl)와 좌표가 없는 링크는 "열 수 없어요" 로 안내(따라가서 확인하지 않음). 목적지가 도착하면 TTS 가 "강남역을 목적지로 안내할까요?" 하고 질문 **직후 한 번만** 음성 인식("응/네" 수락, "아니" 거절). 정차 중에는 터치("안내 시작")로도 선택한다. 수락해야만 NaviHelper `requestRoute` 를 호출한다. 받자마자 자동 이동하지 않는다.
- **음악**: 오디오 포커스 + MediaSession(하드웨어 미디어 키). 주행 중에도 재생은 이어지지만 조작 UI 는 정차 중에만 나온다. 영상은 재생하지 않는다.
- **24시간 자동 삭제**: 받은 항목·파일은 이 기기에서 24시간 뒤(앱 실행 중 30초마다 점검, 시작할 때도) 삭제, 서버는 항목을 받는 즉시 지우고 늦어도 24시간 뒤 삭제. `개인정보·삭제` 탭: 받은 항목 모두 삭제(+서버 세션 삭제 요청), 서버 연결 끊기·동의 철회.
- **시연 모드**: 서버 없이 가상 QR·가상 수신·목적지 음성 수락·사진/PDF 열람·24시간 삭제를 보여 준다. 화면에 `시연` 배지. 시연 데이터는 서버로 가지 않고 저장하지 않는다(앱 시작 시 정리).
- **화면**: Pleos 컴포넌트·토큰만 사용. 주행 중에는 조작 없는 한 화면(받은 개수·음악 상태·동기화 상태). 정차 시 3탭(받은 항목/휴대폰 연결/개인정보·삭제). Landscape/Portrait 모두 단일 열.

## 실행 방법

필요한 것: JDK 17, Android SDK(compileSdk 35). 디자인시스템·`:core`·SDK 의존성이 이 저장소에 포함되어 있어 `app/` 만으로 단독 빌드됩니다. (Pleos SDK 는 공개 Maven 저장소 `nexus-playground.pleos.ai` 에서 받으므로 빌드 시 인터넷이 필요합니다.)

```bash
export JAVA_HOME=<JDK 17 설치 경로>
export ANDROID_HOME=<Android SDK 경로>      # 또는 app/local.properties 에 sdk.dir=<Android SDK 경로>
cd app
./gradlew :cross-drop:assembleDebug              # 디버그 APK: cross-drop/build/outputs/apk/debug/
./gradlew :cross-drop:assembleRelease            # 릴리스 APK: cross-drop/build/outputs/apk/release/
```

`local.properties` 는 개인 환경 파일이라 저장소에 포함하지 않습니다(`.gitignore` 로 제외). 환경변수 `ANDROID_HOME` 을 쓰면 만들 필요가 없습니다.

## 테스트 실행

```bash
cd app
./gradlew :core:test :cross-drop:testDebugUnitTest
```

JVM 단위 테스트이며 에뮬레이터가 필요 없습니다. 결과 리포트는 `*/build/reports/tests/` 에 생성됩니다.

## 에뮬레이터 설정

Pleos Connect 에뮬레이터(Android Automotive 기반)에서 동작을 확인합니다. 설치 방법과 Virtual Console 사용법은 [에뮬레이터 가이드](docs/guide/03-emulator-setup.md)를 따릅니다. 첫 실행 시 Pleos 차량 정보 권한 대화상자가 뜨면 허용해야 합니다.

## APK

릴리스 APK 는 `apk/` 폴더에 있습니다(`apk/<팀명>_<앱이름>_release.apk`).

## 구조

```
app/
├── cross-drop/           # 앱 모듈(:cross-drop)
├── core/               # 공통 라이브러리(:core) - Pleos SDK 연결·차량 상태 어댑터
├── designsystem/       # Pleos 디자인시스템 소스 복사본(:designsystem), 라이선스 포함
├── licenses/           # 서드파티 라이선스(Asta Sans OFL 등)
└── gradle/             # 버전 카탈로그, Gradle wrapper
planning/               # 기획서 (접수양식 02장 8개 항목)
apk/                    # 릴리스 APK
docs/, submission/      # 공모전 기반 자료(원본 유지)
```

## 알려진 한계

- **검증 후속(2026-11-11)**: ① 동의 철회(서버 연결 끊기)는 이미 받은 항목을 지우지 않는다(팝업이 '받은 항목은 그대로예요' 로 고지; 삭제는 '받은 항목 모두 삭제'). ② 상태 파일에 연결 코드·토큰이 평문 JSON 으로 저장된다(앱 전용 저장소, allowBackup=false). ③ 서버가 꺼진 동안 연결 배지는 '연결 상태를 확인하지 못했어요' 로 바뀌지만, 서버 복구 직후 세션이 사라졌다면 다음 폴링(최대 몇 초)이 401 을 받을 때까지는 '휴대폰 연결됨' 이 남을 수 있다. ④ 미사용 QR 이 만료돼 세션이 서버에서 사라지면 앱이 연결 정보를 지우고 '연결 안 됨' 으로 돌아가며, 이때 '만료됨' 안내는 토스트('QR 이 만료됐어요')뿐이다(만료 상태가 화면에 남아 있는 동안에는 한 줄 안내를 표시). ⑤ 내비 경로 요청 뒤 내비의 성공 이벤트를 받지 못하면 앱은 실패로 단정하지 않고 '안내 시작을 확인하지 못했어요' 로 안내한다(에뮬레이터 내비는 이벤트를 주지 않음).
- **에뮬레이터/실차에서 실행해 보지 않았다**: 이 제작 단계에서는 에뮬레이터 사용이 금지되어 화면 렌더링(QR·사진·PDF 뷰어, Landscape/Portrait), TTS/STT, 내비 호출, 오디오 재생, 프로세스 종료 복원은 **컴파일·단위 테스트 수준으로만** 확인했다. QR 은 macOS CoreImage 디코더로 행렬을 따로 디코딩해 확인했고, 앱의 서버 클라이언트는 실제 로컬 서버와의 통합 테스트로 확인했다.
- **내비 경로 생성은 에뮬레이터에서 불가**(FuelWise 확인): `requestRoute` 는 이벤트(오류)까지만 확인된다. **STT 결과도 에뮬레이터에서는 오지 않아** 음성 수락은 시간 초과가 되고 정차 중 터치 또는 `adb` 주입(`--es heard 응`)으로 확인한다.
- **단축 링크 불가**: 카카오·네이버·구글 앱의 공유 링크는 대부분 `kko.to`·`naver.me`·`maps.app.goo.gl` 단축 주소라 따라가지 않는 정책상 해석할 수 없다. 전체 주소(좌표 포함) 링크만 목적지가 된다. 네이버지도는 `lat/lng` 쿼리 형식만 지원(일반 장소 공유 링크는 좌표가 없어 불가).
- **서버는 로컬 테스트용**(TLS·인증 없음). 실서비스에는 TLS, 앱 무결성 증명, 악성 파일 스캔, 신고·차단이 필요하다. 서버 운영자는 보관 시간 동안 파일을 볼 수 있다(종단 간 암호화 없음).
- PDF 검사는 알려진 활성 콘텐츠 키워드(`/JavaScript`·`/JS`·`/OpenAction`·`/Launch` 등)를 거르는 **최선 노력** 검사다. 스트림(압축 여부 무관, 평문 `/Type/ObjStm` 포함) 안쪽은 검사하지 않으며 우회될 수 있다. 예: 문자열·주석 안에 `>>\nstream\n` 을 넣으면 그 뒤 구간이 스트림으로 취급돼 검사되지 않는다(알려진 우회, 토크나이저 미구현). 실제 방어는 ① 허용 목록의 파일 형식만 디코더에 도달한다는 점 ② Android `PdfRenderer`(PDFium 기반)가 PDF JavaScript 를 실행하지 않는다는 점에 의존한다(근거는 문서 기준, 실기 미확인).
- 휴대폰이 보낸 파일을 차량이 열 때의 위험: 형식 허용 목록·매직 바이트·크기 검사와 PDF 활성 콘텐츠 키워드 최선 노력 검사를 하지만 이미지 디코더·`PdfRenderer` 자체의 취약점은 막지 못한다. 모르는 사람이 보낸 파일을 열지 말라는 안내를 개인정보 탭에 둔다.
- 폴링은 앱이 켜져 있을 때만(백그라운드 서비스 없음). 앱이 꺼지면 새 항목은 다음 실행 때 확인한다. 24시간 삭제도 앱이 실행될 때 점검한다(시작 시 정리 + 실행 중 30초 주기). 기기 시계를 되감으면 보관 기간이 늘어나지만 1시간을 넘는 미래 시각은 삭제한다.
- 음악은 MediaBrowserService 가 아니라 차량 미디어 템플릿/앨범 화면에 노출되지 않고, 포그라운드 서비스가 없어 앱이 종료되면 재생도 끝난다. 시연 음악은 WAV 사인파를 M4A 이름으로 저장한 것(검증 경로 우회, 시연 전용).
- 이미 서버 삭제 요청이 실패한 항목은 재시작 뒤 다시 받을 수 있다(메모리 기록뿐). 24시간 판정은 벽시계 단일 기준이라 **시계가 동기화되기 전 콜드 부팅에서는 항목이 한꺼번에 삭제될 수 있다**(프라이버시 우선). **아이폰 HEIC 는 허용 목록에 없다**(실기 확인 전).
- 시연 중에 수락한 가상 목적지도 실제 `requestRoute` 로 전달된다(오퍼 패널에 `시연 목적지` 표시).
- **iOS Share Extension·컴패니언 앱은 범위 밖**(웹 업로드로 대체). 안드로이드 공유 시트 연동도 하지 않았다(외부 앱이 직접 입력을 넣는 경로를 만들지 않기 위해). **스마트폰 연결 도우미(블루투스·프로젝션 진단)는 SDK 가 없어 제외**하고 정적 안내 패널로 대체했다.
- 디자인시스템에 QR·이미지/PDF 뷰어·상단 배너·빈 상태 컴포넌트가 없어 `Canvas`·`Image`·`PleosPanel`/글줄로 최소 대체했다(`docs/apps/cross-drop.md` 기록).

## 라이선스·고지

`app/designsystem/` 은 Pleos 디자인시스템(0.2.0)의 소스 복사본이며, 번들 폰트 Asta Sans 는 SIL OFL 1.1 입니다. 전문은 `app/licenses/AstaSans-OFL.txt`, 요약은 `app/NOTICE` 에 있습니다.

## 기반 자료

이 저장소는 공모전 기반 자료 리포(`org-basic`)를 fork 해 만들었습니다. 아래 자료는 그대로 유지합니다.

| 경로 | 내용 |
|---|---|
| [docs/links.md](docs/links.md) | 설명회 영상, Pleos 개발 문서, 에뮬레이터 가이드, 샘플 앱 GitHub 등 링크 모음 |
| [docs/guide/](docs/guide/README.md) | 문서별 개발 가이드: 공모전 규정, 설계 제약, 에뮬레이터, Vehicle SDK 데이터 사전, NaviHelper, 위치·음성 SDK |
| [docs/pleos-sdk-data.md](docs/pleos-sdk-data.md) | Vehicle SDK, NaviHelper, 위치·음성 SDK, Vehicle Data API로 쓸 수 있는 데이터와 미확인 사항 |
| [docs/ideas.md](docs/ideas.md) | 팀 앱 아이디어 백로그 |
| [docs/third-party-ideas.md](docs/third-party-ideas.md) | 서드파티·특허 아이디어 (기반 데이터로 보관만) |
| [submission/](submission/) | 공식 접수양식 docx와 제출 정보·기획서 항목 요약 |

설명회 자막, 공모전 사이트·Pleos 문서 원문, SDK 라이브러리(aar) 같은 원본 자료는 [hmg-car-app-team/hmg-car-app-contest](https://github.com/hmg-car-app-team/hmg-car-app-contest)에 있습니다.
