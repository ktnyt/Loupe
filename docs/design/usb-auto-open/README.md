# USB launch opt-in

Native Android emulator full-display captures at320dp/font1.3 and400dp/font1.0.
Source a46f911; subsequent uncommitted changes at capture time were test-only update-choice checks.

The new switch controls the PackageManager alias, independently of the retained auto-transfer
preference. Off removes Loupe from USB attachment intent candidates. On enables the alias;
the dependent transfer switch retains its prior value. Current manual import stays available.

The old-version resolver baseline contained CameraAttachActivity. The updated APK had no USB
candidate before first app launch. Native UI off/on/recreation/off and actual SD filter XML checks
passed. Explicit on/off each survived signed same-package APK update with installed hash readback;
the test restored manifest DEFAULT off. Independent visual review inspected all six generated
full-display captures (including off again); no actionable finding within this fixture scope.

No physical SD dialog screenshot, OEM behavior, TalkBack or actual phone OTA application is claimed.
