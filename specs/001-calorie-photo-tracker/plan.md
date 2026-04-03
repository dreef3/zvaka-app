# Implementation Plan: Weight Loss Calorie Tracker

**Branch**: `001-calorie-photo-tracker` | **Date**: 2026-04-03 | **Spec**: [/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/spec.md](/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/spec.md)
**Input**: Feature specification from `/specs/001-calorie-photo-tracker/spec.md`

## Summary

Build a native Android calorie tracker in Kotlin that stores profile and intake
history locally, calculates a daily calorie budget with Mifflin-St Jeor, and uses
embedded Gemma 4 E2B through the LiteRT-LM Android runtime to estimate calories
from food photos on device. The first slice covers onboarding, photo capture with
low-confidence confirmation, today's summary, and 7-day/30-day trends with
forward-only budget changes.

## Technical Context

**Language/Version**: Kotlin 2.x  
**Primary Dependencies**: Android Gradle Plugin, Jetpack Compose, Navigation Compose, AndroidX ViewModel, Room, DataStore, CameraX, LiteRT-LM Android, Gemma 4 E2B model asset  
**Storage**: Room for structured local data, DataStore for lightweight preferences, app-private file storage for captured photos/model assets  
**Testing**: JUnit, Robolectric, Room tests, Compose UI tests, Android instrumentation tests  
**Target Platform**: Android 14+ phones  
**Project Type**: Native mobile app  
**Performance Goals**: Main screens render without visible jank, photo-to-estimate flow typically completes within 20 seconds, summary updates feel immediate after entry mutation  
**Constraints**: Offline-capable for onboarding/history/summary, single-user local-first app, no backend, on-device inference only, background model initialization required, low-confidence results require yes/no confirmation, budget changes apply forward only  
**Scale/Scope**: One personal user, four primary screens, tens to low hundreds of food entries per month, 7-day and 30-day trend windows only

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- The feature serves a concrete personal-use outcome and avoids speculative scope: PASS
- The design is the simplest approach that satisfies the current requirement: PASS
- The work is split into a small vertical slice with an independently usable result: PASS
- Functionality tests are identified for the slice and will be passing before completion: PASS
- Any new dependency or abstraction has a short justification tied to reduced complexity: PASS

## Project Structure

### Documentation (this feature)

```text
specs/001-calorie-photo-tracker/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
└── tasks.md
```

### Source Code (repository root)

```text
android/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/dreef3/weightlossapp/
│       │   │   ├── app/
│       │   │   ├── data/
│       │   │   ├── domain/
│       │   │   ├── features/
│       │   │   │   ├── onboarding/
│       │   │   │   ├── capture/
│       │   │   │   ├── summary/
│       │   │   │   └── trends/
│       │   │   └── inference/
│       │   ├── res/
│       │   └── assets/
│       ├── test/
│       └── androidTest/
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts

specs/
```

**Structure Decision**: Use a single native Android Gradle project under `android/`
with one application module. Keep feature code grouped by user-facing flow and keep
on-device inference behind a local adapter in `inference/` so the rest of the app
does not depend directly on LiteRT-LM engine calls.

## Phase 0: Outline & Research

### Research Focus

- Native Android app architecture that stays small for a solo-maintained app
- Local persistence for profile, budget history, and food history
- Camera capture flow suitable for fast single-photo logging
- On-device Gemma + LiteRT-LM integration boundary and failure handling
- Android test strategy that satisfies the mandatory functionality-test rule

### Research Output

- [/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/research.md](/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/research.md)

## Phase 1: Design & Contracts

### Data Model

- Model a single `UserProfile`
- Store forward-effective `DailyCalorieBudgetPeriod` rows so historical summaries
  keep the budget that was active on each day
- Store `FoodEntry` rows with capture metadata, model confidence state, and
  correction/deletion support
- Derive `DailySummary` and `TrendWindow` views from persisted entries and budgets

### Contracts

- UI contract for onboarding, capture confirmation, summary, trends, and profile edits
- On-device inference contract defining request/response/error shape between the app
  feature layer and the embedded Gemma runtime wrapper

### Validation Design

- Unit tests for Mifflin-St Jeor calculation, activity multiplier mapping, and
  summary/trend aggregation
- Room/integration tests for persistence, forward-only budget history, and entry mutation
- UI/instrumentation tests for onboarding, capture flow, low-confidence confirmation,
  today's summary updates, and trend navigation within two actions

### Phase 1 Output

- [/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/data-model.md](/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/data-model.md)
- [/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/contracts/ui-contracts.md](/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/contracts/ui-contracts.md)
- [/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/contracts/ai-estimation-service.md](/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/contracts/ai-estimation-service.md)
- [/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/quickstart.md](/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/quickstart.md)

## Post-Design Constitution Check

- Personal utility first: PASS. The scope remains a single-user tracker with no premium or social features.
- Simplicity over cleverness: PASS. The plan uses one Android app, local persistence, and an inference adapter instead of adding a backend.
- Functionality tests are mandatory: PASS. Unit, Room/integration, and UI tests are planned for each core behavior.
- Deliver small vertical slices: PASS. The first usable slice is onboarding + capture + today's summary, with trends layered on top.
- Documentation stays current: PASS. The spec, research, data model, contracts, and quickstart are aligned to the native Android direction.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| None | N/A | N/A |
