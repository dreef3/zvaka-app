# Feature Specification: Weight Loss Calorie Tracker

**Feature Branch**: `001-calorie-photo-tracker`  
**Created**: 2026-04-02  
**Status**: Draft  
**Input**: User description: "Build an Android application (or PWA) that will help me with weight loss. I don't like any applications already present on the market for their constant attempts to oversell me their premium plans. I don't need 99% of their functionality. What I need is: 1. Make a photo of any food, estimate how much calories there is via AI, silently track. 2. On the first start ask me necessary questions about weight/height/... and based on well known formulas estimate my daily calorie budget. Track everything towards that budget. 3. Show me today's spent vs remaining calories. 4. Show me simple stats for the past week / 30 days."

## Clarifications

### Session 2026-04-02

- Q: What onboarding inputs are required for the first calorie-budget calculation? → A: First name, weight, height, age, sex, and lifestyle; the minimum set needed for a recognized calorie formula
- Q: Which calorie formula should the product use? → A: Use the Mifflin-St Jeor equation and add age and sex to onboarding
- Q: What should happen when the AI cannot estimate calories with high confidence? → A: Ask the user to confirm whether the detected food is correct; if no, require another photo
- Q: How should profile changes that alter the daily calorie budget affect history? → A: Apply the new budget from the day of change onward; do not rewrite historical days
- Q: Which platform and AI stack should the initial release use? → A: Build a native Android app in Kotlin with Gradle and use embedded Gemma 4 E2B with LiteRT-LM for on-device food-photo estimation

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Set Up My Daily Budget (Priority: P1)

As a person trying to lose weight, I want the app to ask only for my first name,
weight, height, age, sex, and lifestyle activity level on first use, so I can get
a daily calorie budget with minimal setup friction while still using a recognized
calorie formula.

**Why this priority**: Without a calorie budget, the app cannot tell the user
whether they are on track, which makes the rest of the experience much less useful.

**Independent Test**: A new user can complete onboarding by entering first name,
weight, height, age, sex, and lifestyle activity level, see an estimated daily
calorie budget, and reopen the app later to see that the budget is still being used
for daily tracking.

**Acceptance Scenarios**:

1. **Given** a first-time user opens the app, **When** they provide first name,
   weight, height, age, sex, and lifestyle activity level, **Then** the app
   calculates and stores a daily calorie budget using the Mifflin-St Jeor equation
   and shows it as the baseline for today's tracking.
2. **Given** a returning user has already completed onboarding, **When** they open
   the app again, **Then** they are not asked to repeat onboarding unless they
   choose to update their profile data.
3. **Given** a user updates profile data that changes the calorie budget, **When**
   the new budget is saved, **Then** the updated budget applies from the day of the
   change onward and earlier logged days remain based on the previous budget.

---

### User Story 2 - Log Food From a Photo (Priority: P1)

As a person logging meals quickly, I want to take a photo of food and get a calorie
estimate that is recorded automatically, so I can track intake with almost no manual
effort.

**Why this priority**: Fast food logging is the core differentiator and the main
reason to use this product instead of manual calorie tracking.

**Independent Test**: A user can capture a food photo, receive a calorie estimate,
and confirm that the entry appears in today's intake total without needing to fill
in a full meal form when confidence is high; if confidence is not high, the user is
asked to confirm whether the detected food is correct before the entry is saved.

**Acceptance Scenarios**:

1. **Given** a user is on the main logging flow, **When** they take a photo of a
   meal, **Then** the app estimates the calories and records a food entry for today.
2. **Given** a food entry has been recorded from a photo, **When** the user views
   today's summary, **Then** the new calories are included in the consumed total
   and remaining budget.
3. **Given** the estimated calories are clearly wrong, **When** the user edits or
   deletes that entry, **Then** today's totals and historical totals are updated
   immediately.
4. **Given** the AI cannot estimate calories with high confidence, **When** it shows
   the food it believes is in the photo, **Then** the user is asked to answer yes
   or no to confirm whether that detected food is correct.
5. **Given** the AI cannot estimate calories with high confidence and the user
   answers no to the detected-food confirmation, **When** the app rejects the
   current result, **Then** it asks the user to take another photo instead of
   silently saving the entry.

---

### User Story 3 - See Today's Remaining Calories (Priority: P2)

As a person trying to stay within a daily budget, I want to see calories consumed
today versus calories remaining, so I can make meal decisions during the day without
digging through detailed logs.

**Why this priority**: Daily feedback turns logging into actionable guidance and is
the main decision-making view after capture.

**Independent Test**: After onboarding and at least one logged meal, the user can
open the main screen and see consumed calories, remaining calories, and whether they
are over or under budget for the current day.

**Acceptance Scenarios**:

1. **Given** a user has a daily budget and logged food today, **When** they open
   the home screen, **Then** the app shows today's consumed calories and remaining
   calories relative to the stored budget.
2. **Given** the user goes over their daily budget, **When** they view today's
   summary, **Then** the app clearly indicates that the budget has been exceeded.

---

### User Story 4 - Review Recent Trends (Priority: P3)

As a person monitoring progress over time, I want simple 7-day and 30-day calorie
stats, so I can see whether my intake trend is moving in the right direction without
using complex analytics.

**Why this priority**: Trend visibility is useful for reflection, but the app still
delivers core value without it once onboarding and logging are working.

**Independent Test**: A user with multiple days of logged data can view a weekly and
monthly stats screen that summarizes intake against budget in a way that is easy to
understand at a glance.

**Acceptance Scenarios**:

1. **Given** the user has at least 7 days of logged history, **When** they open the
   stats view, **Then** they can see a summary for the last 7 days.
