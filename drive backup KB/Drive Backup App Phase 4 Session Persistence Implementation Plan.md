---
doc_id: drive-backup-app-phase-4-session-persistence-plan
status: complete
last_updated: 2026-07-18
context_role: implementation-plan
read_when:
  - The agent touches cold-launch authentication, account switching, or Phase 4 entry gates.
  - The user reports a Google chooser during an ordinary app relaunch.
do_not_read_when:
  - The task only concerns local folder scanning after authentication is already established.
depends_on:
  - drive-backup-app-phase-2-auth-implementation-plan
  - drive-backup-app-security-privacy-access
  - drive-backup-app-user-journey-gap-audit
---

# Phase 4 Session Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` or `superpowers:executing-plans` and
> complete each task with a red-green test cycle.

**Goal:** Close UX-01 and UX-02 so an approved user remains signed in through
ordinary relaunch, process death, force-stop, reboot, and in-place upgrade, while
account changes and sign-out remain explicit.

**Architecture:** The app persists only approved account metadata in its
app-private DataStore and treats that record as the local app session. Every
cold load re-evaluates the current build allowlist before unlocking. Google
Drive authorization remains a separate, live `AuthorizationClient` boundary and
must verify the selected Google account before protected Drive operations.

**Tech stack:** Kotlin, Coroutines, Preferences DataStore, Credential Manager,
Jetpack Compose, JUnit, Android instrumented tests, physical Samsung acceptance.

## Decision And Alternatives

Use the durable app-private session.

- Credential Manager automatic sign-in is not sufficient. Google documents
  that multiple authorized accounts disable auto-selection; this phone has
  multiple approved Google accounts and previously showed the chooser on every
  cold process before this plan was implemented.
- A new backend session service would require hosting, server credentials,
  expiry and recovery logic, and ongoing maintenance. It is disproportionate
  for this personal, no-cost application.
- App-private DataStore is protected by the Android application sandbox on an
  unrooted device and is sufficient for the local product gate. A rooted device
  or modified APK can bypass any client-only allowlist, so the Drive ACL remains
  the authoritative data boundary.

The cached session is excluded from Android backup and device transfer. A new
installation or cleared app data requires Google sign-in again. Raw ID, access,
and refresh tokens are never persisted by the app.

## Behavior Contract

1. A valid cached approved account enters `Approved` without invoking Credential
   Manager.
2. A cached account that is no longer in the current build allowlist never
   unlocks protected content. Its local/provider state is cleared and the user
   sees the blocked-account state.
3. Missing or unreadable session state remains signed out or fails closed.
4. `Change account` launches the explicit Google account chooser while retaining
   the current approved account as the cancellation fallback.
5. Cancelling account change leaves the current account and folder mappings
   unchanged.
6. Selecting another approved account atomically replaces the cached account.
7. Selecting a blocked account clears the former approved session and keeps
   protected content inaccessible.
8. Explicit sign-out retains the existing durable local/provider cleanup
   protocol.
9. Home/background/activity recreation does not change authentication state.
10. Drive account removal, revoked Drive grants, and security-required failures
    are detected at the separate live authorization boundary and must require
    repair or reauthentication before a Drive operation.

## Global Constraints

- Do not persist Google tokens or add a backend.
- Do not log email addresses, Google subjects, OAuth IDs, or credentials.
- Do not weaken blocked-account or sign-out cleanup behavior.
- Do not clear folder mappings when switching between the mutually trusted
  configured co-administrator accounts.
- Test both `internal` and `public` flavors.
- Final acceptance uses Android user 0 on the physical Samsung with real Google
  accounts; automated tests may use deterministic adapters.

## Task 1: Durable Cold-Launch Session

**Files:**

- Modify: `app/src/main/java/com/aryasubramani/vijibackup/auth/data/AuthSessionManager.kt`
- Modify: `app/src/main/java/com/aryasubramani/vijibackup/auth/presentation/AuthViewModel.kt`
- Test: `app/src/test/java/com/aryasubramani/vijibackup/auth/data/AuthSessionManagerTest.kt`
- Test: `app/src/test/java/com/aryasubramani/vijibackup/auth/presentation/AuthViewModelTest.kt`

