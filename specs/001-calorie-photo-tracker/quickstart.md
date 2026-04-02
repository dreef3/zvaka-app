# Quickstart: Weight Loss Calorie Tracker

## Goal

Validate the first implementation slice of the calorie tracker PWA: onboarding,
photo logging, today's summary, and trend summaries.

## Preconditions

- The frontend PWA project is installed and runnable locally.
- The AI estimation adapter is configured for a non-production testing environment.
- Browser storage is clear before the onboarding flow test.

## Primary Validation Flow

1. Start the frontend application in development mode.
2. Open the app in a mobile-sized browser viewport.
3. Complete onboarding with valid profile inputs.
4. Confirm the app shows a daily calorie budget and lands on today's summary.
5. Capture or select a food photo.
6. Confirm an estimated-calorie food entry is created and today's consumed and
   remaining calories update immediately.
7. Repeat the capture flow with a low-confidence test result.
8. Confirm the app shows the detected food and asks for a yes-or-no confirmation.
9. Answer no and confirm the app asks for another photo without saving the result.
10. Edit a created food entry to a new calorie value.
11. Confirm today's totals update immediately after the edit.
12. Delete the same entry.
13. Confirm today's totals return to the pre-entry state.

## Trend Validation Flow

1. Seed or create entries across multiple dates.
2. Open the 7-day summary.
3. Confirm the window shows total intake, total budget, and average values.
4. Switch to the 30-day summary.
5. Confirm the longer window shows the same categories and marks partial history if
   there are fewer than 30 days of entries.
6. Update profile inputs so the calorie budget changes on the current day.
7. Confirm the new budget applies from today onward and earlier days retain their
   previous budget values in historical summaries.

## Required Automated Test Coverage

- Unit tests for calorie-budget calculation inputs and outputs.
- Unit tests for Mifflin-St Jeor budget calculation inputs and outputs.
- Unit tests for daily summary and rolling-window aggregation.
- Integration tests for creating, editing, and deleting `FoodEntry` records.
- Integration tests for profile updates that change future budget calculations without rewriting history.
- Integration tests for low-confidence food detection confirmation and retake flows.
- End-to-end tests for onboarding, food capture, low-confidence confirmation, daily summary update, and trend view access.

## Expected Non-Goals for This Slice

- Macro tracking
- Exercise tracking
- Social features
- Premium upsells
- Multi-user synchronization
