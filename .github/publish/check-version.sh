#!/usr/bin/env bash
# publish.yml 전용. 현재 디렉터리(배포할 소스 루트)의 core/build.gradle.kts sdkVersion 이 PUBLISH_REF 와 다르면 실패한다.
set -euo pipefail
version=$(sed -n 's/^val sdkVersion = "\(.*\)"$/\1/p' core/build.gradle.kts)
if [ -z "$version" ]; then
  echo "::error::core/build.gradle.kts 에서 sdkVersion 을 찾지 못했다"
  exit 1
fi
if [ "$PUBLISH_REF" != "$version" ]; then
  echo "::error::태그 $PUBLISH_REF 와 sdkVersion $version 이 다르다"
  exit 1
fi
echo "배포 버전 $version"
