# Saved Looks in the photo viewer

## Purpose and agreed scope

Issue #69: save a tuned Look as a reusable named preset, apply it without entering the editor, and optionally save a separate JPEG. User confirmed both display application and optional JPEG saving. Existing CNT-like coefficients remain unchanged. The following contracts are implementation decisions within that scope, recorded before implementation.

## Recipe and identity

- Presets contain only RawEditLook: five curves, enabled flags, LUT bytes/strength, vignette, grain amount/shadow weighting/seed. No exposure, WB or other per-photo development values.
- Built-in CNT-like is always available. Up to32 named immutable user presets, names1–40 Unicode code points after trim, no control characters. Saving creates a new UUID; deletion removes only that library entry.
- Applied photographs own an immutable recipe/name snapshot, independent of preset deletion. Key uses ArchiveStore.key(photo); this existing metadata identity does not promise content identity after relocation/replacement.
- Persistent private storage is distinct from temporary RawEditLookStore sessions. Strict version/EOF/type/size validation, same-directory temporary file, fsync and atomic replace. Maximum4MiB per file and128MiB aggregate; failures retain prior valid data. No original image bytes, source URI or LUT document grant in saved recipe files.

## Rendering and transaction

- No Look restores the existing viewer image path, including RAW embedded JPEG. An applied Look develops the original RAW through existing RawEditSource, not the embedded preview. This distinction must be visible in the sheet for RAW.
- Fixed Look-only base is RawEditState.neutral().withLook(snapshot), including rolloff0. It preserves the accepted same-input CNT calibration without adding a hidden shoulder/exposure/WB adjustment. Saving an editor Look does not include that editor's photo-specific development values.
- Copy OriginalCache output into a separate bounded private input before transferring ownership to RawEditSource. Never give source ownership of an OriginalCache file.
- Reuse existing float RawEditRenderer, oriented-image UV/grain dimensions, full preparation and NativeJpeg95 MediaStore export. Keep preview/full shader and immutable export state shared. Prepared viewer output targets up to4000px long edge, capped12MP, matching existing viewer resolution bounds; initial preview may be2048px.
- Candidate becomes applied only after successful render and atomic selection persistence. Keep previous image/selection until then; failures show retry and retain both. Photo generation and Look revision gate every asynchronous callback, including old original-loader completions. Repeated selections cannot commit out of order.
- Clear removes the saved selection and returns to original display. A temporary Before toggle does not erase the saved selection. Navigation/recreation restores selections. Preview does not create a MediaStore image.
- Optional JPEG saves exactly the current recipe through full-resolution tiled export, without opening RawEditActivity. Failure/cancel leaves applied selection intact. Cancellation/publication uses existing FullSession linearization; published output cannot be reported cancelled. Navigation or recipe changes during saving wait for completion/cancellation outcome.
- Stale callbacks recycle their bitmaps; EGL/source/full caches close on owning workers after navigation/detach/destroy. Same-photo render replacements preserve zoom pose where possible. No changes to original sharing, ranking scores or thumbnails; applied Look is a viewer presentation.

## UI and verification

Claude design must cover viewer Look entry, selector (none/built-in/saved), applied state/Before, JPEG save busy/cancel/success/failure/retry, editor save-name dialog (valid/empty/limit/storage failure), deletion, recreation, short portrait and font1.3. Photos remain primary; retain existing dock controls and ordering except the requested entry. Readable names and status are accessible; no implementation details in product UI beyond the Look-only/RAW behavior that affects user decisions.

Host tests: full recipe/LUT roundtrip, bounds/corruption/atomic replacement, quota/failure preservation, deletion independence and photo identity. Device: saved Look from editor -> viewer apply without editor entry, old original loader race/rapid selection, page/recreate, Before/clear, full export/cancel/failure/source immutability, JPEG/RAW orientation and preview/export parity. Capture synthetic reference-aligned states; independent UI, Cold and Security review. Record selection input, recipe/render commit and the following postOnAnimation callback separately. The callback does not establish the first presented frame; end-to-end presented-frame latency remains unmeasured. No claims from cached-only timing.

