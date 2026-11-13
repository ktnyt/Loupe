# Expressive photo workflow — #112

The Android UI follows the operation hierarchy approved in the [Sites study](https://loupe-expressive-study.ktnyt.chatgpt.site) (`9ba6c87`). The current contract is [Material 3 UI](../../material3-ui.md#expressive-photo-workflow-112), together with the preserved [single-best contract](../single-best/README.md).

All photographs and tag/share labels in these captures are synthetic fixtures. No photo originals are included. Before/after images establish rendering under the stated conditions, not photo quality or processing accuracy.

## Captures and provenance

- `gallery-before-320-1.3.png`: pre-change main-equivalent emulator rendering. Its old regression continued to an expectation that predates #109; it is visual baseline evidence only.
- `expressive-{400-1.0,320-1.3}-{gallery,filter,selection,viewer}.png`: actual Android gallery, draft filter, selection and viewer. The selection condition is fixed above the photo grid, and select-all names the displayed subset.
- `expressive-*-manual-entry.png` and `expressive-*-ai-entry.png`: actual one-tap MainActivity viewer entries. Manual opens the existing internal editor with an app-owned input copy; AI opens the existing Nano Banana native flow. These checks return without saving or submitting.
- `share-320-1.3-menu-*.png` and matching `*-screen.png`: format/destination sheet, including the primary chooser and fixed-format saved destinations. Own-window PixelCopy and composed screen captures are taken after bottom-sheet bounds settle. `capacity-320-1.3-over.png` is a synthetic over-capacity state.
- `import-411-1.0-{collapsed,settings}.png`: actual import Activity at its observed emulator configuration, 411dp/font1.0. The separate 320dp/font1.3 native navigation fixture is not a narrow import-Activity capture.
- `selected-tags-*-{choices,failed,saved}.png` and `*-screen.png`: selected-photo tag picker states. These states include visible explanations and labeled recovery verified by real pointer taps within the usable display and ancestor clipping bounds.
- Pre-change viewer/single-best states remain in `docs/design/single-best/`.

## Verification boundaries

Physical phone connection is unavailable in this session, as confirmed by the user. Emulator operations, native accessibility attributes and captures are separate from physical-device behavior and spoken TalkBack. No real USB camera import, AI request, external sharing handoff, photo-original deletion or account-setting migration is performed by these synthetic checks.

Synthetic checks cover explicit singleton best state; draft/cancel/apply and initial-load filter counting; retained hidden selection; selected-photo tag transactions, rollback, labeled pointer retry, revision conflict, identity persistence failure and sparse revisions with 100 tags / 1,000 metadata-only photos; direct editor/AI entry; manual edit save and original preservation; share formats/capacity/cancellation; import/settings routes; video playback and affected layout states. Exact commands, attempts and final outcomes are recorded on [issue #112](https://github.com/ktnyt/Loupe/issues/112) under run `20261113-expressive-ui`.

The sparse-library navigation check verifies observed empty/small-list state and Back retention. Deep scroll restoration with sufficiently populated real albums/photos remains unverified. Runtime of the current-judgment scan on a densely tagged library is unmeasured. The existing cloud-badge pixel check remains tracked in #80.

The implementation checkpoints preserve resumable work. They do not establish physical-device completion. OTA publication and physical-device completion are separate. The remaining physical-device checks stay open on issue #112 after publication; emulator verification does not establish device installation or readback.
