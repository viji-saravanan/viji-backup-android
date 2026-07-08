---
doc_id: drive-backup-app-project-state
status: active
last_updated: 2026-07-08
context_role: current-state
read_when:
  - The agent needs to understand the current local scaffold before implementation.
  - The agent is about to start a roadmap phase.
do_not_read_when:
  - The task is a pure product decision with no local repo impact.
---

# Drive Backup App Project State

## Workspace

Project root:

```text
<repository-root>
```

Knowledge base:

```text
<repository-root>/drive backup KB
```

## Current Scaffold

The root contains a Phase 1 Android/Gradle scaffold for `Viji Backup`.

Known files:

- `settings.gradle.kts`
- `build.gradle.kts`
- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `app/src/main/AndroidManifest.xml`
- minimal Compose shell
- app identity unit test
- app context instrumented smoke test
- `README.md`

## Current Gaps

- Root is a git repository on branch `setup/phase-1-foundation`.
- Git account switcher profiles verified: `arya-personal` maps to `callmearya` with `owner.primary@example.test`; `viji` maps to `viji-saravanan` with `alternate.user@example.test`.
- Current workflow intentionally splits commits between Arya personal and Viji. Never commit from Arya work.
- Project name is now `Viji Backup`.
- Base application ID is now `com.aryasubramani.vijibackup`.
- Namespace is now `com.aryasubramani.vijibackup`.
- Minimal Compose app shell exists.
- Default example tests have been replaced with app identity smoke tests.
- AGP 9 built-in Kotlin support is used; the old Kotlin Android plugin is intentionally not declared.
- No CI is configured.
- No signing/release setup exists.
- Android SDK platform `android-36.1` is installed locally; API 37 is not installed.
- Lint reports freshness warnings for target SDK, compile SDK, Core KTX, Gradle wrapper, and Compose compiler versions. These are documented in [[Drive Backup App Foundation Decisions]] and should be resolved deliberately in a future SDK/tooling update.

## Current Passing Checks

```bash
./gradlew :app:assembleInternalDebug
./gradlew :app:testInternalDebugUnitTest
./gradlew :app:assemblePublicDebug
./gradlew :app:testPublicDebugUnitTest
./gradlew :app:assembleInternalDebugAndroidTest
./gradlew :app:lintInternalDebug
```

## Immediate Setup Goal

Before feature work, complete roadmap Phase 1: Android Project Setup.

Minimum Phase 1 output:

- keep git work on a branch, not `dev` or `main`;
- app name and package/application ID are chosen;
- repair or reinstall required Android SDK components;
- make the scaffold build cleanly;
- make smoke tests pass;
- add root README or project overview;
- document local build/test commands;
- establish branch workflow before any pushes.

## Next Notes

- [[Drive Backup App Implementation Roadmap]]
- [[Drive Backup App Foundation Decisions]]
- [[Drive Backup App GitHub And Release Workflow]]
- [[Drive Backup App Testing Plan]]
- [[Drive Backup App Context Packets]]
