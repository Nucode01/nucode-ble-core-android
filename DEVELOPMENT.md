# 개발 안내

## 모듈

| 모듈 | 내용 |
| --- | --- |
| `core` | 라이브러리 (namespace `kr.co.nucode.ble`). 공개 API 기준은 `core/api/ble-core.api` |
| `sample` | 실기기 검증용 앱. 가이드 예시 코드(`GuideSnippets.kt`)도 여기서 컴파일됩니다 |
| `contract-runner` | 계약 시나리오 러너 소스. `core` 단위 시험(sim)과 `sample` 계측 시험(device)이 함께 씁니다 |

구조 규칙은 `ArchitectureRulesTest` 가 단위 시험으로 강제합니다.

## 시험과 빌드

```bash
./gradlew test :sample:assembleRelease -PbleContractDir=<계약 폴더>
```

- `test` 는 debug · release 단위 시험을 모두 돌립니다. 공개 API 덤프 대조, 가이드 예시 동기화, 계약 시나리오 sim 이 들어 있습니다.
- `:sample:assembleRelease` 는 R8 을 켠 빌드입니다.

## 계약 폴더

계약 시험은 계약 폴더가 필요합니다. 시나리오 파일과 `consumers/kotlin` 소스가 들어 있는 폴더입니다.

- `-PbleContractDir=<경로>` 로 줍니다. 이 값을 주면 폴더가 반드시 있어야 합니다.
- 값을 주지 않으면 이 저장소 옆의 `../contract` 를 씁니다.

## 실기기 시험

전용 peripheral 펌웨어를 올린 보드와 ADB 로 연결한 Android 기기가 필요합니다. 하드웨어 시험은 명시적으로 켤 때만 돕니다.

```bash
./gradlew :sample:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true
```

주변에 같은 보드가 여러 대면 대상 주소를 고정합니다.

```bash
./gradlew :sample:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.hardwareBle=true \
  -Pandroid.testInstrumentationRunnerArguments.blePeripheralId=<Bluetooth 주소>
```

보드가 없으면 해당 시험은 건너뜁니다. 수동 점검 항목은 [sample/DEVICE_VERIFICATION.md](sample/DEVICE_VERIFICATION.md)에 있습니다.

## CI

`.github/workflows/ci.yml` 은 태그 push 와 손 실행(`workflow_dispatch`)에서만 돕니다.

- 태그 실행은 같은 이름 태그의 계약을 받습니다. 그 태그가 계약 저장소에 없으면 checkout 이 실패합니다.
- 손 실행은 계약 저장소의 `main` 을 받습니다.
