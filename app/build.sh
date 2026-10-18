#!/usr/bin/env bash
# Android SDK build without Gradle. Preview installs alongside the original app.
set -euo pipefail
cd "$(dirname "$0")"
MODE=${1:-preview}
APP_VERSION=$(python3 - <<'PYVER'
import xml.etree.ElementTree as ET
print(ET.parse('AndroidManifest.xml').getroot().attrib['{http://schemas.android.com/apk/res/android}versionName'])
PYVER
)
: "${ANDROID_JAR:?Set ANDROID_JAR to an installed platform android.jar (API 35 or newer)}"
: "${ANDROID_BUILD_TOOLS:?Set ANDROID_BUILD_TOOLS to an installed SDK build-tools directory}"
OUT="$PWD/build/$MODE"
mkdir -p "$OUT/generated" "$OUT/classes" "$OUT/dex"
rm -rf "$OUT/generated" "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/generated" "$OUT/classes" "$OUT/dex"
MANIFEST="$PWD/AndroidManifest.xml"
if [[ "$MODE" == preview ]]; then
  MANIFEST="$OUT/AndroidManifest.xml"
  python3 - "$PWD/AndroidManifest.xml" "$MANIFEST" "$APP_VERSION" <<'PY'
import sys
from pathlib import Path
s=Path(sys.argv[1]).read_text()
for a,b in (('package="com.betterappmafia.independentvideo"','package="com.betterappmafia.independentvideo.preview"'),('android:label="@string/app_name"','android:label="Independent Video ' + sys.argv[3] + ' Test"')):
    assert a in s, a
    s=s.replace(a,b)
Path(sys.argv[2]).write_text(s)
PY
elif [[ "$MODE" != production ]]; then
  echo 'Usage: build.sh preview|production' >&2; exit 1
fi
"$ANDROID_BUILD_TOOLS/aapt" package -f -m --custom-package com.christopher.strata -J "$OUT/generated" -M "$MANIFEST" -S res -I "$ANDROID_JAR"
# Some runtimes contain the JDK compiler module but omit its launcher executable.
if command -v javac >/dev/null; then COMPILER=(javac); else COMPILER=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
mapfile -t SOURCES < <(find src "$OUT/generated" -name '*.java')
"${COMPILER[@]}" -source 8 -target 8 -Xlint:-options -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT/classes" "${SOURCES[@]}"
java -m jdk.jartool/sun.tools.jar.Main cf "$OUT/classes.jar" -C "$OUT/classes" .
java -cp "$ANDROID_BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 29 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar"
"$ANDROID_BUILD_TOOLS/aapt" package -f -M "$MANIFEST" -S res -A assets -I "$ANDROID_JAR" -F "$OUT/resources.apk"
python3 - "$OUT/resources.apk" "$OUT/dex" "$OUT/unsigned.apk" <<'PY'
import sys,zipfile,shutil
from pathlib import Path
shutil.copyfile(sys.argv[1],sys.argv[3])
with zipfile.ZipFile(sys.argv[3],'a',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(Path(sys.argv[2]).glob('*.dex')): z.write(p,p.name)
PY
"$ANDROID_BUILD_TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [[ "$MODE" == preview ]]; then
  # preview builds: the standard Android debug key (Android Studio creates it), or your own via env
  KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
  KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
  KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-android}"
  KEY_PASSWORD="${KEY_PASSWORD:-android}"
else
  : "${KEYSTORE:?Set KEYSTORE to the Independent Video upload key (kept outside this source tree)}"
  : "${KEY_ALIAS:?Set KEY_ALIAS}"
  : "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD}"
  : "${KEY_PASSWORD:?Set KEY_PASSWORD}"
fi
export KEYSTORE_PASSWORD KEY_PASSWORD
APK="$OUT/IndependentVideo-$APP_VERSION-$MODE.apk"
java -jar "$ANDROID_BUILD_TOOLS/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD --out "$APK" "$OUT/aligned.apk"
java -jar "$ANDROID_BUILD_TOOLS/lib/apksigner.jar" verify "$APK"
"$ANDROID_BUILD_TOOLS/zipalign" -c 4 "$APK"
echo "$APK"
