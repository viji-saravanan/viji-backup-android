---
doc_id: drive-backup-app-product-requirements
status: active
last_updated: 2026-07-08
context_role: requirements
read_when:
  - The agent needs acceptance criteria, user flows, MVP scope, or non-goals.
do_not_read_when:
  - The task is only release process or source lookup.
---

# Drive Backup App Product Requirements

## Goal

Create an Android app that backs up selected local folders to a shared Google Drive parent folder with manual sync, periodic sync, progress reporting, user gating, failure isolation, and email summaries.

## MVP Requirements

### Account Access

- User signs in with their own Google account.
- App rejects signed-in accounts that are not on the allowlist.
- App never stores Arya's Google account token on another user's device.
- App explains that uploads go to a Drive folder managed by Arya.

### Drive Destination

- Arya owns the parent backup folder in Google Drive.
- Arya shares the parent folder with approved users as Editor.
- User authorizes the app to access the shared folder.
- App creates a per-user and per-device subfolder under the shared parent folder.
- App stores the chosen Drive folder ID locally after setup.

### Local Folder Selection

- User chooses folders from internal storage through Android's folder picker.
- App persists allowed folder access when Android grants it.
- App shows folders that need permission repair.
- App does not claim it can access folders Android blocks.

### User Settings

- User can add, remove, enable, disable, and repair folder mappings.
- User can choose sync frequency from Manual only, Daily, Every 12 hours, and Every 6 hours.
- User can choose Wi-Fi/unmetered only or allow mobile data.
- User can optionally limit mobile data uploads by file size.
- User can choose whether automatic sync should require charging.
- User can choose whether manual sync can override battery/network constraints.
- User can configure completion email recipients and whether Arya also receives summaries.
- User can preview the estimated impact of first sync and large manual syncs before upload starts.
- Settings must be visible enough that a user understands why a sync did or did not run.

### Setup Preflight

- App runs a setup preflight before first sync.
- Preflight checks signed-in account, allowlist status, local folder permissions, Drive destination access, network policy, notification/progress readiness, and email adapter status.
- Failed preflight blocks automatic sync and explains the repair action.
- Manual sync can proceed only when the blocking item is not required for safe upload.

### Sync

- Manual sync can be triggered from the app.
- Manual sync can be cancelled by the user.
- Periodic sync runs under Android background constraints.
- Sync should default to Wi-Fi or unmetered network when available.
- Sync may use mobile data only when the user enables mobile data or confirms a one-time manual override.
- Sync should avoid running when battery is low unless manually triggered.
- Sync must continue after per-file failures.
- Sync must preserve remote files by default when local files disappear.
- Sync verifies upload completion with available Drive metadata such as size or checksum where supported.
- Sync records files skipped by filters, Android access limits, network limits, mobile-data file-size limits, and user cancellation.

### Progress

- App shows active sync state.
- App shows current file, completed files, failed files, skipped files, bytes uploaded, and estimated remaining work when practical.
- App shows last successful sync per folder.
- App shows a retry queue or "needs attention" list.
- App shows a cancel action during active sync.
- App exposes notification progress for long-running sync when Android requires or benefits from foreground visibility.

### Backup Health

- App shows whether each folder is currently protected, stale, blocked, or never synced.
- App warns when no successful sync has completed within the configured stale threshold.
- App shows Drive destination health and quota-related failures.
- App provides a backup completeness report after each sync.
- App reminds the user to run a recovery drill after initial setup and periodically afterward.

### Email Summary

- App attempts to send an email after every sync attempt.
- Email includes sync status, device, Drive destination, folders scanned, files uploaded, files skipped, files failed, failed filenames, and failure reasons.
- Email failure does not fail the sync.
- Email summary is sent to the app user and optionally to Arya if configured.

### Sync History

- App keeps a local history of sync runs.
- History includes start/end time, status, folder results, file failure reasons, and email result.
- History can be exported for diagnostics.

### Restore Readiness

- MVP does not need full one-tap restore.
- MVP must make Drive contents readable from a laptop browser.
- App should include a recovery checklist that explains how to inspect backup contents in Drive.

## Future Requirements

- Optional per-folder private encrypted backup mode.
- Limited old-version archive before overwriting changed remote files, pending retention decision.
- Selective restore.
- Shared folder health checker.
- Remote allowlist update with signature verification.
- Desktop decrypt tool if private encrypted backup is added.
- Public update checker for APK releases.

## Non-Goals

- No paid backend.
- No Play Store requirement.
- No hidden upload to Arya's Drive using Arya's credentials.
- No silent deletion of Drive files.
- No promise of exact periodic sync timing.

## Acceptance Criteria

- A new user can install the APK, sign in, pass allowlist, select a local folder, connect the shared Drive folder, run manual sync, and see files appear in Drive.
- A periodic sync can be scheduled and later observed in sync history.
- If one file cannot be read, other files still upload.
- If Drive quota or network fails, the app records the failure and preserves a retryable queue.
- The completion email lists failed filenames.
- After a test device reset or reinstall, backed-up files remain accessible from Drive.

## Next Notes

- [[Drive Backup App Architecture]]
- [[Drive Backup App Standard Practice Assessment]]
- [[Drive Backup App Settings Model]]
- [[Drive Backup App Sync Semantics]]
- [[Drive Backup App Failure Matrix]]
- [[Drive Backup App Testing Plan]]
