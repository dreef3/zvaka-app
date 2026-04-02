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

- **Web app**: `frontend/src/`, `frontend/tests/`
- Paths below assume the single frontend PWA structure defined in plan.md

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and baseline tooling

- [ ] T001 Create the PWA project structure in `frontend/` with `frontend/src/`, `frontend/public/`, and `frontend/tests/`
- [ ] T002 Initialize the TypeScript React/Vite application configuration in `frontend/package.json`, `frontend/tsconfig.json`, `frontend/vite.config.ts`, and `frontend/index.html`
- [ ] T003 [P] Configure the PWA shell and manifest in `frontend/public/manifest.webmanifest` and `frontend/public/icons/`
- [ ] T004 [P] Configure the test toolchain in `frontend/vitest.config.ts`, `frontend/playwright.config.ts`, and `frontend/tests/setup.ts`
- [ ] T005 [P] Configure linting and formatting in `frontend/eslint.config.js` and `frontend/.prettierrc`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T006 Define shared domain schemas and types in `frontend/src/lib/types.ts` and `frontend/src/lib/schemas.ts`
- [ ] T007 Implement IndexedDB storage setup and repositories in `frontend/src/services/storage/db.ts`, `frontend/src/services/storage/profile-repository.ts`, and `frontend/src/services/storage/food-entry-repository.ts`
- [ ] T008 [P] Implement Mifflin-St Jeor budget calculation utilities in `frontend/src/services/budget/calculate-budget.ts`
- [ ] T009 [P] Implement daily and rolling aggregation utilities with forward-only budget history rules in `frontend/src/services/budget/summary-calculations.ts`
- [ ] T010 Implement the AI estimation client contract, confidence handling, and error mapping in `frontend/src/services/ai/estimation-client.ts`
- [ ] T011 Implement app routing, first-run gating, and shared layout in `frontend/src/app/App.tsx`, `frontend/src/routes/index.tsx`, and `frontend/src/components/AppShell.tsx`
- [ ] T012 [P] Implement reusable date, image, and local-state helpers in `frontend/src/lib/date.ts`, `frontend/src/lib/images.ts`, and `frontend/src/lib/storage-state.ts`

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Set Up My Daily Budget (Priority: P1) 🎯 MVP

**Goal**: Let a first-time user enter onboarding data and receive a stored daily calorie budget based on the Mifflin-St Jeor equation

**Independent Test**: A new user can enter first name, weight, height, age, sex, and lifestyle activity level, receive a stored daily calorie budget, and later update profile values so the new budget applies from the change date onward without rewriting earlier days.

### Tests for User Story 1 (REQUIRED) ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T013 [P] [US1] Add unit tests for Mifflin-St Jeor budget calculation in `frontend/tests/unit/budget-calculation.test.ts`
- [ ] T014 [P] [US1] Add integration tests for onboarding persistence and active budget creation in `frontend/tests/integration/onboarding-persistence.test.tsx`
- [ ] T015 [P] [US1] Add integration tests for forward-only budget updates after profile changes in `frontend/tests/integration/profile-budget-history.test.tsx`
- [ ] T016 [P] [US1] Add end-to-end onboarding flow coverage in `frontend/tests/contract/onboarding-flow.spec.ts`

### Implementation for User Story 1

- [ ] T017 [P] [US1] Create onboarding form state and validation for first name, weight, height, age, sex, and lifestyle in `frontend/src/features/onboarding/onboarding-form.ts` and `frontend/src/features/onboarding/onboarding-schema.ts`
- [ ] T018 [P] [US1] Create onboarding screen components in `frontend/src/features/onboarding/OnboardingPage.tsx` and `frontend/src/features/onboarding/OnboardingFields.tsx`
- [ ] T019 [US1] Implement onboarding submission and budget activation logic in `frontend/src/features/onboarding/onboarding-service.ts`
- [ ] T020 [US1] Connect onboarding routing and first-run redirect behavior in `frontend/src/routes/onboarding.tsx` and `frontend/src/app/App.tsx`
- [ ] T021 [US1] Add profile edit flow with forward-only budget change handling in `frontend/src/features/onboarding/ProfileSettingsPage.tsx`

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently

---

## Phase 4: User Story 2 - Log Food From a Photo (Priority: P1)

**Goal**: Let the user capture or choose a food photo, save high-confidence estimates silently, and route low-confidence estimates through a confirmation-or-retake step

