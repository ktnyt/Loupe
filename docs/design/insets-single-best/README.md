# System bars and singleton best — #112

The current contract is [Material 3 UI](../../material3-ui.md#expressive-photo-workflow-112). The correction preserves #109's singleton store and ranking semantics; it restores the visible and tappable header.

## Reproduced public-release failure

The baseline used the actual signed `v0.31.9` APK (versionCode 173, source `61ad123bacdf78f486af9387b803a0a4d5f431f6`, SHA-256 `e28d59524105e280dab154fbc574e7af57782dacbf520ab698a0cceac2109130`). On the dedicated API 35 emulator, after rendering a replacement MainActivity root, actual OS safe insets were top 136px / bottom 63px while root padding was zero. The singleton star existed at y=0..126px, entirely behind the status area; Look occupied y=126..252px. The reported Look-only appearance and overlapping gallery header were therefore reproduced without relying on a source-only inference.

`before-album-gallery-320-1.3.png` and `before-viewer-baseline-320-1.3.png` are composed full-display captures made before the failing safe-bounds assertion. The viewer was opened through a safe native grid pointer tap. No pointer was injected at the hidden star.

## Post-fix evidence

The `after-*-320-1.3.png` and `after-*-400-1.0.png` files show generated photographs at constrained portrait Activity widths on the same 1080×2400 emulator display. They include the OS status and gesture bars rather than cropping them away. Filenames record Activity width and font scale, not physical phone dimensions.

`system-insets` passes at both sizes. It checks screen-space containment and ancestor clipping after album-scope/all-photos/selection/Back/viewer rerenders. It requires native bottom navigation in the all-photos case, checks Look and viewer primary actions, and uses actual pointer events to open a singleton and mark/unmark it. An isolated RankingStore is read back after writes and viewer reentry. Burst members retain their separate ranking behavior.

The observed root padding is top 136px / bottom 63px after each rerender. The OS also reports a top display-cutout inset of 136px, equal to the status inset. This is not evidence for a cutout taller than the status area or for every device geometry.

Existing `single-best`, `expressive-ui`, and `viewer-screen` checks also pass at 320dp/font1.3, including persistence failure/retry, filters, manual/AI entry without submission, actual zoom/pinch/swipe, and relevant native AX attributes. App/test builds, app lint and device-check host tests pass. Commands and attempts are recorded on [issue #112](https://github.com/ktnyt/Loupe/issues/112) under `20261114-insets-single-best`.

## Limits

All committed images use cache-only generated photographs. The user's supplied screenshot and photo originals are not included. The initial Activity may run its existing browsing-index startup; this fixture does not create associations in the user's default ranking/tag stores.

The album-scope transition seeds synthetic gallery state and calls the native render boundary; it does not establish an album-cell pointer journey against a populated real album index. Physical hardware, OTA installation/readback, spoken TalkBack, older Android versions, landscape, larger cutouts, and real camera/AI/share processing remain unverified. Static screenshots do not independently establish those operations.
