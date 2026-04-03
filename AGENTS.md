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
<!-- MANUAL ADDITIONS END -->
