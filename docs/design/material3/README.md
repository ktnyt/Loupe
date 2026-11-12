# Material 3 migration evidence — #92

Observed on 2026-11-12 (JST), Xiaomi 24129PN74G / Android 16, physical 1200×2670, density 520. Installed app: 0.30.0 (163). The selected narrow-screen captures use 320dp / font scale 1.3. Images contain synthetic fixtures, not photograph originals.

## Captures

- [Sharing before / after](share-before-after.png): old 0.29.6 (162) and migrated 0.30.0 (163), same 320dp / font 1.3. The actual sheet surfaces are cropped from the captures; this is a visual comparison, not a pixel-equality requirement.
- [Native controls](controls-320-1.3.png): Material buttons, checked/disabled states, tabs and switches.
- [Gallery](gallery-320-1.3.png) and [tag detail](tag-detail-320-1.3.png): wrapping filters, equal-height selection outlines and visible labels.
- [Over capacity](capacity-over-320-1.3.png) / [within capacity](capacity-fit-320-1.3.png): disabled and enabled sharing actions remain reachable.
- [Preset save](preset-save-display.png) / [viewer Look](viewer-look-display.png): full physical-display dimensions retain app bounds and surrounding margins. Only the top 180px OS status region is blacked out to remove unrelated personal status information. The app UI is unchanged. These are synthetic short-window fixtures, not a claim about photograph quality.

## Validation

The final physical-device suite passed: `system-back` (369dp/font 1.0), `share-capacity`, `gallery-material3`, `share-screen`, `viewer-look-ui`, `tag-detail` (320dp/font 1.3), `material3` (320dp/font 1.0, internally checks four width/font combinations), and `sheets` (369dp/font 1.0, internally checks four portrait width/font combinations). Earlier targeted `sync-controls` and `ranking-result` checks also passed. Test fixtures preserve user data; synchronization was not enabled and no external share was sent.

After the final preset-save label/height correction, `viewer-look-ui` was rerun successfully at 320dp/font 1.3, including actual dialog text width/height, cancel/state preservation, save and viewer reflection, Before/Back/paging, synthetic JPEG dimensions and unchanged synthetic original. Both app variants and the test APK built; both lint tasks passed. Host progress/device-check tests passed (7 + 14 tests).

Independent visual review found no remaining mandatory display corrections in the presented states after the tag outline and preset cancel label fixes. Narrow sharing text still wraps frequently; its meaning and actions remain distinguishable. Independent code and security reviews completed without outstanding in-scope findings.

[Dialog event sample](dialog-events-sample.jsonl) contains actual `dialog.show`, `dialog.first_draw` surface geometry and `dialog.frame_commit` events from the device. Rotation had moved these events to `previous.jsonl`. Request IDs match within each sequence; these timestamps describe synthetic runs and are not a benchmark or a measurement of perceived animation completion.

## Limits

- `gallery-screen` still fails the existing cloud badge pixel-range check tracked in [#80](https://github.com/ktnyt/Loupe/issues/80). `gallery-material3` explicitly excludes that one legacy check; the original mode remains available and unchanged in this respect.
- Spoken output and physical TalkBack interaction were omitted at the user's explicit request. Native accessibility checked/disabled semantics and actions were checked programmatically; that is separate evidence.
- Real-photo optical quality, physical camera import, physical IME behavior, export cancellation and actual posting to external services were not established by these fixtures. The image processing/export engine was not changed by this migration.
- Captures establish only the displayed states. The operation checks separately establish the tested transitions; neither claims exhaustive coverage of every screen.

Detailed attempts, failures, corrections and timing-record limitations are preserved in [issue #92](https://github.com/ktnyt/Loupe/issues/92), run `20261112-material3`. Some start records were added after the corresponding action; they are explicitly marked as retrospective and must not be interpreted as accurate operation durations.