2. **Given** the user has at least 30 days of logged history, **When** they switch
   the time range, **Then** they can see a summary for the last 30 days.
3. **Given** the user has fewer than 7 or 30 days of history, **When** they open
   stats, **Then** the app shows available data and makes the shorter history clear.

### Edge Cases

- What happens when the food photo is blurry, contains multiple dishes, or the
  calorie estimate is uncertain?
- What happens when the embedded on-device model is temporarily unavailable or
  cannot return an estimate on the device?
- How does the system handle meals logged shortly before or after midnight so they
  count toward the intended day?
- What happens when a user updates weight, height, or lifestyle after previous
  days have already been logged against an earlier calorie budget?
- How does the app behave when the user has no food logged yet for today or has not
  built enough history for a full 7-day or 30-day summary?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST onboard first-time users by asking only for first
  name, current weight, current height, age, sex, and lifestyle activity level.
- **FR-002**: The system MUST calculate a daily calorie budget from age, sex,
  weight, height, and lifestyle activity level collected during onboarding using
  the Mifflin-St Jeor equation, and store the resulting budget.
- **FR-003**: Users MUST be able to review and update the profile inputs that drive
  the daily calorie budget.
- **FR-003a**: When a profile update changes the daily calorie budget, the new
  budget MUST apply starting on the day of the change and MUST NOT rewrite
  historical daily summaries or trend periods that were already logged under an
  earlier budget.
- **FR-004**: The system MUST allow users to capture a food photo and create a dated
  food entry from that capture flow.
- **FR-005**: The system MUST estimate calories for a food photo and attach that
  estimate to the created food entry.
- **FR-005a**: The initial release MUST be a native Android application built with
  Kotlin and Gradle rather than a PWA or other web-first client.
- **FR-005b**: The initial release MUST use embedded Gemma 4 E2B with LiteRT-LM
  for on-device food-photo calorie estimation rather than a remote AI API.
- **FR-006**: The system MUST record food entries silently by default, without
  requiring premium upsell steps, unnecessary questionnaires, or multi-screen meal
  workflows for the primary logging path when the AI has high confidence in the
  detected food.
- **FR-006a**: When the AI cannot estimate calories with high confidence, the system
  MUST show the food it believes is in the photo and ask the user to confirm with a
  yes-or-no answer whether that detected food is correct before saving the entry.
- **FR-006b**: When the user answers no to the low-confidence detected-food
  confirmation, the system MUST ask the user to take another photo and MUST NOT
  silently save the current result as a food entry.
- **FR-007**: Users MUST be able to view today's total consumed calories and today's
  remaining calories relative to the stored budget.
- **FR-008**: The system MUST update today's totals immediately after a food entry is
  created, edited, or deleted.
- **FR-009**: Users MUST be able to edit or delete a logged food entry so incorrect
  estimates do not permanently distort daily tracking.
- **FR-010**: The system MUST provide a simple summary view for the last 7 days and
  the last 30 days of calorie intake.
- **FR-011**: The 7-day and 30-day summaries MUST show both total intake and the
  user's intake relative to the daily calorie budget for the selected period.
- **FR-012**: The system MUST preserve user profile data, daily budget, and food log
  history between sessions.
- **FR-013**: The system MUST make it clear when an estimate or history view is based
  on partial data, missing data, or limited confidence.
- **FR-014**: The system MUST support a single-user personal tracking experience with
  no requirement for social features, coaching flows, or paid feature prompts in the
  core tracking journey.

### Key Entities *(include if feature involves data)*

- **User Profile**: The personal information collected during onboarding, including
  first name, current weight, current height, age, sex, and lifestyle activity level.
- **Daily Calorie Budget**: The stored per-day intake target derived from the user
  profile and used to calculate remaining calories.
- **Food Entry**: A dated log item created from a food photo, including the image,
  estimated calories, timestamp, model confidence state, and any user corrections.
- **Daily Summary**: The current day's aggregate view of consumed calories, remaining
  calories, and over-budget or under-budget status.
- **Trend Summary**: A rolling 7-day or 30-day view of intake totals and intake
  relative to budget.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 90% of first-time users can complete onboarding and receive a daily
  calorie budget in under 2 minutes without external guidance.
- **SC-002**: 85% of meal logging attempts result in a recorded food entry within
  20 seconds from opening the capture flow to seeing the updated daily total.
- **SC-003**: 100% of created, edited, and deleted food entries are reflected in the
  displayed daily totals by the time the user returns to the main summary view.
- **SC-004**: 90% of users can correctly identify whether they are under or over
  budget for today from the main summary screen without opening a secondary detail
  view.
- **SC-005**: Users can access a 7-day or 30-day summary in no more than 2 actions
  from the main screen.

## Assumptions

- The initial release targets a single person tracking their own calorie intake on a
  mobile device, not shared household or coach-managed use.
- The initial release targets native Android only and is implemented as a Kotlin +
  Gradle application.
- The product experience must stay intentionally minimal and avoid subscription
  marketing patterns, premium upsell interruptions, and broad lifestyle features
  outside calorie budgeting and logging.
- The initial release uses embedded Gemma 4 E2B with LiteRT-LM for on-device food
  understanding and calorie estimation, with no dependency on a remote AI provider
  for the primary estimation flow.
- The daily calorie budget is an estimate for guidance, not medical advice or a
  clinical recommendation.
- Users may occasionally correct or remove AI-generated calorie estimates when the
  captured meal is ambiguous or misread.
- The first release focuses on calorie intake tracking and simple trends, not macro
  tracking, exercise tracking, barcode scanning, meal planning, or community features.
