# Viewer Look response

## Purpose and pre-implementation contract (#71)

Reduce the user's wait on every RAW Look application, including a new viewer/controller. Maintain #69 recipe, RAW quality, linear/encoded color boundaries, precision, output resolution and original protection. Same-controller repeats are a separate condition; no warm-cache result represents a fresh application.

Input→Android frame commit measures rendering/submission, not physical display scanout. Stage events distinguish private original acquisition/copy, initial decode, full preparation, generated preview, tiled detail and persistence. Numeric labels only, no photographic identifiers. Existing UI and Claude reference remain unchanged.

## Baseline evidence

Measurement-only normal0.25.0/code147, device25128PNA1G, real14MB portrait DNG, CNT-like, output3005×3992 (existing4000px/12MP cap), three fresh controllers with fixture original cache removed and three same-controller repeats. OS page cache and JIT are not reset. Fresh frame:19.42/21.19/18.83s; repeat:7.17/7.07/7.13s. All six pixel digests identical (retained privately). Source bytes unchanged, worker barriers/owned-input cleanup succeeded. Post-test standby hold failed separately, so the overall baseline phase is recorded fail; successful measurement evidence is retained explicitly.

Full preparation:7.86–7.93s. Discarded initial RAW decode:1.82–4.29s. Unused full-source preview:1.56–1.89s. Detail:6.89–7.49s including per-tile scheduling. Copy/original acquisition under0.1s each. Current native Debug compile has no optimization; full-region conversion writes each float separately into a direct ByteBuffer.

## Implementation decisions (before optimization)

- Give viewer-owned sources a metadata-only opening path, using the existing LibRaw identify operation for authoritative oriented dimension estimates; full native preparation retains all existing quality and validation settings. Editor preview creation remains unchanged.
- Let viewer FullSession omit the unused2048px preview. Keep the editor's default behavior.
- Bulk-write each converted float row in FullSource.region with the same division and channel replication. Preserve bounded regions, per-row cancellation and file ownership; no JNI conversion or precision change.
- Enable compiler optimization for native pixel work in Debug, retaining symbols and strict floating-point semantics (no fast-math). Verify final output against the baseline, rather than assume compiler equivalence.
- In viewer mode, omit editor gesture debounce and16ms per-tile pauses. Each tile remains a separate bounded job with generation/cancellation checks; editor scheduling is unchanged.
- No persistent photographic cache or lowered preview resolution. Existing source/preset identity and disk quota do not change.

## Verification

Pending optimization and same-input after measurements. JPEG remains a functional/output regression check; the user's reported operation is RAW application each time. Actual IME/TalkBack and physical display scanout are outside this non-layout performance change.
