# Measured CNT-like calibration

## Purpose and current contract

CNT-like uses an independently fitted color transform and brightness-protected vignette, measured from the same input rendered by Gallery CNT at maximum strength. It targets SDR photographic output comparable to the tested reference images. Engine correctness, numerical fit, independent visual assessment, Android execution and user preference are separate checks. Passing a synthetic chart alone does not establish photographic quality parity.

The frozen model is [gallery-cnt-v1.json](calibration/gallery-cnt-v1.json). It contains only measurements/model coefficients from generated charts, with no source photograph, location, camera APK, or vendor LUT asset. [RawEditCntCalibration](../app/src/main/java/jp/nano/rawgallery/RawEditCntCalibration.java) is a deterministic generated representation of its red-fastest 7³ controls. The existing factory bakes these controls into a 33³ float LUT. Generation is checked with:

```sh
python3 scripts/export-cnt-calibration.py --model docs/calibration/gallery-cnt-v1.json --output app/src/main/java/jp/nano/rawgallery/RawEditCntCalibration.java --check
```

## Calibration and evaluation

Generate a deterministic tagged sRGB JPEG chart. Its 17³ training lattice, grayscale samples, disjoint intermediate colors and repeated colors have explicit roles in the manifest. Export the exact same input through Gallery CNT at zero and maximum strength; do not substitute a RAW/CNT-JPEG pair. Record the actual UI selection and strength locally. Camera metadata FilterId 655610 maps to Contemporary in Camera 6.3.005740.1; 66048 maps to Original. These labels do not establish Gallery/Camera processing equivalence.

Gallery outputs in the measurements carried a P3 ICC profile. Convert source and target through their ICC profiles to SDR sRGB before measuring patch interiors; validate dimensions, orientation, geometry, finite values and sample separation. Zero-strength exports measure color-conversion and JPEG round-trip differences. Repeated colors expose spatial variation that cannot be represented by a global color LUT.

The deployed model fits a smooth regularized 7³ color lattice and a shared RGB spatial multiplier. It uses original chart inputs for the deployment path; zero-strength exports are a diagnostic. The spatial factor has a flat center and highlights receive less darkening. Brightness is an encoded-RGB luma proxy. It does not alter RAW exposure/WB/base tone mapping. LUT outputs may exceed [0,1]; clamp the brightness proxy for protection, but retain RGB values until after spatial multiplication and optional grain.

Charts A/B were development data. Chart C used a new layout after model parameters were frozen, and its intermediate colors were not fitted. A/B intermediate colors were used for model selection; do not call them untouched final test data. C tests layout/context robustness in addition to interpolation. Three photographic pairs were excluded from fitting and selection. No scene-specific exposure fitting or per-photo transform was used for those pairs.

| Frozen model test | Mean ΔE76 | Median ΔE76 | p95 ΔE76 |
| --- | ---: | ---: | ---: |
| Chart C, 512 off-grid colors | 1.6012 | — | 3.7605 |
| Plant, same-input Gallery CNT | 2.1194 | 1.9654 | 4.2063 |
| Building, same-input Gallery CNT | 2.5637 | 1.6136 | 8.1612 |
| Interior, same-input Gallery CNT | 2.0524 | 1.6512 | 4.8787 |

Photo measurements use all pixels at equal dimensions, with no registration, clipping/edge exclusion or error-dependent mask. The candidate is the serialized 33³ CPU model before final Android dithering/JPEG. The reference JPEG is ICC-converted to sRGB. JPEG differences and edge/chroma processing remain in the errors. The prior preset's corresponding median errors were 11.1680, 6.1878 and 6.2853. These values describe only these three same-input references, not all Camera photographs.

Independent assessment, with candidate identities hidden, selected the calibrated candidate for all three scenes and found no conspicuous new clipping, banding, objectionable noise or corner falloff at the supplied display scale. The building sky retained a slight cool difference. This is a bounded photographic assessment, not user preference or a claim about untested skin tones, HDR/P3 output, camera ISP or other phones.

## Run a new calibration and evaluate the frozen model

Research dependencies are separate from the Android runtime:

