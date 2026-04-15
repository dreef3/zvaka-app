# weight-loss-app Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-04-03

## Active Technologies
- Kotlin 2.x + Android Gradle Plugin, Jetpack Compose, Navigation Compose, AndroidX ViewModel, Room, DataStore, CameraX, LiteRT-LM Android, Gemma 4 E2B model asset (001-calorie-photo-tracker)
- Room for structured local data, DataStore for lightweight preferences, app-private file storage for captured photos/model assets (001-calorie-photo-tracker)

## Project Structure

```text
android/
specs/
```

## Commands

cd android && ./gradlew installDebug
cd android && ./gradlew testDebugUnitTest
cd android && ./gradlew connectedDebugAndroidTest

## Code Style

Kotlin 2.x: Follow standard conventions

## Recent Changes
- 001-calorie-photo-tracker: Switched planning to native Android with Kotlin, Compose, Room, CameraX, and on-device Gemma via LiteRT-LM

<!-- MANUAL ADDITIONS START -->
- Never run more than one Gradle build at a time on this machine. Before starting any `./gradlew ...` command, verify there is no other active Gradle wrapper build for the current user.
- Use Conventional Commits / semantic commit messages for all commits in this repo. Prefer formats like `feat: ...`, `fix: ...`, `refactor: ...`, `docs: ...`, `ci: ...`, and use `!` or `BREAKING CHANGE:` for breaking changes.
- Local device connection details and Android SDK paths live in `.env.local` at the repo root.
<!-- MANUAL ADDITIONS END -->
