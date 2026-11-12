# Material 3 UI contract

This document is the current visual and interaction contract for Loupe UI work. Issue-specific behavioral contracts remain authoritative for the behavior they define. Historical HTML prototypes and captures under `docs/design/` are retained as records; they are not current visual authority. Do not require Claude, an exported HTML prototype, or pixel equality with historical artifacts.

## Platform and components

Loupe uses Android Views with Java, Material Components for Android 1.13.0, a dark Material 3 theme, and `AppCompatActivity`. Use native Material 3 components for standard controls where they fit the existing purpose and interaction contract: `MaterialButton`, `TabLayout`, `MaterialSwitch`, and `MaterialAlertDialogBuilder`. Keep custom drawing for photo overlays, tone curves, and other controls whose direct manipulation is part of the existing behavior.

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
| Library destinations | `TabLayout` | Photo/album destination, selection lock, existing scroll/filter restoration |
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
