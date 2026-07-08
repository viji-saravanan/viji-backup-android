# Viji Backup

Viji Backup is a personal Android app for selected-folder backup to a shared Google Drive folder.

The project is intentionally built in phases. The current phase is the Android foundation: app identity, build variants, a minimal Compose shell, documentation, and verification commands. Backup, Drive, auth, email, and sync features are not implemented yet.

## Project Root

```text
<repository-root>
```

## Knowledge Base

Start with:

```text
drive backup KB/Drive Backup App Index.md
```

For implementation work, also read:

```text
drive backup KB/Drive Backup App Engineering Change Discipline.md
```

## App Identity

- Display name: `Viji Backup`
- Base application ID: `com.aryasubramani.vijibackup`
- Internal application ID: `com.aryasubramani.vijibackup.internal`
- Public application ID: `com.aryasubramani.vijibackup`

## Local Commands

```bash
./gradlew :app:assembleInternalDebug
./gradlew :app:testInternalDebugUnitTest
./gradlew :app:assemblePublicDebug
./gradlew :app:testPublicDebugUnitTest
```

Instrumented tests require an emulator or device:

```bash
./gradlew :app:connectedInternalDebugAndroidTest
```
