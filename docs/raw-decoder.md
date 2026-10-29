# Native RAW decoder

`NativeRaw.decode(privateCopyPath, maxSide)` uses LibRaw 0.22.3 to unpack sensor data and demosaic it on the device. It never requests an embedded thumbnail. Input must be an app-private temporary copy; the caller owns copy creation/removal and runs decoding outside the UI thread.

## Source and build

The unmodified official source archive is committed at `third-party/libraw/LibRaw-0.22.3.tar.gz`:

- Source: https://www.libraw.org/data/LibRaw-0.22.3.tar.gz
- SHA256: `789f3c33466210a1a0907f747a97c715d21bbf4af712f257fcbbd90111b227e4`
- Selected license: CDDL 1.0 (`third-party/libraw/LICENSE.CDDL`), upstream copyright and per-file notices remain in the archive. Source for this component is distributed with the app source.

CMake checks this hash and extracts the archive into its build directory. No source download occurs during a normal build. The source list follows upstream `Makefile.am`, excluding alternative Phase One processing units. NDK `27.0.12077973`, CMake `3.22.1`, Android API30+, `arm64-v8a`, static C++ runtime. Output: `libloupe_raw.so`, linked with 16 KiB load-segment alignment for Android devices with 16 KiB memory pages.

OpenMP, LCMS, RawSpeed, DNG SDK, GoPro SDK, JasPer, and external JPEG are disabled. Internal lossless-JPEG DNG decoding and zlib remain enabled. Lossy-JPEG DNG, JPEG XL/DNG SDK formats and unsupported cameras fail visibly instead of using a preview.

## Pixels and limits

Camera white balance, `half_size=1`, `no_auto_bright=1`, sRGB primaries, linear gamma (`gamm[0]=gamm[1]=1`), RGB16 output for color sensors; monochrome LibRaw output is accepted as one-channel 16-bit and replicated equally into R/G/B after the same linear resize. Other channel counts are rejected. LibRaw applies orientation once when producing its memory image. A bilinear resize in linear space produces RGBA FLOAT32 with alpha=1. RGB is normalized to 0..1; interpolation roundoff is clamped to that range. LibRaw's 16-bit processing clips highlights before this buffer, so later adjustments cannot recover those highlights.

`maxSide` must be 256..2048; the result never enlarges the decoded image. Input must be a regular file, >0 and <=256 MiB. Both raw sensor and visible dimensions must be positive, <=16384 per axis and <=80 million pixels. LibRaw's raw allocation limit is 256 MiB; this is not a total process-memory cap. The oriented RGB16 copy and up to 64 MiB Java direct buffer exist briefly together with native processing buffers.

The result owns a Java-allocated direct ByteBuffer in native byte order, row-major RGBA, 16 bytes per pixel. Native code holds no persistent handle and frees the LibRaw buffers before returning. Failures use fixed exception strings without input path, camera metadata, or dependency exception text. No photo content is logged. LibRaw-reported data errors reject the image, including cases where the library could otherwise return a partial decode. LibRaw's progress callback observes Java thread interruption; interruption is cooperative, and resize/certain parser stages can finish before it is observed. Callers must also suppress stale UI results.

## Checks

Host smoke checker is built from the same decoding core:

```sh
cmake -S app/src/main/cpp -B /tmp/loupe-raw-host -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/loupe-raw-host
/tmp/loupe-raw-host/raw_decode_check --self-test
/tmp/loupe-raw-host/raw_decode_check /path/to/local/sample.raw
/tmp/loupe-raw-host/raw_decode_check /path/to/local/sample.raw --cancel
/tmp/loupe-raw-host/raw_decode_check /path/to/local/monochrome.raw --mono
```

It emits dimensions and aggregate pixel range only. Local photo fixtures remain outside Git. Host core checks passed on eight local DNG and six ORF files (finite RGB, alpha=1, bounded dimensions). A host JVM check of the exact JNI bridge passed direct-buffer layout/native byte order and pixels for one DNG and one ORF, interrupted-thread cancellation and path-free error text. Invalid maxSide/non-regular input and native progress-callback cancellation were also checked. Android arm64 native shared-library compilation passed. The monochrome follow-up passed a local one-channel RAW decode with exact R=G=B, alpha=1 and bounded finite pixels; the previous eight DNG/six ORF checks also passed. Truncated monochrome input still failed, callback cancellation passed, and the source hash stayed unchanged. Android verification of this follow-up is separate.

Host checks do not establish Android JNI behavior, physical-device performance, display color accuracy, visual orientation correctness, or commercial RAW quality; those need separate on-device checks.
