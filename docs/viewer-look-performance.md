# Viewer Look response

## Purpose and pre-implementation contract (#71)

Reduce the user's wait on every RAW Look application, including a new viewer/controller. Maintain #69 recipe, RAW quality, linear/encoded color boundaries, precision, output resolution and original protection. Same-controller repeats are a separate condition; no warm-cache result represents a fresh application.

Input→Android frame commit measures rendering/submission, not physical display scanout. Stage events distinguish private original acquisition/copy, initial decode, full preparation, generated preview, tiled detail and persistence. Numeric labels only, no photographic identifiers. Existing UI and Claude reference remain unchanged.

## Baseline evidence

Measurement-only normal0.25.0/code147, device25128PNA1G, real14MB portrait DNG, CNT-like, output3005×3992 (existing4000px/12MP cap), three fresh controllers with fixture original cache removed and three same-controller repeats. OS page cache and JIT are not reset. Fresh frame:19.42/21.19/18.83s; repeat:7.17/7.07/7.13s. All six pixel digests identical (retained privately). Source bytes unchanged, worker barriers/owned-input cleanup succeeded. Post-test standby hold failed separately, so the overall baseline phase is recorded fail; successful measurement evidence is retained explicitly.

Full preparation:7.86–7.93s. Discarded initial RAW decode:1.82–4.29s. Unused full-source preview:1.56–1.89s. Detail:6.89–7.49s including per-tile scheduling. Copy/original acquisition under0.1s each. The baseline native Debug build has no optimization; full-region conversion writes each float separately into a direct ByteBuffer.

## Implementation decisions (before optimization)

- Give viewer-owned sources a metadata-only opening path, using the existing LibRaw identify operation for oriented dimension estimates (the prepared cache remains authoritative); full native preparation retains all existing quality and validation settings. Editor preview creation remains unchanged.
- Let viewer FullSession omit the unused2048px preview. Keep the editor's default behavior.
- Bulk-write each converted float row in FullSource.region with the same division and channel replication. Preserve bounded regions, per-row cancellation and file ownership; no JNI conversion or precision change.
- Enable compiler optimization for native pixel work in Debug, retaining symbols and strict floating-point semantics (no fast-math). Verify final output against the baseline, rather than assume compiler equivalence.
- In viewer mode, omit editor gesture debounce and16ms per-tile pauses. Each tile remains a separate bounded job with generation/cancellation checks; editor scheduling is unchanged.
- No persistent photographic cache or lowered preview resolution. Existing source/preset identity and disk quota do not change.

## Verification

The controlled recheck uses the retained measurement-only0.25.0/code147 APK and final0.25.1/code148, device25128PNA1G. Same real14MB portrait DNG/CNT-like/output3005×3992, three fresh-controller/original-cache-miss measurements and three same-controller repeats per version. OS page cache/JIT are not flushed; this is not cold device/process startup. Video is paused/stopped. Battery temperature: before30.4→30.4°C; after30.6→30.7°C. Private source SHA256 is unchanged; all six final full-bitmap pixel digests equal all baseline outputs exactly. No photos/hashes are included here. [Numeric results](viewer-look-performance-results.json).

| Selection → GPU frame commit | Before, seconds | After, seconds | Median reduction |
| --- | --- | --- | --- |
| Fresh controller/cache miss |21.965 /18.770 /18.687 |4.064 /2.739 /2.749 |18.770→2.749s (85.4%) |
| Same-controller repeat |7.217 /7.191 /7.123 |1.358 /1.449 /1.386 |7.191→1.386s (80.7%) |

The first optimized sample still takes about4s; these are one image/device and three samples, not a guarantee for all RAW formats/sizes. No prepared-image disk cache is introduced. The accepted CNT recipe, decode settings, output bound and full-resolution export remain unchanged.

Normal and experimental app/test builds and lint passed (0errors; existing warnings remain). Host commands `sh tests/raw-full-source.sh`, `sh tests/look-preset-store.sh`, `sh tests/raw-edit-look.sh`, `sh tests/raw-edit-look-store.sh`, `sh tests/raw-edit-state.sh` passed. Region checks include arbitrary16bit gray/RGB values bit-for-bit, reused buffer position/limit and cancellation.

Final actual RAW check also verifies cooperative cancellation: direct JNI identification must stop at a LibRaw progress checkpoint under injected thread interruption; a separate factory check verifies owned-copy removal. A private APK with only that progress handler removed was rejected at the exact required assertion, then the production source was restored byte-for-byte and final APKs reinstalled. Upstream cancellation gaps between checkpoints remain unchanged. Controller-owned input/full cache absence is asserted after GL/PREPARATION barriers; fixture source bytes remain unchanged.

Experimental full/Look GPU/native checks passed: both JPEG source factories across all8 EXIF orientations, invalid/dimension-limit cleanup, tiled/untiled output and halo, detail/export/cancellation/publication behavior and Look invariants. Final normal viewer Look/UI and existing photo-edit regressions passed: editor preset save, viewer restore/Before/delete, actual separate JPEG output, explicit/background-close cancellation, dirty Back/recreation and source immutability. External-editor read-only preparation/chooser guards passed; actual third-party editor saving was not exercised. Results are recorded in issue71. Independent Cold/Security re-reviews found no unresolved scoped findings after fixes. Reviewers performed static checks; device measurements were performed by the primary agent.

Reproduce timing through `scripts/device-check.py --serial SERIAL --mode viewer-look-timing-raw --width 400 --font 1.0 --no-standby-video REASON` after supplying the approved DNG privately as `files/viewer-look-raw-fixture.dng` in the normal app. It removes that fixture after completed worker cleanup; do not use a personal source file as the owned test path. The JPEG timing mode creates a private4000×3000 synthetic JPEG; no JPEG speed claim is made here.

Actual IME/TalkBack, physical short-device layout and physical display scanout are outside this non-layout performance change. There is no new visual layout, Claude design change, RAW decoder or dependency. Public app release is not included.
