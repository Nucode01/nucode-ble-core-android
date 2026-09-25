#!/usr/bin/env bash
# 실기기 전제 검증 키트 실행기 — 안드로이드 기기 1대 + nRF52840 NUS 테스트 보드가 붙은 상태에서 돌린다.
# 사용법: [BLE_PERIPHERAL_ID=F0:2F:24:A8:0F:9E] scripts/device-verify.sh
# 절차·결과 기록 양식은 sample/DEVICE_VERIFICATION.md 를 본다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

device_count="$(adb devices | awk 'NR > 1 && $2 == "device"' | wc -l | tr -d ' ')"
if [[ "$device_count" != "1" ]]; then
  echo "adb 에 연결된 기기가 정확히 1대여야 한다 (현재 ${device_count}대)." >&2
  adb devices >&2
  exit 1
fi

gradle_args=(
  ":sample:connectedDebugAndroidTest"
  "-Pandroid.testInstrumentationRunnerArguments.hardwareBle=true"
)
if [[ -n "${BLE_PERIPHERAL_ID:-}" ]]; then
  gradle_args+=("-Pandroid.testInstrumentationRunnerArguments.blePeripheralId=${BLE_PERIPHERAL_ID}")
fi

out_dir="$ROOT/build/device-verify"
mkdir -p "$out_dir"
log_file="$out_dir/$(date +%Y%m%d-%H%M%S).log"

adb logcat -c
test_status=0
./gradlew "${gradle_args[@]}" || test_status=$?

adb logcat -d -s BlePremise BleHardwareTest BleBaseline > "$log_file"
echo "logcat -> $log_file"
echo "--- PREMISE lines ---"
grep "PREMISE|" "$log_file" || echo "(PREMISE 줄 없음)"
echo "gradle exit=$test_status (리포트: sample/build/reports/androidTests/connected/)"
exit "$test_status"
