#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
workspace="$(cd .. && pwd)"
if [[ -z "${JAVA_HOME:-}" && -x "$workspace/esv-toolchain-recovery/jdk21-module-launchers/bin/java" ]]; then
  export JAVA_HOME="$workspace/esv-toolchain-recovery/jdk21-module-launchers"
fi
if [[ -z "${GRADLE_USER_HOME:-}" && -d "$workspace/android-build-home/gradle-home" ]]; then
  export GRADLE_USER_HOME="$workspace/android-build-home/gradle-home"
fi
if [[ -d "$workspace/android-build-home/android-user" ]]; then
  export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$workspace/android-build-home/android-user}"
fi
if [[ -d "$workspace/android-build-home/home" ]]; then
  export HOME="${ESV_BUILD_HOME:-$workspace/android-build-home/home}"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$workspace/esv-toolchain-recovery/sdk" ]]; then
  export ANDROID_HOME="$workspace/esv-toolchain-recovery/sdk"
fi
# Use this invocation's platform-provided proxy with the unmodified default JVM trust.
if [[ -n "${HTTPS_PROXY:-}" ]]; then
  proxy="${HTTPS_PROXY#http://}"; host="${proxy%:*}"; port="${proxy##*:}"
  export GRADLE_OPTS="-Dhttp.proxyHost=$host -Dhttp.proxyPort=$port -Dhttps.proxyHost=$host -Dhttps.proxyPort=$port"
else
  unset GRADLE_OPTS
fi
exec ./gradlew --no-daemon --console=plain "$@"