```sh
python3 -m venv /tmp/loupe-cnt-calibration-venv
/tmp/loupe-cnt-calibration-venv/bin/pip install -r scripts/requirements-cnt-calibration.txt
/tmp/loupe-cnt-calibration-venv/bin/python scripts/calibrate-cnt-look.py generate --output /tmp/cnt-chart --seed 67
# Supply the actual same-input Gallery zero/maximum exports:
/tmp/loupe-cnt-calibration-venv/bin/python scripts/calibrate-cnt-look.py fit --spatial --manifest /tmp/cnt-chart/manifest.json --neutral /path/zero.jpg --reference /path/cnt.jpg --selection-manifest /path/chart-b/manifest.json --selection-reference /path/chart-b/cnt.jpg --output /tmp/cnt-fit
/tmp/loupe-cnt-calibration-venv/bin/python scripts/calibrate-cnt-look.py evaluate --model docs/calibration/gallery-cnt-v1.json --manifest /path/held-out/manifest.json --reference /path/held-out/cnt.jpg --output /tmp/cnt-evaluation
/tmp/loupe-cnt-calibration-venv/bin/python scripts/calibrate-cnt-look.py apply --model docs/calibration/gallery-cnt-v1.json --input /path/input.jpg --output /tmp/cnt-result.png
```

Outputs must be new paths. `fit` without `--spatial` is a global-LUT diagnostic; it cannot represent the measured spatial falloff. A central `.cube` from spatial fitting requires its accompanying spatial model and is not the complete look when imported alone. Applying that cube to JPEGs already containing CNT would apply another grade.

The frozen model fits only chart A training/grayscale samples. Chart A and B off-grid colors select between predeclared brightness proxies by the mean of per-chart errors; chart B is not optimization data. The additional selection-pair arguments preserve that distinction. The public `fit --spatial` command is a new calibration workflow, not an exact replay of the historical fit that produced the frozen model. It initializes from a fresh global lattice and scores baked 33³ candidates; the historical experiment initialized from an earlier spatial fit and selected using unbaked 7³ predictions. Its coefficients therefore need not reproduce the frozen coefficients, even with the exact retained inputs. The frozen JSON is the production source of truth, and Java generation from that JSON is checked exactly. Retain the original chart/manifest and exported-reference bytes, bound by hashes, to reproduce reference evaluations. Regenerating a chart using only its seed is not a substitute across generator or image-library versions. Record dependency versions and model/source/output hashes for each experiment. If a held-out image drives another change, it becomes development data and requires a new test image.

## Remaining quality limits

- Chart C's largest residual, ΔE76 10.73, is a dark saturated blue-green. Global average improvements must not conceal such tails.
- Skin, additional illumination/subjects and Galaxy S25 Ultra input have not been assessed. Do not infer these from three other scenes.
- RAW base rendering remains camera WB, no auto bright and existing user controls. The look does not automatically match a camera JPEG's exposure or local tone mapping.
- Grain is disabled by default. The optional original deterministic grain remains available, retaining strength18/shadows80/seed65; the measured color residual is not interpreted as film grain.
- Output remains 8bit SDR JPEG through the existing full-resolution exporter. HDR and wide-gamut output remain outside the tested contract.

## Android photographic check

The same three inputs were decoded, processed and exported by the installed experimental Android app (0.7.0/code10), using the existing NativeJpeg quality95 exporter. Preview/full-resolution GPU differences were at most one code value, and input hashes were unchanged. Final exported JPEG median ΔE76 versus the same-input Gallery references was 2.0251 (plant), 1.6686 (building) and 1.6689 (interior); p95 was 4.2771, 4.2058 and 4.1737. Measurements include every pixel after ICC conversion at identical dimensions. These JPEG measurements are distinct from the CPU table above.

The user reviewed actual Gallery/Android building and plant comparisons and accepted this level of similarity. That acceptance freezes this preset for the PoC; it does not establish equivalence for Camera CNT, skin or untested devices. Private photographic originals and comparison images remain outside Git. Android operation, UI and delivery results are recorded in issue #67 and [the usage/verification document](cnt-look-poc.md).
