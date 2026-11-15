# Legacy look preservation fixture

This fixture was written by the legacy storage APIs from baseline commit `d9b83eda6646f725ac6907b9056c82c53746a95c` (short name `d9b83ed`). It contains synthetic recipe data only; it contains no photographs, credentials, or real application data.

## Regeneration

The host-only generator and extracted baseline Java sources are kept under `/private/tmp/loupe119-fixture-generator/`. For regeneration, first use an empty output directory; the generator refuses a non-empty directory. From the repository root, the baseline sources were extracted with:

```sh
for f in RawEditState RawEditCurve RawEditTone RawEditLook RawEditCube RawEditCntCalibration RawEditLookStore LookPresetStore; do
  git show "d9b83ed:app/src/main/java/jp/nano/rawgallery/$f.java" > "/private/tmp/loupe119-fixture-generator/$f.java"
done
javac -d /private/tmp/loupe119-fixture-generator/classes /private/tmp/loupe119-fixture-generator/*.java
java -cp /private/tmp/loupe119-fixture-generator/classes \
  jp.nano.rawgallery.GenerateLegacyLookFixture "$PWD/app/src/androidTest/assets/legacy-look"
```

The generator uses `LookPresetStore(new File(output, "look-library"))`, saves the valid `RawEditLook.cntLike()` recipe named `Preserved legacy`, and saves its per-photo selection under key `fixture-photo-key`. It writes a session using `RawEditLookStore.save(...)` with current, saved, and custom recipes. It then reads back the preset, selection snapshot, and session through the baseline APIs and asserts that they round-trip.

## Fixture files

- `look-library/preset-cba904e9-01a3-4d75-9199-dbdaccfb5f3d.look`: one saved `Preserved legacy` recipe.
- `look-library/photo-787b75a1a4c1436267c24179e8dc7e674c961bf02b9d4262e962bc6f945d8c47.look`: one snapshot selected for `fixture-photo-key`.
- `look-sessions/00000000-0000-0000-0000-000000000119.look`: one session; token `00000000-0000-0000-0000-000000000119`.
