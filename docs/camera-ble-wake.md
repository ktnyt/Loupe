# Experimental camera BLE transfer wake

## Purpose and boundaries

Allow a user to select a nearby compatible camera, supply its displayed Bluetooth passcode, and request access for Wi-Fi importing. BLE protocol acceptance is separate from Wi-Fi readiness and from successful importing. No background auto-wake, camera reset, shutter operation, pairing bypass, or credential extraction is implemented.

E-P7 and OM-1 Mark II are experimental targets. An E-P7 has accepted a BLE transfer-wake operation and exposed Wi-Fi without a physical camera operation; the device evidence is recorded in issue #124. Exact power-switch state, firmware version, negative-passcode behavior and controlled standby/disconnection coverage remain unverified. OM-1 Mark II has not been physically tested. A matching advertisement identifies a candidate, not an authenticated camera. The user must select it and provide the passcode from that camera.

## Official operating constraints

- E-P7: pair through OI.Share using its QR code or displayed Bluetooth name/passcode and Wi-Fi credentials. Power-off image access requires Wireless Functions and Power-off Standby enabled. See the [official manual, pp.156–159](https://download.omsystem.com/pages/inst/ep7/manual_ep7_ENU.pdf).
- OM-1 Mark II: [pairing](https://learning.omsystem.com/OM-1MarkII/zz_html_manual/en/pairing_smartphone_wifi_274.html), [Bluetooth standby](https://learning.omsystem.com/OM-1MarkII/zz_html_manual/en/standby_setting_camera_on_275.html), and [Power-off Standby](https://learning.omsystem.com/OM-1MarkII/zz_html_manual/en/wireless_settings_camera_off_276.html) are distinct settings. Smartphone and remote-controller standby are different modes.
- Both manuals describe standby ending after 12 hours of inactivity, card removal, battery replacement, or an onboard charging error. Turning the camera on restores availability. This feature does not promise wake after those conditions or full photographic power-on.
- AIR A01's older Olympus Camera Kit wake API is not evidence of E-P7 or OM-1 Mark II protocol compatibility.

## Protocol evidence

Constants and behavior were independently described from the installed official [OM Image Share Android app](https://play.google.com/store/apps/details?id=com.omdigitalsolutions.oishare), package `com.omdigitalsolutions.oishare`, version `1.5.1` (160), APK SHA-256 `668275cfab852cda5ec38a5b3686f12f61f92cda023dcdfc45ef99563cb3da34`. Only the APK was inspected; no official-app user data or credentials were read. Decompiled proprietary implementation is not included in this repository.

The inspected shared path contains no E-P7/OM-1 Mark II model branch. Static app evidence is not a physical-camera compatibility result.

| Role | UUID |
|---|---|
| Service | `ADC505F9-4E58-4B71-B8CA-983BB8C73E4F` |
| Command write and receipt notification | `82F949B4-F5DC-4CF3-AB3C-FD9FD4017B68` |
| Response notification and ACK write | `B7A8015C-CB94-4EFA-BDA2-B7921FA9951F` |
| Event notification | `05A02050-0860-4919-8ADD-9801FBA8B6ED` |
| Client Characteristic Configuration Descriptor | `00002902-0000-1000-8000-00805F9B34FB` |

Notification descriptors are enabled sequentially for command, response and event characteristics. The helper requires notification and write-with-response properties, serializes operations, and checks descriptor/characteristic write completion status.

Outgoing command frame, for payload length `n`:

```text
01 sequence n+3 family 01 subcommand payload checksum 00
checksum = (family + 1 + subcommand + sum(payload bytes)) & FF
```

The final zero is explicitly zero, not the upper byte of the checksum. Authentication uses family `0C`, subcommand `02`, and UTF-8 bytes of the user-supplied passcode. Transfer wake uses family `0F`, subcommand `01`, payload `02`:

```text
01 sequence 04 0F 01 01 02 13 00
```

The command frame stays within 20 ATT payload bytes; passcodes are 1–12 UTF-8 bytes. No MTU expansion or packet fragmentation is implemented. Passcodes are never logged or persisted. The helper copies the caller's `char[]`, clears its own characters after encoding, and erases the sensitive frame after the write callback or termination. The caller remains responsible for clearing its array and the input field. Application-level passcode transport is visible in the packet; BLE link encryption/bonding behavior has not been established.

A command receipt starts with `05`. A response starts with `04`; family, subcommand and status occupy bytes 3, 5 and 6. The response is acknowledged with `02 response-sequence 00 00 00` on the response characteristic. Authentication succeeds only for status 0. The official app accepts wake status 0 or 1; their distinct meanings remain unknown.

Only proven response fields are validated. Incoming frames must be 7–20 bytes for a response (1–20 for a receipt). These bounds prevent unsafe indexing and do not establish the meaning of the response length byte. Response checksum/trailer semantics and equality of request/response sequence numbers are unverified; this implementation does not invent either check. Consequently it cannot detect every corrupted or stale response, including a stale same-command response with otherwise valid fields. Only one command is outstanding, and a response for another family/subcommand is rejected. Actual Wi-Fi readiness must still be independently checked before importing.

Manufacturer advertisement data is eight bytes including company ID `04D0` or `09F1`; Android removes that two-byte prefix. The next little-endian value is 1. Flags are at index 3 of Android's six-byte manufacturer data: `01` ASIC-on, `02` share, `04` pairing, `08` Bluetooth mode, `20` location mode. Other bytes are not interpreted as a model ID. The official app skips the power-on command after successful authentication in Bluetooth or location mode; the helper follows that behavior. With Bluetooth flag `08`, it instead sends Wi-Fi-start family `1D`, subcommand `01`, payload `02` after authentication, accepting only status 0. With location flag `20` and no `08`, it sends no additional command and returns `LOCATION_MODE_UNVERIFIED`. Ordinary flags (including zero) select transfer wake.

## Integration API

`CameraBleWake(Context)` uses application context. All observer callbacks run on the main thread. Retain the same helper from scan through selection and wake.

- `scan(ScanObserver)` returns `Closeable`, scans for at most 15 seconds, and emits `onCamera(BluetoothDevice, String displayName)` only for matching service/manufacturer data. It deduplicates at most 32 candidates. It finishes with `onFinished()` or `onError(Outcome)`.
- `wake(BluetoothDevice, char[], Observer)` returns `Closeable`. The selected device must have been observed by this helper in the last 60 seconds. Otherwise it returns `UNSUPPORTED_CAMERA`; rescan before retrying.
- `Observer.onFinished(Result)` reports `outcome`, `cameraStatus` (-1 if unavailable), and `wakeCommandSent`. The boolean records an attempted wake-command write, not delivery confirmation. `PROTOCOL_ACCEPTED` means authentication and the selected transfer-wake or Wi-Fi-start response plus ACK completed. It does not mean that Wi-Fi is ready.
- Outcomes distinguish authentication rejection, protocol error, timeout, disconnection, cancellation, missing permission, unavailable Bluetooth, unsupported/stale camera selection, unverified location-only mode and invalid input.
- Close the scan handle before wake and close active handles when the UI leaves the foreground. Closing stops scanning or disconnects/closes GATT, removes session timers and erases helper-owned credentials. No process-global callback or credential storage is used.
- The Activity requests permissions. On Android 12+ the helper checks Bluetooth scan/connect permissions; on Android 11 scanning checks fine location. It never enables Bluetooth, changes pairing, or alters security settings.

Connection has a 20-second timeout; discovery and each descriptor write have a 10-second timeout. Authentication, including its response ACK, has a 10-second timeout. Wake, including its ACK, has a 20-second timeout. Cancellation and disconnection terminate the session without an automatic retry.

The helper retains one local result in `camera_ble_diagnostics`: closed-enum outcome/reason/stage/operation, subscription index, numeric GATT and camera status, notification role and byte count, attempted-wake flag and monotonic elapsed milliseconds. It replaces the previous record. It never records credentials, frames, device identifiers or exception messages. Discovery callback timing is also logged, including callbacks arriving after termination; this distinguishes a late callback from a callback accepted by the session.

The ASIC-off transition is unresolved: the official app delays before connecting and may rely on rescanning/reconnecting before discovery. This helper makes one bounded connection/discovery attempt; it does not implement guessed hardware-wake writes or automatic retries. Failure asks for a fresh explicit attempt or manual camera activation.

## Validation

`sh tests/camera-ble.sh` covers fixed outgoing packet vectors, outgoing length/checksum corruption, UTF-8 bounds, malformed response bounds, operation matching, status handling, response ACK bytes and manufacturer flag parsing. These are host tests, not camera protocol capture tests.

Android helper compilation can be checked with the project build. Host compilation does not validate Android callback ordering, permissions, cancellation races, radio behavior or background restrictions. Before either target model is described as supported, controlled tests must establish service properties, correct and incorrect passcodes, cancellation/disconnection, standby/off-state transitions, wake response and actual Wi-Fi readiness. Keep E-P7 and OM-1 Mark II results separate by firmware version.

## Bluetooth-mode Wi-Fi start evidence

The verified Wi-Fi-start vector is `01 sequence 04 1D 01 01 02 21 00`. It uses the same response/ACK framing and 20-second helper timeout as transfer wake. `wakeCommandSent` includes an attempted Wi-Fi-start write. Authentication failure never schedules either command. Protocol acceptance remains separate from Wi-Fi readiness; this helper does not connect to Wi-Fi or claim that the radio is ready.

In the inspected APK, `N2/a.java` maps advertisement bit `08` to `M2.a.l()`, exposed by the connection manager's `isBluetoothFlag`. `BlePowOnActivity` checks this flag after connection/authentication success and only then calls `M2.b.M(2, 10000)`, which sends family `1D`/subcommand `01`. Status 0 schedules Wi-Fi connection after 5500 ms; other statuses fail. The ordinary branch closes BLE and waits 3000 ms. These waits are official-app behavior, not measured camera readiness or delays implemented by this BLE-only helper.

A separate public source, [db_link CameraBleManager](https://github.com/azqeurio/db_link/blob/33b1f7e3d311967ed17a675a8561748977623fe3/app/src/main/java/dev/dblink/core/bluetooth/CameraBleManager.kt), uses the same UUIDs, authentication, outgoing checksum, ACK and Wi-Fi-start command. Its [MIT license](https://github.com/azqeurio/db_link/blob/33b1f7e3d311967ed17a675a8561748977623fe3/LICENSE) covers that project. Loupe implements protocol facts independently rather than copying its implementation. db_link unconditionally queues power-on then Wi-Fi-start for reconnection; Loupe follows the more specific APK flag condition. Its [compatibility table](https://github.com/azqeurio/db_link/blob/33b1f7e3d311967ed17a675a8561748977623fe3/README.md) marks E-P7 and OM-1 Wi-Fi/import/source switching Tested, and OM-1 Mark II Expected. This does not separately establish power-off BLE wake testing, and it does not establish Loupe compatibility on either target model.

Host tests cover the Wi-Fi-start vector/status, Bluetooth-mode branch including combined `08|20`, ordinary zero/ASIC-on flags, location-only handling and rejected authentication producing no next command. Controlled target-specific callback, cancellation and standby-transition coverage remain device-test requirements.
