# SD card re-detection before Android card-to-card import

## Purpose and scope

Import original photos from a source SD card to the selected microSD card in the same USB reader when Android retains stale media state after a card swap. Re-detect the reader before using the existing mounted-source importer. Source originals remain read-only. Re-detection does not copy photos or select a different destination.

This contract extends [card-to-card import](dual-slot-import-contract.md), [source swap](sd-source-swap-contract.md), [listing](sd-reinsert-listing-contract.md), and [Material 3 UI](material3-ui.md). It replaces the assumption that Android already observes every card change; original-byte reads, SHA verification, durable receipts, cancellation, and destination identity requirements remain authoritative.

## Requirements

1. Offer an explicit re-detection action on the import screen. Use public Android USB APIs without Shizuku, shell commands, hidden APIs, USB resets, or raw filesystem writes.
2. Re-detection is exclusive with import and photo movement, including queued and automatically started operations. A running import must never be interrupted to re-detect a card.
3. Force-claiming a mounted writable volume is forbidden. Require positive evidence that the selected destination and every visible removable volume sharing the reader have been safely ejected. A missing volume, failed query, denied permission, unknown topology, checking state, or read-only mount is not evidence of safe ejection. The app cannot silently eject volumes using a SAF tree grant: root ejection requires a different URI permission.
4. When safe ejection is needed, explain that both cards must be safely removed in Android while remaining physically connected. Open Android storage settings. On returning, re-read actual states; returning from settings or dismissing the dialog does not authorize the claim.
5. Bind an attempt to the same selected destination, exact USB device connection, and intended source. Re-check before claiming and after restoring Android access. A target/source change, extra reader, permission loss, cancellation, or unknown state fails closed.
6. Request Android USB permission only from a foreground user flow. Claim the single supported mass-storage interface, release it, and close the connection in finally. Send no storage data or SCSI commands. Claim/release success alone is not recovery success.
7. Wait for Android to remount the same destination and a distinct source. Check destination writability and rediscover current source/grants. A new/ungranted source requires source selection; never reuse a previous card's listing or automatically change the saved source. Bound the wait, expose failure/retry, and do not spin/reclaim repeatedly.
8. Suppress import starts caused by the attempt's own unmount/mount events. Recovering media state must not bypass format/source confirmation. Refresh the existing importer when the attempt ends; the existing explicit start and approved automatic-import contracts govern subsequent copies.
9. Observe public media/storage and USB events and re-check on resume. When a card-change event is observed, invalidate stale listing/generation and schedule at most one re-detection only if safe-ejection evidence, exact connection/target, USB permission, idle state and foreground lifecycle are satisfied. Otherwise expose the action/preparation state. An OS that reports neither an event nor a changed state cannot be assumed to have detected a physical card swap; do not periodically detach a mounted reader to guess.
10. Preserve camera/MTP import, internal-storage USB reads, legacy history and existing photos. Remove only code made unreachable by this implementation, with reference checks. Do not restore the closed raw-write or Shizuku implementation.

## UI and lifecycle

The import screen retains its existing structure. Add a Material button for re-detection and a compact state message/progress indicator. Distinguish safely ejecting cards, waiting for USB permission, re-detecting, waiting for Android, source selection required, ready, busy and failed states with text and accessibility names, not color alone. Settings guidance uses a Material dialog with explicit open-settings and cancel actions. No automatic claim follows a canceled or abandoned user flow. Rotation/back/stop/destroy must not leak receivers, retain an Activity from background work, authorize a stale USB permission callback, or start a second attempt. Always release a claimed connection even if the view is gone. Preserve chosen destination and provide explicit retry after failure.

## Acceptance and evidence

- Host tests cover safe/unsafe/unknown volume states, exact USB identity and target, contention, cancellation and errors at every USB step, cleanup, timeout, own-event suppression, duplicate events, lifecycle cancellation and changed/ungranted source.
- Synthetic Android checks cover supported/unsupported/busy/ejection-needed/permission-denied/waiting/timeout/ready states and settings return without side effects. Capture 400dp/font1.0 and 320dp/font1.3 with an independent UI review.
- Physical checks wait until the user's current transfer has ended. Use a separate test application or the documented recovery gate. Confirm Android's system-UI safe-ejection states and public-API claim/release/remount without ADB unmount or Shizuku. Confirm source/destination identities and keep existing originals. Transfer a bounded approved fixture and read back destination bytes before claiming production transfer is verified.
- Exercise physical source-card change if the user is available to swap it; otherwise mark actual insertion/removal and unattended reliability unverified. Never claim a synthetic broadcast proves hardware detection.
- Review independently for correctness and security. Merge through normal CI and deliver a clean-main APK through the existing deployment gate. Installation, readback and remaining physical checks are separate from implementation completion.

## API evidence

- [USB exclusive claim and release](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection).
- [Android 16 DocumentsProvider root-URI ejection permission](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/provider/DocumentsProvider.java).
- [Storage volume callback](https://developer.android.com/reference/android/os/storage/StorageManager.StorageVolumeCallback).
