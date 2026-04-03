<!--
Sync Impact Report
Version change: template -> 1.0.0
Modified principles:
- Template Principle 1 -> I. Personal Utility First
- Template Principle 2 -> II. Simplicity Over Cleverness
- Template Principle 3 -> III. Functionality Tests Are Mandatory
- Template Principle 4 -> IV. Deliver Small Vertical Slices
- Template Principle 5 -> V. Keep Documentation Lightweight but Current
Added sections:
- Engineering Constraints
- Delivery Workflow
Removed sections:
- None
Templates requiring updates:
- ✅ .specify/templates/plan-template.md
- ✅ .specify/templates/spec-template.md
- ✅ .specify/templates/tasks-template.md
- ⚠ pending: .specify/templates/commands/*.md (directory not present in this repository)
Follow-up TODOs:
- None
-->
# weight-loss-app Constitution

## Core Principles

### I. Personal Utility First
This project MUST optimize for the needs of a single personal user. Every feature
MUST solve a concrete personal problem in the app and MUST be small enough to
implement, test, and maintain without team-scale process. Work that does not
improve personal usefulness, clarity, or reliability MUST be rejected.

### II. Simplicity Over Cleverness
Solutions MUST favor the simplest design that satisfies the current requirement.
New abstractions, dependencies, and infrastructure MUST have a direct, immediate
benefit. Speculation, premature optimization, and architecture for hypothetical
future scale MUST be avoided because they increase maintenance cost without
improving the personal project outcome.

### III. Functionality Tests Are Mandatory
Every functional change MUST include tests that verify the user-visible behavior
or the core application behavior it changes. Those tests MUST be written before
or alongside implementation, MUST fail when the behavior is absent or broken,
and MUST pass before the work is complete. No feature is done while functionality
tests are missing, failing, or skipped. Rationale: this project values reliable
iteration over speed without proof.

### IV. Deliver Small Vertical Slices
Features MUST be planned and implemented as end-to-end slices that produce usable
progress on their own. Each slice MUST have a clear success condition, a direct
verification path, and minimal cross-feature coupling. Large multi-part rewrites
without an independently valuable checkpoint MUST be split or deferred.

### V. Keep Documentation Lightweight but Current
Documentation MUST stay concise and only cover information needed to understand,
build, test, and maintain the current behavior. When a change affects workflow,
constraints, or verification, the relevant spec-kit artifacts MUST be updated in
the same change. Stale documentation is treated as a defect.

## Engineering Constraints

- Choose familiar tools and straightforward libraries unless a stronger option
  clearly reduces complexity.
- Keep file structure, naming, and interfaces easy to scan for a solo maintainer.
- Add new dependencies only when the implementation and maintenance burden is
  lower than building or keeping the equivalent code locally.
- Prefer reversible changes and localized edits over broad refactors.

## Delivery Workflow

Every feature spec MUST describe the user scenario, the independent test path,
and the measurable definition of done. Every implementation plan MUST pass a
constitution check covering simplicity, slice size, and test strategy. Every
task list MUST include explicit functionality test tasks for each user story or
feature slice. Before completion, the relevant functionality tests MUST be run
and confirmed passing.

## Governance

This constitution overrides conflicting local process guidance for this project.
Amendments MUST be recorded in this file and reflected in affected templates in
the same change. Versioning follows semantic versioning for governance:
MAJOR for incompatible principle changes or removals, MINOR for new principles
or materially expanded obligations, PATCH for clarifications that do not change
expected behavior. Compliance review is required during planning, task creation,
implementation, and final verification; any exception MUST be documented with a
clear reason in the relevant feature artifacts.

**Version**: 1.0.0 | **Ratified**: 2026-04-02 | **Last Amended**: 2026-04-02
