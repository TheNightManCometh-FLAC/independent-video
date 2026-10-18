#!/usr/bin/env bash
# Example: ANDROID_JAR=/sdk/platforms/android-35/android.jar bash tests/run.sh AutomationTest
set -euo pipefail
P=$(cd "$(dirname "$0")/.." && pwd)
AJ=${ANDROID_JAR:-$HOME/.apklab-tools/android.jar}
[[ -f "$AJ" ]] || { echo 'Set ANDROID_JAR to an installed Android platform jar.' >&2; exit 1; }
O=$(mktemp -d); S=$(mktemp -d)
trap 'rm -rf "$O" "$S"' EXIT
if command -v javac >/dev/null; then C=(javac); else C=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${C[@]}" -nowarn -encoding UTF-8 -d "$S" "$P"/tests/shim/org/json/*.java
mapfile -t SOURCES < <(find "$P/src" -name '*.java')
# R is only needed for MainActivity. Compile the resource constants from the last SDK build.
R="$P/build/preview/generated/com/christopher/strata/R.java"
[[ -f "$R" ]] || R="$P/build/production/generated/com/christopher/strata/R.java"
if [[ -f "$R" ]]; then SOURCES+=("$R"); fi
"${C[@]}" -nowarn -encoding UTF-8 -cp "$AJ" -d "$O" "${SOURCES[@]}"
if [[ "${1:?Pass a test class name}" != EncoderFinishTest ]]; then
    "${C[@]}" -nowarn -encoding UTF-8 -cp "$O:$AJ" -d "$O" "$P/tests/$1.java"
fi
T=$1; shift
if [[ "$T" == EncoderFinishTest ]]; then
    "${C[@]}" -nowarn -encoding UTF-8 -d "$S" "$P"/tests/codec-shim/android/media/*.java
    "${C[@]}" -nowarn -encoding UTF-8 -cp "$S:$O:$AJ" -d "$O" "$P/tests/EncoderFinishTest.java"
fi
java -cp "$S:$O:$AJ" "com.christopher.strata.$T" "$@"
