# Material 3 UI contract

This document is the current visual and interaction contract for Loupe UI work. Issue-specific behavioral contracts remain authoritative for the behavior they define. Historical HTML prototypes and captures under `docs/design/` are retained as records; they are not current visual authority. Do not require Claude, an exported HTML prototype, or pixel equality with historical artifacts.

## Platform and components

Loupe uses Android Views with Java, Material Components for Android 1.13.0, a dark Material 3 theme, and `AppCompatActivity`. Use native Material 3 components for standard controls where they fit the existing purpose and interaction contract: `MaterialButton`, `BottomNavigationView`, `MaterialSwitch`, and `MaterialAlertDialogBuilder`. Keep custom drawing for photo overlays, tone curves, and other controls whose direct manipulation is part of the existing behavior.

Use Material 3 component states, shape, typography, color roles, elevation, touch targets, and accessibility behavior. Custom-drawn controls must provide equivalent state feedback and accessible names/actions where applicable. Respect system font scaling, window insets, and dark theme.

## UI design and review

Before implementation, update this document or the issue's current contract with the concrete affected screens, states, interactions, layout constraints, and accessibility expectations. Preserve existing behavior and unaffected screen structure; do not introduce a general product redesign as part of a focused migration.

Capture the current Android UI and the changed Android UI for the same screen states and relevant viewport/font-scale conditions. Review component usage, hierarchy, spacing, legibility, state feedback, touch targets, navigation, and accessibility. Use captures as evidence of the observed implementation, not as a claim of pixel equality to HTML or an unrelated mock. Use operation records or video for behavior that a still image cannot establish.

A reviewer independent from the implementation evaluates the UI and UX from the issue purpose, current contract, captures, and operation evidence. Record findings, fixes, and unverified areas. Keep device review, cold review, security review, and issue progress gates in force.

## Behavior and accessibility

Material 3 migration changes visual components and their native interaction states; it does not silently change photo selection, save/export, cancellation, retry, navigation, persistence, or other issue-defined behavior. Preserve these contracts and verify affected transitions. Preserve content descriptions, focus order, keyboard/AX actions, and state announcements. Test TalkBack when a change affects accessibility semantics, and state clearly when physical-device or audio checks are unavailable.

## Migration scope and guarantees (#92)

| Screen/control | Native implementation | Behavior retained |
| --- | --- | --- |
| Library destinations | `BottomNavigationView` | Photo/album/import destinations, selection lock, existing scroll/filter restoration |
| Gallery, ranking, tags, settings and viewer actions | `MaterialButton` | Existing callback, content description, cancellation and retry |
| Share format and rank filters | Checkable `MaterialButton` | Caller owns committed selection; capacity can disable each format |
| Best-only sharing and sync settings | `MaterialSwitch` | Existing checked listener and persisted state |
| Alerts and tag creation | `MaterialAlertDialogBuilder` | Confirm/cancel, validation and retry |
| Modal share and menu sheets | `BottomSheetDialog` | Reachable wrapping/scrolling content, cancel/back/outside dismissal rules |
| RAW editor | `AppCompatActivity`, Material actions and alerts | RAW processing, curves, direct manipulation and nondestructive save |

The fixed dark scheme uses primary `#ADC6FF` / on-primary `#002E69`, surface `#111318`, container `#1D2026`, on-surface `#E2E2E9` and on-surface-variant `#C4C6D0`. Dynamic wallpaper colors are not enabled, so the chrome around a photograph stays stable. Standard controls keep Material-managed backgrounds and ripple/disabled states. Standard actions have at least 48dp touch height; labels wrap rather than shrink when space is limited. Specialized curve banks and nonmodal photo-adjustment overlays preserve their direct-manipulation layout; verify those hit regions separately.

Before merge, check photo/album navigation, selection and best-only sharing, format capacity/disabled state, ranking, tags, synchronization toggles and RAW save/cancel. Verify 320dp and the device's normal width with font scale 1.0 and 1.3. Synthetic component captures and emulator operation are useful evidence but do not replace physical-device behavior and independent UI review.