**Interface:** `LoadAuthSessionResult.Approved(account)` means the cached record
passed the current allowlist and may unlock the app without a credential request.
`LoadAuthSessionResult.Blocked(...)` carries independent local/provider cleanup
outcomes for fail-closed UI mapping.

- [x] Write tests proving cached approved, removed-from-allowlist, cleanup
  failure, unreadable, empty, and cancellation behavior.
- [x] Run both flavor unit-test tasks and confirm the new tests fail for the
  intended missing result types.
- [x] Add the minimal load-time allowlist evaluation and map approved/blocked
  results in `AuthViewModel`.
- [x] Run focused tests, then both complete flavor unit suites.
- [x] Commit the independently working cold-launch session slice.

## Task 2: Transactional Account Change

**Files:**

- Modify: `app/src/main/java/com/aryasubramani/vijibackup/auth/presentation/AuthViewModel.kt`
- Modify: `app/src/main/java/com/aryasubramani/vijibackup/app/MainActivity.kt`
- Modify: `app/src/main/java/com/aryasubramani/vijibackup/app/VijiBackupApp.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/aryasubramani/vijibackup/auth/presentation/AuthViewModelTest.kt`
- Test: `app/src/androidTest/java/com/aryasubramani/vijibackup/auth/presentation/AuthGateScreenInstrumentedTest.kt`

**Interface:** `AuthViewModel.changeAccount()` starts one
`GoogleSignInMode.Explicit` operation only from `AuthUiState.Approved`; its
fallback is the exact previous approved state.

- [x] Write tests for launch, duplicate taps, cancel, interruption, approved
  replacement, same-account selection, blocked selection, stale callbacks, and
  sign-out/account-change races.
- [x] Run focused unit and Compose tests and confirm the new cases fail.
- [x] Add the ViewModel command and a visible `Change account` command beside
  the existing sign-out behavior without redesigning the screen.
- [x] Run focused and full two-flavor verification.
- [x] Commit the account-change slice separately.

## Task 3: Live Acceptance And Documentation

**Files:**

- Modify: `drive backup KB/Drive Backup App User Journey Gap Audit.md`
- Modify: `drive backup KB/Drive Backup App Security Privacy And Access.md`
- Modify: `drive backup KB/Drive Backup App Testing Plan.md`
- Modify: `drive backup KB/Drive Backup App Fresh Laptop Setup And Test Runbook.md`
- Modify: `drive backup KB/Drive Backup App Project State.md`
- Modify: `drive backup KB/Drive Backup App Source Register.md`

- [x] Install app/test APKs in place on Android user 0 without clearing data.
- [x] Sign in once, swipe away, relaunch, force-stop/relaunch, reboot/relaunch,
  and install an in-place upgrade. No ordinary case may show a Google chooser.
- [x] Cover `Change account` cancellation, second approved account, same account,
  blocked account, stale callback, and sign-out races in deterministic tests;
  retain the per-user-phone repetition as a release matrix item.
- [x] Explicitly sign out and confirm the next launch remains signed out.
- [x] Scan app logs for email, OAuth ID, token, URI, and private filename leaks.
- [x] Update UX-01/UX-02 and the KB only with evidence actually observed.
- [x] Run the canonical two-flavor build, unit, Android-test APK, and lint matrix.
- [x] Commit docs/evidence separately, push the Phase 4 branch, and open a draft
  PR against `feature/phase-3-folder-health-scan` until Phase 3 merges.

## Completion Evidence

- The physical Samsung reopens the approved public app after force-stop, reboot,
  and in-place APK replacement without launching a Google chooser.
- The approved account remains visible and `Change account` remains explicit.
- The unlocked-device auth-gate and app-composition regression suite passes
  16/16 with zero failures on Android user 0.
- Both flavor unit, APK, Android-test APK, and lint tasks pass.
- Physical removal of a Google account, unavailable Play services, and
  repetition on each intended user's own phone remain release compatibility
  cases; they do not change the ordinary-relaunch contract.

## Exit Gate

- Ordinary relaunch, process death, force-stop, reboot, and in-place upgrade do
  not invoke Credential Manager for an unchanged approved local session.
- Explicit sign-out and blocked-account selection remain fail closed.
- Account change is user initiated and cancellation safe.
- The current approved account remains visible.
- Exact top-level Downloads implementation may begin only after this gate passes
  on the physical Samsung.
