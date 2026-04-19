# weight-loss-app

Native Android calorie tracker focused on one-person daily calorie budgeting and
fast photo logging without premium upsells.

## Planned Stack

- Kotlin + Gradle
- Jetpack Compose
- Room + DataStore
- CameraX
- LiteRT-LM with embedded Gemma 4 E2B

## Local Setup

1. Install JDK 17+
2. Install Android Studio or the Android SDK for API 35
3. Open the `android/` project
4. Put the LiteRT-LM compatible model file at the app-private runtime path `files/models/gemma-3n-e2b-it.litertlm`
5. Render `google-services.json` from the template once before building (substitute your Firebase Web API key):
   ```bash
   cd android
   FIREBASE_WEB_API_KEY=your-key-here envsubst '$FIREBASE_WEB_API_KEY' \
     < app/google-services.json.template > app/google-services.json
   ```
   The rendered `app/google-services.json` is `.gitignore`d so it will never be committed.
   In CI, the `Render google-services.json` workflow step does this automatically from the `FIREBASE_WEB_API_KEY` repository secret.
6. If you are working outside Android Studio, set `ANDROID_SDK_ROOT` and use the checked-in Gradle Wrapper under `android/`

## Commands

```bash
cd android && ./gradlew installDebug
cd android && ./gradlew testDebugUnitTest
cd android && ./gradlew assembleDebugAndroidTest
cd android && ./gradlew connectedDebugAndroidTest
```

## Current Status

The repository now contains the Android scaffold, Room/DataStore foundation,
onboarding flow, capture orchestration, today's summary, and trend summaries.
`testDebugUnitTest` passes locally. Device/emulator-backed `androidTest`
execution still requires a connected Android 14+ target.
