# UI Contracts: Weight Loss Calorie Tracker

## Onboarding Contract

**Purpose**: Define the first-run profile and calorie-budget setup flow.

**Inputs**:
- First name
- Weight in kilograms
- Height in centimeters
- Age in years
- Sex
- Activity level

**Output State**:
- Persisted `UserProfile`
- Active `DailyCalorieBudgetPeriod` effective today
- Navigation target set to today's summary screen

**Rules**:
- First-run users must complete onboarding before calorie tracking is active.
- Returning users must not be forced back through onboarding unless profile data is missing.
- Editing profile values that change the budget creates a new budget period effective on the save date only.

## Food Capture Contract

**Purpose**: Define the app-layer behavior for photo-based meal logging.

**Input**:
- One food photo captured with the device camera

**Inference Output Shape**:
- `estimatedCalories`: integer
- `confidenceState`: `high`, `non_high`, or `failed`
- `detectedFoodLabel`: short label shown to the user when confirmation is required
- `confidenceNotes`: optional explanation of uncertainty

**Output State**:
- A persisted `FoodEntry` when the save path succeeds
- Updated `DailySummary`
- Updated trend aggregates derived from the changed day

**Rules**:
- High-confidence estimates save silently by default.
- Non-high-confidence estimates must show the detected food and require a yes/no confirmation before save.
- If the user rejects the detection, the app must request another photo and must not save the rejected result.

## Daily Summary Contract

**Purpose**: Define the minimum home-screen data contract.

**Displayed Fields**:
- Today's budget calories
- Today's consumed calories
- Today's remaining calories
- Over-budget or under-budget status
- Today's entry count

**Rules**:
- Values must update immediately after entry creation, correction, or deletion.
- The summary must use the budget period active for today's local date.

## Trend Summary Contract

**Purpose**: Define the minimum 7-day and 30-day summary views.

**Supported Windows**:
- Last 7 days
- Last 30 days

**Displayed Fields**:
- Total consumed calories
- Total budget calories
- Average consumed calories
- Average remaining calories
- Partial-history indicator when the full requested window is unavailable

**Rules**:
- Trend views must remain accessible within two actions from the main screen.
- Historical calculations must use the budget period active on each included day.
- Rejected or deleted entries must not contribute to the window.
