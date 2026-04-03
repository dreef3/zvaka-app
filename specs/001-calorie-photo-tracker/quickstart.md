# Quickstart: Weight Loss Calorie Tracker

## Goal

Validate the first native Android implementation slice: onboarding, on-device
photo logging, today's summary, and recent trends.

## Preconditions

- The Android project under `android/` is created and syncs successfully.
- The embedded Gemma 4 E2B model asset and LiteRT-LM runtime are available to the app.
- An Android emulator or device running Android 14+ is available for UI validation.
- App data is cleared before the first-run onboarding test.

## Primary Validation Flow

1. From the repository root, run `cd android && ./gradlew installDebug`.
2. Launch the app on an emulator or physical device.
3. Complete onboarding with valid first name, weight, height, age, sex, and lifestyle values.
4. Confirm the app shows today's calorie budget after onboarding completes.
5. Capture a food photo from the main logging flow.
6. Confirm a high-confidence estimate creates a food entry and immediately updates today's consumed and remaining calories.
7. Trigger a non-high-confidence estimation case.
8. Confirm the app shows the detected food and asks for a yes/no confirmation.
9. Answer `No` and confirm the app requests another photo without saving the rejected result.
10. Capture again and complete a save path.
11. Edit the created entry and confirm today's totals update immediately.
12. Delete the same entry and confirm totals return to the pre-entry state.

## Trend Validation Flow

1. Seed or create entries across multiple dates.
2. Open the trends screen from the main screen within two actions.
3. Confirm the 7-day view shows total intake, total budget, and averages.
4. Switch to the 30-day view.
5. Confirm partial history is clearly marked when fewer than 30 days exist.
6. Update profile values so the budget changes today.
7. Confirm the new budget applies from today onward and earlier days retain their historical budget values.

## Required Automated Test Coverage

- Unit tests for Mifflin-St Jeor calculation and activity multiplier mapping.
- Unit tests for daily summary and 7-day/30-day trend aggregation.
- Room/integration tests for onboarding persistence, budget-period creation, and forward-only budget changes.
- Integration tests for create/edit/delete `FoodEntry` behavior.
- Integration tests for low-confidence confirmation and retake-without-save behavior.
- Compose or instrumentation tests for onboarding, food capture, today's summary, and trends access within two actions.

## Suggested Commands

- `cd android && ./gradlew testDebugUnitTest`
- `cd android && ./gradlew assembleDebugAndroidTest`
- `cd android && ./gradlew connectedDebugAndroidTest`

## Validation Notes

- `2026-04-03`: `cd android && ./gradlew testDebugUnitTest` passed in the local environment.
- `2026-04-03`: `androidTest` source files for onboarding, capture, summary, and trends compile-target coverage were added, but `connectedDebugAndroidTest` still requires an attached emulator or device and was not executed here.

## Expected Non-Goals for This Slice

- Macro tracking
- Exercise tracking
- Social features
- Premium upsells
- Multi-user synchronization
- Cloud sync or backend services
