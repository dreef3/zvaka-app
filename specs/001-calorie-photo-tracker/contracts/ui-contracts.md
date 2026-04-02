# UI Contracts: Weight Loss Calorie Tracker

## Onboarding Contract

**Purpose**: Define the inputs and outputs for the first-run profile and budget flow.

**Inputs**:
- First name
- Height
- Weight
- Age
- Sex
- Age or date of birth
- Activity level

**Output State**:
- Stored `UserProfile`
- Active `DailyCalorieBudget`
- Redirect target set to the today's summary screen

**Rules**:
- First-run users must complete this flow before the app treats calorie tracking as active.
- Returning users must not be forced back through onboarding unless profile data is missing or they intentionally edit it.
- Profile updates that change the budget must create a new active budget effective on the day of change and must not rewrite earlier days.

## Food Capture Contract

**Purpose**: Define the boundary for photo-based meal logging.

**Input**:
- One food photo captured or selected by the user

**AI Estimation Response Shape**:
- `estimatedCalories`: integer
- `confidenceLabel`: one of `high`, `medium`, `low`, or omitted
- `detectedFoodLabel`: short description of the detected food
- `confidenceNotes`: optional brief explanation of ambiguity

**Output State**:
- A persisted `FoodEntry`
- Updated `DailySummary` for the relevant day
- Updated trend aggregates derived from the changed day

**Rules**:
- The primary flow must persist the entry without routing the user through long meal forms.
- If estimation confidence is not high, the app must ask the user to confirm whether the detected food is correct before saving.
- If the user rejects the detected food, the app must ask for another photo and must not save the current result.

## Daily Summary Contract

**Purpose**: Define the minimum data shown on the home screen.

**Displayed Fields**:
- Today's budget calories
- Today's consumed calories
- Today's remaining calories
- Over-budget or under-budget status
- Entry count for today

**Rules**:
- The values shown must reflect the latest active entries and budget for today's date.
- Edits and deletions must update the summary without requiring a manual refresh.

## Trend Summary Contract

**Purpose**: Define the data contract for the 7-day and 30-day summary views.

**Supported Windows**:
- Last 7 days
- Last 30 days

**Displayed Fields**:
- Total calories consumed in the window
- Total budget calories in the window
- Average consumed calories per day
- Average remaining calories per day
- Partial-history indicator when full-window data is unavailable

**Rules**:
- The trend view must remain available even when the user has fewer than 7 or 30 days of history.
- Window calculations must include only active, non-deleted food entries.
