# Implementation Plan: Weight Loss Calorie Tracker

**Branch**: `001-calorie-photo-tracker` | **Date**: 2026-04-02 | **Spec**: [/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/spec.md](/home/ae/weight-loss-app/specs/001-calorie-photo-tracker/spec.md)
**Input**: Feature specification from `/specs/001-calorie-photo-tracker/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Build a minimal single-user calorie tracking product that estimates food calories
from a photo, computes a personal daily calorie budget during onboarding, and
shows a clear daily remaining-calorie summary plus simple 7-day and 30-day
trend views. The implementation will use a PWA-first architecture to satisfy the
Android-friendly requirement with less complexity than a native app, store user
and food-log data locally, use the Mifflin-St Jeor equation for budget calculation,
and require low-confidence AI results to be user-confirmed before saving.

## Technical Context

**Language/Version**: TypeScript 5.x  
**Primary Dependencies**: React 19, Vite 7, React Router, Dexie, Zod, AI vision HTTP adapter  
**Storage**: IndexedDB for local-first persistence, browser local settings for lightweight preferences  
**Testing**: Vitest, React Testing Library, Playwright  
**Target Platform**: Android-friendly Progressive Web App in modern mobile Chromium browsers
**Project Type**: web app (PWA)  
**Performance Goals**: Main summary available in under 1 second from warm start; photo-to-tracked-entry flow within 20 seconds under normal network conditions, including the low-confidence confirmation branch  
**Constraints**: Single-user only, minimal UI, offline access for existing history, network required for photo calorie estimation, no premium or social flows, historical calorie budgets remain unchanged after profile updates  
**Scale/Scope**: One user per device, hundreds to low thousands of food entries, four primary screens plus supporting edit and confirmation states

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- The feature serves a concrete personal-use outcome and avoids speculative scope.
- The design is the simplest approach that satisfies the current requirement.
- The work is split into a small vertical slice with an independently usable result.
- Functionality tests are identified for the slice and will be passing before completion.
- Any new dependency or abstraction has a short justification tied to reduced complexity.

Gate status before Phase 0: PASS

- Personal-use outcome: The product remains explicitly focused on one person tracking calories without premium distractions.
- Simplicity: PWA-first still avoids native-platform overhead and the AI boundary remains narrow.
- Vertical slice: Onboarding, photo logging, daily summary, and trends still map cleanly to the requested user-visible flows.
- Test strategy: Unit, integration, and end-to-end tests cover onboarding, formula-based budget creation, low-confidence confirmation behavior, daily totals, and trend calculations.
- Dependency justification: React/Vite, Dexie, and Zod reduce complexity for routing, persistence, and validation without adding backend infrastructure.

## Project Structure

### Documentation (this feature)

```text
specs/001-calorie-photo-tracker/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
frontend/
├── public/
│   ├── manifest.webmanifest
│   └── icons/
├── src/
│   ├── app/
│   ├── components/
│   ├── features/
│   │   ├── onboarding/
│   │   ├── food-log/
│   │   ├── summary/
│   │   └── trends/
│   ├── lib/
│   ├── services/
│   │   ├── ai/
│   │   ├── budget/
│   │   └── storage/
│   └── routes/
└── tests/
    ├── contract/
    ├── integration/
    └── unit/
```

**Structure Decision**: Use a single frontend PWA project. This keeps the first
release local-first and avoids introducing a backend until real needs exceed the
simple single-user scope. Contracts cover the UI boundaries and the AI estimation
adapter rather than public server endpoints.

Post-design constitution check: PASS

- Personal utility remains explicit: all design artifacts map to a single-user calorie-tracking workflow.
- Simplicity remains intact: there is no backend, auth system, premium system, or speculative analytics platform.
- Vertical slices remain small: onboarding, photo logging, summary, and trends are isolated feature areas with clear contracts.
- Functionality tests remain mandatory: `quickstart.md` and the plan both require unit, integration, and end-to-end coverage for the core flows and clarified edge branches.
- Dependency scope remains justified: each selected dependency reduces implementation complexity for routing, validation, or local persistence.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| None | N/A | N/A |
