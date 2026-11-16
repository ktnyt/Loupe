# Android import progress captures

Synthetic singleton fixtures on dedicated emulator5580, source14c8d96. No physical card or photo transfer.

- `before-4800-320-1.3.png`: published178 count-only176/4800 display.
- `320-1.3-history-4800.png`:4800 history checks, not a save plan.
- `320-1.3-known-4800-zero-saved.png`:4800 known and zero newly saved.
- `320-1.3-retry-wait.png`: operation stays active while awaiting reconnection.
- `400-1.0-content-two.png`: two content candidates, not two promised new saves.

Native assertions also covered listing, cancellation, failure, cumulative retry success, shared tab accessibility labels and selection lock. Independent visual review inspected all18 after captures at320dp/font1.3 and400dp/font1.0. No material visual findings in those fixtures. These cropped app captures do not establish OS-bar safety, TalkBack behavior, measured touch targets or physical SD performance. See issue128 for operation records and remaining physical checks.
