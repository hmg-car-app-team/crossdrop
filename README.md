# org-basic

현대자동차그룹 차량용 앱 개발 공모전의 기반 데이터 리포입니다. 팀 슬랙에서 공유한 링크, Pleos SDK 데이터 정리, 아이디어, 공식 접수양식을 모았습니다.

**각자 이 리포를 fork해서, 아래 자료를 바탕으로 자기 프로젝트 파일을 세팅하세요.**

- 접수 마감: **2026-11-15(일) 23:59**
- 공모전 공식 사이트: https://appdevchallenge.auto-hmg.io/

## 들어 있는 자료

| 경로 | 내용 |
|---|---|
| [docs/links.md](docs/links.md) | 설명회 영상, Pleos 개발 문서, 에뮬레이터 가이드, 샘플 앱 GitHub 등 링크 모음 |
| [docs/emulator-setup.md](docs/emulator-setup.md) | Pleos Connect Emulator 설치, AVD 생성, Virtual Console 사용 순서 요약 |
| [docs/pleos-sdk-data.md](docs/pleos-sdk-data.md) | Vehicle SDK, NaviHelper, 위치·음성 SDK, Vehicle Data API로 쓸 수 있는 데이터와 미확인 사항 |
| [docs/ideas.md](docs/ideas.md) | 팀 앱 아이디어 백로그 |
| [docs/third-party-ideas.md](docs/third-party-ideas.md) | 서드파티·특허 아이디어 (기반 데이터로 보관만) |
| [submission/](submission/) | 공식 접수양식 docx와 제출 정보·기획서 항목 요약 |

설명회 자막, 공모전 사이트·Pleos 문서 원문, SDK 라이브러리(aar) 같은 원본 자료는 [hmg-car-app-team/hmg-car-app-contest](https://github.com/hmg-car-app-team/hmg-car-app-contest)에 있습니다.

## Fork해서 프로젝트 세팅하기

1. 이 리포를 내 계정으로 fork하고 클론합니다. 웹에서는 오른쪽 위 **Fork** 버튼을 눌러도 됩니다.
   ```bash
   gh repo fork hmg-car-app-team/org-basic --clone --fork-name <프로젝트명>
   cd <프로젝트명>
   ```
2. [docs/ideas.md](docs/ideas.md)에서 만들 아이디어를 고르고, [docs/pleos-sdk-data.md](docs/pleos-sdk-data.md)에서 쓸 SDK와 데이터를 정합니다.
3. [docs/emulator-setup.md](docs/emulator-setup.md)를 따라 Pleos Connect Emulator와 Virtual Console을 준비합니다.
4. [Pleos-Playground 샘플 앱](https://github.com/Pleos-Playground)을 참고해 `app/`에 Android 프로젝트를 만듭니다.
5. [submission/README.md](submission/README.md)의 기획서 8개 항목대로 `planning/`에 기획 초안을 씁니다.
6. 이 README를 내 앱 소개로 바꾸고 커밋·푸시합니다.

fork한 리포 구조 예시:

```
<프로젝트명>/
├── README.md        # 내 앱 소개로 교체
├── app/             # Pleos Connect Android 프로젝트
├── planning/        # 기획 초안 (접수양식 02장 항목 기준)
├── docs/            # 이 리포의 기반 자료 (그대로 유지)
└── submission/      # 공식 접수양식
```

이 리포에 자료가 추가되면 fork에서 `git pull upstream main`으로 받아옵니다. (`gh repo fork --clone`으로 받았다면 `upstream` 리모트가 이미 설정되어 있습니다.)

## 주의

- 성명·연락처·서명을 채운 접수양식은 커밋하지 마세요. GitHub 저장소를 심사 참고자료로 내면 그대로 노출됩니다.
- 비공개 리포의 fork는 공개로 바꿀 수 없습니다. 심사용 공개 저장소가 필요하면 새 리포를 만들어 코드를 옮기세요.
- Fork 버튼이 비활성화되어 있으면 org 오너에게 비공개 리포 fork 허용을 요청하세요.
