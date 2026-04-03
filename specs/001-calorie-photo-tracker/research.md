# Research: Weight Loss Calorie Tracker

## Native Platform Choice

**Decision**: Implement the first release as a native Android application built
with Kotlin and Gradle.

**Rationale**: The latest clarification makes native Android mandatory. A single
Android app avoids the extra moving parts of a PWA shell or backend and fits the
constitution's preference for the simplest stack that satisfies the requirement.

**Alternatives considered**:
- PWA: no longer acceptable after clarification
- Cross-platform runtime: adds framework overhead without solving a real problem

## UI Architecture

**Decision**: Use Jetpack Compose with a single-activity architecture, feature-level
ViewModels, and Navigation Compose.

**Rationale**: Compose is the simplest modern Android UI stack for a small app with
four screens and a few transient states. It keeps UI state, previews, and testing
straightforward while avoiding XML/view-binding duplication.

**Alternatives considered**:
- XML fragments: workable, but heavier for iteration and state wiring
- Multi-activity app: unnecessary for this scope

## Persistence Strategy

**Decision**: Store structured data in Room, lightweight settings in DataStore, and
captured photos in app-private storage.

**Rationale**: The app is local-first, single-user, and offline-capable for core
history and summary views. Room fits the relational needs of budget periods and food
entries better than ad hoc file storage, while DataStore is enough for first-run and
simple preferences.

**Alternatives considered**:
- SharedPreferences only: too limited for history and queries
- SQLite without Room: lower-level and more error-prone for a solo app
- Remote backend: conflicts with the clarified on-device design

## On-Device Inference Boundary

**Decision**: Wrap the LiteRT-LM Android `Engine` and the embedded Gemma runtime in
a local inference adapter that accepts one image and returns calories, confidence
state, detected food, and optional notes.

**Rationale**: The app needs one narrow AI boundary. Keeping inference behind a
small adapter isolates runtime initialization, prompt formatting, model loading, and
error handling from the feature code and keeps a later model swap localized.

**Alternatives considered**:
- Direct model calls from UI code: too brittle and hard to test
- Remote AI API: explicitly rejected by the clarified requirement

## Embedded Model Constraint

**Decision**: Treat `Gemma 4 E2B` as a fixed product constraint and use the
LiteRT-LM Android runtime as the Kotlin integration surface, with model-capability
checks isolated behind an adapter.

**Rationale**: The user specified this exact stack. Official public Android examples
currently document LiteRT-LM for Kotlin/Gradle integration and emphasize supported
LiteRT model packaging. The plan needs a clean escape hatch if Gemma 4 E2B packaging
or runtime support differs when implementation starts. The adapter boundary keeps
that risk localized without contradicting the spec.

**Alternatives considered**:
- Silently changing the model family: violates the user's clarified requirement
- Hard-coding model/runtime usage throughout the app: makes any compatibility fix expensive

## Runtime Initialization

**Decision**: Initialize the LiteRT-LM engine off the main thread and reuse a
long-lived runtime instance for capture requests.

**Rationale**: The official Android docs note that model initialization can take
seconds. Loading the engine in a background coroutine and reusing it reduces startup
jank and avoids paying the full initialization cost for every photo.

**Alternatives considered**:
- Recreate the engine for every request: too slow and battery-inefficient
- Initialize on the UI thread: risks visible freezes

## Camera Capture Strategy

**Decision**: Use CameraX for capture and allow a single-photo logging flow that can
immediately hand off the image to on-device inference.

**Rationale**: CameraX is the most direct Android-native choice for a simple capture
experience with predictable lifecycle handling. It supports the "take a photo and log
it" workflow without custom camera plumbing.

**Alternatives considered**:
- Implicit camera intents only: less control over the fast capture flow
- Gallery-only import: does not satisfy the primary "make a photo" path

## Calorie Budget Calculation

**Decision**: Use the Mifflin-St Jeor equation with an activity multiplier derived
from the selected lifestyle level.

**Rationale**: This was already clarified in the spec and stays appropriate in the
Android implementation. The formula is common, deterministic, and easy to test.

**Alternatives considered**:
- Static targets: too crude
- Changing to another formula: unnecessary because the spec is already explicit

## Low-Confidence Handling

**Decision**: High-confidence estimates save silently; non-high-confidence estimates
must show the detected food and require a yes/no confirmation before persistence.

**Rationale**: This preserves the product's minimal primary flow while protecting the
daily log from obviously ambiguous detections. A rejected detection routes directly to
retake rather than opening a manual meal editor.

**Alternatives considered**:
- Always review before save: too much friction
- Always save low-confidence estimates: too likely to corrupt data

## Testing Strategy

**Decision**: Use unit tests for formula and aggregation logic, Room/integration
tests for persistence rules, and Android UI tests for the end-to-end user flows.

**Rationale**: The constitution requires functionality tests for each behavior. The
highest-risk areas are budget math, forward-only budget periods, entry mutations, and
the low-confidence confirmation branch.

**Alternatives considered**:
- Manual verification only: violates the constitution
- UI tests only: too slow and too weak for calculation-heavy logic
