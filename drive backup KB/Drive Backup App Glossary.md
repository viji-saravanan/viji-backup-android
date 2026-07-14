---
doc_id: drive-backup-app-glossary
status: active
last_updated: 2026-07-08
context_role: glossary
read_when:
  - The agent needs terminology or local meaning of project terms.
do_not_read_when:
  - The task already defines all terms in context.
---

# Drive Backup App Glossary

Canonical project language also lives in [[CONTEXT]]. Keep this note and `CONTEXT.md` aligned when terms change.

## Allowlist

The list of Google account emails permitted to use the app after sign-in.

## Arya Shared Backup Folder

The Google Drive parent folder owned by Arya and shared with approved users as Editor.

## Drive Destination

The Drive folder selected or authorized as the upload target for a device's backups.

## Folder Mapping

A configuration that links one local Android folder to one Drive destination folder.

## Install ID

A generated identifier for one app installation on one device. Used to avoid collisions between devices with the same display name.

## Knowledge Base

The project documentation vault under `drive backup KB/`. `KB` is shorthand for knowledge base.

## Manual Sync

A user-triggered one-time sync run that starts from the app UI.

## Normal Backup

MVP privacy mode where files are uploaded to Drive as normal readable files.

## Partial Success

A sync run where at least one file failed but other eligible files completed.

## Private Backup

Future privacy mode where files are encrypted before upload and require app/tool plus recovery key to restore.

## Recovery Drill

A test that confirms backed-up files remain accessible after device reset, app reinstall, or account reconnection.

## Sync Ledger

Local durable record of file state, Drive file IDs, upload state, retries, and per-file results.

## Next Notes

- [[Drive Backup App Index]]
- [[Drive Backup App Sync Semantics]]