**Independent Test**: A user can save a high-confidence photo result immediately, and a low-confidence result asks whether the detected food is correct; answering no requires another photo and does not save the current result.

### Tests for User Story 2 (REQUIRED) ⚠️

- [ ] T022 [P] [US2] Add contract tests for the AI estimation client response, detected-food label, and error mapping in `frontend/tests/contract/ai-estimation-client.test.ts`
- [ ] T023 [P] [US2] Add integration tests for high-confidence save, low-confidence confirmation, and retake flows in `frontend/tests/integration/food-entry-confidence-flow.test.tsx`
- [ ] T024 [P] [US2] Add integration tests for create, edit, and delete food-entry mutations in `frontend/tests/integration/food-entry-lifecycle.test.tsx`
- [ ] T025 [P] [US2] Add end-to-end photo logging coverage for both confirmation branches in `frontend/tests/contract/photo-log-flow.spec.ts`

### Implementation for User Story 2

- [ ] T026 [P] [US2] Create food-entry domain types and mappers including detected-food and confidence fields in `frontend/src/features/food-log/food-entry-types.ts`
- [ ] T027 [P] [US2] Create capture, result, and confidence-confirmation components in `frontend/src/features/food-log/FoodCapturePage.tsx`, `frontend/src/features/food-log/FoodCaptureForm.tsx`, `frontend/src/features/food-log/FoodEstimateResult.tsx`, and `frontend/src/features/food-log/FoodConfidencePrompt.tsx`
- [ ] T028 [US2] Implement photo submission, AI estimation, silent-save, and low-confidence confirmation flow in `frontend/src/features/food-log/food-log-service.ts`
- [ ] T029 [US2] Implement retake behavior and no-save handling for rejected low-confidence results in `frontend/src/features/food-log/food-confidence-mutations.ts`
- [ ] T030 [US2] Implement entry edit and delete actions in `frontend/src/features/food-log/FoodEntryEditor.tsx` and `frontend/src/features/food-log/food-entry-mutations.ts`
- [ ] T031 [US2] Add user-facing recovery states for unreadable images and estimation failures in `frontend/src/features/food-log/FoodEstimateErrorState.tsx`

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently

---

## Phase 5: User Story 3 - See Today's Remaining Calories (Priority: P2)

**Goal**: Show a clear home screen with consumed calories, remaining calories, and budget status for today

**Independent Test**: After onboarding and at least one logged meal, the user can open the home screen and see accurate daily totals that react to entry edits, deletions, and the current day's active budget.

### Tests for User Story 3 (REQUIRED) ⚠️

- [ ] T032 [P] [US3] Add unit tests for daily summary aggregation with active daily budgets in `frontend/tests/unit/daily-summary.test.ts`
- [ ] T033 [P] [US3] Add integration tests for summary updates after food-entry mutations in `frontend/tests/integration/daily-summary-updates.test.tsx`
- [ ] T034 [P] [US3] Add end-to-end home-summary coverage in `frontend/tests/contract/daily-summary.spec.ts`

### Implementation for User Story 3

- [ ] T035 [P] [US3] Create summary view components in `frontend/src/features/summary/SummaryPage.tsx` and `frontend/src/features/summary/SummaryCards.tsx`
- [ ] T036 [US3] Implement today's summary selectors and active-budget state composition in `frontend/src/features/summary/summary-service.ts`
- [ ] T037 [US3] Connect the home route and live summary refresh behavior in `frontend/src/routes/home.tsx`
- [ ] T038 [US3] Add empty-state and over-budget variants in `frontend/src/features/summary/SummaryEmptyState.tsx` and `frontend/src/features/summary/OverBudgetNotice.tsx`

**Checkpoint**: At this point, User Stories 1, 2, and 3 should work independently

---

## Phase 6: User Story 4 - Review Recent Trends (Priority: P3)

**Goal**: Show simple 7-day and 30-day calorie summaries with partial-history handling and preserved historical budget values

**Independent Test**: A user with multi-day entries can open the trends screen, switch between 7-day and 30-day windows, and see totals and averages derived from stored entries and the budgets that were active on each historical day.

### Tests for User Story 4 (REQUIRED) ⚠️

- [ ] T039 [P] [US4] Add unit tests for rolling-window calculations with preserved historical budgets in `frontend/tests/unit/trend-window.test.ts`
- [ ] T040 [P] [US4] Add integration tests for partial-history windows and profile-change history behavior in `frontend/tests/integration/trends-view.test.tsx`
- [ ] T041 [P] [US4] Add end-to-end trends screen coverage in `frontend/tests/contract/trends-flow.spec.ts`

