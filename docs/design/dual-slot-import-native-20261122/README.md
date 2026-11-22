# Dual-slot import confirmation Android captures

Native Android15 emulator, isolated `jp.nano.rawgallery.sd162`, `camera-pilot-dual-slot-ui` at400dp/font1 and320dp/font1.3. The dialog uses two synthetic cards with the same display name and different UUIDs. JPEG-only, RAW-only and VIDEO-only initial choices are captured. No physical USB reader is modeled here.

The fixture checks distinct source/destination IDs, selection, visible video limitation, disabled empty confirmation,48dp checkbox targets and cancellation. It invokes the real confirmation dialog after simulated source validation and never presses the positive action. Import progress and settings remain unchanged. It does not verify folder selection, a physical transfer, full-byte equality on SD, Android rotation during USB permission, or TalkBack audio.

Material3/current contracts remain authoritative; these are historical verification captures.
