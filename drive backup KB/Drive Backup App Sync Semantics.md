---
doc_id: drive-backup-app-sync-semantics
status: draft
last_updated: 2026-07-08
context_role: sync-rules
read_when:
  - The agent needs file comparison, deletion, conflict, progress, or retry behavior.
do_not_read_when:
  - The task is only GitHub release setup.
---

# Drive Backup App Sync Semantics

## Sync Objective

Each selected local folder should eventually have a corresponding Drive folder containing the latest uploadable version of each local file, while preserving earlier remote files when local state is ambiguous.

## Sync Direction

MVP is one-way local-to-Drive backup.

No Drive-to-local mirroring in MVP.

## Folder Mapping

Each mapping has:

- local folder display name;
- local folder persisted URI;
- Drive destination folder ID;
- privacy mode;
- sync constraints;
- last successful sync timestamp.

## User-Controlled Settings

Sync behavior must come from explicit settings, not hidden defaults. See [[Drive Backup App Settings Model]] for the full settings contract.

The sync engine should receive an effective settings snapshot at the start of each run. That snapshot should be written to sync history.

## Privacy Modes

MVP supports only normal backup:

- Files upload as normal readable Drive files.
- Files can be opened from a laptop browser.
- Drive previews and search may work depending on file type.

Future private backup:

- Files are encrypted before upload.
- Drive sees encrypted blobs.
- Restore requires app or decrypt tool plus recovery key.

## File Identity

Use a layered identity strategy:

1. Local folder mapping ID.
2. Relative path inside the selected folder.
3. Local metadata such as size and modified time.
4. Optional content hash when practical.
5. Stored Drive file ID after upload.

Do not rely on filename alone.

## Upload Rules

- Upload new local files.
- Upload changed local files.
- Skip unchanged files.
- Resume upload if an existing resumable session is valid.
- Restart upload if resumable session expired.
- Record every skipped, uploaded, failed, and deferred file.
- Upload over mobile data only when the effective settings allow it or the user confirms a manual one-time override.
- Verify the uploaded file with available Drive metadata such as size or checksum when supported.
- Mark verification mismatch as a file failure and keep other files moving.

## Preview Rules

- First sync should offer a preview before upload starts.
- Preview should estimate folder count, file count, total bytes, mobile-data impact, blocked folders, and likely skipped files.
- Preview must not modify Drive.
- Preview estimates can be incomplete when Android does not expose metadata cheaply; label estimates as such.

## Filter Rules

- MVP must report skipped files clearly.
- Basic user exclusions can be added after core sync is stable.
- Full include/exclude pattern language is future scope.
- Filtered files count as skipped, not failed.

## Delete Rules

MVP never auto-deletes remote files.

If a local file disappears:

- mark it as missing locally;
- leave the Drive copy untouched;
- include it in diagnostics if relevant.

Future cleanup can add explicit archive or delete behavior, but it must be opt-in.

## Version Retention Rules

Current decision: unresolved.

Recommended direction: before overwriting a changed remote file, preserve a limited old-version archive such as last 3 versions or 30 days, whichever is smaller.

Reason: backup systems should protect against accidental overwrite with corrupt or unwanted local changes.

Trade-off: version retention uses additional Drive storage and needs quota-aware cleanup.

## Conflict Rules

If Drive has a file at the same relative path but the local ledger cannot prove it is the same file:

- do not overwrite silently;
- create a conflict record;
- prefer preserving both files;
- require user-visible resolution if the conflict affects restore correctness.

## Progress Rules

Progress should be durable enough to survive activity recreation and app process death.

Progress should report:

- run status;
- current folder;
- current file;
- completed count;
- skipped count;
- failed count;
- uploaded bytes;
- total discovered bytes when known;
- queued retry count.

## Scheduling Rules

- Manual sync starts a one-time sync request.
- Periodic sync uses unique scheduled work.
- Manual sync should not create duplicate concurrent uploads for the same mapping.
- If a periodic sync is already running, manual sync should either attach to it or queue after it.
- User-selected frequency controls how periodic sync is scheduled, but Android still controls exact execution timing.
- Manual only disables periodic scheduling but keeps the manual sync button available.
- Daily, Every 12 hours, and Every 6 hours are MVP frequency choices.
- Wi-Fi/unmetered only is the default network policy.
- Allow mobile data permits scheduled and manual uploads on mobile data unless a per-file mobile data size limit blocks a file.

## Cancellation Rules

- Active sync UI must expose Cancel.
- Cancel should stop new uploads from starting as soon as practical.
- If a file upload cannot be interrupted safely, finish or abort it according to the upload API's safe behavior, then record the result.
- Cancellation must preserve completed file records.
- Cancellation must preserve retry state for incomplete files.
- Cancelled sync is a terminal run state and should still write sync history.
- Cancelled sync should attempt an email summary if email settings require summaries for cancelled or failed runs.

## Completion Rules

A sync run completes as:

- Success: all eligible files uploaded or already current.
- Partial Success: at least one file failed, but other files completed.
- Failed: no meaningful sync could run due to auth, Drive destination, or folder access failure.
- Cancelled: user cancelled or system interrupted before completion.

Every terminal state should write sync history and attempt email notification.

## Verification And Health Rules

- Each sync run should produce a completeness report.
- Completeness report should include scanned folders, uploadable files, uploaded files, unchanged files, skipped files, failed files, blocked folders, and verification failures.
- Health dashboard should derive from sync history, retry queue, folder permissions, Drive destination status, and stale thresholds.
- Recovery drill reminders should be visible until the user confirms a successful manual Drive inspection.

## Next Notes

- [[Drive Backup App Failure Matrix]]
- [[Drive Backup App Testing Plan]]
- [[Drive Backup App Product Requirements]]
- [[Drive Backup App Settings Model]]
- [[Drive Backup App Standard Practice Assessment]]
