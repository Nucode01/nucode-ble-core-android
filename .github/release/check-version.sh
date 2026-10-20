#!/usr/bin/env bash
# ci.yml 태그 실행용. 현재 디렉터리(저장소 루트)의 core/build.gradle.kts sdkVersion 이 RELEASE_TAG 와 다르면 실패한다.
# JitPack 은 태그 이름을 버전으로 내므로 태그와 sdkVersion 이 같아야 한다.
set -euo pipefail
version=$(sed -n 's/^val sdkVersion = "\(.*\)"$/\1/p' core/build.gradle.kts)
if [ -z "$version" ]; then
  echo "::error::core/build.gradle.kts 에서 sdkVersion 을 찾지 못했다"
  exit 1
fi
if [ "$RELEASE_TAG" != "$version" ]; then
  echo "::error::태그 $RELEASE_TAG 와 sdkVersion $version 이 다르다"
  exit 1
fi
echo "릴리스 버전 $version"
