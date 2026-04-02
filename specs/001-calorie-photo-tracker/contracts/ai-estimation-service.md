# AI Estimation Service Contract

## Purpose

Define the narrow service boundary between the PWA and the remote food-photo calorie
estimation capability.

## Request Contract

**Inputs**:
- `image`: one meal photo
- `capturedAt`: timestamp of capture
- `locale`: optional locale hint for food naming or notes

**Constraints**:
- One request corresponds to one user logging event.
- The client must treat the service as estimation-only; it does not own food-entry
  persistence or daily total calculations.

## Response Contract

**Required Fields**:
- `estimatedCalories`: integer calorie estimate

**Optional Fields**:
- `confidenceLabel`: normalized confidence indicator
- `detectedFoodLabel`: short detected-food description used in confirmation prompts
- `confidenceNotes`: short explanation of ambiguity or assumptions
- `detectedItems`: optional list of recognized meal components for future display

## Error Contract

**Failure Classes**:
- `unreadable-image`
- `estimation-unavailable`
- `network-failure`
- `rate-limited`

**Rules**:
- Errors must be explicit enough for the client to show a user-facing recovery path.
- The client must not create a successful AI-derived entry from an error response.
- Recovery may include retry, discard, or confirmation-driven retake after a low-confidence result.
