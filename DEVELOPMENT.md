# 개발 안내

## 저장소

| 저장소 | 주소 | 쓰임 |
| --- | --- | --- |
| 메인 (Azure) | 사내 Azure DevOps. 주소는 사내 문서에 있습니다 | 개발. 원격 이름 `origin` |
| 미러 (GitHub) | `https://github.com/Nucode01/nucode-ble-core-android.git` | 설치 · 패키지 · CI. 원격 이름 `github` |

개발은 Azure 메인에서 합니다. GitHub 는 미러입니다. 라이브러리 설치, GitHub Packages 배포, CI 는 모두 GitHub 미러에서 합니다.

사람은 Azure 메인(`origin`)에만 push 합니다. `github` 원격으로 직접 push 하지 않습니다.

- Azure Pipelines(`azure-pipelines.yml`)가 main 과 태그 push 마다 GitHub 미러로 `main` 과 모든 태그를 옮깁니다.
- 덮어쓰기와 삭제는 하지 않습니다. 미러 이력이 Azure 와 갈라지면 파이프라인이 실패합니다.
- 미러의 태그 push 가 GitHub Actions 의 ci.yml 과 publish.yml 을 시작합니다.
- GitHub 는 push 된 태그의 커밋에 있는 workflow 를 씁니다. 옛 태그(0.2.0~0.3.0)에는 publish.yml 이 없고 ci.yml 도 태그로 돌지 않습니다. 그래서 첫 동기화에서 옛 태그가 올라가도 배포는 돌지 않습니다. 0.3.0 은 Publish 손 실행으로 배포합니다.

### 파이프라인 설정 (처음 한 번)

1. Azure DevOps 에서 이 저장소의 `azure-pipelines.yml` 로 파이프라인을 만듭니다.
2. GitHub 에서 `Nucode01/nucode-ble-core-android` 에 쓸 수 있는 토큰을 만듭니다. 권한은 contents 쓰기와 workflows 쓰기입니다. workflow 파일을 바꾸는 커밋을 push 하려면 workflows 쓰기가 필요합니다.
3. 파이프라인 변수에 `GITHUB_MIRROR_TOKEN` 을 비밀 변수로 더하고 그 토큰을 넣습니다.
4. 조직에서 Microsoft-hosted 병렬 작업을 쓸 수 있는지 확인합니다. 쓸 수 없으면 파이프라인이 대기열에서 시작하지 않습니다.

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
- 값을 주지 않으면 이 저장소 옆의 `../contract` 를 씁니다. 그 폴더가 없으면 계약 시험은 건너뜁니다.

계약 시험은 루트 저장소(`nucode-ble-core`)를 받은 로컬에서 돌립니다. 루트 저장소 안의 `android/` 에서 실행하면 `../contract` 가 잡힙니다. GitHub 미러 CI 에는 계약 폴더가 없어 계약 시험이 돌지 않습니다. 계약에 닿는 변경은 push 전에 로컬에서 시험합니다.

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

`.github/workflows/ci.yml` 은 GitHub 미러의 태그 push 와 손 실행(`workflow_dispatch`)에서만 돕니다.

- GitHub-hosted 러너(`ubuntu-latest`)에서 `./gradlew test :sample:assembleRelease` 를 돌립니다.
- 계약 폴더를 받지 않습니다. 계약 시나리오 sim 은 건너뜁니다.

## 배포

`.github/workflows/publish.yml` 이 GitHub 미러에서 `kr.co.nucode:ble-core` 를 GitHub Packages(`https://maven.pkg.github.com/Nucode01/nucode-ble-core-android`)에 배포합니다.

1. `core/build.gradle.kts` 의 `sdkVersion` 을 새 버전으로 올리고 커밋합니다.
2. 루트 저장소를 받은 로컬에서 계약 시험을 포함한 전체 시험을 돌립니다. 미러 CI 는 계약 시험을 돌리지 않기 때문입니다.
3. `sdkVersion` 과 같은 이름의 태그를 만들어 Azure 에 push 합니다: `git push origin main <태그>`. 파이프라인이 GitHub 미러로 옮깁니다.
4. GitHub 태그 push 에서 publish.yml 이 core 단위 시험을 돌린 뒤 배포합니다. ci.yml 도 같은 태그에서 시험과 빌드를 돌립니다.

- 태그 이름이 `sdkVersion` 과 다르면 publish.yml 은 배포하지 않고 실패합니다(`.github/publish/check-version.sh`).
- 이미 있는 태그를 다시 배포하려면 GitHub 미러의 Actions 에서 Publish 를 손으로 실행하고 `ref` 에 태그 이름을 줍니다. 손 실행은 시험을 건너뜁니다. 이 workflow 보다 먼저 만든 태그(0.3.0)도 같은 방법으로 배포합니다. 옛 태그에 없는 저장소 정의는 `.github/publish/github-packages.init.gradle.kts` 가 더합니다.
- GitHub Packages 는 같은 버전을 두 번 받지 않습니다. 다시 올리려면 패키지 버전을 지우거나 버전을 올립니다.

손으로 배포하려면 `write:packages` 범위의 토큰으로 실행합니다.

```bash
./gradlew :core:publishReleasePublicationToGitHubPackagesRepository -Pgpr.user=<사용자> -Pgpr.key=<토큰>
```
