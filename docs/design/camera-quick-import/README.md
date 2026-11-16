# Android capture record for #126

Captured on the dedicated emulator on 2026-11-16 JST from the implementation in 9edec05. The Activity configuration/window width and font scale are 320dp/1.3 and 400dp/1.0; the PNGs contain the composed Android display, including OS regions. Camera names are synthetic fixtures, and no QR or password is displayed.

The unregistered state offers camera registration, cancellation and direct SD-folder selection. The chooser requires an explicit choice between two complete registered camera profiles. `CameraQuickImportChecks` exercises the actual SD button and intercepts `ACTION_OPEN_DOCUMENT_TREE` before the picker opens; it also intercepts camera handoff before radio operations, checks opaque-ID-only routing and verifies recreation does not reconnect. `CameraTabsChecks` exercises the real library long-press and confirms duplicate gestures emit one preserved-original quick request.

Independent UI review accepted all four final captures after adding the direct SD action. Physical-device behavior, actual picker selection, touch dimensions, TalkBack speech and focus traversal are separate checks. These images are verification records; the current UI contract remains `docs/material3-ui.md` and issue #126.
