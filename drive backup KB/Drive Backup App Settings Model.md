---
doc_id: drive-backup-app-settings-model
status: active
last_updated: 2026-07-08
context_role: settings-controls
read_when:
  - The agent works on user settings, sync frequency, folder mappings, network choices, battery choices, cancellation, or defaults.
do_not_read_when:
  - The task is only Drive OAuth or GitHub release visibility.
---

# Drive Backup App Settings Model

The app must expose settings for the decisions users reasonably need to control. Defaults should be conservative, but the user can override them.

## Settings Principles

- Settings must be understandable without reading docs.
- Dangerous settings need clear consequences.
- Defaults should prevent surprise mobile data usage, battery drain, and accidental remote deletion.
- A user setting must not weaken account gating or token security.
- Any setting that affects sync behavior must appear in sync history for that run.

## Required MVP Settings

### Account

- Signed-in Google account.
- Sign out.
- Allowlist status.
- Re-check access.

### Drive Destination

- Connected shared Drive folder.
- Folder health check.
- Reconnect or change destination.
- Display the resolved per-user/per-device Drive path.

### Folder Mappings

- Add folder.
- Remove folder from future sync.
- Repair folder permission.
- Rename local display label.
- View mapped Drive folder.
- Enable or disable individual folder mapping.

Removing a folder mapping must not delete Drive files.

### Sync Frequency

MVP should offer simple choices:

- Manual only.
- Daily.
- Every 12 hours.
- Every 6 hours.

Do not offer intervals that imply exact timing. Label periodic sync as approximate because Android controls final execution timing.

Default: Daily.

### Network

MVP should offer:

- Wi-Fi/unmetered only.
- Allow mobile data.
- Allow mobile data only for files under a user-selected size limit.

Default: Wi-Fi/unmetered only.

If mobile data is enabled, the app should show a clear warning that uploads may use significant data.

### Battery And Charging

MVP should offer:

- Avoid sync when battery is low.
- Sync only while charging.
- Allow manual sync override even when battery is low.

Default: avoid sync when battery is low; charging-only disabled.

### Manual Sync Controls

- Sync all folders now.
- Sync one folder now.
- Preview first sync or large manual sync before upload.
- Cancel current sync.
- Retry failed files.

Manual sync should explain when it will use mobile data based on current settings.

### Email Notifications

- Email recipient for the app user.
- Optional Arya recipient.
- Include failed filenames.
- Send summary on success.
- Send summary on partial success.
- Send summary on failure.

Default: include failed filenames and send after every sync attempt.

### Privacy Mode

MVP setting is informational only:

- Normal backup: readable Drive files.

Future setting:

- Private backup: encrypted files, requires app/tool and recovery key.

Private backup must not be selectable until restore/decrypt tooling exists.

### Backup Health

- Stale backup threshold.
- Recovery drill reminder.
- Show unprotected folders.
- Show pending retry count.
- Show last verified upload time.

Default stale threshold: 48 hours after the expected schedule window.

### Filters And Exclusions

MVP should support a simple skipped-file report and basic exclude controls.

Initial exclude behavior:

- show files skipped because Android cannot read them;
- show files skipped because mobile data size limit blocked them;
- allow simple user-configured filename or extension exclusions after core sync is stable.

Do not add a full pattern language until the basic sync engine is reliable.

## Advanced Settings

These can be added after MVP:

- Per-folder frequency.
- Per-folder network policy.
- Per-folder mobile data file-size limit.
- Full exclude file patterns.
- Include only file patterns.
- Retention/version policy.
- Pause all automatic sync.
- Export diagnostics.
- Clear local sync history after export.

## Settings Persistence

Settings must survive app restart and device reboot where Android permits. Changes should apply to the next sync run unless the user explicitly restarts the active sync.

## Settings History

Each sync run should record the effective settings used:

- frequency source;
- network policy;
- battery policy;
- selected folder mappings;
- privacy mode;
- email notification options.

This prevents later confusion when a user asks why a sync did or did not upload on mobile data.

## Next Notes

- [[Drive Backup App Product Requirements]]
- [[Drive Backup App Sync Semantics]]
- [[Drive Backup App Testing Plan]]
- [[Drive Backup App Failure Matrix]]