## Status

Implementation and scoped runtime gates are complete. Normal0.25.0/code147 and experimental0.8.0/code11 were installed with device/host APK SHA256 equality. Baseline normal0.24.0/code146 viewer instrumentation passed. Store, look/temporary-store/state/PCHIP/tone host tests and144 structure checks passed; normal/experimental builds and lint passed with0errors (existing and text/RTL warnings remain).

Normal400dp/font1.0 and constrained400×640dp/font1.3 UI checks passed: editor recipe save without exposure/WB, selector/Before/delete, recreation, full JPEG output and source immutability. Backend checks cover rapid selection, stale original callbacks, corrupt-selection retry, explicit/background-close export cancellation, retained selection, outcome restoration and worker termination. Existing viewer, photo-edit and tag-detail regressions passed; external-editor launch/save itself was not exercised. Progress comments distinguish failed harness attempts and corrected assertions from successful gates; attempt8's erroneous complete record was explicitly corrected and is excluded from the evidence.

The opt-in actual14MB portrait DNG test passed through existing RAW development and the shared GPU Look path: upright3072×4080, bounded viewer preparation, saved recipe restore, unchanged source and verified owned-file/cache cleanup. It does not assess photographic similarity. One RAW selection took12,838ms to recipe/render commit and12,841ms to the subsequent animation callback; this is one input/condition, not first presented-frame latency or a speed comparison.

Independent Cold and Security reviews have no unresolved scoped findings after fixes. Final bounded test changes were independently rechecked with Luna when Astra was unavailable. Independent visual review passed for captured states, with small native/browser text-wrap differences, selector position differences (about5px normal/15px short) and fixture ordering differences. HTML uses an illustrative image, so its image colors/vignette are not rendering evidence. Actual finger operation, real software IME, TalkBack/audio and a physical short device remain unverified. These do not block the scoped automated static-photo PoC; there is no release or full Leica-equivalence claim.

## Completed UI reference (before UI implementation)

Claude completed loupe-viewer-look-presets.html (SHA256 00c3297cc099337109098e87db3ccb2f2046b793c89b03e7ee64ead31a8402f2). New Look control is top-right, preserving the existing dock; Before top-left. Selector dimensions, name dialog, loading/retry/delete and save/navigation confirmation are defined in that HTML. Original S08 and current source artifact were directly viewed before requesting and before implementation; only requested additions alter the new screen. Four matched-size reference PNGs are in docs/design. This establishes UI design, not Android validation.

## Use

In the editor open the Look sheet and choose プリセットとして保存. Only the current Look is saved; cancelling the name sheet leaves edits unchanged. In a photo viewer select the upper-right ルック control, choose built-in CNT-like or a saved entry; 補正前 temporarily displays the original. The selector offers JPEGで別に保存 for a separate full-resolution export. Existing share still shares the original. A deleted library entry leaves each photograph's independent applied snapshot intact. These flows were exercised on the device.

## Test process boundary

The opt-in RAW check supplies its image after APK installation; image bytes are never APK assets or committed artifacts. Its controlled Look input is that private fixture. It starts the normal MainActivity, so existing startup behavior may enumerate MediaStore metadata and refresh the local gallery-index.db (and normal browsing caches). It is not a fully isolated fixture-only application process. This existing authorized browsing behavior is accepted for the runtime check; source media are not edited and the test does not send/upload the photograph. Fixture input, original cache, embedded-preview .jpg/.json/.part and selection snapshot are independently removed and their absence verified after worker barriers. Native termination/cleanup failures prevent PASS and are reported rather than deleting files still in use.

## Viewer response (#71)

Normal0.25.1/code148 retains these rendering/storage/UI contracts and omits discarded previews, bulk-transfers float rows and optimizes native Debug pixel work. Viewer requests do not use editor gesture debounce/tile pacing; editor defaults remain unchanged. [Same-input frame timings, output equality and limits](viewer-look-performance.md) supersede the earlier single12.84s sample as performance evidence.
