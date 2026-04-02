# Research: Weight Loss Calorie Tracker

## Platform Choice

**Decision**: Build the first release as a Progressive Web App optimized for Android.

**Rationale**: The user explicitly allowed "Android application (or PWA)." A PWA
keeps the initial scope smaller, supports installable mobile behavior, and avoids
native Android build and release overhead while the product is still validating a
small personal-use workflow.

**Alternatives considered**:
- Native Android app: stronger device integration, but higher implementation and
  maintenance cost for the first slice.
- Cross-platform native wrapper: still adds packaging complexity without clear
  user benefit for the requested feature set.

## Persistence Strategy

**Decision**: Store profile, calorie budget, and food-log history locally in
IndexedDB.

**Rationale**: The product is single-user, local-first, and does not need account
management or multi-device synchronization in the first version. IndexedDB supports
structured history, image metadata, and offline viewing without introducing a server.

**Alternatives considered**:
- Remote database and auth: unnecessary for a first personal-use release.
- localStorage only: too limited and brittle for structured history and larger data.

## AI Estimation Boundary

**Decision**: Use a narrow AI vision adapter that accepts a food photo and returns a
calorie estimate plus confidence notes.

**Rationale**: The product's only remote intelligence requirement is estimating
calories from food photos. Keeping this behind a small service boundary lets the app
stay simple and makes provider changes possible without reshaping the rest of the app.

**Alternatives considered**:
- On-device vision: lower privacy risk but too complex and less flexible for the
  first implementation.
- Manual-only entry: simpler technically, but does not satisfy the main user value.

## Calorie Budget Calculation

**Decision**: Calculate the daily calorie budget from onboarding inputs using the
Mifflin-St Jeor equation plus lifestyle activity adjustment.

**Rationale**: The user explicitly asked for "well known formulas." Mifflin-St Jeor
is a common, defensible formula and requires a concrete onboarding set of age, sex,
weight, height, and lifestyle activity level. This keeps the estimate predictable,
explainable, and testable.

**Alternatives considered**:
- Static calorie targets: too inaccurate across different users.
- Unspecified "recognized formula": too ambiguous for consistent implementation.
- Highly customized nutrition coaching logic: outside scope for a minimal tracker.

## Low-Confidence Estimation Handling

**Decision**: When AI estimation confidence is not high, show the detected food and
ask the user to confirm it with a yes-or-no answer before saving.

**Rationale**: This preserves the fast silent-by-default flow for high-confidence
results while adding a minimal guardrail for ambiguous photos. If the user rejects
the detected food, the app asks for another photo rather than silently saving bad
data.

**Alternatives considered**:
- Always require user review: too much friction for the normal path.
- Always save low-confidence results: too likely to pollute calorie history.
- Fall back to manual meal entry: outside the intended minimal workflow.

## Profile Change Budget History

**Decision**: Apply budget changes from the day of profile change onward and do not
rewrite earlier days.

**Rationale**: Historical summaries should remain faithful to the budget in effect
when those days were logged. Forward-only changes also simplify reasoning about
trend history and reduce surprising retroactive changes.

**Alternatives considered**:
- Recalculate all historical days after every profile change: confusing and harder to validate.
- Ask the user to choose on every change: unnecessary complexity for a personal tool.

## Screen Model

**Decision**: Keep the experience to four primary screens: onboarding, capture/log,
today summary, and trends.

**Rationale**: This directly matches the requested functionality and keeps navigation
and implementation simple. Edit and delete actions remain subordinate states rather
than full additional product areas.

**Alternatives considered**:
- Rich meal management and coaching dashboards: unnecessary for the stated goal.
- Hidden multi-step food workflows: conflicts with the "silently track" requirement.

## Testing Strategy

**Decision**: Require unit tests for calculation and aggregation logic, integration
tests for persistence and state updates, and end-to-end tests for the core user flows.

**Rationale**: The constitution requires functionality tests to exist and pass before
completion. The riskiest behavior in this feature is not rendering but calculation,
state propagation, and the photo-to-log workflow.

**Alternatives considered**:
- Manual verification only: violates the constitution.
- End-to-end only: misses edge cases in calorie calculations and aggregation rules.
