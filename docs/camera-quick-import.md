# Quick import from the library (#126)

## Contract before implementation

A normal tap on Import opens the existing import screen. Long press and its accessibility action request import without deleting originals. Selection and active import suppress the action. Routing prefers an attached USB camera, then a persisted readable SD tree whose directory can actually be queried, then complete local QR registrations. One complete registration proceeds; several require explicit selection; none offers registration. Discovery reads local state only and must never connect to a camera.

Source discovery runs off the UI thread with a bounded deadline. A backgrounded or destroyed screen cannot launch a stale result. Runtime permission requests retain the chosen opaque profile or SD continuation across recreation, and continue once after approval. A consumed initial Intent must not replay after recreation. Existing service locking, ledger, originals, verification, retry and credential protections remain authoritative.

## Current behavior

The library opens the import screen with a consumed quick-import request. That screen resolves local sources and provides visible progress, cancellation/failure, registration guidance or an explicit camera chooser. SD reachability uses a cancellable document-directory query, not a persisted grant alone. QR profile names appear only in local UI; only opaque IDs cross the activity boundary. USB retains its no-delete quick flow. Pending USB permission requests persist the requested device ID, device name, vendor and product IDs; continuation verifies that identity and consumes the request before rendering or opening first-import setup. A disconnected or changed device requires a new explicit operation. SD and registered-camera services retain their original permission and import paths.
