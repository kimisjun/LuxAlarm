# Warmly Solo Implementation Plan

> **For Hermes:** UI/UX evidence and sketches are approved before production Compose implementation. Execute each code task with RED→GREEN→REFACTOR and two-stage review.

**Goal:** Deliver a Galaxy-ready solo gentle-wake app centered on one sleep plan and a local wake playlist.

**Architecture:** Keep Room as the single source of truth and `AlarmManager` registrations as rebuildable projections. Use a short receiver, foreground playback service, full-screen wake UI, and local app-owned audio copies. No legacy migration or multi-scheduler ownership layer.

**Tech Stack:** Kotlin, Jetpack Compose, Room, AlarmManager, foreground service, Media3/Android audio APIs as already available in the project.

---

## Phase A — UI/UX before production code

1. Research official/store flows for alarm onboarding, sleep scheduling, and playlist editing.
2. Create two interactive mobile HTML variants covering onboarding → wake time → bedtime recommendations → playlist → confirmation → home.
3. Verify both at narrow mobile width and large text.
4. Compare them and select one design language before writing Compose UI.

## Phase B — First vertical slice

1. RED: tests for exactly three bedtime recommendations, next-day rollover, and direct custom bedtime.
2. GREEN: minimal pure `SleepPlanDraft` and recommendation function.
3. RED: Compose test for the onboarding wake-time screen and three recommendations.
4. GREEN: minimal localized screen following the selected sketch.
5. Persist one draft/plan and render the saved home summary.

## Phase C — Local playlist

1. RED: playlist creation, ordered add/remove/reorder, duplicate policy, and missing-track fallback tests.
2. Replace the one-file audio store with app-owned immutable track files and metadata.
3. Build playlist list/editor using the researched familiar patterns.
4. Add sequential repeat playback and fallback to the bundled/default alarm sound.

## Phase D — Real wake execution

1. Schedule ramp start and target-time backup for the single plan.
2. Validate immutable plan/occurrence identity in the receiver.
3. Start and promote the playback service before media work.
4. Drive screen brightness, per-player volume, and vibration from the existing ramp model.
5. Make `일어났어요` idempotently stop all effects.
6. Rebuild schedules after boot, time/timezone change, package update, and plan edits.

## Phase E — Verification and private use

1. Full unit/UI tests, lint, release compile, Spotless, APK build, and checksum.
2. Galaxy canary: lock, process kill, reboot, Doze, permission denial/regrant, missing track, duplicate delivery.
3. Multi-night private use by 은준; record missed/late alarms and timing evidence.
4. Remove unused features before adding Phase 2 relationship sharing.
