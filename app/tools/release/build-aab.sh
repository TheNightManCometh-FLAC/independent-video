#!/usr/bin/env bash
# Independent Video release build: APK + Android App Bundle, signed with the upload key.
# Usage: build-aab.sh play|kofi
#   play: no brand/donate.txt, so no Ko-fi link anywhere in the app (Play payments policy)
#   kofi: ships the Ko-fi link
set -euo pipefail
VARIANT=${1:?play or kofi}
# Paths below are the cloud workspace these builds were made in; set SRC/SP for another machine.
SRC=${SRC:-/home/claude/src/Strata}
SP=${SP:-/tmp/claude-0/-home-claude/56423f1a-bc66-51ae-939f-1bd1ad0cbbdf/scratchpad}
# SP/iv-keys must hold independent-video-upload.jks, password.txt and the PEM pair (never commit these).
ST=$SP/stage-$VARIANT; OUTD=$SP/release; mkdir -p "$OUTD"
T=$HOME/.apklab-tools
SKILL=$(dirname "$(find / -path '*android-apk-builder/SKILL.md' -not -path '*-plus*' 2>/dev/null | head -1)")
KS=$SP/iv-keys/independent-video-upload.jks; PW=$(cat $SP/iv-keys/password.txt)
export JAVA_TOOL_OPTIONS=

rm -rf "$ST"; mkdir -p "$ST/keys"
cp -r "$SRC/src" "$SRC/res" "$SRC/assets" "$SRC/AndroidManifest.xml" "$ST/"
cp "$SP/iv-keys/debug.key.pem" "$SP/iv-keys/debug.cert.pem" "$ST/keys/"
[[ "$VARIANT" == play ]] && rm -f "$ST/assets/brand/donate.txt"
V=$(python3 -I -c "import xml.etree.ElementTree as E;print(E.parse('$ST/AndroidManifest.xml').getroot().attrib['{http://schemas.android.com/apk/res/android}versionName'])")
NAME=IndependentVideo-$V-$VARIANT

# 1. the APK (also leaves build/res.zip and build/classes.dex for the bundle)
bash "$SKILL/scripts/build.sh" "$ST" "$OUTD/$NAME.apk" | grep -E '^PASS|^package|FAIL'

# 2. relink the same compiled resources in protobuf format, as bundles require
B=$ST/build
ASSETS=(); [ -d "$ST/assets" ] && ASSETS=(-A "$ST/assets")
"$T/aapt2" link --proto-format -I "$T/android.jar" --manifest "$ST/AndroidManifest.xml" "${ASSETS[@]}" -o "$B/proto.apk" "$B/res.zip"

# 3. module layout: manifest/, dex/, res/, assets/, resources.pb
M=$B/module; rm -rf "$M"; mkdir -p "$M/manifest" "$M/dex"
(cd "$M" && unzip -q "$B/proto.apk")
mv "$M/AndroidManifest.xml" "$M/manifest/AndroidManifest.xml"
cp "$B/classes.dex" "$M/dex/classes.dex"
rm -f "$B/base.zip"; (cd "$M" && zip -qr -D "$B/base.zip" .)

# 4. bundle + sign (jarsigner, as Play expects for an upload)
rm -f "$OUTD/$NAME.aab"
java -jar "$T/bundletool.jar" build-bundle --modules="$B/base.zip" --output="$OUTD/$NAME.aab"
jarsigner -keystore "$KS" -storepass "$PW" -keypass "$PW" -sigalg SHA256withRSA -digestalg SHA-256 "$OUTD/$NAME.aab" upload >/dev/null
jarsigner -verify "$OUTD/$NAME.aab" | tail -1

# 5. check: bundletool validates it, and the APK Play would make from it installs the same app
java -jar "$T/bundletool.jar" validate --bundle="$OUTD/$NAME.aab" >/dev/null && echo "bundle valid"
rm -f "$B/check.apks"
java -jar "$T/bundletool.jar" build-apks --bundle="$OUTD/$NAME.aab" --output="$B/check.apks" --mode=universal \
  --ks="$KS" --ks-pass="pass:$PW" --ks-key-alias=upload --key-pass="pass:$PW"
rm -rf "$B/check"; mkdir -p "$B/check"; (cd "$B/check" && unzip -q "$B/check.apks")
"$T/aapt2" dump badging "$B/check/universal.apk" | grep -E "^package|application-label:|targetSdk|uses-permission"
echo "donate.txt in bundle: $(unzip -l "$OUTD/$NAME.aab" | grep -c 'assets/brand/donate.txt' || true)"
ls -la "$OUTD/$NAME.aab" "$OUTD/$NAME.apk"
