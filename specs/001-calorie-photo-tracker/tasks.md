---

description: "Task list for implementing the Weight Loss Calorie Tracker feature"
---

# Tasks: Weight Loss Calorie Tracker

**Input**: Design documents from `/specs/001-calorie-photo-tracker/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Functionality test tasks are REQUIRED for every user story and feature
slice. Include the tests needed to prove the behavior works, and make passing
those tests part of the completion criteria.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Mobile app**: `android/app/src/main/java/com/dreef3/weightlossapp/` and test files under `android/app/src/test/java/com/dreef3/weightlossapp/` or `android/app/src/androidTest/java/com/dreef3/weightlossapp/`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the Android project, baseline build config, and test scaffolding

- [X] T001 Create the Android Gradle project structure in `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, and `android/app/build.gradle.kts`
- [X] T002 Create the application manifest, app entry point, and Compose theme scaffold in `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/dreef3/weightlossapp/app/MainActivity.kt`, and `android/app/src/main/java/com/dreef3/weightlossapp/app/ui/theme/`
- [X] T003 [P] Configure unit and instrumentation test runners in `android/app/src/test/java/com/dreef3/weightlossapp/` and `android/app/src/androidTest/java/com/dreef3/weightlossapp/`
- [X] T004 [P] Add model/runtime asset placeholders and packaging config in `android/app/src/main/assets/` and `android/app/build.gradle.kts`
- [X] T005 [P] Document Android setup and Gradle commands in `README.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T006 Define shared domain models and enums in `android/app/src/main/java/com/dreef3/weightlossapp/domain/model/UserProfile.kt`, `android/app/src/main/java/com/dreef3/weightlossapp/domain/model/DailyCalorieBudgetPeriod.kt`, `android/app/src/main/java/com/dreef3/weightlossapp/domain/model/FoodEntry.kt`, and `android/app/src/main/java/com/dreef3/weightlossapp/domain/model/TrendWindow.kt`
- [X] T007 Implement Room entities, DAOs, and database wiring in `android/app/src/main/java/com/dreef3/weightlossapp/data/local/entity/`, `android/app/src/main/java/com/dreef3/weightlossapp/data/local/dao/`, and `android/app/src/main/java/com/dreef3/weightlossapp/data/local/AppDatabase.kt`
- [X] T008 [P] Implement DataStore preferences for first-run and lightweight settings in `android/app/src/main/java/com/dreef3/weightlossapp/data/preferences/AppPreferences.kt`
- [X] T009 [P] Implement Mifflin-St Jeor budget calculation and activity multiplier utilities in `android/app/src/main/java/com/dreef3/weightlossapp/domain/calculation/CalorieBudgetCalculator.kt`
- [X] T010 [P] Implement daily summary and rolling trend aggregation utilities in `android/app/src/main/java/com/dreef3/weightlossapp/domain/calculation/SummaryAggregator.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/domain/calculation/TrendAggregator.kt`
- [X] T011 Implement repository interfaces and Room-backed repositories in `android/app/src/main/java/com/dreef3/weightlossapp/domain/repository/` and `android/app/src/main/java/com/dreef3/weightlossapp/data/repository/`
- [X] T012 [P] Implement app navigation shell and first-run gating in `android/app/src/main/java/com/dreef3/weightlossapp/app/AppNavHost.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/app/AppStateViewModel.kt`
- [X] T013 Implement the on-device inference adapter contract and error model in `android/app/src/main/java/com/dreef3/weightlossapp/inference/FoodEstimationEngine.kt`, `android/app/src/main/java/com/dreef3/weightlossapp/inference/FoodEstimationResult.kt`, and `android/app/src/main/java/com/dreef3/weightlossapp/inference/FoodEstimationError.kt`
- [X] T014 [P] Implement camera/photo file helpers and local date utilities in `android/app/src/main/java/com/dreef3/weightlossapp/app/media/PhotoStorage.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/app/time/LocalDateProvider.kt`

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Set Up My Daily Budget (Priority: P1) 🎯 MVP

**Goal**: Let a first-time user enter onboarding data and receive a stored daily calorie budget that can later be updated without rewriting history

**Independent Test**: A new user can complete onboarding with first name, weight, height, age, sex, and activity level, then later edit profile values and see a new budget apply only from the day of change onward.

### Tests for User Story 1 (REQUIRED) ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] T015 [P] [US1] Add unit tests for Mifflin-St Jeor calculation and activity mapping in `android/app/src/test/java/com/dreef3/weightlossapp/domain/calculation/CalorieBudgetCalculatorTest.kt`
- [X] T016 [P] [US1] Add Room tests for onboarding persistence and active budget-period creation in `android/app/src/test/java/com/dreef3/weightlossapp/data/repository/ProfileRepositoryTest.kt`
- [X] T017 [P] [US1] Add Room tests for forward-only budget updates after profile changes in `android/app/src/test/java/com/dreef3/weightlossapp/data/repository/BudgetHistoryRepositoryTest.kt`
- [X] T018 [P] [US1] Add Compose instrumentation coverage for onboarding completion and returning-user bypass in `android/app/src/androidTest/java/com/dreef3/weightlossapp/features/onboarding/OnboardingFlowTest.kt`

### Implementation for User Story 1

- [X] T019 [P] [US1] Create onboarding UI state, validators, and mappers in `android/app/src/main/java/com/dreef3/weightlossapp/features/onboarding/OnboardingFormState.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/features/onboarding/OnboardingValidator.kt`
- [X] T020 [P] [US1] Create onboarding screen composables in `android/app/src/main/java/com/dreef3/weightlossapp/features/onboarding/OnboardingScreen.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/features/onboarding/OnboardingFields.kt`
- [X] T021 [US1] Implement onboarding ViewModel and submission flow in `android/app/src/main/java/com/dreef3/weightlossapp/features/onboarding/OnboardingViewModel.kt`
- [X] T022 [US1] Implement profile save, budget-period creation, and forward-only history logic in `android/app/src/main/java/com/dreef3/weightlossapp/domain/usecase/SaveUserProfileUseCase.kt`
- [X] T023 [US1] Add profile edit screen and navigation path in `android/app/src/main/java/com/dreef3/weightlossapp/features/onboarding/ProfileEditScreen.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/app/AppNavHost.kt`

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently

---

## Phase 4: User Story 2 - Log Food From a Photo (Priority: P1)

**Goal**: Let the user capture a food photo, save high-confidence estimates silently, and route non-high-confidence results through confirmation or retake

**Independent Test**: A user can capture a food photo, get a saved entry immediately when confidence is high, and when confidence is not high they must confirm the detected food or retake without saving.

### Tests for User Story 2 (REQUIRED) ⚠️

- [X] T024 [P] [US2] Add unit tests for the inference adapter response and error mapping in `android/app/src/test/java/com/dreef3/weightlossapp/inference/FoodEstimationEngineTest.kt`
- [X] T025 [P] [US2] Add repository tests for create, edit, delete, and rejected-entry behavior in `android/app/src/test/java/com/dreef3/weightlossapp/data/repository/FoodEntryRepositoryTest.kt`
- [ ] T026 [P] [US2] Add instrumentation coverage for high-confidence save and low-confidence confirmation branches in `android/app/src/androidTest/java/com/dreef3/weightlossapp/features/capture/FoodCaptureFlowTest.kt`
- [ ] T027 [P] [US2] Add instrumentation coverage for retake-without-save and entry correction in `android/app/src/androidTest/java/com/dreef3/weightlossapp/features/capture/FoodCaptureRetakeTest.kt`

### Implementation for User Story 2

- [X] T028 [P] [US2] Create capture UI state and camera permission handling in `android/app/src/main/java/com/dreef3/weightlossapp/features/capture/CaptureUiState.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/features/capture/CameraPermissionState.kt`
- [X] T029 [P] [US2] Create CameraX capture screen composables in `android/app/src/main/java/com/dreef3/weightlossapp/features/capture/FoodCaptureScreen.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/features/capture/CameraPreview.kt`
- [X] T030 [P] [US2] Implement LiteRT-LM backed engine initialization and inference execution in `android/app/src/main/java/com/dreef3/weightlossapp/inference/LiteRtFoodEstimationEngine.kt`
- [X] T031 [US2] Implement capture orchestration and silent-save flow in `android/app/src/main/java/com/dreef3/weightlossapp/features/capture/FoodCaptureViewModel.kt`
- [X] T032 [US2] Implement low-confidence confirmation and retake-without-save behavior in `android/app/src/main/java/com/dreef3/weightlossapp/features/capture/ConfidenceConfirmationDialog.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/domain/usecase/ConfirmFoodEstimateUseCase.kt`
- [X] T033 [US2] Implement edit and delete entry flows in `android/app/src/main/java/com/dreef3/weightlossapp/features/capture/FoodEntryEditorSheet.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/domain/usecase/UpdateFoodEntryUseCase.kt`
- [X] T034 [US2] Add unreadable-image, model-unavailable, and inference-timeout recovery UI in `android/app/src/main/java/com/dreef3/weightlossapp/features/capture/CaptureErrorState.kt`

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently

---

## Phase 5: User Story 3 - See Today's Remaining Calories (Priority: P2)

**Goal**: Show today's consumed calories, remaining calories, and budget status with immediate updates after entry changes

**Independent Test**: After onboarding and at least one logged entry, the user can open the home screen and see accurate consumed and remaining calories that update after create, edit, and delete operations.

### Tests for User Story 3 (REQUIRED) ⚠️

- [X] T035 [P] [US3] Add unit tests for daily summary aggregation with active budget periods in `android/app/src/test/java/com/dreef3/weightlossapp/domain/calculation/DailySummaryAggregatorTest.kt`
- [X] T036 [P] [US3] Add repository or ViewModel tests for live summary updates after entry mutations in `android/app/src/test/java/com/dreef3/weightlossapp/features/summary/TodaySummaryViewModelTest.kt`
- [ ] T037 [P] [US3] Add instrumentation coverage for home-screen totals, empty state, and over-budget state in `android/app/src/androidTest/java/com/dreef3/weightlossapp/features/summary/TodaySummaryScreenTest.kt`

### Implementation for User Story 3

- [X] T038 [P] [US3] Create today's summary composables in `android/app/src/main/java/com/dreef3/weightlossapp/features/summary/TodaySummaryScreen.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/features/summary/SummaryCards.kt`
- [X] T039 [US3] Implement today's summary ViewModel and selectors in `android/app/src/main/java/com/dreef3/weightlossapp/features/summary/TodaySummaryViewModel.kt`
- [X] T040 [US3] Add empty-state, over-budget, and limited-confidence UI variants in `android/app/src/main/java/com/dreef3/weightlossapp/features/summary/SummaryEmptyState.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/features/summary/OverBudgetNotice.kt`
- [X] T041 [US3] Connect the home destination and summary refresh wiring in `android/app/src/main/java/com/dreef3/weightlossapp/app/AppNavHost.kt`

**Checkpoint**: At this point, User Stories 1, 2, and 3 should work independently

---

## Phase 6: User Story 4 - Review Recent Trends (Priority: P3)

**Goal**: Show simple 7-day and 30-day summaries with partial-history messaging and preserved historical budget values

**Independent Test**: A user with multiple days of data can open the trends screen within two actions from the main screen, switch windows, and see totals and averages based on the budget active on each historical day.

### Tests for User Story 4 (REQUIRED) ⚠️

- [X] T042 [P] [US4] Add unit tests for 7-day and 30-day aggregation with historical budget preservation in `android/app/src/test/java/com/dreef3/weightlossapp/domain/calculation/TrendAggregatorTest.kt`
- [X] T043 [P] [US4] Add ViewModel tests for partial-history and window switching behavior in `android/app/src/test/java/com/dreef3/weightlossapp/features/trends/TrendsViewModelTest.kt`
- [ ] T044 [P] [US4] Add instrumentation coverage for trends access within two actions and partial-history messaging in `android/app/src/androidTest/java/com/dreef3/weightlossapp/features/trends/TrendsScreenTest.kt`

### Implementation for User Story 4

- [X] T045 [P] [US4] Create trends composables and window toggle UI in `android/app/src/main/java/com/dreef3/weightlossapp/features/trends/TrendsScreen.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/features/trends/TrendWindowToggle.kt`
- [X] T046 [US4] Implement trends ViewModel and rolling summary selectors in `android/app/src/main/java/com/dreef3/weightlossapp/features/trends/TrendsViewModel.kt`
- [X] T047 [US4] Connect the trends destination and partial-history notice wiring in `android/app/src/main/java/com/dreef3/weightlossapp/app/AppNavHost.kt`

**Checkpoint**: All user stories should now be independently functional

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T048 [P] Add shared test fixtures and builders in `android/app/src/test/java/com/dreef3/weightlossapp/testutil/ProfileFixtures.kt` and `android/app/src/test/java/com/dreef3/weightlossapp/testutil/FoodEntryFixtures.kt`
- [ ] T049 Tune startup and inference initialization timing in `android/app/src/main/java/com/dreef3/weightlossapp/app/AppInitializer.kt` and `android/app/src/main/java/com/dreef3/weightlossapp/inference/LiteRtFoodEstimationEngine.kt`
- [ ] T050 [P] Update developer setup and validation notes in `README.md` and `/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/quickstart.md`
- [ ] T051 Run the quickstart validation flows and record any required follow-up in `/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - US1 and US2 can proceed in parallel after Foundational
  - US3 depends on working onboarding and entry flows
  - US4 depends on working history and summary data
