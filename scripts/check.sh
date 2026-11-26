#!/usr/bin/env bash
# The one command that defines "healthy": CI runs exactly this, so green here means green in CI.
#
#   scripts/check.sh [android|ios|all]     (default: all)
#
# Builds and runs the unit tests without the model file (SPEC.md §3.1). It does not cover the
# emulator/simulator and airplane-mode checks (SPEC.md §9); those are still run by hand for now.
# A platform that cannot run on this machine is skipped, unless it was named explicitly or
# CHECK_STRICT=1 is set (CI sets it), in which case it fails.
set -euo pipefail
cd "$(dirname "$0")/.."

target="${1:-all}"
case "$target" in android | ios | all) ;; *) echo "usage: scripts/check.sh [android|ios|all]" >&2; exit 2 ;; esac

can_run_android() {
  command -v java > /dev/null || { echo "java not found"; return 1; }
  [[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" || -f android/local.properties ]] || { echo "Android SDK not found"; return 1; }
}

can_run_ios() {
  [[ "$(uname)" == Darwin ]] && command -v xcodebuild > /dev/null || { echo "needs macOS with Xcode"; return 1; }
}

run_android() {
  (cd android && ./gradlew testDebugUnitTest assembleDebug)
}

run_ios() {
  local udid="${SIM_UDID:-$(xcrun simctl list devices available \
    | awk '/^-- iOS 27/{f=1;next} /^--/{f=0} f && /iPhone/' \
    | grep -m1 -oE '[0-9A-F]{8}-([0-9A-F]{4}-){3}[0-9A-F]{12}' || true)}"
  [[ -n "$udid" ]] || { echo "no iOS 27 iPhone simulator available (set SIM_UDID)" >&2; return 1; }
  (cd ios && xcodebuild test \
    -project Translator.xcodeproj \
    -scheme Translator \
    -destination "platform=iOS Simulator,id=$udid" \
    -parallel-testing-enabled NO \
    CODE_SIGNING_ALLOWED=NO)
}

ran=()
skipped=()
for platform in android ios; do
  [[ "$target" == all || "$target" == "$platform" ]] || continue
  if ! reason="$("can_run_$platform")"; then
    if [[ "$target" == "$platform" || -n "${CHECK_STRICT:-}" ]]; then
      echo "FAIL: cannot check $platform here: $reason" >&2
      exit 1
    fi
    skipped+=("$platform ($reason)")
    continue
  fi
  echo "==> $platform"
  "run_$platform"
  ran+=("$platform")
done

echo "OK. ran: ${ran[*]:-none}; skipped: ${skipped[*]:-none}"
