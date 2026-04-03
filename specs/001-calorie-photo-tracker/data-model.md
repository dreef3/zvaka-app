# Data Model: Weight Loss Calorie Tracker

## UserProfile

**Purpose**: Stores the personal inputs required to calculate the daily calorie budget.

**Fields**:
- `id`: single local profile identifier
- `createdAt`: timestamp of onboarding completion
- `updatedAt`: timestamp of the latest profile edit
- `firstName`: short display name
- `sex`: enum used by the Mifflin-St Jeor calculation
- `ageYears`: integer age captured during onboarding
- `heightCm`: positive integer
- `weightKg`: positive decimal
- `activityLevel`: enum `sedentary`, `light`, `moderate`, `active`, `very_active`

**Validation Rules**:
- `firstName` may be blank only if the UI intentionally allows it; otherwise keep it short.
- `ageYears`, `heightCm`, and `weightKg` must be within sane human-entry bounds.
- `activityLevel` must be one of the supported enum values.

**Relationships**:
- One `UserProfile` owns many `DailyCalorieBudgetPeriod` records over time.

## DailyCalorieBudgetPeriod

**Purpose**: Stores the calorie budget that applies from a specific local date onward.

**Fields**:
- `id`: stable local identifier
- `profileId`: foreign key to `UserProfile`
- `caloriesPerDay`: positive integer
- `formulaName`: fixed string `mifflin-st-jeor`
- `activityMultiplier`: decimal multiplier derived from `activityLevel`
- `effectiveFromDate`: local date when this budget becomes active
- `createdAt`: timestamp when the period was created

**Validation Rules**:
- Only one budget period may be active for a given local day.
- New periods must not rewrite or delete historical periods that were already effective.
- `effectiveFromDate` for a profile edit is the local day of the saved change.

**Relationships**:
- One `DailyCalorieBudgetPeriod` may apply to many `FoodEntry` and `DailySummary` calculations for matching dates.

## FoodEntry

**Purpose**: Represents one logged meal created from a captured food photo.

**Fields**:
- `id`: stable local identifier
- `capturedAt`: timestamp of image capture
- `entryDate`: local date used for aggregation
- `imagePath`: app-private file path or URI token for the stored image
- `estimatedCalories`: integer produced by the model
- `finalCalories`: integer used in totals after optional correction
- `confidenceState`: enum `high`, `non_high`, `failed`
- `detectedFoodLabel`: short model description shown in confirmation prompts
- `confidenceNotes`: optional explanation of ambiguity
- `confirmationStatus`: enum `not_required`, `accepted`, `rejected`
- `source`: enum `ai_estimate`, `user_corrected`
- `deletedAt`: nullable timestamp for soft deletion

**Validation Rules**:
- Active entries must have `finalCalories > 0`.
- Entries with `confirmationStatus = rejected` must not be included in totals.
- Soft-deleted entries must be excluded from daily and trend aggregates.
- `entryDate` must be derived from the device local time zone at capture/edit time.

**State Transitions**:
- `captured -> estimated_high`
- `captured -> pending_confirmation`
- `captured -> failed`
- `pending_confirmation -> saved`
- `pending_confirmation -> rejected`
- `saved -> corrected`
- `saved -> deleted`
- `corrected -> deleted`

## DailySummary

**Purpose**: Represents the aggregate shown for one local day on the home screen.

**Fields**:
- `date`: local date
- `budgetCalories`: active budget for the day
- `consumedCalories`: sum of active `FoodEntry.finalCalories`
- `remainingCalories`: `budgetCalories - consumedCalories`
- `status`: enum `under`, `at`, `over`
- `entryCount`: count of active entries
- `hasLimitedConfidenceEntries`: boolean for visible uncertainty messaging

**Validation Rules**:
- Any create/edit/delete operation on a `FoodEntry` must immediately update the derived summary.
- The summary must use the budget period active for `date`.

## TrendWindow

**Purpose**: Represents the 7-day or 30-day rolling summary.

**Fields**:
- `windowType`: enum `last7Days`, `last30Days`
- `startDate`: inclusive local date
- `endDate`: inclusive local date
- `daysIncluded`: number of days represented
- `totalConsumedCalories`: total intake in the window
- `totalBudgetCalories`: total budget in the window
- `averageConsumedCalories`: average intake per included day
- `averageRemainingCalories`: average remaining calories per included day
- `isPartial`: whether less than the full requested history exists

**Validation Rules**:
- The active budget for each historical day must come from the budget period effective on that day.
- Deleted or rejected entries must not contribute to trend totals.
- Partial windows must remain viewable and clearly labeled.
