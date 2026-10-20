# 개발 안내

## 저장소

| 저장소 | 주소 | 쓰임 |
| --- | --- | --- |
| 메인 (Azure) | 사내 Azure DevOps. 주소는 사내 문서에 있습니다 | 개발. 원격 이름 `origin` |
| 미러 (GitHub) | `https://github.com/Nucode01/nucode-ble-core-android.git` | 설치(JitPack) · CI. 원격 이름 `github` |

개발은 Azure 메인에서 합니다. GitHub 는 미러입니다. JitPack 배포와 CI 는 모두 GitHub 미러를 기준으로 합니다.

사람은 Azure 메인(`origin`)에만 push 합니다. `github` 원격으로 직접 push 하지 않습니다.

- Azure Pipelines(`azure-pipelines.yml`)가 실행을 시작한 ref 하나만 GitHub 미러로 옮깁니다. main push 는 `main` 을, 태그 push 는 그 태그만 옮깁니다. 태그 실행은 미러의 `main` 을 바꾸지 않습니다.
- 덮어쓰기와 삭제는 하지 않습니다. 미러 이력이 Azure 와 갈라지면 파이프라인이 실패합니다.
- 미러의 태그 push 가 GitHub Actions 의 ci.yml 을 시작합니다.
- GitHub 는 push 된 태그의 커밋에 있는 workflow 를 씁니다. 옛 태그(0.2.0~0.3.0)의 ci.yml 은 태그로 돌지 않습니다.
- 옛 태그에는 `jitpack.yml` 이 없어 JitPack 에서 받을 수 없습니다.

### 파이프라인 설정 (처음 한 번)

파이프라인은 미러 저장소의 SSH deploy key 로 push 합니다. deploy key 는 사람 계정과 만료에 묶이지 않고, 이 저장소 하나에만 쓸 수 있습니다.

1. 이 저장소 전용 ed25519 키 쌍을 만듭니다: `ssh-keygen -t ed25519 -N '' -C nucode-ble-core-android-mirror -f nucode-ble-core-android-mirror`. 다른 저장소와 키를 같이 쓰지 않습니다.
2. 공개 키(`.pub`)를 GitHub 미러 `Nucode01/nucode-ble-core-android` 의 Settings → Deploy keys 에 더합니다. Allow write access 를 켭니다.
3. 개인 키를 Azure DevOps 의 Pipelines → Library → Secure files 에 올립니다. 이름은 `nucode-ble-core-android-mirror` 입니다.
4. 개인 키는 올린 뒤 로컬에서 지웁니다. 저장소에 넣지 않습니다.
5. Azure DevOps 에서 이 저장소의 `azure-pipelines.yml` 로 파이프라인을 만듭니다.
6. 첫 실행에서 secure file 사용 권한을 물으면 Permit 을 누릅니다.
7. 조직에서 Microsoft-hosted 병렬 작업을 쓸 수 있는지 확인합니다. 쓸 수 없으면 파이프라인이 대기열에서 시작하지 않습니다.

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

JitPack 이 GitHub 미러의 태그를 빌드해 냅니다. 저장소 루트 `jitpack.yml` 이 JDK 17 로 `./gradlew :core:publishToMavenLocal` 을 돌립니다. JitPack 은 그 결과를 `com.github.Nucode01:nucode-ble-core-android:<태그>` 로 냅니다.

1. `core/build.gradle.kts` 의 `sdkVersion` 을 새 버전으로 올리고 커밋합니다.
2. 루트 저장소를 받은 로컬에서 계약 시험을 포함한 전체 시험을 돌립니다. 미러 CI 는 계약 시험을 돌리지 않기 때문입니다.
3. `sdkVersion` 과 같은 이름의 태그를 만들어 Azure 에 push 합니다: `git push origin main <태그>`. 파이프라인이 GitHub 미러로 옮깁니다.
4. 미러 태그 push 에서 ci.yml 이 태그와 `sdkVersion` 이 같은지 검사한 뒤 시험과 빌드를 돌립니다.
5. `https://jitpack.io/#Nucode01/nucode-ble-core-android` 에서 그 태그를 빌드합니다. JitPack 은 처음 요청받을 때 빌드합니다. 빌드 로그에서 좌표를 확인합니다.

- 태그 이름이 `sdkVersion` 과 다르면 ci.yml 이 실패로 알립니다(`.github/release/check-version.sh`). JitPack 빌드는 막지 않습니다.
- JitPack 은 태그마다 처음 빌드한 결과를 저장해 둡니다. 태그를 옮기지 말고 새 태그를 만듭니다.

JitPack 과 같은 빌드를 로컬에서 확인합니다.

```bash
./gradlew --no-daemon :core:publishToMavenLocal
```