Sources: [Material Components 1.13.0 getting started](https://github.com/material-components/material-components-android/blob/1.13.0/docs/getting-started.md), [1.13.0 release](https://github.com/material-components/material-components-android/releases/tag/1.13.0).

The RAW adjustment sliders remain specialized `AppCompatSeekBar` subclasses: they implement photo pinch forwarding, cancel-time value restoration, double-tap reset, keyboard Delete reset or custom tick rendering. These gesture contracts are retained rather than replaced by a standard `Slider` without equivalent behavior. Nonmodal look-preview overlays also keep their bounded height and undimmed photograph; their actions and switches use Material components.

Modal window decor includes the full-screen scrim. Measure `design_bottom_sheet` as the actual surface, bounded by the configured available width and 640dp; alerts use their native centered geometry. Material entrance/exit motion is expected. Preserve stable placement after entrance rather than requiring every animation frame to match the retired custom Dialog.

## Verified migration evidence

See [physical-device captures and validation limits](design/material3/README.md) for #92. The user explicitly omitted spoken-output/TalkBack validation for this migration; programmatic native accessibility checks are separate evidence. Existing cloud-badge pixel validation remains tracked in #80.

## Expressive photo workflow (#112)

The approved Sites study (`9ba6c87`) supplies the hierarchy and entry points for the Android redesign. Native Android behavior, issue-defined contracts and the following requirements govern implementation; browser sample data or processing are not implementation authority.

- Library: photos, albums and camera import have one consistent bottom navigation. Import remains reachable without a connected camera, shows connection/progress when present, and keeps the explicit import/permission flow. Navigation is suppressed during photo selection. Photo/album scroll positions and committed filters survive return.
- Gallery: one heading with selection, filter and settings actions replaces the always-visible rank filters and action dock. Rank and camera filters are grouped in one native sheet, with a result count and explicit apply. Dismissal leaves the prior conditions unchanged; applied conditions remain visible and clearable. The single-photo rank rules from #109 and burst journals remain authoritative.
- Selection: exit and select-all sit above the photos; related tag/comparison actions are grouped separately from the primary share action. Counts, hidden selection, unresolved bursts, best-only sharing and bulk co-winner operations remain available. Selection tagging freezes the exact explicitly selected photo identities (including hidden selections), asks for tag confirmation, and uses the existing transactional tag batch store. Empty selection disables the action; failures keep the selected set and a visible retry; conflicts require reloading choices rather than silently overwriting another edit. Selection never becomes an implicit ranking action. Applied conditions remain visible during selection, and select-all explicitly names the displayed subset. Exit selection to change or clear the conditions so the selected set cannot silently change.
- Viewer: pen and sparkle directly enter the existing manual editor and Nano Banana workflow. Single-photo best toggles use committed store state, pending/failed/retry semantics and native checked accessibility from #109. Information, tags, zoom and previous/next actions remain accessible through a secondary action surface; swipe/pinch/Look/Before contracts remain intact. Photo, status and editor geometry must still fit narrow windows and font scaling.
- Sharing: JPEG and original format choices form one related group; one primary continuation opens the destination chooser. Existing configured destination formats, the 512 MiB gate, cloud download/preparation, cancellation, retry and handoff history semantics are preserved.
- Components: use native Material surfaces and managed button state layers. Primary and supplementary actions differ in containment and size. Standard touch targets are at least 48dp; text may wrap and content may scroll rather than shrinking labels. State must not depend on color alone. Respect reduced system motion, keyboard/AX focus and system insets.

Validate before/after Android captures and affected transitions at normal width/font1.0 and 320dp/font1.3, with independent UI/UX review. Synthetic/emulator evidence and physical-device evidence must be reported separately. A successful web prototype, build or capture does not establish device behavior, optical quality, AI processing or physical USB import.
