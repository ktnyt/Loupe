# Selection count evidence (#115)

These are historical verification captures, not the current visual authority.
Use `docs/material3-ui.md` and the issue contract for future UI work.

- Environment: emulator-5580, Android app 0.31.10 (174), generated three-JPEG fixture and isolated ranking preferences.
- `before-400.png` / `after-400.png`: 400dp, font scale 1.0.
- `before-320.png` / `after-320.png`: 320dp, font scale 1.3.
- Before images show the initial comparison. After images show the next comparison following one A selection: a new A has count 0, and the previous winner is now B with count 1. Thus the photo identity, rather than the A/B slot, owns the count.
- Source: PixelCopy of the Android content root; these are not full-display or physical-device captures.
- Operations asserted at both sizes: initial 0/0, choose A -> 0/1, undo -> 0/0, choose B -> 0/1, undo -> 0/0, choose A, return to gallery, resume -> 0/1. Existing mode/save-failure/result/re-sort/zoom/scroll checks also passed.
- Host tests cover both sorting modes, legacy journals, ties, undo, resume, reset, unknown IDs and tied-group representatives.
- Independent UI review found no blocking visual defect in these states.
- Not verified: extremely narrow-photo badge fallback, physical touch bounds/feedback, OS-bar safety, TalkBack speech/focus, physical-device behavior. No physical device was connected; merge and main installation remain pending.

Counts describe choices in the current persisted comparison journal. Direct winner/co-winner assignment can generate that journal, so counts after undoing such an assignment may include generated choices; they do not audit historical user taps. Saved data and ranking behavior remain unchanged.

See https://github.com/ktnyt/Loupe/issues/115 for contracts, progress, review findings and limitations.
