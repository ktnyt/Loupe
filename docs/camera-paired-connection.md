# Registered camera connection and import

Status: implementation added; Android emulator validation completed, physical end-to-end validation pending. The contract was recorded before implementation and extends QR registration to Wi-Fi as explicitly requested by the user.

## Purpose and requirements

Register camera information once, then use one visible action to wake the registered camera over BLE, acquire its Wi-Fi network and import only missing photos. The user does not type BLE or Wi-Fi credentials again. Android may require its own network approval; denial is a failed/cancelled connection, never import success. Import preserves the existing common Wi-Fi/SD content ledger, save verification, retry and original-preservation contracts.

## Credential contract

A complete encrypted profile contains exact Bluetooth name, Bluetooth passcode, exact SSID, QR security type (1 WPA2 or 2 WPA3), and Wi-Fi passphrase. Validate supported QR fields and byte bounds before replacement. Android passphrase requirements are enforced; unsupported credentials fail closed rather than downgrade security. No QR source, input image, plaintext file, logs, Intent extras or host output contains credentials. Android's request builder requires a transient String; complete erasure of library String copies is not promised.

Use the existing Keystore/AES-GCM no-backup atomic replacement, decrypted readback and rollback contract for all fields together. Existing BLE-only version-1 profiles remain readable and usable for their previous action; they are explicitly incomplete for automatic Wi-Fi and require one QR update. Do not delete or fabricate their missing Wi-Fi data. At most eight profiles. Opaque profile IDs are the only profile reference passed through service Intents. Exact name matching is required before reuse; profile deletion removes its Wi-Fi and BLE credentials together.

## Session contract

The visible Activity obtains Bluetooth, platform Wi-Fi/location and photo permissions, then starts one connected-device foreground-service operation by opaque profile ID. The service owns BLE scanning/wake, optional retained GATT, Wi-Fi NetworkCallback and import worker. Activity stop/recreation and OS approval dialogs do not release the service's network request. Explicit cancellation, service destruction, permission failure and bounded connection failure release resources and clear operation-owned credential arrays.

BLE ASIC-off recovery follows camera-ble-wake.md. For Bluetooth-mode Wi-Fi-start, retain GATT after response/ACK, wait 5500 ms, and retain it only through camera-network verification (maximum 180 seconds). The ordinary transfer-wake branch closes BLE and waits 3000 ms. Protocol acceptance is not Wi-Fi availability.

Request a WifiNetworkSpecifier for the exact registered SSID and the registered WPA2/WPA3 mode. Remove INTERNET capability for this local network; do not silently downgrade WPA3, select a prefix match, replace OS network credentials or call bindProcessToNetwork. The acquisition/probe deadline is 180 seconds, separate from import duration. onUnavailable fails without inventing a cause; onLost stops active camera I/O safely. Ignore callbacks from an ended session and unregister its request once.

Only report connected after the existing fixed-address VER_100 probe succeeds on the exact Network supplied by that request. Reuse that same Network-bound Ep7WifiClient for listing, downloads and verification; do not select another available network. Keep the request registered through import completion/failure/cancellation, then release it. Keep explicit-session callbacks separate from passive automatic-import watching and prevent duplicate imports. While another operation is running, retain at most one explicit registered-camera request; the latest requested profile/settings replace that pending slot and duplicate requests for the active profile do not add another import. Hand off after the current import safely ends, ahead of passive watcher requests. User cancellation and service destruction discard the pending request, and handoff must not clear a concurrent stop flag.

## Validation

Verify v1 compatibility, complete-profile roundtrip/restart, failed replacement rollback, tamper rejection and deletion with synthetic secrets. Verify exact SSID/security request construction, OS approval/denial, bounded cancellation and stale callbacks, the same Network for probe/import, network loss mid-file, Activity recreation and duplicate watcher suppression. Capture and independently review changed Android UI. Physical tests separately establish BLE acceptance, network verification and differential import; physical OFF conditions and OM-1 Mark II are separate evidence.

## Android UI contract

Follow docs/material3-ui.md. The Wi-Fi screen opens registered-camera connection; the QR screen shows empty, legacy-incomplete and complete registrations. A complete profile has one filled, at-least-48dp connect-and-import action with its camera name; legacy profiles retain their registration and show QR-update guidance. No password is rendered. Keep remove-registration confirmation and manual diagnostic operations, using outlined secondary wake actions. Initial OS approval is explained without promising its suppression. Request missing Bluetooth/Wi-Fi/location/photo permissions before starting the service, and make denial recoverable. Import progress and cancellation remain visible in CameraImportActivity while the service owns the session. Verify at 320dp/font1.3 and 400dp/font1.0, including scroll bounds and accessibility labels; static captures do not establish TalkBack speech or physical connection.
