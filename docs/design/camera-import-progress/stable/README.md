# Stable per-photo progress

Synthetic Android display captures including OS bars. Source a5aa6ab; dedicated emulator5580.

Before: CONTENT and SAVING change the heading, detail rows and progress mode for each photo.
After: both events retain the same heading, counter fields, denominator and cancel position.
The320dp/font1.3 pair uses identical1/20 processed,1 saved,4800 known and4 KiB read counters.
The400dp/font1.0 display is also retained.

The same native heading-stability assertion fails before the change and passes afterward.
The regression covers20 alternating events, actual counter labels, progress mode and denominator;
existing cancellation/retry/zero-save/selection checks also pass. Independent visual review inspected
all4 before and8 after full/cropped captures. No actionable visual finding in this fixture scope.
No physical SD, actual photo transfer, measured touch targets or TalkBack result is implied.