### Implementation for User Story 4

- [ ] T042 [P] [US4] Create trends screen components in `frontend/src/features/trends/TrendsPage.tsx`, `frontend/src/features/trends/TrendWindowToggle.tsx`, and `frontend/src/features/trends/TrendSummaryCards.tsx`
- [ ] T043 [US4] Implement 7-day and 30-day aggregation selectors with preserved historical budget values in `frontend/src/features/trends/trends-service.ts`
- [ ] T044 [US4] Connect trends routing and partial-history messaging in `frontend/src/routes/trends.tsx`

**Checkpoint**: All user stories should now be independently functional

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T045 [P] Add seed fixtures and reusable test data builders in `frontend/tests/fixtures/profile-fixtures.ts` and `frontend/tests/fixtures/food-entry-fixtures.ts`
- [ ] T046 Improve installability and mobile metadata in `frontend/public/manifest.webmanifest` and `frontend/index.html`
- [ ] T047 [P] Document local setup and validation commands in `README.md`
- [ ] T048 Run the quickstart validation and capture any fixes in `specs/001-calorie-photo-tracker/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel if needed, though priority order is still recommended
  - Or sequentially in priority order (US1/US2 as P1, then US3, then US4)
- **Polish (Final Phase)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P1)**: Can start after Foundational (Phase 2) - Depends on the shared AI client and benefits from US1 for an active budget, but can be implemented independently with seeded budget state during development
- **User Story 3 (P2)**: Depends on US1 for an active budget and US2 for meal entries to be meaningful
- **User Story 4 (P3)**: Depends on US1 for historical budget data and US2 for multi-day entry history

### Within Each User Story

- Tests MUST be written and FAIL before implementation
- Story-specific domain types and components before service orchestration
- Services before route wiring
- Core implementation before recovery-state and polish work
- Story complete before moving to the next priority if delivering incrementally

### Parallel Opportunities

- `T003`, `T004`, and `T005` can run in parallel during setup
- `T008`, `T009`, and `T012` can run in parallel during foundational work
- Within US1, `T013`, `T014`, `T015`, `T016`, `T017`, and `T018` can run in parallel
- Within US2, `T022`, `T023`, `T024`, `T025`, `T026`, and `T027` can run in parallel
- Within US3, `T032`, `T033`, `T034`, and `T035` can run in parallel
- Within US4, `T039`, `T040`, `T041`, and `T042` can run in parallel

---

## Parallel Example: User Story 2

```bash
# Launch all tests for User Story 2 together:
Task: "Add contract tests for the AI estimation client response, detected-food label, and error mapping in frontend/tests/contract/ai-estimation-client.test.ts"
Task: "Add integration tests for high-confidence save, low-confidence confirmation, and retake flows in frontend/tests/integration/food-entry-confidence-flow.test.tsx"
Task: "Add integration tests for create, edit, and delete food-entry mutations in frontend/tests/integration/food-entry-lifecycle.test.tsx"
Task: "Add end-to-end photo logging coverage for both confirmation branches in frontend/tests/contract/photo-log-flow.spec.ts"

# Launch independent UI/domain tasks for User Story 2 together:
Task: "Create food-entry domain types and mappers including detected-food and confidence fields in frontend/src/features/food-log/food-entry-types.ts"
Task: "Create capture, result, and confidence-confirmation components in frontend/src/features/food-log/FoodCapturePage.tsx, frontend/src/features/food-log/FoodCaptureForm.tsx, frontend/src/features/food-log/FoodEstimateResult.tsx, and frontend/src/features/food-log/FoodConfidencePrompt.tsx"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Test User Story 1 independently
5. Demo onboarding and forward-only budget update behavior before expanding scope

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Demo
3. Add User Story 2 → Test independently → Demo
4. Add User Story 3 → Test independently → Demo
5. Add User Story 4 → Test independently → Demo
6. Finish with polish and quickstart validation

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1
   - Developer B: User Story 2
   - Developer C: User Story 3 or 4 once dependencies are ready
3. Stories complete with contract-aligned integration points

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable once its dependencies are satisfied
- Functionality tests are explicitly required by the constitution and included for every story
- Commit after each task or logical group
- Avoid vague tasks, same-file conflicts, and cross-story coupling that breaks independent validation
