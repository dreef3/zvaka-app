# GitHub Release Secrets

To run the release workflow end-to-end from GitHub Actions, add these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
  Base64-encoded upload keystore file contents
- `ANDROID_KEYSTORE_PASSWORD`
  Upload keystore password
- `ANDROID_KEY_ALIAS`
  Upload key alias
- `ANDROID_KEY_PASSWORD`
  Upload key password
- `MODEL_IMPROVEMENT_API_BASE_URL`
  HTTPS endpoint used by release builds for model-improvement uploads
- `MODEL_IMPROVEMENT_CLOUD_PROJECT_NUMBER`
  Google Cloud project number used by release builds for Play Integrity token requests

Optional secret for automatic Play upload:

- None when using GitHub OIDC Workload Identity Federation

Optional repository variable:

- None required for track selection; the manual workflow accepts `tracks` input and defaults to `qa`

## Notes

- The intended primary release path is GitHub Actions in `.github/workflows/android-release.yml`.
- The release workflow is manually triggered and creates the Git tag and published GitHub release itself.
- The workflow computes the next version automatically from the latest `v*` tag and the selected bump type.
- With `bump_type=auto`, the workflow infers:
  - `major` for commits with `BREAKING CHANGE` or `type!:` markers
  - `minor` for `feat:`
  - `patch` otherwise
- GitHub release notes are generated automatically from changes since the previous release.
- Google Play upload is authenticated via GitHub OIDC Workload Identity Federation using:
  - provider `projects/1086833593805/locations/global/workloadIdentityPools/github/providers/my-repo`
  - service account `github-releaser@opportune-chess-492418-r5.iam.gserviceaccount.com`
- The release workflow builds both:
  - `app-release.aab` for Google Play
  - `app-release.apk` for manual testing/distribution
- It also uploads:
  - ProGuard/R8 `mapping.txt`
- Example command to create `ANDROID_KEYSTORE_BASE64` on Linux:
  `base64 -w 0 upload-keystore.jks`
