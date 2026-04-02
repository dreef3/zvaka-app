# Data Model: Weight Loss Calorie Tracker

## UserProfile

**Purpose**: Stores the personal inputs required to estimate a daily calorie budget.

**Fields**:
- `id`: stable local identifier
- `createdAt`: timestamp of initial onboarding completion
- `updatedAt`: timestamp of latest profile change
- `firstName`: short display name
- `sex`: enum used by the chosen formula if required
- `dateOfBirth` or `ageYears`: age input used for budget estimation
- `heightCm`: positive integer
- `weightKg`: positive decimal
- `activityLevel`: normalized activity classification
- `goalRate`: desired weight-loss pace or target aggressiveness
- `goalWeightKg`: optional target weight

**Validation Rules**:
- Height and weight must be positive and within sane human-entry bounds.
- Activity level must be one of the defined supported options.
- Goal rate must stay within a safe, bounded range supported by the product.

**Relationships**:
- One `UserProfile` produces one active `DailyCalorieBudget`.
- Profile updates may produce a replacement budget calculation for future tracking only.

## DailyCalorieBudget

**Purpose**: Stores the estimated calorie target derived from profile data.

**Fields**:
- `id`: stable local identifier
- `profileId`: reference to `UserProfile`
- `caloriesPerDay`: integer calorie target
- `formulaName`: recognized formula used for calculation
- `activityMultiplier`: normalized multiplier value used in calculation
- `goalAdjustment`: calorie delta used to move toward the target goal
- `effectiveFromDate`: date when this budget becomes active
- `supersededAt`: optional timestamp when replaced by a new budget

**Validation Rules**:
- Calories per day must be a positive integer.
- Exactly one active budget may apply to a given date for summary calculations.
- A replacement budget may not retroactively rewrite dates before `effectiveFromDate`.

**Relationships**:
- Many `DailySummary` and `TrendSummary` calculations reference the active budget.

## FoodEntry

**Purpose**: Represents one logged eating event created from a food photo.

**Fields**:
- `id`: stable local identifier
- `capturedAt`: timestamp the food was logged
- `entryDate`: local calendar day used for daily aggregation
- `imageUri`: local browser-managed image reference or blob key
- `estimatedCalories`: integer returned by the AI estimator
- `finalCalories`: integer used in totals after optional user correction
- `confidenceLabel`: optional normalized confidence bucket
- `detectedFoodLabel`: short AI description of the food it believes is in the photo
- `confidenceNotes`: optional plain-language explanation of uncertainty
- `source`: enum with values such as `ai-estimate` and `user-corrected`
- `notes`: optional short user note
- `deletedAt`: optional soft-delete timestamp

**Validation Rules**:
- `finalCalories` must be positive when the entry is active.
- A deleted entry must be excluded from current totals and trends.
- `entryDate` must be derived consistently from the user's local time zone rules.

**State Transitions**:
- `captured` -> `estimated`
- `estimated` -> `pending-confirmation`
- `pending-confirmation` -> `estimated`
- `pending-confirmation` -> `rejected`
- `estimated` -> `corrected`
- `estimated` -> `deleted`
- `corrected` -> `deleted`

## DailySummary

**Purpose**: Represents the current or historical per-day aggregate shown in the main
summary view.

**Fields**:
- `date`: local calendar day
- `budgetCalories`: active target for that day
- `consumedCalories`: sum of active `FoodEntry.finalCalories`
- `remainingCalories`: `budgetCalories - consumedCalories`
- `status`: enum `under`, `at`, or `over`
- `entryCount`: number of active food entries
- `isPartial`: boolean indicating whether the day or data is incomplete

**Validation Rules**:
- Aggregates must be recomputed whenever an entry is created, edited, deleted, or a
  budget becomes effective for the same date.
- Budget changes affect the date of change onward and do not rewrite earlier daily summaries.

## TrendWindow

**Purpose**: Represents the 7-day or 30-day summary view.

**Fields**:
- `windowType`: enum `last7Days` or `last30Days`
- `startDate`: inclusive date boundary
- `endDate`: inclusive date boundary
- `daysIncluded`: number of days with available data in the window
- `totalConsumedCalories`: aggregate intake over the window
- `totalBudgetCalories`: aggregate active budget over the window
- `averageConsumedCalories`: average intake across included days
- `averageRemainingCalories`: average daily remaining calories across included days
- `isPartial`: boolean indicating incomplete history for the full requested window

**Validation Rules**:
- Window calculations must use the active budget for each included day.
- Window calculations must preserve historical budgets that were active on each included day.
- Partial-history windows must remain viewable and explicitly marked as partial.