- **Polish (Final Phase)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational - no dependency on other stories
- **User Story 2 (P1)**: Can start after Foundational - benefits from US1 for active budget state but can be developed with seeded local profile data
- **User Story 3 (P2)**: Depends on US1 for budget state and US2 for meaningful entry totals
- **User Story 4 (P3)**: Depends on US1 for historical budget periods and US2 for multi-day food history

### Within Each User Story

- Tests MUST be written and FAIL before implementation
- Domain and UI state before ViewModel orchestration
- ViewModels and use cases before navigation wiring
- Core behavior before error-state and polish work
- Each story must pass its required functionality tests before the next slice is considered complete

### Parallel Opportunities

- `T003`, `T004`, and `T005` can run in parallel during setup
- `T008`, `T009`, `T010`, `T012`, and `T014` can run in parallel during foundational work
- Within US1, `T015`, `T016`, `T017`, `T018`, `T019`, and `T020` can run in parallel
- Within US2, `T024`, `T025`, `T026`, `T027`, `T028`, `T029`, and `T030` can run in parallel
- Within US3, `T035`, `T036`, `T037`, and `T038` can run in parallel
- Within US4, `T042`, `T043`, `T044`, and `T045` can run in parallel

---

## Parallel Example: User Story 2

```bash
# Launch User Story 2 test work together:
Task: "Add unit tests for the inference adapter response and error mapping in android/app/src/test/java/com/dreef3/weightlossapp/inference/FoodEstimationEngineTest.kt"
Task: "Add repository tests for create, edit, delete, and rejected-entry behavior in android/app/src/test/java/com/dreef3/weightlossapp/data/repository/FoodEntryRepositoryTest.kt"
Task: "Add instrumentation coverage for high-confidence save and low-confidence confirmation branches in android/app/src/androidTest/java/com/dreef3/weightlossapp/features/capture/FoodCaptureFlowTest.kt"
Task: "Add instrumentation coverage for retake-without-save and entry correction in android/app/src/androidTest/java/com/dreef3/weightlossapp/features/capture/FoodCaptureRetakeTest.kt"

# Launch independent implementation tasks together:
Task: "Create capture UI state and camera permission handling in android/app/src/main/java/com/dreef3/weightlossapp/features/capture/CaptureUiState.kt and android/app/src/main/java/com/dreef3/weightlossapp/features/capture/CameraPermissionState.kt"
Task: "Create CameraX capture screen composables in android/app/src/main/java/com/dreef3/weightlossapp/features/capture/FoodCaptureScreen.kt and android/app/src/main/java/com/dreef3/weightlossapp/features/capture/CameraPreview.kt"
Task: "Implement LiteRT-LM backed engine initialization and inference execution in android/app/src/main/java/com/dreef3/weightlossapp/inference/LiteRtFoodEstimationEngine.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Run the US1 tests and verify onboarding plus forward-only budget changes

### Incremental Delivery

1. Complete Setup + Foundational
2. Add User Story 1 → Test independently → Demo onboarding and budget persistence
3. Add User Story 2 → Test independently → Demo photo logging and confirmation flow
4. Add User Story 3 → Test independently → Demo today's summary
5. Add User Story 4 → Test independently → Demo weekly/monthly trends
6. Finish with polish and quickstart validation

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1
   - Developer B: User Story 2
   - Developer C: User Story 3 preparation or shared polish
3. Merge stories in priority order and keep instrumentation tests green at each checkpoint

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] labels map tasks to specific user stories for traceability
- Every user story includes required functionality tests per the constitution
- The task list follows the Android/Kotlin/Compose plan rather than the earlier web/PWA direction
- There is still a spec-vs-plan naming mismatch between `LiteRT-RM` in `spec.md` and `LiteRT-LM` in the Android docs and plan; resolve that before implementation if you want fully clean traceability
