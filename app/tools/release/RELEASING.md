# Releasing an update

Every update goes to three places from one version bump: **Google Play** (an App Bundle), **GitHub** (an APK), and **F-Droid** (built by F-Droid from the git tag).

| Channel | What ships | Signed with | Ko-fi link |
|---|---|---|---|
| Google Play | `IndependentVideo-<v>-play.aab` | upload key, then Play's app signing key | no (Play payments policy) |
| GitHub release | `IndependentVideo-<v>.apk` | upload key | yes |
| F-Droid | F-Droid builds `bash build.sh fdroid` from tag `<v>` | F-Droid's key | yes |

The three are signed with different keys, so a phone can't update from one to another. That's normal.

## Steps

1. **Bump the version** in `app/AndroidManifest.xml`: `versionCode` +1, and `versionName` (e.g. `1.0.1`). The tag name will be exactly the versionName.
2. **Write the notes.**
   - `app/CHANGELOG.md`: new entry at the top, starting `# Independent Video <versionName>`.
   - `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: the short "What's new" (500 characters max). Play, GitHub and F-Droid all use this text.
3. **Package.** `KEYS=/path/to/iv-keys bash app/tools/release/release.sh` runs every test suite, builds both variants, checks the signing key, the version and where the Ko-fi link is, and writes `app/build/release/<v>/RELEASE-NOTES.md`.
4. **Commit and push** to `main` (the version bump, notes, and `tools/release/released.txt`).
5. **Play:** upload the `.aab` to a new release on the track you're using and paste the notes.
6. **GitHub:** draft a release, create tag `<versionName>` on `main`, attach the `.apk`, paste the notes. The tag must be on the commit from step 4.
7. **F-Droid:** nothing. Its update checker sees the tag and builds it, usually within a few days.

## The upload key

`independent-video-upload.jks` (alias `upload`, SHA-256 `9A:FC:A2:98…62:5D:EE`), its `password.txt`, and the same key as `debug.key.pem`/`debug.cert.pem` for the APK signer. Keep these backed up privately. They are never committed (`.gitignore` blocks them). If the upload key is ever lost, Play can reset it through Play App Signing; GitHub APK users would have to reinstall.

## F-Droid recipe

`tools/release/fdroid/com.betterappmafia.independentvideo.yml` is the metadata file submitted to [fdroiddata](https://gitlab.com/fdroid/fdroiddata). After F-Droid accepts it, F-Droid maintains its copy; this one is just the record.
