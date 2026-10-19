#!/usr/bin/env bash
# Package one Independent Video update for every channel.
#
#   KEYS=/path/to/iv-keys bash app/tools/release/release.sh [--skip-tests]
#
# Before running: bump versionCode and versionName in app/AndroidManifest.xml, add a
# "# Independent Video <versionName>" entry at the top of app/CHANGELOG.md, and write
# fastlane/metadata/android/en-US/changelogs/<versionCode>.txt (500 characters max;
# it is the "What's new" text for Play, GitHub and F-Droid).
#
# Output in app/build/release/<versionName>/ (not committed):
#   IndependentVideo-<v>-play.aab  upload to Play Console (no Ko-fi link: Play payments policy)
#   IndependentVideo-<v>.apk       attach to the GitHub release (Ko-fi build, upload key)
#   RELEASE-NOTES.md               what to paste where, and the tag to create
# F-Droid needs no upload: it builds from the git tag (UpdateCheckMode: Tags).
#
# Runs in the Claude cloud workspace with the android-apk-builder toolchain set up
# (~/.apklab-tools: aapt2, dx, android.jar, bundletool.jar). KEYS holds
# independent-video-upload.jks, password.txt and debug.key.pem/debug.cert.pem (the
# same upload key as PEM). Never commit any of those.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../../.." && pwd); APP=$REPO/app
: "${KEYS:?Set KEYS to the folder holding the upload key (independent-video-upload.jks, password.txt, PEM pair)}"
for f in independent-video-upload.jks password.txt debug.key.pem debug.cert.pem; do
  [[ -f "$KEYS/$f" ]] || { echo "missing $KEYS/$f" >&2; exit 1; }; done
export JAVA_TOOL_OPTIONS=
read -r CODE NAME < <(python3 -I - "$APP/AndroidManifest.xml" <<'PY'
import sys, xml.etree.ElementTree as E
a=E.parse(sys.argv[1]).getroot().attrib; ns='{http://schemas.android.com/apk/res/android}'
print(a[ns+'versionCode'], a[ns+'versionName'])
PY
)
echo "== Independent Video $NAME (versionCode $CODE)"
fail() { echo "STOP: $*" >&2; exit 1; }

# 1. preflight: a real version bump with notes for every store
LAST=$(grep -v '^#' "$APP/tools/release/released.txt" | tail -1 | cut -d' ' -f1)
(( CODE > LAST )) || fail "versionCode $CODE is not above the last release ($LAST). Bump it in AndroidManifest.xml."
NOTES=$REPO/fastlane/metadata/android/en-US/changelogs/$CODE.txt
[[ -f "$NOTES" ]] || fail "write $NOTES (What's new, 500 characters max)"
N=$(python3 -I -c "import sys;print(len(open(sys.argv[1],encoding='utf-8').read().strip()))" "$NOTES")
(( N <= 500 )) || fail "$NOTES is $N characters; Play and F-Droid allow 500"
head -1 "$APP/CHANGELOG.md" | grep -qF "Independent Video $NAME" || fail "app/CHANGELOG.md must start with '# Independent Video $NAME'"
if git -C "$REPO" ls-remote --tags origin 2>/dev/null | grep -q "refs/tags/$NAME\$"; then fail "tag $NAME already exists on GitHub"; fi

# 2. tests
if [[ "${1:-}" != --skip-tests ]]; then
  TMP=$(mktemp -d)
  for t in "$APP"/tests/*Test.java; do
    s=$(basename "$t" .java)
    if (cd "$APP" && bash tests/run.sh "$s") > "$TMP/$s.log" 2>&1; then echo "  pass $s"; else cat "$TMP/$s.log"; fail "$s failed"; fi
  done
fi

# 3. build both variants from this repo's app/ with the upload key
OUT=$APP/build/release/$NAME; WORK=$APP/build/release/work; rm -rf "$OUT" "$WORK"; mkdir -p "$OUT" "$WORK"
for v in play kofi; do
  SRC=$APP SP=$WORK KEYS=$KEYS OUTD=$WORK bash "$APP/tools/release/build-aab.sh" $v
done
cp "$WORK/IndependentVideo-$NAME-play.aab" "$OUT/"
cp "$WORK/IndependentVideo-$NAME-kofi.apk" "$OUT/IndependentVideo-$NAME.apk"

# 4. checks: right key, right version, Ko-fi only where allowed
T=$HOME/.apklab-tools; PW=$(cat "$KEYS/password.txt")
WANT=$(keytool -list -v -keystore "$KEYS/independent-video-upload.jks" -storepass "$PW" -alias upload 2>/dev/null | sed -n 's/.*SHA256: *//p')
GOT=$(keytool -printcert -jarfile "$OUT/IndependentVideo-$NAME-play.aab" 2>/dev/null | sed -n 's/.*SHA256: *//p' | head -1)
[[ -n "$WANT" && "$WANT" == "$GOT" ]] || fail "bundle is not signed with the upload key ($GOT)"
unzip -l "$OUT/IndependentVideo-$NAME-play.aab" | grep -q 'assets/brand/donate.txt' && fail "Play bundle contains the Ko-fi link"
unzip -l "$OUT/IndependentVideo-$NAME.apk" | grep -q 'assets/brand/donate.txt' || fail "GitHub APK is missing the Ko-fi link"
"$T/aapt2" dump badging "$OUT/IndependentVideo-$NAME.apk" | grep -q "versionCode='$CODE' versionName='$NAME'" || fail "APK version mismatch"

# 5. what goes where
WHATS=$(cat "$NOTES")
cat > "$OUT/RELEASE-NOTES.md" <<MD
# Independent Video $NAME (versionCode $CODE)

## 1. Google Play
Play Console → Independent Video → Test and release → your track → Create new release.
Upload \`IndependentVideo-$NAME-play.aab\`. Release name: \`$NAME\`. Release notes:

<en-US>
$WHATS
</en-US>

## 2. GitHub (this also creates the tag F-Droid builds from)
github.com/TheNightManCometh-FLAC/independent-video → Releases → Draft a new release.
- Choose a tag: type \`$NAME\` → "Create new tag on publish", target \`main\`
- Title: \`Independent Video $NAME\`
- Attach \`IndependentVideo-$NAME.apk\`
- Description:

$WHATS

The APK here is signed differently from the Google Play and F-Droid versions, so it won't install over them. Pick one source and stay with it.

## 3. F-Droid
Nothing to upload. F-Droid's checker sees the new tag \`$NAME\`, builds it from source and publishes it, usually within a few days.
MD
printf '%s %s %s\n' "$CODE" "$NAME" "$(date -u +%F)" >> "$APP/tools/release/released.txt"
echo "== done: $OUT"; ls -la "$OUT"
