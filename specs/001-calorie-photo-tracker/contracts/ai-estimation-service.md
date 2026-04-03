# On-Device Estimation Contract

## Purpose

Define the internal boundary between the Android feature layer and the embedded
Gemma 4 E2B + LiteRT-LM inference adapter.

## Request Contract

**Inputs**:
- `imagePath`: app-private path or URI token for one captured food photo
- `capturedAt`: timestamp of capture
- `locale`: optional locale hint for food naming or notes

**Constraints**:
- One request corresponds to one logging attempt.
- The inference adapter is estimation-only and does not own persistence, daily totals,
  or trend calculations.

## Response Contract

**Required Fields**:
- `estimatedCalories`: integer
- `confidenceState`: one of `high`, `non_high`, `failed`

**Optional Fields**:
- `detectedFoodLabel`: short detected-food description used in confirmation prompts
- `confidenceNotes`: short explanation of ambiguity or missing certainty
- `detectedItems`: optional list of meal components if the embedded model can provide them

## Error Contract

**Failure Classes**:
- `model-unavailable`
- `model-load-failed`
- `unreadable-image`
- `estimation-failed`
- `inference-timeout`

**Rules**:
- Error results must be explicit enough for the app to show a user-facing retry or retake path.
- The app must not create a successful food entry from an error response.
- A `non_high` response is not an error; it routes to the confirmation flow.
