# Camera and photo-move operation admission

## Purpose

Protect already accepted camera imports and photo moves before their asynchronous service/worker starts. Permit a foreground USB re-detection flow to reserve exclusive admission before the user safely ejects media in Android Settings. Preserve the existing I/O lock and transfer algorithms.

## Contract

- A transfer owns an in-memory, thread-independent admission token before it is submitted to Android or an executor. A failed submission releases it. Non-exported same-process service delivery must transfer ownership without creating a gap or accepting a duplicate/replayed token.
- Re-detection can reserve preparation only when no accepted, queued or executing transfer exists and no I/O operation owns the existing lock. While reserved, new transfers are rejected before acceptance. Preserve WATCH-only monitoring.
- The existing ReentrantLock continues to serialize actual I/O. Do not hold a thread-owned lock across Android lifecycle or external Settings.
- Accepted paired-camera queue continuations retain admission until the queue becomes idle. Duplicate/coalesced/canceled requests cannot leak tokens or close another request's token.
- Release an admission token once on invalid or duplicate delivery, rejected executor, cancellation before worker start, or real terminal worker cleanup. Service/Activity destruction must not release a token early while its worker can still perform I/O. Process death discards in-memory proof and requires a new re-detection attempt.
- Preparation reservation ownership can transfer from UI to the re-detection worker. Canceling the UI cannot close a worker's reservation. Authorization expiry and preparation suppression are separate: expiry cannot automatically start an import while the user is still ejecting media in Settings.
- Cover ImportService, CameraDifferentialService (including direct automatic enqueue and paired continuations), and PhotoMoveService submission. Audit direct I/O-lock callers and preserve their final state checks.

## Verification

Use deterministic pauses before service delivery, before worker lock acquisition, between paired continuations and at UI/worker ownership handoff. Cover startup failure, invalid/duplicate request, rejected executor, queued cancellation, interruption, worker completion and competing reservation. Verify existing host and synthetic Android camera/photo-move regression. This prerequisite changes no user interface or storage content format; actual transfer/re-detection hardware verification belongs to #166.
