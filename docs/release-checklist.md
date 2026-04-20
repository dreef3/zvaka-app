# Release Checklist

## Build And Security

- Primary release build path is GitHub Actions via `.github/workflows/android-release.yml`.
- The release workflow is manually triggered with `workflow_dispatch`.
- The workflow automatically:
  - finds the latest `v*` tag
  - increments semantic version using the selected bump type (`auto`, `patch`, `minor`, `major`)
  - derives Android `versionName` and `versionCode`
  - creates and publishes the matching GitHub release
  - generates GitHub release notes from changes since the previous release
  - uploads the AAB to the selected Google Play tracks
- Add required repository secrets before the first release run:
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`
  - `MODEL_IMPROVEMENT_API_BASE_URL`
  - `MODEL_IMPROVEMENT_CLOUD_PROJECT_NUMBER`
- GitHub Actions Play upload uses Workload Identity Federation, not a service-account JSON secret.
- The workflow produces:
  - signed `app-release.aab`
  - signed `app-release.apk`
  - `mapping.txt`
- Workflow inputs:
  - `bump_type`
  - `tracks` with default `qa`
- Use local `cd android && ./gradlew bundleRelease` only as a secondary verification path, not the primary release process.
- Verify the release build uses an upload key, not the debug key.
- Keep `usesCleartextTraffic` disabled.
- Keep Android system backup and device-to-device migration disabled; this app uses its own Google Drive backup flow.
- Keep release shrinking/obfuscation enabled.

## Google Play Console

- Enable Play App Signing.
- Create the Android OAuth client for the release package/signing certificate.
- Verify the OAuth consent screen includes the Drive `drive.appdata` scope.
- Complete the Data safety form for:
  - Camera access for meal capture
  - Photos stored in app-private storage
  - Optional Google account / Drive backup connection
- Provide a privacy policy URL that explains on-device model use and optional Drive backup.
- Complete content rating, app category, contact details, and testing track setup.
- Prepare screenshots, app icon, feature graphic, and short/full store descriptions.

## Functional Verification

- Install the release-signed build on a clean device.
- Complete onboarding without Drive restore.
- Connect Google Drive and verify first backup succeeds.
- Reinstall on a second device with the same Google account and verify restore from onboarding.
- Verify meal capture, coach chat, model download, and background sync on Wi-Fi and cellular.
- Verify no debug-only screens or sample content are exposed in the release UX.
- Verify Settings -> Data and sync enables model improvement sharing in the release build and can request Play Integrity-backed uploads.

## Notes

- Current Google Play target API requirement is Android 15 / API 35 for new apps and updates submitted after August 31, 2025:
  https://support.google.com/googleplay/android-developer/answer/11926878
- Android recommends disabling cleartext traffic unless it is explicitly required:
  https://developer.android.com/guide/topics/manifest/application-element
