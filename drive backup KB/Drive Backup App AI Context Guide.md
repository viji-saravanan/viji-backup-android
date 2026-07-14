---
doc_id: drive-backup-app-ai-context-guide
status: active
last_updated: 2026-07-08
context_role: retrieval-rules
read_when:
  - A future AI session needs to continue work after compaction or context loss.
  - The agent needs to avoid reading the entire vault.
do_not_read_when:
  - The task is a narrow factual lookup already answered by the loaded note.
---

# Drive Backup App AI Context Guide

This note defines how an AI agent should retrieve context for this project without filling the context window with unrelated notes.

## Core Rule

Load [[Drive Backup App Index]] first, then load only the packet for the current task. For exact feature-level packets, read [[Drive Backup App Context Packets]]. Use backlinks and "Next Notes" sections to expand context only when the task crosses a boundary.

For implementation work, also load [[Drive Backup App Engineering Change Discipline]]. It is the guardrail against one-file patches that ignore surrounding contracts.

## Task Classification

Classify the user's request before reading more notes.

| Request Shape | Primary Packet |
|---|---|
| "What should the app do?" | Product packet |
| "How should this be implemented?" | Architecture packet |
| "What happens when X fails?" | Failure packet |
| "How do we test X?" | Testing packet |
| "Can Android/Drive/Gmail do X?" | Source-backed platform packet |
| "Make a release / GitHub branch / APK" | Release packet |
| "Continue from docs after compaction" | Index plus this guide |

## Source-Backed Claim Rule

The following topics are unstable enough that future agents must verify from source before implementation or before making a hard claim:

- Android background execution and WorkManager timing.
- Android Storage Access Framework folder restrictions.
- Google Drive OAuth scopes.
- Google Drive shared folder upload and ownership behavior.
- Gmail API, SMTP, Apps Script, or email quota behavior.
- GitHub release visibility and repository access behavior.
- Android signing, app install, and Play Protect behavior.

Use [[Drive Backup App Source Register]] to find the relevant official source. If the source is older than the current implementation date or the exact behavior matters, browse the official documentation again.

## Skeptical Implementation Rule

Future agents must actively challenge their own plan before implementing. For each implementation task, identify:

- the most likely failure mode;
- the worst user-visible failure mode;
- the security or privacy failure mode;
- the state-recovery failure mode after process death, reboot, or reinstall;
- the test that would catch each failure.

Agents must also identify the blast radius of every edited file. A change is not complete until the agent has checked the surrounding callers, dependencies, manifests/config, persistence contracts, UI state, docs, and tests that apply to the touched area.

If any of those cannot be answered from loaded context, ask the user or update [[Drive Backup App Open Questions And Assumptions]] before proceeding.

## Context Budget Rules

- Read notes by purpose, not by filename count.
- Prefer the latest compact decision note over long prior discussion.
- Never paste entire source docs into implementation context.
- When implementing a feature, load requirements, architecture, failure cases, and tests for that feature.
- When writing tests, load the feature requirements plus failure matrix. Do not load GitHub release docs unless release behavior is under test.
- When changing security, auth, Drive access, or email, load the source register and verify current platform docs.

## Retrieval Workflow

1. Read [[Drive Backup App Index]].
2. Identify the exact work item.
3. Load the smallest context packet from the index table or [[Drive Backup App Context Packets]].
4. If editing implementation files, load [[Drive Backup App Engineering Change Discipline]].
5. Extract constraints into a local working summary.
6. Check [[Drive Backup App Open Questions And Assumptions]] for unresolved decisions.
7. If implementation touches a platform boundary, load [[Drive Backup App Source Register]] and verify the official doc.
8. Before finishing, compare the change against [[Drive Backup App Testing Plan]] and [[Drive Backup App Failure Matrix]].
9. Confirm the test set includes negative, edge, cancellation, interruption, and persistence cases where relevant.

## Anti-Hallucination Checklist

Before proposing or coding behavior, answer:

- Which note is the product authority for this behavior?
- Which note defines the failure behavior?
- Which note defines the tests?
- Which files/contracts are in the blast radius of this edit?
- Is this a platform claim that needs source verification?
- Is this MVP behavior or future behavior?
- Does the change preserve "one file failure must not stop the whole sync"?
- What negative test would fail if this implementation is wrong?
- What state survives app restart, process death, reboot, and revoked permission?

## Next Notes

- [[Drive Backup App Index]]
- [[Drive Backup App Context Packets]]
- [[Drive Backup App Engineering Change Discipline]]
- [[Drive Backup App Source Register]]
- [[Drive Backup App Open Questions And Assumptions]]
