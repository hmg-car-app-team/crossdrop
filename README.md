# 크로스드롭 CrossDrop

동승자가 찾은 목적지를 운전자가 다시 입력해야 합니다. 이 앱은 정차 중 운전자가 동의하면 뜨는 QR의 주소를 브라우저로 열어 목적지와 사진을 보내게 하고, 운전자가 수락한 목적지만 내비에 요청합니다.

## 주요 기능
- **QR로 차량 연결**: 정차 중 동의하면 1회용 코드(10분)가 담긴 QR이 뜨고, 브라우저에서 업로드 페이지를 열어 보냅니다.
- **파일·링크 보내기**: 사진, PDF, 음악, 지도 링크. 영상은 받지 않습니다.
- **목적지 안내**: 지도 링크는 문자열만 해석하고, 운전자가 음성이나 터치로 수락해야만 내비에 경로를 요청합니다.
- **주행 중 안전**: 사진·PDF·QR은 정차 중에만 보이고 주행하면 닫힙니다.
- **삭제**: 서버 사본은 24시간 뒤 삭제됩니다. `모두 삭제`는 기기의 받은 항목을, `연결 끊기`는 서버의 세션·파일 삭제 요청과 동의 철회를 처리합니다.

## 실행 방법
1. APK 설치: `adb install -r apk/hmg-car-app-team_CrossDrop_release.apk`
2. 서버 실행: `cd server && python3 -m crossdrop.server --port 8780`
3. 연결: `adb reverse tcp:8780 tcp:8780` 후 앱에서 `휴대폰 연결`
4. QR의 주소(`http://127.0.0.1:8780/u#코드`)를 호스트 PC 브라우저로 열어 보냅니다(절차는 `server/crossdrop/README.md`).

## 테스트 환경
Pleos Connect v2.1.0 에뮬레이터(AVD `pleos21`), Android 14, 2560x1440 가로.

## APK 위치
`apk/hmg-car-app-team_CrossDrop_release.apk`

## 데이터와 개인정보
- 파일과 링크는 릴레이 서버를 거쳐 최대 24시간 보관되며, 그동안 서버 운영자가 볼 수 있고 종단 간 암호화는 없습니다. 차량이 받은 뒤 서버 사본을 지우도록 설계했습니다.
- 연결 동의 팝업이 서버 경유와 전송 대상 주소를 알리며, 연결을 끊으면 멈춥니다.
- 이름·계정·기기 식별자·위치는 보내지 않습니다.

## 소스 빌드·테스트
`app/` 폴더만으로 단독 빌드됩니다(JDK 17, Android SDK 필요, Pleos SDK 는 공개 Maven 에서 받으므로 인터넷 필요).

```bash
cd app
./gradlew :cross-drop:assembleDebug :cross-drop:assembleRelease
./gradlew :core:test :cross-drop:testDebugUnitTest
```

서드파티 고지는 `app/NOTICE`, 폰트 라이선스는 `app/licenses/` 에 있습니다.
